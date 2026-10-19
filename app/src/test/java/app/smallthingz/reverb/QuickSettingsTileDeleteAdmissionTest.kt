package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsTileDeleteAdmissionTest {
    private val option = QuickSaveTileOption(0, "a".repeat(32))
    private val target = QuickSettingsTileDeleteTarget(option, editRevision = 7L)

    @Test fun confirmationUsesTheFocusedSheetAndCurrentParentDraft() {
        // The Settings window loses focus when this modal owns input. Its draft stays valid.
        assertTrue(target.canConfirm(target, 7L, option, sheetReady = true, mutationAvailable = true))
        assertFalse(target.canConfirm(target, 7L, option, sheetReady = false, mutationAvailable = true))
        // A hidden/restoring/persisting Settings owner supplies no revision even if the sheet remains.
        assertFalse(target.canConfirm(target, null, option, sheetReady = true, mutationAvailable = true))
    }

    @Test fun dismissedConfirmationCannotActOnANewConfirmationOfTheSameTile() {
        val reopened = QuickSettingsTileDeleteTarget(option, editRevision = 7L)
        assertFalse(target.canConfirm(null, 7L, option, sheetReady = true, mutationAvailable = true))
        assertFalse(target.canConfirm(reopened, 7L, option, sheetReady = true, mutationAvailable = true))
        assertTrue(reopened.canConfirm(reopened, 7L, option, sheetReady = true, mutationAvailable = true))
    }

    @Test fun revisedDraftOrReusedTileSlotInvalidatesTheConfirmation() {
        assertFalse(target.canConfirm(target, 8L, option, sheetReady = true, mutationAvailable = true))
        val replacement = option.copy(revision = "b".repeat(32))
        assertFalse(target.canConfirm(target, 7L, replacement, sheetReady = true, mutationAvailable = true))
        assertFalse(target.canConfirm(target, 7L, null, sheetReady = true, mutationAvailable = true))
        // Accepted work and failed data reads both revoke mutation availability.
        assertFalse(target.canConfirm(target, 7L, option, sheetReady = true, mutationAvailable = false))
    }
}
