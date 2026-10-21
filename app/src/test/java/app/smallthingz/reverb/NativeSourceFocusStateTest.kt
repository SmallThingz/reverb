package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

/**
 * Source contract tests for the actual coordinator. View callback timing, traversal geometry and
 * Window attachment are the adapter's separate source/runtime obligations, not mocked passes here.
 */
class NativeSourceFocusStateTest {
    private class Target(val migrated: Boolean, var focused: Boolean = true, var valid: Boolean = true)
    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class Search(val direction: Int, val rect: Rect?, val active: Target?)

    private class Probe {
        val state = NativeSourceFocusState<Target>()
        val searches = mutableListOf<Search>()
        val resets = mutableListOf<Int>()
        var parks = 0
        var ownerFocused = false
        var hasFocus = false
        var parkResult = true
        var resetResult = true
        var searchResult: (Search) -> Boolean = { false }
        fun request(direction: Int = DOWN, rect: Rect? = null): Boolean =
            nativeRequestSourceOwnerFocus(
                state = state,
                ownerFocused = ownerFocused,
                activeIsMigrated = { it.migrated && it.valid && it.focused },
                direction = direction,
                previousRect = rect,
                hasFocus = hasFocus,
                isOneDimensional = { it == NEXT || it == PREVIOUS },
                parkOwner = { parks++; parkResult },
                search = { d, r, a -> Search(d, r, a).let { searches += it; searchResult(it) } },
                reset = { resets += it; resetResult },
            )
    }

    @Test fun placementAvailabilityDistinguishesMigratedTargetsFromOriginalAndroidViews() {
        // FocusTarget.onPlaced is gated off in UI1.11.2; original AndroidView notifications remain.
        assertFalse(nativeReportsSourceFocusAvailability(retainedAndroidView = false))
        assertTrue(nativeReportsSourceFocusAvailability(retainedAndroidView = true))
    }

    @Test fun stableMigratedDescendantRepresentsFocusedComposeOwner() {
        val p = Probe()
        val active = Target(migrated = true)
        p.state.focused(active)
        assertTrue(p.request(NEXT, Rect(9, 8, 30, 40)))
        assertSame(active, p.state.active)
        assertTrue(p.searches.isEmpty())
        assertTrue(p.resets.isEmpty())
        assertEquals(0, p.parks)
    }

    @Test fun ownerAlreadyFocusedDoesNotSearchOrMoveCurrentTarget() {
        val p = Probe()
        p.ownerFocused = true
        assertTrue(p.request(LEFT, Rect(1, 2, 8, 9)))
        assertTrue(p.searches.isEmpty())
        assertTrue(p.resets.isEmpty())
        assertEquals(0, p.parks)
    }

    @Test fun stillAttachedInteropLossPreservesTargetDirectionAndPriorRectForRetry() {
        val p = Probe()
        val old = Target(migrated = false, focused = false)
        val rect = Rect(13, 29, 61, 82)
        p.state.focused(old)
        p.state.childCleared(retainedAndroidViewStillAttached = true)
        p.searchResult = { it.rect == null }
        assertTrue(p.request(UP, rect))
        assertFalse(p.state.pendingOwnerInvalidation)
        assertEquals(2, p.searches.size)
        assertSame(rect, p.searches[0].rect)
        assertNull(p.searches[1].rect)
        p.searches.forEach { assertEquals(UP, it.direction); assertSame(old, it.active) }
        assertEquals(0, p.parks)
        assertTrue(p.resets.isEmpty())
    }

    @Test fun removedFocusedTargetParksBeforeAnySearch() {
        val p = Probe()
        p.state.focused(Target(migrated = false))
        p.state.childCleared(retainedAndroidViewStillAttached = false)
        assertTrue(p.request(DOWN, Rect(0, 70, 50, 100)))
        assertTrue(p.state.pendingOwnerInvalidation)
        assertNull(p.state.active)
        assertEquals(1, p.parks)
        assertTrue(p.searches.isEmpty())
        assertTrue(p.resets.isEmpty())
    }

