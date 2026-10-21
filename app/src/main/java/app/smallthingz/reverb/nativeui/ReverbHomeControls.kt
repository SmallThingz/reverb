package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.smallthingz.reverb.R
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.isBufferActivelyRecording
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

internal data class ReverbHomeControlsState(
    val selectedBuffer: BufferSlot,
    val activeBuffer: BufferSlot?,
    val listening: Boolean,
    val oneShotEnabled: Boolean,
    val oneShotFull: Boolean,
    val loopingEnabled: Boolean,
    val serviceReady: Boolean,
    val saving: Boolean,
    val hasHistory: Boolean,
    val selectedRecording: Boolean,
)

internal data class ReverbHomeActions(
    val selectBuffer: (BufferSlot) -> Unit,
    val activateBuffer: (BufferSlot) -> Unit,
    val openBufferSettings: (BufferSlot) -> Unit,
    val exportFull: (BufferSlot) -> Unit,
    val exportCustom: (BufferSlot) -> Unit,
    val clearBuffer: (BufferSlot) -> Unit,
    val openLibrary: () -> Unit,
)

/** CaptureControlCluster + BufferSelector. The Home owner supplies the currently rendered slot. */
internal class ReverbHomeControls(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    nativeInputRoot: () -> View?,
    animationScope: CoroutineScope,
) : ReverbHomeControlSurface(context) {
    private var environment = initialEnvironment
    private val oneShot = HomeBufferSegment(context, initialEnvironment, nativeInputRoot, animationScope)
    private val looping = HomeBufferSegment(context, initialEnvironment, nativeInputRoot, animationScope)
    private val buttons = listOf(
        HomeAction(context, initialEnvironment, nativeInputRoot, ReverbIcon.Save, R.string.record_all_memory),
        HomeAction(context, initialEnvironment, nativeInputRoot, ReverbIcon.ExportRange, R.string.export_range_title),
        HomeAction(context, initialEnvironment, nativeInputRoot, ReverbIcon.Delete, R.string.clear_buffer),
        HomeAction(context, initialEnvironment, nativeInputRoot, ReverbIcon.Library, R.string.files_tab),
    )

    // Reverse source z order: lower action Row was placed after the selector Row. Retain these
    // node identities across bindings; only actual DOWN runs nearest-target/shape arbitration.
    private val pointerChildren by lazy {
        (buttons.asReversed() + listOf(looping, oneShot)).map { it.hitLayout(inputCoordinates) }
    }
    override fun childHitLayouts(): List<NativeViewPointerHitLayout> = pointerChildren

    init {
        setSurfaceEnvironment(initialEnvironment)
        content.addView(oneShot); content.addView(looping)
        buttons.forEach(content::addView)
    }

    fun bind(state: ReverbHomeControlsState, flip: ReverbHomeFlipFrame, actions: ReverbHomeActions) {
        val slot = state.selectedBuffer
        oneShot.bind(context.getString(R.string.buffer_one_shot),
            if (state.oneShotFull) ReverbIcon.Check else ReverbIcon.OneShot,
            slot == BufferSlot.ONE_SHOT,
            isBufferActivelyRecording(BufferSlot.ONE_SHOT, state.listening, state.activeBuffer),
            state.oneShotEnabled && !state.oneShotFull, state.oneShotFull) {
            actions.selectBuffer(BufferSlot.ONE_SHOT)
        }
        looping.bind(context.getString(R.string.buffer_loop), ReverbIcon.Looping,
            slot == BufferSlot.LOOPING,
            isBufferActivelyRecording(BufferSlot.LOOPING, state.listening, state.activeBuffer),
            state.loopingEnabled, false) { actions.selectBuffer(BufferSlot.LOOPING) }
        val canExport = state.serviceReady && !state.saving && state.hasHistory
        buttons[0].bind(canExport, false, flip) { actions.exportFull(slot) }
        buttons[1].bind(canExport, false, flip) { actions.exportCustom(slot) }
        buttons[2].bind(canExport && !state.selectedRecording, true, flip) { actions.clearBuffer(slot) }
        // Library is the one icon not flipped with the selected buffer.
        buttons[3].bind(!state.saving, false, ReverbHomeFlipFrame(), actions.openLibrary)
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        setSurfaceEnvironment(value)
        oneShot.setEnvironment(value); looping.setEnvironment(value)
        buttons.forEach { it.setEnvironment(value) }
        requestLayout()
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(w: Int, h: Int) {
        val width = resolveSize(dp(256), w)
        val height = resolveSize(dp(112), h)
        setMeasuredDimension(width, height)
        content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        val selectorWidth = min(dp(242), width)
        val selectorHeight = min(dp(54), height)
        val horizontalPadding = dp(6) * 2
        val maxSegmentHeight = (selectorHeight - dp(6) * 2).coerceAtLeast(0)
        var remaining = (selectorWidth - horizontalPadding).coerceAtLeast(0)
        for (segment in listOf(oneShot, looping)) {
            val segmentWidth = min(dp(112), remaining)
            segment.measure(MeasureSpec.makeMeasureSpec(segmentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxSegmentHeight, MeasureSpec.AT_MOST))
            remaining = (remaining - segment.measuredWidth - min(dp(6), remaining - segment.measuredWidth))
                .coerceAtLeast(0)
        }
        val actionWidth = min(dp(256), width)
        val actionHeight = min(dp(70), height)
        var actionRemaining = (actionWidth - dp(8) * 2).coerceAtLeast(0)
        val actionMaxHeight = (actionHeight - dp(8) * 2).coerceAtLeast(0)
        for (button in buttons) {
            val childWidth = min(dp(54), actionRemaining)
            val childHeight = min(dp(54), actionMaxHeight)
            button.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
            actionRemaining = (actionRemaining - childWidth - min(dp(8), actionRemaining - childWidth))
                .coerceAtLeast(0)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, width, height)
        val selectorWidth = min(dp(242), width)
        val selectorHeight = min(dp(54), height)
        val selectorLeft = ((width - selectorWidth) / 2f).roundToInt()
        val selectorInnerHeight = (selectorHeight - dp(6) * 2).coerceAtLeast(0)
        var logicalX = dp(6)
        for (segment in listOf(oneShot, looping)) {
            val x = selectorLeft + if (layoutDirection == LAYOUT_DIRECTION_RTL)
                selectorWidth - logicalX - segment.measuredWidth else logicalX
            val y = dp(6) + ((selectorInnerHeight - segment.measuredHeight) / 2f).roundToInt()
            segment.layout(x, y, x + segment.measuredWidth, y + segment.measuredHeight)
            logicalX += segment.measuredWidth + dp(6)
        }
        val actionWidth = min(dp(256), width)
        val actionHeight = min(dp(70), height)
        val actionLeft = ((width - actionWidth) / 2f).roundToInt()
        val actionTop = height - actionHeight
        val innerHeight = (actionHeight - dp(8) * 2).coerceAtLeast(0)
        logicalX = dp(8)
        for (button in buttons) {
            val x = actionLeft + if (layoutDirection == LAYOUT_DIRECTION_RTL)
                actionWidth - logicalX - button.measuredWidth else logicalX
            val y = actionTop + dp(8) + ((innerHeight - button.measuredHeight) / 2f).roundToInt()
            button.layout(x, y, x + button.measuredWidth, y + button.measuredHeight)
            logicalX += button.measuredWidth + dp(8)
        }
    }
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection); requestLayout()
    }
}

