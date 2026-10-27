package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackConfirmationTest {
    @Test fun firstBackPreservesDraftAndQuickSecondConfirmsOnlyOnce() {
        val confirmation = SettingsBackConfirmation()
        assertFalse(confirmation.confirm(100, 8))
        assertTrue(confirmation.confirm(2_100, 8))
        assertFalse(confirmation.confirm(2_101, 8))
    }

    @Test fun expiredBackStartsANewWindow() {
        val confirmation = SettingsBackConfirmation()
        assertFalse(confirmation.confirm(100, 8))
        assertFalse(confirmation.confirm(2_101, 8))
        assertTrue(confirmation.confirm(2_102, 8))
    }

    @Test fun editedDraftNeedsItsOwnConfirmation() {
        val confirmation = SettingsBackConfirmation()
        assertFalse(confirmation.confirm(100, 8))
        assertFalse(confirmation.confirm(101, 9))
        assertTrue(confirmation.confirm(102, 9))
    }

    @Test fun pausedOrReopenedPanelCannotConsumePreviousConfirmation() {
        val confirmation = SettingsBackConfirmation()
        assertFalse(confirmation.confirm(100, 8))
        confirmation.reset()
        assertFalse(confirmation.confirm(101, 8))
    }

    @Test fun backwardsClockNeverConfirms() {
        val confirmation = SettingsBackConfirmation()
        assertFalse(confirmation.confirm(100, 8))
        assertFalse(confirmation.confirm(99, 8))
    }
}
