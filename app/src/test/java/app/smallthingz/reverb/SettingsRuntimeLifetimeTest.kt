package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRuntimeLifetimeTest {
    @Test
    fun overlappingAcceptedAppliesReleaseOnlyAfterLastTerminal() {
        val lifetime = SettingsRuntimeLifetime()

        lifetime.begin()
        lifetime.begin()
        assertTrue(lifetime.isActive())

        assertFalse(lifetime.finish())
        assertTrue(lifetime.isActive())

        assertTrue(lifetime.finish())
        assertFalse(lifetime.isActive())
    }

    @Test
    fun terminalWithoutAcceptedApplyFailsFast() {
        val lifetime = SettingsRuntimeLifetime()

        assertThrows(IllegalStateException::class.java) {
            lifetime.finish()
        }
        assertFalse(lifetime.isActive())
    }

    @Test
    fun keepalivePreservesStickyRestartOnlyForHealthyCaptureIntent() {
        assertTrue(
            settingsRuntimeKeepaliveShouldBeSticky(
                listeningIntentEnabled = true,
                foregroundStartBlocked = false,
                foregroundServiceTimedOut = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            settingsRuntimeKeepaliveShouldBeSticky(
                listeningIntentEnabled = false,
                foregroundStartBlocked = false,
                foregroundServiceTimedOut = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            settingsRuntimeKeepaliveShouldBeSticky(
                listeningIntentEnabled = true,
                foregroundStartBlocked = true,
                foregroundServiceTimedOut = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            settingsRuntimeKeepaliveShouldBeSticky(
                listeningIntentEnabled = true,
                foregroundStartBlocked = false,
                foregroundServiceTimedOut = true,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            settingsRuntimeKeepaliveShouldBeSticky(
                listeningIntentEnabled = true,
                foregroundStartBlocked = false,
                foregroundServiceTimedOut = false,
                persistenceFailureBlocked = true,
            ),
        )
    }
}
