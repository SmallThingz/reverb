package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class LibraryRenameRevealTest {
    @Test fun recordedLandscapeViewportCanShowFieldAndActionsWithoutLeadingPadding() {
        // QA79cbc4: viewport253..499; header253..569; editor337..401; actions454..559.
        val top = checkNotNull(libraryRenameRevealTop(316, 84, 246))
        assertEquals(70, top)
        assertTrue(316 - top <= 246)
        assertTrue(84 >= top && 148 <= 316) // Entire field retained in the requested region.
        assertTrue(201 >= top && 306 <= 316) // Both action bounds retained too.
    }

    @Test fun fittingHeaderKeepsItsWholeOutlineAndPadding() {
        assertEquals(0, libraryRenameRevealTop(316, 84, 600))
    }

    @Test fun OversizedHeaderKeepsCaretUnlessValidationNeedsBottomActions() {
        assertNull(libraryRenameRevealTop(410, 84, 246))
        assertEquals(164, libraryRenameRevealTop(410, 84, 246, validation = true))
        assertNull(libraryRenameRevealTop(410, 84, 0, validation = true))
    }
}
