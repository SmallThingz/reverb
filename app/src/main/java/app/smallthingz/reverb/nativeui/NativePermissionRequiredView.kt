package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeRectangularViewport
import smallthingz.ui.util.closePreservingPrimaryFailure
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import app.smallthingz.reverb.R
import app.smallthingz.reverb.requiresLegacyPublicStoragePermission
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MainActivity1300-1338 only. Parent already applies Scaffold padding, main background/noise and
 * navigation motion. Granted capture and denied-sheet states have no children in this branch.
 */
internal class NativePermissionRequiredView(
    context:Context,
    private val host:NativeMaterialButtonHost,
    onReviewPermission:()->Unit,
    onLibrary:()->Unit,
):ViewGroup(context),AutoCloseable {
    private var environment=host.environment
    private var review=onReviewPermission
    private var library=onLibrary
    private var content:PermissionContent?=null
    val hitLayouts:List<NativeViewPointerHitLayout> get()=content?.hitLayouts?:emptyList()
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;clipToPadding=false}

    fun bind(permissionsGranted:Boolean,showPermissionDenied:Boolean,
             onReviewPermission:()->Unit=review,onLibrary:()->Unit=library) {
        review=onReviewPermission;library=onLibrary
        val show=!permissionsGranted&&!showPermissionDenied
        if(show&&content==null) {
            content=PermissionContent(context,host,{review()},{library()}).also {
                it.bindEnvironment(environment);addView(it)
            }
        } else if(!show) {
            content?.let {removeView(it);it.close()};content=null
        }
        content?.bindPermissions(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,
            !requiresLegacyPublicStoragePermission() ||
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED)
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        environment=next;content?.bindEnvironment(next)
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h)
        content?.measure(permissionExact(width),permissionExact(height))
        setMeasuredDimension(width,height)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {content?.layout(0,0,width,height)}
    override fun addChildrenForAccessibility(out:ArrayList<View>) {
        content?.takeIf {host.sourceNode(it).present}?.let(out::add)
    }
    override fun close() {content?.let {removeView(it);it.close()};content=null;review={};library={}}
}

private class PermissionContent(context:Context,private val host:NativeMaterialButtonHost,
                                review:()->Unit,library:()->Unit):NativeRectangularViewport(context),AutoCloseable {
    private var environment=host.environment
    private val message=ParagraphView(context,environment.appearance.typography.defaultTextStyle,environment.textDensity,
        environment.sharedFontResolver,environment.palette.onSurfaceVariant).apply {
        isScreenReaderFocusable=true
    }
    private val allow=LibrarySheetButton(context,host,true,context.getString(R.string.allow),review)
    private val libraryAction=PermissionLibraryAction(context,host,library)
    private val messageLease=message.bindPlainTextAccessibility(host.textWindow,host.textGeometry,
        sourceNode={host.sourceNode(message)}) {host.sourceNode(message).present}
    private val outerClipLease=host.registerTextClip(this)
    private var columnWidth=0
    private var columnHeight=0
    private var gap=0
    private var libraryOuterHeight=0
    private val shield=object:NativeViewPhasePointerNode {
        override val view:View get()=this@PermissionContent
        override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass)=Unit
        override fun cancel()=Unit
    }
    private val hitChildren=java.util.Collections.unmodifiableList(listOf(libraryAction.hitLayout,allow.hitLayout))
    val hitLayout=host.hit(this,listOf(shield),children={hitChildren},
        clip={containsViewportPoint(it.x,it.y)})
    val hitLayouts=listOf(hitLayout)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;isScreenReaderFocusable=false
        addView(message);addView(allow);addView(libraryAction)
    }
    fun bindPermissions(microphoneAllowed:Boolean,legacyStorageAllowed:Boolean) {
        message.setText(context.getString(requiredPermissionMessageResource(microphoneAllowed,legacyStorageAllowed)))
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        message.setTypography(next.appearance.typography.defaultTextStyle,next.textDensity,next.sharedFontResolver)
        message.animateTextColor(next.palette.onSurfaceVariant)
        allow.bindEnvironment(next);libraryAction.bindEnvironment(next)
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h)
        val pad=dp(24)
        val insideWidth=max(0,width-pad-pad);val insideHeight=max(0,height-pad-pad)
        message.measure(permissionAtMost(insideWidth),permissionAtMost(insideHeight))
        gap=min(dp(12),max(0,insideHeight-message.measuredHeight))
        allow.measure(permissionAtMost(insideWidth),permissionAtMost(max(0,insideHeight-message.measuredHeight-gap)))
        columnWidth=min(width,max(message.measuredWidth,allow.measuredWidth)+pad+pad)
        columnHeight=min(height,message.measuredHeight+gap+allow.measuredHeight+pad+pad)
        val bottom=dp(22)
        libraryAction.measure(permissionExact(min(dp(libraryAction.widthDp),width)),
            permissionExact(min(dp(libraryAction.heightDp),max(0,height-bottom))))
        libraryOuterHeight=min(height,libraryAction.measuredHeight+bottom)
        setMeasuredDimension(width,height)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val pad=dp(24)
        val columnLeft=((width-columnWidth)*.5f).roundToInt()
        val columnTop=((height-columnHeight)*.5f).roundToInt()
        val innerWidth=max(0,columnWidth-pad-pad)
        fun place(view:View,y:Int) {
            val x=columnLeft+pad+((innerWidth-view.measuredWidth)*.5f).roundToInt()
            view.layout(x,y,x+view.measuredWidth,y+view.measuredHeight)
        }
        place(message,columnTop+pad)
        place(allow,columnTop+pad+message.measuredHeight+gap)
        val x=((width-libraryAction.measuredWidth)*.5f).roundToInt()
        val y=height-libraryOuterHeight
        libraryAction.layout(x,y,x+libraryAction.measuredWidth,y+libraryAction.measuredHeight)
    }
    override fun addChildrenForAccessibility(outChildren:ArrayList<View>) {
        val start=outChildren.size
        super.addChildrenForAccessibility(outChildren)
        if(start==outChildren.size)return
        val admit=host.admitChildren
        if(admit!=null) {
            // Main owns the bound action nodes; wrappers map to those nodes in one capture.
            admit(outChildren,start) {child->when {
                child===allow->allow.accessibilitySemanticView
                child===libraryAction->libraryAction.accessibilitySemanticView
                else->child
            }}
            return
        }
        for(index in outChildren.lastIndex downTo start) {
            val child=outChildren[index]
            val present=when {
                child===allow->allow.isAccessibilityNodePresent()
                child===libraryAction->libraryAction.isAccessibilityNodePresent()
                child is NativeMergedRowAccessibilityHost->child.mergedRowAccessibility.isNodePresent()
                else->host.sourceNode(child).present
            }
            if(!present)outChildren.removeAt(index)
        }
    }
    override fun onTouchEvent(event:MotionEvent)=true // Released transparent non-clickable Surface shield.
    override fun getAccessibilityClassName():CharSequence="android.view.View"
    override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=
        if(host.sourceNode(this).present)super.createAccessibilityNodeInfo()else null
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.view.View";info.isClickable=false;info.isLongClickable=false
        info.isFocusable=false;info.isFocused=false;info.isScreenReaderFocusable=false;info.isEnabled=true
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if(isAccessibilityFocused)AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        host.sourceNode(this).populate(info)
    }
    override fun close() {outerClipLease.close();libraryAction.close();allow.close();messageLease.close()}
    private fun dp(value:Int)=(value*environment.textDensity.density).roundToInt()
}

