package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDirectoryPickerStateTest {
    private val tree = "content://example.documents/tree/primary%3AMusic%2FReverb"
    private val newerTree = "content://example.documents/tree/primary%3ANewer"

    @Test
    fun restoredRegistryResultWaitsForFreshHydrationAndAppliesOnce() {
        val original = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(original.beginRequest())
        val savedBeforeDestroy = original.save()
        original.setPanelActive(false)

        // The launcher key and request are restored together. Registry registration can
        // synchronously deliver the successful result before the IO hydration effect returns.
        val restored = SettingsDirectoryPickerState.restore(savedBeforeDestroy)
        assertTrue(restored.acceptsResult)
        restored.receiveResult(tree)
        assertNull(restored.takePendingResult(hydrated = false))
        assertFalse(restored.beginRequest())
        assertEquals(tree, restored.takePendingResult(hydrated = true))
        assertNull(restored.takePendingResult(hydrated = true))
        assertTrue(restored.beginRequest())
    }

    @Test
    fun hydrationFailuresKeepAcceptedResultThroughAnotherRecreation() {
        val state = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(state.beginRequest())
        state.receiveResult(tree)
        repeat(3) { assertNull(state.takePendingResult(hydrated = false)) }

        val restored = SettingsDirectoryPickerState.restore(state.save())
        assertFalse(restored.acceptsResult)
        assertNull(restored.takePendingResult(hydrated = false))
        assertEquals(tree, restored.takePendingResult(hydrated = true))
        assertNull(restored.takePendingResult(hydrated = true))
    }

    @Test
    fun closedOutstandingRequestCannotAttachToReopenedActivation() {
        val state = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(state.beginRequest())
        state.setPanelActive(false)
        val restored = SettingsDirectoryPickerState.restore(state.save())
        restored.setPanelActive(true)
        assertFalse(restored.acceptsResult)
        assertFalse(restored.beginRequest())
        restored.receiveResult(tree)
        assertNull(restored.takePendingResult(hydrated = true))

        assertTrue(restored.beginRequest())
        restored.receiveResult(newerTree)
        assertEquals(newerTree, restored.takePendingResult(hydrated = true))
    }

    @Test
    fun closingAfterResultDiscardsPendingSelectionBeforeReopen() {
        val state = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(state.beginRequest())
        state.receiveResult(tree)
        state.setPanelActive(false)
        assertNull(state.takePendingResult(hydrated = true))
        val restored = SettingsDirectoryPickerState.restore(state.save())
        restored.setPanelActive(true)
        assertNull(restored.takePendingResult(hydrated = true))
        assertTrue(restored.beginRequest())
    }

    @Test
    fun cancelledRestoredRequestDrainsWithoutCreatingAnEdit() {
        val original = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(original.beginRequest())
        val restored = SettingsDirectoryPickerState.restore(original.save())
        restored.receiveResult(null)
        assertNull(restored.takePendingResult(hydrated = true))
        assertFalse(restored.acceptsResult)
        assertTrue(restored.beginRequest())
    }

    @Test
    fun newerExplicitDirectoryEditWinsOverPendingSelection() {
        val state = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(state.beginRequest())
        state.receiveResult(tree)
        // Default-folder and Undo retire only this pending directory transaction.
        state.retire()
        assertNull(state.takePendingResult(hydrated = true))
        assertTrue(state.beginRequest())
        state.receiveResult(newerTree)
        assertEquals(newerTree, state.takePendingResult(hydrated = true))
    }

    @Test
    fun explicitEditWhilePickerOutstandingRetiresOnlyThatRequest() {
        val state = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(state.beginRequest())
        state.retire()
        assertFalse(state.acceptsResult)
        assertFalse(state.beginRequest())
        state.receiveResult(tree)
        assertNull(state.takePendingResult(hydrated = true))
        assertTrue(state.beginRequest())
    }

    @Test
    fun unownedAndDuplicateTerminalResultsCannotCreateOrReplaceAnEdit() {
        val state = SettingsDirectoryPickerState(panelActive = true)
        state.receiveResult(tree)
        assertNull(state.takePendingResult(hydrated = true))
        assertTrue(state.beginRequest())
        state.receiveResult(tree)
        state.receiveResult(newerTree)
        assertEquals(tree, state.takePendingResult(hydrated = true))
        state.receiveResult(newerTree)
        assertNull(state.takePendingResult(hydrated = true))
    }

    @Test
    fun rejectedGrantOrLaunchClearsRequestWithoutChangingDirectory() {
        val state = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(state.beginRequest())
        // Production passes null after a validation/grant rejection or a thrown launch.
        state.receiveResult(null)
        assertNull(state.takePendingResult(hydrated = true))
        assertTrue(state.beginRequest())
        state.receiveResult(newerTree)
        assertEquals(newerTree, state.takePendingResult(hydrated = true))
    }
}