/** Shared exact click/ripple ownership on an existing control; no themed platform Button. */
internal abstract class HomeClickable(
    context: Context,
    protected var currentEnvironment: ReverbViewEnvironment,
    private val nativeRoot: () -> View?,
    private val radiusDp: Float?,
) : ViewGroup(context), NativeClickPhaseHost {
    protected var action: () -> Unit = {}
    protected val indication = ReverbRowIndication.install(this, currentEnvironment.palette.ink,
        ValueAnimator::getDurationScale)
    private val key = Any()
    private val input = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot get() = nativeRoot()
        override val minimumTouchWidthPx get() = 48f * currentEnvironment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun interestedParent(event: MotionEvent) = false // Cluster is outside swipe Box.
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
        override fun admitDown(event: MotionEvent): Boolean = reverbGroupGeometry(
            width.toFloat(), height.toFloat(), currentEnvironment.textDensity.density,
            radiusDp ?: min(width, height) / (2f * currentEnvironment.textDensity.density),
        ).acceptsPointer(event.x, event.y,
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
    }, ReverbRowIndicationSink(indication) { x, y, target -> target.set(x, y) })
    override val nativeClickEndpoint = ReverbClickPhaseEndpoint(input)

    fun hitLayout(coordinates: NativeWindowPointerCoordinates): NativeViewPointerHitLayout {
        val node = NativeClickPhaseNode(this, nativeClickEndpoint) { false }
        return object : NativeViewPointerHitLayout {
            override val placed get() = isAttachedToWindow && visibility == VISIBLE
            override val size get() = Size(width.toFloat(), height.toFloat())
            override val minimumTouchTarget get() = Size(48f * currentEnvironment.textDensity.density,
                48f * currentEnvironment.textDensity.density)
            override val nodes = listOf(node)
            override val childrenInHitOrder = emptyList<NativeViewPointerHitLayout>()
            override fun screenToLocal(screen: Offset) = coordinates.coordinates(this@HomeClickable).local(screen)
            override fun withinLayerBounds(local: Offset) = reverbGroupGeometry(
                width.toFloat(), height.toFloat(), currentEnvironment.textDensity.density,
                radiusDp ?: min(width, height) / (2f * currentEnvironment.textDensity.density),
            ).contains(local.x, local.y)
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = radiusDp?.let { it * currentEnvironment.textDensity.density }
                    ?: (min(width, height) / 2f)
                outline.setRoundRect(0, 0, width, height, min(radius, min(width, height) / 2f))
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    protected fun bindClick(enabled: Boolean, next: () -> Unit) {
        action = next
        isEnabled = enabled
        isFocusable = enabled
        input.bind(NativeClickBinding(key, enabled, onClick = { performClick() }))
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); input.attached(); bindClick(isEnabled, action)
    }
    override fun onDetachedFromWindow() {
        input.detached(); super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        indication.onMeasured(w, h, currentEnvironment.textDensity.density)
        invalidateOutline()
    }
    override fun onTouchEvent(event: MotionEvent) = input.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
        if (isHomeActivationKey(code)) input.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isHomeActivationKey(code)) input.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val platform = super.onHoverEvent(event)
        return input.hover(event) || platform
    }
    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous); input.focusChanged(gain)
    }
}

