package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class LibraryRenameRevealTest {
    @Test fun recordedLandscapeViewportDoesNotTradeTheOutlineForVisibleActions() {
        // QA79cbc4: viewport253..499; header253..569; editor337..401; actions454..559.
        // The outline starts at 42, before the EditText at 84. Revealing from 70 clips it.
        assertNull(libraryRenameRevealTop(316, 42, 246))
    }

    @Test fun fieldAndActionsShareTheViewportWhenOnlyLeadingPaddingMustScroll() {
        val top = checkNotNull(libraryRenameRevealTop(316, 42, 280))
        assertEquals(36, top)
        assertTrue(top <= 42)
        assertEquals(280, 316 - top)
    }

    @Test fun fittingHeaderKeepsItsWholeOutlineAndPadding() {
        assertEquals(0, libraryRenameRevealTop(316, 42, 600))
    }

    @Test fun oversizedHeaderRevealsTheFieldUnlessValidationNeedsBottomActions() {
        assertNull(libraryRenameRevealTop(410, 42, 246))
        assertEquals(164, libraryRenameRevealTop(410, 42, 246, validation = true))
        assertNull(libraryRenameRevealTop(410, 42, 0, validation = true))
    }
}
