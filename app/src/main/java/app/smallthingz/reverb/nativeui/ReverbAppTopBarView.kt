package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import smallthingz.ui.text.style.TextOverflow
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

/** Released geometry with the approved Home-only start Incidents action; caller resolves status insets. */
internal class ReverbAppTopBarView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    private val nativeInputRoot: View,
) : ReverbTopBarActionRoutingGroup(context) {
    private var environment = initialEnvironment
    private var back: ReverbTopBarIconAction? = null
    private var incidentAction: (() -> Unit)? = null
    private var incidentAlert = false
    private var incidentsSuppressed = false
    private val incidents = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Incidents, R.string.open_incidents, false)
    private val settings = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Settings, R.string.open_settings, false)
    private val brand = ReverbTopBarBrandAction(context, environment, nativeInputRoot)
    private val noise = ReverbNoiseDrawable(resources, environment.palette.surface, ReverbNoiseDrawable.SEED_TOP_BAR)
    private var statusInset = 0
    private var barHeight = 66f
    private var accessibilityLease: AutoCloseable? = null
    private var refreshAccessibilityBack: (() -> Unit)? = null
    private var admitAccessibilityChildren: ((ArrayList<View>, Int, (View) -> View?) -> Unit)? = null
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
        incidentAction = actions.incidents
        incidentAlert = hasIncidents
        bindIncidents()
        brand.bind(value, actions.brand)
        refreshAccessibilityBack?.invoke()
        touchDensity = value.textDensity.density
        if (layoutChanged) requestLayout()
    }

    /** The retained Home bar must not expose an action behind Library, including its selection bar. */
    fun setIncidentsSuppressed(value: Boolean) {
        if (incidentsSuppressed == value) return
        incidentsSuppressed = value
        bindIncidents()
    }

    private fun bindIncidents() {
        val action = incidentAction ?: return
        val visible = back == null && !incidentsSuppressed
        incidents.visibility = if (visible) VISIBLE else GONE
        val tint = if (incidentAlert) environment.palette.error else environment.palette.muted
        incidents.bind(environment, visible, tint,
            if (incidentAlert) reverbColorWithAlpha(tint, .55f) else environment.palette.border, action)
    }

    /** Same window cursor and source geometry as the enclosing Home/Library screen. */
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode,
        admitChildren: ((ArrayList<View>, Int, (View) -> View?) -> Unit)? = null): AutoCloseable {
        val previous = accessibilityLease
        accessibilityLease = null
        previous?.close()
        val fixed = ArrayList<AutoCloseable>(3)
        try {
            fixed += brand.bindAccessibility(cursor, geometry, sourceNode)
            fixed += incidents.bindAccessibility(cursor, geometry, sourceNode)
            fixed += settings.bindAccessibility(cursor, geometry, sourceNode)
        } catch (error: Throwable) {
            closeTopBarAccessibilityLeases(fixed.asReversed(), error)
            throw error
        }
        var boundBack: ReverbTopBarIconAction? = null
        var backLease: AutoCloseable? = null
        val refresh: () -> Unit = {
            if (boundBack !== back) {
                val retired = backLease
                backLease = null
                boundBack = null
                retired?.close()
                val next = back
                backLease = next?.bindAccessibility(cursor, geometry, sourceNode)
                boundBack = next
            }
        }
        refreshAccessibilityBack = refresh
        try { refresh() } catch (error: Throwable) {
            if (refreshAccessibilityBack === refresh) refreshAccessibilityBack = null
            closeTopBarAccessibilityLeases(listOfNotNull(backLease) + fixed.asReversed(), error)
            throw error
        }
        admitAccessibilityChildren = admitChildren
        var closed = false
        val lease = AutoCloseable {
            if (!closed) {
                closed = true
                if (refreshAccessibilityBack === refresh) {
                    refreshAccessibilityBack = null
                    admitAccessibilityChildren = null
                }
                val retired = backLease
                backLease = null
                boundBack = null
                closeTopBarAccessibilityLeases(listOfNotNull(retired) + fixed.asReversed())
            }
        }
        accessibilityLease = lease
        return lease
    }

    override fun addChildrenForAccessibility(children: ArrayList<View>) {
        val first = children.size
        super.addChildrenForAccessibility(children)
        if (first == children.size) return
        val admit = admitAccessibilityChildren
        if (admit != null) {
            admit(children, first, ::accessibilityAction)
        } else {
            // Standalone owners already bind each action's source node. A clipped action must
            // not be advertised by this structural wrapper when its node provider returns null.
            var write = first
            for (read in first until children.size) {
                val child = children[read]
                if ((accessibilityAction(child) as? NativeMergedRowAccessibilityHost)?.mergedRowAccessibility
                        ?.isNodePresent() != false) children[write++] = child
            }
            if (write != children.size) children.subList(write, children.size).clear()
        }
    }

    private fun accessibilityAction(child: View): View? = when (child) {
        is ReverbTopBarIconAction -> child.accessibilityAction
        is ReverbTopBarActionLeaf, is ReverbTopBarBrandAction -> child
        else -> null
    }
    fun sourceClip(view: View): NativeTextSemanticsClip? = when {
        view === this -> NativeTextSemanticsClip.Unclipped
        view === brand -> topBarClipBounds(view)
        else -> back?.sourceClip(view) ?: incidents.sourceClip(view) ?: settings.sourceClip(view)
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
        logical(incidents, inset)
        val bx = ((width - size) / 2f).roundToInt()
        // This View is the inner visual Surface; the source's outer size remains46dp.
        val visualOffset = nativeMinimumInteractiveVisualOffset(size, dp(48f))
        brand.layout(bx + visualOffset, y + visualOffset,
            bx + visualOffset + size, y + visualOffset + size)
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
    internal val accessibilityAction: View get() = leaf
    init {
        // The source size modifier does not clip an overflowing minimum-layout residual.
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(leaf, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(surface)
    }
    fun bind(environment: ReverbViewEnvironment, enabled: Boolean, tint: Int, border: Int, onClick: () -> Unit) {
        density = environment.textDensity.density
        surface.setPalette(environment.palette.field, border)
        leaf.bind(environment, enabled, tint, onClick)
    }
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        leaf.bindAccessibility(cursor, geometry, sourceNode)
    fun sourceClip(view: View): NativeTextSemanticsClip? = when (view) {
        surface -> topBarClipBounds(view)
        this, leaf -> NativeTextSemanticsClip.Unclipped
        else -> null
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
        val minimum = (48f * density).roundToInt()
        val x = ((width - surface.measuredWidth) / 2f).roundToInt() +
            nativeMinimumInteractiveVisualOffset(surface.measuredWidth, minimum)
        val y = ((height - surface.measuredHeight) / 2f).roundToInt() +
            nativeMinimumInteractiveVisualOffset(surface.measuredHeight, minimum)
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
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost {
    private val identity = Any()
    private var click: (() -> Unit)? = null
    private var icon = ReverbIconDrawable(glyph, environment.textDensity.density, environment.palette.ink)
    private val descriptionView = ReverbTopBarDescriptionView(context, description)
    private val accessibilityChildren = listOf(descriptionView)
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(
        this, NativeMergedRowRole.BUTTON,
        sourceState = { NativeMergedRowAccessibilityState(isEnabled, null, null,
            accessibilityChildren, rolePresent = false) },
        invokeSourceClick = { if (isEnabled && click != null) { click?.invoke(); true } else false },
    )
    private val indication = ReverbRowIndication.install(this, environment.palette.ink, { reverbAnimatorDurationScale(context) })
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
    override fun containsPointerLayer(x: Float, y: Float) =
        reverbGroupGeometry(width.toFloat(), height.toFloat(), environment.textDensity.density, 15f).contains(x, y)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // Source role-less Surface has no description; its actual Icon child owns it.
        isClickable = true
        setWillNotDraw(false)
        accessibilityDelegate = mergedRowAccessibility
        addView(descriptionView)
        setOnClickListener { if (isEnabled) click?.invoke() }
    }
    fun bind(value: ReverbViewEnvironment, enabled: Boolean, tint: Int, callback: () -> Unit) {
        if (environment.textDensity.density != value.textDensity.density) {
            icon = ReverbIconDrawable(glyph, value.textDensity.density, tint)
            requestLayout()
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
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        bindTopBarActionAccessibility(this, mergedRowAccessibility, descriptionView, cursor, geometry, sourceNode)
    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = (23f * environment.textDensity.density).roundToInt()
        descriptionView.measure(appTopBarExact(min(size, w)), appTopBarExact(min(size, h)))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = ((width - descriptionView.measuredWidth) / 2f).roundToInt()
        val y = ((height - descriptionView.measuredHeight) / 2f).roundToInt()
        descriptionView.layout(x, y, x + descriptionView.measuredWidth, y + descriptionView.measuredHeight)
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
    // The new non-interactive semantics child must not intercept the parent's hover observer.
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchHoverEvent(event)
        return controller.hover(event) || handled
    }
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

private class ReverbTopBarBrandAction(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost {
    private val identity = Any()
    private val mark = ReverbBrandMarkView(context)
    private val descriptionView = ReverbTopBarDescriptionView(context, context.getString(R.string.app_name))
    private val accessibilityChildren = listOf(descriptionView)
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(
        this, NativeMergedRowRole.BUTTON,
        sourceState = { NativeMergedRowAccessibilityState(isEnabled, null, null,
            accessibilityChildren, rolePresent = false) },
        invokeSourceClick = { click?.let { it(); true } ?: false },
    )
    private val softwareClip = Path()
    private var click: (() -> Unit)? = null
    private val indication = ReverbRowIndication.install(this, environment.palette.ink, { reverbAnimatorDurationScale(context) })
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
    override fun containsPointerLayer(x: Float, y: Float) =
        reverbGroupGeometry(width.toFloat(), height.toFloat(), environment.textDensity.density,
            min(width, height) / (2f * environment.textDensity.density)).contains(x, y)
    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = mergedRowAccessibility
        addView(mark)
        addView(descriptionView)
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
        descriptionView.measure(appTopBarExact(mark.measuredWidth), appTopBarExact(mark.measuredHeight))
        setMeasuredDimension(mark.measuredWidth, mark.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        mark.layout(0, 0, width, height)
        descriptionView.layout(0, 0, width, height)
    }
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        bindTopBarActionAccessibility(this, mergedRowAccessibility, descriptionView, cursor, geometry, sourceNode)
    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
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
    // The new non-interactive semantics child must not intercept the parent's hover observer.
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchHoverEvent(event)
        return controller.hover(event) || handled
    }
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


/** Actual source Icon/Image semantic child; drawing stays on the existing unchanged renderer. */
private class ReverbTopBarDescriptionView(context: Context, description: String) : View(context) {
    private var sourceNode: (() -> NativeSourceAccessibilityNode)? = null
    private var text: PlainTextAccessibility? = null
    private var token: Any? = null
    init {
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = false
    }
    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
        node: () -> NativeSourceAccessibilityNode): AutoCloseable {
        val next = Any(); token = next; sourceNode = node
        text = PlainTextAccessibility(this, cursor,
            snapshot = { PlainTextAccessibilitySnapshot(contentDescription.toString(), { null }, { null }) },
            exposed = { sourceNode?.invoke()?.present == true },
            descriptionOnly = true)
        return AutoCloseable {
            if (token === next) { token = null; sourceNode = null; text = null }
        }
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // The pinned delegate suppresses Image class/focus under a merging Surface ancestor.
        info.className = "android.view.View"
        info.text = null
        info.isScreenReaderFocusable = false
        info.isFocusable = false
        info.isClickable = false
        info.isLongClickable = false
        info.isEnabled = true
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        text?.populate(info)
        sourceNode?.invoke()?.populate(info)
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        if (sourceNode?.invoke()?.present == false) return false
        return text?.performAction(action, args) ?: super.performAccessibilityAction(action, args)
    }
    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = "android.view.View"
        event.isEnabled = true
        event.text.clear()
        event.contentDescription = null
    }
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

private fun bindTopBarActionAccessibility(host: ViewGroup, parent: ReverbMergedRowAccessibility,
    description: ReverbTopBarDescriptionView, cursor: PlainTextAccessibilityWindowScope,
    geometry: NativePlainTextViewGeometry, sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable {
    val parentLease = parent.bindSourceGeometry(geometry, sourceNode) { sourceNode(host).present }
    val childLease = try { description.bindAccessibility(cursor) { sourceNode(description) } }
    catch (error: Throwable) {
        closeTopBarAccessibilityLeases(listOf(parentLease), error)
        throw error
    }
    var closed = false
    return AutoCloseable {
        if (!closed) {
            closed = true
            closeTopBarAccessibilityLeases(listOf(childLease, parentLease))
        }
    }
}

private fun closeTopBarAccessibilityLeases(leases: List<AutoCloseable>, primary: Throwable? = null) {
    var failure = primary
    for (lease in leases) try { lease.close() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
    }
    failure?.let { throw it }
}

private fun topBarClipBounds(view: View): NativeTextSemanticsClip = NativeTextSemanticsClip.Rectangle(
    smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))

private fun appTopBarExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)

private fun isActivationKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
    else -> false
}
