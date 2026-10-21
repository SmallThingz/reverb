package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class NativeMainNavigationStateTest {
    @Test fun restorationKeepsOnlyReleasedSaveableFields() {
        val saved = NativeMainSavedNavigation(true, 1, true, true, true)
        val state = NativeMainNavigationState(saved)
        assertEquals(saved, state.save())
        assertFalse(state.librarySelectionActive)
        assertFalse(state.libraryExpandedRecordingActive)
        assertFalse(state.libraryUndoVisible)
        assertNull(state.dragTarget)
        assertEquals(0f, state.settingsDragProgress, 0f)
    }

    @Test fun eachRevealStartsBothProgressValuesAtZeroAndUsesActualStartBand() {
        val state = NativeMainNavigationState()
        assertEquals(NativeMainPanel.SETTINGS, state.beginVerticalReveal(48f, 100f))
        state.verticalRevealDelta(30f, 100f)
        state.cancelVerticalReveal()
        assertEquals(NativeMainPanel.LIBRARY, state.beginVerticalReveal(52f, 100f))
        assertEquals(0f, state.settingsDragProgress, 0f)
        state.verticalRevealDelta(-20f, 100f)
        state.cancelVerticalReveal()
        assertNull(state.beginVerticalReveal(50f, 100f))
        assertEquals(0f, state.libraryDragProgress, 0f)
    }

    @Test fun exactCommitThresholdAndCancelPreserveReleasedNavigation() {
        val state = NativeMainNavigationState()
        state.beginVerticalReveal(0f, 100f)
        state.verticalRevealDelta(12f, 100f)
        state.endVerticalReveal()
        assertTrue(state.showSettings)
        state.closeSettings()
        state.beginVerticalReveal(100f, 100f)
        state.verticalRevealDelta(-50f, 100f)
        state.cancelVerticalReveal()
        assertFalse(state.showLibrary)
        assertEquals(0f, state.libraryTarget(), 0f)
    }

    @Test fun hiddenPanelsRetainTheirExistingOwnersAfterFirstReveal() {
        val state = NativeMainNavigationState()
        assertFalse(state.retainSettings(0f))
        state.beginVerticalReveal(0f, 100f)
        assertTrue(state.retainSettings(0f))
        state.cancelVerticalReveal()
        assertTrue(state.retainSettings(0f))
        assertFalse(state.retainLibrary(0f))
        assertTrue(state.retainLibrary(.01f))
        assertTrue(state.retainLibrary(0f))
    }

    @Test fun selectionAndExpansionBlockOnlyParentLibraryBack() {
        val state = NativeMainNavigationState()
        state.openLibrary()
        assertTrue(state.libraryBackEnabled)
        state.librarySelectionActive = true
        assertFalse(state.libraryBackEnabled)
        assertTrue(state.libraryActive)
        state.librarySelectionActive = false
        state.libraryExpandedRecordingActive = true
        assertFalse(state.libraryBackEnabled)
        assertTrue(state.libraryActive)
    }

    @Test fun closingIncidentMotionRetainsMainGatesUntilItsActualVisibilityBoundary() {
        val state = NativeMainNavigationState()
        state.openLibrary()
        state.openIncidents()
        state.bindIncidentsOverlayVisibility(true)
        state.closeIncidents()
        assertFalse(state.libraryBackEnabled)
        assertFalse(state.libraryActive)
        state.bindIncidentsOverlayVisibility(false)
        assertTrue(state.libraryBackEnabled)
        assertTrue(state.libraryActive)
    }

    @Test fun aboutSuppressesMainBackAndDeliveryWithoutChangingSavedPanelFlags() {
        val state = NativeMainNavigationState()
        state.openSettings(1)
        state.openAbout()
        assertFalse(state.settingsBackEnabled)
        assertFalse(state.settingsPanelActive)
        assertFalse(state.feedbackPlacement(0f).deliveryAllowed)
        state.closeAbout()
        assertTrue(state.settingsBackEnabled)
        assertEquals(1, state.settingsBufferTargetCode)
    }

    @Test fun feedbackReturnsToHomePaddingImmediatelyOnSettingsClose() {
        val state = NativeMainNavigationState()
        state.openSettings()
        assertEquals(NativeMainFeedbackPlacement(true, 2f, 18, 20, false), state.feedbackPlacement(0f))
        state.closeSettings()
        assertEquals(NativeMainFeedbackPlacement(true, 2f, 18, 104, false), state.feedbackPlacement(0f))
        assertFalse(state.saveFeedbackVisible(.5f, 0f))
        assertTrue(state.homeVisualizerVisible)
    }

    @Test fun undoDeliveryGateUsesLibraryFlagButLibraryExitStillKeepsPanelPaddingAndZ() {
        val state = NativeMainNavigationState()
        state.openLibrary()
        state.libraryUndoVisible = true
        assertFalse(state.feedbackPlacement(1f).deliveryAllowed)
        state.closeLibrary()
        assertEquals(NativeMainFeedbackPlacement(true, 5f, 18, 20, true), state.feedbackPlacement(.5f))
        assertEquals(NativeMainFeedbackPlacement(true, 2f, 18, 104, false), state.feedbackPlacement(0f))
    }

    @Test fun libraryHeaderNavigationClosesLibraryWithoutResettingUnrelatedFlags() {
        val state = NativeMainNavigationState()
        state.openLibrary()
        state.openSettingsFromLibrary()
        assertFalse(state.showLibrary)
        assertTrue(state.showSettings)
        assertEquals(-1, state.settingsBufferTargetCode)
        state.closeSettings()
        state.openLibrary()
        state.openIncidentsFromLibrary()
        assertFalse(state.showLibrary)
        assertTrue(state.showIncidents)
    }
}
