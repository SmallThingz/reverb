package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeTopBarLayout
import smallthingz.ui.components.NativePathMorph
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeToolbarContentAction
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.text.view.closeAccessibilityLeases
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.Context
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils
import smallthingz.ui.animation.view.ViewColorTransition
import app.smallthingz.reverb.R

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
) : NativeTopBarLayout(context, initialEnvironment.appearance.toolbars) {
    private var environment = initialEnvironment
    private var back: ReverbTopBarIconAction? = null
    private var incidentAction: (() -> Unit)? = null
    private var incidentAlert = false
    private var incidentsSuppressed = false
    private var backIncidentProgress = 0f
    private val incidents = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Incidents, R.string.open_incidents, false)
    private val settings = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Settings, R.string.open_settings, false)
    private val brand = ReverbTopBarBrandAction(context, environment, nativeInputRoot)
    private val chrome = NativeToolbarBackground(this, toolbarStyle, toolbarStyle.backgroundColor(environment.palette))
    private var accessibilityLease: AutoCloseable? = null
    private var refreshAccessibilityBack: (() -> Unit)? = null
    private var admitAccessibilityChildren: ((ArrayList<View>, Int, (View) -> View?) -> Unit)? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        bindStyle(initialEnvironment.textDensity.density, 0, initialEnvironment.appearance.toolbars)
        addView(brand, ActionLayoutParams(Slot.CENTER, minimumVisualOffset = true))
        addView(incidents, ActionLayoutParams(Slot.START))
        addView(settings, ActionLayoutParams(Slot.END))
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    fun bind(value: ReverbViewEnvironment, hasIncidents: Boolean, actions: ReverbAppTopBarActions,
             statusInsetPx: Int = 0, applyStatusBarPadding: Boolean = true) {
        environment = value
        bindStyle(value.textDensity.density, if (applyStatusBarPadding) statusInsetPx else 0, value.appearance.toolbars)
        chrome.bind(toolbarStyle, toolbarStyle.backgroundColor(value.palette))
        val onBack = actions.back
        if (onBack == null) { back?.let(::removeView); back = null }
        else {
            if (back == null) back = ReverbTopBarIconAction(context, value, nativeInputRoot, ReverbIcon.Back, R.string.back, false).also { addView(it, 0, ActionLayoutParams(Slot.START)) }
            back!!.bind(value, true, NativeToolbarActionTone.MUTED, onBack)
        }
        settings.bind(value, true, NativeToolbarActionTone.MUTED, actions.settings)
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
        val tone = if (incidentAlert) NativeToolbarActionTone.ALERT else NativeToolbarActionTone.MUTED
        incidents.bind(environment, visible, tone, action)
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
    selectionWrapper, sharedSurface, rolePresent, environment.appearance.toolbars.contentColor(environment.palette),
    ::invalidateSettingsTextContrast, { view, tint -> smallthingz.ui.controls.textSurfaceForeground(settingsTextSurface(view), tint) },
    environment.appearance.toolbars.action) {
    constructor(context: Context, environment: ReverbViewEnvironment, inputRoot: View,
        glyph: ReverbIcon, description: Int, selectionWrapper: Boolean, sharedSurface: Boolean = false,
        rolePresent: Boolean = false) :
        this(context, environment, { inputRoot }, glyph, description, selectionWrapper, sharedSurface, rolePresent)
    private var incidentProgress = 0f
    private var incidentAlert = false
    private var incidentMorph: NativePathMorph? = null
    private var surfaceMotion: ViewColorTransition? = null
    private val incidentColorMotion = if (glyph == ReverbIcon.Back)
        visual.colorTransition(environment.appearance.toolbars.actionTint(environment.palette, NativeToolbarActionTone.MUTED)) {
            visual.invalidate()
        } else null
    private val incidentTint = { checkNotNull(incidentColorMotion).color(0) }
    init {
        val tint = environment.appearance.toolbars.contentColor(environment.palette)
        setGlyph(ReverbIconDrawable(glyph, environment.textDensity.density, tint), tint)
    }
    fun bind(value: ReverbViewEnvironment, enabled: Boolean, tone: NativeToolbarActionTone, onClick: () -> Unit) {
        val style = value.appearance.toolbars
        val tint = style.actionTint(value.palette, tone)
        val border = style.actionOutline(value.palette, tone)
        if (environment.textDensity.density != value.textDensity.density) {
            setGlyph(ReverbIconDrawable(glyph, value.textDensity.density, tint), tint)
            if (incidentMorph != null) {
                incidentMorph = libraryBackIncidentMorph(value.textDensity.density)
                setGlyphMorph(incidentMorph, incidentProgress, incidentTint)
            }
        }
        environment = value
        bindStyle(style.action)
        if (!sharedSurface) {
            val fill = style.actionFill(value.palette)
            val motion = surfaceMotion ?: ViewColorTransition(this,
                intArrayOf(fill, border, border), ::applySurfacePalette).also { surfaceMotion = it }
            motion.retarget(fill, border, incidentBorder())
            applySurfacePalette()
        }
        incidentColorMotion?.retarget(style.actionTint(value.palette, incidentTone()))
        bindAction(value.textDensity.density, enabled, tint, style.contentColor(value.palette), onClick)
    }
    fun bindIcon(next: ReverbIcon, description: String) {
        setDescription(description)
        if (glyph == next) return
        glyph = next
        setGlyph(ReverbIconDrawable(next, environment.textDensity.density, sampledIconTint), sampledIconTint)
    }
    fun setIncidentMorph(progress: Float, hasIncidents: Boolean) {
        incidentProgress = progress.coerceIn(0f, 1f)
        if (incidentAlert != hasIncidents) {
            incidentAlert = hasIncidents
            val style = environment.appearance.toolbars
            surfaceMotion?.retarget(style.actionFill(environment.palette),
                style.actionOutline(environment.palette, NativeToolbarActionTone.MUTED), incidentBorder())
        }
        if (glyph == ReverbIcon.Back) {
            if (incidentMorph == null) incidentMorph = libraryBackIncidentMorph(environment.textDensity.density)
            incidentColorMotion?.retarget(environment.appearance.toolbars.actionTint(environment.palette, incidentTone()))
            setGlyphMorph(incidentMorph, incidentProgress, incidentTint)
        }
        applySurfacePalette()
    }
    private fun incidentTone() = if (incidentAlert) NativeToolbarActionTone.ALERT else NativeToolbarActionTone.MUTED
    private fun incidentBorder() = environment.appearance.toolbars.actionOutline(environment.palette, incidentTone())
    private fun applySurfacePalette() {
        val motion = surfaceMotion ?: return
        setSurfacePaletteImmediately(motion.color(0), ColorUtils.blendARGB(motion.color(1), motion.color(2), incidentProgress))
    }
}

private class ReverbTopBarBrandAction(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: View,
    private val mark: ReverbBrandMarkView = ReverbBrandMarkView(context),
) : NativeToolbarContentAction(context, mark, context.getString(R.string.app_name), inputRoot,
    environment.textDensity.density, environment.appearance.toolbars.action.minimumTargetDp,
    environment.appearance.toolbars.contentColor(environment.palette)) {
    fun bind(value: ReverbViewEnvironment, callback: () -> Unit) {
        mark.refreshResourcePalette()
        val style = value.appearance.toolbars
        bindAction(value.textDensity.density, style.action.minimumTargetDp, style.contentColor(value.palette), callback)
    }
}

private fun topBarClipBounds(view: View): NativeTextSemanticsClip = NativeTextSemanticsClip.Rectangle(
    smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
