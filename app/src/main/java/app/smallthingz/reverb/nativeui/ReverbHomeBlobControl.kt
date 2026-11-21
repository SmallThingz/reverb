package app.smallthingz.reverb.nativeui

import smallthingz.ui.interaction.view.isNativeClickActivationKey

import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.NativeClickIndication
import smallthingz.ui.interaction.view.NativePressToken
import smallthingz.ui.interaction.view.NativeStateToken
import smallthingz.ui.interaction.view.ViewClickController
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween
import app.smallthingz.reverb.NativeAudioBlobController
import smallthingz.ui.components.blob.NativeAudioBlobControlView
import smallthingz.ui.components.blob.NativeAudioBlobDescriptions
import smallthingz.ui.components.blob.NativeAudioBlobIcons
import smallthingz.ui.components.blob.NativeAudioBlobReadout
import app.smallthingz.reverb.R
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import kotlinx.coroutines.CoroutineScope

/** Already resolved BufferBlobPage facts; no service reference or recording action is owned here. */
internal data class ReverbHomeBlobState(
    val controller: NativeAudioBlobController,
    val active: Boolean,
    val saving: Boolean,
    val captureEnabled: Boolean,
    val clickableEnabled: Boolean,
    val filled: Boolean,
    val dimmed: Boolean,
    val primaryText: String?,
    val secondaryText: String?,
    val visualizerVisible: Boolean,
    val skipSilenceEnabled: Boolean = false,
)

/** Source flip animation is owned by the containing Home state owner, not by each drawable. */
internal data class ReverbHomeFlipFrame(
    val degrees: Float = 0f,
    val pivotFractionX: Float = .5f,
    val depthScale: Float = 1f,
    val contentAlpha: Float = 1f,
)

/**
 * AudioBlobControl's existing native renderer + the exact overlay labels.
 *
 * The required input environment comes from the parent's source-equivalent horizontal gesture
 * owner. There is deliberately no guessed GestureDetector/interception implementation here.
 * The host calls close when this source node leaves the normal Home branch.
 */
