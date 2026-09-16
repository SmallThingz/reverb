package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIncidentStoreTest {
    @Test
    fun onlyUnplannedProcessExitsCountAsRecordingIncidents() {
        listOf(
            EXIT_REASON_ANOMALY,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            EXIT_REASON_MEMORY_LIMITER,
            ApplicationExitInfo.REASON_SIGNALED,
        ).forEach { reason ->
            assertTrue("reason=$reason", isSpuriousRecordingProcessExitReason(reason))
        }

        listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
        ).forEach { reason ->
            assertFalse("reason=$reason", isSpuriousRecordingProcessExitReason(reason))
        }
    }

    @Test
    fun exitDispositionRetainsMissingEvidenceAndSeparatesKnownOutcomes() {
        assertTrue(recordingExitDisposition(null) == RecordingExitDisposition.PENDING)
        assertTrue(
            recordingExitDisposition(ApplicationExitInfo.REASON_CRASH) ==
                RecordingExitDisposition.INCIDENT,
        )
        assertTrue(
            recordingExitDisposition(ApplicationExitInfo.REASON_SIGNALED) ==
                RecordingExitDisposition.INCIDENT,
        )
        assertTrue(
            recordingExitDisposition(ApplicationExitInfo.REASON_PACKAGE_UPDATED) ==
                RecordingExitDisposition.EXPECTED,
        )
        assertTrue(
            recordingExitDisposition(ApplicationExitInfo.REASON_OTHER) ==
                RecordingExitDisposition.EXPECTED,
        )
    }

    @Test
    fun incidentKindUsesStableByteIdentity() {
        assertTrue(RecordingIncidentKind.UNEXPECTED_SHUTDOWN.storageCode in Byte.MIN_VALUE..Byte.MAX_VALUE)
        assertTrue(
            RecordingIncidentKind.fromStorageCode(RecordingIncidentKind.UNEXPECTED_SHUTDOWN.storageCode) ==
                RecordingIncidentKind.UNEXPECTED_SHUTDOWN,
        )
        assertTrue(RecordingIncidentKind.fromStorageCode(0x7f) == null)
    }

    @Test
    fun pendingIncidentCanCarryAnAlreadyObservedResumeTime() {
        val incident = RecordingIncident(
            occurredAtMillis = 10_000L,
            resumedAtMillis = 12_500L,
            exitReason = ApplicationExitInfo.REASON_CRASH,
        )
        assertFalse(incident.recoveryPending)
        assertTrue(recordingIncidentDowntimeMillis(incident) == 2_500L)
    }

    @Test
    fun oneCaptureResumeCompletesEveryStillOpenIncident() {
        val incidents = listOf(
            RecordingIncident(occurredAtMillis = 1_000L, resumedAtMillis = 0L),
            RecordingIncident(occurredAtMillis = 2_000L, resumedAtMillis = 0L),
            RecordingIncident(occurredAtMillis = 500L, resumedAtMillis = 900L),
        )
        val updated = completeRecordingIncidentDowntimes(incidents, 3_000L)
        assertTrue(updated[0].resumedAtMillis == 3_000L)
        assertTrue(updated[1].resumedAtMillis == 3_000L)
        assertTrue(updated[2].resumedAtMillis == 900L)
    }

    @Test
    fun downtimeAndAcknowledgementStayIndependentFromHistoryRetention() {
        val incident = RecordingIncident(
            occurredAtMillis = 1_000L,
            resumedAtMillis = 6_250L,
            acknowledgedAtMillis = 7_000L,
            exitReason = ApplicationExitInfo.REASON_SIGNALED,
        )
        assertTrue(incident.acknowledged)
        assertFalse(incident.recoveryPending)
        assertTrue(recordingIncidentDowntimeMillis(incident) == 5_250L)
        assertTrue(recordingExitReasonLabel(incident.exitReason) == "Signaled")
        assertTrue(recordingIncidentDowntimeMillis(incident.copy(resumedAtMillis = 0L)) == null)
    }
}
