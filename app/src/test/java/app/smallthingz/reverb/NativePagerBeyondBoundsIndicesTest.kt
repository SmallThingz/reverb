package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePagerBeyondBoundsIndicesTest {
    @Test fun fillPageSearchKeepsSourceTwoItemBudget() {
        assertEquals(listOf(1, 2), nativePagerBeyondBoundsIndices(0, 0, true, true))
        assertEquals(listOf(1, 0), nativePagerBeyondBoundsIndices(2, 2, false, true))
    }
    @Test fun fractionalViewportBeginsOutsideAlreadyPlacedPages() {
        assertEquals(listOf(2), nativePagerBeyondBoundsIndices(0, 1, true, true))
        assertEquals(listOf(0), nativePagerBeyondBoundsIndices(1, 2, false, true))
    }
    @Test fun disabledOrEmptyPagerCannotGenerateFocusNodes() {
        assertEquals(emptyList<Int>(), nativePagerBeyondBoundsIndices(0, 0, true, false))
        assertEquals(emptyList<Int>(), nativePagerBeyondBoundsIndices(0, -1, true, true))
        assertEquals(emptyList<Int>(), nativePagerBeyondBoundsIndices(0, 0, true, true, 0))
    }
    @Test fun edgesDoNotWrapIntoAnotherPage() {
        assertEquals(emptyList<Int>(), nativePagerBeyondBoundsIndices(2, 2, true, true))
        assertEquals(emptyList<Int>(), nativePagerBeyondBoundsIndices(0, 0, false, true))
    }
}
