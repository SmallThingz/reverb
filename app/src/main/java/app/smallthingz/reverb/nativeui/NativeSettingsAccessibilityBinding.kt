package app.smallthingz.reverb.nativeui

import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.NativeWindowScrollAccessibilityEvents
import java.util.IdentityHashMap

/** Finite Settings leases on Main's existing cursor; no second accessibility window owner. */
internal class NativeSettingsAccessibilityBinding(
    private val content:NativeSettingsScreenContent,
    private val window:NativeWindowTextAccessibilityOwner,
    private val sourceNode:(View)->NativeSourceAccessibilityNode,
    private val scrollEvents:NativeWindowScrollAccessibilityEvents,
    private val admitChildren:((ArrayList<View>,Int,(View)->View?)->Unit)?=null,
):AutoCloseable {
    private val clips=NativeTextSemanticsClipPolicy(content::sourceClip)
    private val topBar=window.bindSettingsTopBar(content.topBar,content,clips,sourceNode)
    private var sectionView:SettingsSectionListView?=null
    private var sections:NativePageTextAccessibilityOwner?=null
    private var listViewport:ReverbLibraryRecyclerView?=null
    private var listScroll:NativeLibraryRecyclerAccessibility?=null
    private val progress=IdentityHashMap<SettingsCalibrationProgressView,AutoCloseable>()
    private var closed=false
    fun refresh() {
        if(closed)return
        val next=content.sectionContent
        if(sectionView!==next) {
            sections?.close()
            sectionView=next
            sections=next?.let {window.bindContent(it,clips,sourceNode) {view->
                content.inputAllowed&&!sourceNode(view).hidden
            }}
        }else sections?.refresh()
        // Settings has only the hydrated LazyColumn. Its pre-hydration Box has no scroll node.
        val nextViewport=content.listViewport
        if(listViewport!==nextViewport) {
            listScroll?.close();listScroll=null
            listViewport=nextViewport
            listScroll=nextViewport?.let {NativeLibraryRecyclerAccessibility(it,sourceNode,scrollEvents,admitChildren)}
        }
        val present=IdentityHashMap<SettingsCalibrationProgressView,Boolean>()
        fun visit(view:View) {
            if(view is SettingsCalibrationProgressView)present[view]=true
            if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
        }
        next?.let(::visit)
        progress.keys.filter {!present.containsKey(it)}.forEach {progress.remove(it)?.close()}
        for(view in present.keys)if(!progress.containsKey(view))progress[view]=
            window.bindSettingsProgress(view,content,clips) {content.inputAllowed&&!sourceNode(view).hidden}
    }
    override fun close() {
        if(closed)return
        closed=true
        var failure:Throwable?=null
        fun release(action:()->Unit){try{action()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        release(topBar::close);release {sections?.close()};sections=null;sectionView=null
        release {listScroll?.close()};listScroll=null;listViewport=null
        progress.values.forEach {release(it::close)};progress.clear()
        failure?.let {throw it}
    }
}
