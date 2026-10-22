package app.smallthingz.reverb.nativeui

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect as ComposeRect
import kotlin.math.max

/** One cursor per actual sheet Window; bodies explicitly share it instead of creating another. */
internal class NativeActionSheetAccessibility(
    private val root:View,
    private val surface:NativeSheetSurface,
    private val scrim:SheetScrim,
    private val handle:NativeSheetDragHandle,
    private val app:NativeSheetAppContent,
):AutoCloseable {
    val cursor=PlainTextAccessibilityWindowScope(root)
    var extraClip:(View)->NativeTextSemanticsClip = {NativeTextSemanticsClip.Unclipped}
    val geometry=NativePlainTextViewGeometry(root,NativeTextSemanticsClipPolicy {view->
        when {
            view===surface.painted.clip||view is NativeVerticalViewport->
                NativeTextSemanticsClip.Rectangle(ComposeRect(0f,0f,view.width.toFloat(),view.height.toFloat()))
            else->extraClip(view)
        }
    })
    private val leases=ArrayList<AutoCloseable>()
    init {
        if(surface.id==View.NO_ID)surface.id=View.generateViewId()
        if(scrim.id==View.NO_ID)scrim.id=View.generateViewId()
        scrim.accessibilityTraversalAfter=surface.id
        app.titleView.isScreenReaderFocusable=true
        leases+=app.titleView.bindPlainTextAccessibility(cursor,geometry,
            sourceNode={sourceNode(app.titleView)}) {sourceNode(app.titleView).present}
        leases+=app.close.bindAccessibility(cursor,geometry) {view->
            sourceNode(view,if(view===app.close.visual)app.close.minimumTouchTargetPx else 0f)
        }
        handle.sourceNode={sourceNode(handle,app.minimumTouchTargetPx)}
        handle.mark.sourceNode={sourceNode(handle.mark)}
        surface.sourceChildPresent={view->when {
            view is NativeMergedRowAccessibilityHost->view.mergedRowAccessibility.isNodePresent()
            view===handle->handle.sourceNode?.invoke()?.present!=false
            else->sourceNode(view).present
        }}
        leases+=bindDescription(handle.mark,speaking=false,click=false)
        leases+=bindDescription(scrim,speaking=true,click=true)
        for(view in listOf(root,surface))view.accessibilityDelegate=object:View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.className="android.view.View";info.isClickable=false;info.isLongClickable=false
                info.isFocusable=false;info.isFocused=false;info.isEnabled=true
                info.isScreenReaderFocusable=false
                info.actionList.toList().forEach(info::removeAction)
                info.addAction(if(host.isAccessibilityFocused)
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
                sourceNode(host).populate(info)
            }
        }
    }
    fun sourceNode(view:View,minimumTouch:Float=0f):NativeSourceAccessibilityNode {
        if(!view.isAttachedToWindow)return NativeSourceAccessibilityNode.absent()
        var current:View?=view
        var hidden=false
        while(current!=null) {
            if(current.visibility!=View.VISIBLE)return NativeSourceAccessibilityNode.absent()
            if(current.alpha==0f)hidden=true
            if(current===root)break
            current=current.parent as?View
        }
        if(current!==root)return NativeSourceAccessibilityNode.absent()
        val x=max(0f,minimumTouch-view.width)*.5f
        val y=max(0f,minimumTouch-view.height)*.5f
        val semanticHeight=if(view is SheetRoot)view.sourceSemanticsHeight else view.height
        val capture=geometry.capture(view,Offset.Zero,
            ComposeRect(-x,-y,view.width+x,semanticHeight+y))?:return NativeSourceAccessibilityNode.absent()
        val bounds=nativeMergedRoleBoundsInScreen(capture,view.isLaidOut)
        return NativeSourceAccessibilityNode(!bounds.isEmpty,hidden,!hidden&&!bounds.isEmpty,bounds)
    }
    private fun bindDescription(view:View,speaking:Boolean,click:Boolean):AutoCloseable {
        val helper=PlainTextAccessibility(view,cursor,
            {PlainTextAccessibilitySnapshot(view.contentDescription?.toString().orEmpty(),{null},{null})},
            {sourceNode(view).present},descriptionOnly=true)
        view.accessibilityDelegate=object:View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                helper.populate(info)
                val facts=sourceNode(host)
                info.isClickable=click;info.isLongClickable=false;info.isFocusable=false;info.isFocused=false
                info.isScreenReaderFocusable=speaking&&!facts.hidden
                facts.populate(info)
                if(click)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean {
                if(!sourceNode(host).present)return false
                return helper.performAction(action,args)?:when(action) {
                    AccessibilityNodeInfo.ACTION_CLICK->if(click){host.performClick();true}else false
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS->
                        super.performAccessibilityAction(host,action,args)
                    else->false
                }
            }
        }
        return AutoCloseable {view.accessibilityDelegate=null}
    }
    override fun close() {
        leases.forEach(AutoCloseable::close);leases.clear()
        handle.sourceNode=null
        handle.mark.sourceNode=null;surface.sourceChildPresent=null
        root.accessibilityDelegate=null;surface.accessibilityDelegate=null
    }
}
