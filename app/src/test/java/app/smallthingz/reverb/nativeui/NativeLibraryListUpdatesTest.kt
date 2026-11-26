package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
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

    @Test fun waveformProofBindsOnlyExpandedOwnerWithoutCatalogWork() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = Any()
        val owner = Owner("A")
        val waiting = state(owner = owner).copy(waveformReadAllowed = false)
        updates.submit(catalog, waiting)
        val change = checkNotNull(updates.submit(catalog, waiting.copy(waveformReadAllowed = true)))
        assertFalse(change.catalogChanged)
        assertFalse(change.allVisibleRows)
        assertEquals(setOf("A"), change.recordingIds)
        assertSame(owner, updates.current!!.expandedOwner)
        assertNull(updates.submit(catalog, waiting.copy(waveformReadAllowed = true)))
    }

    @Test fun inlineInfoRetainsItsOwnerlessExpansionWithoutCatalogWork() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = Any()
        updates.submit(catalog, state())
        val info = state().copy(infoRecordingId = "A")
        val opened = updates.submit(catalog, info)!!
        assertFalse(opened.catalogChanged)
        assertTrue(opened.allVisibleRows) // Other rows revoke pointer/accessibility admission.
        assertNull(updates.current!!.expandedOwner) // Info must never instantiate playback.
        assertNull(updates.submit(catalog, info.copy()))
        val closed = updates.submit(catalog, state())!!
        assertTrue(closed.allVisibleRows)
        assertEquals(setOf("A"), closed.recordingIds)
    }

    @Test fun switchingInfoToRenameRestoresOldRowAndReplacesOnlyTheNewHeader() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = Any()
        updates.submit(catalog, state().copy(infoRecordingId = "A"))
        val renamed = state().copy(renameRecordingId = "B")
        val change = updates.submit(catalog, renamed)!!
        assertFalse(change.catalogChanged)
        assertEquals(setOf("A", "B"), change.recordingIds)
        assertNull(updates.current!!.infoRecordingId)
        assertNull(updates.submit(catalog, renamed))
        val busy = updates.submit(catalog, renamed.copy(blockedIds = setOf("B")))!!
        assertEquals(setOf("B"), busy.recordingIds)
        assertFalse(busy.allVisibleRows)
        assertEquals("B", updates.current!!.renameRecordingId)
    }

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

    @Test fun playbackStartSwitchAndStopInvalidateOnlyOldAndNewRecordingIds() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = Any()
        val a = playingRecording("A")
        val b = playingRecording("B")
        updates.submit(catalog, state())
        val cachedBinding = updates.bindingRevision
        for ((playing, affected) in listOf(a to setOf("A"), b to setOf("A", "B"), null to setOf("B"))) {
            val before = updates.revision
            val change = checkNotNull(updates.submit(catalog, state().copy(playingRecording = playing)))
            assertFalse(change.catalogChanged)
            assertFalse(change.allVisibleRows)
            assertEquals(affected, change.recordingIds)
            assertFalse(change.affects("unrelated"))
            assertEquals(before + 1, updates.revision)
            assertEquals("Playback must not rebind unrelated cached holders", cachedBinding, updates.bindingRevision)
            assertEquals(playing, updates.current!!.playingRecording)
        }
    }

    @Test fun sameIdPlaybackReplacementRefreshesThatRowAndCachedRevision() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = Any()
        val a = playingRecording("A")
        updates.submit(catalog, state().copy(playingRecording = a))
        val cachedBinding = updates.bindingRevision
        for (replacement in listOf(a.copy(fileIdentity = "replacement"),
            a.copy(sizeBytes = a.sizeBytes + 1), a.copy(storageType = RecordingStorageType.DOCUMENT))) {
            val cachedRevision = updates.revision
            val change = checkNotNull(updates.submit(catalog, state().copy(playingRecording = replacement)))
            assertEquals(setOf("A"), change.recordingIds)
            assertFalse(change.catalogChanged)
            assertFalse(change.allVisibleRows)
            assertEquals(cachedRevision + 1, updates.revision)
            assertEquals(cachedBinding, updates.bindingRevision)
            assertEquals(replacement, updates.current!!.playingRecording)
            assertNull(updates.submit(catalog, state().copy(playingRecording = replacement.copy())))
            assertEquals(cachedRevision + 1, updates.revision)
        }
        val beforeClear = updates.revision
        updates.clear()
        assertNull(updates.current)
        assertEquals(beforeClear + 1, updates.revision)
        assertEquals(cachedBinding + 1, updates.bindingRevision)
        assertTrue(updates.submit(catalog, state())!!.catalogChanged)
        assertNull(updates.current!!.playingRecording)
    }

    @Test fun playbackOnlyUpdatePreservesEditorSelectionTrimAndOwnerIdentity() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = Any()
        val owner = Owner("editor")
        for (editor in listOf(
            state(owner = owner, trim = "editor"),
            state(selected = setOf("editor"), selecting = true),
            state().copy(infoRecordingId = "editor"),
            state().copy(renameRecordingId = "editor"),
        )) {
            updates.submit(catalog, editor)
            val current = checkNotNull(updates.current)
            val binding = updates.bindingRevision
            val change = checkNotNull(updates.submit(catalog, editor.copy(playingRecording = playingRecording("A"))))
            assertEquals(setOf("A"), change.recordingIds)
            assertFalse(change.catalogChanged)
            assertFalse(change.allVisibleRows)
            assertSame(current.expandedOwner, updates.current!!.expandedOwner)
            assertSame(current.selectedIds, updates.current!!.selectedIds)
            assertEquals(current, updates.current!!.copy(playingRecording = null))
            assertEquals(binding, updates.bindingRevision)
        }
    }

    @Test fun playbackPayloadNeverTraversesCatalogAndNoOpKeepsCachedRevision() {
        val catalog = object : AbstractList<String>() {
            override val size: Int get() = error("Playback payload must not read catalog size")
            override fun get(index: Int): String = error("Playback payload must not read catalog rows")
            override fun equals(other: Any?): Boolean = error("Catalog comparison must use reference identity")
        }
        val updates = NativeLibraryListUpdates<Owner>()
        val a = playingRecording("A")
        updates.submit(catalog, state().copy(playingRecording = a))
        val cached = updates.revision
        val cachedBinding = updates.bindingRevision
        assertNull(updates.submit(catalog, state().copy(playingRecording = a.copy())))
        assertEquals(cached, updates.revision)
        assertEquals(cachedBinding, updates.bindingRevision)
        val changed = checkNotNull(updates.submit(catalog, state().copy(playingRecording = playingRecording("B"))))
        assertFalse(changed.catalogChanged)
        assertEquals(setOf("A", "B"), changed.recordingIds)
        assertEquals(cached + 1, updates.revision)
        assertEquals(cachedBinding, updates.bindingRevision)
        assertEquals("B", updates.current!!.playingRecording?.id)
    }

    @Test fun bindingRevisionStillAdvancesForOwnerCatalogAndOtherPresentationChanges() {
        val updates = NativeLibraryListUpdates<Owner>()
        val catalog = Any()
        val a = playingRecording("A")
        val initial = state().copy(playingRecording = a)
        updates.submit(catalog, initial)
        var binding = updates.bindingRevision
        for (next in listOf(
            state(blocked = setOf("B")),
            state(selected = setOf("B"), selecting = true),
            state(owner = Owner("B")),
            state(owner = Owner("B")), // A renewed owner for the same ID must rebind.
            state(trim = "B"),
            state(active = false),
            state().copy(infoRecordingId = "B"),
            state().copy(renameRecordingId = "B"),
        )) {
            val current = next.copy(playingRecording = a)
            assertNotNull(updates.submit(catalog, current))
            assertEquals(++binding, updates.bindingRevision)
            assertNull(updates.submit(catalog, current.copy()))
            assertEquals(binding, updates.bindingRevision)
        }
        assertTrue(updates.submit(Any(), checkNotNull(updates.current))!!.catalogChanged)
        assertEquals(++binding, updates.bindingRevision)
        updates.clear()
        assertEquals(++binding, updates.bindingRevision)
    }

    private fun playingRecording(id: String) = RecordingEntity(id, "$id.wav", "audio/wav", 1L,
        2_000L, 192_044L, "PCM", RecordingStorageType.FILE, "/fixture", "identity-$id",
        createdAtMillis = 1L, lastSeenAtMillis = 1L)
}
