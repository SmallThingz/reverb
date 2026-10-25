package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.oppositeBufferSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbHomeExternalSelectionTest {
    @Test fun externalReturnBeforeAndAfterMidpointReversesWithoutCommitting() {
        for (source in BufferSlot.entries) for (elapsed in listOf(20L, 200L)) {
            val target = oppositeBufferSlot(source)
            val motion = ReverbHomeBufferMotion(source)
            motion.bind(target, false, 1f)
            assertNull(motion.frame(0L, 1f))
            assertNull(motion.frame(elapsed * 1_000_000L, 1f))
            assertEquals(if (elapsed == 20L) source else target, motion.renderedBuffer)
            val pose = motion.flipFrame
            motion.bind(source, false, 1f)
            assertEquals("Retarget must preserve the current physical pose", pose, motion.flipFrame)
            motion.bind(source, false, 1f)
            assertNull(motion.frame((elapsed + 260L) * 1_000_000L, 1f))
            assertSettled(motion, source)
            motion.bind(source, false, 1f)
            assertSettled(motion, source)
            assertNull(motion.frame((elapsed + 520L) * 1_000_000L, 1f))
        }
    }

    @Test fun externalReturnBeforeAnyFrameRetiresAtNormalAndZeroScale() {
        for (source in BufferSlot.entries) for (scale in listOf(0f, 1f)) {
            val motion = ReverbHomeBufferMotion(source)
            motion.bind(oppositeBufferSlot(source), false, scale)
            // The owner may be paused and therefore deliver no intermediate frames.
            motion.bind(source, false, scale)
            assertNull(motion.frame(8_000_000_000L, scale))
            assertNull(motion.frame(8_260_000_000L, scale))
            assertSettled(motion, source)
        }
    }

    @Test fun externalReturnAfterSamplingCanDisableAnimationImmediately() {
        for (source in BufferSlot.entries) {
            val motion = ReverbHomeBufferMotion(source)
            motion.bind(oppositeBufferSlot(source), false, 1f)
            motion.frame(0L, 1f)
            motion.frame(200_000_000L, 1f)
            motion.bind(source, false, 0f)
            assertTrue("Terminal retirement still needs a frame", motion.running)
            assertNull(motion.frame(216_000_000L, 0f))
            assertSettled(motion, source)
        }
    }

    @Test fun rapidExternalAlternationSettlesAtTheLatestAuthority() {
        for (source in BufferSlot.entries) for (sample in listOf(false, true)) {
            val target = oppositeBufferSlot(source)
            val motion = ReverbHomeBufferMotion(source)
            motion.bind(target, false, 1f)
            if (sample) { motion.frame(0L, 1f); motion.frame(40_000_000L, 1f) }
            motion.bind(source, false, 1f)
            if (sample) motion.frame(60_000_000L, 1f)
            motion.bind(target, false, 1f)
            assertNull(motion.frame(80_000_000L, 1f))
            assertNull(motion.frame(400_000_000L, 1f))
            assertSettled(motion, target)
        }
    }

    @Test fun unchangedExternalSelectionCannotCancelManualNavigationOrDuplicateItsCommit() {
        for (source in BufferSlot.entries) for (scale in listOf(0f, 1f)) {
            val target = oppositeBufferSlot(source)
            val motion = ReverbHomeBufferMotion(source)
            motion.requestSelection(target, scale)
            motion.bind(source, false, scale)
            val first = motion.frame(0L, scale)
            motion.bind(source, false, scale)
            val last = motion.frame(260_000_000L, scale)
            assertEquals(listOf(target), listOfNotNull(first, last))
            assertSettled(motion, target)
            motion.bind(source, false, scale)
            assertSettled(motion, target)
            assertNull(motion.frame(520_000_000L, scale))
        }
    }

    @Test fun changedExternalSelectionTakesOwnershipFromPendingManualCommit() {
        for (source in BufferSlot.entries) {
            val target = oppositeBufferSlot(source)
            val motion = ReverbHomeBufferMotion(source)
            motion.requestSelection(target, 1f)
            motion.frame(0L, 1f)
            motion.frame(40_000_000L, 1f)
            motion.bind(target, false, 1f)
            motion.bind(source, false, 1f)
            assertNull(motion.frame(300_000_000L, 1f))
            assertSettled(motion, source)
            motion.requestSelection(target, 0f)
            assertEquals(target, motion.frame(320_000_000L, 0f))
            assertNull(motion.frame(340_000_000L, 0f))
        }
    }

    @Test fun changedAuthorityRetiresDragOwnershipForForwardAndReturnTargets() {
        for (source in BufferSlot.entries) for (returnToSource in listOf(false, true)) {
            val target = oppositeBufferSlot(source)
            val motion = ReverbHomeBufferMotion(source)
            if (returnToSource) {
                // Manual navigation has not yet been acknowledged by external selection.
                motion.requestSelection(target, 0f)
                assertEquals(target, motion.frame(0L, 0f))
            }
            val displayed = motion.displayedBuffer
            motion.dragStarted()
            val amount = if (displayed == BufferSlot.ONE_SHOT) -400f else 400f
            assertTrue(motion.dragged(amount, 1_000f))
            motion.bind(target, false, 1f)
            assertFalse("External authority owns the transition now", motion.dragged(amount, 1_000f))
            motion.dragEnded(1f)
            motion.dragCancelled(1f)
            assertNull(motion.frame(10_000_000L, 1f))
            assertNull(motion.frame(270_000_000L, 1f))
            assertSettled(motion, target)
        }
    }

    @Test fun savingUpdatesGateLocalNavigationButDoNotDiscardExternalAuthority() {
        for (source in BufferSlot.entries) {
            val target = oppositeBufferSlot(source)
            val motion = ReverbHomeBufferMotion(source)
            motion.bind(source, true, 1f)
            motion.requestSelection(target, 1f)
            assertSettled(motion, source)
            motion.bind(target, true, 1f)
            motion.frame(0L, 1f)
            motion.frame(40_000_000L, 1f)
            motion.bind(source, true, 1f)
            assertNull(motion.frame(300_000_000L, 1f))
            assertSettled(motion, source)
            motion.requestSelection(target, 0f)
            assertSettled(motion, source)
            motion.bind(source, false, 0f)
            motion.requestSelection(target, 0f)
            assertEquals(target, motion.frame(320_000_000L, 0f))
            assertSettled(motion, target)
        }
    }

    private fun assertSettled(motion: ReverbHomeBufferMotion, slot: BufferSlot) {
        assertEquals(slot, motion.displayedBuffer)
        assertEquals(slot, motion.renderedBuffer)
        assertFalse(motion.running)
        assertEquals(0f, motion.flipFrame.degrees, 0f)
        assertEquals(1f, motion.flipFrame.depthScale, 0f)
        assertEquals(.5f, motion.flipFrame.pivotFractionX, 0f)
    }
}
