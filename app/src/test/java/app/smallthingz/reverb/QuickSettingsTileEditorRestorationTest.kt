package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsTileEditorRestorationTest {
    private fun option() = QuickSaveTileOption(
        2, "a".repeat(32), ReverbService.BufferSlot.LOOPING, 2, QuickSaveUnit.MINUTES,
    )

    private fun draft(existing: QuickSaveTileOption? = null) = QuickSettingsTilesEditorState().also { state ->
        state.target = QuickSettingsTileEditorTarget(existing).also { target ->
            target.selectBuffer(ReverbService.BufferSlot.ONE_SHOT)
            target.seconds = 180
            target.editRevision++
        }
    }

    @Test fun newDraftWaitsForHydrationAndSurvivesAnotherPendingRecreation() {
        val original = draft()
        val saved = original.save(panelActive = true)
        assertTrue(saved.all { it is Int || it is String })
        val waiting = QuickSettingsTilesEditorState.restore(saved)
        assertNull(waiting.target)
        assertTrue(waiting.editorOpen)
        assertFalse(waiting.restorePendingDraft(parentReady = false))
        assertEquals(saved, waiting.save(panelActive = true))

        val replacement = QuickSettingsTilesEditorState.restore(waiting.save(panelActive = true))
        assertTrue(replacement.restorePendingDraft(parentReady = true))
        val target = replacement.target!!
        assertNotSame(original.target, target)
        assertNull(target.existing)
        assertEquals(ReverbService.BufferSlot.ONE_SHOT, target.buffer)
        assertEquals(180, target.seconds)
        assertEquals(0L, target.editRevision)
        assertFalse(target.busy)
        assertFalse(replacement.mutationInProgress)
        assertNull(replacement.deleting)
        assertNull(replacement.pinning)
        assertFalse(replacement.restorePendingDraft(parentReady = true))
    }

    @Test fun existingDraftKeepsOriginalIdentitySeparateFromDirtyBufferAndDuration() {
        val expected = option()
        val replacement = QuickSettingsTilesEditorState.restore(draft(expected).save(panelActive = true))
        assertTrue(replacement.restorePendingDraft(parentReady = true))
        val target = replacement.target!!
        assertEquals(expected, target.existing)
        assertEquals(ReverbService.BufferSlot.LOOPING, target.existing!!.buffer)
        assertEquals(120, requireNotNull(target.existing).seconds)
        assertEquals(ReverbService.BufferSlot.ONE_SHOT, target.buffer)
        assertEquals(180, target.seconds)

        // Later buffer switches remain local edits and never retarget the pinned definition.
        assertTrue(target.selectBuffer(ReverbService.BufferSlot.LOOPING))
        assertTrue(target.selectBuffer(ReverbService.BufferSlot.ONE_SHOT))
        assertEquals(expected, target.existing)
        assertEquals(180, target.seconds)
    }

    @Test fun restoredExistingDefinitionCannotSaveAgainstDeletionChangeOrReusedSlot() {
        val expected = option()
        val replacement = QuickSettingsTilesEditorState.restore(draft(expected).save(panelActive = true))
        replacement.restorePendingDraft(parentReady = true)
        val pinned = replacement.target!!.existing
        assertTrue(quickSaveTileDefinitionIsCurrent(pinned, listOf(expected)))
        assertFalse(quickSaveTileDefinitionIsCurrent(pinned, emptyList()))
        assertFalse(quickSaveTileDefinitionIsCurrent(pinned, listOf(expected.copy(amount = 3))))
        assertFalse(quickSaveTileDefinitionIsCurrent(pinned, listOf(expected.copy(revision = "b".repeat(32)))))
        assertTrue(quickSaveTileDefinitionIsCurrent(null, listOf(expected)))
    }

    @Test fun delayedRestorationCannotReplaceANewerEditor() {
        val waiting = QuickSettingsTilesEditorState.restore(draft(option()).save(panelActive = true))
        assertFalse(waiting.restorePendingDraft(parentReady = false))
        val newer = QuickSettingsTileEditorTarget(null).also { it.seconds = 45 }
        waiting.target = newer
        assertFalse(waiting.restorePendingDraft(parentReady = true))
        assertSame(newer, waiting.target)
        assertFalse(waiting.restorePendingDraft(parentReady = true))
        assertEquals(newer.saveDraft(), waiting.save(panelActive = true))
    }

    @Test fun cancelBeforeOrAfterHydrationRetiresTheDraftPermanently() {
        for (hydrateFirst in listOf(false, true)) {
            val replacement = QuickSettingsTilesEditorState.restore(draft().save(panelActive = true))
            if (hydrateFirst) replacement.restorePendingDraft(parentReady = true)
            assertTrue(replacement.closeEditor()) // Back/Cancel or parent Undo closes the child first.
            assertFalse(replacement.editorOpen)
            assertFalse(replacement.restorePendingDraft(parentReady = true))
            assertEquals(emptyList<Any>(), replacement.save(panelActive = true))
            val next = QuickSettingsTilesEditorState.restore(replacement.save(panelActive = true))
            assertFalse(next.restorePendingDraft(parentReady = true))
            assertFalse(next.editorOpen)
        }
    }

    @Test fun cleanOrClosedPanelsAndTransientPlatformActionsHaveNoRestorableEditor() {
        assertEquals(emptyList<Any>(), QuickSettingsTilesEditorState().save(panelActive = true))
        assertEquals(emptyList<Any>(), draft().save(panelActive = false))
        val waiting = QuickSettingsTilesEditorState.restore(draft().save(panelActive = true))
        assertEquals(emptyList<Any>(), waiting.save(panelActive = false))
        waiting.closeEditor() // Production inactive-panel boundary.
        assertFalse(waiting.restorePendingDraft(parentReady = true))

        val pinning = QuickSettingsTilesEditorState().also { it.pinning = Any() }
        val deleting = QuickSettingsTilesEditorState().also {
            it.deleting = QuickSettingsTileDeleteTarget(option(), 7L)
        }
        for (state in listOf(pinning, deleting)) {
            val replacement = QuickSettingsTilesEditorState.restore(state.save(panelActive = true))
            assertFalse(replacement.restorePendingDraft(parentReady = true))
            assertNull(replacement.pinning)
            assertNull(replacement.deleting)
            assertFalse(replacement.mutationInProgress)
        }
    }

    @Test fun acceptedMutationIsNotRestoredOrCancelledByDraftCleanup() {
        val original = draft(option())
        val target = original.target!!
        target.busy = true
        assertFalse(original.closeEditor())
        assertSame(target, original.target)
        assertTrue(original.mutationInProgress)
        val replacement = QuickSettingsTilesEditorState.restore(original.save(panelActive = true))
        assertFalse(replacement.restorePendingDraft(parentReady = true))
        assertFalse(replacement.editorOpen)
        assertFalse(replacement.mutationInProgress)

        // After the accepted job reaches terminal, an inactive panel can retire its old draft.
        target.busy = false
        assertTrue(original.closeEditor())
        assertEquals(emptyList<Any>(), original.save(panelActive = true))

        val deletion = QuickSettingsTilesEditorState().also { it.deletingBusy = true }
        assertEquals(emptyList<Any>(), deletion.save(panelActive = true))
    }

    @Test fun restoredDurationKeepsItsValueWhileOnlyAFreshCapacityCanAuthorizeInput() {
        val oldCapacity = TileCapacityLoad()
        val oldRequest = oldCapacity.begin(7L)!!
        val previous = TileCapacities(3_600, 600.0, 600.0)
        assertTrue(oldCapacity.finish(oldRequest, 7L, previous))
        val replacement = QuickSettingsTilesEditorState.restore(draft().save(panelActive = true))
        replacement.restorePendingDraft(parentReady = true)
        val target = replacement.target!!

        // QuickTileInlineEditor creates a new capacity owner; saved UI values convey no readiness.
        val freshCapacity = TileCapacityLoad()
        assertNull(freshCapacity.ready(7L))
        assertFalse(freshCapacity.finish(oldRequest, 7L, previous))
        val request = freshCapacity.begin(9L)!!
        val changed = TileCapacities(90, loopingSeconds = 60.0, oneShotSeconds = null)
        assertFalse(freshCapacity.finish(request, 8L, previous))
        assertTrue(freshCapacity.finish(request, 9L, changed))
        assertEquals(180, target.seconds)
        assertTrue(target.seconds > freshCapacity.ready(9L)!!.maximumSeconds)
        assertNull(freshCapacity.ready(9L)!!.forBuffer(target.buffer).retainedSeconds)
        assertNull(freshCapacity.ready(10L)) // A newer parent edit also revokes fresh readiness.
    }

    @Test fun malformedSavedDraftFallsBackToNoEditorWithoutReinterpretingIdentity() {
        val valid = draft(option()).save(panelActive = true)
        val invalid = listOf(
            emptyList(),
            valid.dropLast(1),
            valid.toMutableList().apply { this[0] = 2 },
            valid.toMutableList().apply { this[1] = 999 },
            valid.toMutableList().apply { this[2] = 0 },
            valid.toMutableList().apply { this[2] = 1_001 },
            valid.toMutableList().apply { this[2] = 86_401 },
            valid.toMutableList().apply { this[3] = "1" },
            valid.toMutableList().apply { this[3] = "invalid definition" },
        )
        for (saved in invalid) {
            val replacement = QuickSettingsTilesEditorState.restore(saved)
            assertFalse(replacement.restorePendingDraft(parentReady = true))
            assertFalse(replacement.editorOpen)
            assertNull(replacement.target)
        }
    }
}
