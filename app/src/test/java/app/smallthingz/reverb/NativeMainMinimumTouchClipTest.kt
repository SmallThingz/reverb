package app.smallthingz.reverb

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression vectors from pinned NodeCoordinator minimum-aware semantic clipping. */
class NativeMainMinimumTouchClipTest {
    @Test fun fortySixPixelControlKeepsItsFortyEightPixelSemanticTarget() {
        assertEquals(Rect(-1f, -1f, 47f, 47f),
            nativeMinimumTouchClip(Rect(-1f, -1f, 47f, 47f), Rect(0f, 0f, 46f, 46f), 48f))
    }

    @Test fun partiallyClippedControlRetainsSourceHalfTargetOutsideScrollEdge() {
        assertEquals(Rect(0f, -24f, 100f, 25f),
            nativeMinimumTouchClip(Rect(0f, -25f, 100f, 25f), Rect(0f, 0f, 100f, 100f), 48f))
    }

    @Test fun whollyOutsideControlCannotGainAnIntersectionFromTargetExpansion() {
        assertTrue(nativeMinimumTouchClip(Rect(-80f, 5f, -32f, 53f),
            Rect(0f, 0f, 100f, 100f), 48f).isEmpty)
    }

    @Test fun clipCoordinatesRemainRelativeToActualLayerOrigin() {
        assertEquals(Rect(9f, 19f, 57f, 67f),
            nativeMinimumTouchClip(Rect(9f, 19f, 57f, 67f), Rect(10f, 20f, 56f, 66f), 48f))
    }
}
