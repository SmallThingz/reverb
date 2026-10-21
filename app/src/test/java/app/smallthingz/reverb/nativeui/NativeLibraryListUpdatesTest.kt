package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class NativeLibraryListUpdatesTest {
    private class Owner(val id: String)
    private fun state(
        selected: Set<String> = emptySet(),
        selecting: Boolean = false,
        blocked: Set<String> = emptySet(),
        owner: Owner? = null,
        trim: String? = null,
        active: Boolean = true,
    ) = NativeLibraryRowState(selected, selecting, blocked, owner?.id, owner, trim, active)

    @Test fun noOpKeepsRevisionAndDoesNotInvalidateRows() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B")
        val initial = updates.submit(catalog, state())!!
        assertTrue(initial.catalogChanged)
        assertTrue(initial.allVisibleRows)
        val revision = updates.revision
        assertNull(updates.submit(catalog, state()))
        assertEquals(revision, updates.revision)
    }

    @Test fun selectionChangesOnlyTheChangedIdsInsideSelectionMode() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B", "C")
        updates.submit(catalog, state(selected = setOf("A"), selecting = true))
        val change = updates.submit(catalog, state(selected = setOf("B"), selecting = true))!!
        assertFalse(change.catalogChanged)
        assertFalse(change.allVisibleRows)
        assertEquals(setOf("A", "B"), change.recordingIds)
        assertTrue(change.affects("A"))
        assertFalse(change.affects("C"))
    }

    @Test fun enteringAndLeavingSelectionModeUpdateEveryVisibleRow() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B")
        updates.submit(catalog, state())
        val entered = updates.submit(catalog, state(selected = setOf("A"), selecting = true))!!
        assertFalse(entered.catalogChanged)
        assertTrue(entered.affects("B"))
        val left = updates.submit(catalog, state())!!
        assertTrue(left.allVisibleRows)
        assertTrue(left.affects("B"))
    }

    @Test fun busyChangesOnlyOldAndNewBlockedRows() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B", "C")
        updates.submit(catalog, state(blocked = setOf("A", "C")))
        val change = updates.submit(catalog, state(blocked = setOf("B", "C")))!!
        assertEquals(setOf("A", "B"), change.recordingIds)
        assertFalse(change.catalogChanged)
        assertFalse(change.allVisibleRows)
    }

    @Test fun switchingExpansionInvalidatesBothOldAndNewRows() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B")
        updates.submit(catalog, state(owner = Owner("A")))
        val change = updates.submit(catalog, state(owner = Owner("B")))!!
        assertEquals(setOf("A", "B"), change.recordingIds)
        assertFalse(change.allVisibleRows)
    }

    @Test fun sameRecordingNewOwnerAndCollapseStillRefreshTheLease() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A")
        val previousOwner = Owner("A")
        updates.submit(catalog, state(owner = previousOwner))
        val nextOwner = Owner("A")
        val renewed = updates.submit(catalog, state(owner = nextOwner))!!
        assertEquals(setOf("A"), renewed.recordingIds)
        assertSame(nextOwner, updates.current!!.expandedOwner)
        assertNull(updates.submit(catalog, state(owner = nextOwner)))
        val collapsed = updates.submit(catalog, state())!!
        assertEquals(setOf("A"), collapsed.recordingIds)
        assertNull(updates.current!!.expandedOwner)
        assertNull(updates.current!!.expandedRecordingId)
    }

    @Test fun trimRequestMovesAndClearsOnTheCorrectRows() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B")
        updates.submit(catalog, state(trim = "A"))
        assertEquals(setOf("A", "B"), updates.submit(catalog, state(trim = "B"))!!.recordingIds)
        assertEquals(setOf("B"), updates.submit(catalog, state())!!.recordingIds)
    }

    @Test fun screenActiveRefreshesVisibleRowsIncludingOutgoingLeases() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B")
        updates.submit(catalog, state())
        val hidden = updates.submit(catalog, state(active = false))!!
        assertTrue(hidden.allVisibleRows)
        assertFalse(hidden.catalogChanged)
        assertFalse(updates.current!!.screenActive)
        assertTrue(updates.submit(catalog, state())!!.allVisibleRows)
    }

    @Test fun newCatalogWithSameIdsStillDiffsMetadataAndPreservesTransientState() {
        data class Recording(val id: String, val title: String, val revision: String)
        val updates = NativeLibraryListUpdates<Owner>()
        val owner = Owner("A")
        val first = listOf(Recording("A", "old", "r1"))
        val changed = listOf(Recording("A", "renamed", "r2"))
        updates.submit(first, state(owner = owner, selected = setOf("A"), selecting = true))
        val change = updates.submit(changed, state(owner = owner, selected = setOf("A"), selecting = true))!!
        assertTrue(change.catalogChanged)
        assertFalse(change.allVisibleRows)
        assertTrue(change.recordingIds.isEmpty())
        assertSame(owner, updates.current!!.expandedOwner)
        assertEquals(setOf("A"), updates.current!!.selectedIds)
    }

    @Test fun simultaneousCatalogAndTransientChangesRetainBothUpdateKinds() {
        val updates = NativeLibraryListUpdates<Owner>()
        updates.submit(listOf("A", "B"), state(owner = Owner("A")))
        val change = updates.submit(listOf("B", "C"),
            state(owner = Owner("B"), blocked = setOf("B"), selected = setOf("C"), selecting = true))!!
        assertTrue(change.catalogChanged)
        assertTrue(change.allVisibleRows)
        assertEquals(setOf("A", "B", "C"), change.recordingIds)
    }

    @Test fun callerSetMutationCannotRewriteThePreviousSnapshot() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B")
        val selected = mutableSetOf("A")
        val blocked = mutableSetOf("B")
        updates.submit(catalog, state(selected = selected, blocked = blocked))
        selected.clear()
        blocked.clear()
        assertEquals(setOf("A"), updates.current!!.selectedIds)
        assertEquals(setOf("B"), updates.current!!.blockedIds)
        val change = updates.submit(catalog, state(selected = selected, blocked = blocked))!!
        assertEquals(setOf("A", "B"), change.recordingIds)
    }

    @Test fun cachedHolderRevisionCannotMistakeAChangedOrClearedBranchForCurrent() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = listOf("A", "B")
        updates.submit(catalog, state(owner = Owner("A")))
        val cachedRevision = updates.revision
        updates.submit(catalog, state(blocked = setOf("A"), active = false))
        assertNotEquals(cachedRevision, updates.revision)
        assertNull(updates.current!!.expandedOwner)
        assertFalse(updates.current!!.screenActive)
        assertTrue("A" in updates.current!!.blockedIds)
        val beforeClear = updates.revision
        updates.clear()
        assertNull(updates.current)
        assertNotEquals(beforeClear, updates.revision)
        assertTrue(updates.submit(catalog, state())!!.catalogChanged)
    }

    @Test fun transientPublicationsNeverReadTheCatalogEvenAtLargeSize() {
        class CountingCatalog : AbstractList<String>() {
            var reads = 0
            override val size: Int get() { reads++; return 100_000 }
            override fun get(index: Int): String { reads++; return index.toString() }
        }
        val catalog = CountingCatalog()
        val updates = NativeLibraryListUpdates<Owner>()
        updates.submit(catalog, state())
        repeat(100) { index ->
            val change = updates.submit(catalog, state(
                selected = setOf(index.toString()), selecting = true,
                blocked = setOf((index + 1).toString()), owner = Owner(index.toString())))!!
            assertFalse(change.catalogChanged)
        }
        assertEquals(0, catalog.reads)
    }
}
