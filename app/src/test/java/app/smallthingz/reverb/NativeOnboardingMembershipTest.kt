package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeOnboardingMembershipTest {
    private data class Membership(
        val placed: IntRange,
        val expected: Set<Int>,
        val actualMask: Int,
    )

    /** The previous production set expression is the independent membership oracle. */
    private fun membership(placed: IntRange, search: Set<Int>, focused: Int?): Membership =
        Membership(
            placed,
            (placed.toSet() + search + listOfNotNull(focused)).toSortedSet(),
            nativeOnboardingPageMembershipMask(placed, search, focused),
        )

    private fun searchPages(bits: Int): Set<Int> = (0..2).filterTo(linkedSetOf()) {
        bits and (1 shl it) != 0
    }

    private fun checkMembership(value: Membership) {
        assertEquals(value.expected.toList(), (0..2).filter { value.actualMask and (1 shl it) != 0 })
        assertEquals(0, value.actualMask and 7.inv())
    }

    @Test
    fun allPagePinsAndMembershipTransitionsMatchPriorOrderedSets() {
        val cases = buildList {
            for (first in 0..2) for (last in first..2) {
                for (search in 0..7) for (focused in listOf(null, 0, 1, 2)) {
                    add(membership(first..last, searchPages(search), focused).also(::checkMembership))
                }
            }
        }
        assertEquals(192, cases.size)
        for (before in cases) for (after in cases) {
            val previousUnchanged = after.expected == before.expected && after.placed == before.placed
            val cachedUnchanged = after.actualMask == before.actualMask && after.placed == before.placed
            assertEquals(previousUnchanged, cachedUnchanged)
        }
    }

    @Test
    fun actualViewportPlacementBoundariesRetainSearchAndFocusedPins() {
        for (width in listOf(0, 1, 17, 480, 1080)) {
            val maximum = width.toLong() * 2
            val offsets = listOf(0L, width.toLong() - 1, width.toLong(), width.toLong() + 1, maximum)
                .map { it.coerceIn(0L, maximum) }.distinct()
            for (offset in offsets) for (search in 0..7) for (focused in listOf(null, 0, 1, 2)) {
                checkMembership(membership(nativeOnboardingPlacedPages(width, offset), searchPages(search), focused))
            }
        }
    }
}
