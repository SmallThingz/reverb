package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeInputOwnershipTest {
    @Test
    fun bodyEntryWhileWheelIsActiveDoesNotEditOrCancelTheWheelOwner() {
        val wheel = RangeDurationWheelInteraction().begin(RangeEditTarget.END)
        val retired = mutableListOf<Boolean>()
        val body = RangeInputGesture({ true }, { wheel }, { retired += it })

        assertFalse(body.perform { error("body must not select or scrub") })
        assertFalse(body.finish { error("body must not audition or resume") })
        assertEquals(listOf(false), retired)
        assertEquals(RangeEditTarget.END, wheel.commitTarget())
    }

    @Test
    fun completedWheelBetweenBodyCallbacksRetiresMoveAndReleaseButAllowsFreshBody() {
        for (changed in listOf(false, true)) {
            for (releaseNext in listOf(false, true)) {
                var wheel = RangeDurationWheelInteraction()
                var values = RangeEditValues(30f, 80f)
                val retired = mutableListOf<Boolean>()
                val body = RangeInputGesture({ true }, { wheel }, { retired += it })
                assertTrue(body.perform {
                    values = adjustRangeEditTarget(values, RangeEditTarget.START, 35f, 100f).values
                })

                wheel = wheel.begin(RangeEditTarget.START)
                if (changed) values = resizeRangeSelectionDuration(
                    values, requireNotNull(wheel.commitTarget()), 25f, 100f,
                ).values
                wheel = wheel.end()
                val wheelValues = values

                if (!releaseNext) assertFalse(body.perform {
                    values = adjustRangeEditTarget(values, RangeEditTarget.START, 40f, 100f).values
                })
                assertFalse(body.finish { error("old body must not resume") })
                assertEquals(wheelValues, values)
                assertEquals(listOf(false), retired)

                val fresh = RangeInputGesture({ true }, { wheel }, { error("fresh body is admitted") })
                assertTrue(fresh.perform {
                    values = adjustRangeEditTarget(values, RangeEditTarget.START, values.startSeconds + 1f, 100f).values
                })
                assertTrue(fresh.finish {})
                assertEquals(wheelValues.startSeconds + 1f, values.startSeconds, 0f)
            }
        }
    }

    @Test
    fun nextPuckFrameAfterImmediateWheelTransactionCannotCommitOrShuttleWithoutPointerEvent() {
        for (changed in listOf(false, true)) {
            var wheel = RangeDurationWheelInteraction()
            var selectedStart = 30f
            var newerPreviewPlaying = false
            val retired = mutableListOf<Boolean>()
            val commits = mutableListOf<Float>()
            val shuttles = mutableListOf<Float>()
            val puck = FineSeekInputSession(
                createInputGesture = { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
                onCancel = { _, stillCurrent ->
                    retired += stillCurrent
                    if (stillCurrent) newerPreviewPlaying = false
                },
            )
            assertTrue(puck.perform {})
            assertTrue(puck.frame(0.25f, 16_000_000L, { commits += it }, { shuttles += it }))
            assertTrue(commits.isEmpty())
            assertEquals(listOf(0.25f), shuttles)

            // The same native generic-scroll callback begins, optionally commits, and ends.
            wheel = wheel.begin(RangeEditTarget.START)
            if (changed) selectedStart = 55f
            wheel = wheel.end()
            newerPreviewPlaying = true
            val wheelStart = selectedStart

            assertFalse(puck.frame(
                0.5f, 20_000_000L,
                { delta -> selectedStart += delta; commits += delta },
                { shuttles += it },
            ))
            assertFalse(puck.finishFineAdjust(
                { delta -> selectedStart += delta; commits += delta },
                { error("retired puck must not resume") },
            ))
            assertEquals(wheelStart, selectedStart, 0f)
            assertTrue(commits.isEmpty())
            assertEquals(listOf(0.25f), shuttles)
            assertEquals(listOf(false), retired)
            assertTrue(newerPreviewPlaying)

            val fresh = FineSeekInputSession(
                { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
                { _, _ -> error("fresh puck is admitted") },
            )
            assertTrue(fresh.frame(0.125f, 40_000_000L, { commits += it }, {}))
            assertTrue(fresh.finishFineAdjust({ commits += it }, {}))
            assertEquals(listOf(0.125f), commits) // No pending delta leaks from the retired puck.
        }
    }

    @Test
    fun puckReleaseAfterWheelTakeoverDiscardsPendingDeltaBeforeAnyNextFrame() {
        for (completeWheel in listOf(false, true)) {
            var wheel = RangeDurationWheelInteraction()
            val retired = mutableListOf<Boolean>()
            var commits = 0
            val puck = FineSeekInputSession(
                { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
                { _, stillCurrent -> retired += stillCurrent },
            )
            assertTrue(puck.frame(0.25f, 10_000_000L, { commits++ }, {}))
            wheel = wheel.begin(RangeEditTarget.START)
            if (completeWheel) wheel = wheel.end()

            assertFalse(puck.finishFineAdjust({ commits++ }, { error("old release must not resume") }))
            assertFalse(puck.perform { error("old pointer cannot restart fine adjustment") })
            puck.cancel()
            assertEquals(0, commits)
            assertEquals(listOf(false), retired)
        }
    }

    @Test
    fun normalPuckFramesCoalesceAndReleaseFlushesExactRemainderOnce() {
        val wheel = RangeDurationWheelInteraction()
        val commits = mutableListOf<Float>()
        val shuttles = mutableListOf<Float>()
        var ends = 0
        val puck = FineSeekInputSession(
            { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
            { _, _ -> error("normal gesture is not cancelled") },
        )
        assertTrue(puck.perform {})
        assertTrue(puck.frame(0.25f, 16_000_000L, { commits += it }, { shuttles += it }))
        assertTrue(puck.frame(0.5f, 20_000_000L, { commits += it }, { shuttles += it }))
        assertTrue(puck.frame(-0.125f, 10_000_000L, { commits += it }, { shuttles += it }))
        assertTrue(puck.finishFineAdjust({ commits += it }, { ends++ }))
        assertFalse(puck.frame(100f, 40_000_000L, { commits += it }, { shuttles += it }))
        assertFalse(puck.finishFineAdjust({ commits += it }, { ends++ }))
        puck.cancel()

        assertEquals(listOf(0.75f, -0.125f), commits)
        assertEquals(listOf(0.25f, 0f, -0.125f), shuttles)
        assertEquals(1, ends)
    }

    @Test
    fun readinessLossCancelsCurrentPuckOnceAndNeverFlushesOrResumes() {
        var ready = true
        val wheel = RangeDurationWheelInteraction()
        val retired = mutableListOf<Boolean>()
        val puck = FineSeekInputSession(
            { onCancel -> RangeInputGesture({ ready }, { wheel }, onCancel) },
            { _, stillCurrent -> retired += stillCurrent },
        )
        assertTrue(puck.frame(0.5f, 10_000_000L, { error("not due") }, {}))
        ready = false

        assertFalse(puck.frame(0.5f, 30_000_000L, { error("no cancelled commit") }, { error("no cancelled shuttle") }))
        assertFalse(puck.finishFineAdjust({ error("no final delta") }, { error("no cancelled resume") }))
        puck.cancel()
        assertEquals(listOf(true), retired)
    }

    @Test
    fun heldPuckTapCannotPlayAfterWheelTransactionButFreshTapCan() {
        var wheel = RangeDurationWheelInteraction()
        var taps = 0
        val retired = mutableListOf<Boolean>()
        val oldTap = FineSeekInputSession(
            { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
            { _, stillCurrent -> retired += stillCurrent },
        )
        assertTrue(oldTap.perform {})
        wheel = wheel.begin(RangeEditTarget.END).end()

        assertFalse(oldTap.finishPointerTap { taps++ })
        assertEquals(listOf(false), retired)
        val freshTap = FineSeekInputSession(
            { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
            { _, _ -> error("fresh tap is admitted") },
        )
        assertTrue(freshTap.perform {})
        assertTrue(freshTap.finishPointerTap { taps++ })
        assertFalse(freshTap.finishPointerTap { taps++ })
        assertEquals(1, taps)
    }

    @Test
    fun lateRetirementDoesNotTouchAnotherSessionsAccumulator() {
        var wheel = RangeDurationWheelInteraction()
        val old = FineSeekInputSession(
            { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
            { _, stillCurrent -> assertFalse(stillCurrent) },
        )
        assertTrue(old.frame(20f, 10_000_000L, { error("not due") }, {}))
        wheel = wheel.begin(RangeEditTarget.START).end()
        val freshCommits = mutableListOf<Float>()
        val fresh = FineSeekInputSession(
            { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
            { _, _ -> error("fresh session must survive") },
        )
        assertTrue(fresh.frame(0.25f, 20_000_000L, { freshCommits += it }, {}))
        old.cancel()
        assertFalse(old.finishFineAdjust({ error("old final delta") }, { error("old end") }))
        assertTrue(fresh.frame(0.5f, 20_000_000L, { freshCommits += it }, {}))
        assertTrue(fresh.finishFineAdjust({ freshCommits += it }, {}))
        assertEquals(listOf(0.75f), freshCommits)
    }

    @Test
    fun pointerTapUsesLiveSquareBoundsAndCannotRecoverAfterLeavingOrConsumption() {
        assertTrue(fineSeekPointerTapInsideBounds(31f, 31f, 0f, 0f, 32f))
        assertFalse(rangeFineTunePuckContains(31f, 31f, 0f, 0f, 32f))
        assertTrue(fineSeekPointerTapInsideBounds(31f, 0f, 0f, 0f, 32f))
        assertFalse(fineSeekPointerTapInsideBounds(31f, 0f, -2f, 0f, 32f))
        for (consumed in listOf(false, true)) {
            val wheel = RangeDurationWheelInteraction()
            var taps = 0
            val tap = FineSeekInputSession(
                { onCancel -> RangeInputGesture({ true }, { wheel }, onCancel) },
                { _, _ -> error("only tap eligibility is cancelled") },
            )
            assertTrue(tap.perform {})
            tap.observePointerTap(insideBounds = consumed, consumed = consumed)
            tap.observePointerTap(insideBounds = true, consumed = false)
            assertTrue(tap.finishPointerTap { taps++ })
            assertEquals(0, taps)
        }
    }
}
