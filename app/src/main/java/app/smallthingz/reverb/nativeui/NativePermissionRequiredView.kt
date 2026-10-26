package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import app.smallthingz.reverb.R
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
                                review:()->Unit,library:()->Unit):ViewGroup(context),AutoCloseable {
    private var environment=host.environment
    private val message=ReverbParagraphView(context,reverbDefaultTextStyle(),environment.textDensity,
        environment.sharedFontResolver,environment.palette.onSurfaceVariant).apply {
        setText(context.getString(R.string.permission_required_message))
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
        clip={it.x>=0f&&it.y>=0f&&it.x<width&&it.y<height})
    val hitLayouts=listOf(hitLayout)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;isScreenReaderFocusable=false
        clipChildren=false;clipToPadding=false
        clipToOutline=true
        outlineProvider=object:ViewOutlineProvider() {
            override fun getOutline(view:View,outline:Outline){outline.setRect(0,0,view.width,view.height)}
        }
        addView(message);addView(allow);addView(libraryAction)
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        message.setTypography(reverbDefaultTextStyle(),next.textDensity,next.sharedFontResolver)
        message.setTextColor(next.palette.onSurfaceVariant)
        allow.bindEnvironment(next);libraryAction.bindEnvironment(next)
        if(densityChanged)requestLayout()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){invalidateOutline()}
    override fun draw(canvas:Canvas) {
        if(canvas.isHardwareAccelerated)super.draw(canvas)
        else {val saved=canvas.save();try {canvas.clipRect(0,0,width,height);super.draw(canvas)}
            finally {canvas.restoreToCount(saved)}}
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
        libraryAction.measure(permissionExact(min(dp(54),width)),permissionExact(min(dp(54),max(0,height-bottom))))
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
        info.actionList.toList().forEach(info::removeAction)
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
    private val surface=ReverbGroupLayout(context,18f)
    private val visual=LibraryVisual(context)
    private var action:(()->Unit)?=action
    private val indication=ReverbRowIndication.install(visual,environment.palette.onSurface,
        android.animation.ValueAnimator::getDurationScale)
    private val controller=ReverbClickController(visual,object:NativeClickEnvironment {
        override val nativeInputRoot:View get()=host.inputRoot
        override val minimumTouchWidthPx get()=48f*environment.textDensity.density
        override val minimumTouchHeightPx get()=minimumTouchWidthPx
        override fun admitDown(event:MotionEvent)=reverbGroupGeometry(visual.width.toFloat(),visual.height.toFloat(),
            environment.textDensity.density,18f).acceptsPointer(event.x,event.y,
                event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER,minimumTouchWidthPx)
        override fun interestedParent(event:MotionEvent)=false
        override fun longPressOwnershipChanged(owns:Boolean)=Unit
    },ReverbRowIndicationSink(indication){x,y,p->p.set(x,y)})
    private val endpoint=ReverbClickPhaseEndpoint(controller)
    private val click=NativeClickPhaseNode(visual,endpoint,host.interestedParent)
    private val clipLease=host.registerTextClip(surface)
    private val parentLease=if(host.sourceProvider!=null) {
        visual.mergedRowAccessibility.bindSourceGeometry(host.textGeometry,host.sourceProvider) {
            !host.sourceNode(visual).hidden
        }
    } else visual.mergedRowAccessibility.bindGeometry(host.textGeometry,
        {host.sourceNode(visual,48f*environment.textDensity.density)},{host.sourceNode(it).present},
        {!host.sourceNode(visual).hidden})
    private val glyphLease=visual.glyph.bindDescriptionAccessibility(host.textWindow,
        {host.sourceNode(visual.glyph)},{!host.sourceNode(visual.glyph).hidden})
    val hitLayout=host.hit(surface,emptyList(),children={
        listOf(host.hit(visual,listOf(click),clip={surface.containsVisualPoint(it.x,it.y)}))
    },clip={surface.containsVisualPoint(it.x,it.y)})
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false;clipToPadding=false
        surface.setPalette(environment.palette.surfaceContainerHigh,Color.TRANSPARENT)
        surface.addRow(visual,ReverbGroupLayout.LayoutParams(-1,-1))
        addView(surface)
        controller.bind(NativeClickBinding(this,true,{visual.performClick()}))
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next
        surface.setPalette(next.palette.surfaceContainerHigh,Color.TRANSPARENT)
        visual.glyph.bind(ReverbIcon.Library,next.palette.onSurfaceVariant)
        indication.updateColor(next.palette.onSurface)
        if(densityChanged)requestLayout()
    }
    override fun onMeasure(w:Int,h:Int){surface.measure(w,h);setMeasuredDimension(surface.measuredWidth,surface.measuredHeight)}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){surface.layout(0,0,width,height)}
    val accessibilitySemanticView:View get()=visual
    fun isAccessibilityNodePresent():Boolean=visual.mergedRowAccessibility.isNodePresent()
    override fun close(){action=null;click.cancel();controller.detached();glyphLease.close();parentLease.close();clipLease.close()}

    private inner class LibraryVisual(context:Context):ViewGroup(context),NativeClickPhaseHost,
        NativePointerLayerShape,NativeMergedRowAccessibilityHost {
        val glyph=OnboardingIconView(context,ReverbIcon.Library,environment.palette.onSurfaceVariant).apply {
            contentDescription=context.getString(R.string.files_tab)
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;isScreenReaderFocusable=false
        }
        private val accessibilityChildren=listOf(glyph)
        override val nativeClickEndpoint get()=endpoint
        override val mergedRowAccessibility=ReverbMergedRowAccessibility(this,NativeMergedRowRole.BUTTON,
            {NativeMergedRowAccessibilityState(true,null,null,accessibilityChildren)},{activate()})
        init {
            isClickable=true;isFocusable=true;descendantFocusability=FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(glyph);addView(mergedRowAccessibility.roleView);accessibilityDelegate=mergedRowAccessibility
        }
        private fun activate():Boolean {val callback=action?:return false;callback();return true}
        override fun performClick():Boolean {if(action==null)return false;super.performClick();return activate()}
        override fun containsPointerLayer(x:Float,y:Float)=surface.containsVisualPoint(x,y)
        override fun onMeasure(w:Int,h:Int) {
            val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h)
            val icon=(25f*environment.textDensity.density).roundToInt()
            glyph.measure(permissionExact(min(icon,width)),permissionExact(min(icon,height)))
            mergedRowAccessibility.roleView.measure(permissionExact(width),permissionExact(height))
            setMeasuredDimension(width,height)
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val x=((width-glyph.measuredWidth)*.5f).roundToInt();val y=((height-glyph.measuredHeight)*.5f).roundToInt()
            glyph.layout(x,y,x+glyph.measuredWidth,y+glyph.measuredHeight)
            mergedRowAccessibility.roleView.layout(0,0,width,height)
            indication.onMeasured(width,height,environment.textDensity.density)
        }
        override fun addChildrenForAccessibility(out:ArrayList<View>)=mergedRowAccessibility.addChildrenForAccessibility(out)
        override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=
            if(mergedRowAccessibility.isNodePresent())super.createAccessibilityNodeInfo()else null
        override fun onTouchEvent(event:MotionEvent)=controller.touch(event)
        override fun dispatchKeyEvent(event:KeyEvent)=when(event.keyCode) {
            KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_SPACE->
                controller.key(event)
            else->super.dispatchKeyEvent(event)
        }
        override fun onHoverEvent(event:MotionEvent):Boolean {val native=super.onHoverEvent(event);return controller.hover(event)||native}
        override fun onFocusChanged(focused:Boolean,direction:Int,old:Rect?) {
            super.onFocusChanged(focused,direction,old);controller.focusChanged(focused)
        }
        override fun onAttachedToWindow(){super.onAttachedToWindow();controller.attached();controller.focusChanged(isFocused)}
        override fun onDetachedFromWindow(){controller.detached();super.onDetachedFromWindow()}
        override fun getAccessibilityClassName():CharSequence="android.view.View"
    }
}

private fun permissionExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY)
private fun permissionAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.AT_MOST)
