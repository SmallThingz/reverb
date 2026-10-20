package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class FineSeekPositionTest {
    private val frameNanos = 16_666_667L
    private val frameSeconds = frameNanos / 1_000_000_000f

    private class RangeEditor(
        var values: RangeEditValues = RangeEditValues(70_000f, 71_000f),
        var duration: Float = 72_000f,
        var target: RangeEditTarget = RangeEditTarget.START,
    ) {
        val position = FineSeekPosition<RangeEditTarget>()
        var wheel = RangeDurationWheelInteraction()

        fun commit(delta: Float) {
            values = adjustRangeFineSeekPosition(position, values, target, delta, duration).values
        }

        fun project(pending: Float) = projectRangeFineSeekPosition(position, values, target, pending, duration)

        fun session(): FineSeekInputSession {
            position.reset()
            return FineSeekInputSession(
                { cancel -> RangeInputGesture({ true }, { wheel }, cancel) },
                { _, stillCurrent -> if (stillCurrent) position.reset() },
            )
        }
    }

    private fun slowRangeDelta(): Float = rangeFineTuneDeltaSeconds(
        horizontalPull = rangeFineTuneSeekPull(0.01f),
        verticalPull = rangeFineTuneConstrainedY(2f, 0.01f),
        durationSeconds = 72_000f,
        dtSeconds = frameSeconds,
    )

    @Test
    fun ordinarySlowFramesReachTheProductionRangeUpdateAtSeventyThousandSeconds() {
        for (target in RangeEditTarget.values()) {
            val editor = RangeEditor(target = target)
            val original = editTargetValue(editor.values, target)
            val delta = slowRangeDelta()
            assertTrue(delta > 0f)
            assertTrue(delta * 3f < Math.ulp(original) / 2f)
            val session = editor.session()
            var projected = original.toDouble()
            repeat(600) {
                assertTrue(session.frame(delta, frameNanos, editor::commit) { projected = editor.project(it) })
            }
            assertTrue(session.finishFineAdjust(editor::commit, editor.position::reset))
            val expected = original.toDouble() + delta.toDouble() * 600
            assertEquals(expected.toFloat(), editTargetValue(editor.values, target), 0f)
            assertEquals(expected, projected, 0.000001)
            assertTrue(editTargetValue(editor.values, target) > original + 0.5f)
        }
    }

    @Test
    fun reversingTheSameGestureRecoversBothPositiveAndNegativeRoundingRemainders() {
        val editor = RangeEditor()
        val initial = editor.values
        val delta = slowRangeDelta()
        val session = editor.session()
        repeat(600) { session.frame(delta, frameNanos, editor::commit, {}) }
        assertTrue(editor.values.startSeconds > initial.startSeconds)
        repeat(600) { session.frame(-delta, frameNanos, editor::commit, {}) }
        session.finishFineAdjust(editor::commit, editor.position::reset)
        assertEquals(initial, editor.values)

        editor.commit(0.003f)
        assertEquals(initial, editor.values)
        editor.commit(0.003f)
        assertEquals(Math.nextUp(initial.startSeconds), editor.values.startSeconds, 0f)
        editor.commit(-0.003f)
        assertEquals(initial, editor.values)
    }

    @Test
    fun shuttleProjectionIncludesResidualAndPendingWithoutConsumingEither() {
        val editor = RangeEditor()
        editor.commit(0.002f)
        assertEquals(70_000f, editor.values.startSeconds, 0f)
        val expected = 70_000.0 + 0.002f.toDouble() + 0.001f.toDouble()
        repeat(4) { assertEquals(expected, editor.project(0.001f), 0.0) }
        editor.commit(0.002f)
        assertEquals(Math.nextUp(70_000f), editor.values.startSeconds, 0f)
        assertEquals(70_000.0 + 0.002f.toDouble() * 2, editor.project(0f), 0.0)
    }

    @Test
    fun cancelAndFreshGestureDiscardResidualAndUndrainedMotion() {
        val editor = RangeEditor()
        val old = editor.session()
        old.frame(0.003f, 40_000_000L, editor::commit, {})
        old.frame(0.003f, 10_000_000L, editor::commit, {})
        old.cancel()
        assertFalse(old.finishFineAdjust(editor::commit, editor.position::reset))
        val fresh = editor.session()
        fresh.frame(0.003f, 40_000_000L, editor::commit, {})
        assertEquals(70_000f, editor.values.startSeconds, 0f)
        assertTrue(fresh.finishFineAdjust(editor::commit, editor.position::reset))
        assertEquals(70_000.0, editor.project(0f), 0.0)
    }

    @Test
    fun retiredGestureCannotResetTheNewerOwnersResidual() {
        val editor = RangeEditor()
        val old = editor.session()
        old.frame(0.003f, 10_000_000L, editor::commit, {})
        editor.wheel = editor.wheel.begin(RangeEditTarget.START).end()
        val fresh = editor.session()
        fresh.frame(0.003f, 40_000_000L, editor::commit, {})
        old.cancel()
        assertFalse(old.finishFineAdjust(editor::commit, editor.position::reset))
        fresh.frame(0.003f, 40_000_000L, editor::commit, {})
        assertEquals(Math.nextUp(70_000f), editor.values.startSeconds, 0f)
    }

    @Test
    fun targetDirectEditAndBoundsChangesRebaseRatherThanReusePreviousMotion() {
        val editor = RangeEditor()
        editor.commit(0.003f)
        editor.target = RangeEditTarget.END
        editor.commit(0.003f)
        assertEquals(71_000f, editor.values.endSeconds, 0f)
        editor.position.reset() // Direct edits also reset when they leave the numeric value unchanged.
        editor.commit(0.003f)
        assertEquals(71_000f, editor.values.endSeconds, 0f)
        editor.values = RangeEditValues(60_000f, 70_000f)
        editor.commit(0.003f)
        assertEquals(70_000f, editor.values.endSeconds, 0f)
        editor.duration = 73_000f
        editor.commit(0.003f)
        assertEquals(70_000f, editor.values.endSeconds, 0f)
    }

    @Test
    fun rangeBoundsDiscardOverscrollBeforeSmallInwardMotionAndKeepCornerPushing() {
        val editor = RangeEditor(values = RangeEditValues(70_000f, 72_000f), target = RangeEditTarget.END)
        repeat(20) { editor.commit(100f) }
        editor.commit(-0.002f)
        assertEquals(72_000f, editor.values.endSeconds, 0f)
        editor.commit(-0.002f)
        assertEquals(Math.nextDown(72_000f), editor.values.endSeconds, 0f)

        editor.position.reset()
        editor.target = RangeEditTarget.START
        editor.values = RangeEditValues(0f, 0.05f)
        repeat(20) { editor.commit(-100f) }
        editor.commit(0.01f)
        assertEquals(0.01f, editor.values.startSeconds, 0f)
        assertEquals(0.06f, editor.values.endSeconds, 0.000001f)
        val limit = requireNotNull(rangeEndpointLimits(editor.duration, RangeEditTarget.START)).endInclusive
        editor.commit(Float.MAX_VALUE)
        assertEquals(limit, editor.values.startSeconds, 0f)
        assertTrue(editor.values.startSeconds < editor.values.endSeconds)
        editor.commit(-0.02f)
        assertTrue(editor.values.startSeconds < limit)
    }

    @Test
    fun shortTimelinesKeepPrecisionAndExistingMinimumGap() {
        val editor = RangeEditor(RangeEditValues(25f, 29f), 30f)
        val delta = rangeFineTuneDeltaSeconds(
            rangeFineTuneSeekPull(0.01f), rangeFineTuneConstrainedY(2f, 0.01f), 30f, frameSeconds,
        )
        val session = editor.session()
        repeat(600) { session.frame(delta, frameNanos, editor::commit, {}) }
        session.finishFineAdjust(editor::commit, editor.position::reset)
        assertEquals((25.0 + delta.toDouble() * 600).toFloat(), editor.values.startSeconds, 0f)
        assertEquals(29f, editor.values.endSeconds, 0f)
        for (target in RangeEditTarget.values()) {
            val tiny = RangeEditor(RangeEditValues(0f, 0.02f), 0.02f, target)
            tiny.commit(100f)
            tiny.commit(-100f)
            assertEquals(RangeEditValues(0f, 0.02f), tiny.values)
        }
    }

    @Test
    fun ordinarySharedFramesAccumulateSubMillisecondLibraryPlaybackAndTrimMotion() {
        for (target in InlineFineSeekTarget.values()) {
            val position = FineSeekPosition<InlineFineSeekTarget>()
            var values = InlineFineSeekValues(10_000, 10_000, 20_000)
            fun current() = when (target) {
                InlineFineSeekTarget.PLAYHEAD -> values.cursorMillis
                InlineFineSeekTarget.TRIM_START -> values.startMillis
                InlineFineSeekTarget.TRIM_END -> values.endMillis
            }
            fun commit(delta: Float) {
                values = adjustInlineFineSeekPosition(position, values, target, delta, 30_000)
            }
            val initial = current()
            val delta = rangeFineTuneDeltaSeconds(rangeFineTuneSeekPull(0.01f), 0f, 30f, frameSeconds)
            assertTrue(delta > 0f && delta * 3_000f < 0.5f)
            val session = FineSeekInputSession(
                { cancel -> RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, cancel) },
                { _, _ -> position.reset() },
            )
            var projected = initial / 1000.0
            repeat(600) {
                session.frame(delta, frameNanos, ::commit) {
                    projected = projectInlineFineSeekPosition(position, values, target, it, 30_000)
                }
            }
            session.finishFineAdjust(::commit, position::reset)
            val expectedMillis = initial + delta.toDouble() * 600 * 1_000
            assertEquals(expectedMillis.roundToInt(), current())
            assertTrue(current() > initial)
            assertEquals(expectedMillis / 1_000.0, projected, 0.0000001)
        }
    }

    @Test
    fun inlineRoundingReversalBoundsAndProductionPauseRevokeResidual() {
        val bookkeeping = InlinePlayerBookkeeping()
        val position = bookkeeping.fineSeekPosition
        var values = InlineFineSeekValues(1000, 0, 2000)
        fun commit(delta: Float) {
            values = adjustInlineFineSeekPosition(position, values, InlineFineSeekTarget.PLAYHEAD, delta, 2000)
        }
        commit(0.0004f)
        assertEquals(1000, values.cursorMillis)
        commit(0.0004f)
        assertEquals(1001, values.cursorMillis)
        commit(-0.0004f)
        assertEquals(1000, values.cursorMillis)
        bookkeeping.revokeResume()
        commit(0.0004f)
        assertEquals(1000, values.cursorMillis)
        repeat(10) { commit(10f) }
        repeat(3) { commit(-0.0002f) }
        assertEquals(1999, values.cursorMillis)
        repeat(10) { commit(-10f) }
        repeat(3) { commit(0.0002f) }
        assertEquals(1, values.cursorMillis)

        for (target in listOf(InlineFineSeekTarget.TRIM_START, InlineFineSeekTarget.TRIM_END)) {
            val trimPosition = FineSeekPosition<InlineFineSeekTarget>()
            var trim = InlineFineSeekValues(0, 0, 2000)
            trim = adjustInlineFineSeekPosition(trimPosition, trim, target, 10f, 2000)
            val atBound = if (target == InlineFineSeekTarget.TRIM_START) trim.startMillis else trim.endMillis
            assertEquals(if (target == InlineFineSeekTarget.TRIM_START) 1950 else 2000, atBound)
            repeat(3) { trim = adjustInlineFineSeekPosition(trimPosition, trim, target, -0.0002f, 2000) }
            val inward = if (target == InlineFineSeekTarget.TRIM_START) trim.startMillis else trim.endMillis
            assertEquals(atBound - 1, inward)
            assertTrue(trim.endMillis - trim.startMillis >= 50)
        }
    }
}
