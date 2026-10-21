package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.MonotonicFrameClock
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.*
import kotlinx.coroutines.CoroutineScope

/** Remaining ScaffoldDefaults systemBars/displayCutout insets; the window owns consumption. */
internal data class NativeSettingsInsets(val left:Int=0,val top:Int=0,val right:Int=0,val bottom:Int=0)
internal data class NativeSettingsScreenInput(
    val active:Boolean,
    val focusRetention:ReverbService.BufferSlot?=null,
    val insets:NativeSettingsInsets=NativeSettingsInsets(),
)

/**
 * Released SettingsScreen's transparent Scaffold and seven keyed sections. The retained typed owner
 * performs hydration/drafts/persistence; this ordinary ViewGroup only assembles its current Views.
 * Main supplies its existing window pointer, accessibility, frame and nested-scroll owners.
 */
internal class NativeSettingsScreenContent(
    context:Context,
    val owner:NativeSettingsOwner,
    initial:NativeSettingsScreenInput,
    initialEnvironment:ReverbViewEnvironment,
    lifecycleOwner:LifecycleOwner,
    backDispatcher:OnBackPressedDispatcher,
    val inputRoot:View,
    frames:MonotonicFrameClock,
    animationScope:CoroutineScope,
    val nestedScroll:NativeVerticalNestedHost,
    parentInterested:(NativeDragPointerChange)->Boolean,
    restoredPosition:SettingsListSavedPosition?=null,
    private val changedNodes:()->Unit,
):ViewGroup(context),AutoCloseable {
    private var input=initial
    private var environment=initialEnvironment
    private var closed=false
    private var started=false
    private var publishing=false
    private var republish=false
    private var lastSections:SectionValues?=null
    private var lastTopBar:TopBarValues?=null
    private var list:SettingsSectionListView?=null
    private var savedPosition=restoredPosition
    private var pendingRetentionFocus=false
    private var previousFocusKey:Pair<ReverbService.BufferSlot?,Boolean>?=null
    val topBar=ReverbSettingsTopBarView(context,environment) {inputRoot}
    private val deleteSheet=NativeSettingsTileDeleteSheet(this) {environment}
    private val sections=NativeSettingsSections(context,owner,{environment},lifecycleOwner.lifecycle,
        {inputRoot},frames,animationScope,parentInterested,{input.focusRetention},
        deleteSheet::bind,::structureChanged)
    private val editorBack=object:OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {owner.closeTileEditorOnBack()}
    }.also {backDispatcher.addCallback(lifecycleOwner,it)}

    val pointerEnvironment get()=environment
    val sectionContent:SettingsSectionListView? get()=list
    val listViewport:ReverbLibraryRecyclerView? get()=list?.viewport
    val inputAllowed:Boolean get()=owner.acceptsInput()
    val gateNode=object:NativeViewPhasePointerNode {
        override val view:View get()=this@NativeSettingsScreenContent
        override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass) {
            if(pass==NativePointerPass.INITIAL&&!inputAllowed)event.changes.forEach {it.consume()}
        }
        override fun cancel()=Unit
    }

    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren=false;clipToPadding=false
        if(android.os.Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
    }

    /** Start only after Main has installed its shared input/accessibility and Activity Result route. */
    fun start() {
        if(closed||started)return
        started=true
        owner.start(::publish)
    }
    fun bind(next:NativeSettingsScreenInput,nextEnvironment:ReverbViewEnvironment=environment) {
        if(closed)return
        val geometryChanged=input.insets!=next.insets
        input=next;environment=nextEnvironment
        owner.setActive(next.active)
        if(started)publish()
        if(geometryChanged)requestLayout()
    }
    fun savePosition():SettingsListSavedPosition = list?.savePosition()
        ?:savedPosition?:SettingsListSavedPosition(0,0)

    private fun publish() {
        if(closed)return
        if(publishing) {republish=true;return}
        publishing=true
        try {
            do {
                republish=false
                render()
            } while(republish&&!closed)
        } finally {publishing=false}
    }
    private fun render() {
        val state=owner.state
        editorBack.isEnabled=state.active&&owner.quickTileEditorState.editorOpen
        importantForAccessibility=if(inputAllowed)IMPORTANT_FOR_ACCESSIBILITY_NO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val focusKey=input.focusRetention to owner.batteryOptimizationRestricted
        if(previousFocusKey!=focusKey) {
            previousFocusKey=focusKey
            if(input.focusRetention!=null)pendingRetentionFocus=true
        }
        if(!state.hydrated) {
            list?.let {savedPosition=it.savePosition();removeView(it);it.close()}
            list=null
            if(topBar.parent===this)removeView(topBar)
            lastSections=null;lastTopBar=null
            structureChanged()
            return
        }
        if(topBar.parent==null)addView(topBar)
        val currentList=list?:SettingsSectionListView(context,sections,environment,
            owner.batteryOptimizationRestricted,savedPosition,::structureChanged).also {
            list=it;addView(it,0);lastSections=null;structureChanged()
        }
        val sectionValues=SectionValues(state,owner.errors,owner.exportPathText,owner.canMove,
            owner.batteryOptimizationRestricted,environment,input.focusRetention)
        if(lastSections!=sectionValues) {
            lastSections=sectionValues
            currentList.bind(owner.batteryOptimizationRestricted,environment)
        } else sections.refreshCalibration()
        val bar=TopBarValues(state.activation,state.hasUnsavedChanges,state.persisting,
            owner.calibration.running,owner.quickTileEditorState.editorOpen,
            owner.notificationDurationInteraction.pending,environment)
        if(lastTopBar!=bar) {
            lastTopBar=bar
            topBar.bind(environment,SettingsTopBarBinding(bar.changed,bar.persisting,bar.calibrating,
                bar.tileOpen,bar.durationPending,{owner.navigateBack(bar.activation)},{owner.done(bar.activation)}))
        }
        topBar.setRemainingWindowInsets(NativeSettingsTopBarInsets(input.insets.left,input.insets.top,input.insets.right))
        if(pendingRetentionFocus) {
            pendingRetentionFocus=false
            currentList.focusRetention()
        }
    }
    private fun structureChanged() {
        if(closed)return
        changedNodes()
    }
    fun pointerNodes(view:View):List<NativeViewPhasePointerNode> = sections.pointerNodes(view)
    fun sourceClip(view:View):NativeTextSemanticsClip {
        topBar.sourceClip(view)?.let {return it}
        return when {
            view===listViewport||view is SettingsExpansionView->
                NativeTextSemanticsClip.Rectangle(androidx.compose.ui.geometry.Rect(0f,0f,view.width.toFloat(),view.height.toFloat()))
            view.parent is ReverbGroupLayout&&(view.parent as ReverbGroupLayout).pointerContent===view->
                NativeTextSemanticsClip.Rectangle(androidx.compose.ui.geometry.Rect(0f,0f,view.width.toFloat(),view.height.toFloat()))
            else->NativeTextSemanticsClip.Unclipped
        }
    }

    override fun addFocusables(views:ArrayList<View>,direction:Int,focusableMode:Int) {
        if(inputAllowed)super.addFocusables(views,direction,focusableMode)
    }
    override fun onRequestFocusInDescendants(direction:Int,previouslyFocusedRect:Rect?):Boolean =
        inputAllowed&&super.onRequestFocusInDescendants(direction,previouslyFocusedRect)

    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h)
        if(topBar.parent===this)topBar.measure(exact(width),atMost(height))
        val top=if(topBar.parent===this)topBar.measuredHeight else 0
        list?.measure(exact((width-input.insets.left-input.insets.right).coerceAtLeast(0)),
            exact((height-top-input.insets.bottom).coerceAtLeast(0)))
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        if(topBar.parent===this)topBar.layout(0,0,width,topBar.measuredHeight)
        list?.let {it.layout(input.insets.left,topBar.measuredHeight,
            input.insets.left+it.measuredWidth,topBar.measuredHeight+it.measuredHeight)}
        // Child expansion/membership can change while this root's own bounds stay identical.
        structureChanged()
    }
    override fun close() {
        if(closed)return
        closed=true
        editorBack.remove()
        var failure:Throwable?=null
        fun retire(action:()->Unit) {try{action()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        retire(deleteSheet::close)
        retire {list?.close()};list=null
        retire(topBar::cancelInput)
        retire(owner::close)
        failure?.let {throw it}
    }
    private data class SectionValues(val state:SettingsDraftPresentation,val errors:NativeSettingsRetentionErrors,
        val path:String,val canMove:Boolean,val restricted:Boolean,val environment:ReverbViewEnvironment,
        val focus:ReverbService.BufferSlot?)
    private data class TopBarValues(val activation:Any,val changed:Boolean,val persisting:Boolean,
        val calibrating:Boolean,val tileOpen:Boolean,val durationPending:Boolean,val environment:ReverbViewEnvironment)
}
