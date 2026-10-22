package app.smallthingz.reverb

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionLeaseParkingTest {
    @Test
    fun releaseBetweenStoreObservationAndCompletionCannotBeLost() {
        for (looping in listOf(false, true)) withFixture(looping) { fixture ->
            val lease = requireNotNull(fixture.store.acquireRange(0.0, fixture.store.durationSeconds()))
            fixture.configure(1_024)
            val pass = fixture.nextPass()
            val blocked = fixture.store.performRetentionMaintenanceStep()
            assertTrue(blocked.blockedOnLease)

            lease.close()
            fixture.deliverReleaseNotifications() // Main -> audio arrives before worker settlement.
            assertTrue(fixture.passes.isEmpty())
            assertEquals(RetentionPassCompletion.RETRY, fixture.settle(pass, blocked))
            fixture.retry()
            fixture.drain()
            assertEquals(1_024L, fixture.store.countFilledBytes())
            assertFalse(fixture.state.isActive())
        }
    }

    @Test
    fun releaseAfterParkingImmediatelyClaimsAWorkerForEitherBuffer() {
        for (looping in listOf(false, true)) withFixture(looping) { fixture ->
            val lease = requireNotNull(fixture.store.acquireRange(0.0, fixture.store.durationSeconds()))
            fixture.configure(1_024)
            fixture.parkNextPass()
            assertTrue(fixture.passes.isEmpty())
            assertTrue(fixture.state.isActive()) // Keep the accepted foreground lifetime.

            lease.close()
            fixture.deliverReleaseNotifications()
            assertEquals(1, fixture.passes.size)
            fixture.drain()
            assertEquals(1_024L, fixture.store.countFilledBytes())
            assertFalse(fixture.state.isActive())
        }
    }

    @Test
    fun offOrIncreasedLimitReconcilesParkedWorkEvenWithNegativeObservation() {
        for (limit in listOf(0L, 65_536L)) withFixture { fixture ->
            fixture.store.acquireRange(0.0, fixture.store.durationSeconds())!!.use {
                fixture.configure(1_024)
                fixture.parkNextPass()

                fixture.configure(limit)
                assertFalse(fixture.store.retentionMaintenanceNeeded())
                assertEquals(1, fixture.passes.size)
                assertTrue(fixture.state.isActive())
                fixture.drain()
                assertFalse(fixture.state.isActive())
                assertEquals(2_048L, fixture.store.countFilledBytes())
            }
        }
    }

    @Test
    fun configurationCrossingAWorkerCannotParkItsStaleLeaseObservation() = withFixture { fixture ->
        fixture.store.acquireRange(0.0, fixture.store.durationSeconds())!!.use {
            fixture.configure(1_024)
            val pass = fixture.nextPass()
            val blocked = fixture.store.performRetentionMaintenanceStep()
            fixture.configure(0)
            assertTrue(fixture.passes.isEmpty())
            assertEquals(RetentionPassCompletion.RETRY, fixture.settle(pass, blocked))
            fixture.retry()
            fixture.drain()
            assertFalse(fixture.state.isActive())
            assertEquals(2_048L, fixture.store.countFilledBytes())
        }
    }

    @Test
    fun cancelledPartialClearRechecksAFormerLeaseBoundaryWithoutWaitingForRelease() =
        withFixture(parts = 3) { fixture ->
            fixture.store.acquireRange(0.0, fixture.store.durationSeconds())!!.use {
                fixture.configure(5_120)
                fixture.parkNextPass()
                val cleared = fixture.store.clearOneChunk()
                assertFalse(cleared.complete) // Cancel accepted after this durability-complete unit.
                assertEquals(4_096L, cleared.remainingPayloadBytes)
                assertFalse(fixture.store.retentionMaintenanceNeeded())

                fixture.schedule() // finishBufferClearOperation's unconditional terminal recheck.
                assertEquals(1, fixture.passes.size)
                fixture.drain()
                assertFalse(fixture.state.isActive())
            }
            fixture.deliverReleaseNotifications()
            fixture.drain() // The retired leased chunk still receives its eventual cleanup.
            assertFalse(fixture.store.retentionMaintenanceNeeded())
        }

    @Test
    fun completedClearSettlesParkedLifetimeBeforeEditorCloses() = withFixture { fixture ->
        fixture.store.acquireRange(0.0, fixture.store.durationSeconds())!!.use {
            fixture.configure(1_024)
            fixture.parkNextPass()
            assertTrue(fixture.store.clearOneChunk().complete)
            fixture.schedule()
            fixture.drain()
            assertFalse(fixture.state.isActive())
            assertEquals(0L, fixture.store.countFilledBytes())
        }
        fixture.deliverReleaseNotifications()
        fixture.drain()
    }

    @Test
    fun oneLeasedStoreCannotParkAnotherStoresRunnableBacklog() = withFixture { looping ->
        withFixture(looping = false, parts = 3) { oneShot ->
            looping.store.acquireRange(0.0, looping.store.durationSeconds())!!.use {
                looping.configure(1_024)
                oneShot.configure(1_024)
                val blocked = looping.store.performRetentionMaintenanceStep()
                val progressing = oneShot.store.performRetentionMaintenanceStep()
                assertTrue(blocked.blockedOnLease)
                assertTrue(progressing.progressed)
                assertTrue(progressing.needsMore)
                assertFalse(retentionPassWaitsOnlyForLeases(blocked, progressing))
                assertFalse(retentionPassWaitsOnlyForLeases(progressing, blocked))
            }
        }
    }

    @Test
    fun activeCaptureAndUnknownBlockReasonsRetainRetry() {
        val state = RetentionMaintenanceSchedulerState()
        val pass = requireNotNull(state.claimObservedNeed(true))
        var listening = false
        val lease = PersistentAudioChunkStore.RetentionMaintenanceStep(false, true, true, true)
        val idle = PersistentAudioChunkStore.RetentionMaintenanceStep(false, false, false)
        assertTrue(retentionPassWaitsOnlyForLeases(lease, idle))
        assertFalse(listening)
        listening = true // Start wins after the store was sampled but before settlement.
        assertEquals(RetentionPassCompletion.RETRY, state.settlePass(pass, true) {
            retentionPassWaitsOnlyForLeases(lease, idle) && !listening
        })
        val afterStop = requireNotNull(state.claimActiveRetry())
        listening = false
        assertEquals(RetentionPassCompletion.WAIT_FOR_LEASE, state.settlePass(afterStop, true) {
            retentionPassWaitsOnlyForLeases(lease, idle) && !listening
        })
        val unknown = PersistentAudioChunkStore.RetentionMaintenanceStep(false, true, true)
        assertFalse(retentionPassWaitsOnlyForLeases(lease, unknown))
        assertFalse(retentionPassWaitsOnlyForLeases(unknown, idle))
        assertFalse(retentionPassWaitsOnlyForLeases(idle, idle))
    }

    @Test
    fun clearAndNewerClaimInvalidateOldLeaseWaitAndItsWake() {
        val state = RetentionMaintenanceSchedulerState()
        val oldGeneration = requireNotNull(state.observationGeneration())
        val oldPass = requireNotNull(state.claimObservedNeed(true, oldGeneration))
        state.clear()
        state.rearmAfterConfiguration()
        val current = requireNotNull(state.claimObservedNeed(true))
        assertNull(state.claimObservedNeed(true, oldGeneration))
        assertEquals(RetentionPassCompletion.STALE, state.settlePass(oldPass, true) { true })
        assertTrue(state.ownsPass(current))
        assertEquals(RetentionPassCompletion.WAIT_FOR_LEASE, state.settlePass(current, true) { true })
        assertNotNull(state.claimObservedNeed(true))
    }

    @Test
    fun failureLatchStillOverridesParkingAndRecheckSignals() {
        val state = RetentionMaintenanceSchedulerState()
        val pass = requireNotNull(state.claimObservedNeed(true))
        assertTrue(state.failObservation(requireNotNull(state.observationGeneration())))
        assertNull(state.claimObservedNeed(true))
        assertEquals(RetentionPassCompletion.COMPLETE, state.settlePass(pass, true) { true })
        assertFalse(state.isActive())
        assertNull(state.claimActiveRetry())
    }

    private class Fixture(val store: PersistentAudioChunkStore) {
        val state = RetentionMaintenanceSchedulerState()
        val passes = ArrayDeque<Long>()
        val releases = ArrayDeque<() -> Unit>()

        fun configure(limit: Long) {
            store.configure(RetentionMode.SIZE, limit, 8_000, 1, deferRetentionCleanup = true)
            state.rearmAfterConfiguration()
            schedule()
        }

        fun schedule() {
            observeRetentionMaintenancePass(state, store::retentionMaintenanceNeeded) { throw it }
                ?.let { passes.add(it) }
        }

        fun retry() { state.claimActiveRetry()?.let { passes.add(it) } }
        fun nextPass(): Long = passes.removeFirst()
        fun deliverReleaseNotifications() { while (releases.isNotEmpty()) releases.removeFirst().invoke() }

        fun settle(pass: Long, step: PersistentAudioChunkStore.RetentionMaintenanceStep) =
            state.settlePass(pass, step.needsMore) {
                retentionPassWaitsOnlyForLeases(step,
                    PersistentAudioChunkStore.RetentionMaintenanceStep(false, false, false))
            }

        fun parkNextPass() {
            val pass = nextPass()
            val step = store.performRetentionMaintenanceStep()
            assertTrue(step.blockedOnLease)
            assertEquals(RetentionPassCompletion.WAIT_FOR_LEASE, settle(pass, step))
        }

        fun drain() {
            var count = 0
            while (passes.isNotEmpty()) {
                check(++count < 32) { "Maintenance failed to settle" }
                val pass = nextPass()
                when (settle(pass, store.performRetentionMaintenanceStep())) {
                    RetentionPassCompletion.RETRY -> retry()
                    RetentionPassCompletion.COMPLETE -> schedule()
                    RetentionPassCompletion.WAIT_FOR_LEASE, RetentionPassCompletion.STALE -> Unit
                }
            }
        }
    }

    private fun withFixture(
        looping: Boolean = true,
        parts: Int = 1,
        block: (Fixture) -> Unit,
    ) {
        val parent = File("build/tmp/retention-lease-parking-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "lease-").toFile()
        var fixture: Fixture? = null
        try {
            PersistentAudioChunkStore(root, overwriteOldest = looping, directorySync = {},
                onMaintenanceNeeded = {
                    val owner = requireNotNull(fixture)
                    owner.releases.add { owner.schedule() }
                }).use { store ->
                store.configure(RetentionMode.SIZE, 65_536, 8_000, 1, deferRetentionCleanup = true)
                repeat(parts) { part ->
                    val pcm = ByteArray(2_048) { (part * 37 + it).toByte() }
                    assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                    store.sealActiveChunk()
                }
                val owner = Fixture(store)
                fixture = owner
                block(owner)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
