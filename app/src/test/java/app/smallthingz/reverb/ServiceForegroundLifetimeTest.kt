package app.smallthingz.reverb

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceForegroundLifetimeTest {
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
