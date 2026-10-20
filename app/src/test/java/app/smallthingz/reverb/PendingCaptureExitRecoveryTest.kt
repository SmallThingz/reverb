package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCaptureExitRecoveryTest {
    private val first = ActiveRecordingSessionMarker(
        armed = true,
        pid = 42,
        processStartElapsedRealtimeMillis = 10L,
        armedAtMillis = 150L,
        armedElapsedRealtimeMillis = 60L,
        packageLastUpdateTimeMillis = 1L,
    )

    private fun encode(pending: List<PendingRecordingSession>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { RecordingIncidentStore.writePendingSessionsPayload(it, pending) }
        }.toByteArray()

    private fun decode(bytes: ByteArray): List<PendingRecordingSession> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            readIncidentPayloadExact(input, "pending capture test", RecordingIncidentStore::readPendingSessionsPayload)
        }

    private fun legacyBytes(pending: List<PendingRecordingSession>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x52495031)
                output.writeByte(1)
                output.writeShort(pending.size)
                pending.forEach { session ->
                    with(session.marker) {
                        output.writeBoolean(armed)
                        output.writeInt(pid)
                        output.writeLong(processStartElapsedRealtimeMillis)
                        output.writeLong(armedAtMillis)
                        output.writeLong(armedElapsedRealtimeMillis)
                        output.writeLong(packageLastUpdateTimeMillis)
                    }
                    output.writeLong(session.resumedAtMillis)
                }
            }
        }.toByteArray()

    @Test
    fun firstKnownDeadBoundary_survivesCodecReplayAndLaterPidReuse_withoutClockReset() {
        // P1 armed at 150 and died at 200; P2 first observed it dead at process start 300.
        var durable = encode(appendPendingRecordingSession(emptyList(), first, 300L))
        for (laterStart in listOf(400L, 600L, 900L)) {
            val restarted = decode(durable)
            assertEquals(300L, restarted.single().endedByMillis)
            // Even an active-marker retirement retry cannot widen the queued owner.
            val retried = appendPendingRecordingSession(restarted, first, laterStart)
            assertEquals(restarted, retried)
            // A later P3 reused PID 42 and died at 500. This is the production query predicate.
            assertFalse(pendingCaptureExitTimestampMatches(retried.single(), 42, 500L))
            assertTrue(pendingCaptureExitTimestampMatches(retried.single(), 42, 200L))
            durable = encode(retried)
        }
        assertEquals(first, decode(durable).single().marker)
    }

    @Test
    fun matchingRequiresExactPidAndBothPersistedBounds() {
        val owner = PendingRecordingSession(first, endedByMillis = 300L)
        assertTrue(pendingCaptureExitTimestampMatches(owner, 42, 150L))
        assertTrue(pendingCaptureExitTimestampMatches(owner, 42, 300L))
        assertFalse(pendingCaptureExitTimestampMatches(owner, 43, 200L))
        assertFalse(pendingCaptureExitTimestampMatches(owner, 42, 149L))
        assertFalse(pendingCaptureExitTimestampMatches(owner, 42, 301L))
        assertFalse(pendingCaptureExitTimestampMatches(owner.copy(endedByMillis = 0L), 42, 200L))
    }

    @Test
    fun legacyCodecAndReenqueue_preserveUnknownAuthorityAndResumeTime() {
        val legacy = listOf(PendingRecordingSession(first, resumedAtMillis = 700L))
        val decoded = decode(legacyBytes(legacy))
        assertEquals(legacy, decoded)
        assertSame(decoded, appendPendingRecordingSession(decoded, first, 900L))
        val rewritten = encode(decoded)
        assertEquals(3, rewritten[4].toInt())
        assertEquals(legacy, decode(rewritten))
        // A resume timestamp alone cannot authorize a process exit before that resume.
        assertFalse(pendingCaptureExitTimestampMatches(decode(rewritten).single(), 42, 500L))
    }

    @Test
    fun legacyOwnerStaysUnknownAcrossLaterAppRecoveryAndRepeatedRestarts() {
        var captureBytes = legacyBytes(listOf(PendingRecordingSession(first, resumedAtMillis = 700L)))
        var appState = AppProcessRecoveryState(AppProcessMarker(42, 100L))
        for (start in listOf(600L, 900L, 1_200L)) {
            // App recovery can later manufacture a matching-PID/process owner with a later end
            // boundary. Even on the next restart it is not proof of the legacy capture boundary.
            recoverAppProcessExits(appState, AppProcessMarker(99, start), { appState = it }, { null }, {
                throw AssertionError("Missing app evidence was published")
            })
            assertTrue(appState.pending.any { it.marker == AppProcessMarker(42, 100L) })
            val restored = decode(captureBytes)
            val retried = appendPendingRecordingSession(restored, first, start)
            assertSame(restored, retried)
            assertEquals(0L, retried.single().endedByMillis)
            assertFalse(pendingCaptureExitTimestampMatches(retried.single(), 42, 500L))
            captureBytes = encode(retried)
        }
        assertEquals(first, decode(captureBytes).single().marker)
        assertEquals(700L, decode(captureBytes).single().resumedAtMillis)
    }

    @Test
    fun fixedBoundsCannotBeWidenedByResume() {
        val bounded = listOf(PendingRecordingSession(first, endedByMillis = 300L))
        val resumed = decode(encode(bounded.map { it.copy(resumedAtMillis = 800L) }))
        assertEquals(300L, resumed.single().endedByMillis)
        assertFalse(pendingCaptureExitTimestampMatches(resumed.single(), 42, 500L))
    }

    @Test
    fun mixedQueueCodec_preservesOrderUnknownOwnersAndExistingBounds() {
        val unknown = PendingRecordingSession(first.copy(pid = 43))
        val fixed = PendingRecordingSession(first.copy(pid = 44), endedByMillis = 250L)
        val pending = listOf(unknown, fixed)
        val updated = appendPendingRecordingSession(pending, first, 300L)
        assertEquals(listOf(unknown, fixed, PendingRecordingSession(first, endedByMillis = 300L)), decode(encode(updated)))
    }

    @Test
    fun newOwnerPublicationFailure_retainsActiveMarkerUntilDurableRetry() {
        for (visiblePublication in listOf(false, true)) {
            val durableBefore = encode(emptyList())
            var visible = durableBefore
            var activeMarker: ActiveRecordingSessionMarker? = first
            val failure = IOException("pending directory barrier")
            assertSame(failure, assertThrows(IOException::class.java) {
                publishPendingRecordingSession(decode(durableBefore), first, 300L) {
                    if (visiblePublication) visible = encode(it)
                    throw failure
                }
                activeMarker = null
            })
            assertEquals(first, activeMarker)
            // Retry in the same process retains its stable start, including visible unconfirmed bytes.
            publishPendingRecordingSession(decode(visible), first, 300L) { visible = encode(it) }
            activeMarker = null
            assertEquals(null, activeMarker)
            assertEquals(300L, decode(visible).single().endedByMillis)
            assertEquals(first, decode(visible).single().marker)
        }
    }

    @Test
    fun acceptedOldEvidence_preservesCheckedAndDeletedSessionIdentityAfterQueueReplay() {
        val owner = decode(encode(listOf(PendingRecordingSession(first, resumedAtMillis = 350L, endedByMillis = 300L)))).single()
        for (acknowledgement in listOf(0L, 400L, Long.MIN_VALUE)) {
            val provisional = RecordingIncident(occurredAtMillis = 300L, resumedAtMillis = 350L,
                acknowledgedAtMillis = acknowledgement, pid = first.pid,
                processStartedAtMillis = recordingSessionProcessStartedAtMillis(first), captureArmedAtMillis = first.armedAtMillis)
            assertFalse(pendingCaptureExitTimestampMatches(owner, 42, 500L))
            assertTrue(pendingCaptureExitTimestampMatches(owner, 42, 200L))
            val evidence = provisional.copy(occurredAtMillis = 200L, acknowledgedAtMillis = 0L,
                exitReason = ApplicationExitInfo.REASON_CRASH, description = "Original process")
            val enriched = mergeRecordingIncidentEvidence(provisional, evidence)
            assertTrue(recordingIncidentReferenceMatches(provisional, enriched))
            assertEquals(recordingIncidentPresentationKey(provisional), recordingIncidentPresentationKey(enriched))
            assertEquals(acknowledgement, enriched.acknowledgedAtMillis)
            assertEquals(350L, enriched.resumedAtMillis)
            assertEquals(200L, enriched.occurredAtMillis)
        }
    }

    @Test
    fun pendingCodec_rejectsTruncationTrailingBytesUnknownVersionsAndInvalidBounds() {
        val valid = encode(listOf(PendingRecordingSession(first, endedByMillis = 300L)))
        val negative = valid.copyOf().apply { for (index in size - 8 until size) this[index] = -1 }
        val invalid = listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(1),
            valid.copyOf().apply { this[4] = 4 }, negative,
            valid.copyOf().apply { this[5] = 0; this[6] = 0x81.toByte() },
            legacyBytes(listOf(PendingRecordingSession(first))) + byteArrayOf(0))
        invalid.forEach { bytes -> assertThrows(IOException::class.java) { decode(bytes) } }
        assertEquals(emptyList<PendingRecordingSession>(), decode(encode(emptyList())))
    }

    @Test
    fun capacityFailureAndInvalidNewBounds_preserveEveryExistingOwner() {
        val full = (1..MAX_PENDING_INCIDENT_SESSIONS).map { PendingRecordingSession(first.copy(pid = it), endedByMillis = 300L) }
        val original = encode(full)
        assertThrows(IOException::class.java) { appendPendingRecordingSession(full, first.copy(pid = 999), 600L) }
        assertArrayEquals(original, encode(full))
        assertSame(full, appendPendingRecordingSession(full, first, 900L))
        assertThrows(IOException::class.java) { appendPendingRecordingSession(emptyList(), first, 0L) }
        for (invalid in listOf(full + PendingRecordingSession(first), listOf(PendingRecordingSession(first, endedByMillis = -1L)))) {
            val bytes = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                RecordingIncidentStore.writePendingSessionsPayload(DataOutputStream(bytes), invalid)
            }
            assertEquals(0, bytes.size())
        }
    }
}
