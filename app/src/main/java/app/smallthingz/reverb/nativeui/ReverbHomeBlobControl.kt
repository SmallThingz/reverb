package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
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
import app.smallthingz.reverb.AUDIO_BLOB_READOUT_INK
import app.smallthingz.reverb.AUDIO_BLOB_READOUT_SUPPORT
import app.smallthingz.reverb.audioBlobReadoutSupport
import app.smallthingz.reverb.R
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.sqrt
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
) : ViewGroup(context), AutoCloseable, NativeClickPhaseHost {
    private var environment = initialEnvironment
    private var current: ReverbHomeBlobState? = null
    private var flip = ReverbHomeFlipFrame()
    private var attachedController: AudioBlobController? = null
    private val renderer = AudioBlobView(context)
    private val overlay = BlobOverlay(context, initialEnvironment)
    private var focusVisible = false
    private val focusHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val focusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var focusCenterX = 0f
    private var focusCenterY = 0f
    private var focusRadius = 0f
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
        // Keyboard focus has a perimeter cue; hover retains the unmodified material.
        override fun stateEnter(token: NativeStateToken) {
            if (token.kind == NativeStateToken.Kind.FOCUS) setFocusVisible(true)
        }
        override fun stateExit(token: NativeStateToken) {
            if (token.kind == NativeStateToken.Kind.FOCUS) setFocusVisible(false)
        }
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
        renderer.onReadoutAppearanceChanged = overlay::setMaterialAppearance
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
        overlay.setEnvironment(value)
        renderState()
        requestLayout()
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
        overlay.bind(value, flip.contentAlpha)
        applyTransform()
    }

    private fun updateRendererState(value: ReverbHomeBlobState) {
        val colors = environment.palette
        val visuallyEnabled = value.captureEnabled || value.filled
        renderer.updateState(value.active, visuallyEnabled, value.saving, value.visualizerVisible,
            colors.primary, colors.tertiary,
            if (value.filled) colors.primaryContainer else colors.surfaceContainerHighest,
            value.skipSilenceEnabled)
        overlay.updateMaterialGeometry(renderer, colors.surface)
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

    private fun setFocusVisible(value: Boolean) {
        if (focusVisible == value) return
        focusVisible = value
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!focusVisible || !isFocused || !isEnabled || !isAttachedToWindow || closed || focusRadius <= 0f) return
        canvas.drawCircle(focusCenterX, focusCenterY, focusRadius, focusHalo)
        canvas.drawCircle(focusCenterX, focusCenterY, focusRadius, focusStroke)
    }

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
        val density = environment.textDensity.density
        focusCenterX = width * .5f
        focusCenterY = height * .5f
        focusRadius = (minOf(width, height) * .5f - 4f * density).coerceAtLeast(0f)
        focusHalo.strokeWidth = 4f * density
        focusStroke.strokeWidth = 2f * density
        focusHalo.color = environment.palette.surface
        focusStroke.color = environment.palette.onSurface
        renderer.layout(0, 0, width, height)
        val x = ((width - overlay.measuredWidth) / 2f).roundToInt()
        val y = ((height - overlay.measuredHeight) / 2f).roundToInt()
        overlay.layout(x, y, x + overlay.measuredWidth, y + overlay.measuredHeight)
        overlay.updateMaterialGeometry(renderer, environment.palette.surface)
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
        if (!focused) setFocusVisible(false)
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
    private val darken = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
    private val lighten = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        // Neutral support only affects startup geometry outside the readable material body.
        xfermode = darken
    }
    private var materialUsesDarkInk = false
    private var materialTransitioning = false
    private var materialNeedsSupport = true
    private var readoutSupportColor = AUDIO_BLOB_READOUT_SUPPORT
    private var readoutCenterX = 0f
    private var readoutCenterY = 0f
    private var readoutCoreRadius = 0f
    private var readoutOuterRadius = 0f
    private var readoutMaskWidth = 0
    private var readoutMaskHeight = 0
    private var readoutEdgeExtent = 0f
    private var radialReadoutShader: Shader? = null
    private var boundedReadoutShader: Shader? = null
    private var readoutLayoutRevision = 0
    private var projectedLayoutRevision = -1
    private var projectedWidth = 0
    private var projectedHeight = 0
    private var agslGradientMin = 0f
    private var agslGradientMax = 1f
    private var fallbackGradientMin = 0f
    private var fallbackGradientMax = 1f
    private var contentOpacity = 1f

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
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
        state?.let { bind(it, contentOpacity) }
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

    fun setMaterialAppearance(darkInk: Boolean, transitioning: Boolean, needsSupport: Boolean) {
        if (materialUsesDarkInk == darkInk && materialTransitioning == transitioning && materialNeedsSupport == needsSupport) return
        materialUsesDarkInk = darkInk
        materialTransitioning = transitioning
        materialNeedsSupport = needsSupport
        readoutPaint.xfermode = if (darkInk) lighten else darken
        val support = audioBlobReadoutSupport(darkInk, transitioning)
        if (readoutSupportColor != support) {
            readoutSupportColor = support
            rebuildReadoutShader()
        } else {
            readoutPaint.shader = if (materialTransitioning) radialReadoutShader else boundedReadoutShader
        }
        state?.let { bind(it, contentOpacity) }
    }

    fun bind(value: ReverbHomeBlobState, contentAlpha: Float) {
        state = value
        setContentOpacity(contentAlpha)
        val colors = environment.palette
        val tint = when { materialTransitioning -> if (materialUsesDarkInk) Color.BLACK else AUDIO_BLOB_READOUT_INK
            value.filled -> colors.onPrimaryContainer
            value.active -> if (materialUsesDarkInk) Color.BLACK else AUDIO_BLOB_READOUT_INK
            else -> colors.onSurfaceVariant }
        icon.bind(if (value.filled) ReverbIcon.Check else if (value.active) ReverbIcon.Pause else ReverbIcon.Capture,
            tint, if (value.filled) filledDescription else if (value.active) activeDescription else readyDescription)
        primary.visibility = if (value.primaryText != null) VISIBLE else GONE
        primary.setText(value.primaryText.orEmpty())
        primary.setTextColor(tint)
        secondary.visibility = if (value.secondaryText != null && (value.active || value.filled)) VISIBLE else GONE
        secondary.setText(value.secondaryText.orEmpty())
        secondary.setTextColor(tint)
        invalidate()
    }

    fun setContentOpacity(value: Float) {
        val opacity = value.coerceIn(0f, 1f)
        if (opacity == contentOpacity) return
        contentOpacity = opacity
        // Keep this node opaque: a translucent group isolates/clips the radial blend from the blob.
        // Fade the field and glyphs individually so the field always blends with the real material.
        readoutPaint.alpha = (opacity * 255f).roundToInt()
        icon.alpha = opacity
        primary.alpha = opacity
        secondary.alpha = opacity
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!materialNeedsSupport || (state?.active != true && !materialTransitioning) || (state?.filled == true && !materialTransitioning) || readoutOuterRadius <= 0f || readoutPaint.alpha == 0) return
        if (materialTransitioning) {
            // A broad, diffuse correction follows the changing material without a rectangular plate.
            canvas.drawCircle(readoutCenterX, readoutCenterY, readoutOuterRadius, readoutPaint)
        } else {
            // Settled energetic folds need support only near the glyphs, not in empty concavities.
            canvas.drawRect(-readoutEdgeExtent, -readoutEdgeExtent,
                width + readoutEdgeExtent, height + readoutEdgeExtent, readoutPaint)
        }
    }

    private fun readoutCornerDistanceSquared(centerX: Float, centerY: Float): Float {
        var maximum = 0f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val dx = max(abs(child.left - centerX), abs(child.right - centerX))
            val dy = max(abs(child.top - centerY), abs(child.bottom - centerY))
            maximum = max(maximum, dx * dx + dy * dy)
        }
        return maximum
    }

    fun readoutFootprintRadius(): Float = readoutCoreRadius + abs(readoutCenterY - height * .5f)

    fun updateMaterialGeometry(renderer: AudioBlobView, background: Int) {
        val canvasWidth = renderer.width
        val canvasHeight = renderer.height
        if (readoutLayoutRevision == 0 || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            renderer.updateReadoutGeometry(0f, background)
            return
        }
        if (projectedLayoutRevision != readoutLayoutRevision || projectedWidth != canvasWidth || projectedHeight != canvasHeight) {
            val minSize = minOf(canvasWidth, canvasHeight).toFloat()
            val w = canvasWidth.toFloat()
            val h = canvasHeight.toFloat()
            val diagonalSquared = w * w + h * h
            val margin = 2f * environment.textDensity.density
            var aMin = Float.POSITIVE_INFINITY
            var aMax = Float.NEGATIVE_INFINITY
            var fMin = Float.POSITIVE_INFINITY
            var fMax = Float.NEGATIVE_INFINITY
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val left = child.left - width * .5f - margin
                val right = child.right - width * .5f + margin
                val top = child.top - height * .5f - margin
                val bottom = child.bottom - height * .5f + margin
                aMin = minOf(aMin, .46f + (.9f * left - .55f * bottom) / minSize)
                aMax = maxOf(aMax, .46f + (.9f * right - .55f * top) / minSize)
                fMin = minOf(fMin, .5f + (left * w + top * h) / diagonalSquared)
                fMax = maxOf(fMax, .5f + (right * w + bottom * h) / diagonalSquared)
            }
            agslGradientMin = if (aMin.isFinite()) aMin.coerceIn(0f, 1f) else 0f
            agslGradientMax = if (aMax.isFinite()) aMax.coerceIn(0f, 1f) else 1f
            fallbackGradientMin = if (fMin.isFinite()) fMin.coerceIn(0f, 1f) else 0f
            fallbackGradientMax = if (fMax.isFinite()) fMax.coerceIn(0f, 1f) else 1f
            projectedLayoutRevision = readoutLayoutRevision
            projectedWidth = canvasWidth
            projectedHeight = canvasHeight
        }
        renderer.updateReadoutGeometry(readoutFootprintRadius(), background,
            agslGradientMin, agslGradientMax, fallbackGradientMin, fallbackGradientMax)
    }

    private fun updateReadoutGeometry() {
        readoutLayoutRevision++
        val centerX = width * .5f
        var lower = 0f
        var upper = height.toFloat()
        // Minimize the enclosing circle using the actual narrow icon and wider text bounds.
        // The final radius is measured again, so optimization precision cannot reduce coverage.
        repeat(24) {
            val first = lower + (upper - lower) / 3f
            val second = upper - (upper - lower) / 3f
            if (readoutCornerDistanceSquared(centerX, first) < readoutCornerDistanceSquared(centerX, second))
                upper = second else lower = first
        }
        val centerY = (lower + upper) * .5f
        val core = sqrt(readoutCornerDistanceSquared(centerX, centerY)) + 2f * environment.textDensity.density
        val outer = core + 24f * environment.textDensity.density
        if (centerX == readoutCenterX && centerY == readoutCenterY &&
            core == readoutCoreRadius && outer == readoutOuterRadius &&
            width == readoutMaskWidth && height == readoutMaskHeight) return
        readoutCenterX = centerX
        readoutCenterY = centerY
        readoutCoreRadius = core
        readoutOuterRadius = outer
        readoutMaskWidth = width
        readoutMaskHeight = height
        rebuildReadoutShader()
    }

    private fun rebuildReadoutShader() {
        if (readoutOuterRadius <= 0f) return
        val coreFraction = readoutCoreRadius / readoutOuterRadius
        val colors = IntArray(10)
        val stops = FloatArray(10)
        colors[0] = readoutSupportColor
        for (index in 1..9) {
            val t = (index - 1) / 8f
            val alpha = ((1f - t * t * (3f - 2f * t)) * 255f).roundToInt()
            colors[index] = (readoutSupportColor and 0x00ffffff) or (alpha shl 24)
            stops[index] = coreFraction + (1f - coreFraction) * t
        }
        val radial = RadialGradient(readoutCenterX, readoutCenterY, readoutOuterRadius,
            colors, stops, Shader.TileMode.CLAMP)
        // The enclosing circle contains empty space far beyond the glyphs. Confine its correction
        // to the measured readout plus a smooth fringe, so energetic concavities get no gray halo.
        val margin = 2f * environment.textDensity.density
        val feather = 8f * environment.textDensity.density
        readoutEdgeExtent = margin + feather
        val maskColors = IntArray(18)
        val horizontalStops = FloatArray(18)
        val verticalStops = FloatArray(18)
        val horizontalFringe = feather / (readoutMaskWidth + 2f * readoutEdgeExtent)
        val verticalFringe = feather / (readoutMaskHeight + 2f * readoutEdgeExtent)
        for (index in 0..8) {
            val t = index / 8f
            val alpha = (t * t * (3f - 2f * t) * 255f).roundToInt()
            maskColors[index] = (alpha shl 24) or 0x00ffffff
            maskColors[17 - index] = maskColors[index]
            horizontalStops[index] = t * horizontalFringe
            horizontalStops[17 - index] = 1f - t * horizontalFringe
            verticalStops[index] = t * verticalFringe
            verticalStops[17 - index] = 1f - t * verticalFringe
        }
        val horizontal = LinearGradient(-readoutEdgeExtent, 0f, readoutMaskWidth + readoutEdgeExtent, 0f,
            maskColors, horizontalStops, Shader.TileMode.CLAMP)
        val vertical = LinearGradient(0f, -readoutEdgeExtent, 0f, readoutMaskHeight + readoutEdgeExtent,
            maskColors, verticalStops, Shader.TileMode.CLAMP)
        radialReadoutShader = radial
        boundedReadoutShader = ComposeShader(ComposeShader(radial, horizontal, PorterDuff.Mode.DST_IN),
            vertical, PorterDuff.Mode.DST_IN)
        readoutPaint.shader = if (materialTransitioning) radialReadoutShader else boundedReadoutShader
        invalidate()
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
        updateReadoutGeometry()
    }
}

private class HomeBlobIcon(context: Context, environment: ReverbViewEnvironment) : View(context) {
    private val checkIcon = ReverbIconDrawable(ReverbIcon.Check, environment.textDensity.density, environment.palette.ink)
    private val pauseIcon = ReverbIconDrawable(ReverbIcon.Pause, environment.textDensity.density, environment.palette.ink)
    private val captureIcon = ReverbIconDrawable(ReverbIcon.Capture, environment.textDensity.density, environment.palette.ink)
    private var drawable = captureIcon
    private var boundIcon: ReverbIcon? = null
    private var boundTint = 0
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun bind(icon: ReverbIcon, color: Int, description: String) {
        val appearanceChanged = boundIcon != icon || boundTint != color
        if (appearanceChanged) {
            boundIcon = icon
            boundTint = color
            drawable = checkNotNull(when {
                icon === ReverbIcon.Check -> checkIcon
                icon === ReverbIcon.Pause -> pauseIcon
                icon === ReverbIcon.Capture -> captureIcon
                else -> null
            })
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

