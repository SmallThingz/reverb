package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun unrelatedUnappliedCapacityEditsKeepExistingActionsButCannotAuthorizeANewDuration() {
        val baseline = SettingsSnapshot(format = ExportFormat.WAV, codec = ExportCodec.PCM_16,
            sampleRate = 48_000, channelMode = ChannelMode.MONO,
            oneShotRetentionTime = 60, loopingRetentionTime = 300)
        val previews = listOf(
            baseline.copy(sampleRate = 32_000),
            baseline.copy(channelMode = ChannelMode.STEREO),
            baseline.copy(oneShotRetentionTime = 120),
        )
        for (preview in previews) {
            val capacityReady = !settingsTileCapacityDraftChanged(baseline, preview, "1", "5", "0", "0")
            assertFalse(capacityReady)
            // Row actions receive the owner's live revision, independently of duration admission.
            val actionRevision = quickSettingsTileActionRevision(7L, 7L)
            val durationRevision = settingsTileDurationRevision(actionRevision, capacityReady)
            assertEquals(7L, actionRevision)
            assertTrue(target.canConfirm(target, actionRevision, option, sheetReady = true, mutationAvailable = true))
            assertNull(durationRevision)
            val capacities = TileCapacityLoad()
            val pending = capacities.begin(7L)!!
            assertFalse(capacities.finish(pending, durationRevision, TileCapacities(3_600, 300.0, 60.0)))
            assertNull(capacities.ready(durationRevision))
        }
    }

    @Test fun invalidCapacityTextAlsoKeepsExistingActionsWithoutUnlockingCreation() {
        val baseline = SettingsSnapshot(oneShotRetentionTime = 60, loopingRetentionTime = 300)
        val capacityReady = !settingsTileCapacityDraftChanged(baseline, baseline, "invalid", "5", "0", "0")
        assertFalse(capacityReady)
        val actionRevision = quickSettingsTileActionRevision(7L, 7L)
        assertTrue(target.canConfirm(target, actionRevision, option, sheetReady = true, mutationAvailable = true))
        assertNull(settingsTileDurationRevision(actionRevision, capacityReady))
    }

    @Test fun unavailableOwnerAndChangedParentRejectExistingCallbacksEvenWithAppliedCapacity() {
        // Hidden, detached, persisting, unhydrated, unfocused, and inactive owners supply null.
        assertNull(quickSettingsTileActionRevision(7L, null))
        assertNull(settingsTileDurationRevision(null, capacityDraftReady = true))
        // A retained callback cannot silently attach itself to the next parent revision.
        assertNull(quickSettingsTileActionRevision(7L, 8L))
        val current = quickSettingsTileActionRevision(8L, 8L)
        assertEquals(8L, current)
        assertFalse(target.canConfirm(target, current, option, sheetReady = true, mutationAvailable = true))
        assertEquals(8L, settingsTileDurationRevision(current, capacityDraftReady = true))
    }
}
