package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AppProcessExitRecoveryTest {
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

    @Test
    fun queryBudget_rotatesWithoutDroppingOwnersOrChangingPriorUpperBound() {
        val owners = (1..20).map { PendingAppProcessExit(AppProcessMarker(it, it.toLong()), 100L) }
        var durable = AppProcessRecoveryState(second, owners)
        val queried = mutableListOf<PendingAppProcessExit>()
        repeat(3) {
            var calls = 0
            recoverAppProcessExits(durable, second, { durable = it },
                { queried += it; calls++; null }, { fail("Unexpected incident") })
            assertEquals(8, calls)
            assertEquals(owners.toSet(), durable.pending.toSet())
        }
        assertEquals(owners.toSet(), queried.toSet())
        val third = AppProcessMarker(44, 300L)
        recoverAppProcessExits(durable, third, { durable = it }, { null }, { fail("Unexpected incident") })
        assertTrue(durable.pending.containsAll(owners))
        assertTrue(durable.pending.contains(PendingAppProcessExit(second, 300L)))
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
    fun processCodec_readsV1AndRoundTripsV2_rejectsTruncationTrailingBytesAndForgedCount() {
        val legacy = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(0x52495032); it.writeByte(1); it.writeInt(first.pid); it.writeLong(100L)
            }
        }.toByteArray()
        assertEquals(AppProcessRecoveryState(first), decoded(legacy))
        val state = AppProcessRecoveryState(second, listOf(PendingAppProcessExit(first, 200L)))
        val bytes = encoded(state)
        assertEquals(state, decoded(bytes))
        val forged = bytes.copyOf().also {
            // v2 header (5), current PID/start (12), then pending count.
            it[17] = 0x7f; it[18] = 0x7f; it[19] = 0x7f; it[20] = 0x7f
        }
        for (invalid in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1), forged)) {
            try { decoded(invalid); fail("Accepted corrupt process state") } catch (_: IOException) { }
        }
    }
}
