/*
 * Native specialization of ReverbSheet.kt and Material3 1.4.0 ModalBottomSheet.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.CubicBezierEasing
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.graphics.Color as ComposeColor
import smallthingz.ui.graphics.luminance
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePagerTouchRoot
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import app.smallthingz.reverb.NativeWindowScrollAccessibilityEvents
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One caller-owned sheet route. Direct title/action Close invokes the current owner immediately;
 * scrim/drag/Back use M3's respective hide-then-callback paths. No implicit busy/permission policy.
 *
 * Construct, create caller bodies against [contentContext], call [setContent], then show().
 * The owner calls dismiss() when its source route is removed. Bodies/actions rebind in place.
 */
internal class ReverbActionSheetDialog(
    private val anchor:View,
    initialEnvironment:ReverbViewEnvironment,
    title:String,
    onDismiss:()->Unit,
):ComponentDialog(ContextThemeWrapper(anchor.context,app.smallthingz.reverb.R.style.ReverbNativeSheetWindowTheme)) {
    var environment=initialEnvironment;private set
    private var onOwnerDismiss=onDismiss
    private var disposed=false
    private val runtime=NativeAboutWindowRuntime()
    private val anchorLifecycle=anchor.findViewTreeLifecycleOwner()?.lifecycle
    private var dismissalAllowed:()->Boolean={true}
    private var hasDismissalAdmission=false
    private var previousDismissalAllowed=true
    private val state=NativeActionSheetState({environment.textDensity.density},::stateChanged) {dismissalAllowed()}
    private val predictive=Animatable(0f)
    private val scrimAnimation=Animatable(0f)
    private val scrimTargets=Channel<Float>(Channel.CONFLATED)
    private var scrimTarget=0f
    private var showEffect:Job?=null
    private val pointer=SheetPointerOwner(environment.textDensity.density)
    private val handle=NativeSheetDragHandle(context,environment,{pointer.inputRoot},runtime,::handleClick,::requestAnimatedDismiss)
    private val appContent=NativeSheetAppContent(context,environment,title,{pointer.inputRoot}) {onOwnerDismiss()}
    private val surface=NativeSheetSurface(context,environment,handle,appContent)
    private val scrim=SheetScrim(context,environment.palette.scrim,::requestAnimatedDismiss)
    val root:SheetRoot=SheetRoot(context,pointer,state,surface,scrim,environment.textDensity.density,
        ::measured,::applyMotion)
    private val dragChannel=NativeActionSheetDragChannel(state,runtime.scope,::settleToDismiss)
    private val dragNode=NativeActionSheetDragNode(surface,state,dragChannel)
    private val nested=NativeActionSheetNestedScroll(state,::settleToDismiss)
    private val accessibility=NativeActionSheetAccessibility(root,surface,scrim,handle,appContent)
    private val scrollEvents=NativeWindowScrollAccessibilityEvents(root,runtime.scope,
        onScrollGeometryChanged={root.invalidate()},onBatchFinished={root.invalidate()},onClosed={})
    val contentContext=NativeActionSheetContentContext(
        root,environment,runtime,nested,pointer,dragNode::interested,accessibility,scrollEvents,
    )
    private val insets=NativeWindowInsetsBinding(root) {root.insets(it)}
    private val lifecycleObserver=LifecycleEventObserver {_,event->
        when(event) {
            Lifecycle.Event.ON_START->runtime.setStarted(true)
            Lifecycle.Event.ON_STOP->runtime.setStarted(false)
            Lifecycle.Event.ON_DESTROY->dismiss()
            else->Unit
        }
    }
    private val back=object:OnBackPressedCallback(true) {
        override fun handleOnBackStarted(backEvent:BackEventCompat)=snapPredictive(backEvent.progress)
        override fun handleOnBackProgressed(backEvent:BackEventCompat)=snapPredictive(backEvent.progress)
        override fun handleOnBackCancelled() {
            runtime.scope.launch {predictive.animateTo(0f) {applyMotion()}}
        }
        override fun handleOnBackPressed() {
            if(!dismissalAllowed()) {refreshDismissalAdmission();return}
            val dismissAtRequest=onOwnerDismiss
            runtime.scope.launch {state.hide()}.invokeOnCompletion {
                // Ordinary sheets retain the released completion callback on cancellation.
                if(!hasDismissalAdmission||(!disposed&&!state.visible&&dismissalAllowed()))dismissAtRequest()
            }
        }
    }

    init {
        val actual=requireNotNull(window)
        actual.requestFeature(Window.FEATURE_NO_TITLE)
        actual.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(actual,false)
        setContentView(root)
        root.setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
        root.setViewTreeViewModelStoreOwner(anchor.findViewTreeViewModelStoreOwner())
        root.setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
        root.layoutDirection=anchor.layoutDirection
        root.elevation=8f*environment.textDensity.density
        root.outlineProvider=object:ViewOutlineProvider() {
            override fun getOutline(view:View,result:Outline) {
                result.setRect(0,0,view.width,view.height);result.alpha=0f
            }
        }
        if(Build.VERSION.SDK_INT>=29)root.isForceDarkAllowed=false
        updateWindowParameters()
        updateWindowColors()
        pointer.bind(root,surface,scrim,handle,appContent,dragNode)
        onBackPressedDispatcher.addCallback(this,back)
        anchorLifecycle?.addObserver(lifecycleObserver)
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View) {
                runtime.setStarted(anchorLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)!=false)
            }
            override fun onViewDetachedFromWindow(view:View) {runtime.setStarted(false)}
        })
        runtime.scope.launch {
            // animateValueAsState's conflated targets, including its extra receive to avoid old target.
            for(received in scrimTargets) {
                val latest=scrimTargets.tryReceive().getOrNull()?:received
                launch {if(latest!=scrimAnimation.targetValue) {
                    scrimAnimation.animateTo(latest,NativeActionSheetState.scrimSpec) {
                        scrim.opacity=value.coerceIn(0f,1f)
                    }
                }}
            }
        }
    }

    fun setContent(body:View,actions:View,hitLayouts:List<NativeViewPointerHitLayout>) {
        appContent.setContent(body,actions)
        pointer.setContentHits(hitLayouts)
    }
    /** Callback and title updates never recreate bodies, accepted actions, draft fields or gestures. */
    fun bind(title:String,onDismiss:()->Unit) {
        onOwnerDismiss=onDismiss;appContent.bind(title) {onOwnerDismiss()}
        root.layoutDirection=anchor.layoutDirection;updateWindowParameters()
    }
    /** Palette changes update this same Window; density/configuration recreation remains host-owned. */
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(disposed||environment===next)return
        environment=next
        contentContext.bindEnvironment(next)
        surface.bindEnvironment(next)
        appContent.bindEnvironment(next)
        handle.bindEnvironment(next)
        scrim.bindColor(next.palette.scrim)
        updateWindowColors()
    }
    /** Busy-policy correction: admission precedes M3's hide-then-callback path. */
    fun bindDismissalAdmission(allowed:()->Boolean) {
        hasDismissalAdmission=true
        dismissalAllowed=allowed
        refreshDismissalAdmission()
    }
    fun refreshDismissalAdmission() {
        if(disposed)return
        val allowed=dismissalAllowed()
        if(previousDismissalAllowed==allowed)return
        previousDismissalAllowed=allowed
        if(!allowed) {
            state.dismissalAdmissionChanged()
            runtime.scope.launch {predictive.snapTo(0f);applyMotion()}
            applyMotion()
        }
    }
    val contentView:View get()=surface
    val hasCurrentWindowFocus get()=root.isAttachedToWindow&&root.hasWindowFocus()

    private fun snapPredictive(raw:Float) {
        runtime.scope.launch {predictive.snapTo(if(dismissalAllowed())PREDICTIVE_EASING.transform(raw)else 0f);applyMotion()}
    }
    private fun handleClick() {
        when(state.currentValue) {
            NativeSheetValue.EXPANDED->requestAnimatedDismiss()
            NativeSheetValue.HIDDEN->runtime.scope.launch {state.show()}
        }
    }
    fun requestAnimatedDismiss() {
        if(!dismissalAllowed()) {refreshDismissalAdmission();return}
        val dismissAtRequest=onOwnerDismiss
        runtime.scope.launch {state.hide()}.invokeOnCompletion {
            if(!state.visible&&dismissalAllowed())dismissAtRequest()
        }
    }
    private fun settleToDismiss(velocity:Float) {
        val dismissAtRequest=onOwnerDismiss
        runtime.scope.launch {state.settle(velocity)}.invokeOnCompletion {
            if(!state.visible&&dismissalAllowed())dismissAtRequest()
        }
    }
    private fun measured() {
        if(state.hasExpandedState&&showEffect==null)showEffect=runtime.scope.launch {state.show()}
        else if(!state.hasExpandedState) {showEffect?.cancel();showEffect=null}
        applyMotion()
    }
    private fun stateChanged() {
        applyStateChange()
    }
    // Kept separate from state's constructor callback: callbacks run only after measure/mutation.
    private fun applyStateChange() {
        root.placeSheet()
        val target=if(state.targetValue!=NativeSheetValue.HIDDEN)1f else 0f
        if(target!=scrimTarget) {scrimTarget=target;scrimTargets.trySend(target)}
        handle.refreshState()
        applyMotion()
    }
    private fun applyMotion() {
        if(!state.offset.isNaN())surface.applyMotion(state.offset,
            state.anchors?.minimum?:state.offset,if(dismissalAllowed())predictive.value else 0f)
    }
    private fun updateWindowColors() {
        val actual=requireNotNull(window)
        val contentColor=ComposeColor(environment.palette.onSurface)
        val dark=contentColor!=ComposeColor.Transparent&&contentColor.luminance()<=.5f
        WindowCompat.getInsetsController(actual,actual.decorView).apply {
            isAppearanceLightStatusBars=dark;isAppearanceLightNavigationBars=dark
        }
    }
    private fun updateWindowParameters() {
        val flags=(anchor.rootView.layoutParams as?WindowManager.LayoutParams)?.flags?:0
        requireNotNull(window).apply {
            setFlags(flags and WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(if(Build.VERSION.SDK_INT>=30)WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        val result=super.onTouchEvent(event)
        if(result)back.handleOnBackPressed()
        return result
    }
    override fun cancel()=Unit
    override fun dismiss() {
        if(disposed)return
        disposed=true
        var failure:Throwable?=null
        fun release(block:()->Unit) {
            try {block()}catch(error:Throwable) {
                val first=failure
                if(first==null)failure=error else if(first!==error)first.addSuppressed(error)
            }
        }
        // Released DisposableEffect removes its own dialog Window before disposing its content.
        // A failing input/owner callback must not skip Window removal or the remaining cleanup.
        release {super.dismiss()}
        release {anchorLifecycle?.removeObserver(lifecycleObserver)}
        release {back.remove()}
        release {insets.close()}
        release {pointer.close()}
        release {dragChannel.close()}
        release {accessibility.close()}
        release {scrollEvents.close()}
        release {handle.close()}
        release {appContent.close.cancelInput()}
        release {scrimTargets.close()}
        release {runtime.close()}
        failure?.let {throw it}
    }
    companion object {private val PREDICTIVE_EASING=CubicBezierEasing(.1f,.1f,0f,1f)}
}

/** Passed once to concrete body/action owners, without discovering or guessing their input shapes. */
internal class NativeActionSheetContentContext(
    val inputRoot:NativePagerTouchRoot,
    initialEnvironment:ReverbViewEnvironment,
    val runtime:NativeAboutWindowRuntime,
    val nestedScroll:NativeVerticalNestedHost,
    private val pointer:SheetPointerOwner,
    val interestedParent:(NativeDragPointerChange)->Boolean,
    val accessibility:NativeActionSheetAccessibility,
    val scrollEvents:NativeWindowScrollAccessibilityEvents,
) {
    var environment=initialEnvironment;private set
    internal fun bindEnvironment(next:ReverbViewEnvironment) {environment=next}
    fun hit(view:View,nodes:List<NativeViewPhasePointerNode>,
        children:()->List<NativeViewPointerHitLayout> = {emptyList()},
        minimumTouchTarget:Size=Size(48f*environment.textDensity.density,48f*environment.textDensity.density),
        clip:(Offset)->Boolean={true},
    ):NativeViewPointerHitLayout=NativeActionSheetHitLayout(view,pointer.geometry,
        {minimumTouchTarget},nodes,children,clip)
}

internal class SheetRoot(
    context:Context,pointer:SheetPointerOwner,
    private val state:NativeActionSheetState,
    private val surface:NativeSheetSurface,
    private val scrim:View,
    private val density:Float,
    private val measured:()->Unit,
    private val motion:()->Unit,
):NativePagerTouchRoot(context,pointer.geometry::rootToScreen,pointer.geometry::coordinates,
    pointer.layout,pointer.geometry::begin,mouseButtons=true) {
    private var imeBottom=0
    /** Source traversal semantics is inside imePadding, although its real Window remains full-size. */
    val sourceSemanticsHeight get()=max(0,height-imeBottom)
    private var safe=Insets.NONE
    private var measuring=false
    init {
        clipChildren=false;clipToPadding=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        isScreenReaderFocusable=false
        // AndroidComposeView searches eligible children first; touch mode may retain owner focus.
        isFocusable=true
        isFocusableInTouchMode=true
        descendantFocusability=FOCUS_AFTER_DESCENDANTS
        defaultFocusHighlightEnabled=false
        addView(scrim);addView(surface)
    }
    fun insets(value:WindowInsetsCompat) {
        imeBottom=value.getInsets(WindowInsetsCompat.Type.ime()).bottom
        safe=value.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or
            WindowInsetsCompat.Type.displayCutout())
        updateContentInsets()
        requestLayout()
    }
    private fun updateContentInsets():Boolean {
        val topConsumed=if(state.offset.isNaN())0 else state.offset.toInt().coerceAtLeast(0)
        return surface.insets.update(max(0,safe.top-topConsumed),max(0,safe.bottom-imeBottom))
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        measuring=true
        try {
            val width=MeasureSpec.getSize(widthMeasureSpec);val height=MeasureSpec.getSize(heightMeasureSpec)
            val available=max(0,height-imeBottom)
            updateContentInsets()
            scrim.measure(sheetWindowExact(width),sheetWindowExact(available))
            surface.measure(sheetWindowExact(min(width,(640f*density).roundToInt())),sheetWindowAtMost(available))
            setMeasuredDimension(width,height)
            state.measured(available,surface.measuredHeight)
        } finally {measuring=false}
        measured()
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        scrim.layout(0,0,scrim.measuredWidth,scrim.measuredHeight)
        val x=((width-surface.measuredWidth)*.5f).roundToInt()
        val y=if(state.offset.isNaN())height else state.offset.roundToInt()
        surface.layout(x,y,x+surface.measuredWidth,y+surface.measuredHeight)
        motion()
    }
    fun placeSheet() {
        if(measuring)return
        if(!state.offset.isNaN()&&surface.isLaidOut)surface.offsetTopAndBottom(state.offset.roundToInt()-surface.top)
        updateContentInsets()
    }
    override fun getAccessibilityClassName():CharSequence="android.view.View"
}
internal class SheetScrim(context:Context,color:Int,private val action:()->Unit):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var sourceColor=reverbColorWithAlpha(color,.32f)
    var opacity=0f
        set(value) {if(field!=value){field=value;invalidate()}}
    init {
        contentDescription=context.getString(smallthingz.ui.R.string.close_sheet)
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable=true
        isScreenReaderFocusable=true
        setOnClickListener {action()}
        // Source Scrim has pointer/semantic actions, but no keyboard FocusTarget.
        isFocusable=false
    }
    fun bindColor(color:Int) {
        val next=reverbColorWithAlpha(color,.32f)
        if(sourceColor!=next) {sourceColor=next;invalidate()}
    }
    override fun onDraw(canvas:Canvas) {
        // DrawScope.configurePaint(color,alpha) multiplies source alpha before roundToInt.
        paint.color=reverbColorWithAlpha(sourceColor,Color.alpha(sourceColor)/255f*opacity)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    }
    override fun getAccessibilityClassName():CharSequence="android.view.View"
}
internal class SheetPointerOwner(private val density:Float):AutoCloseable {
    private var root:SheetRoot?=null
    val inputRoot:View? get()=root
    val geometry=NativeWindowPointerCoordinates {checkNotNull(root)}
    private var sheets=emptyList<NativeViewPointerHitLayout>()
    private var contentHits=emptyList<NativeViewPointerHitLayout>()
    private var fixedContentHits=emptyList<NativeViewPointerHitLayout>()
    private var combinedContentHits=emptyList<NativeViewPointerHitLayout>()
    val layout=object:NativeViewPointerHitLayout {
        override val placed get()=root?.isAttachedToWindow==true
        override val size get()=Size((root?.width?:0).toFloat(),(root?.height?:0).toFloat())
        override val minimumTouchTarget=Size.Zero
        override val nodes=emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder get()=sheets
        override fun screenToLocal(screen:Offset)=geometry.coordinates(checkNotNull(root)).local(screen)
        override fun withinLayerBounds(local:Offset)=true
    }
    fun bind(root:SheetRoot,surface:NativeSheetSurface,scrim:SheetScrim,handle:NativeSheetDragHandle,
        app:NativeSheetAppContent,drag:NativeActionSheetDragNode) {
        this.root=root
        val minimum={Size(48f*density,48f*density)}
        val close=app.close.visual
        val closeHit=NativeActionSheetHitLayout(close,geometry,minimum,
            listOf(NativeClickPhaseNode(close,close.nativeClickEndpoint,drag::interested)),clip={p->
                val radius=min(close.width,close.height)*.5f
                val x=p.x-close.width*.5f;val y=p.y-close.height*.5f;x*x+y*y<=radius*radius
            })
        val handleHit=NativeActionSheetHitLayout(handle,geometry,minimum,
            listOf(handle.tooltip.pointerNode,NativeClickPhaseNode(handle,handle.nativeClickEndpoint,drag::interested)))
        val shield=object:NativeViewPhasePointerNode {
            override val view:View get()=surface.painted
            override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass)=Unit
            override fun cancel()=Unit
        }
        fixedContentHits=listOf(closeHit,handleHit)
        refreshContentHits()
        val contentHit=NativeActionSheetHitLayout(surface.painted,geometry,minimum,listOf(shield),
            children={combinedContentHits},clip=surface::containsShape)
        val surfaceChildren=listOf(contentHit)
        val surfaceHit=NativeActionSheetHitLayout(surface,geometry,minimum,listOf(drag),
            children={surfaceChildren})
        val scrimHit=NativeActionSheetHitLayout(scrim,geometry,{Size.Zero},
            listOf(NativeSheetScrimTapNode(scrim,{density}) {scrim.performClick()}))
        sheets=listOf(surfaceHit,scrimHit)
    }
    fun setContentHits(value:List<NativeViewPointerHitLayout>) {
        contentHits=value
        refreshContentHits()
    }
    private fun refreshContentHits() {
        combinedContentHits=if(contentHits.isEmpty())fixedContentHits else contentHits+fixedContentHits
    }
    override fun close() {
        root?.cancelPointerInput()
        sheets=emptyList();contentHits=emptyList();fixedContentHits=emptyList();combinedContentHits=emptyList();root=null
    }
}
private fun sheetWindowExact(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY)
private fun sheetWindowAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.AT_MOST)
