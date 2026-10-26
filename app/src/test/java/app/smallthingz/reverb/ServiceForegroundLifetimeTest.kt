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
        assertEquals("component", requireServiceStarted { "component" })

        val error = assertThrows(IllegalStateException::class.java) {
            requireServiceStarted<String> { null }
        }

        assertTrue(error.message.orEmpty().contains("no component"))
    }

    @Test
    fun serviceStartPreservesThrownFailure() {
        val expected = SecurityException("denied")

        val actual = assertThrows(SecurityException::class.java) {
            requireServiceStarted<String> { throw expected }
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
    fun wakeLockAcquireSuccessTransfersOwnerWithoutRelease() {
        val owner = TestWakeLockOwner()

        val acquired = acquireWakeLockReportingFailure(
            create = { owner },
            configure = { it.held = true },
            isHeld = { it.held },
            release = { it.release() },
            onFailure = { throw AssertionError("unexpected failure", it) },
        )

        assertEquals(owner, acquired)
        assertEquals(0, owner.releaseCalls)
    }

    @Test
    fun wakeLockCreateFailureReportsWithoutConfigurationOrRelease() {
        val expected = IllegalStateException("create failed")
        var configureCalls = 0
        var releaseCalls = 0
        var observed: Exception? = null
        val createOwner: () -> TestWakeLockOwner = { throw expected }

        val acquired = acquireWakeLockReportingFailure(
            create = createOwner,
            configure = { configureCalls++ },
            isHeld = { false },
            release = { releaseCalls++ },
            onFailure = { observed = it },
        )

        assertTrue(acquired == null)
        assertEquals(expected, observed)
        assertEquals(0, configureCalls)
        assertEquals(0, releaseCalls)
    }

    @Test
    fun wakeLockAcquireFailureReleasesPartialOwnerAndReportsPrimary() {
        val owner = TestWakeLockOwner()
        val expected = IllegalStateException("acquire failed")
        var observed: Exception? = null

        val acquired = acquireWakeLockReportingFailure(
            create = { owner },
            configure = {
                it.held = true
                throw expected
            },
            isHeld = { it.held },
            release = { it.release() },
            onFailure = { observed = it },
        )

        assertTrue(acquired == null)
        assertEquals(expected, observed)
        assertEquals(1, owner.releaseCalls)
    }

    @Test
    fun wakeLockAcquireCleanupFailureIsSuppressedOnPrimary() {
        val cleanupFailure = IllegalStateException("release failed")
        val owner = TestWakeLockOwner(releaseFailure = cleanupFailure)
        val expected = IllegalStateException("acquire failed")
        var observed: Exception? = null

        val acquired = acquireWakeLockReportingFailure(
            create = { owner },
            configure = {
                it.held = true
                throw expected
            },
            isHeld = { it.held },
            release = { it.release() },
            onFailure = { observed = it },
        )

        assertTrue(acquired == null)
        assertEquals(expected, observed)
        assertEquals(listOf(cleanupFailure), expected.suppressed.toList())
        assertEquals(1, owner.releaseCalls)
    }

    @Test
    fun wakeLockAcquireReporterFailureCannotEscapeOptionalRuntimePath() {
        val owner = TestWakeLockOwner()

        val acquired = acquireWakeLockReportingFailure(
            create = { owner },
            configure = { throw IllegalStateException("configure failed") },
            isHeld = { it.held },
            release = { it.release() },
            onFailure = { throw IllegalStateException("report failed") },
        )

        assertTrue(acquired == null)
        assertEquals(0, owner.releaseCalls)
    }

    @Test
    fun wakeLockAcquireFailureBeforeHoldSkipsRelease() {
        val owner = TestWakeLockOwner()
        var observed: Exception? = null

        val acquired = acquireWakeLockReportingFailure(
            create = { owner },
            configure = { throw IllegalStateException("configure failed") },
            isHeld = { it.held },
            release = { it.release() },
            onFailure = { observed = it },
        )

        assertTrue(acquired == null)
        assertTrue(observed is IllegalStateException)
        assertEquals(0, owner.releaseCalls)
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
    private class TestWakeLockOwner(
        private val releaseFailure: Exception? = null,
    ) {
        var held = false
        var releaseCalls = 0

        fun release() {
            releaseCalls++
            held = false
            releaseFailure?.let { throw it }
        }
    }

}