/** Source54dp Surface:18dp shape, surfaceContainerHigh and25dp described Library icon. */
private class PermissionLibraryAction(context:Context,private val host:NativeMaterialButtonHost,
                                      action:()->Unit):ViewGroup(context),AutoCloseable {
    private var environment=host.environment
    private val recipe = environment.appearance.buttons.largeRaisedIcon
    val widthDp get() = recipe.surface.minimumWidthDp
    val heightDp get() = recipe.surface.minimumHeightDp
    private val surface=NativeGroupLayout(context, requireNotNull(recipe.surface.radiusDp),
        onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    private val glyph = OnboardingIconView(context, ReverbIcon.Library, recipe.contentColor(environment.palette.buttonPalette, true)).apply {
        contentDescription = context.getString(R.string.files_tab)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isScreenReaderFocusable = false
    }
    private val visual = NativeMaterialButtonView(context, recipe,
        environment.textDensity, environment.sharedFontResolver,
        NativeButtonInputPolicy(inputRoot = { host.inputRoot }), action).apply {
        setLeadingView(glyph, exposeToAccessibility = true)
    }
    private val click = NativeClickPhaseNode(visual, visual.nativeClickEndpoint, host.interestedParent)
    private val clipLease=host.registerTextClip(surface)
    private val parentLease=if(host.sourceProvider!=null) {
        visual.mergedRowAccessibility.bindSourceGeometry(host.textGeometry,host.sourceProvider) {
            !host.sourceNode(visual).hidden
        }
    } else visual.mergedRowAccessibility.bindGeometry(host.textGeometry,
        {host.sourceNode(visual,visual.minimumTouchTargetDp*environment.textDensity.density)},{host.sourceNode(it).present},
        {!host.sourceNode(visual).hidden})
    private val glyphLease=glyph.bindDescriptionAccessibility(host.textWindow,
        {host.sourceNode(glyph)},{!host.sourceNode(glyph).hidden})
    val hitLayout=run {
        val children=listOf(host.hit(visual,listOf(click),clip={surface.containsVisualPoint(it.x,it.y)}))
        host.hit(surface,emptyList(),children={children},
            clip={surface.containsVisualPoint(it.x,it.y)})
    }
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;clipToPadding=false
        surface.setPalette(recipe.surroundingColor(environment.palette.buttonPalette),Color.TRANSPARENT)
        surface.addRow(visual,NativeGroupLayout.LayoutParams(-1,-1))
        addView(surface)
        visual.bind("", true, environment.palette.buttonPalette)
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        surface.setPalette(recipe.surroundingColor(next.palette.buttonPalette),Color.TRANSPARENT)
        glyph.bind(ReverbIcon.Library, recipe.contentColor(next.palette.buttonPalette, true))
        visual.bindTypography(next.textDensity, next.sharedFontResolver)
        visual.bind("", true, next.palette.buttonPalette)
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(w:Int,h:Int){surface.measure(w,h);setMeasuredDimension(surface.measuredWidth,surface.measuredHeight)}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){surface.layout(0,0,width,height)}
    val accessibilitySemanticView:View get()=visual
    fun isAccessibilityNodePresent():Boolean=visual.mergedRowAccessibility.isNodePresent()
    override fun close() {
        var failure = closePreservingPrimaryFailure(null, click::cancel)
        for (lease in listOf(visual, glyphLease, parentLease, clipLease)) {
            failure = closePreservingPrimaryFailure(failure, lease::close)
        }
        failure?.let { throw it }
    }
}

private fun permissionExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY)
private fun permissionAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.AT_MOST)
