package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDurationBindingTest {
    // These fixtures have a stable parent owner; integration cases below advance it on acceptance.
    private fun NotificationDurationBinding.commitInput(seconds: Int, owner: Long?): Int? =
        commitInput(seconds, owner) { NotificationDurationCommit(it, requireNotNull(owner)) }

    private fun NotificationDurationBinding.finishInteraction(owner: Long?): Int? =
        finishInteraction(owner) { NotificationDurationCommit(it, requireNotNull(owner)) }

    @Test fun acceptedKeyboardInputsAdvanceTheirOwnOwnerBeforeRecomposition() {
        val binding = NotificationDurationBinding()
        var parentSeconds = 30
        var parentOwner = 7L
        val apply: (Int) -> NotificationDurationCommit? = { seconds ->
            parentSeconds = seconds
            NotificationDurationCommit(seconds, ++parentOwner)
        }
        binding.update(parentSeconds, 300, enabled = true, owner = parentOwner)

        repeat(2) {
            val next = notificationDurationStepSeconds(binding.seconds, 300, true)
            assertEquals(31 + it, binding.commitInput(next, parentOwner, apply))
        }
        assertEquals(32, parentSeconds)
        assertEquals(32, binding.seconds)
        assertNull(binding.commitInput(90, 7L, apply))

        // The resulting Compose update must not invalidate a fresh gesture begun after the edit.
        binding.beginInteraction(parentOwner)
        binding.select(45)
        binding.update(parentSeconds, 300, enabled = true, owner = parentOwner)
        assertEquals(45, binding.finishInteraction(parentOwner, apply))
        assertEquals(46, binding.commitInput(46, parentOwner, apply))
    }

    @Test fun unrelatedEditAfterOwnCommitStillRejectsFreshInputUntilRendered() {
        val binding = NotificationDurationBinding()
        var parentSeconds = 30
        var parentOwner = 7L
        var applied = 0
        val apply: (Int) -> NotificationDurationCommit? = { seconds ->
            applied++
            parentSeconds = seconds
            NotificationDurationCommit(seconds, ++parentOwner)
        }
        binding.update(parentSeconds, 300, enabled = true, owner = parentOwner)
        assertEquals(31, binding.commitInput(31, parentOwner, apply))
        parentSeconds = 60
        parentOwner++ // An unrelated Settings edit or Undo owns this new baseline.
        assertNull(binding.commitInput(32, parentOwner, apply))
        assertEquals(1, applied)
        assertEquals(60, parentSeconds)
        binding.update(parentSeconds, 300, enabled = true, owner = parentOwner)
        assertEquals(61, binding.commitInput(61, parentOwner, apply))
    }

    @Test fun canonicalTileDurationAndOwnRevisionStaySynchronizedWithoutRecomposition() {
        val binding = NotificationDurationBinding()
        val target = QuickSettingsTileEditorTarget()
        target.seconds = 999
        fun owner() = target.wheelOwner(7L)
        val apply: (Int) -> NotificationDurationCommit? = { seconds ->
            target.seconds = quickTileRepresentableSeconds(seconds)
            target.editRevision++
            NotificationDurationCommit(target.seconds, owner())
        }
        binding.update(target.seconds, 86_400, enabled = true, owner = owner())

        // Touch/SET_PROGRESS keeps its existing floor normalization, including the unit gap.
        binding.beginInteraction(owner())
        binding.select(1_001)
        assertEquals(999, binding.finishInteraction(owner(), apply))
        assertEquals(target.seconds, binding.seconds)
        for (expected in listOf(1_020, 1_080)) {
            val next = quickTileStepSeconds(binding.seconds, 86_400, true)
            assertEquals(expected, binding.commitInput(next, owner(), apply))
            assertEquals(expected, target.seconds)
        }
        for (expected in listOf(1_020, 999)) {
            val previous = quickTileStepSeconds(binding.seconds, 86_400, false)
            assertEquals(expected, binding.commitInput(previous, owner(), apply))
            assertEquals(expected, target.seconds)
        }
    }

    @Test fun rejectedParentCommitDoesNotAdvanceTheBinding() {
        val binding = NotificationDurationBinding()
        binding.update(30, 300, enabled = true, owner = 7L)
        assertNull(binding.commitInput(31, 7L) { null })
        assertEquals(30, binding.seconds)
        assertEquals(31, binding.commitInput(31, 7L))
    }

    @Test fun reentrantExternalUpdateCannotBeReplacedByAnOwnCommitReceipt() {
        val binding = NotificationDurationBinding()
        binding.update(30, 300, enabled = true, owner = 7L)
        assertNull(binding.commitInput(31, 7L) {
            binding.update(70, 300, enabled = true, owner = 99L)
            NotificationDurationCommit(it, 8L)
        })
        assertEquals(70, binding.seconds)
        assertEquals(71, binding.commitInput(71, 99L))
    }

    @Test fun notificationKeyboardAndAccessibilityStepsRemainOneSecond() {
        assertEquals(1_000, notificationDurationStepSeconds(999, 86_400, true))
        assertEquals(59_941, notificationDurationStepSeconds(59_940, 86_400, true))
        assertEquals(999, notificationDurationStepSeconds(1_000, 86_400, false))
        assertEquals(1, notificationDurationStepSeconds(1, 86_400, false))
        assertEquals(31, notificationDurationStepSeconds(31, 31, true))
    }

    @Test fun focusReturnAcceptsInputWithoutAnotherRender() {
        val binding = NotificationDurationBinding()
        binding.update(30, 300, enabled = true, owner = 7L)

        // A calibration cancellation can recompose the wheel while the notification shade owns focus.
        // That render retains its draft identity, while all live input admission returns null.
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(null)
        binding.select(45)
        assertNull(binding.finishInteraction(null))
        assertNull(binding.commitInput(31, null))
        assertEquals(30, binding.seconds)

        // Focus alone need not recompose. Keyboard/accessibility and then touch work immediately.
        assertEquals(31, binding.commitInput(31, 7L))
        binding.beginInteraction(7L)
        binding.select(45)
        assertEquals(45, binding.finishInteraction(7L))
    }

    @Test fun focusLossRejectsAPendingTouchAndRetainsTheDraft() {
        val binding = NotificationDurationBinding()
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(7L)
        binding.select(90)
        assertNull(binding.finishInteraction(null))
        assertEquals(30, binding.seconds)
        assertNull(binding.finishInteraction(7L))
        assertEquals(31, binding.commitInput(31, 7L))
    }

    @Test fun newerDraftRejectsInputBeforeAndAfterItsRender() {
        val binding = NotificationDurationBinding()
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(7L)
        binding.select(90)
        assertNull(binding.commitInput(31, 8L))

        binding.update(40, 300, enabled = true, owner = 8L)
        assertNull(binding.finishInteraction(8L))
        assertNull(binding.commitInput(31, 7L))
        assertEquals(40, binding.seconds)
        assertEquals(41, binding.commitInput(41, 8L))
    }

    @Test fun changedLimitOrDisabledOwnerCannotCommitAnOldSettle() {
        val binding = NotificationDurationBinding()
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(7L)
        binding.select(90)
        binding.update(30, 60, enabled = true, owner = 7L)
        assertNull(binding.finishInteraction(7L))
        assertEquals(30, binding.seconds)

        binding.beginInteraction(7L)
        binding.select(45)
        binding.update(30, 60, enabled = false, owner = 7L)
        assertNull(binding.finishInteraction(7L))
        assertNull(binding.commitInput(31, 7L))
        binding.release()
        assertNull(binding.commitInput(31, 7L))
    }

    @Test fun tileWheelTracksBothParentAndLocalDraftRevisions() {
        val target = QuickSettingsTileEditorTarget()
        val binding = NotificationDurationBinding()
        val originalOwner = target.wheelOwner(7L)
        binding.update(30, 300, enabled = true, owner = originalOwner)
        binding.beginInteraction(originalOwner)
        binding.select(45)

        // A temporary absence of live admission never replaces the composed tile identity.
        assertEquals(originalOwner, target.wheelOwner(7L))
        assertNull(binding.finishInteraction(null))
        assertEquals(31, binding.commitInput(31, target.wheelOwner(7L)))

        target.editRevision++
        val editedOwner = target.wheelOwner(7L)
        assertNotEquals(originalOwner, editedOwner)
        assertNull(binding.commitInput(32, editedOwner))
        binding.update(60, 300, enabled = true, owner = editedOwner)
        assertEquals(61, binding.commitInput(61, editedOwner))
        val parentOwner = target.wheelOwner(8L)
        assertNotEquals(editedOwner, parentOwner)
        assertNull(binding.commitInput(62, parentOwner))
    }

    @Test fun selectedTileBufferTapRetainsWheelOwnership() {
        val target = QuickSettingsTileEditorTarget()
        val wheelOwner = target.wheelOwner(7L)

        assertFalse(target.selectBuffer(target.buffer))
        assertEquals(wheelOwner, target.wheelOwner(7L))
    }

    @Test fun rapidTileBufferRoundTripInvalidatesTheOriginalWheelInteraction() {
        val target = QuickSettingsTileEditorTarget()
        val originalBuffer = target.buffer
        val binding = NotificationDurationBinding()
        val originalOwner = target.wheelOwner(7L)
        binding.update(30, 300, enabled = true, owner = originalOwner)
        binding.beginInteraction(originalOwner)
        binding.select(45)

        assertTrue(target.selectBuffer(ReverbService.BufferSlot.ONE_SHOT))
        assertTrue(target.selectBuffer(originalBuffer))

        assertEquals(originalBuffer, target.buffer)
        assertNotEquals(originalOwner, target.wheelOwner(7L))
        assertNull(binding.finishInteraction(target.wheelOwner(7L)))
        assertEquals(30, binding.seconds)
    }

    @Test fun tileSaveRejectsBufferChangeAndRetainsDraft() {
        val target = QuickSettingsTileEditorTarget()
        val editRevision = target.editRevision
        target.busy = true

        assertFalse(target.selectBuffer(ReverbService.BufferSlot.ONE_SHOT))
        assertEquals(ReverbService.BufferSlot.LOOPING, target.buffer)
        assertEquals(editRevision, target.editRevision)
    }

    @Test fun admittedNativeEditLocksSaveBeforeRenderThroughCommitAndDisplayReset() {
        val interaction = NotificationDurationInteractionState()
        val binding = NotificationDurationBinding(interaction)
        var parentSeconds = 30
        var parentOwner = 7L
        binding.update(parentSeconds, 300, enabled = true, owner = parentOwner)
        binding.beginInteraction(parentOwner)
        // Both Save callbacks read this same holder, without waiting for Compose to redraw.
        assertTrue(interaction.pending)
        binding.select(45)
        assertEquals(30, parentSeconds)
        assertTrue(interaction.pending)
        assertEquals(45, binding.finishInteraction(parentOwner, { seconds ->
            assertTrue(interaction.pending)
            parentSeconds = seconds
            NotificationDurationCommit(seconds, ++parentOwner)
        }, resetDisplay = { canonicalSeconds ->
            assertTrue(interaction.pending)
            assertEquals(45, canonicalSeconds)
            assertEquals(parentSeconds, canonicalSeconds)
        }))
        assertFalse(interaction.pending)
        assertEquals(45, parentSeconds)
    }

    @Test fun unchangedNativeSelectionStillReleasesSaveAfterDisplayReset() {
        val interaction = NotificationDurationInteractionState()
        val binding = NotificationDurationBinding(interaction)
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(7L)
        // Native publishDurationIfChanged emits no select call for an unchanged gesture.
        assertTrue(interaction.pending)
        assertNull(binding.finishInteraction(7L, {
            throw AssertionError("Unchanged interaction must not edit the draft")
        }, resetDisplay = { seconds ->
            assertTrue(interaction.pending)
            assertEquals(30, seconds)
        }))
        assertFalse(interaction.pending)
    }

    @Test fun invalidatedOrDisabledNativeSettleKeepsSaveLockedUntilPixelsReset() {
        for (disable in listOf(false, true)) {
            val interaction = NotificationDurationInteractionState()
            val binding = NotificationDurationBinding(interaction)
            binding.update(30, 300, enabled = true, owner = 7L)
            binding.beginInteraction(7L)
            binding.select(45)
            val currentOwner = if (disable) 7L else 8L
            val currentSeconds = if (disable) 30 else 60
            binding.update(currentSeconds, 300, enabled = !disable, owner = currentOwner)
            assertTrue(interaction.pending)
            assertNull(binding.finishInteraction(currentOwner, {
                throw AssertionError("Superseded or disabled native edit must not commit")
            }, resetDisplay = { seconds ->
                assertTrue(interaction.pending)
                assertEquals(currentSeconds, seconds)
            }))
            assertFalse(interaction.pending)
        }
    }

    @Test fun pausedNativeOwnerRejectsDelayedSelectionWithoutEarlySaveUnlock() {
        val interaction = NotificationDurationInteractionState()
        val binding = NotificationDurationBinding(interaction)
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(7L)
        binding.invalidateInteraction() // Production ON_PAUSE/ON_STOP callback.
        assertTrue(interaction.pending)
        assertNull(binding.commitInput(31, 7L))
        binding.select(45)
        assertNull(binding.finishInteraction(7L))
        assertEquals(30, binding.seconds)
        assertFalse(interaction.pending)
    }

    @Test fun retiredBindingAndItsLateTerminalCannotReleaseNewWheelOwner() {
        val interaction = NotificationDurationInteractionState()
        val oldBinding = NotificationDurationBinding(interaction)
        val newBinding = NotificationDurationBinding(interaction)
        oldBinding.update(30, 300, enabled = true, owner = 7L)
        oldBinding.beginInteraction(7L)
        oldBinding.select(45)
        newBinding.update(60, 300, enabled = true, owner = 8L)
        newBinding.beginInteraction(8L)
        newBinding.select(90)
        oldBinding.release()
        assertTrue(interaction.pending)
        assertNull(oldBinding.finishInteraction(7L))
        oldBinding.release()
        assertTrue(interaction.pending)
        assertEquals(90, newBinding.finishInteraction(8L))
        assertFalse(interaction.pending)
    }

    @Test fun terminalCommitCannotReleaseAnInteractionStartedDuringItsCallback() {
        val interaction = NotificationDurationInteractionState()
        val completing = NotificationDurationBinding(interaction)
        val newer = NotificationDurationBinding(interaction)
        completing.update(30, 300, enabled = true, owner = 7L)
        newer.update(60, 300, enabled = true, owner = 9L)
        completing.beginInteraction(7L)
        completing.select(45)
        assertEquals(45, completing.finishInteraction(7L, { seconds ->
            newer.beginInteraction(9L)
            newer.select(90)
            NotificationDurationCommit(seconds, 8L)
        }, resetDisplay = { assertTrue(interaction.pending) }))
        assertTrue(interaction.pending)
        completing.release()
        assertTrue(interaction.pending)
        assertEquals(90, newer.finishInteraction(9L))
        assertFalse(interaction.pending)
    }

    @Test fun failedTerminalCommitResetsPixelsAndReleasesOnlyItsSaveToken() {
        val interaction = NotificationDurationInteractionState()
        val binding = NotificationDurationBinding(interaction)
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(7L)
        binding.select(45)
        val expected = IllegalStateException("Draft callback failed")
        val resetFailure = IllegalArgumentException("Display reset failed")
        assertSame(expected, assertThrows(IllegalStateException::class.java) {
            binding.finishInteraction(7L, { throw expected }, resetDisplay = { seconds ->
                assertTrue(interaction.pending)
                assertEquals(30, seconds)
                throw resetFailure
            })
        })
        assertEquals(listOf(resetFailure), expected.suppressed.toList())
        assertFalse(interaction.pending)
        assertNull(binding.finishInteraction(7L))
    }

    @Test fun rejectedNativeBeginRetainsSaveLockButImmediateDiscreteEditsDoNot() {
        val interaction = NotificationDurationInteractionState()
        val binding = NotificationDurationBinding(interaction)
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(null)
        // The native View still moves even though this draft owner cannot commit its result.
        assertTrue(interaction.pending)
        assertNull(binding.commitInput(31, 7L))
        binding.select(45)
        assertNull(binding.finishInteraction(7L))
        assertFalse(interaction.pending)
        // Keyboard and accessibility use this immediate production commit adapter.
        assertEquals(31, binding.commitInput(31, 7L))
        assertFalse(interaction.pending)
        assertEquals(32, binding.commitInput(32, 7L))
        assertFalse(interaction.pending)
        binding.beginInteraction(7L)
        assertTrue(interaction.pending)
        binding.release() // View release/disposal removes its pixels and its only token.
        assertFalse(interaction.pending)
    }

    @Test fun replacementNativeBeginWithUnrenderedOwnerKeepsSaveLocked() {
        val interaction = NotificationDurationInteractionState()
        val binding = NotificationDurationBinding(interaction)
        binding.update(30, 300, enabled = true, owner = 7L)
        binding.beginInteraction(7L)
        binding.select(45)
        // Another Settings edit advances live ownership before AndroidView has redrawn.
        // A second DOWN stops the old snap and starts a new visual gesture immediately.
        binding.beginInteraction(8L)
        assertTrue(interaction.pending)
        binding.select(90)
        assertNull(binding.finishInteraction(8L, {
            throw AssertionError("Unrendered owner must not accept the native selection")
        }, resetDisplay = { seconds ->
            assertTrue(interaction.pending)
            assertEquals(30, seconds)
        }))
        assertFalse(interaction.pending)
        assertEquals(30, binding.seconds)
    }
}
