package app.smallthingz.reverb.nativeui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import app.smallthingz.reverb.*
import kotlinx.coroutines.CoroutineScope
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Settings' actual modifier path within the one Main window dispatcher. */
internal class NativeSettingsPointerBinding(
    private val content:NativeSettingsScreenContent,
    private val geometry:NativeWindowPointerCoordinates,
    private val frames:MonotonicFrameClock,
    private val scope:CoroutineScope,
    private val parentInterested:(NativeDragPointerChange)->Boolean,
    private val suppressMovementConsumption:(Boolean)->Unit={},
):AutoCloseable {
    private val layouts=IdentityHashMap<View,Layout>()
    private var listView:ReverbLibraryRecyclerView?=null
    private var scroll:NativeLibraryScrollOwner?=null
    private var scrollNodes=emptyList<NativeViewPhasePointerNode>()
    private val topBarNodes=listOf(content.topBar.touchShieldNode)
    private var closed=false
    private val density get()=content.pointerEnvironment.textDensity.density
    val layout:NativeViewPointerHitLayout=object:NativeViewPointerHitLayout {
        override val placed get()=!closed&&content.isAttachedToWindow&&content.isLaidOut&&content.visibility==View.VISIBLE
        override val size get()=Size(content.width.toFloat(),content.height.toFloat())
        override val minimumTouchTarget get()=Size(48f*density,48f*density)
        override val nodes=listOf(content.gateNode)
        override val childrenInHitOrder:List<NativeViewPointerHitLayout>
            get(){refresh();return children(content)}
        override fun screenToLocal(screen:Offset)=geometry.coordinates(content).local(screen)
        override fun withinLayerBounds(local:Offset)=true
    }
    fun refresh() {
        if(closed)return
        val next=content.listViewport
        if(next!==listView) {
            scroll?.close()
            listView=next
            scroll=next?.let {NativeLibraryScrollOwner(it,content.pointerEnvironment,frames,scope,
                content.nestedScroll,parentInterested)}
            scrollNodes=scroll?.let {java.util.Collections.unmodifiableList(listOf(it.observerNode,it.dragNode))}?:emptyList()
        }
        layouts.keys.filter {!it.isAttachedToWindow}.forEach {layouts.remove(it)?.close()}
    }
    fun pageKey(focused:View?,event:KeyEvent):Boolean {
        if(!content.inputAllowed)return false
        val viewport=listView?:return false
        if(event.keyCode!=KeyEvent.KEYCODE_PAGE_DOWN&&event.keyCode!=KeyEvent.KEYCODE_PAGE_UP)return false
        var current=focused
        while(current!=null&&current!==viewport)current=current.parent as?View
        if(current!==viewport)return false
        return scroll?.pageKey(event.keyCode==KeyEvent.KEYCODE_PAGE_DOWN,
            event.action==KeyEvent.ACTION_DOWN,event.isCtrlPressed)==true
    }
    private fun children(view:View):List<NativeViewPointerHitLayout> {
        if(view !is ViewGroup||isAndroidLeaf(view))return emptyList()
        val result=ArrayList<NativeViewPointerHitLayout>(view.childCount)
        for(index in view.childCount-1 downTo 0) {
            val child=view.getChildAt(index)
            if(child.visibility==View.VISIBLE)result+=layouts.getOrPut(child){Layout(child)}
        }
        return result
    }
    private fun interested(view:View,change:NativeDragPointerChange):Boolean {
        var current:View?=view
        while(current!=null&&current!==content) {
            if(current===listView)return scroll?.interested(change)==true||parentInterested(change)
            current=current.parent as?View
        }
        return parentInterested(change)
    }
    private fun isAndroidLeaf(view:View)=view is RangeDurationWheelView||view is SilenceCurveView||
        view is SettingsRetentionInputView
    private inner class Layout(private val view:View):NativeViewPointerHitLayout,AutoCloseable {
        private val click=(view as?NativeClickPhaseHost)?.let {
            NativeClickPhaseNode(view,it.nativeClickEndpoint){change->interested(view,change)}
        }
        private val interop=if(isAndroidLeaf(view))NativeAndroidInteropNode(view,
            (view.parent as NativeAndroidInteropHost).nativeInteropRequests,suppressMovementConsumption)else null
        private val shield=if(view is ReverbGroupLayout)object:NativeViewPhasePointerNode {
            override val view get()=this@Layout.view
            override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass)=Unit
            override fun cancel()=Unit
        }else null
        private val interopNodes=interop?.let(::listOf)?:emptyList()
        private val fallbackNodes=click?.let(::listOf)?:shield?.let(::listOf)?:emptyList()
        override val placed get()=view.isAttachedToWindow&&view.isLaidOut&&view.visibility==View.VISIBLE
        override val size get()=Size(view.width.toFloat(),view.height.toFloat())
        override val minimumTouchTarget get()=Size(48f*density,48f*density)
        override val childrenInHitOrder get()=children(view)
        override fun screenToLocal(screen:Offset)=geometry.coordinates(view).local(screen)
        override val nodes:List<NativeViewPhasePointerNode> get() {
            if(view===content.topBar)return topBarNodes
            if(view===listView)return scrollNodes
            if(interop!=null)return interopNodes
            val custom=content.pointerNodes(view)
            if(custom.isNotEmpty())return custom
            return fallbackNodes
        }
        override fun withinLayerBounds(local:Offset):Boolean=when {
            view is ReverbGroupLayout->view.containsVisualPoint(local.x,local.y)
            view is NativePointerLayerShape->view.containsPointerLayer(local.x,local.y)
            view===listView-> {
                val extra=(30f*density).roundToInt()
                local.x>=-extra&&local.x<view.width+extra&&local.y>=0f&&local.y<view.height
            }
            view is SettingsExpansionView||view===content.topBar.contentClipView->
                local.x>=0f&&local.x<view.width&&local.y>=0f&&local.y<view.height
            else->true
        }
        override fun close() {try{click?.cancel()}finally{interop?.close()}}
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun release(block:()->Unit){try{block()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        release {scroll?.close()};scroll=null;listView=null
        scrollNodes=emptyList()
        layouts.values.forEach {release(it::close)};layouts.clear()
        failure?.let {throw it}
    }
}
