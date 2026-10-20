package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionFailureSettlementTest {
    @Test
    fun retiredCleanupFailureDuringRetirementSettlesAndRearmsForBothBuffers() {
        assertRetiredCleanupFailureSettles(holdLease = false)
    }

    @Test
    fun retiredCleanupFailureAfterLastLeaseSettlesAndRearmsForBothBuffers() {
        assertRetiredCleanupFailureSettles(holdLease = true)
    }

    @Test
    fun failedMarkerRemovalStaysOwnedThroughSealingAndTerminalCloseStillAttemptsIt() {
        withRetiredStore(looping = false, kind = CleanupFailure.MARKER_REMOVAL) { store, _, failure, _ ->
            val queues = Queues(store)
            failure.enabled = true
            queues.configureAndSchedule(4_096L)
            queues.drain()
            assertEquals(1, failure.attempts)
            assertEquals(1, queues.failures)
            assertRetiredCleanupOwned(store, 2u, claimPending = false, removalPending = true)

            store.sealActiveChunk()
            store.checkpoint()
            assertEquals("Failure sealing/checkpoint retried deferred cleanup", 1, failure.attempts)
            assertRetiredCleanupOwned(store, 2u, claimPending = false, removalPending = true)

            assertThrows(IOException::class.java) { store.close() }
            assertTrue("Terminal close skipped its durability attempt", failure.attempts > 1)
            assertRetiredCleanupOwned(store, 2u, claimPending = false, removalPending = true)
            failure.enabled = false
        }
    }

    @Test
    fun deferredCheckpointAndIdReuseStillRequireUnownedStaleMarkerBarrier() {
        withRetiredStore(looping = false, kind = CleanupFailure.MARKER_REMOVAL) { store, root, failure, _ ->
            val nextId = PersistentAudioChunkStore::class.java.getDeclaredField("nextChunkId")
            nextId.isAccessible = true
            nextId.setInt(store, 7)
            File(root, "retired").apply { mkdirs() }.resolve("7").writeText("v1|7|1|8000|1|PCM_16")
            failure.enabled = true
            assertThrows(IOException::class.java) { store.append(ByteArray(2), 0, 2) }
            assertEquals(1, failure.attempts)
            assertFalse(File(root, "chunks/7").exists())

            assertThrows(IOException::class.java) { store.checkpoint() }
            assertEquals("Deferred checkpoint skipped an unowned stale-id barrier", 2, failure.attempts)
            assertFalse(File(root, "chunks/7").exists())
            failure.enabled = false
            assertEquals(2, store.append(ByteArray(2), 0, 2))
            assertEquals("ID reuse omitted the pending directory barrier", 3, failure.barriers)
        }
    }

    private fun assertRetiredCleanupFailureSettles(holdLease: Boolean) {
        for (looping in listOf(false, true)) {
            for (kind in CleanupFailure.values()) {
                withRetiredStore(looping, kind) { store, root, failure, parts ->
                    val lease = if (holdLease) requireNotNull(store.acquireRange(0.0, store.durationSeconds())) else null
                    val queues = Queues(store)
                    failure.enabled = true
                    queues.configureAndSchedule(4_096L)
                    queues.drain()
                    if (lease != null) {
                        assertEquals(0, failure.attempts)
                        assertEquals(0, queues.failures)
                        lease.close()
                        assertEquals("Last lease release performed cleanup I/O", 0, failure.attempts)
                        queues.schedule()
                        queues.drain()
                    }
                    assertEquals(1, failure.attempts)
                    assertEquals(1, queues.failures)
                    assertEquals("Controlled $kind durability failure", queues.lastStoreFailure?.message)
                    assertEquals(1, queues.seals)
                    assertFalse(queues.state.isActive())
                    assertFalse(queues.foreground)
                    assertTrue(queues.delayed.isEmpty())
                    assertEquals(4_096L, store.countFilledBytes())

                    val retiredId = if (looping) 0u else 2u
                    assertRetiredCleanupOwned(store, retiredId,
                        claimPending = kind == CleanupFailure.CHUNK_DELETION,
                        removalPending = kind == CleanupFailure.MARKER_REMOVAL)
                    assertEquals(kind == CleanupFailure.CHUNK_DELETION, File(root, "retired/$retiredId").exists())
                    if (looping) {
                        val nextId = PersistentAudioChunkStore::class.java.getDeclaredField("nextChunkId")
                        nextId.isAccessible = true
                        val originalNext = nextId.getInt(store)
                        try {
                            nextId.setInt(store, retiredId.toInt())
                            val collision = assertThrows(IOException::class.java) { store.append(ByteArray(2), 0, 2) }
                            assertTrue(collision.message?.contains("collision with live data") == true)
                            assertFalse(File(root, "chunks/$retiredId").exists())
                        } finally {
                            nextId.setInt(store, originalNext)
                        }
                    }
                    repeat(3) { queues.schedule(); queues.retry(); store.sealActiveChunk() }
                    queues.drain()
                    assertEquals("Failed generation retried cleanup", 1, failure.attempts)
                    assertEquals(1, queues.failures)

                    failure.enabled = false
                    queues.configureAndSchedule(4_096L)
                    queues.drain()
                    assertFalse(store.retentionMaintenanceNeeded())
                    assertFalse(queues.state.isActive())
                    assertFalse(queues.foreground)
                    assertEquals(1, queues.failures)
                    val expected = if (looping) parts[1] + parts[2] else parts[0] + parts[1]
                    assertArrayEquals(expected, readPcm(store))
                    assertFalse(File(root, "retired/$retiredId").exists())
                    assertTrue(File(root, "chunks").listFiles().orEmpty().none { it.name.endsWith(".pending") })
                }
            }
        }
    }

    private enum class CleanupFailure { MARKER_REMOVAL, CHUNK_DELETION }

    private class CleanupFault(
        val kind: CleanupFailure,
        var enabled: Boolean = false,
        var attempts: Int = 0,
        var barriers: Int = 0,
    )

    private fun withRetiredStore(
        looping: Boolean,
        kind: CleanupFailure,
        block: (PersistentAudioChunkStore, File, CleanupFault, List<ByteArray>) -> Unit,
    ) {
        val parent = File("build/tmp/retention-failure-settlement-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "retired-").toFile()
        val fault = CleanupFault(kind)
        try {
            PersistentAudioChunkStore(root, overwriteOldest = looping, directorySync = { directory ->
                val removalBarrier = when (fault.kind) {
                    CleanupFailure.MARKER_REMOVAL -> directory.name == "retired" &&
                        directory.listFiles().orEmpty().none { it.name.toUIntOrNull() != null }
                    CleanupFailure.CHUNK_DELETION -> directory.name == "chunks" &&
                        directory.listFiles().orEmpty().none { it.name.endsWith(".pending") }
                }
                if (removalBarrier) fault.barriers++
                if (fault.enabled && removalBarrier) {
                    fault.attempts++
                    throw IOException("Controlled ${fault.kind} durability failure")
                }
            }).use { store ->
                store.configure(RetentionMode.SIZE, 65_536L, 8_000, 1, deferRetentionCleanup = true)
                val parts = (0 until 3).map { part -> ByteArray(2_048) { (part * 37 + it).toByte() } }
                for (part in parts) {
                    assertEquals(part.size, store.append(part, 0, part.size))
                    store.sealActiveChunk()
                }
                block(store, root, fault, parts)
            }
        } finally {
            fault.enabled = false
            root.deleteRecursively()
        }
    }

    private fun assertRetiredCleanupOwned(
        store: PersistentAudioChunkStore,
        id: UInt,
        claimPending: Boolean,
        removalPending: Boolean,
    ) {
        fun field(name: String): Any = PersistentAudioChunkStore::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            requireNotNull(it.get(store))
        }
        assertTrue((field("retiredById") as Map<*, *>).containsKey(id))
        assertTrue((field("eligibleRetiredById") as Map<*, *>).containsKey(id))
        assertEquals(claimPending, (field("retiredDeletionClaims") as Map<*, *>).containsKey(id))
        assertEquals(removalPending, (field("retirementTombstoneRemovalDurabilityPending") as Set<*>).contains(id))
    }

    @Test
    fun failedPartialRewriteSealsOnceSettlesAndRearmsOnlyAfterConfiguration() = withStore { store, root, writes ->
        val original = File(root, "chunks/0").readBytes()
        val queues = Queues(store)
        writes.fail = true
        queues.configureAndSchedule(1_024L)
        queues.drain()

        assertEquals(1, writes.attempts)
        assertEquals(1, queues.failures)
        assertEquals(1, queues.seals)
        assertFalse(queues.state.isActive())
        assertFalse(queues.foreground)
        assertEquals(1, queues.serviceStops)
        assertArrayEquals(original, File(root, "chunks/0").readBytes())
        assertEquals(2_048L, store.countFilledBytes())

        // Polling, seal callbacks and old retry callbacks cannot manufacture a recovery event.
        repeat(3) { queues.schedule(); queues.retry() }
        queues.drain()
        assertEquals(1, writes.attempts)
        assertEquals(1, queues.failures)

        writes.fail = false
        queues.configureAndSchedule(1_024L)
        queues.drain()
        assertEquals(2, writes.attempts)
        assertEquals(1, queues.failures)
        assertEquals(1_024L, store.countFilledBytes())
        assertFalse(queues.foreground)
        assertFalse(queues.state.isActive())
        assertArrayEquals(original.copyOfRange(original.size - 2_048, original.size - 1_024), readPcm(store))
    }

    @Test
    fun executorRejectionUsesSameFailureBoundaryAndDoesNotRetryFromSeal() = withStore { store, _, writes ->
        val queues = Queues(store)
        queues.rejectSubmission = true
        queues.configureAndSchedule(1_024L)
        queues.drain()

        assertEquals(1, queues.failures)
        assertEquals(1, queues.seals)
        assertEquals(0, writes.attempts)
        assertFalse(queues.foreground)
        assertFalse(queues.state.isActive())
        assertEquals(2_048L, store.countFilledBytes())

        queues.rejectSubmission = false
        queues.configureAndSchedule(1_024L)
        queues.drain()
        assertEquals(1, writes.attempts)
        assertEquals(1_024L, store.countFilledBytes())
        assertEquals(1, queues.failures)
    }

    @Test
    fun leasedChunkKeepsHealthyDelayedRetryAndForegroundOwner() = withStore { store, _, writes ->
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        val queues = Queues(store)
        queues.configureAndSchedule(1_024L)
        queues.drain()
        assertEquals(0, queues.failures)
        assertEquals(0, writes.attempts)
        assertEquals(1, queues.delayed.size)
        assertTrue(queues.state.isActive())
        assertTrue(queues.foreground)
        assertEquals(0, queues.serviceStops)

        lease.close()
        queues.delayed.removeFirst().run()
        queues.drain()
        assertEquals(1, writes.attempts)
        assertEquals(0, queues.failures)
        assertEquals(1_024L, store.countFilledBytes())
        assertFalse(queues.foreground)
    }

    @Test
    fun queuedWorkerFailureAfterNewConfigurationDoesNotPauseOrLatchNewWork() = withStore { store, _, writes ->
        val queues = Queues(store)
        writes.fail = true
        queues.configureAndSchedule(1_024L)
        queues.finishOneWorkerBeforeTerminal()
        assertEquals(1, writes.attempts)
        assertTrue(queues.state.isActive())
        assertTrue(queues.foreground)

        // Configuration wins the audio queue before the older worker's failure terminal.
        writes.fail = false
        queues.configureAndSchedule(1_024L)
        queues.drain()
        assertEquals(2, writes.attempts)
        assertEquals(0, queues.failures)
        assertEquals(0, queues.seals)
        assertEquals(1_024L, store.countFilledBytes())
        assertFalse(queues.state.isActive())
        assertFalse(queues.foreground)
    }

    @Test
    fun inspectionFailureLatchesBeforeCleanupAndSuccessfulConfigurationReopensIt() {
        val state = RetentionMaintenanceSchedulerState()
        var reports = 0
        var observations = 0
        val failingObservation = {
            observations++
            throw IOException("Controlled storage inspection failure")
        }
        repeat(3) {
            assertNull(observeRetentionMaintenancePass(state, failingObservation) { reports++ })
        }
        assertEquals(1, observations)
        assertEquals(1, reports)
        state.clear() // Generic cleanup is not recovery.
        assertNull(state.observationGeneration())
        state.rearmAfterConfiguration()
        assertNotNull(observeRetentionMaintenancePass(state, { true }) { reports++ })
        assertEquals(1, reports)
    }

    @Test
    fun successfulConfigurationRetainsOldPhysicalOwnerButItsLateFailureCannotBlockNewWork() {
        val state = RetentionMaintenanceSchedulerState()
        val old = requireNotNull(state.claimObservedNeed(true))
        state.rearmAfterConfiguration()
        assertTrue(state.ownsPass(old))
        assertNull(state.claimObservedNeed(true))
        var reports = 0
        var next: Long? = null
        settleFailedRetentionPass(state, old, { reports++ }, {
            assertTrue(state.isActive())
            next = state.claimActiveRetry()
        }, { throw AssertionError("No prior failure") })
        assertEquals(0, reports)
        assertNotNull(next)
        assertTrue(state.ownsPass(requireNotNull(next)))
        assertNotNull(state.observationGeneration())

        // Replaying the old terminal cannot affect that replacement pass.
        assertEquals(RetentionPassFailure.IGNORED, state.failPass(old))
        assertFalse(state.completePass(old, false))
        assertTrue(state.ownsPass(requireNotNull(next)))
        assertTrue(state.completePass(requireNotNull(next), false))
        assertFalse(state.isActive())
    }

    @Test
    fun staleInspectionSuccessOrFailureCannotCrossConfigurationBoundary() {
        for (fail in listOf(false, true)) {
            val state = RetentionMaintenanceSchedulerState()
            var reports = 0
            val old = observeRetentionMaintenancePass(state, {
                state.rearmAfterConfiguration()
                if (fail) throw IOException("Old inspection")
                true
            }, { reports++ })
            assertNull(old)
            assertEquals(0, reports)
            assertNotNull(observeRetentionMaintenancePass(state, { true }) { reports++ })
        }
    }

    @Test
    fun inspectionFailureKeepsInFlightOwnerUntilItsTerminalWithoutDuplicateNotice() {
        for (workerFails in listOf(false, true)) {
            val state = RetentionMaintenanceSchedulerState()
            val pass = requireNotNull(state.claimObservedNeed(true))
            var reports = 0
            assertNull(observeRetentionMaintenancePass(state, {
                throw IOException("Controlled concurrent inspection failure")
            }, { reports++ }))
            assertTrue(state.isActive())
            assertTrue(state.ownsPass(pass))
            if (workerFails) {
                var terminalCalls = 0
                settleFailedRetentionPass(state, pass, { reports++ }, {
                    throw AssertionError("No newer configuration")
                }, { terminalCalls++ })
                assertEquals(1, terminalCalls)
            } else {
                assertTrue(state.completePass(pass, needsMore = true))
            }
            assertEquals(1, reports)
            assertFalse(state.isActive())
            assertNull(state.claimActiveRetry())
        }
    }

    @Test
    fun newerInspectionFailureStillWinsOverOldPassAfterConfiguration() {
        val state = RetentionMaintenanceSchedulerState()
        val old = requireNotNull(state.claimObservedNeed(true))
        state.rearmAfterConfiguration()
        assertTrue(state.failObservation(requireNotNull(state.observationGeneration())))
        assertEquals(RetentionPassFailure.ALREADY_BLOCKED, state.failPass(old))
        assertFalse(state.isActive())
        assertNull(state.claimActiveRetry())
    }

    @Test
    fun failedMaintenanceSettlementKeepsAcceptedSettingsLifetime() {
        val state = RetentionMaintenanceSchedulerState()
        val pass = requireNotNull(state.claimObservedNeed(true))
        assertEquals(RetentionPassFailure.BLOCKED, state.failPass(pass))
        var foregroundRemovals = 0
        var serviceStops = 0
        settleIdleServiceLifetime(state, { throw AssertionError("Failed backlog is not an owner") },
            { true }, { foregroundRemovals++ }, { serviceStops++ })
        assertEquals(1, foregroundRemovals)
        assertEquals(0, serviceStops)
        settleIdleServiceLifetime(state, { false }, { false }, {}, { serviceStops++ })
        assertEquals(1, serviceStops)
    }

    /** Uses production admission/failure/settlement seams with real chunk writes and retirement cleanup. */
    private class Queues(private val store: PersistentAudioChunkStore) {
        val state = RetentionMaintenanceSchedulerState()
        private val main = ArrayDeque<Runnable>()
        private val audio = ArrayDeque<Runnable>()
        private val worker = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        var rejectSubmission = false
        var failures = 0
        var lastStoreFailure: IOException? = null
        var seals = 0
        var foreground = false
        var serviceStops = 0

        fun configureAndSchedule(limit: Long) {
            store.configure(RetentionMode.SIZE, limit, 8_000, 1, deferRetentionCleanup = true)
            state.rearmAfterConfiguration()
            schedule()
        }

        fun schedule() {
            val pass = observeRetentionMaintenancePass(state, store::retentionMaintenanceNeeded) { fail() }
            if (pass != null) admit(pass)
        }

        fun retry() { state.claimActiveRetry()?.let(::admit) }

        private fun admit(pass: Long) {
            admitRetentionPassOnMain(state, pass, { main.add(it) }, { true }, {
                foreground = true
                true
            }, { id ->
                try {
                    if (rejectSubmission) throw RejectedExecutionException("Controlled executor rejection")
                    worker.add(Runnable { runPass(id) })
                } catch (_: RejectedExecutionException) {
                    passFailed(id)
                }
            }, { main.add(Runnable { settle() }) })
        }

        private fun runPass(pass: Long) {
            val step = try {
                store.performRetentionMaintenanceStep()
            } catch (error: IOException) {
                lastStoreFailure = error
                passFailed(pass)
                return
            }
            if (!state.completePass(pass, step.needsMore)) return
            if (step.needsMore && state.isActive()) {
                if (step.blocked) delayed.add(Runnable { retry() }) else retry()
                return
            }
            schedule()
            if (!state.isActive()) main.add(Runnable { settle() })
        }

        private fun passFailed(pass: Long) {
            // Worker failures transfer terminal ownership to the same audio queue as configuration.
            audio.add(Runnable {
                settleFailedRetentionPass(state, pass, ::fail, ::retry, {
                    main.add(Runnable { settle() })
                })
            })
        }

        fun finishOneWorkerBeforeTerminal() {
            main.removeFirst().run()
            worker.removeFirst().run()
        }

        private fun fail() {
            failures++
            audio.add(Runnable {
                seals++
                store.sealActiveChunk()
                schedule() // The production failure -> seal -> maintenance cycle.
                main.add(Runnable { settle() })
            })
        }

        private fun settle() = settleIdleServiceLifetime(
            state, { foreground = true; true }, { false },
            { foreground = false }, { serviceStops++ },
        )

        fun drain() {
            repeat(50) {
                main.pollFirst()?.run()
                worker.pollFirst()?.run()
                audio.pollFirst()?.run()
                if (main.isEmpty() && worker.isEmpty() && audio.isEmpty()) return
            }
            throw AssertionError("Maintenance did not settle")
        }
    }

    private class Writes(var fail: Boolean = false, var attempts: Int = 0)

    private fun withStore(block: (PersistentAudioChunkStore, File, Writes) -> Unit) {
        val parent = File("build/tmp/retention-failure-settlement-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        val writes = Writes()
        try {
            PersistentAudioChunkStore(root, overwriteOldest = false, atomicChunkReplace = { source, target ->
                writes.attempts++
                if (writes.fail) throw IOException("Controlled partial-rewrite publication failure")
                replaceAudioChunkAtomically(source, target)
            }).use { store ->
                store.configure(RetentionMode.SIZE, 65_536L, 8_000, 1, deferRetentionCleanup = true)
                val pcm = ByteArray(2_048) { (it * 29).toByte() }
                store.append(pcm, 0, pcm.size)
                store.sealActiveChunk()
                block(store, root, writes)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun readPcm(store: PersistentAudioChunkStore): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
            lease.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                output.write(bytes, offset, count)
                count
            }
        }
        return output.toByteArray()
    }
}
