package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeActionSheetContentContext
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeSourceAccessibilityNodeProvider
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.util.closePreservingPrimaryFailure
import kotlin.math.max
import kotlin.math.roundToInt

/** Direct native-window bindings needed by this Button leaf; no renderer, registry or state tree. */
internal class NativeMaterialButtonHost(
    val environment:ReverbViewEnvironment,
    val inputRoot:View,
    val interestedParent:(NativeDragPointerChange)->Boolean,
    val textWindow:PlainTextAccessibilityWindowScope,
    val textGeometry:NativePlainTextViewGeometry,
    private val sourceFacts:(View,Float)->NativeSourceAccessibilityNode,
    val registerTextClip:(View)->AutoCloseable,
    private val createHit:(View,List<NativeViewPhasePointerNode>,()->List<NativeViewPointerHitLayout>,
                           (Offset)->Boolean)->NativeViewPointerHitLayout,
    val admitChildren:((ArrayList<View>,Int,(View)->View?)->Unit)?=null,
    // Supply only when provider facts match sourceFacts for every requested minimum-touch override.
    val sourceProvider:NativeSourceAccessibilityNodeProvider?=null,
) {
    fun sourceNode(view:View,minimumTouch:Float=0f)=sourceFacts(view,minimumTouch)
    fun hit(view:View,nodes:List<NativeViewPhasePointerNode>,
            children:()->List<NativeViewPointerHitLayout> = {emptyList()},clip:(Offset)->Boolean={true})=
        createHit(view,nodes,children,clip)

    companion object {
        fun forSheet(sheet:NativeActionSheetContentContext,environment:ReverbViewEnvironment)=NativeMaterialButtonHost(
            environment,sheet.inputRoot,sheet.interestedParent,sheet.accessibility.cursor,
            sheet.accessibility.geometry,sheet.accessibility::sourceNode,
            registerTextClip={content->
                val a=sheet.accessibility
                val earlier=a.extraClip
                val own:(View)->NativeTextSemanticsClip={view->if(view===content)
                    NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(0f,0f,
                        view.width.toFloat(),view.height.toFloat()))else earlier(view)}
                a.extraClip=own
                AutoCloseable {if(a.extraClip===own)a.extraClip=earlier}
            },
            createHit={view,nodes,children,clip->sheet.hit(view,nodes,children,clip=clip)},
        )
    }
}

/** Released natural-width default Button/TextButton, not the footer's fixed-width control. */
internal class LibrarySheetButton(
    context: Context,
    host: NativeMaterialButtonHost,
    filled: Boolean,
    text: String,
    onClick: () -> Unit,
    private val leadingIcon: ReverbIcon? = null,
    errorColors: Boolean = false,
) : ViewGroup(context), AutoCloseable {
    constructor(context:Context,host:NativeActionSheetContentContext,environment:ReverbViewEnvironment,filled:Boolean,text:String,onClick:()->Unit,
                leadingIcon:ReverbIcon?=null,errorColors:Boolean=false):
        this(context,NativeMaterialButtonHost.forSheet(host,environment),filled,text,onClick,leadingIcon,errorColors)
    private var environment=host.environment
    private var labelText = text
    private var action = onClick
    private val buttons = environment.appearance.buttons
    private val recipe = when {
        filled && errorColors -> buttons.destructive
        filled -> buttons.filled
        errorColors -> buttons.destructiveText
        else -> buttons.text
    }
    private val surface = NativeMaterialButtonView(context, recipe,
        environment.textDensity, environment.sharedFontResolver,
        NativeButtonInputPolicy(inputRoot = { host.inputRoot }), onClick)
    private val accessibility: AutoCloseable
    private val textAccessibility: AutoCloseable
    private val clip: AutoCloseable
    private val click = NativeClickPhaseNode(surface,surface.nativeClickEndpoint,host.interestedParent)
    private var closed=false
    val hitLayout: NativeViewPointerHitLayout = run {
        val surfaceNodes=listOf(click)
        val surfaceClip:(Offset)->Boolean={surface.containsPointerLayer(it.x,it.y)}
        host.hit(this,emptyList(),children = {
            // Sheet hosts capture their current minimum target when creating this wrapper.
            listOf(host.hit(surface,surfaceNodes,clip=surfaceClip))
        })
    }
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;clipToPadding=false
        addView(surface)
        val minimumTouch = { surface.minimumTouchTargetDp * environment.textDensity.density }
        val present = { host.sourceNode(surface, minimumTouch()).present }
        accessibility = host.sourceProvider?.let { surface.mergedRowAccessibility.bindSourceGeometry(host.textGeometry, it, present) }
            ?: surface.mergedRowAccessibility.bindGeometry(host.textGeometry, { host.sourceNode(surface, minimumTouch()) },
                { host.sourceNode(it).present }, present)
        val label = surface.label
        textAccessibility = label.bindPlainTextAccessibility(host.textWindow, host.textGeometry,
            { host.sourceNode(label) }) { host.sourceNode(label).present }
        clip = surface.registerTextClip(host.registerTextClip)
        bindIcon()
        bind(text, true, onClick)
    }
    fun bind(text: String, enabled: Boolean, onClick: () -> Unit) {
        labelText = text
        action = onClick
        surface.bind(text, enabled, environment.palette.buttonPalette, onClick = onClick)
    }
    private fun bindIcon() {
        surface.setLeadingIcon(leadingIcon?.let { ReverbIconDrawable(it, environment.textDensity.density, environment.palette.onPrimary) })
    }
    internal val accessibilitySemanticView:View get()=surface
    internal fun isAccessibilityNodePresent():Boolean=surface.mergedRowAccessibility.isNodePresent()
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed || environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment = next
        surface.bindTypography(next.textDensity, next.sharedFontResolver)
        if (densityChanged) {
            bindIcon()
            requestLayout()
        }
        bind(labelText, surface.isEnabled, action)
    }
    override fun onMeasure(w:Int,h:Int) {
        surface.measure(w,h)
        val minimum=(surface.minimumTouchTargetDp*environment.textDensity.density).roundToInt()
        setMeasuredDimension(resolveSize(max(minimum,surface.measuredWidth),w),
            resolveSize(max(minimum,surface.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-surface.measuredWidth)*.5f).roundToInt()
        val y=((height-surface.measuredHeight)*.5f).roundToInt()
        surface.layout(x,y,x+surface.measuredWidth,y+surface.measuredHeight)
    }
    override fun close() {
        if(closed)return
        closed=true
        action = {}
        var failure = closePreservingPrimaryFailure(null, click::cancel)
        for (lease in listOf(surface, clip, textAccessibility, accessibility)) {
            failure = closePreservingPrimaryFailure(failure, lease::close)
        }
        failure?.let { throw it }
    }
}