internal class HomeBufferSegment(context: Context, currentEnvironment: ReverbViewEnvironment,
    root: () -> View?, animationScope: CoroutineScope) : HomeClickable(context, currentEnvironment, root, 16f) {
    private val glyph = HomeGlyph(context, currentEnvironment, ReverbIcon.OneShot)
    private val label = currentEnvironment.paragraph(context, "", ReverbTextRole.LABEL_LARGE)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundMotion = colorMotion(Color.Transparent)
    private val foregroundMotion = colorMotion(Color(currentEnvironment.palette.muted))
    private var initialized = false
    private var selection = false
    private var recording = false
    private var bufferEnabled = true
    private var filled = false
    private val frames = HomeFrameLoop(this, animationScope,
        { backgroundMotion.running || foregroundMotion.running }) { time, scale ->
        backgroundMotion.frame(time, scale); foregroundMotion.frame(time, scale)
        applyColors()
    }
    init {
        setWillNotDraw(false)
        addView(glyph); addView(label)
        label.setTextLayout(maxLines = 1)
    }
    fun bind(text: String, icon: ReverbIcon, selected: Boolean, recording: Boolean,
        enabled: Boolean, filled: Boolean, interactionEnabled: Boolean = true, next: () -> Unit) {
        selection = selected; this.recording = recording; bufferEnabled = enabled; this.filled = filled
        isSelected = selected
        glyph.setIcon(icon)
        label.setText(text)
        bindClick(interactionEnabled, next) // Disabled/full buffers remain selectable; Home keeps the true default.
        updateTargets()
    }
    fun setEnvironment(value: ReverbViewEnvironment) {
        currentEnvironment = value
        value.bindParagraphStyle(label, ReverbTextRole.LABEL_LARGE)
        glyph.setEnvironment(value)
        updateTargets()
        invalidateOutline(); requestLayout()
    }
    private fun updateTargets() {
        val p = currentEnvironment.palette
        val background = Color(when {
            selection && recording -> p.primary
            filled && selection -> p.tertiary
            selection -> reverbColorWithAlpha(p.primaryContainer, .74f)
            else -> android.graphics.Color.TRANSPARENT
        })
        val foreground = Color(when {
            selection && recording -> p.onPrimary
            filled && selection -> p.onTertiary
            selection && !bufferEnabled -> reverbColorWithAlpha(p.onPrimaryContainer, .62f)
            selection -> p.onPrimaryContainer
            else -> reverbColorWithAlpha(p.muted, if (bufferEnabled) .52f else .30f)
        })
        if (!initialized) {
            initialized = true; backgroundMotion.snap(background); foregroundMotion.snap(foreground)
        } else {
            val scale = frames.durationScale
            backgroundMotion.target(background, scale); foregroundMotion.target(foreground, scale)
        }
        // Selectable is outside Surface's content provider: inherited cluster onSurface is the ripple.
        indication.updateColor(p.ink)
        applyColors(); requestFrame()
    }
    private fun applyColors() {
        fill.color = backgroundMotion.value.toArgb()
        val tint = foregroundMotion.value.toArgb()
        glyph.setTint(tint); label.setTextColor(tint); invalidate()
    }
    private fun requestFrame() = frames.request()
    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFrame() }
    override fun onDetachedFromWindow() {
        frames.cancel()
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        val radius = min(16f * currentEnvironment.textDensity.density, min(width, height) / 2f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fill)
    }
    private fun dp(value: Int) = (value * currentEnvironment.textDensity.density).roundToInt()
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val maxHeight = MeasureSpec.getSize(h)
        val innerWidth = (width - dp(12) * 2).coerceAtLeast(0)
        val innerHeight = (maxHeight - dp(11) * 2).coerceAtLeast(0)
        glyph.measure(MeasureSpec.makeMeasureSpec(min(dp(18), innerWidth), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(min(dp(18), innerHeight), MeasureSpec.EXACTLY))
        val gap = min(dp(7), (innerWidth - glyph.measuredWidth).coerceAtLeast(0))
        label.measure(MeasureSpec.makeMeasureSpec((innerWidth - glyph.measuredWidth - gap).coerceAtLeast(0),
            MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(innerHeight, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, resolveSize(max(glyph.measuredHeight, label.measuredHeight) + dp(11) * 2, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = (width - dp(12) * 2).coerceAtLeast(0)
        val gap = min(dp(7), (inner - glyph.measuredWidth).coerceAtLeast(0))
        val used = glyph.measuredWidth + gap + label.measuredWidth
        fun place(child: View, beforeChildSize: Int) {
            val x = dp(12) + nativeCenteredRowChildOffset(inner, used, beforeChildSize)
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        // Source Arrangement.Center reverses children, not already-rounded LTR coordinates.
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            place(label, 0); place(glyph, label.measuredWidth + gap)
        } else {
            place(glyph, 0); place(label, glyph.measuredWidth + gap)
        }
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.TabWidget"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.isSelected = selection
    }
    private fun colorMotion(color: Color) = LibraryTweenValue(color, (Color.VectorConverter)(color.colorSpace),
        tween<Color>(160), { AnimationVector4D(0f, 0f, 0f, 0f) })
}

private class HomeAction(context: Context, currentEnvironment: ReverbViewEnvironment,
    root: () -> View?, icon: ReverbIcon, description: Int) : HomeClickable(context, currentEnvironment, root, null) {
    private val glyph = HomeGlyph(context, currentEnvironment, icon)
    private var flip = ReverbHomeFlipFrame()
    private var destructive = false
    init {
        glyph.contentDescription = context.getString(description)
        glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(glyph)
    }
    fun bind(enabled: Boolean, destructive: Boolean, frame: ReverbHomeFlipFrame, next: () -> Unit) {
        this.destructive = destructive
        flip = frame
        bindClick(enabled, next)
        apply()
    }
    fun setEnvironment(value: ReverbViewEnvironment) {
        currentEnvironment = value; glyph.setEnvironment(value); apply(); requestLayout()
    }
    private fun apply() {
        val p = currentEnvironment.palette
        glyph.setTint(if (!isEnabled) reverbColorWithAlpha(p.muted, .28f)
            else if (destructive) p.error else p.ink)
        // IconButton's inherited LocalContentColor controls the ripple, not the explicit glyph tint.
        indication.updateColor(if (isEnabled) p.ink else reverbColorWithAlpha(p.ink, .38f))
        glyph.rotationY = flip.degrees
        glyph.pivotX = glyph.width * flip.pivotFractionX
        glyph.pivotY = glyph.height * .5f
        glyph.scaleY = flip.depthScale
        glyph.cameraDistance = 24f * currentEnvironment.textDensity.density * resources.displayMetrics.densityDpi
    }
    private fun dp(value: Int) = (value * currentEnvironment.textDensity.density).roundToInt()
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        glyph.measure(MeasureSpec.makeMeasureSpec(min(dp(25), width), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(min(dp(25), height), MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = ((width - glyph.measuredWidth) / 2f).roundToInt()
        val y = ((height - glyph.measuredHeight) / 2f).roundToInt()
        glyph.layout(x, y, x + glyph.measuredWidth, y + glyph.measuredHeight)
        apply()
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

private class HomeGlyph(context: Context, private var environment: ReverbViewEnvironment,
    private var icon: ReverbIcon) : View(context) {
    private var tint = environment.palette.ink
    private var drawable = ReverbIconDrawable(icon, environment.textDensity.density, tint)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setEnvironment(value: ReverbViewEnvironment) {
        if (value.textDensity.density != environment.textDensity.density) {
            drawable = ReverbIconDrawable(icon, value.textDensity.density, tint)
        }
        environment = value
        drawable.setBounds(0, 0, width, height); drawable.layoutDirection = layoutDirection
        invalidate()
    }
    fun setIcon(value: ReverbIcon) {
        if (icon != value) { icon = value; drawable = ReverbIconDrawable(value, environment.textDensity.density, tint) }
        drawable.setBounds(0, 0, width, height); drawable.layoutDirection = layoutDirection
        invalidate()
    }
    fun setTint(value: Int) { tint = value; drawable.setTint(value); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { drawable.setBounds(0, 0, w, h) }
    override fun onRtlPropertiesChanged(direction: Int) {
        super.onRtlPropertiesChanged(direction); drawable.layoutDirection = direction; invalidate()
    }
    override fun onDraw(canvas: Canvas) { drawable.draw(canvas) }
}


// Declined repeat DOWN/unmatched UP must not fall through to stock View click handling.
internal fun isHomeActivationKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
    else -> false
}
