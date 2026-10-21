package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.ui.text.style.TextOverflow
import app.smallthingz.reverb.R
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ReverbAppTopBarActions(
    val brand: () -> Unit,
    val incidents: () -> Unit,
    val settings: () -> Unit,
    val back: (() -> Unit)? = null,
)

/** Direct released AppTopBar.kt geometry; caller supplies the already-resolved status-bar inset. */
internal class ReverbAppTopBarView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    private val nativeInputRoot: View,
) : ReverbTopBarActionRoutingGroup(context) {
    private var environment = initialEnvironment
    private var back: ReverbTopBarIconAction? = null
    private val incidents = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Incidents, R.string.open_incidents, false)
    private val settings = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Settings, R.string.open_settings, false)
    private val brand = ReverbTopBarBrandAction(context, environment, nativeInputRoot)
    private val noise = ReverbNoiseDrawable(resources, environment.palette.surface, ReverbNoiseDrawable.SEED_TOP_BAR)
    private var statusInset = 0
    private var barHeight = 66f
    init {
        background = noise
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(brand); addView(incidents); addView(settings)
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    fun bind(value: ReverbViewEnvironment, hasIncidents: Boolean, actions: ReverbAppTopBarActions,
             statusInsetPx: Int = 0, applyStatusBarPadding: Boolean = true, barHeightDp: Float = 66f) {
        val nextInset = if (applyStatusBarPadding) statusInsetPx else 0
        val layoutChanged = environment.textDensity.density != value.textDensity.density ||
            statusInset != nextInset || barHeight != barHeightDp
        environment = value
        statusInset = nextInset
        barHeight = barHeightDp
        noise.setBackgroundColor(value.palette.surface)
        val onBack = actions.back
        if (onBack == null) { back?.let(::removeView); back = null }
        else {
            if (back == null) back = ReverbTopBarIconAction(context, value, nativeInputRoot, ReverbIcon.Back, R.string.back, false).also { addView(it, 0) }
            back!!.bind(value, true, value.palette.muted, value.palette.border, onBack)
        }
        settings.bind(value, true, value.palette.muted, value.palette.border, actions.settings)
        val tint = if (hasIncidents) value.palette.error else value.palette.muted
        incidents.bind(value, true, tint,
            if (hasIncidents) reverbColorWithAlpha(tint, .55f) else value.palette.border, actions.incidents)
        brand.bind(value, actions.brand)
        touchDensity = value.textDensity.density
        if (layoutChanged) requestLayout()
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(46f)
        for (index in 0 until childCount) getChildAt(index).measure(appTopBarExact(size), appTopBarExact(size))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(statusInset + dp(barHeight), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val inset = dp(14f)
        val size = dp(46f)
        val y = statusInset + ((height - statusInset - size) / 2f).roundToInt()
        fun logical(child: View, x: Int) {
            val physical = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - size else x
            child.layout(physical, y, physical + size, y + size)
        }
        back?.let { logical(it, inset) }
        logical(settings, width - inset - size)
        logical(incidents, width - inset - dp(56f) - size)
        val bx = ((width - size) / 2f).roundToInt()
        brand.layout(bx, y, bx + size, y + size)
    }
}

/** Explicit48dp minimum-target routing; never a permanent whole-screen touch delegate. */
internal abstract class ReverbTopBarActionRoutingGroup(context: Context) : ViewGroup(context) {
    protected var touchDensity = resources.displayMetrics.density
    private var delegated: View? = null
    init { clipChildren = false; clipToPadding = false }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            delegated = null
            val eligible = ArrayList<View>(childCount)
            val bounds = ArrayList<ReverbTopBarHitBounds>(childCount)
            for (index in childCount - 1 downTo 0) {
                val child = getChildAt(index)
                if (child.visibility != VISIBLE) continue
                val shape = when (child) {
                    is ReverbTopBarIconAction -> child.hitBounds()
                    is ReverbTopBarBrandAction -> ReverbTopBarHitBounds(child.left.toFloat(), child.top.toFloat(),
                        child.width.toFloat(), child.height.toFloat(), min(child.width, child.height) / 2f)
                    else -> continue
                }
                eligible += child
                bounds += shape
            }
            val hit = reverbTopBarHitIndex(bounds, event.x, event.y,
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, 48f * touchDensity)
            delegated = hit?.let(eligible::get)
        }
        val target = delegated ?: return super.dispatchTouchEvent(event)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        val handled = try { target.dispatchTouchEvent(copy) } finally { copy.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) delegated = null
        return handled
    }
    override fun onDetachedFromWindow() { delegated = null; super.onDetachedFromWindow() }
}

/**46dp Surface,15dp corners,1dp source border,23dp icon; optional48dp selection wrapper. */
internal class ReverbTopBarIconAction(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: View,
    glyph: ReverbIcon,
    description: Int,
    private val selectionWrapper: Boolean,
) : ViewGroup(context) {
    private var density = environment.textDensity.density
    private val surface = ReverbGroupLayout(context, 15f)
    private val leaf = ReverbTopBarActionLeaf(context, environment, inputRoot, glyph, context.getString(description))
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(leaf, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(surface)
    }
    fun bind(environment: ReverbViewEnvironment, enabled: Boolean, tint: Int, border: Int, onClick: () -> Unit) {
        density = environment.textDensity.density
        surface.setPalette(environment.palette.field, border)
        leaf.bind(environment, enabled, tint, onClick)
    }
    internal fun hitBounds() = ReverbTopBarHitBounds(
        left + surface.left.toFloat(), top + surface.top.toFloat(),
        surface.width.toFloat(), surface.height.toFloat(), 15f * density,
    )
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val outer = ((if (selectionWrapper) 48f else 46f) * density).roundToInt()
        val visual = (46f * density).roundToInt()
        surface.measure(appTopBarExact(visual), appTopBarExact(visual))
        setMeasuredDimension(resolveSize(outer, widthMeasureSpec), resolveSize(outer, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - surface.measuredWidth) / 2f).roundToInt()
        val y = ((height - surface.measuredHeight) / 2f).roundToInt()
        surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-surface.left.toFloat(), -surface.top.toFloat())
        return try { leaf.dispatchTouchEvent(copy) } finally { copy.recycle() }
    }
}

