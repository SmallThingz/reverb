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
