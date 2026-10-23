package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeOnboardingTouchBoundsTest {
    private val minimum = (48f * (421f * 0.00625f)).let { Size(it, it) }

    @Test fun scrolledLargeRowKeepsItsSourceTouchTopAcrossViewportClip() {
        // Retained hosts13 axis scene: original row top110, viewport top153, height745.
        val local = Rect(0f, -43f, 954f, 702f)
        val clipped = nativeOnboardingClipTouchBounds(local, Size(954f, 1954f), minimum)
        assertEquals(Rect(63f, 110f, 1017f, 855f), clipped.translate(Offset(63f, 153f)))
    }

    @Test fun fartherPartialRowIsLimitedToHalfMinimumBeyondAncestor() {
        val clipped = nativeOnboardingClipTouchBounds(
            Rect(0f, -200f, 954f, 545f), Size(954f, 1954f), minimum)
        assertEquals(Rect(0f, -minimum.height / 2f, 954f, 545f), clipped)
    }

    @Test fun whollyOffscreenRowDoesNotAcquireExpandedClipAdmission() {
        assertEquals(Rect.Zero, nativeOnboardingClipTouchBounds(
            Rect(0f, -300f, 954f, -1f), Size(954f, 1954f), minimum))
    }

    @Test fun smallInitialTargetExpandsSymmetricallyBeforeAncestorClipping() {
        val initial = nativeOnboardingMinimumTouchBounds(Size(104f, 104f), minimum)
        val padding = (minimum.width - 104f) / 2f
        assertEquals(Rect(-padding, -padding, 104f + padding, 104f + padding), initial)
        assertEquals(initial, nativeOnboardingClipTouchBounds(initial, Size(104f, 104f), minimum))
    }
}
