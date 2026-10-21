package app.smallthingz.reverb

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class NativeDirectionalFocusTest {
    private val source = Rect(0f, 0f, 10f, 10f)

    @Test fun horizontalBeamWinsEvenWhenFartherThanDiagonalCandidate() {
        assertTrue(nativeIsBetterFocusCandidate(Rect(100f, 0f, 110f, 10f),
            Rect(11f, 11f, 21f, 21f), source, FocusDirection.Right))
    }
    @Test fun verticalBeamDoesNotDefeatCompletelyCloserDiagonalCandidate() {
        assertFalse(nativeIsBetterFocusCandidate(Rect(0f, 100f, 10f, 110f),
            Rect(11f, 11f, 21f, 21f), source, FocusDirection.Down))
    }
    @Test fun oppositeDirectionNeverBecomesNextFocus() {
        assertNull(nativeBestDirectionalFocusCandidate(listOf(Rect(-20f, 0f, -10f, 10f)),
            source, FocusDirection.Right) { it })
    }
    @Test fun equalDistanceRetainsPlacementOrder() {
        val a = Rect(11f, 0f, 21f, 10f)
        val b = Rect(11f, 0f, 21f, 10f)
        val candidates = listOf("first" to a, "second" to b)
        assertEquals("first", nativeBestDirectionalFocusCandidate(candidates, source,
            FocusDirection.Right) { it.second }?.first)
    }
    @Test fun sourceDistanceConvertsToLongBeforeWeighting() {
        assertFalse(nativeIsBetterFocusCandidate(Rect(10.1f, 0f, 20.1f, 10f),
            Rect(10.9f, 0f, 20.9f, 10f), source, FocusDirection.Right))
    }
}