    @Test fun failedParkingDoesNotFallThroughToPrematureSearch() {
        val p = Probe()
        p.state.focused(Target(migrated = true))
        p.state.childCleared(retainedAndroidViewStillAttached = false)
        p.parkResult = false
        p.searchResult = { true }
        assertFalse(p.request())
        assertTrue(p.state.pendingOwnerInvalidation)
        assertTrue(p.searches.isEmpty())
        assertTrue(p.resets.isEmpty())
    }

    @Test fun settledInvalidationSearchesWithoutRemovedTarget() {
        val p = Probe()
        p.state.focused(Target(migrated = false))
        p.state.childCleared(retainedAndroidViewStillAttached = false)
        assertTrue(p.request())
        p.state.retireInvalidation()
        p.searchResult = { true }
        assertTrue(p.request(DOWN))
        assertEquals(1, p.searches.size)
        assertEquals(Search(DOWN, null, null), p.searches.single())
        assertEquals(1, p.parks)
    }

    @Test fun genuineNewFocusCancelsPendingInvalidationAndKeepsNewTarget() {
        val p = Probe()
        p.state.focused(Target(migrated = true))
        p.state.childCleared(retainedAndroidViewStillAttached = false)
        val newTarget = Target(migrated = true)
        p.state.focused(newTarget)
        assertFalse(p.state.pendingOwnerInvalidation)
        assertTrue(p.request(NEXT))
        assertSame(newTarget, p.state.active)
        assertTrue(p.searches.isEmpty())
        assertEquals(0, p.parks)
    }

    @Test fun ownerRetirementClearsBothPendingInvalidationAndOldIdentity() {
        val p = Probe()
        p.state.focused(Target(migrated = false))
        p.state.childCleared(retainedAndroidViewStillAttached = false)
        p.state.clear()
        assertNull(p.state.active)
        assertFalse(p.state.pendingOwnerInvalidation)
        p.searchResult = { true }
        assertTrue(p.request())
        assertEquals(Search(DOWN, null, null), p.searches.single())
        assertEquals(0, p.parks)
    }

    @Test fun oneDimensionalWrapOccursOnlyAfterBothSearchesFail() {
        for (direction in listOf(NEXT, PREVIOUS)) {
            val p = Probe()
            val active = Target(migrated = false)
            p.state.focused(active)
            p.hasFocus = true
            val rect = Rect(4, 7, 17, 22)
            assertTrue(p.request(direction, rect))
            assertEquals(listOf(Search(direction, rect, active), Search(direction, null, active)), p.searches)
            assertEquals(listOf(direction), p.resets)
            assertEquals(0, p.parks)
        }
    }

    @Test fun spatialSearchAndUnfocusedOwnerNeverUseOneDimensionalWrap() {
        for (direction in listOf(LEFT, UP, RIGHT, DOWN, NEXT, PREVIOUS)) {
            val p = Probe()
            p.hasFocus = direction !in listOf(NEXT, PREVIOUS)
            assertFalse(p.request(direction, Rect(3, 5, 7, 11)))
            assertEquals(2, p.searches.size)
            assertTrue(p.resets.isEmpty())
            assertEquals(0, p.parks)
        }
    }

    @Test fun successfulFirstSearchStopsBeforeNullRectRetryAndWrap() {
        val p = Probe()
        val rect = Rect(6, 12, 18, 24)
        p.hasFocus = true
        p.searchResult = { true }
        assertTrue(p.request(PREVIOUS, rect))
        assertEquals(listOf(Search(PREVIOUS, rect, null)), p.searches)
        assertTrue(p.resets.isEmpty())
        assertEquals(0, p.parks)
    }

    private companion object {
        const val NEXT = 1
        const val PREVIOUS = 2
        const val LEFT = 17
        const val UP = 33
        const val RIGHT = 66
        const val DOWN = 130
    }
}
