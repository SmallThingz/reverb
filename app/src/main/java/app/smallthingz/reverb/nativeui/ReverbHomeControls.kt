package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
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
import smallthingz.ui.animation.VectorConverter
import smallthingz.ui.animation.core.AnimationVector4D
import smallthingz.ui.animation.core.tween
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
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
    private var oneShotLabel = context.getString(R.string.buffer_one_shot)
    private var loopingLabel = context.getString(R.string.buffer_loop)
    private var currentActions: ReverbHomeActions? = null
    private var selectedBuffer = BufferSlot.ONE_SHOT
    private val selectOneShot: () -> Unit = { currentActions?.selectBuffer(BufferSlot.ONE_SHOT) }
    private val selectLooping: () -> Unit = { currentActions?.selectBuffer(BufferSlot.LOOPING) }
    private val exportFull: () -> Unit = { currentActions?.exportFull(selectedBuffer) }
    private val exportCustom: () -> Unit = { currentActions?.exportCustom(selectedBuffer) }
    private val clearBuffer: () -> Unit = { currentActions?.clearBuffer(selectedBuffer) }
    private val openLibrary: () -> Unit = { currentActions?.openLibrary() }
    private val libraryFrame = ReverbHomeFlipFrame()
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
        // Publish actions/slot even when the visual and interaction inputs are unchanged.
        currentActions = actions
        val slot = state.selectedBuffer
        selectedBuffer = slot
        oneShot.bind(oneShotLabel,
            if (state.oneShotFull) ReverbIcon.Check else ReverbIcon.OneShot,
            slot == BufferSlot.ONE_SHOT,
            isBufferActivelyRecording(BufferSlot.ONE_SHOT, state.listening, state.activeBuffer),
            state.oneShotEnabled && !state.oneShotFull, state.oneShotFull, next = selectOneShot)
        looping.bind(loopingLabel, ReverbIcon.Looping,
            slot == BufferSlot.LOOPING,
            isBufferActivelyRecording(BufferSlot.LOOPING, state.listening, state.activeBuffer),
            state.loopingEnabled, false, next = selectLooping)
        val canExport = state.serviceReady && !state.saving && state.hasHistory
        buttons[0].bind(canExport, false, flip, exportFull)
        buttons[1].bind(canExport, false, flip, exportCustom)
        buttons[2].bind(canExport && !state.selectedRecording, true, flip, clearBuffer)
        // Library is the one icon not flipped with the selected buffer.
        buttons[3].bind(!state.saving, false, libraryFrame, openLibrary)
    }

    /** Library and selector content stay static while the selected buffer's three glyphs flip. */
    fun updateFrame(frame: ReverbHomeFlipFrame) {
        buttons[0].updateFrame(frame)
        buttons[1].updateFrame(frame)
        buttons[2].updateFrame(frame)
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        oneShotLabel = context.getString(R.string.buffer_one_shot)
        loopingLabel = context.getString(R.string.buffer_loop)
        setSurfaceEnvironment(value)
        oneShot.setEnvironment(value); looping.setEnvironment(value)
        buttons.forEach { it.setEnvironment(value) }
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        oneShotLabel = context.getString(R.string.buffer_one_shot)
        loopingLabel = context.getString(R.string.buffer_loop)
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
        for (index in 0..1) {
            val segment = if (index == 0) oneShot else looping
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
        for (index in buttons.indices) {
            val button = buttons[index]
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
        for (index in 0..1) {
            val segment = if (index == 0) oneShot else looping
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
        for (index in buttons.indices) {
            val button = buttons[index]
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
    private val performCurrentClick: () -> Unit = { performClick() }
    private var clickBinding: NativeClickBinding? = null
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
        if (clickBinding?.enabled != enabled) {
            val binding = NativeClickBinding(key, enabled, onClick = performCurrentClick)
            clickBinding = binding
            input.bind(binding)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); input.attached(); bindClick(isEnabled, action)
    }
    override fun onDetachedFromWindow() {
        input.detached()
        clickBinding = null // The controller clears its binding; reattach must publish it again.
        super.onDetachedFromWindow()
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
    root: () -> View?, animationScope: CoroutineScope) : HomeClickable(context, currentEnvironment, root, 16f),
    NativeMergedRowAccessibilityHost {
    private val glyph = HomeGlyph(context, currentEnvironment, ReverbIcon.OneShot)
    private val label = currentEnvironment.paragraph(context, "", ReverbTextRole.LABEL_LARGE)
    private val accessibilityChildren = listOf(label)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundMotion = colorMotion(Color.Transparent)
    private val foregroundMotion = colorMotion(Color(currentEnvironment.palette.muted))
    private val transitionInk = HomeBufferInk()
    private var initialized = false
    private var selection = false
    private var recording = false
    private var bufferEnabled = true
    private var filled = false
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(this, NativeMergedRowRole.TAB,
        { NativeMergedRowAccessibilityState(isEnabled, null, null, accessibilityChildren, selected = selection) },
        { action(); true })
    private val frames = HomeFrameLoop(this, animationScope,
        { backgroundMotion.running || foregroundMotion.running }) { time, scale ->
        backgroundMotion.frame(time, scale); foregroundMotion.frame(time, scale)
        applyColors()
    }
    init {
        setWillNotDraw(false)
        addView(glyph); addView(label)
        // The shared page owner binds this existing row to Main's minimum-aware source bounds.
        // The role-only child has no drawing/input and keeps the original visual bounds.
        addView(mergedRowAccessibility.roleView)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = mergedRowAccessibility
        label.isScreenReaderFocusable = false
        label.setTextLayout(maxLines = 1)
    }
    fun bind(text: String, icon: ReverbIcon, selected: Boolean, recording: Boolean,
        enabled: Boolean, filled: Boolean, interactionEnabled: Boolean = true, next: () -> Unit) {
        val appearanceChanged = !initialized || selection != selected || this.recording != recording ||
            bufferEnabled != enabled || this.filled != filled
        selection = selected; this.recording = recording; bufferEnabled = enabled; this.filled = filled
        isSelected = selected
        glyph.setIcon(icon)
        label.setText(text)
        bindClick(interactionEnabled, next) // Disabled/full buffers remain selectable; Home keeps the true default.
        if (appearanceChanged) updateTargets()
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
        transitionInk.target(p.field, p.ink, background.toArgb(), foreground.toArgb())
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
        val tint = transitionInk.foreground(fill.color, foregroundMotion.value.toArgb(),
            backgroundMotion.running || foregroundMotion.running)
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
        mergedRowAccessibility.roleView.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
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
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
    override fun addChildrenForAccessibility(children: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(children)
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
    fun updateFrame(frame: ReverbHomeFlipFrame) {
        flip = frame
        applyTransform()
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
        applyTransform()
    }
    private fun applyTransform() {
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
        if (icon == value) return
        icon = value
        drawable = ReverbIconDrawable(value, environment.textDensity.density, tint)
        drawable.setBounds(0, 0, width, height); drawable.layoutDirection = layoutDirection
        invalidate()
    }
    fun setTint(value: Int) {
        if (tint == value) return
        tint = value; drawable.setTint(value); invalidate()
    }
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
