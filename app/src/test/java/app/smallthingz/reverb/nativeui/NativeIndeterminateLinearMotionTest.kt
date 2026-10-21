package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeIndeterminateLinearMotionTest {
    private fun ms(value: Long) = value * 1_000_000L

    @Test fun staggeredHeadAndTailDelaysAndEndpoints() {
        val motion = NativeIndeterminateLinearMotion()
        motion.frame(0, 1f)
        motion.frame(ms(250), 1f)
        assertTrue(motion.firstHead > 0f)
        assertEquals(0f, motion.firstTail, 0f)
        assertEquals(0f, motion.secondHead, 0f)
        assertEquals(0f, motion.secondTail, 0f)
        motion.frame(ms(650), 1f)
        assertTrue(motion.firstTail > 0f)
        assertEquals(0f, motion.secondHead, 0f)
        motion.frame(ms(900), 1f)
        assertTrue(motion.secondHead > 0f)
        assertEquals(0f, motion.secondTail, 0f)
        motion.frame(ms(1250), 1f)
        assertEquals(1f, motion.firstHead, 0f)
        assertEquals(1f, motion.firstTail, 0f)
        motion.frame(ms(1500), 1f)
        assertEquals(1f, motion.secondHead, 0f)
        assertTrue(motion.secondTail in 0f..1f)
    }

    @Test fun fullCyclePreservesStaggerAndSourceClockQuantization() {
        val motion = NativeIndeterminateLinearMotion()
        motion.frame(0, 1f)
        motion.frame(ms(699), 1f)
        val previousMillisecond = listOf(motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail)
        motion.frame(ms(700), 1f)
        val values = listOf(motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail)
        // Pinned InfiniteTransition divides Long elapsed time by Float durationScale before
        // toLong: 2450ms rounds down to 2,449,999,872ns. After the 1750ms repeat, the
        // keyframe engine truncates 699,999,872ns to 699ms, exactly as the released UI does.
        assertEquals(2_449_999_872L, (ms(2450) / 1f).toLong())
        motion.frame(ms(2450), 1f)
        assertEquals(previousMillisecond,
            listOf(motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail))
        // One microsecond past that rounding boundary, all four tracks repeat the 700ms sample.
        motion.frame(ms(2450) + 1_000L, 1f)
        assertEquals(values, listOf(motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail))
    }

    @Test fun scaleChangesRestartAllTracksOnTheSampledFrame() {
        val motion = NativeIndeterminateLinearMotion()
        motion.frame(ms(100), 1f)
        motion.frame(ms(800), 1f)
        assertTrue(motion.firstHead > 0f)
        motion.frame(ms(900), 2f)
        assertEquals(0f, motion.firstHead, 0f)
        motion.frame(ms(1300), 2f)
        val normal = NativeIndeterminateLinearMotion()
        normal.frame(0, 1f)
        normal.frame(ms(200), 1f)
        assertEquals(normal.firstHead, motion.firstHead, 0f)
        assertEquals(normal.firstTail, motion.firstTail, 0f)
    }

    @Test fun zeroScaleUsesTargetsAndResumptionStartsFresh() {
        val motion = NativeIndeterminateLinearMotion()
        motion.frame(0, 0f)
        assertEquals(listOf(1f, 1f, 1f, 1f),
            listOf(motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail))
        motion.frame(ms(500), 1f)
        assertEquals(listOf(0f, 0f, 0f, 0f),
            listOf(motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail))
        motion.frame(ms(1000), 1f)
        assertTrue(motion.firstHead > 0f)
        motion.reset()
        assertEquals(0f, motion.firstHead, 0f)
    }

    @Test fun initialAndDisabledMotionHaveNoActiveSegmentAndEndWithTheWholeTrack() {
        for (value in listOf(0f, 1f)) {
            val lines = mutableListOf<Triple<Float, Float, Boolean>>()
            nativeIndeterminateLinearSegments(100f, 4f, 1f, value, value, value, value) { a, b, active ->
                lines += Triple(a, b, active)
            }
            assertTrue(lines.all { !it.third })
            assertEquals(Triple(0f, 1f, false), lines.last())
            if (value == 0f) assertEquals(1, lines.size)
            else {
                // The source first draws a reversed track past the right end, which clamps to the
                // same round endpoint before the final full track covers it. Preserve that call.
                assertEquals(2, lines.size)
                assertEquals(1.08f, lines.first().first, .000001f)
                assertEquals(1f, lines.first().second, 0f)
            }
        }
    }

    @Test fun activeSegmentsAndGapsPreserveSourceDrawOrderAndDirection() {
        val lines = mutableListOf<Triple<Float, Float, Boolean>>()
        nativeIndeterminateLinearSegments(100f, 4f, 1f, .8f, .5f, .3f, .1f) { a, b, active ->
            lines += Triple(a, b, active)
        }
        assertEquals(5, lines.size)
        assertEquals(listOf(false, true, false, true, false), lines.map { it.third })
        val expected = listOf(.88f to 1f, .8f to .5f, .38f to .42f, .3f to .1f, 0f to .02f)
        lines.zip(expected).forEach { (actual, wanted) ->
            assertEquals(wanted.first, actual.first, .000001f)
            assertEquals(wanted.second, actual.second, .000001f)
        }
    }

    @Test fun lineGeometryMirrorsDirectedEndpointsAndKeepsButtFallback() {
        val points = mutableListOf<List<Float>>()
        nativeLinearProgressLine(100f, 4f, true, .8f, .5f) { a, b, y, round ->
            assertTrue(round)
            points += listOf(a, b, y)
        }
        assertEquals(50f, points.single()[0], .00001f)
        assertEquals(20f, points.single()[1], .00001f)
        assertEquals(2f, points.single()[2], 0f)
        var drew = false
        nativeLinearProgressLine(100f, 4f, false, .5f, .5f) { _, _, _, _ -> drew = true }
        assertFalse(drew)
        nativeLinearProgressLine(2f, 4f, false, 0f, 1f) { a, b, y, round ->
            assertFalse(round)
            assertEquals(0f, a, 0f)
            assertEquals(2f, b, 0f)
            assertEquals(2f, y, 0f)
            drew = true
        }
        assertTrue(drew)
    }
}
