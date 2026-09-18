package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIncidentStoreTest {
    @Test
    fun everyObservedProcessExitInterruptsAnArmedCapture() {
        listOf(
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            EXIT_REASON_MEMORY_LIMITER,
            EXIT_REASON_ANOMALY,
            999,
        ).forEach { reason ->
            assertTrue(
                "reason=$reason",
                recordingExitDisposition(reason) == RecordingExitDisposition.INCIDENT,
            )
        }
    }

    @Test
    fun missingExitEvidenceStaysPendingInsteadOfPretendingTheStopWasExpected() {
        assertTrue(recordingExitDisposition(null) == RecordingExitDisposition.PENDING)
    }

    @Test
    fun pendingIncidentQueueFailsClosedBeforeEvidenceWouldBeDropped() {
        assertTrue(pendingIncidentQueueCanAppend(0))
        assertTrue(pendingIncidentQueueCanAppend(MAX_PENDING_INCIDENT_SESSIONS - 1))
        assertFalse(pendingIncidentQueueCanAppend(MAX_PENDING_INCIDENT_SESSIONS))
        assertFalse(pendingIncidentQueueCanAppend(MAX_PENDING_INCIDENT_SESSIONS + 1))
    }

    @Test
    fun processLocalIncidentRetry_bindsOnlyToTheArmedSessionFromThisProcessLifetime() {
        assertTrue(
            processLocalIncidentMarkerMatches(
                armed = true, markerPid = 41, markerProcessStartElapsedRealtimeMillis = 100L,
                markerArmedAtMillis = 1_000L, expectedPid = 41,
                expectedProcessStartElapsedRealtimeMillis = 100L, stopOccurredAtMillis = 2_000L,
            ),
        )
        assertFalse(
            processLocalIncidentMarkerMatches(
                armed = false, markerPid = 41, markerProcessStartElapsedRealtimeMillis = 100L,
                markerArmedAtMillis = 1_000L, expectedPid = 41,
                expectedProcessStartElapsedRealtimeMillis = 100L, stopOccurredAtMillis = 2_000L,
            ),
        )
        assertFalse(
            processLocalIncidentMarkerMatches(
                armed = true, markerPid = 40, markerProcessStartElapsedRealtimeMillis = 100L,
                markerArmedAtMillis = 1_000L, expectedPid = 41,
                expectedProcessStartElapsedRealtimeMillis = 100L, stopOccurredAtMillis = 2_000L,
            ),
        )
        assertFalse(
            processLocalIncidentMarkerMatches(
                armed = true, markerPid = 41, markerProcessStartElapsedRealtimeMillis = 99L,
                markerArmedAtMillis = 1_000L, expectedPid = 41,
                expectedProcessStartElapsedRealtimeMillis = 100L, stopOccurredAtMillis = 2_000L,
            ),
        )
        assertFalse(
            processLocalIncidentMarkerMatches(
                armed = true, markerPid = 41, markerProcessStartElapsedRealtimeMillis = 100L,
                markerArmedAtMillis = 2_001L, expectedPid = 41,
                expectedProcessStartElapsedRealtimeMillis = 100L, stopOccurredAtMillis = 2_000L,
            ),
        )
    }

    @Test
    fun externalStopReasonsHaveUsefulIncidentLabels() {
        assertTrue(recordingExitReasonLabel(ApplicationExitInfo.REASON_PACKAGE_UPDATED) == "Package updated")
        assertTrue(recordingExitReasonLabel(ApplicationExitInfo.REASON_USER_REQUESTED) == "User requested stop")
        assertTrue(recordingExitReasonLabel(ApplicationExitInfo.REASON_PERMISSION_CHANGE) == "Permission change")
        assertTrue(recordingExitReasonLabel(ApplicationExitInfo.REASON_OTHER) == "System stop")
    }



    @Test
    fun captureStartSeparatesTransparentRestartFromStaleSameProcessSession() {
        assertTrue(
            captureSessionStartDisposition(false, continuousRestart = false) ==
                CaptureSessionStartDisposition.NEW_SESSION,
        )
        assertTrue(
            captureSessionStartDisposition(true, continuousRestart = true) ==
                CaptureSessionStartDisposition.CONTINUE_SESSION,
        )
        assertTrue(
            captureSessionStartDisposition(true, continuousRestart = false) ==
                CaptureSessionStartDisposition.RESOLVE_INTERRUPTED_SESSION,
        )
    }

    @Test
    fun provisionalIncidentIsEnrichedWhenExitEvidenceArrives() {
        val provisional = RecordingIncident(
            occurredAtMillis = 5_000L,
            resumedAtMillis = 0L,
            exitReason = android.app.ApplicationExitInfo.REASON_UNKNOWN,
            pid = 77,
            captureArmedAtMillis = 1_000L,
            description = "pending",
        )
        val classified = provisional.copy(
            occurredAtMillis = 4_000L,
            resumedAtMillis = 6_000L,
            exitReason = android.app.ApplicationExitInfo.REASON_CRASH,
            description = "crash",
        )
        val merged = mergeRecordingIncidentEvidence(provisional, classified)
        assertTrue(recordingIncidentsShareCaptureSession(provisional, classified))
        assertEquals(4_000L, merged.occurredAtMillis)
        assertEquals(6_000L, merged.resumedAtMillis)
        assertEquals(android.app.ApplicationExitInfo.REASON_CRASH, merged.exitReason)
        assertEquals("crash", merged.description)
    }

    @Test
    fun incidentReferenceSurvivesEvidenceTimestampEnrichment() {
        val provisional = RecordingIncident(
            occurredAtMillis = 10_000L,
            pid = 42,
            captureArmedAtMillis = 9_000L,
        )
        val enriched = provisional.copy(
            occurredAtMillis = 10_500L,
            exitReason = ApplicationExitInfo.REASON_CRASH,
        )
        assertTrue(recordingIncidentReferenceMatches(enriched, provisional))
        assertTrue(recordingIncidentReferenceMatches(provisional, enriched))
        assertFalse(
            recordingIncidentReferenceMatches(
                enriched.copy(captureArmedAtMillis = 8_000L),
                provisional,
            ),
        )
    }

    @Test
    fun priorProcessExitEvidenceIsBoundedBeforeCurrentProcessStart() {
        assertTrue(exitTimestampBelongsToPriorProcess(10_500L, 10_000L, 11_000L))
        assertTrue(exitTimestampBelongsToPriorProcess(11_000L, 10_000L, 11_000L))
        assertFalse(exitTimestampBelongsToPriorProcess(9_999L, 10_000L, 11_000L))
        assertFalse(exitTimestampBelongsToPriorProcess(11_001L, 10_000L, 11_000L))
    }

    @Test
    fun provisionalServiceStopMergesLaterExitEvidenceWithoutDuplicatingSession() {
        val provisional = RecordingIncident(
            occurredAtMillis = 10_000L,
            resumedAtMillis = 12_000L,
            acknowledgedAtMillis = 13_000L,
            exitReason = ApplicationExitInfo.REASON_UNKNOWN,
            pid = 42,
            captureArmedAtMillis = 9_000L,
            description = "Recorder service stopped while capture was running",
        )
        val classified = RecordingIncident(
            occurredAtMillis = 10_500L,
            resumedAtMillis = 0L,
            exitReason = ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            exitStatus = 7,
            pid = 42,
            captureArmedAtMillis = 9_000L,
            description = "package updated",
        )

        assertTrue(recordingIncidentsShareCaptureSession(provisional, classified))
        val merged = mergeRecordingIncidentEvidence(provisional, classified)
        assertTrue(merged.exitReason == ApplicationExitInfo.REASON_PACKAGE_UPDATED)
        assertTrue(merged.exitStatus == 7)
        assertTrue(merged.occurredAtMillis == 10_000L)
        assertTrue(merged.resumedAtMillis == 12_000L)
        assertTrue(merged.acknowledgedAtMillis == 13_000L)
        assertTrue(merged.description == "package updated")
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
    fun acknowledgementCanBeCheckedAndUncheckedWithoutChangingIncidentIdentity() {
        val incident = RecordingIncident(
            occurredAtMillis = 1_000L,
            resumedAtMillis = 2_000L,
            exitReason = ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        )
        val checked = toggleRecordingIncidentAcknowledgement(incident, acknowledgedAtMillis = 3_000L)
        assertTrue(checked.acknowledged)
        assertTrue(checked.acknowledgedAtMillis == 3_000L)
        assertTrue(checked.occurredAtMillis == incident.occurredAtMillis)
        assertTrue(checked.resumedAtMillis == incident.resumedAtMillis)

        val unchecked = toggleRecordingIncidentAcknowledgement(checked, acknowledgedAtMillis = 4_000L)
        assertFalse(unchecked.acknowledged)
        assertTrue(unchecked.acknowledgedAtMillis == 0L)
        assertTrue(unchecked.occurredAtMillis == incident.occurredAtMillis)
    }

    @Test
    fun incidentStopSummaryShowsStopTimeAndDurationWithoutRestartTime() {
        val incident = RecordingIncident(
            occurredAtMillis = 10_000L,
            resumedAtMillis = 15_250L,
        )
        val summary = formatIncidentStopSummary(incident)
        assertTrue(summary.startsWith("Stopped at "))
        assertTrue(summary.contains(" for 0:06"))
        assertFalse(summary.contains("→"))
        assertFalse(summary.contains("stopped for"))
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

    @Test
    fun incidentHistoryMutation_reportsFailureWithoutEscapingProcessOwner() {
        val expected = IOException("history unavailable")
        var observed: Exception? = null

        runIncidentHistoryMutation(
            mutation = { throw expected },
            onFailure = { observed = it },
        )

        assertSame(expected, observed)
    }

    @Test
    fun incidentHistoryMutation_failureReporterCannotKillProcessOwner() {
        var mutationRan = false

        runIncidentHistoryMutation(
            mutation = {
                mutationRan = true
                throw IOException("history unavailable")
            },
            onFailure = { throw IllegalStateException("feedback unavailable") },
        )

        assertTrue(mutationRan)
    }
}
