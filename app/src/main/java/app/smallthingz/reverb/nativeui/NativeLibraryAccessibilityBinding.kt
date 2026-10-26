/*
 * Lazy-list accessibility range adapted from AndroidX foundation/ui 1.11.2.
 * Copyright The Android Open Source Project. Apache License 2.0.
 */
package app.smallthingz.reverb.nativeui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.NativeAccessibilityScrollRange
import app.smallthingz.reverb.NativeScrollAccessibilityRegistration
import app.smallthingz.reverb.NativeVerticalScrollAccessibilityBinding
import app.smallthingz.reverb.NativeWindowScrollAccessibilityEvents
import java.util.IdentityHashMap

/** Existing Views and the existing Main cursor/scroll queue; no copied semantic or rendering tree. */
internal class NativeLibraryAccessibilityBinding(
    private val content:ReverbLibraryScreenContent,
    window:NativeWindowTextAccessibilityOwner,
    private val sourceNode:(View)->NativeSourceAccessibilityNode,
    private val scrollEvents:NativeWindowScrollAccessibilityEvents,
    private val admitChildren:((ArrayList<View>,Int,(View)->View?)->Unit)?=null,
):AutoCloseable {
    private val clips=NativeTextSemanticsClipPolicy(content::sourceClip)
    private val text=window.bindContent(content,clips,sourceNode){view->sourceNode(view).present}
    private val cursor=window.textWindow
    private val geometry=window.contentGeometry(content,clips)
    private val toolbarLeases=IdentityHashMap<View,AutoCloseable>()
    private var list:ReverbLibraryRecyclerView?=null
    private var listLease:NativeLibraryRecyclerAccessibility?=null
    private var empty:NativeVerticalViewport?=null
    private var emptyLease:AutoCloseable?=null
    private var closed=false

    fun refresh() {
        if(closed)return
        text.refresh() // Covers actual paragraph/merged-role nodes, including FineSeek puck/glyph.
        refreshToolbar()
        val nextList=content.listViewport
        if(list!==nextList) {
            listLease?.close();listLease=null;list=nextList
            listLease=nextList?.let {NativeLibraryRecyclerAccessibility(it,sourceNode,scrollEvents,admitChildren)}
        }
        val nextEmpty=content.emptyViewport
        if(empty!==nextEmpty) {
            emptyLease?.close();emptyLease=null
            empty?.let {it.sourceAccessibilityNode=null;it.sourceAccessibilityChildren=null}
            empty=nextEmpty
            if(nextEmpty!=null) {
                nextEmpty.sourceAccessibilityNode={sourceNode(nextEmpty)}
                nextEmpty.sourceAccessibilityChildren={sourceChildren(nextEmpty,sourceNode,admitChildren)}
                emptyLease=NativeVerticalScrollAccessibilityBinding(nextEmpty,scrollEvents){sourceNode(nextEmpty).present}
            }
        }
    }
    private fun refreshToolbar() {
        val present=java.util.Collections.newSetFromMap(IdentityHashMap<View,Boolean>())
        fun visit(view:View) {
            if(view is ReverbAppTopBarView || view is ReverbTopBarIconAction) {
                present.add(view)
                if(!toolbarLeases.containsKey(view))toolbarLeases[view]=when(view) {
                    is ReverbAppTopBarView->view.bindAccessibility(cursor,geometry,sourceNode)
                    is ReverbTopBarIconAction->view.bindAccessibility(cursor,geometry,sourceNode)
                    else->error("Unsupported toolbar leaf")
                }
                return // A full AppTopBar owns its optional Back and fixed action descriptions.
            }
            if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
        }
        visit(content.toolbarAccessibilityRoot)
        var failure:Throwable?=null
        val retired=toolbarLeases.keys.filter{it !in present}
        for(view in retired)try{toolbarLeases.remove(view)?.close()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }
        failure?.let{throw it}
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun release(action:()->Unit){try{action()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        release(text::close)
        val previousToolbar=toolbarLeases.values.toList();toolbarLeases.clear()
        previousToolbar.forEach{release(it::close)}
        release{listLease?.close()};listLease=null;list=null
        release{emptyLease?.close()};emptyLease=null
        empty?.let {it.sourceAccessibilityNode=null;it.sourceAccessibilityChildren=null};empty=null
        failure?.let{throw it}
    }
}

/** Source LazyLayout pseudo-offset; it intentionally does not estimate total pixel content height. */
internal fun nativeLibraryAccessibilityRange(firstIndex:Int,offset:Int,canScrollForward:Boolean):NativeAccessibilityScrollRange {
    val value=(offset+firstIndex*500).toFloat()
    return NativeAccessibilityScrollRange(value,if(canScrollForward)value+100f else value)
}

/** Native layout-only wrappers never become extra advertised children under the source LazyColumn. */
private fun sourceChildren(
    root:ViewGroup,
    sourceNode:(View)->NativeSourceAccessibilityNode,
    admitChildren:((ArrayList<View>,Int,(View)->View?)->Unit)?,
):List<View> {
    val children=ArrayList<View>()
    fun visit(view:View) {
        if(view.visibility!=View.VISIBLE || view.importantForAccessibility==View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)return
        if(view.isImportantForAccessibility)children.add(view)
        else if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
    }
    for(index in 0 until root.childCount)visit(root.getChildAt(index))
    if(children.isEmpty())return children
    if(admitChildren!=null) {
        // All candidates are semantic children. A missing node stays absent; unlike structural
        // wrappers, none may opt out of admission through a null semantic-child mapping.
        admitChildren(children,0) {it}
    }else {
        // Standalone callers retain their existing per-node presence contract.
        var write=0
        for(read in children.indices) {
            val child=children[read]
            if(sourceNode(child).present)children[write++]=child
        }
        if(write!=children.size)children.subList(write,children.size).clear()
    }
    return children
}

internal class NativeLibraryRecyclerAccessibility(
    private val view:ReverbLibraryRecyclerView,
    private val sourceNode:(View)->NativeSourceAccessibilityNode,
    private val window:NativeWindowScrollAccessibilityEvents,
    private val admitChildren:((ArrayList<View>,Int,(View)->View?)->Unit)?=null,
):View.OnAttachStateChangeListener,AutoCloseable {
    private var registration:NativeScrollAccessibilityRegistration?=null
    private val originalDelegate=ViewCompat.getAccessibilityDelegate(view)
    private var closed=false
    private fun range():NativeAccessibilityScrollRange {
        val manager=view.layoutManager as? LinearLayoutManager?:return nativeLibraryAccessibilityRange(0,0,false)
        val index=manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val item=manager.findViewByPosition(index)
        val offset=if(item==null)0 else (view.paddingTop-manager.getDecoratedTop(item)).coerceAtLeast(0)
        return nativeLibraryAccessibilityRange(index,offset,view.canScrollVertically(1))
    }
    private val delegate=object:AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host,info)
            val current=range()
            info.className="android.view.View" // CollectionInfo supplies the list role in the source.
            info.text=null;info.contentDescription=null
            info.isEnabled=true;info.isFocusable=false;info.isFocused=false
            info.isClickable=false;info.isLongClickable=false;info.isScreenReaderFocusable=false
            info.isSelected=false;info.isCheckable=false;info.isChecked=false
            info.isScrollable=current.maximum>0f
            info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                view.adapter?.itemCount?:0,1,false))
            val nativeInfo = info.unwrap()
            val actions = nativeInfo.actionList
            for (index in actions.size - 1 downTo 0) nativeInfo.removeAction(actions[index])
            info.addAction(if(view.isAccessibilityFocused)
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_ACCESSIBILITY_FOCUS)
            if(current.value<current.maximum) {
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN)
            }
            if(current.value>0f) {
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_UP)
            }
            sourceNode(view).populate(nativeInfo)
        }
        override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean {
            if(closed || !sourceNode(view).present)return false
            val direction=when(action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,android.R.id.accessibilityActionScrollDown->1
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,android.R.id.accessibilityActionScrollUp->-1
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS->return super.performAccessibilityAction(host,action,args)
                else->return false
            }
            val current=range()
            if(direction>0 && current.value>=current.maximum || direction<0 && current.value<=0f)return false
            val viewport=(view.height-view.paddingTop-view.paddingBottom).toFloat()
            return view.sourceSemanticsScrollBy?.invoke(direction*viewport)==true
        }
    }
    private val scroll=object:RecyclerView.OnScrollListener(){
        override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
            if(dy!=0)registration?.scrollChanged() else registration?.layoutChanged()
        }
    }
    private val layout=View.OnLayoutChangeListener{_,_,_,_,_,_,_,_,_->registration?.layoutChanged()}
    init {
        view.sourceAccessibilityNode={sourceNode(view)}
        view.sourceAccessibilityChildren={sourceChildren(view,sourceNode,admitChildren)}
        ViewCompat.setAccessibilityDelegate(view,delegate)
        view.sourceAccessibilityEventsBound=true
        view.addOnScrollListener(scroll);view.addOnLayoutChangeListener(layout)
        view.addOnAttachStateChangeListener(this)
        if(view.isAttachedToWindow)onViewAttachedToWindow(view)
    }
    override fun onViewAttachedToWindow(v:View) {
        if(closed || registration!=null)return
        registration=window.registerScroll(view,{sourceNode(view).present},{view},vertical=::range)
    }
    override fun onViewDetachedFromWindow(v:View){
        val previous=registration;registration=null;previous?.close()
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun release(action:()->Unit){try{action()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        release{view.removeOnAttachStateChangeListener(this)}
        release{view.removeOnScrollListener(scroll)}
        release{view.removeOnLayoutChangeListener(layout)}
        release{onViewDetachedFromWindow(view)}
        release{if(ViewCompat.getAccessibilityDelegate(view)===delegate)ViewCompat.setAccessibilityDelegate(view,originalDelegate)}
        view.sourceAccessibilityNode=null;view.sourceAccessibilityChildren=null
        view.sourceAccessibilityEventsBound=false
        failure?.let{throw it}
    }
}
