package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class LibraryExpansionRevealTest {
    @Test fun growthAndReducedViewportAdmitWorkButRepeatedPublicationDoesNot() {
        val request = LibraryExpansionReveal()
        request.update("last", true)
        assertTrue(request.changed(90, 400, 500))
        assertTrue(request.changed(310, 400, 500))
        request.update("last", true)
        assertFalse(request.changed(310, 400, 500))
        assertTrue(request.changed(310, 400, 240))
        assertFalse(request.changed(310, 400, 240))
    }

    @Test fun userInputWinsOverRemainingExpansionFramesAndSameStatePublications() {
        val request = LibraryExpansionReveal()
        request.update("last", true)
        assertTrue(request.changed(90, 400, 500))
        request.cancel()
        request.update("last", true)
        assertNull(request.requestedId)
        assertFalse(request.changed(310, 400, 500))
        request.update(null, false)
        request.update("last", true)
        assertTrue(request.changed(310, 400, 500))
    }

    @Test fun replacementAndPlaybackToInfoAreNewRequestsAndZeroViewportWaits() {
        val request = LibraryExpansionReveal()
        request.update("a", false)
        request.cancel()
        request.update("a", true)
        assertEquals("a", request.requestedId)
        assertFalse(request.changed(300, 400, 0))
        assertTrue(request.changed(300, 400, 500))
        request.update("b", true)
        assertEquals("b", request.requestedId)
        assertTrue(request.changed(300, 400, 500))
        request.update(null, false)
        assertFalse(request.changed(400, 400, 500))
    }
}
