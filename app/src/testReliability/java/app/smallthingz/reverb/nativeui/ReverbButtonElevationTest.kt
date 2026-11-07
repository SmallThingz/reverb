package app.smallthingz.reverb.nativeui

import smallthingz.ui.interaction.view.ButtonElevationInteraction
import smallthingz.ui.interaction.view.FilledButtonElevationState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FloatTweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused state/math checks, not native shadow or scheduling proof. */
class ReverbButtonElevationTest {
    @Test fun latestActiveIdentityWinsAndEndingOlderTokenDoesNotReorder() {
        val state = FilledButtonElevationState()
        val olderHover = state.begin(ButtonElevationInteraction.HOVER)
        val focus = state.begin(ButtonElevationInteraction.FOCUS)
        val newerHover = state.begin(ButtonElevationInteraction.HOVER)
        assertEquals(1f, state.targetDp, 0f)
        state.end(olderHover)
        assertEquals(1f, state.targetDp, 0f)
        state.end(newerHover)
        assertEquals(0f, state.targetDp, 0f)
        state.end(focus)
        assertEquals(0f, state.targetDp, 0f)
    }

    @Test fun hoverIncomingAndOutgoingUseDifferentPinned120msEasings() {
        val state = FilledButtonElevationState()
        val hover = state.begin(ButtonElevationInteraction.HOVER)
        state.frame(1_000_000_000L, 1f)
        state.frame(1_060_000_000L, 1f)
        val incoming = FloatTweenSpec(duration = 120, easing = FastOutSlowInEasing)
        assertEquals(incoming.getValueFromNanos(60_000_000, 0f, 1f, 0f).toBits(), state.valueDp.toBits())
        state.frame(1_120_000_000L, 1f)
        assertEquals(1f, state.valueDp, 0f)
        assertFalse(state.isRunning)

        state.end(hover)
        state.frame(2_000_000_000L, 1f)
        state.frame(2_060_000_000L, 1f)
        val outgoing = FloatTweenSpec(duration = 120, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f))
        assertEquals(outgoing.getValueFromNanos(60_000_000, 1f, 0f, 0f).toBits(), state.valueDp.toBits())
    }

    @Test fun disablingNewZeroTargetSnapsButDisablingExistingZeroTargetDoesNot() {
        val snap = FilledButtonElevationState()
        snap.begin(ButtonElevationInteraction.HOVER)
        snap.frame(1_000_000_000L, 1f)
        snap.frame(1_060_000_000L, 1f)
        assertTrue(snap.valueDp > 0f)
        snap.setEnabled(false)
        assertEquals(0f, snap.valueDp, 0f)
        assertFalse(snap.isRunning)

        val existingTarget = FilledButtonElevationState()
        val hover = existingTarget.begin(ButtonElevationInteraction.HOVER)
        existingTarget.frame(1_000_000_000L, 1f)
        existingTarget.frame(1_120_000_000L, 1f)
        existingTarget.end(hover)
        existingTarget.frame(2_000_000_000L, 1f)
        existingTarget.frame(2_040_000_000L, 1f)
        val beforeDisable = existingTarget.valueDp
        existingTarget.setEnabled(false)
        assertEquals(beforeDisable.toBits(), existingTarget.valueDp.toBits())
        assertEquals(0f, existingTarget.targetDp, 0f)
        assertTrue(existingTarget.isRunning)
    }

    @Test fun zeroDurationScaleFinishesAndOwnerRetirementInvalidatesOldTokens() {
        val state = FilledButtonElevationState()
        val oldHover = state.begin(ButtonElevationInteraction.HOVER)
        state.frame(1_000_000_000L, 0f)
        assertEquals(1f, state.valueDp, 0f)
        assertFalse(state.isRunning)
        state.retireOwner()
        assertEquals(0f, state.valueDp, 0f)
        val newHover = state.begin(ButtonElevationInteraction.HOVER)
        state.end(oldHover)
        assertEquals(1f, state.targetDp, 0f)
        state.end(newHover)
        assertEquals(0f, state.targetDp, 0f)
    }

    @Test fun interruptionKeepsDeliveredValueAndStartsNewTweenAtLastFrame() {
        val state = FilledButtonElevationState()
        state.begin(ButtonElevationInteraction.HOVER)
        state.frame(1_000_000_000L, 1f)
        state.frame(1_040_000_000L, 1f)
        val interrupted = state.valueDp
        state.begin(ButtonElevationInteraction.PRESS)
        assertEquals(interrupted.toBits(), state.valueDp.toBits())
        state.frame(1_100_000_000L, 1f)
        val incoming = FloatTweenSpec(duration = 120, easing = FastOutSlowInEasing)
        assertEquals(incoming.getValueFromNanos(60_000_000, interrupted, 0f, 0f).toBits(), state.valueDp.toBits())
    }
}