private class ReverbTopBarActionLeaf(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: View,
    private val glyph: ReverbIcon,
    description: String,
) : View(context), NativeClickPhaseHost {
    private val identity = Any()
    private var click: (() -> Unit)? = null
    private var icon = ReverbIconDrawable(glyph, environment.textDensity.density, environment.palette.ink)
    private val indication = ReverbRowIndication.install(this, environment.palette.ink, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View = inputRoot
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = reverbGroupGeometry(width.toFloat(), height.toFloat(),
            environment.textDensity.density, 15f).acceptsPointer(event.x, event.y,
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint: NativeClickPhaseEndpoint = ReverbClickPhaseEndpoint(controller)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = description
        isClickable = true
        setOnClickListener { if (isEnabled) click?.invoke() }
    }
    fun bind(value: ReverbViewEnvironment, enabled: Boolean, tint: Int, callback: () -> Unit) {
        if (environment.textDensity.density != value.textDensity.density) {
            icon = ReverbIconDrawable(glyph, value.textDensity.density, tint)
        }
        environment = value
        click = callback
        isEnabled = enabled
        isFocusable = enabled
        icon.setTint(if (enabled) tint else reverbColorWithAlpha(tint, .38f))
        indication.updateColor(value.palette.ink)
        controller.bind(NativeClickBinding(identity, enabled, onClick = { performClick() }))
        updateGeometry(width, height)
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateGeometry(w, h)
    private fun updateGeometry(w: Int, h: Int) {
        icon.layoutDirection = layoutDirection
        val size = (23f * environment.textDensity.density).roundToInt()
        val glyphWidth = min(size, w)
        val glyphHeight = min(size, h)
        val x = ((w - glyphWidth) / 2f).roundToInt()
        val y = ((h - glyphHeight) / 2f).roundToInt()
        icon.setBounds(x, y, x + glyphWidth, y + glyphHeight)
        indication.onMeasured(w, h, environment.textDensity.density)
    }
    override fun onDraw(canvas: Canvas) { icon.draw(canvas) }
    override fun onRtlPropertiesChanged(layoutDirection: Int) { super.onRtlPropertiesChanged(layoutDirection); icon.layoutDirection = layoutDirection }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
        if (isActivationKey(code)) controller.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isActivationKey(code)) controller.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean { val handled = super.onHoverEvent(event); return controller.hover(event) || handled }
    override fun onFocusChanged(focused: Boolean, direction: Int, rect: Rect?) { super.onFocusChanged(focused, direction, rect); controller.focusChanged(focused) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.attached()
        controller.bind(if (click == null) null else NativeClickBinding(identity, isEnabled, onClick = { performClick() }))
        controller.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

private class ReverbTopBarBrandAction(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context), NativeClickPhaseHost {
    private val identity = Any()
    private val mark = ReverbBrandMarkView(context)
    private val softwareClip = Path()
    private var click: (() -> Unit)? = null
    private val indication = ReverbRowIndication.install(this, environment.palette.ink, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View = inputRoot
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = reverbGroupGeometry(width.toFloat(), height.toFloat(),
            environment.textDensity.density, min(width, height) / (2f * environment.textDensity.density))
            .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint: NativeClickPhaseEndpoint = ReverbClickPhaseEndpoint(controller)
    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.app_name)
        addView(mark)
        setOnClickListener { click?.invoke() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, min(width, height) / 2f)
            }
        }
    }
    fun bind(value: ReverbViewEnvironment, callback: () -> Unit) {
        environment = value
        click = callback
        mark.refreshResourcePalette()
        indication.updateColor(value.palette.ink)
        controller.bind(NativeClickBinding(identity, true, onClick = { performClick() }))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        mark.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(mark.measuredWidth, mark.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { mark.layout(0, 0, width, height) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        indication.onMeasured(w, h, environment.textDensity.density)
        softwareClip.rewind()
        softwareClip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), min(w, h) / 2f, min(w, h) / 2f, Path.Direction.CW)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas) else {
            val checkpoint = canvas.save()
            try { canvas.clipPath(softwareClip); super.draw(canvas) }
            finally { canvas.restoreToCount(checkpoint) }
        }
    }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
        if (isActivationKey(code)) controller.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isActivationKey(code)) controller.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean { val handled = super.onHoverEvent(event); return controller.hover(event) || handled }
    override fun onFocusChanged(focused: Boolean, direction: Int, rect: Rect?) { super.onFocusChanged(focused, direction, rect); controller.focusChanged(focused) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.attached()
        controller.bind(if (click == null) null else NativeClickBinding(identity, isEnabled, onClick = { performClick() }))
        controller.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

private fun appTopBarExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun appTopBarAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)

private fun isActivationKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
    else -> false
}
