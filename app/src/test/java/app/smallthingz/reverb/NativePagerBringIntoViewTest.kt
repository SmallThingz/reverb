package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePagerBringIntoViewTest {
    private fun distance(proposed: Float, offset: Float, size: Float,
        first: Int = 100, rtl: Boolean = false, forward: Boolean = true) =
        nativePagerBringIntoViewDistance(proposed, offset, size, 400f, 400, first, rtl, forward)

    @Test fun overflowingRequestMovesAnIntegralPageFromCurrentPhysicalOffset() {
        assertEquals(300f, distance(250f, 450f, 50f), 0f)
        assertEquals(-100f, distance(-50f, -70f, 40f), 0f)
    }

    @Test fun rtlUsesTheOppositePhysicalFirstPageOrigin() {
        assertEquals(100f, distance(100f, 450f, 50f, rtl = true), 0f)
        assertEquals(-300f, distance(-50f, -70f, 40f, rtl = true), 0f)
    }

    @Test fun fulfilledRequestStillSnapsInTheLastLogicalDirection() {
        assertEquals(300f, distance(0f, 100f, 30f, forward = true), 0f)
        assertEquals(-100f, distance(0f, 100f, 30f, forward = false), 0f)
        assertEquals(-300f, distance(0f, 100f, 30f, rtl = true, forward = true), 0f)
        assertEquals(100f, distance(0f, 100f, 30f, rtl = true, forward = false), 0f)
    }

    @Test fun settledFullyVisibleRequestDoesNotMove() {
        assertEquals(0f, distance(0f, 100f, 30f, first = 0), 0f)
        assertEquals(0f, distance(0f, 100f, 30f, first = 0, rtl = true), 0f)
    }

    @Test fun visibilityThresholdIsThePinnedInclusiveOnePixel() {
        assertEquals(300f, distance(20f, -2f, 3f, forward = false), 0f)
        assertEquals(-100f, distance(20f, -2f, 3.001f, forward = false), 0f)
    }

    @Test fun zeroWidthCannotStartAnUnfulfillableReveal() {
        assertEquals(0f, nativePagerBringIntoViewDistance(20f, 10f, 30f, 0f, 0, 0, false, true), 0f)
    }
}
