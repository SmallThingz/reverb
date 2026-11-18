package app.smallthingz.reverb

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceForegroundLifetimeTest {

    @Test
    fun durableLifecycleRejectionHasFactualReasonAndNoPlatformCause() {
        for (rejection in DurableSaveLifetimeRejection.entries) {
            val result = attemptDurableSaveLifetimeProtection(rejection,
                start = { throw AssertionError("Rejected work must not start a service") },
                promote = { throw AssertionError("Rejected work must not promote foreground") })
            val failure = result.failure as DurableSaveLifetimeRejectedException
            assertSame(rejection, failure.rejection)
            assertEquals(rejection.explanation, failure.message)
            assertEquals(null, failure.cause)
        }
    }


    @Test
    fun durableLifetimeDoesNotConvertFatalFailure() {
        val fatal = AssertionError("fatal")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            attemptDurableSaveLifetimeProtection(null, { throw fatal }, {})
        })
    }

    @Test
    fun coldRecompressionTerminalKeepsCaptureUntilInitializationRestoresRuntime() {
        // The export worker can fail before the audio initialization/main restoration handoff.
        // READY is not healthy capture yet, but a valid pending restore still owns the Service.
        assertFalse(serviceHasHealthyListeningLifetime(ReverbService.STATE_READY, true, false, false, false))
        assertTrue(serviceHasPendingCaptureInitializationLifetime(true, true, false, false, false))
        // Initialization transfers ownership to normal listening; it must not leave a permanent
        // pending owner that could preserve a stopped recorder after a later terminal export.
        assertFalse(serviceHasPendingCaptureInitializationLifetime(false, true, false, false, false))
        assertTrue(serviceHasHealthyListeningLifetime(ReverbService.STATE_LISTENING, true, false, false, false))
        assertFalse(serviceHasHealthyListeningLifetime(ReverbService.STATE_READY, true, false, false, false))
    }

    @Test
    fun pendingCaptureInitializationNeverPreservesDisabledOrFailedCapture() {
        for (mask in 0 until 16) {
            val enabled = mask and 1 != 0
            val foregroundBlocked = mask and 2 != 0
            val timedOut = mask and 4 != 0
            val storageBlocked = mask and 8 != 0
            assertEquals(enabled && !foregroundBlocked && !timedOut && !storageBlocked,
                serviceHasPendingCaptureInitializationLifetime(true, enabled, foregroundBlocked, timedOut, storageBlocked))
            assertFalse(serviceHasPendingCaptureInitializationLifetime(false, enabled, foregroundBlocked, timedOut, storageBlocked))
        }
    }

    @Test
    fun serviceStartRequiresReturnedComponent() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireServiceStarted { null }
        }

        assertTrue(error.message.orEmpty().contains("no component"))
    }

    @Test
    fun serviceStartPreservesThrownFailure() {
        val expected = SecurityException("denied")

        val actual = assertThrows(SecurityException::class.java) {
            requireServiceStarted { throw expected }
        }

        assertSame(expected, actual)
    }

    @Test
    fun retentionUsesDataSyncWhenMicrophoneDoesNotOwnLifetime() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundServiceTypesForWork(
                listening = false,
                exporting = false,
                retaining = true,
            ),
        )
    }

    @Test
    fun microphoneRemainsExclusiveWhenRetentionRunsDuringCapture() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypesForWork(
                listening = true,
                exporting = false,
                retaining = true,
            ),
        )
    }

    @Test
    fun retentionDoesNotBorrowUnacquiredHigherPriorityLifetime() {
        assertFalse(
            retentionMaintenanceHasProtectedLifetime(
                healthyListeningLifetime = false,
                higherPriorityWorkActive = true,
                foregroundServiceTypes = 0,
            ),
        )
        assertTrue(
            retentionMaintenanceHasProtectedLifetime(
                healthyListeningLifetime = false,
                higherPriorityWorkActive = true,
                foregroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ),
        )
        assertTrue(
            retentionMaintenanceHasProtectedLifetime(
                healthyListeningLifetime = true,
                higherPriorityWorkActive = false,
                foregroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ),
        )
        assertFalse(
            retentionMaintenanceHasProtectedLifetime(
                healthyListeningLifetime = false,
                higherPriorityWorkActive = false,
                foregroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ),
        )
    }

    @Test
    fun dataSyncNotificationHandoffKeepsHigherPriorityWorkVisible() {
        assertTrue(exportShouldRefreshDataSyncNotification(0))
        assertTrue(
            exportShouldRefreshDataSyncNotification(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ),
        )
        assertFalse(
            exportShouldRefreshDataSyncNotification(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ),
        )

        assertTrue(
            clearShouldRefreshDataSyncNotification(
                foregroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                exportActive = false,
            ),
        )
        assertFalse(
            clearShouldRefreshDataSyncNotification(
                foregroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                exportActive = true,
            ),
        )
        assertFalse(
            clearShouldRefreshDataSyncNotification(
                foregroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                exportActive = false,
            ),
        )
    }

    @Test
    fun foregroundTimeoutBlocksRetentionRestartUntilServiceRecovery() {
        assertTrue(
            retentionMaintenanceMayRun(
                serviceDestroying = false,
                foregroundServiceTimedOut = false,
            ),
        )
        assertFalse(
            retentionMaintenanceMayRun(
                serviceDestroying = false,
                foregroundServiceTimedOut = true,
            ),
        )
        assertFalse(
            retentionMaintenanceMayRun(
                serviceDestroying = true,
                foregroundServiceTimedOut = false,
            ),
        )
    }

    @Test
    fun staleKeepaliveCannotStopHealthyListeningLifetime() {
        assertTrue(
            serviceHasHealthyListeningLifetime(
                recorderState = ReverbService.STATE_LISTENING,
                listeningIntentEnabled = true,
                foregroundStartBlocked = false,
                foregroundServiceTimedOut = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            serviceHasHealthyListeningLifetime(
                recorderState = ReverbService.STATE_LISTENING,
                listeningIntentEnabled = true,
                foregroundStartBlocked = true,
                foregroundServiceTimedOut = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            serviceHasHealthyListeningLifetime(
                recorderState = ReverbService.STATE_PAUSED,
                listeningIntentEnabled = true,
                foregroundStartBlocked = false,
                foregroundServiceTimedOut = false,
                persistenceFailureBlocked = false,
            ),
        )
    }
}
