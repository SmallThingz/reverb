package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeOnboardingPlacedPagesTest {
    @Test fun settledHasOnlyItsOwnPage() {
        assertEquals(0..0, nativeOnboardingPlacedPages(400, 0))
        assertEquals(1..1, nativeOnboardingPlacedPages(400, 400))
        assertEquals(2..2, nativeOnboardingPlacedPages(400, 800))
    }

    @Test fun anyNonzeroFractionPlacesBothNeighborsWithoutAThirdPage() {
        assertEquals(0..1, nativeOnboardingPlacedPages(400, 1))
        assertEquals(0..1, nativeOnboardingPlacedPages(400, 399))
        assertEquals(1..2, nativeOnboardingPlacedPages(400, 401))
        assertEquals(1..2, nativeOnboardingPlacedPages(400, 799))
    }

    @Test fun zeroWidthUsesThePinnedMeasureLastPage() {
        assertEquals(2..2, nativeOnboardingPlacedPages(0, 0))
    }
}
