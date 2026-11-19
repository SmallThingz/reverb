package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeTopBarHitBounds
import smallthingz.ui.components.NativeTopBarHitTarget
import smallthingz.ui.components.NativeTopBarLayout
import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.interaction.view.isNativeClickActivationKey
import smallthingz.ui.geometry.roundedGroupContains
import smallthingz.ui.geometry.roundedGroupGeometry
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.RowIndication
import smallthingz.ui.interaction.view.RowIndicationSink
import smallthingz.ui.interaction.view.ViewClickController
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.text.view.NativeActionDescriptionView
import smallthingz.ui.text.view.bindActionDescriptionAccessibility
import smallthingz.ui.text.view.closeAccessibilityLeases
import smallthingz.ui.text.view.MergedRowAccessibility
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeMergedRowAccessibilityState
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.graphics.ColorUtils
import smallthingz.ui.animation.view.ViewColorTransition
import app.smallthingz.reverb.R
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativeClickPhaseEndpoint
import smallthingz.ui.input.phase.ViewClickPhaseEndpoint
import kotlin.math.min

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
) : NativeTopBarLayout(context) {
    private var environment = initialEnvironment
    private var back: ReverbTopBarIconAction? = null
    private var incidentAction: (() -> Unit)? = null
    private var incidentAlert = false
    private var incidentsSuppressed = false
    private var backIncidentProgress = 0f
    private val incidents = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Incidents, R.string.open_incidents, false)
    private val settings = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Settings, R.string.open_settings, false)
    private val brand = ReverbTopBarBrandAction(context, environment, nativeInputRoot)
    private val noise = ReverbNoiseDrawable(resources, environment.palette.surface, ReverbNoiseDrawable.SEED_TOP_BAR)
    private val noiseColor = noise.colorTransition(this, environment.palette.surface)
    private var accessibilityLease: AutoCloseable? = null
    private var refreshAccessibilityBack: (() -> Unit)? = null
    private var admitAccessibilityChildren: ((ArrayList<View>, Int, (View) -> View?) -> Unit)? = null
    init {
        background = noise
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        bindMetrics(initialEnvironment.textDensity.density)
        addView(brand, ActionLayoutParams(Slot.CENTER, minimumVisualOffset = true))
        addView(incidents, ActionLayoutParams(Slot.START))
        addView(settings, ActionLayoutParams(Slot.END))
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    fun bind(value: ReverbViewEnvironment, hasIncidents: Boolean, actions: ReverbAppTopBarActions,
             statusInsetPx: Int = 0, applyStatusBarPadding: Boolean = true, barHeightDp: Float = 66f) {
        environment = value
        bindMetrics(value.textDensity.density, if (applyStatusBarPadding) statusInsetPx else 0, barHeightDp)
        noiseColor.retarget(value.palette.surface)
        val onBack = actions.back
        if (onBack == null) { back?.let(::removeView); back = null }
        else {
            if (back == null) back = ReverbTopBarIconAction(context, value, nativeInputRoot, ReverbIcon.Back, R.string.back, false).also { addView(it, 0, ActionLayoutParams(Slot.START)) }
            back!!.bind(value, true, value.palette.muted, value.palette.border, onBack)
        }
        settings.bind(value, true, value.palette.muted, value.palette.border, actions.settings)
        incidentAction = actions.incidents
        incidentAlert = hasIncidents
        back?.setIncidentMorph(backIncidentProgress, hasIncidents)
        bindIncidents()
        brand.bind(value, actions.brand)
        refreshAccessibilityBack?.invoke()
    }

    /** The retained Home bar must not expose an action behind Library, including its selection bar. */
    fun setIncidentsSuppressed(value: Boolean) {
        if (incidentsSuppressed == value) return
        incidentsSuppressed = value
        bindIncidents()
    }

    /** Visual preview only: the existing Back leaf retains its label and navigation authority. */
    fun setBackIncidentProgress(progress: Float) {
        val next = progress.coerceIn(0f, 1f)
        if (backIncidentProgress == next) return
        backIncidentProgress = next
        back?.setIncidentMorph(backIncidentProgress, incidentAlert)
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
            closeAccessibilityLeases(fixed.asReversed(), error)
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
            closeAccessibilityLeases(listOfNotNull(backLease) + fixed.asReversed(), error)
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
                closeAccessibilityLeases(listOfNotNull(retired) + fixed.asReversed())
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

    private fun accessibilityAction(child: View): View? = when {
        child is ReverbTopBarIconAction -> child.accessibilityAction
        child === back?.visual || child === incidents.visual || child === settings.visual || child === brand -> child
        else -> null
    }
    fun sourceClip(view: View): NativeTextSemanticsClip? = when {
        view === this -> NativeTextSemanticsClip.Unclipped
        view === brand -> topBarClipBounds(view)
        else -> back?.sourceClip(view) ?: incidents.sourceClip(view) ?: settings.sourceClip(view)
    }
}

/** Reverb glyph/palette and incident-preview binding for the shared action. */
internal class ReverbTopBarIconAction(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private var glyph: ReverbIcon,
    description: Int,
    selectionWrapper: Boolean,
    private val sharedSurface: Boolean = false,
    rolePresent: Boolean = false,
) : NativeTopBarAction(context, environment.textDensity.density, inputRoot, context.getString(description),
    selectionWrapper, sharedSurface, rolePresent, environment.palette.ink,
    ::invalidateSettingsTextContrast, { view, tint -> smallthingz.ui.controls.textSurfaceForeground(settingsTextSurface(view), tint) }) {
    constructor(context: Context, environment: ReverbViewEnvironment, inputRoot: View,
        glyph: ReverbIcon, description: Int, selectionWrapper: Boolean, sharedSurface: Boolean = false,
        rolePresent: Boolean = false) :
        this(context, environment, { inputRoot }, glyph, description, selectionWrapper, sharedSurface, rolePresent)
    private var icon = ReverbIconDrawable(glyph, environment.textDensity.density, environment.palette.ink)
    private var publishedTint = environment.palette.ink
    private var incidentProgress = 0f
    private var iconIncidentProgress = 0f
    private var incidentAlert = false
    private var incidentMorph: LibraryBackIncidentMorph? = null
    private var surfaceMotion: ViewColorTransition? = null
    private val incidentColorMotion = if (glyph == ReverbIcon.Back)
        visual.colorTransition(environment.palette.muted) { visual.invalidate() } else null
    init {
        iconPainter = { canvas, bounds, tint ->
            icon.layoutDirection = visual.layoutDirection
            icon.setBounds(bounds)
            if (publishedTint != tint) { publishedTint = tint; icon.setTint(tint) }
            if (iconIncidentProgress <= 0f) icon.draw(canvas)
            else incidentMorph?.draw(canvas, bounds, iconIncidentProgress,
                ColorUtils.blendARGB(sampledIconTint, checkNotNull(incidentColorMotion).color(0), iconIncidentProgress),
                visual.layoutDirection == LAYOUT_DIRECTION_RTL)
        }
    }
    fun bind(value: ReverbViewEnvironment, enabled: Boolean, tint: Int, border: Int, onClick: () -> Unit) {
        if (environment.textDensity.density != value.textDensity.density) {
            icon = ReverbIconDrawable(glyph, value.textDensity.density, tint)
            publishedTint = tint
            if (incidentMorph != null) incidentMorph = LibraryBackIncidentMorph(value.textDensity.density)
        }
        environment = value
        if (!sharedSurface) {
            val motion = surfaceMotion ?: ViewColorTransition(this,
                intArrayOf(value.palette.field, border, border), ::applySurfacePalette).also { surfaceMotion = it }
            motion.retarget(value.palette.field, border, incidentBorder())
            applySurfacePalette()
        }
        incidentColorMotion?.retarget(if (incidentAlert) value.palette.error else value.palette.muted)
        bindAction(value.textDensity.density, enabled, tint, value.palette.ink, onClick)
    }
    fun bindIcon(next: ReverbIcon, description: String) {
        setDescription(description)
        if (glyph == next) return
        glyph = next
        icon = ReverbIconDrawable(next, environment.textDensity.density, sampledIconTint)
        publishedTint = sampledIconTint
        visual.invalidate()
    }
    fun setIncidentMorph(progress: Float, hasIncidents: Boolean) {
        incidentProgress = progress.coerceIn(0f, 1f)
        if (incidentAlert != hasIncidents) {
            incidentAlert = hasIncidents
            surfaceMotion?.retarget(environment.palette.field, environment.palette.border, incidentBorder())
        }
        if (glyph == ReverbIcon.Back) {
            if (incidentMorph == null) incidentMorph = LibraryBackIncidentMorph(environment.textDensity.density)
            iconIncidentProgress = incidentProgress
            incidentColorMotion?.retarget(if (hasIncidents) environment.palette.error else environment.palette.muted)
            visual.invalidate()
        }
        applySurfacePalette()
    }
    private fun incidentBorder() = if (incidentAlert)
        reverbColorWithAlpha(environment.palette.error, .55f) else environment.palette.border
    private fun applySurfacePalette() {
        val motion = surfaceMotion ?: return
        setSurfacePaletteImmediately(motion.color(0), ColorUtils.blendARGB(motion.color(1), motion.color(2), incidentProgress))
    }
}

private class ReverbTopBarBrandAction(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost, NativeTopBarHitTarget {
    override fun hitBounds() = NativeTopBarHitBounds(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat(), min(width, height) / 2f)
    private val identity = Any()
    private val mark = ReverbBrandMarkView(context)
    private val descriptionView = NativeActionDescriptionView(context, context.getString(R.string.app_name))
    private val accessibilityChildren = listOf(descriptionView)
    override val mergedRowAccessibility = MergedRowAccessibility(
        this, NativeMergedRowRole.BUTTON,
        sourceState = { NativeMergedRowAccessibilityState(isEnabled, null, null,
            accessibilityChildren, rolePresent = false) },
        invokeSourceClick = { click?.let { it(); true } ?: false },
    )
    private val softwareClip = Path()
    private var click: (() -> Unit)? = null
    private val indication = RowIndication.install(this, environment.palette.ink, { readCachedAnimatorDurationScale(context) })
    private val controller = ViewClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View = inputRoot
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = roundedGroupGeometry(width.toFloat(), height.toFloat(),
            environment.textDensity.density, min(width, height) / (2f * environment.textDensity.density))
            .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, RowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint: NativeClickPhaseEndpoint = ViewClickPhaseEndpoint(controller)
    override fun containsPointerLayer(x: Float, y: Float) =
        roundedGroupContains(width.toFloat(), height.toFloat(), environment.textDensity.density,
            min(width, height) / (2f * environment.textDensity.density), x, y)
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
        bindActionDescriptionAccessibility(this, mergedRowAccessibility, descriptionView, cursor, geometry, sourceNode)
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
        if (isNativeClickActivationKey(code)) controller.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isNativeClickActivationKey(code)) controller.key(event) else super.onKeyUp(code, event)
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


private fun topBarClipBounds(view: View): NativeTextSemanticsClip = NativeTextSemanticsClip.Rectangle(
    smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))

private fun appTopBarExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
