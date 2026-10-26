package app.smallthingz.reverb.nativeui

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerConsumption
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.rangeFineTuneDeltaSeconds
import app.smallthingz.reverb.rangeFineTuneSeekPull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSpringFineSeekStateTest {
    private val geometry = SpringFineSeekGeometry(300, 120, 1f)

    @Test fun childConsumedOrdinaryTapDoesNotPauseAudio() {
        val h = Harness()
        val down = event(150f, 60f, pressed = true, previouslyPressed = false, consumed = true)
        h.send(down)
        assertFalse(h.state.dragging)
        h.send(event(150f, 60f, pressed = false, previouslyPressed = true, consumed = true))
        assertTrue(h.events.isEmpty())
        assertFalse(h.state.needsFrames)
    }

    @Test fun outsideCircularPuckStartsFineAdjustOnTheDown() {
        val h = Harness()
        val down = event(260f, 60f, true, false)
        h.send(down)
        assertTrue(h.state.dragging)
        assertTrue(down.changes.single().consumed)
        assertEquals(listOf("old:start", "old:begin"), h.events)
        assertEquals(110f / 116f, h.state.horizontalPull, 0f)
    }

    @Test fun radialSlopEqualityDoesNotDragButNextMovementDoes() {
        val h = Harness(slop = 10f)
        h.send(event(150f, 60f, true, false))
        val exact = event(156f, 68f, true, true, previous = Offset(150f, 60f))
        h.send(exact)
        assertFalse(h.state.dragging)
        assertFalse(exact.changes.single().consumed)
        val over = event(156f, 68.01f, true, true, previous = Offset(156f, 68f))
        h.send(over)
        assertTrue(h.state.dragging)
        assertTrue(over.changes.single().consumed)
    }

    @Test fun frameAccumulatorCommitsAtThirtyThreeMillisAndRetainsPendingShuttle() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.state.frame(1_000_000_000L) // first source frame only validates the session
        assertTrue(h.commits.isEmpty())
        assertTrue(h.pending.isEmpty())
        h.state.frame(1_020_000_000L)
        assertTrue(h.commits.isEmpty())
        assertTrue(h.pending.single() > 0f)
        val first = h.pending.single()
        h.state.frame(1_040_000_000L)
        assertEquals(1, h.commits.size)
        assertEquals(first * 2f, h.commits.single(), 0f)
        assertEquals(0f, h.pending.last(), 0f)
    }

    @Test fun resizeDuringGestureUsesLiveCenterButRetainsDownTravel() {
        val h = Harness()
        h.send(event(150f, 60f, true, false))
        val wider = SpringFineSeekGeometry(400, 120, 1f)
        h.state.pointer(event(210f, 60f, true, true), NativePointerPass.MAIN, wider)
        assertTrue(h.state.dragging)
        assertEquals(10f / geometry.inputHorizontalTravel, h.state.horizontalPull, 0f)
        val narrower = SpringFineSeekGeometry(200, 120, 1f)
        h.state.pointer(event(110f, 60f, true, true), NativePointerPass.MAIN, narrower)
        assertEquals(10f / geometry.inputHorizontalTravel, h.state.horizontalPull, 0f)
    }

    @Test fun normalReleaseFlushesSubThresholdDeltaBeforeEndExactlyOnce() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.state.frame(1_000_000_000L)
        h.state.frame(1_010_000_000L)
        val pending = h.pending.single()
        h.send(event(266f, 60f, false, true))
        assertEquals(listOf(pending), h.commits)
        assertEquals(listOf("old:start", "old:begin", "old:commit", "old:end"), h.events)
        assertFalse(h.state.dragging)
        h.state.cancel()
        assertFalse(h.events.any { it.endsWith(":cancel") })
    }

    @Test fun cancelDiscardsPendingDeltaAndOnlyCancelsItsOwnedDrag() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.state.frame(1_000_000_000L)
        h.state.frame(1_010_000_000L)
        h.state.cancel()
        h.state.cancel()
        assertTrue(h.commits.isEmpty())
        assertEquals(1, h.events.count { it == "old:cancel" })
        assertFalse(h.events.any { it.endsWith(":end") })
    }

    @Test fun elapsedFrameHitchIsCappedToFiftyMillis() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.state.frame(1_000_000_000L)
        h.state.frame(2_000_000_000L)
        val expected = rangeFineTuneDeltaSeconds(rangeFineTuneSeekPull(1f), 0f, 100f, .05f)
        assertEquals(expected, h.commits.single(), 0f)
    }

    @Test fun consumedReleaseCancelsRatherThanFlushingOrEnding() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.state.frame(1_000_000_000L); h.state.frame(1_010_000_000L)
        h.send(event(266f, 60f, false, true, consumed = true))
        assertTrue(h.commits.isEmpty())
        assertEquals(1, h.events.count { it == "old:cancel" })
        assertFalse(h.events.any { it.endsWith(":end") })
    }

    @Test fun sameKeyRebindKeepsRunningPointerAndFrameCallbacksButCancelIsCurrent() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.state.bind(h.binding("new", key = "recording"), 10f)
        assertTrue(h.state.dragging)
        assertFalse(h.events.any { it.endsWith(":cancel") })
        h.state.frame(1_000_000_000L); h.state.frame(1_040_000_000L)
        assertTrue("old:commit" in h.events)
        assertFalse("new:commit" in h.events)
        h.state.cancel()
        assertTrue("new:cancel" in h.events)
    }

    @Test fun changedKeyRetiresTheOldSessionThroughTheCurrentSourceCancelCallback() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.state.bind(h.binding("new", key = "different-recording"), 10f)
        assertEquals(1, h.events.count { it == "new:cancel" })
        assertFalse("old:cancel" in h.events)
        assertFalse(h.state.dragging)
    }

    @Test fun unchangedDisabledBindingDoesNotReplayItsEnabledEffect() {
        val h = Harness(initialEnabled = false)
        assertEquals(listOf("old:cancel"), h.events)
        h.state.bind(h.binding("new", enabled = false), 10f)
        assertEquals(listOf("old:cancel"), h.events)
    }

    @Test fun originalPointerReleaseDoesNotHandoverAndWaitsForAllPointersUp() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        val firstUp = change(1, 266f, 60f, false, true)
        val secondHeld = change(2, 100f, 60f, true, true)
        h.send(NativeDragPointerEvent(listOf(firstUp, secondHeld), Offset.Zero))
        assertFalse(h.state.dragging)
        h.send(NativeDragPointerEvent(listOf(
            change(2, 100f, 60f, true, true), change(3, 260f, 60f, true, false),
        ), Offset.Zero))
        assertFalse(h.state.dragging)
        h.send(NativeDragPointerEvent(listOf(
            change(2, 100f, 60f, false, true), change(3, 260f, 60f, true, true),
        ), Offset.Zero))
        assertFalse(h.state.dragging)
        assertEquals(1, h.events.count { it.endsWith(":begin") })
        h.send(NativeDragPointerEvent(listOf(change(3, 260f, 60f, false, true)), Offset.Zero))
        h.send(event(266f, 60f, true, false))
        // No animation frame has returned the puck from x=266 yet: this DOWN is ON the puck.
        // Source waits for radial slop before beginning the next fine-adjust drag.
        assertFalse(h.state.dragging)
        h.send(event(200f, 60f, true, true, previous = Offset(266f, 60f)))
        assertTrue(h.state.dragging)
        assertEquals(2, h.events.count { it.endsWith(":begin") })
    }

    @Test fun returnWaitsForItsFirstFrameThenReachesExactlyZeroAtTwoHundredTenMillis() {
        val h = Harness()
        h.send(event(266f, 60f, true, false))
        h.send(event(266f, 60f, false, true))
        val start = h.state.horizontalPull
        assertEquals(1f, start, 0f)
        h.state.frame(3_000_000_000L)
        assertEquals(start, h.state.horizontalPull, 0f)
        h.state.frame(3_105_000_000L)
        assertTrue(h.state.horizontalPull > 0f && h.state.horizontalPull < start)
        h.state.frame(3_210_000_000L)
        assertEquals(0f, h.state.horizontalPull, 0f)
        assertEquals(0f, h.state.rawVerticalPull, 0f)
        assertFalse(h.state.needsFrames)
    }

    @Test fun returnCurveMatchesReleasedFloatBitsAcrossCompleteAndInterruptedReturns() {
        // Independent released expression: retain its per-call Float arithmetic as the oracle.
        fun released(elapsedNanos: Long): Float {
            val t = (elapsedNanos.toFloat() / 210_000_000L).coerceIn(0f, 1f)
            val strength = 3.4f
            return (kotlin.math.cosh(strength * (1f - t)) - 1f) /
                (kotlin.math.cosh(strength) - 1f)
        }
        fun checkSample(elapsed: Long) {
            val expected = released(elapsed)
            val actual = springFineSeekReturnRemaining(elapsed)
            assertEquals("remaining at $elapsed", expected.toRawBits(), actual.toRawBits())
            // A new return may begin at either sign or an interrupted fractional displacement.
            for (start in floatArrayOf(-1f, -.375f, -0.0f, 0f, .0001f, .625f, 1f)) {
                assertEquals("displacement $start at $elapsed",
                    (start * expected).toRawBits(), (start * actual).toRawBits())
            }
        }
        for (elapsed in 0L..210_000_000L step 100_000L) checkSample(elapsed)
        for (elapsed in longArrayOf(Long.MIN_VALUE, -1L, 1L, 16_666_666L, 33_333_333L,
            104_999_999L, 105_000_001L, 209_999_999L, 210_000_001L, Long.MAX_VALUE)) {
            checkSample(elapsed)
        }
    }

    @Test fun fractionalDensityKeepsOddCenteredPositionAndOnlyMirrorsInnerOffset() {
        val g = SpringFineSeekGeometry(421, 316, 421f / 160f)
        assertEquals(168, g.hitWidth)
        assertEquals(127, g.puckLeft(0f, false))
        assertEquals(127, g.puckLeft(0f, true))
        assertEquals(248, g.puckLeft(1f, false))
        assertEquals(6, g.puckLeft(1f, true))
        val tiny = SpringFineSeekGeometry(60, 60, 2.5f)
        assertEquals(1f, tiny.inputHorizontalTravel, 0f)
        assertEquals(2.5f, tiny.drawHorizontalTravel, 0f)
    }

    private inner class Harness(slop: Float = 10f, initialEnabled: Boolean = true) {
        val events = mutableListOf<String>()
        val commits = mutableListOf<Float>()
        val pending = mutableListOf<Float>()
        val state = NativeSpringFineSeekState({}, {})
        init { state.bind(binding("old", enabled = initialEnabled), slop) }
        fun binding(label: String, key: Any? = "recording", enabled: Boolean = true) =
            SpringFineSeekBinding(enabled, false, 100f, key,
                onTogglePlayback = { events += "$label:toggle" },
                onInteractionStart = { events += "$label:start" },
                onBeginFineAdjust = { events += "$label:begin" },
                onFineAdjust = { delta, hint ->
                    assertEquals(.04f, hint, 0f); events += "$label:commit"; commits += delta
                },
                onUpdateFineAdjustShuttle = { _, value -> pending += value },
                onEndFineAdjust = { events += "$label:end" },
                onCancelFineAdjust = { events += "$label:cancel" })
        fun send(event: NativeDragPointerEvent) = state.pointer(event, NativePointerPass.MAIN, geometry)
    }

    private fun event(x: Float, y: Float, pressed: Boolean, previouslyPressed: Boolean,
                      consumed: Boolean = false, previous: Offset = Offset(x, y)) =
        NativeDragPointerEvent(listOf(change(1, x, y, pressed, previouslyPressed, consumed, previous)), Offset.Zero)
    private fun change(id: Long, x: Float, y: Float, pressed: Boolean, previouslyPressed: Boolean,
                       consumed: Boolean = false, previous: Offset = Offset(x, y)) =
        NativeDragPointerChange(id, 0L, Offset(x, y), previous, pressed, previouslyPressed,
            PointerType.Touch, Offset(x, y), consumption = NativePointerConsumption(consumed))
}
