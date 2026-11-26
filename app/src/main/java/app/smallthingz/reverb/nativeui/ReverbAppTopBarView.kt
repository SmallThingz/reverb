package app.smallthingz.reverb.nativeui

import smallthingz.ui.reverb.ReverbTopBarLayout
import smallthingz.ui.reverb.setReverbBrandOutline
import smallthingz.ui.components.NativePathMorph
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.reverb.ReverbToolbarContentActionView
import smallthingz.ui.reverb.ReverbToolbarActionView
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.text.view.closeAccessibilityLeases
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.Context
import android.view.View
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
) : ReverbTopBarLayout(context, initialEnvironment.textDensity.density, initialEnvironment.palette) {
    private var environment = initialEnvironment
    private var back: ReverbTopBarIconAction? = null
    private var incidentAction: (() -> Unit)? = null
    private var incidentAlert = false
    private var incidentsSuppressed = false
    private var backIncidentProgress = 0f
    private val incidents = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Incidents, R.string.open_incidents, false)
    private val settings = ReverbTopBarIconAction(context, environment, nativeInputRoot, ReverbIcon.Settings, R.string.open_settings, false)
    private val brand = ReverbTopBarBrandAction(context, environment, nativeInputRoot)
    private var accessibilityLease: AutoCloseable? = null
    private var refreshAccessibilityBack: (() -> Unit)? = null
    private var admitAccessibilityChildren: ((ArrayList<View>, Int, (View) -> View?) -> Unit)? = null
    init {
        addToolbarSlot(brand, Slot.CENTER)
        addToolbarSlot(incidents, Slot.START)
        addToolbarSlot(settings, Slot.END)
    }
    fun bind(value: ReverbViewEnvironment, hasIncidents: Boolean, actions: ReverbAppTopBarActions,
             statusInsetPx: Int = 0, applyStatusBarPadding: Boolean = true) {
        environment = value
        bindToolbar(value.textDensity.density, value.palette, if (applyStatusBarPadding) statusInsetPx else 0)
        val onBack = actions.back
        if (onBack == null) { back?.let(::removeView); back = null }
        else {
            if (back == null) back = ReverbTopBarIconAction(context, value, nativeInputRoot, ReverbIcon.Back, R.string.back, false).also { addToolbarSlot(it, Slot.START, index = 0) }
            back!!.bind(value, true, NativeToolbarActionTone.MUTED, onBack)
        }
        settings.bind(value, true, NativeToolbarActionTone.MUTED, actions.settings)
        incidentAction = actions.incidents
        incidentAlert = hasIncidents
        back?.setIncidentMorph(backIncidentProgress, hasIncidents)
        bindIncidents()
        brand.bind(value, onBack == null, actions.brand)
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
    sharedSurface: Boolean = false,
    rolePresent: Boolean = false,
) : ReverbToolbarActionView(context, environment.textDensity.density, environment.palette, inputRoot,
    context.getString(description), selectionWrapper, sharedSurface, rolePresent) {
    constructor(context: Context, environment: ReverbViewEnvironment, inputRoot: View,
        glyph: ReverbIcon, description: Int, selectionWrapper: Boolean, sharedSurface: Boolean = false,
        rolePresent: Boolean = false) :
        this(context, environment, { inputRoot }, glyph, description, selectionWrapper, sharedSurface, rolePresent)
    private var incidentProgress = 0f
    private var incidentAlert = false
    private var incidentMorph: NativePathMorph? = null
    init {
        val tint = ReverbDesign.appearance.toolbars.contentColor(environment.palette)
        setGlyph(ReverbIconDrawable(glyph, environment.textDensity.density, tint), tint)
    }

    fun bind(value: ReverbViewEnvironment, enabled: Boolean, tone: NativeToolbarActionTone, onClick: () -> Unit) {
        if (environment.textDensity.density != value.textDensity.density) {
            val tint = ReverbDesign.appearance.toolbars.actionTint(value.palette, tone)
            setGlyph(ReverbIconDrawable(glyph, value.textDensity.density, tint), tint)
            if (incidentMorph != null) incidentMorph = libraryBackIncidentMorph(value.textDensity.density)
        }
        environment = value
        bindToolbarAction(value.textDensity.density, value.palette, enabled, tone, onClick)
        incidentMorph?.let { setToolbarActionPreview(it, incidentProgress, incidentTone()) }
    }
    fun bindIcon(next: ReverbIcon, description: String) {
        setDescription(description)
        if (glyph == next) return
        glyph = next
        setGlyph(ReverbIconDrawable(next, environment.textDensity.density, sampledIconTint), sampledIconTint)
    }
    fun setIncidentMorph(progress: Float, hasIncidents: Boolean) {
        incidentProgress = progress.coerceIn(0f, 1f)
        incidentAlert = hasIncidents
        if (glyph == ReverbIcon.Back) {
            val morph = incidentMorph ?: libraryBackIncidentMorph(environment.textDensity.density).also { incidentMorph = it }
            setToolbarActionPreview(morph, incidentProgress, incidentTone())
        }
    }

    private fun incidentTone() = if (incidentAlert) NativeToolbarActionTone.ALERT else NativeToolbarActionTone.MUTED
}

private class ReverbTopBarBrandAction(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: View,
    private val mark: ReverbBrandMarkView = ReverbBrandMarkView(context),
) : ReverbToolbarContentActionView(context, mark, context.getString(R.string.app_name), inputRoot,
    environment.textDensity.density, environment.palette) {
    fun bind(value: ReverbViewEnvironment, outlined: Boolean, callback: () -> Unit) {
        mark.refreshResourcePalette()
        mark.setReverbBrandOutline(outlined)
        bindToolbarContent(value.textDensity.density, value.palette, callback)
    }
}

private fun topBarClipBounds(view: View): NativeTextSemanticsClip = NativeTextSemanticsClip.Rectangle(
    smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
