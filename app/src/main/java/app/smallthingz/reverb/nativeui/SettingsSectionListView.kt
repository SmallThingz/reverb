package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

internal interface NativeSettingsSection:AutoCloseable {
    val view:View
    /** Reads the current screen snapshot; never performs persistence or canonicalization. */
    fun bind()
}
internal fun interface SettingsSectionFactory {
    fun create(key:SettingsSectionKey):NativeSettingsSection
}

/**
 * Finite Settings list host. The existing screen owns Scaffold insets, palette/noise, input admission,
 * draft/controller and section factories. This host owns item membership and scroll position only.
 * Native recycling/scroll dispatch still requires observable release comparison before cutover.
 */
internal class SettingsSectionListView(
    context:Context,
    private val factory:SettingsSectionFactory,
    private var environment:ReverbViewEnvironment,
    restricted:Boolean,
    restored:SettingsListSavedPosition?=null,
    private val onStructureChanged:()->Unit={},
):FrameLayout(context),AutoCloseable {
    private val pendingPosition=SettingsPendingScrollPosition(restored)
    private val manager:LinearLayoutManager=object:LinearLayoutManager(context,RecyclerView.VERTICAL,false) {
        override fun onLayoutChildren(recycler:RecyclerView.Recycler,state:RecyclerView.State) {
            if(!state.isPreLayout)pendingPosition.pendingForPlacement(state.itemCount)?.let {
                super.scrollToPositionWithOffset(it.firstVisibleItemIndex,-it.firstVisibleItemScrollOffset)
            }
            super.onLayoutChildren(recycler,state)
        }
        override fun onLayoutCompleted(state:RecyclerView.State) {
            super.onLayoutCompleted(state)
            if(!state.isPreLayout)pendingPosition.placed(placedPosition())
        }
    }
    private val rows=Rows(restricted)
    val viewport:ReverbLibraryRecyclerView get()=list
    private val list=ReverbLibraryRecyclerView(context).apply {
        layoutManager=manager;adapter=rows
        itemAnimator=null
        isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false
        clipToPadding=false
        setHasFixedSize(false)
    }
    private var closed=false
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(list,LayoutParams(-1,-1))
        applyPadding()
    }
    fun bind(restricted:Boolean,next:ReverbViewEnvironment) {
        check(!closed)
        environment=next;applyPadding()
        rows.membership(restricted)
        rows.bindAttached()
    }
    fun bindSection(key:SettingsSectionKey) {
        check(!closed)
        rows.bindAttached(key)
    }
    fun focusRetention() {
        // Released effect calls scrollToItem2/1 immediately when the focus-buffer owner changes.
        val index=SettingsSectionKey.retentionIndex(rows.restricted)
        list.stopScroll()
        pendingPosition.request(SettingsListSavedPosition(index,0))
        manager.scrollToPositionWithOffset(index,0)
    }
    fun savePosition():SettingsListSavedPosition = pendingPosition.save(placedPosition())

    private fun placedPosition():SettingsListSavedPosition? {
        val index=manager.findFirstVisibleItemPosition()
        if(index==RecyclerView.NO_POSITION)return null
        val item=manager.findViewByPosition(index)
        val offset=if(item==null)0 else (list.paddingTop-manager.getDecoratedTop(item)).coerceAtLeast(0)
        return SettingsListSavedPosition(index,offset)
    }
    private fun applyPadding() {
        list.setPadding(0,0,0,(24f*environment.textDensity.density).roundToInt())
    }
    override fun close() {
        if(closed)return
        closed=true
        list.stopScroll()
        list.adapter=null
        rows.close()
    }
    private inner class Rows(initialRestricted:Boolean):RecyclerView.Adapter<Holder>(),AutoCloseable {
        var restricted=initialRestricted
            private set
        private var keys=SettingsSectionKey.visible(initialRestricted)
        private val holders=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Holder,Boolean>())
        init {setHasStableIds(true)}
        fun membership(value:Boolean) {
            if(restricted==value)return
            val oldIndex=manager.findFirstVisibleItemPosition()
            val anchor=keys.getOrNull(oldIndex)
            val offset=manager.findViewByPosition(oldIndex)?.let {manager.getDecoratedTop(it)-list.paddingTop}
            restricted=value;keys=SettingsSectionKey.visible(value)
            if(value)notifyItemInserted(0)else notifyItemRemoved(0)
            if(anchor!=null&&offset!=null) {
                val index=keys.indexOf(anchor)
                if(index>=0) {
                    pendingPosition.request(SettingsListSavedPosition(index,(-offset).coerceAtLeast(0)))
                    manager.scrollToPositionWithOffset(index,offset)
                }
            }
        }
        fun bindAttached(key:SettingsSectionKey?=null) {
            for(index in 0 until list.childCount) {
                val holder=list.getChildViewHolder(list.getChildAt(index)) as? Holder?:continue
                if(key==null||holder.itemViewType==key.ordinal)holder.section.bind()
            }
        }
        override fun getItemCount()=keys.size
        override fun getItemId(position:Int)=keys[position].ordinal.toLong()
        override fun getItemViewType(position:Int)=keys[position].ordinal
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder {
            val section=factory.create(SettingsSectionKey.entries[viewType])
            check(section.view.parent==null) {"A Settings section factory must return an unparented owned View"}
            section.view.layoutParams=RecyclerView.LayoutParams(-1,-2)
            return Holder(section).also {holders.add(it)}
        }
        override fun onBindViewHolder(holder:Holder,position:Int)=holder.section.bind()
        override fun onViewAttachedToWindow(holder:Holder) {
            super.onViewAttachedToWindow(holder)
            // RV may reattach a cached holder without rebinding its unchanged stable item ID.
            holder.section.bind()
            onStructureChanged()
        }
        override fun onViewDetachedFromWindow(holder:Holder) {
            super.onViewDetachedFromWindow(holder)
            onStructureChanged()
        }
        override fun onViewRecycled(holder:Holder) {
            // Reuse retains the same keyed section type. No durable/draft operation is triggered.
            super.onViewRecycled(holder)
        }
        override fun close() {
            var failure:Throwable?=null
            holders.forEach {holder->
                try {holder.section.close()}catch(error:Throwable) {
                    if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
                }
            }
            holders.clear()
            failure?.let {throw it}
        }
    }
    private class Holder(val section:NativeSettingsSection):RecyclerView.ViewHolder(section.view)
}
