package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.unit.sp
import app.smallthingz.reverb.AudioBlobController
import app.smallthingz.reverb.AudioBlobView
import app.smallthingz.reverb.R
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

/** Already resolved BufferBlobPage facts; no service reference or recording action is owned here. */
internal data class ReverbHomeBlobState(
    val controller: AudioBlobController,
    val active: Boolean,
    val saving: Boolean,
    val captureEnabled: Boolean,
    val clickableEnabled: Boolean,
    val filled: Boolean,
    val dimmed: Boolean,
    val primaryText: String?,
    val secondaryText: String?,
    val visualizerVisible: Boolean,
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
) : ViewGroup(context), AutoCloseable, NativeClickPhaseHost {
    private var environment = initialEnvironment
    private var current: ReverbHomeBlobState? = null
    private var flip = ReverbHomeFlipFrame()
    private var attachedController: AudioBlobController? = null
    private val renderer = AudioBlobView(context)
    private val overlay = BlobOverlay(context, initialEnvironment)
    private val ownerKey = Any()
    private val performCurrentClick: () -> Unit = { performClick() }
    private var clickBinding: NativeClickBinding? = null
    private val presses = LinkedHashSet<NativePressToken>()
    private val pressMotion = LibraryTweenValue(1f, Float.VectorConverter, tween<Float>(100),
        { AnimationVector1D(0f) })
    private var closed = false
    private val frames = HomeFrameLoop(this, animationScope, { !closed && pressMotion.running }) { nanos, scale ->
        pressMotion.frame(nanos, scale)
        applyTransform()
    }
    private val click = ReverbClickController(this, inputEnvironment, object : NativeClickIndication {
        override fun press(token: NativePressToken) { presses.add(token); updatePressed() }
        override fun release(token: NativePressToken) { presses.remove(token); updatePressed() }
        override fun cancel(token: NativePressToken) { presses.remove(token); updatePressed() }
        // Source indication=null; hover/focus do not create a ripple or state layer.
        override fun stateEnter(token: NativeStateToken) = Unit
        override fun stateExit(token: NativeStateToken) = Unit
    })
    override val nativeClickEndpoint = ReverbClickPhaseEndpoint(click)

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(renderer)
        addView(overlay)
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
        overlay.alpha = frame.contentAlpha.coerceIn(0f, 1f)
        applyTransform()
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        overlay.setEnvironment(value)
        renderState()
        requestLayout()
    }

    private fun bindInput() {
        val enabled = current?.let { it.clickableEnabled && !it.saving } == true
        isEnabled = enabled
        isFocusable = enabled
        if (clickBinding?.enabled != enabled) {
            val binding = NativeClickBinding(ownerKey, enabled, onClick = performCurrentClick)
            clickBinding = binding
            click.bind(binding)
        }
    }

    private fun renderState() {
        val value = current ?: return
        updateRendererState(value)
        overlay.bind(value, flip.contentAlpha)
        applyTransform()
    }

    private fun updateRendererState(value: ReverbHomeBlobState) {
        val colors = environment.palette
        val visuallyEnabled = value.captureEnabled || value.filled
        renderer.updateState(value.active, visuallyEnabled, value.saving, value.visualizerVisible,
            colors.primary, colors.tertiary,
            if (value.filled) colors.primaryContainer else colors.surfaceContainerHighest)
        alpha = if (!value.dimmed && visuallyEnabled && !value.saving) 1f else .56f
    }

    private fun applyTransform() {
        scaleX = pressMotion.value
        scaleY = pressMotion.value * flip.depthScale
        rotationY = flip.degrees
        pivotX = width * flip.pivotFractionX
        pivotY = height * .5f
        // ViewLayer.cameraDistancePx multiplies by densityDpi before setting View.cameraDistance.
        cameraDistance = 24f * environment.textDensity.density * resources.displayMetrics.densityDpi
    }

    private fun updatePressed() {
        pressMotion.target(if (presses.isEmpty()) 1f else .96f, frames.durationScale)
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
        requestFrame()
    }

    override fun onDetachedFromWindow() {
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
        click.detached()
        clickBinding = null
        attachedController?.detach(renderer)
        attachedController = null
        frames.cancel()
        presses.clear()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        renderer.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        overlay.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        renderer.layout(0, 0, width, height)
        val x = ((width - overlay.measuredWidth) / 2f).roundToInt()
        val y = ((height - overlay.measuredHeight) / 2f).roundToInt()
        overlay.layout(x, y, x + overlay.measuredWidth, y + overlay.measuredHeight)
        applyTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = click.touch(event)
    /** Parent Final-pass adapter may cancel a click after a source-consumed horizontal movement. */
    fun cancelGesture() = click.cancel()
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
        if (isHomeActivationKey(code)) click.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isHomeActivationKey(code)) click.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val platformHandled = super.onHoverEvent(event)
        return click.hover(event) || platformHandled
    }
    override fun onFocusChanged(focused: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(focused, direction, previous)
        click.focusChanged(focused)
    }
}

