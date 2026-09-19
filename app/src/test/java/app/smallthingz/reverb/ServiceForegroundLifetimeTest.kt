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
    fun retentionProtectionFailureClearingOwnershipAllowsServiceStopConvergence() {
        var active = true
        var protectionCalls = 0

        assertFalse(
            retentionMaintenanceKeepsServiceAlive(
                isActive = { active },
                ensureProtectedLifetime = {
                    protectionCalls++
                    active = false
                },
            ),
        )
        assertEquals(1, protectionCalls)
    }

    @Test
    fun activeRetentionKeepsServiceAliveAfterProtectionAttempt() {
        var protectionCalls = 0

        assertTrue(
            retentionMaintenanceKeepsServiceAlive(
                isActive = { true },
                ensureProtectedLifetime = { protectionCalls++ },
            ),
        )
        assertEquals(1, protectionCalls)
    }

    @Test
    fun inactiveRetentionDoesNotAttemptForegroundProtection() {
        var protectionCalls = 0

        assertFalse(
            retentionMaintenanceKeepsServiceAlive(
                isActive = { false },
                ensureProtectedLifetime = { protectionCalls++ },
            ),
        )
        assertEquals(0, protectionCalls)
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
    fun wakeLockReleaseFailure_isReportedWithoutEscapingTeardown() {
        val expected = IllegalStateException("release failed")
        var observed: Exception? = null

        releaseWakeLockReportingFailure(
            isHeld = { true },
            release = { throw expected },
            onFailure = { observed = it },
        )

        assertEquals(expected, observed)
    }

    @Test
    fun wakeLockStateProbeFailure_isReportedWithoutReleaseRetry() {
        val expected = IllegalStateException("state failed")
        var releaseCalls = 0
        var observed: Exception? = null

        releaseWakeLockReportingFailure(
            isHeld = { throw expected },
            release = { releaseCalls++ },
            onFailure = { observed = it },
        )

        assertEquals(expected, observed)
        assertEquals(0, releaseCalls)
    }

    @Test
    fun wakeLockReleaseReporterFailure_cannotAbortTeardown() {
        releaseWakeLockReportingFailure(
            isHeld = { true },
            release = { throw IllegalStateException("release failed") },
            onFailure = { throw IllegalStateException("report failed") },
        )
    }

    @Test
    fun unheldWakeLock_skipsRelease() {
        var released = false
        var reported = false

        releaseWakeLockReportingFailure(
            isHeld = { false },
            release = { released = true },
            onFailure = { reported = true },
        )

        assertFalse(released)
        assertFalse(reported)
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
