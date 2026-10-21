package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * Concrete input/ripple/elevation assembly for the released onboarding controls.
 * No navigation or permission state lives here. The native root supplies the actual per-change
 * consumption/parent interest and preserves its Android ancestor delay policy.
 */
internal class OnboardingNativeInteractionsFactory(
    private val inputRoot: () -> View? = { null },
) : OnboardingFooterInteractionsFactory {
    override fun create(
        inputHost: View,
        indicationHost: View,
        filled: Boolean,
        setShadowElevationPx: (Float) -> Unit,
    ): OnboardingFooterInteractions = OnboardingNativeInteractions(
        inputHost, indicationHost, capsule = true, filled = filled,
        setShadowElevationPx = setShadowElevationPx,
        inputRoot = inputRoot,
    )

    fun row(host: View): OnboardingFooterInteractions =
        OnboardingNativeInteractions(host, host, capsule = false, filled = false,
            setShadowElevationPx = null, inputRoot = inputRoot)
}

private class OnboardingNativeInteractions(
    private val inputHost: View,
    private val indicationHost: View,
    private val capsule: Boolean,
    filled: Boolean,
    setShadowElevationPx: ((Float) -> Unit)?,
    inputRoot: () -> View?,
) : OnboardingFooterInteractions {
    private val indication = ReverbRowIndication.install(
        indicationHost, android.graphics.Color.BLACK, ValueAnimator::getDurationScale,
    )
    override val foreground: Drawable get() = indication
    private val shadow = setShadowElevationPx?.let {
        createReverbOnboardingButtonElevation(inputHost, filled, inputHost.isEnabled, it)
    }
    private val inputLocation = IntArray(2)
    private val indicationLocation = IntArray(2)
    private val ripple = ReverbRowIndicationSink(indication) { x, y, output ->
        inputHost.getLocationInWindow(inputLocation)
        indicationHost.getLocationInWindow(indicationLocation)
        output.set(x + inputLocation[0] - indicationLocation[0], y + inputLocation[1] - indicationLocation[1])
    }
    private val elevationTokens = IdentityHashMap<Any, ReverbButtonElevationToken>()
    private val sink = object : NativeClickIndication {
        override fun press(token: NativePressToken) {
            ripple.press(token)
            shadow?.begin(ReverbButtonElevationInteraction.PRESS)?.let { elevationTokens[token] = it }
        }
        override fun release(token: NativePressToken) {
            ripple.release(token)
            shadow?.end(elevationTokens.remove(token))
        }
        override fun cancel(token: NativePressToken) {
            ripple.cancel(token)
            shadow?.end(elevationTokens.remove(token))
        }
        override fun stateEnter(token: NativeStateToken) {
            ripple.stateEnter(token)
            val kind = when (token.kind) {
                NativeStateToken.Kind.HOVER -> ReverbButtonElevationInteraction.HOVER
                NativeStateToken.Kind.FOCUS -> ReverbButtonElevationInteraction.FOCUS
            }
            shadow?.begin(kind)?.let { elevationTokens[token] = it }
        }
        override fun stateExit(token: NativeStateToken) {
            ripple.stateExit(token)
            shadow?.end(elevationTokens.remove(token))
        }
    }
    private val environment = object : NativeClickEnvironment {
        override val nativeInputRoot: View? get() = inputRoot()
        override val minimumTouchWidthPx: Float get() = 48f * inputHost.resources.displayMetrics.density
        override val minimumTouchHeightPx: Float get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent): Boolean {
            val density = inputHost.resources.displayMetrics.density
            val radius = if (capsule) min(inputHost.width, inputHost.height) / (2f * density) else 0f
            return reverbGroupGeometry(inputHost.width.toFloat(), inputHost.height.toFloat(), density, radius)
                .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        }
        // Standalone fixture fallback; native root phase delivery supplies per-change interest.
        override fun interestedParent(event: MotionEvent): Boolean = false
        // None of these released onboarding controls has a long-click action.
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }
    private val input = ReverbClickController(inputHost, environment, sink)
    override val pointerEndpoint: NativeClickPhaseEndpoint = ReverbClickPhaseEndpoint(input)
    private var enabled = inputHost.isEnabled

    override fun bind(enabled: Boolean, contentColor: Int) {
        this.enabled = enabled
        // Preserve ButtonElevation's target-key behavior before disable emits cancellation ends.
        shadow?.bindEnabled(enabled)
        indication.updateColor(contentColor)
        input.bind(NativeClickBinding(inputHost, enabled, onClick = { inputHost.performClick() }))
    }
    override fun measured(width: Int, height: Int) {
        indication.onMeasured(indicationHost.width, indicationHost.height, indicationHost.resources.displayMetrics.density)
        shadow?.measured()
    }
    override fun touch(event: MotionEvent): Boolean = input.touch(event)
    override fun key(event: KeyEvent): Boolean = input.key(event)
    override fun hover(event: MotionEvent): Boolean = input.hover(event)
    override fun focusChanged(focused: Boolean) = input.focusChanged(focused)
    override fun cancel() = input.cancel()
    override fun attached() {
        input.attached()
        input.bind(NativeClickBinding(inputHost, enabled, onClick = { inputHost.performClick() }))
        shadow?.attached()
    }
    override fun detached() {
        input.detached()
        elevationTokens.clear()
        shadow?.detached()
    }
}
