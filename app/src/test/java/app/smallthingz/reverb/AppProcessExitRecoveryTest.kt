package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AppProcessExitRecoveryTest {
    private val cursor = AppProcessInspectionCursor()
    private val first = AppProcessMarker(42, 100L)
    private val second = AppProcessMarker(43, 200L)

    private fun exit(reason: Int = ApplicationExitInfo.REASON_CRASH_NATIVE) = RecordingIncident(
        occurredAtMillis = 150L,
        kind = RecordingIncidentKind.UNEXPECTED_ERROR,
        pid = first.pid,
        processStartedAtMillis = first.processStartedAtMillis,
        exitReason = reason,
    )

    private fun encoded(state: AppProcessRecoveryState): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { RecordingIncidentStore.writeProcessStatePayload(it, state) }
        }.toByteArray()

    private fun decoded(bytes: ByteArray): AppProcessRecoveryState =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            readIncidentPayloadExact(input, "app process state", RecordingIncidentStore::readProcessStatePayload)
        }

    @Test
    fun nullThenNativeCrashOrAnr_afterCurrentReplacement_isRecoveredExactlyOnce() {
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) {
            var durable = encoded(AppProcessRecoveryState(first))
            val incidents = mutableListOf<RecordingIncident>()
            var available = false
            var queries = 0
            var writes = 0
            fun recover() = recoverAppProcessExits(
                decoded(durable), second,
                { durable = encoded(it); writes++ },
                { owner ->
                    queries++
                    assertEquals(PendingAppProcessExit(first, 200L), owner)
                    // The replacement marker and its independent pending owner already survived disk replay.
                    assertEquals(second, decoded(durable).current)
                    assertTrue(decoded(durable).pending.contains(owner))
                    if (available) exit(reason) else null
                },
                { incidents += it },
            )
            recover()
            assertEquals(listOf(PendingAppProcessExit(first, 200L)), decoded(durable).pending)
            assertTrue(incidents.isEmpty())
            val writesAfterReplacement = writes
            recover()
            assertEquals(writesAfterReplacement, writes) // Empty query cannot create a revision/write loop.
            available = true
            recover()
            repeat(3) { recover() }
            assertEquals(1, incidents.size)
            assertEquals(reason, incidents.single().exitReason)
            assertEquals(3, queries)
            assertTrue(decoded(durable).pending.isEmpty())
        }
    }

    @Test
    fun ordinaryUnarmedExit_retiresOwnerWithoutIncident() {
        val reasons = listOf(ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_UNKNOWN)
        for (reason in reasons) {
            var durable = AppProcessRecoveryState(first)
            val state = recoverAppProcessExits(durable, second, { durable = it }, { exit(reason) },
                { fail("Ordinary unarmed exit became an incident") })
            assertEquals(second, durable.current)
            assertTrue(state.pending.isEmpty())
            assertEquals(state, durable)
        }
    }

    @Test
    fun ownershipWriteFailure_doesNotQueryOrOverwriteOnlyPriorOwner() {
        val durable = AppProcessRecoveryState(first)
        try {
            recoverAppProcessExits(durable, second, { throw IOException("directory sync") },
                { fail("Queried before ownership was durable"); null }, { fail("Published early") })
            fail("Expected I/O failure")
        } catch (_: IOException) { }
        assertEquals(first, durable.current)
    }

    @Test
    fun optionalPlatformQueryFailure_preservesOwnerAndReadableHistory_withoutPublicationLoop() {
        var durable = AppProcessRecoveryState(second, listOf(PendingAppProcessExit(first, 200L)))
        val existingHistory = listOf(RecordingIncident(occurredAtMillis = 50L))
        var writes = 0
        var publications = 0
        repeat(3) {
            recoverAppProcessExits(durable, second, { durable = it; writes++ },
                { queryOptionalAppExitEvidence<RecordingIncident> { throw IllegalStateException("Binder unavailable") } },
                { publications++ })
            // Recovery completed normally, allowing the history caller to return its readable rows.
            assertEquals(50L, existingHistory.single().occurredAtMillis)
            assertEquals(listOf(PendingAppProcessExit(first, 200L)), durable.pending)
        }
        assertEquals(0, writes)
        assertEquals(0, publications)
        recoverAppProcessExits(durable, second, { durable = it },
            { queryOptionalAppExitEvidence { exit() } }, { publications++ })
        assertEquals(1, publications)
        assertTrue(durable.pending.isEmpty())
    }

    @Test
    fun queryOrHistoryFailure_keepsExactPendingOwnerRetryable() {
        for (failQuery in listOf(true, false)) {
            var durable = AppProcessRecoveryState(first)
            try {
                recoverAppProcessExits(durable, second, { durable = it },
                    { if (failQuery) throw IOException("ActivityManager unavailable") else exit() },
                    { throw IOException("history publication") })
                fail("Expected I/O failure")
            } catch (_: IOException) { }
            assertEquals(second, durable.current)
            assertEquals(listOf(PendingAppProcessExit(first, 200L)), durable.pending)
            val published = mutableListOf<RecordingIncident>()
            recoverAppProcessExits(durable, second, { durable = it }, { exit() }, { published += it })
            assertEquals(1, published.size)
            assertTrue(durable.pending.isEmpty())
        }
    }

    @Test
    fun retirementWriteFailure_replaysDurableIncidentWithoutDuplicate() {
        var durable = AppProcessRecoveryState(second, listOf(PendingAppProcessExit(first, 200L)))
        val incidents = mutableListOf<RecordingIncident>()
        fun publish(incident: RecordingIncident) {
            if (incidents.none { recordingIncidentReferenceMatches(it, incident) }) incidents += incident
        }
        try {
            recoverAppProcessExits(durable, second, { throw IOException("retire sync") }, { exit() }, ::publish)
            fail("Expected I/O failure")
        } catch (_: IOException) { }
        assertEquals(1, durable.pending.size)
        recoverAppProcessExits(durable, second, { durable = it }, { exit() }, ::publish)
        assertEquals(1, incidents.size)
        assertTrue(durable.pending.isEmpty())
    }

    private fun owner(pid: Int, start: Long, end: Long) =
        PendingAppProcessExit(AppProcessMarker(pid, start), end)

    // Three owners compete for the last slot at end=60. PID breaks the start=20 tie.
    // The older start at end=61 still outranks all three. The order mimics a rotated v2 queue.
    private val newestAtCutoff = owner(2, 20, 60)
    private val survivors = (1..31).map { owner(100 + it, 5, 60L + it) } + newestAtCutoff
    private val oldestEvicted = owner(9, 19, 60)
    private val rotatedLegacy = survivors.take(12) + oldestEvicted +
        survivors.drop(12) + owner(1, 20, 60)

    private fun v1Payload(marker: AppProcessMarker): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x52495032)
                output.writeByte(1)
                output.writeInt(marker.pid)
                output.writeLong(marker.processStartedAtMillis)
            }
        }.toByteArray()

    private fun v2Payload(owners: List<PendingAppProcessExit>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x52495032)
                output.writeByte(2)
                output.writeInt(second.pid)
                output.writeLong(second.processStartedAtMillis)
                output.writeInt(owners.size)
                for (pending in owners) {
                    output.writeInt(pending.marker.pid)
                    output.writeLong(pending.marker.processStartedAtMillis)
                    output.writeLong(pending.endedByMillis)
                }
            }
        }.toByteArray()

    @Test
    fun unresolvedInspections_coverAll32EveryFourPasses_withoutWritingOrReordering() {
        val initial = AppProcessRecoveryState(second, survivors)
        val bytes = encoded(initial)
        repeat(2) {
            val seen = mutableSetOf<PendingAppProcessExit>()
            repeat(4) {
                var calls = 0
                val state = recoverAppProcessExits(
                    decoded(bytes), second, { fail("Unresolved inspection wrote process state") },
                    { pending ->
                        calls++
                        assertTrue("Repeated owner before all 32 were inspected", seen.add(pending))
                        null
                    },
                    { fail("Unexpected incident") }, cursor,
                )
                assertEquals(8, calls)
                assertEquals(initial, state)
                assertArrayEquals(bytes, encoded(state))
            }
            assertEquals(survivors.toSet(), seen)
        }
    }

    @Test
    fun classification_retiresOnlyMatchedOwners_andPreservesRemainingOrder() {
        val initial = AppProcessRecoveryState(second, survivors)
        var durable = encoded(initial)
        val crash = survivors[10]
        val ordinary = survivors[22]
        fun evidence(pending: PendingAppProcessExit, reason: Int) = exit(reason).copy(
            pid = pending.marker.pid, processStartedAtMillis = pending.marker.processStartedAtMillis,
            occurredAtMillis = pending.endedByMillis,
        )
        val incident = evidence(crash, ApplicationExitInfo.REASON_CRASH_NATIVE)
        val published = mutableListOf<RecordingIncident>()
        repeat(8) {
            val before = decoded(durable)
            val classified = mutableSetOf<PendingAppProcessExit>()
            val result = recoverAppProcessExits(
                before, second, { durable = encoded(it) },
                { pending ->
                    when (pending) {
                        crash -> incident
                        ordinary -> evidence(ordinary, ApplicationExitInfo.REASON_EXIT_SELF)
                        else -> null
                    }?.also { classified.add(pending) }
                },
                { published += it }, cursor,
            )
            assertEquals(before.pending.filterNot { it in classified }, result.pending)
            assertEquals(result, decoded(durable))
        }
        assertEquals(survivors.filterNot { it == ordinary || it == crash }, decoded(durable).pending)
        assertEquals(listOf(incident), published)
    }

    @Test
    fun admissionIntoRotatedFullQueue_evictsMinimumByEndStartAndPid() {
        val cutoffGroup = listOf(owner(1, 21, 60), owner(3, 20, 60), newestAtCutoff)
        val newerEnds = survivors.dropLast(1).take(29)
        val rotated = newerEnds.drop(10) + cutoffGroup + newerEnds.take(10)
        val replacement = AppProcessMarker(44, 300L)
        val admitted = PendingAppProcessExit(second, replacement.processStartedAtMillis)
        val expected = (rotated - newestAtCutoff + admitted).toSet()
        var durable = v2Payload(rotated)
        var writes = 0
        val state = recoverAppProcessExits(
            decoded(durable), replacement, { durable = encoded(it); writes++ },
            { pending ->
                assertEquals(1, writes)
                assertEquals(expected, decoded(durable).pending.toSet())
                assertTrue(pending in expected)
                null
            },
            { fail("Unexpected incident") }, cursor,
        )
        assertEquals(1, writes)
        assertEquals(replacement, state.current)
        assertEquals(32, state.pending.size)
        assertEquals(expected, state.pending.toSet())
        assertEquals(state, decoded(durable))
    }

    // Only the visible bytes and directory sync are simulated. Both reads and writes use the store's codec.
    private inner class ProcessFile(var visible: ByteArray) {
        val key = File("process-state")
        val publication = AppProcessStatePublication()
        var writes = 0
        var syncFailure: IOException? = null

        fun publish(state: AppProcessRecoveryState) {
            visible = encoded(state)
            writes++
            syncFailure?.let { throw it }
        }

        fun read() = checkNotNull(publication.confirmRead(key, decoded(visible), ::publish))

        fun recover(
            findExit: (PendingAppProcessExit) -> RecordingIncident?,
            publishIncident: (RecordingIncident) -> Unit = { fail("Unexpected incident") },
        ): AppProcessRecoveryState {
            var prior = read()
            return recoverAppProcessExits(prior, second, { target ->
                publication.write(key, prior, target) { started ->
                    started()
                    publish(target)
                }
                prior = target
            }, findExit, publishIncident, cursor)
        }
    }

    @Test
    fun oversizedRotatedV2_confirmsVisibleCompactionBeforeFurtherInspection() {
        val file = ProcessFile(v2Payload(rotatedLegacy))
        val before = file.read()
        assertTrue(before.needsCompaction)
        assertEquals(second, before.current)
        assertEquals(32, before.pending.size)
        assertEquals(survivors.toSet(), before.pending.toSet())
        val normalized = AppProcessRecoveryState(before.current, before.pending)
        val expectedBytes = v2Payload(normalized.pending)

        var queries = 0
        fun recover() = file.recover({ queries++; null })
        val failure = IOException("directory sync")
        file.syncFailure = failure
        repeat(2) { attempt ->
            assertSame(failure, assertThrows(IOException::class.java) { recover() })
            assertEquals(attempt + 1, file.writes)
            assertArrayEquals(expectedBytes, file.visible)
            assertEquals(0, queries)
        }

        file.syncFailure = null
        assertEquals(normalized, recover())
        assertEquals(8, queries)
        assertEquals(3, file.writes)
        repeat(4) { assertEquals(normalized, recover()) }
        assertArrayEquals(expectedBytes, file.visible)
        assertEquals(3, file.writes)
    }

    @Test
    fun retirementSyncFailure_preservesTombstoneUntilExactOwnerStateIsConfirmed() {
        val retired = PendingAppProcessExit(first, second.processStartedAtMillis)
        val remaining = owner(12, 40, 90)
        val file = ProcessFile(v2Payload(listOf(retired, remaining)))
        val expectedBytes = v2Payload(listOf(remaining))
        val incident = exit()
        val history = listOf(incident.copy(acknowledgedAtMillis = Long.MIN_VALUE))
        val incoming = RecordingIncident(occurredAtMillis = 300L)
        fun compactHistory() = compactRecordingIncidentDeletions(history, incoming) {
            val state = file.read()
            RecordingIncidentReplayOwners(
                appProcessPid = state.current.pid,
                appProcessStartedAtMillis = state.current.processStartedAtMillis,
                pendingAppProcesses = state.pending.map { it.marker },
            )
        }

        assertEquals(history, compactHistory())
        val queries = mutableListOf<PendingAppProcessExit>()
        val published = mutableListOf<RecordingIncident>()
        fun recover() = file.recover({ pending ->
            queries += pending
            if (pending == retired) incident else null
        }, { published += it })

        val failure = IOException("retirement directory sync")
        file.syncFailure = failure
        assertSame(failure, assertThrows(IOException::class.java) { recover() })
        assertEquals(1, file.writes)
        assertArrayEquals(expectedBytes, file.visible)
        assertEquals(history, compactHistory()) // The tombstone-owner read retries the failed sync.
        assertEquals(2, file.writes)
        assertArrayEquals(expectedBytes, file.visible)
        assertEquals(listOf(retired, remaining), queries)

        file.syncFailure = null
        assertTrue(compactHistory().isEmpty())
        assertEquals(3, file.writes)
        assertArrayEquals(expectedBytes, file.visible)
        repeat(2) {
            assertTrue(compactHistory().isEmpty())
            assertEquals(listOf(remaining), recover().pending)
        }
        assertEquals(listOf(retired, remaining, remaining, remaining), queries)
        assertEquals(listOf(incident), published)
        assertEquals(3, file.writes)
    }

    @Test
    fun failureBeforePublicationStarted_leavesPriorAndReplacementReadsAuthoritative() {
        val file = ProcessFile(v2Payload(survivors))
        val prior = file.read()
        val target = prior.copy(pending = emptyList())
        val failure = IOException("failed before finishWrite")
        assertSame(failure, assertThrows(IOException::class.java) {
            file.publication.write(file.key, prior, target) { throw failure }
        })
        assertEquals(prior, file.read())
        val replacement = AppProcessRecoveryState(first)
        file.visible = encoded(replacement)
        assertEquals(replacement, file.read())
        assertEquals(0, file.writes)
    }

    @Test
    fun uncertainCompaction_rejectsChangedEvictedPidUntilExactPriorIsObserved() {
        val original = v2Payload(rotatedLegacy)
        val file = ProcessFile(original)
        val prior = file.read()
        val target = AppProcessRecoveryState(prior.current, prior.pending)
        file.syncFailure = IOException("directory sync")
        assertThrows(IOException::class.java) {
            file.publication.write(file.key, prior, target) { started ->
                started()
                file.publish(target)
            }
        }
        val expectedBytes = v2Payload(target.pending)
        assertArrayEquals(expectedBytes, file.visible)
        file.syncFailure = null
        val replacement = v2Payload(survivors.reversed())
        val changedEvictedPid = v2Payload(rotatedLegacy.map { row ->
            if (row == oldestEvicted) row.copy(marker = row.marker.copy(pid = 10)) else row
        })
        val changedPrior = decoded(changedEvictedPid)
        assertTrue(changedPrior.needsCompaction)
        assertEquals(prior.current, changedPrior.current)
        assertEquals(prior.pending, changedPrior.pending)
        assertNotEquals(prior, changedPrior)
        for (unexpected in listOf(expectedBytes + byteArrayOf(1), replacement, changedEvictedPid)) {
            file.visible = unexpected.copyOf()
            assertThrows(IOException::class.java) { file.read() }
            assertArrayEquals(unexpected, file.visible)
            assertEquals(1, file.writes)
        }

        file.visible = original
        assertEquals(target, file.read())
        assertArrayEquals(expectedBytes, file.visible)
        assertEquals(target, file.read())
        assertEquals(2, file.writes)
    }

    @Test
    fun uncertainMarkerReplacement_rejectsEmptyV2InPlaceOfV1Prior() {
        val original = v1Payload(first)
        val file = ProcessFile(original)
        val failure = IOException("marker directory sync")
        file.syncFailure = failure
        assertSame(failure, assertThrows(IOException::class.java) {
            file.recover({ fail("Queried before marker sync"); null })
        })
        val target = AppProcessRecoveryState(second, listOf(PendingAppProcessExit(first, 200L)))
        assertArrayEquals(encoded(target), file.visible)

        val emptyV2 = encoded(AppProcessRecoveryState(first))
        file.visible = emptyV2.copyOf()
        file.syncFailure = null
        assertThrows(IOException::class.java) { file.read() }
        assertArrayEquals(emptyV2, file.visible)
        assertEquals(1, file.writes)

        file.visible = original
        assertEquals(target, file.read())
        assertArrayEquals(encoded(target), file.visible)
        assertEquals(target, file.read())
        assertEquals(2, file.writes)
    }

    @Test
    fun oversizedV2_rejectsCorruptEvictedRowTruncationAndTrailingBytesBeforeRecovery() {
        val bytes = v2Payload(rotatedLegacy)
        val evicted = rotatedLegacy.last()
        val invalidRows = listOf(
            evicted.copy(marker = evicted.marker.copy(pid = 0)),
            evicted.copy(marker = evicted.marker.copy(processStartedAtMillis = 0)),
            evicted.copy(endedByMillis = 0),
        )
        val corrupt = invalidRows.map { v2Payload(rotatedLegacy.dropLast(1) + it) }
        for (invalid in corrupt + listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1))) {
            assertThrows(IOException::class.java) {
                recoverAppProcessExits(
                    decoded(invalid), second, { fail("Overwrote corrupt legacy state") },
                    { fail("Queried corrupt state"); null }, { fail("Unexpected incident") }, cursor,
                )
            }
        }
    }

    @Test
    fun v2Codec_preservesWireBytesAt32AndRejectsWriting33() {
        val atLimit = AppProcessRecoveryState(second, survivors)
        val bytes = encoded(atLimit)
        assertArrayEquals(v2Payload(survivors), bytes)
        assertEquals(atLimit, decoded(bytes))
        val oversized = survivors + owner(90, 20, 60)
        assertThrows(IOException::class.java) { encoded(atLimit.copy(pending = oversized)) }
    }

    @Test
    fun timestampAndPidReuse_cannotSelectLaterProcessExitOrMergeDistinctLifetimes() {
        val owner = PendingAppProcessExit(first, 200L)
        assertTrue(appExitTimestampMatches(owner, 42, 150L))
        assertFalse(appExitTimestampMatches(owner, 43, 150L))
        assertFalse(appExitTimestampMatches(owner, 42, 99L))
        assertFalse(appExitTimestampMatches(owner, 42, 201L))
        assertFalse(recordingIncidentReferenceMatches(exit(), exit().copy(processStartedAtMillis = 101L)))
        assertFalse(unexpectedProcessExitAlreadyRecorded(
            listOf(exit().copy(description = "Uncaught exception on main")), 42, 150L,
            processStartedAtMillis = 101L,
        ))
    }

    @Test
    fun pendingOwner_preservesDeletedAppExitIdentityAcrossMarkerReplacement() {
        val deleted = exit().copy(acknowledgedAtMillis = Long.MIN_VALUE)
        val retained = retainRecordingIncidentDeletions(
            listOf(deleted),
            RecordingIncidentReplayOwners(appProcessPid = second.pid,
                appProcessStartedAtMillis = second.processStartedAtMillis, pendingAppProcesses = listOf(first)),
            RecordingIncident(occurredAtMillis = 300L),
        )
        assertEquals(listOf(deleted), retained)
    }

    @Test
    fun processCodec_readsV1AndV2AndRejectsTruncationTrailingBytesAndForgedCount() {
        assertEquals(AppProcessRecoveryState(first, sourceVersion = 1), decoded(v1Payload(first)))
        val state = AppProcessRecoveryState(second, listOf(PendingAppProcessExit(first, 200L)))
        val v2 = decoded(v2Payload(state.pending))
        assertEquals(state.current, v2.current)
        assertEquals(state.pending, v2.pending)
        val bytes = encoded(state)
        assertEquals(state, decoded(bytes))
        val forged = bytes.copyOf().also {
            // Versioned header (5), current PID/start (12), then pending count.
            it[17] = 0x7f; it[18] = 0x7f; it[19] = 0x7f; it[20] = 0x7f
        }
        for (invalid in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1), forged)) {
            try { decoded(invalid); fail("Accepted corrupt process state") } catch (_: IOException) { }
        }
    }
}
