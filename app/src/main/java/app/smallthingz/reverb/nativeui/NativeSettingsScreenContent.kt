package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.reverb.settings.ReverbSettingsScreenLayout
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.lifecycle.Lifecycle
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
    private val animationScope:CoroutineScope,
    val nestedScroll:NativeVerticalNestedHost,
    parentInterested:(NativeDragPointerChange)->Boolean,
    restoredPosition:SettingsListSavedPosition?=null,
    private val changedNodes:()->Unit,
):ReverbSettingsScreenLayout(context, initialEnvironment.forSettings(), frames, animationScope),
    AutoCloseable, NativeSourceFocusEntryBoundary {
    private val lifecycle=lifecycleOwner.lifecycle
    private var input=initial
    private var sourceEnvironment=initialEnvironment
    private var environment=initialEnvironment.forSettings()
    private var closed=false
    private var started=false
    private var publishing=false
    private var republish=false
    private var lastSections:SectionValues?=null
    private var lastTopBar:TopBarValues?=null
    private val tileEditorPublication=SettingsTileEditorPublication()
    private var list:SettingsSectionListView?=null
    private var savedPosition=restoredPosition
    private var pendingRetentionFocus=false
    private var focusKeyInitialized=false
    private var previousFocusBuffer:ReverbService.BufferSlot?=null
    val topBar=ReverbSettingsTopBarView(context,environment,animationScope) {inputRoot}
    private val deleteSheet=NativeSettingsTileDeleteSheet(this) {environment}
    private val sections=NativeSettingsSections(context,owner,{environment},lifecycleOwner.lifecycle,
        {inputRoot},animationScope,parentInterested,{input.focusRetention},
        deleteSheet::bind,::structureChanged)
    private val editorBack=object:OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {owner.navigateBack(owner.state.activation)}
    }.also {backDispatcher.addCallback(lifecycleOwner,it)}

    val pointerEnvironment get()=environment
    val sectionContent:SettingsSectionListView? get()=list
    val listViewport:ReverbLibraryRecyclerView? get()=list?.viewport
    val inputAllowed:Boolean get()=owner.acceptsInput()
    override val sourceFocusEntryAllowed:Boolean get()=inputAllowed
    val gateNode=object:NativeViewPhasePointerNode {
        override val view:View get()=this@NativeSettingsScreenContent
        override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass) {
            if(pass==NativePointerPass.INITIAL&&!inputAllowed)event.changes.forEach {it.consume()}
        }
        override fun cancel()=Unit
    }

    init { installTopBar(topBar) }

    /** Start only after Main has installed its shared input/accessibility and Activity Result route. */
    fun start() {
        if(closed||started)return
        started=true
        owner.start(::publish)
    }
    fun bind(next:NativeSettingsScreenInput,nextEnvironment:ReverbViewEnvironment=sourceEnvironment) {
        if(closed)return
        if(!next.active||input.focusRetention!=next.focusRetention)dismissAudioChoices()
        input=next
        if(sourceEnvironment!==nextEnvironment) {
            sourceEnvironment=nextEnvironment
            environment=nextEnvironment.forSettings()
        }
        owner.setActive(next.active)
        if(started)publish()
    }
    fun dismissAudioChoices() = sections.dismissAudioChoices()
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {
        if(!hasWindowFocus)dismissAudioChoices()
        super.onWindowFocusChanged(hasWindowFocus)
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
        if(!inputAllowed||!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))dismissAudioChoices()
        editorBack.isEnabled=state.active&&(state.hasUnsavedChanges||state.persisting||
            owner.quickTileEditorState.editorOpen||owner.calibration.running||
            owner.notificationDurationInteraction.pending)
        val focusBuffer=input.focusRetention
        if(!focusKeyInitialized||previousFocusBuffer!=focusBuffer) {
            focusKeyInitialized=true
            previousFocusBuffer=focusBuffer
            if(input.focusRetention!=null)pendingRetentionFocus=true
        }
        val bar=TopBarValues(state.activation,state.hasUnsavedChanges,state.persisting,
            owner.calibration.running,owner.quickTileEditorState.editorOpen,
            owner.notificationDurationInteraction.pending,owner.backHint,environment)
        if(lastTopBar!=bar) {
            lastTopBar=bar
            topBar.bind(environment,SettingsTopBarBinding(bar.changed,bar.persisting,bar.calibrating,
                bar.tileOpen,bar.durationPending,{owner.navigationAction(bar.activation)},{owner.done(bar.activation)},
                bar.backHint))
        }
        val insets = NativeTopBarInsets(input.insets.left,input.insets.top,input.insets.right)
        topBar.setRemainingWindowInsets(insets)
        bindSettingsSurface(environment, insets, input.insets.bottom, inputAllowed, state.hydrationPending)
        if(!state.hydrated) {
            list?.let {
                savedPosition = it.savePosition()
                setSettingsList(null)
                it.close()
            }
            list=null
            lastSections=null
            tileEditorPublication.reset()
            structureChanged()
            return
        }
        val currentList=list?:SettingsSectionListView(context,sections,environment,
            true,savedPosition,::structureChanged).also {
            list=it;setSettingsList(it);lastSections=null;structureChanged()
        }
        val sectionValues=SectionValues(state,owner.errors,owner.exportPathText,owner.canMove,
            owner.settingsCommitBlocked,owner.batteryOptimizationRestricted,owner.resumeNotificationBlocked,owner.restartAccessMissing,
            owner.allFilesAllowed,owner.audioReadAllowed,owner.autoStartChoice,environment,input.focusRetention)
        val tileEditorChanged=tileEditorPublication.changed(owner.quickTileEditorState)
        if(lastSections!=sectionValues) {
            lastSections=sectionValues
            currentList.bind(owner.batteryOptimizationRestricted || owner.resumeNotificationBlocked ||
                owner.restartAccessMissing,environment)
        } else {
            // Back can close the child without changing the parent draft or firing the tile owner.
            if(tileEditorChanged)currentList.bindSection(SettingsSectionKey.QUICK_TILES)
            sections.refreshCalibration()
        }
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
    fun sourceClip(view: View): NativeTextSemanticsClip = topBar.sourceClip(view) ?: settingsSourceClip(view)

    override fun onSettingsLayoutChanged() = structureChanged()

    override fun addFocusables(views:ArrayList<View>,direction:Int,focusableMode:Int) {
        if(inputAllowed)super.addFocusables(views,direction,focusableMode)
    }
    override fun onRequestFocusInDescendants(direction:Int,previouslyFocusedRect:Rect?):Boolean =
        inputAllowed&&super.onRequestFocusInDescendants(direction,previouslyFocusedRect)

    override fun close() {
        if(closed)return
        closed=true
        editorBack.remove()
        var failure:Throwable?=null
        fun retire(action:()->Unit) {try{action()}catch(error:Throwable){
            if(failure==null)failure=error else if(failure!==error)failure!!.addSuppressed(error)
        }}
        retire(deleteSheet::close)
        retire { list?.close() };list=null
        retire(topBar::close)
        retire(::releaseSettingsSurface)
        retire(owner::close)
        failure?.let {throw it}
    }
    private data class SectionValues(val state:SettingsDraftPresentation,val errors:NativeSettingsRetentionErrors,
        val path:String,val canMove:Boolean,val commitBlocked:Boolean,val batteryRestricted:Boolean,
        val notificationBlocked:Boolean,val restartAccessMissing:Boolean,
        val allFilesAllowed:Boolean,val audioReadAllowed:Boolean,val autoStartChoice:AutoStartChoice,
        val environment:ReverbViewEnvironment,
        val focus:ReverbService.BufferSlot?)
    private data class TopBarValues(val activation:Any,val changed:Boolean,val persisting:Boolean,
        val calibrating:Boolean,val tileOpen:Boolean,val durationPending:Boolean,val backHint:Any?,
        val environment:ReverbViewEnvironment)
}
