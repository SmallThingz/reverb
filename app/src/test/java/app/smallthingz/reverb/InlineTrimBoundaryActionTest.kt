package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class InlineTrimBoundaryActionTest {
    @Test fun eachEndpointCanBeEditedWithoutMovingTheOtherEndpoint() {
        val original = InlineFineSeekValues(4_000, 2_000, 10_000)
        for ((target, requested, expected) in listOf(
            Triple(InlineFineSeekTarget.TRIM_START, 3_125.0, original.copy(startMillis = 3_125)),
            Triple(InlineFineSeekTarget.TRIM_END, 8_250.0, original.copy(endMillis = 8_250)),
        )) {
            var paused = false
            var result: InlineFineSeekValues? = null
            assertTrue(runInlineTrimBoundaryAction(original, 20_000, target, requested, true,
                pause = { paused = true }, apply = { assertTrue(paused); result = it }))
            assertEquals(expected, result)
        }
    }

    @Test fun crossingAndSourceLimitsMatchTheExistingPointerBoundaryPolicy() {
        val original = InlineFineSeekValues(4_000, 2_000, 10_000)
        for ((target, request) in listOf(
            InlineFineSeekTarget.TRIM_START to -1.0e100,
            InlineFineSeekTarget.TRIM_START to 15_000.0,
            InlineFineSeekTarget.TRIM_START to 1.0e100,
            InlineFineSeekTarget.TRIM_END to -1.0e100,
            InlineFineSeekTarget.TRIM_END to 1_000.0,
            InlineFineSeekTarget.TRIM_END to 1.0e100,
        )) {
            var result: InlineFineSeekValues? = null
            assertTrue(runInlineTrimBoundaryAction(original, 20_000, target, request, true, {}, { result = it }))
            val limits = inlineTrimBoundaryLimits(20_000, target)!!
            val expected = adjustInlineTrimTarget(original, 20_000, target,
                request.coerceIn(limits.first.toDouble(), limits.last.toDouble()).toInt())
            assertEquals(expected, result)
            assertTrue(result!!.endMillis - result!!.startMillis >= 50)
            assertTrue(result!!.startMillis >= 0 && result!!.endMillis <= 20_000)
        }
    }

    @Test fun subMinimumSourceAndMaximumIntDurationStayBounded() {
        for (duration in listOf(1, 25, 50, Int.MAX_VALUE)) {
            val original = InlineFineSeekValues(0, 0, duration)
            for (target in listOf(InlineFineSeekTarget.TRIM_START, InlineFineSeekTarget.TRIM_END)) {
                for (requested in listOf(-1.0e100, 1.0e100)) {
                    var result: InlineFineSeekValues? = null
                    assertTrue(runInlineTrimBoundaryAction(original, duration, target, requested, true, {}, { result = it }))
                    assertTrue(result!!.startMillis >= 0)
                    assertTrue(result!!.endMillis <= duration)
                    assertTrue(result!!.endMillis - result!!.startMillis >= minOf(50, duration))
                }
            }
        }
    }

    @Test fun nonCurrentOrUnavailableOwnersCannotPauseOrMutateTheRange() {
        val admitted = listOf(true, true, false, false, true, true, false)
        fun allowed(flags: List<Boolean>) = inlineTrimBoundaryActionAllowed(
            prepared = flags[0], trimMode = flags[1], saving = flags[2], released = flags[3],
            visible = flags[4], resumed = flags[5], gestureActive = flags[6],
        )
        assertTrue(allowed(admitted))
        for (index in admitted.indices) {
            val blocked = admitted.toMutableList().apply { this[index] = !this[index] }
            assertFalse(allowed(blocked))
            assertFalse(runInlineTrimBoundaryAction(InlineFineSeekValues(0, 0, 10_000), 10_000,
                InlineFineSeekTarget.TRIM_START, 1_000.0, allowed(blocked),
                { error("denied input must not pause playback") }, { error("denied input must not edit") }))
        }
    }

    @Test fun invalidRequestsHaveNoPlaybackOrRangeSideEffects() {
        for ((request, duration, target) in listOf(
            Triple(Double.NaN, 10_000, InlineFineSeekTarget.TRIM_START),
            Triple(Double.POSITIVE_INFINITY, 10_000, InlineFineSeekTarget.TRIM_END),
            Triple(1_000.0, 0, InlineFineSeekTarget.TRIM_START),
            Triple(1_000.0, 10_000, InlineFineSeekTarget.PLAYHEAD),
        )) {
            assertFalse(runInlineTrimBoundaryAction(InlineFineSeekValues(0, 0, 10_000), duration,
                target, request, true, { error("must not pause") }, { error("must not apply") }))
        }
    }

    @Test fun advertisedFloatEndpointsRoundTripToExactSourceBounds() {
        for (duration in listOf(25, 100_000_003, 2_000_000_050, Int.MAX_VALUE)) {
            for (target in listOf(InlineFineSeekTarget.TRIM_START, InlineFineSeekTarget.TRIM_END)) {
                val limits = inlineTrimBoundaryLimits(duration, target)!!
                for (exactMillis in listOf(limits.first, limits.last)) {
                    val advertisedSeconds = exactMillis.toFloat() / 1000f
                    val request = inlineTrimBoundaryProgressMillis(advertisedSeconds, duration, target)!!
                    assertEquals(exactMillis.toDouble(), request, 0.0)
                    var result: InlineFineSeekValues? = null
                    assertTrue(runInlineTrimBoundaryAction(InlineFineSeekValues(0, 0, duration),
                        duration, target, request, true, {}, { result = it }))
                    val actual = if (target == InlineFineSeekTarget.TRIM_START) result!!.startMillis else result!!.endMillis
                    assertEquals(exactMillis, actual)
                }
            }
        }
        assertEquals(1_250.0, inlineTrimBoundaryProgressMillis(1.25f, 10_000, InlineFineSeekTarget.TRIM_START)!!, 0.0)
        assertNull(inlineTrimBoundaryProgressMillis(Float.NaN, 10_000, InlineFineSeekTarget.TRIM_START))
        assertNull(inlineTrimBoundaryProgressMillis(Float.POSITIVE_INFINITY, 10_000, InlineFineSeekTarget.TRIM_END))
    }

    @Test fun rapidActionsPauseAndQueueOnlyTheirLatestExplicitEndpoint() {
        var values = InlineFineSeekValues(0, 0, 10_000)
        val seeks = InlinePlaybackSeekQueue()
        seeks.request(0, resumePlayback = true)
        for (requested in listOf(1_000.0, 2_000.0, 3_000.0)) {
            assertTrue(runInlineTrimBoundaryAction(values, 10_000, InlineFineSeekTarget.TRIM_START,
                requested, true, pause = { seeks.resume = false }, apply = {
                    values = it
                    seeks.request(it.startMillis, resumePlayback = false)
                }))
        }
        assertEquals(3_000, values.startMillis)
        assertEquals(10_000, values.endMillis)
        assertEquals(3_000, seeks.completed())
        assertNull(seeks.completed())
        assertFalse(seeks.resume)
    }
}