internal class ReverbHomeBlobControl(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    inputEnvironment: NativeClickEnvironment,
    animationScope: CoroutineScope,
    private var onActivate: () -> Unit,
) : NativeAudioBlobControlView(
    context,
    createHomeBlobReadout(context, initialEnvironment),
    initialEnvironment.audioBlobStyle,
    initialEnvironment.textDensity.density,
), AutoCloseable, NativeClickPhaseHost {
    private var environment = initialEnvironment
    private var current: ReverbHomeBlobState? = null
    private var flip = ReverbHomeFlipFrame()
    private var attachedController: NativeAudioBlobController? = null
    private val ownerKey = Any()
    private val performCurrentClick: () -> Unit = { performClick() }
    private var clickBinding: NativeClickBinding? = null
    private val presses = LinkedHashSet<NativePressToken>()
    private val pressMotion = LibraryTweenValue(1f, Float.VectorConverter,
        tween<Float>(initialEnvironment.audioBlobStyle.pressDurationMillis),
        { AnimationVector1D(0f) })
    private var closed = false
    override val focusDecorationEnabled: Boolean get() = !closed
    private val frames = HomeFrameLoop(this, animationScope, { !closed && pressMotion.running }) { nanos, scale ->
        pressMotion.frame(nanos, scale)
        applyTransform()
    }
    private val click = ViewClickController(this, inputEnvironment, object : NativeClickIndication {
        override fun press(token: NativePressToken) { presses.add(token); updatePressed() }
        override fun release(token: NativePressToken) { presses.remove(token); updatePressed() }
        override fun cancel(token: NativePressToken) { presses.remove(token); updatePressed() }
        // Keyboard focus has a perimeter cue; hover retains the unmodified material.
        override fun stateEnter(token: NativeStateToken) {
            if (token.kind == NativeStateToken.Kind.FOCUS) setFocusVisible(true)
        }
        override fun stateExit(token: NativeStateToken) {
            if (token.kind == NativeStateToken.Kind.FOCUS) setFocusVisible(false)
        }
    })
    override val nativeClickEndpoint = ViewClickPhaseEndpoint(click)

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { onActivate() }
    }

    fun bind(value: ReverbHomeBlobState, frame: ReverbHomeFlipFrame, action: () -> Unit) {
        check(!closed)
        current = value
        flip = frame
        onActivate = action
        // The source reuses the same AndroidView, transferring controller identity on the midpoint.
        if (attachedController !== value.controller) {
            attachedController?.detach(renderer)
            value.controller.attach(renderer)
            attachedController = value.controller
        }
        renderState()
        bindInput()
    }

    /** A flip does not change content/input, but renderer maintenance remains unconditional. */
    fun updateFrame(frame: ReverbHomeFlipFrame) {
        check(!closed)
        flip = frame
        current?.let { updateRendererState(it) }
        overlay.setContentOpacity(frame.contentAlpha)
        applyTransform()
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        setControlAppearance(value.audioBlobStyle, value.textDensity.density)
        overlay.setAppearance(value.audioBlobStyle, value.textDensity, value.sharedFontResolver,
            homeBlobDescriptions(context))
        renderState()
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.setDescriptions(homeBlobDescriptions(context))
    }

    private fun bindInput() {
        val enabled = current?.let { it.clickableEnabled && !it.saving } == true
        isEnabled = enabled
        isFocusable = enabled
        if (!enabled) setFocusVisible(false)
        if (clickBinding?.enabled != enabled) {
            val binding = NativeClickBinding(ownerKey, enabled, onClick = performCurrentClick)
            clickBinding = binding
            click.bind(binding)
        }
    }

    private fun renderState() {
        val value = current ?: return
        updateRendererState(value)
        overlay.bind(value.active, value.filled, value.primaryText, value.secondaryText, flip.contentAlpha)
        applyTransform()
    }

    private fun updateRendererState(value: ReverbHomeBlobState) {
        val style = environment.audioBlobStyle
        val visuallyEnabled = value.captureEnabled || value.filled
        renderer.updateState(value.active, visuallyEnabled, value.saving, value.visualizerVisible,
            style, filled = value.filled, skipSilenceEnabled = value.skipSilenceEnabled)
        updateBlobDecoration(value.dimmed || !visuallyEnabled || value.saving)
    }

    private fun applyTransform() =
        applyBlobTransform(pressMotion.value, flip.depthScale, flip.degrees, flip.pivotFractionX)

    private fun updatePressed() {
        pressMotion.target(if (presses.isEmpty()) 1f else environment.audioBlobStyle.pressedScale,
            frames.durationScale)
        requestFrame()
    }
    private fun requestFrame() = frames.request()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (closed) return
        click.attached()
        current?.let { value ->
            if (attachedController !== value.controller) {
                value.controller.attach(renderer)
                attachedController = value.controller
            }
        }
        bindInput()
        click.focusChanged(isFocused)
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        setFocusVisible(false)
        click.detached()
        clickBinding = null // Reattach must restore the binding cleared by detached().
        attachedController?.detach(renderer)
        attachedController = null
        frames.cancel()
        super.onDetachedFromWindow()
    }

    override fun close() {
        if (closed) return
        closed = true
        setFocusVisible(false)
        click.detached()
        clickBinding = null
        attachedController?.detach(renderer)
        attachedController = null
        frames.cancel()
        presses.clear()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = click.touch(event)
    /** Parent Final-pass adapter may cancel a click after a source-consumed horizontal movement. */
    fun cancelGesture() = click.cancel()
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
        if (isNativeClickActivationKey(code)) click.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isNativeClickActivationKey(code)) click.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val platformHandled = super.onHoverEvent(event)
        return click.hover(event) || platformHandled
    }
    override fun onFocusChanged(focused: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(focused, direction, previous)
        if (!focused) setFocusVisible(false)
        click.focusChanged(focused)
    }
}

internal fun createHomeBlobReadout(context: Context, environment: ReverbViewEnvironment): NativeAudioBlobReadout {
    val density = environment.textDensity.density
    val ink = environment.palette.ink
    val icons = NativeAudioBlobIcons(
        filled = ReverbIconDrawable(ReverbIcon.Check, density, ink),
        active = ReverbIconDrawable(ReverbIcon.Pause, density, ink),
        ready = ReverbIconDrawable(ReverbIcon.Capture, density, ink),
    )
    return NativeAudioBlobReadout(context, icons, homeBlobDescriptions(context), environment.audioBlobStyle,
        environment.textDensity, environment.sharedFontResolver)
}

private fun homeBlobDescriptions(context: Context) = NativeAudioBlobDescriptions(
    filled = context.getString(R.string.buffer_filled),
    active = context.getString(R.string.tap_to_pause_buffer),
    ready = context.getString(R.string.tap_to_start_buffer),
)
