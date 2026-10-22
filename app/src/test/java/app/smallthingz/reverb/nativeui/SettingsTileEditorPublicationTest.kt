package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.QuickSaveTileOption
import app.smallthingz.reverb.QuickSaveUnit
import app.smallthingz.reverb.QuickSettingsTileDeleteTarget
import app.smallthingz.reverb.QuickSettingsTileEditorTarget
import app.smallthingz.reverb.QuickSettingsTilesEditorState
import app.smallthingz.reverb.ReverbService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTileEditorPublicationTest {
    @Test fun parentBackClosesCreationWithoutParentDraftChangeOrExternalEvent() {
        val state = QuickSettingsTilesEditorState()
        val publication = SettingsTileEditorPublication()
        state.target = QuickSettingsTileEditorTarget()
        assertTrue(publication.changed(state))
        repeat(20) { assertFalse(publication.changed(state)) }
        assertTrue(state.closeEditor())
        assertTrue(publication.changed(state))
        // A synchronous child publication during reconciliation must not bind recursively forever.
        assertFalse(publication.changed(state))
    }

    @Test fun parentBackClosesDeleteAndPendingRestoration() {
        val option = QuickSaveTileOption(2, "a".repeat(32), ReverbService.BufferSlot.LOOPING, 2, QuickSaveUnit.MINUTES)
        val deleting = QuickSettingsTilesEditorState().also {
            it.deleting = QuickSettingsTileDeleteTarget(option, 0L)
        }
        val saved = QuickSettingsTileEditorTarget().saveDraft()
        val pending = QuickSettingsTilesEditorState.restore(saved)
        for (state in listOf(deleting, pending)) {
            val publication = SettingsTileEditorPublication()
            assertTrue(publication.changed(state))
            assertTrue(state.closeEditor())
            assertTrue(publication.changed(state))
            assertFalse(publication.changed(state))
        }
    }

    @Test fun acceptedMutationDoesNotCloseOrRebindUnchangedEditor() {
        val state = QuickSettingsTilesEditorState()
        state.target = QuickSettingsTileEditorTarget().also { it.busy = true }
        val publication = SettingsTileEditorPublication()
        assertTrue(publication.changed(state))
        assertFalse(state.closeEditor())
        assertFalse(publication.changed(state))
        state.target!!.busy = false
        assertTrue(state.closeEditor())
        assertTrue(publication.changed(state))
    }

    @Test fun childDraftEditsStayChildOwnedButReplacingEditorRebinds() {
        val state = QuickSettingsTilesEditorState()
        val first = QuickSettingsTileEditorTarget()
        state.target = first
        val publication = SettingsTileEditorPublication()
        assertTrue(publication.changed(state))
        first.seconds = 120
        first.selectBuffer(ReverbService.BufferSlot.ONE_SHOT)
        assertFalse(publication.changed(state))
        state.target = QuickSettingsTileEditorTarget()
        assertTrue(publication.changed(state))
        publication.reset()
        assertTrue(publication.changed(state))
        assertFalse(publication.changed(state))
    }
}
