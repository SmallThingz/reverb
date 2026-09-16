package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIncidentStoreTest {
    @Test
    fun onlyUnplannedProcessExitsCountAsRecordingIncidents() {
        listOf(
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
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
    fun incidentKindUsesStableByteIdentity() {
        assertTrue(RecordingIncidentKind.UNEXPECTED_SHUTDOWN.storageCode in Byte.MIN_VALUE..Byte.MAX_VALUE)
        assertTrue(
            RecordingIncidentKind.fromStorageCode(RecordingIncidentKind.UNEXPECTED_SHUTDOWN.storageCode) ==
                RecordingIncidentKind.UNEXPECTED_SHUTDOWN,
        )
        assertTrue(RecordingIncidentKind.fromStorageCode(0x7f) == null)
    }
}