private val blobPrimaryTextStyle = ReverbParagraphStyles[ReverbTextRole.TITLE_LARGE].copy(
    fontSize = 18.sp, lineHeight = 22.sp,
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
private val blobSecondaryTextStyle = ReverbParagraphStyles[ReverbTextRole.LABEL_MEDIUM].copy(
    fontSize = 14.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)

/** Keeps source conditional Spacer/Text children and their measurement order, not joined text. */
private class BlobOverlay(context: Context, initialEnvironment: ReverbViewEnvironment) : ViewGroup(context) {
    private var environment = initialEnvironment
    private var state: ReverbHomeBlobState? = null
    private val icon = HomeBlobIcon(context, initialEnvironment)
    private var filledDescription = context.getString(R.string.buffer_filled)
    private var activeDescription = context.getString(R.string.tap_to_pause_buffer)
    private var readyDescription = context.getString(R.string.tap_to_start_buffer)
    private val primary = initialEnvironment.paragraph(context, "", ReverbTextRole.TITLE_LARGE)
    private val secondary = initialEnvironment.paragraph(context, "", ReverbTextRole.LABEL_MEDIUM)

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon); addView(primary); addView(secondary)
        primary.setTextLayout(maxLines = 1)
        secondary.setTextLayout(maxLines = 1)
        bindTypography()
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        filledDescription = context.getString(R.string.buffer_filled)
        activeDescription = context.getString(R.string.tap_to_pause_buffer)
        readyDescription = context.getString(R.string.tap_to_start_buffer)
        bindTypography()
        state?.let { bind(it, alpha) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        filledDescription = context.getString(R.string.buffer_filled)
        activeDescription = context.getString(R.string.tap_to_pause_buffer)
        readyDescription = context.getString(R.string.tap_to_start_buffer)
    }

    private fun bindTypography() {
        primary.setTypography(blobPrimaryTextStyle, environment.textDensity, environment.sharedFontResolver)
        secondary.setTypography(blobSecondaryTextStyle, environment.textDensity, environment.sharedFontResolver)
        requestLayout()
    }

    fun bind(value: ReverbHomeBlobState, contentAlpha: Float) {
        state = value
        alpha = contentAlpha.coerceIn(0f, 1f)
        val colors = environment.palette
        val tint = when { value.filled -> colors.onPrimaryContainer
            value.active -> colors.onPrimary
            else -> colors.onSurfaceVariant }
        icon.bind(if (value.filled) ReverbIcon.Check else if (value.active) ReverbIcon.Pause else ReverbIcon.Capture,
            tint, if (value.filled) filledDescription else if (value.active) activeDescription else readyDescription)
        primary.visibility = if (value.primaryText != null) VISIBLE else GONE
        primary.setText(value.primaryText.orEmpty())
        primary.setTextColor(tint)
        secondary.visibility = if (value.secondaryText != null && (value.active || value.filled)) VISIBLE else GONE
        secondary.setText(value.secondaryText.orEmpty())
        secondary.setTextColor(reverbColorWithAlpha(tint, .76f))
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(w: Int, h: Int) {
        val maxWidth = MeasureSpec.getSize(w)
        val maxHeight = MeasureSpec.getSize(h)
        val iconWidth = dp(38).coerceAtMost(maxWidth)
        val iconHeight = dp(38).coerceAtMost(maxHeight)
        icon.measure(MeasureSpec.makeMeasureSpec(iconWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(iconHeight, MeasureSpec.EXACTLY))
        var usedHeight = icon.measuredHeight
        var usedWidth = icon.measuredWidth
        for (index in 0..1) {
            val label = if (index == 0) primary else secondary
            if (label.visibility == GONE) continue
            val gap = if (index == 0) 10 else 2
            usedHeight += dp(gap).coerceAtMost((maxHeight - usedHeight).coerceAtLeast(0))
            label.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec((maxHeight - usedHeight).coerceAtLeast(0), MeasureSpec.AT_MOST))
            usedHeight += label.measuredHeight
            usedWidth = max(usedWidth, label.measuredWidth)
        }
        setMeasuredDimension(resolveSize(usedWidth, w), resolveSize(usedHeight, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        val iconX = ((width - icon.measuredWidth) / 2f).roundToInt()
        icon.layout(iconX, y, iconX + icon.measuredWidth, y + icon.measuredHeight)
        y += icon.measuredHeight
        for (index in 0..1) {
            val label = if (index == 0) primary else secondary
            if (label.visibility == GONE) continue
            val gap = if (index == 0) 10 else 2
            y += dp(gap).coerceAtMost((height - y).coerceAtLeast(0))
            val x = ((width - label.measuredWidth) / 2f).roundToInt()
            label.layout(x, y, x + label.measuredWidth, y + label.measuredHeight)
            y += label.measuredHeight
        }
    }
}

private class HomeBlobIcon(context: Context, environment: ReverbViewEnvironment) : View(context) {
    private val icons = mapOf(
        ReverbIcon.Check to ReverbIconDrawable(ReverbIcon.Check, environment.textDensity.density, environment.palette.ink),
        ReverbIcon.Pause to ReverbIconDrawable(ReverbIcon.Pause, environment.textDensity.density, environment.palette.ink),
        ReverbIcon.Capture to ReverbIconDrawable(ReverbIcon.Capture, environment.textDensity.density, environment.palette.ink),
    )
    private var drawable = checkNotNull(icons[ReverbIcon.Capture])
    private var boundIcon: ReverbIcon? = null
    private var boundTint = 0
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun bind(icon: ReverbIcon, color: Int, description: String) {
        val appearanceChanged = boundIcon != icon || boundTint != color
        if (appearanceChanged) {
            boundIcon = icon
            boundTint = color
            drawable = checkNotNull(icons[icon])
            drawable.setTint(color)
            drawable.setBounds(0, 0, width, height)
        }
        if (contentDescription != description) contentDescription = description
        if (appearanceChanged) invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        drawable.setBounds(0, 0, w, h)
    }
    override fun onDraw(canvas: Canvas) { drawable.draw(canvas) }
}

