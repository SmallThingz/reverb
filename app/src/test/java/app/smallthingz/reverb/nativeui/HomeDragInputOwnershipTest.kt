package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativeHomeHorizontalDrag
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.oppositeBufferSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType

class HomeDragInputOwnershipTest {
    @Test fun savingKeyChangeReturnsActiveDragAndIgnoresItsLateUp() = scenarios { h ->
        h.startDrag()
        val pose = h.motion.flipFrame
        h.bind(h.source, saving = true)
        if (h.scale != 0f) assertEquals(pose, h.motion.flipFrame)
        h.up()
        h.finishReturn()
        h.assertNeutral()
        assertTrue(h.commits.isEmpty())
        h.bind(h.source, saving = false)
        h.motion.requestSelection(h.target, h.scale)
        h.tick(1_000)
        h.tick(1_260)
        assertEquals(listOf(h.target), h.commits)
    }

    @Test fun clearingSavingDoesNotRestartAnAlreadyOwnedReturn() {
        for (source in BufferSlot.entries) {
            val h = Harness(source, 1f)
            h.startDrag()
            h.bind(source, saving = true)
            h.tick(0)
            h.tick(80)
            val pose = h.motion.flipFrame
            h.bind(source, saving = false)
            assertEquals(pose, h.motion.flipFrame)
            h.tick(260)
            h.assertNeutral()
            assertTrue(h.commits.isEmpty())
        }
    }

    @Test fun detachRetiresDragAndReattachSchedulesItsReturn() = scenarios { h ->
        h.startDrag()
        h.detach()
        assertTrue("A detached return must remain available to the attach frame request", h.motion.running)
        h.up()
        // No frame clock is delivered while detached. The first attach frame starts the return.
        h.attach()
        h.tick(5_000)
        h.tick(5_260)
        h.assertNeutral()
        assertTrue(h.commits.isEmpty())
        h.startDrag()
        h.up()
        h.tick(6_000)
        h.tick(6_260)
        assertEquals(listOf(h.target), h.commits)
    }

    @Test fun repeatedRetirementDoesNotTakeOverProgrammaticSettling() = scenarios { h ->
        h.motion.requestSelection(h.target, h.scale)
        h.bind(h.source, saving = true)
        h.detach()
        h.attach()
        h.tick(0)
        h.tick(80)
        h.bind(h.source, saving = false)
        h.tick(260)
        h.tick(520)
        assertEquals(listOf(h.target), h.commits)
        assertEquals(h.target, h.motion.displayedBuffer)
        assertFalse(h.motion.running)
    }

    @Test fun completedDragKeepsItsAcceptedCommitAcrossSavingAndDetach() = scenarios { h ->
        h.startDrag()
        h.up()
        h.bind(h.source, saving = true)
        h.detach()
        h.attach()
        h.tick(0)
        h.tick(260)
        h.tick(520)
        assertEquals(listOf(h.target), h.commits)
        assertEquals(h.target, h.motion.displayedBuffer)
        assertFalse(h.motion.running)
    }

    @Test fun externalSelectionTakeoverKeepsItsOwnTransitionAcrossKeyReset() = scenarios { h ->
        h.startDrag()
        h.bind(h.target, saving = true)
        h.up()
        h.tick(0)
        h.tick(260)
        assertEquals(h.target, h.motion.displayedBuffer)
        assertTrue(h.commits.isEmpty())
        assertFalse(h.motion.running)
    }

    @Test fun restartBeforeSlopDoesNotCreatePhantomMotion() = scenarios { h ->
        h.down()
        h.move(4f * h.direction)
        h.bind(h.source, saving = true)
        h.up()
        h.assertNeutral()
        assertTrue(h.commits.isEmpty())
        h.bind(h.source, saving = false)
        h.startDrag()
        h.up()
        h.tick(0)
        h.tick(260)
        assertEquals(listOf(h.target), h.commits)
    }

    @Test fun closeStyleRetirementClearsGestureWithoutNavigationCommit() = scenarios { h ->
        h.startDrag()
        restartHomeDragInput(h.motion, h.detector, h.scale)
        h.up()
        // Close cancels the real frame owner; sampling here verifies the retained motion has
        // only a return, never an accepted navigation waiting to escape a stale detector UP.
        h.finishReturn()
        h.assertNeutral()
        assertTrue(h.commits.isEmpty())
    }

    private fun scenarios(block: (Harness) -> Unit) {
        for (source in BufferSlot.entries) for (scale in listOf(0f, 1f)) block(Harness(source, scale))
    }

    /** Models the owner's key/attachment scheduling around the actual detector and motion. */
    private class Harness(val source: BufferSlot, val scale: Float) {
        val target = oppositeBufferSlot(source)
        val direction = if (source == BufferSlot.ONE_SHOT) -1f else 1f
        val motion = ReverbHomeBufferMotion(source)
        val commits = mutableListOf<BufferSlot>()
        private var saving = false
        private var pointerKey = source to false
        private var attached = true
        private var x = 0f
        val detector = NativeHomeHorizontalDrag({ 10f }, { motion.dragStarted() },
            { motion.dragged(it, 1_000f) }, { motion.dragEnded(scale) }, { motion.dragCancelled(scale) })
        fun bind(selection: BufferSlot, saving: Boolean) {
            this.saving = saving
            motion.bind(selection, saving, scale)
            reconcileKey()
        }
        private fun reconcileKey() {
            val next = motion.displayedBuffer to saving
            if (next != pointerKey) {
                pointerKey = next
                restartHomeDragInput(motion, detector, scale)
            }
        }
        fun detach() {
            attached = false
            restartHomeDragInput(motion, detector, scale)
        }
        fun attach() { attached = true }
        fun tick(ms: Long) {
            check(attached)
            val commit = motion.frame(ms * 1_000_000L, scale)
            reconcileKey()
            if (commit != null) commits += commit
        }
        fun finishReturn() { tick(0); tick(260); tick(520) }
        fun down() { x = 0f; event(0f, 0f, down = true) }
        fun move(next: Float) { event(next, x); x = next }
        fun up() = event(x, x, up = true)
        fun startDrag() { down(); move(410f * direction) }
        private fun event(next: Float, previous: Float, down: Boolean = false, up: Boolean = false) {
            val change = NativeDragPointerChange(1L, 0L, Offset(next, 0f), Offset(previous, 0f),
                !up, !down, PointerType.Touch, Offset(next, 0f))
            val event = NativeDragPointerEvent(listOf(change), Offset.Zero)
            for (pass in NativePointerPass.entries) detector.dispatch(event, pass)
        }
        fun assertNeutral() {
            assertEquals(source, motion.displayedBuffer)
            assertEquals(source, motion.renderedBuffer)
            assertFalse(motion.running)
            assertEquals(0f, motion.flipFrame.degrees, 0f)
            assertEquals(1f, motion.flipFrame.depthScale, 0f)
        }
    }
}
