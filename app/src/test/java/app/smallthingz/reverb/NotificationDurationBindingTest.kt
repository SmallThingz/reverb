package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDurationBindingTest {
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
        val target = QuickSettingsTileEditorTarget(null)
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
        val target = QuickSettingsTileEditorTarget(null)
        val wheelOwner = target.wheelOwner(7L)

        assertFalse(target.selectBuffer(target.buffer))
        assertEquals(wheelOwner, target.wheelOwner(7L))
    }

    @Test fun rapidTileBufferRoundTripInvalidatesTheOriginalWheelInteraction() {
        val target = QuickSettingsTileEditorTarget(null)
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
        val target = QuickSettingsTileEditorTarget(null)
        val editRevision = target.editRevision
        target.busy = true

        assertFalse(target.selectBuffer(ReverbService.BufferSlot.ONE_SHOT))
        assertEquals(ReverbService.BufferSlot.LOOPING, target.buffer)
        assertEquals(editRevision, target.editRevision)
    }
}
