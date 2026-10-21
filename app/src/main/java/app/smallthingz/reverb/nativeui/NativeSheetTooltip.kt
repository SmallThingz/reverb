/*
 * Plain nonpersistent tooltip used by the released M3 sheet drag handle.
 * Source: Material3 1.4.0 Tooltip.kt / internal.BasicTooltip.kt; UI 1.11.2 AndroidPopup.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.input.pointer.PointerType
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class NativeTooltipPlacement(val x:Int,val y:Int)
internal fun nativeTooltipAbove(
    anchorLeft:Int,anchorTop:Int,anchorRight:Int,anchorBottom:Int,
    width:Int,height:Int,windowWidth:Int,spacing:Int,
):NativeTooltipPlacement {
    var x=anchorLeft+(anchorRight-anchorLeft-width)/2
    if(x<0)x=anchorLeft else if(x+width>windowWidth)x=anchorRight-width
    var y=anchorTop-height-spacing
    if(y<0)y=anchorBottom+spacing
    return NativeTooltipPlacement(x,y)
}

/** Process-wide native counterpart of BasicTooltipDefaults.GlobalMutatorMutex. */
private val nativeTooltipMutex=MutatorMutex()
internal class NativeSheetTooltipMotion {
    private val scale=LibraryTransitionValue(.8f,Float.VectorConverter,spring(visibilityThreshold=.01f),
        {AnimationVector1D(0f)})
    private val fade=LibraryTransitionValue(0f,Float.VectorConverter,spring(visibilityThreshold=.01f),
        {AnimationVector1D(0f)})
    private var current=false
    private var target=false
    private var origin:Long?=null
    private var playTime=0L
    val shouldExist get()=current||target
    val alpha get()=fade.value
    val sizeScale get()=scale.value
    fun target(value:Boolean) {
        if(target==value)return
        if(current!=target)current=target
        target=value
        scale.target(if(value)1f else .8f,spring(.9f,1400f),playTime)
        fade.target(if(value)1f else 0f,spring(1f,3800f),playTime)
    }
    fun frame(time:Long,durationScale:Float) {
        if(!scale.running&&!fade.running)return
        if(origin==null)origin=time
        val toEnd=durationScale==0f
        if(!toEnd)playTime=((time-checkNotNull(origin)).toDouble()/durationScale).roundToLong()
        scale.frame(playTime,toEnd);fade.frame(playTime,toEnd)
        if(!scale.running&&!fade.running) {current=target;origin=null;playTime=0L}
    }
}

/** Direct existing-anchor adapter; no extra clickable View or second press/ripple controller. */
internal class NativeSheetTooltip(
    private val anchor:View,
    private var environment:ReverbViewEnvironment,
    private val runtime:NativeAboutWindowRuntime,
    private val description:String,
):AutoCloseable {
    private val nodeJob=SupervisorJob(runtime.scope.coroutineContext[Job])
    private val scope=CoroutineScope(runtime.scope.coroutineContext+nodeJob)
    private val motion=NativeSheetTooltipMotion()
    private var continuation:CancellableContinuation<Unit>?=null
    private var popup:NativePlainTooltipWindow?=null
    private var timer:Job?=null
    private var disposed=false
    private var awaiting=false
    private var longPressed=false
    private var firstDown=false
    private var held=MutableStateFlow(false)
    private var animation:Job?=null
    val pointerNode=object:NativeViewPhasePointerNode {
        override val view get()=anchor
        override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass) {
            if(event.changes.isEmpty())return
            if(pass==NativePointerPass.INITIAL) {
                firstDown=false
                if(!awaiting) {
                    if(event.changes.all {it.changedToDown&&!it.consumed}&&
                        event.changes.first().type in listOf(PointerType.Touch,PointerType.Stylus)) {
                        awaiting=true;firstDown=true;longPressed=false
                        held=MutableStateFlow(true)
                        val thisHold=held
                        timer=scope.launch {
                            delay(ViewConfiguration.getLongPressTimeout().toLong())
                            if(awaiting) {
                                longPressed=true
                                scope.launch(start=CoroutineStart.UNDISPATCHED) {
                                    try {show(MutatePriority.PreventUserInput)}
                                    finally {if(motion.shouldExist)thisHold.collectLatest {if(!it)dismiss()}}
                                }
                            }
                        }
                    }
                    return
                }
                if(event.changes.all {it.changedToUp&&!it.consumed}) {
                    if(longPressed)event.changes.first().consume()
                    finishGesture()
                } else if(event.changes.any {it.consumed||outside(it.position,it.type)})finishGesture()
            } else if(pass==NativePointerPass.FINAL&&awaiting&&!firstDown&&event.changes.any {it.consumed}) {
                finishGesture()
            }
        }
        override fun cancel()=finishGesture()
    }
    private fun outside(point:Offset,type:PointerType):Boolean {
        val minimum=48f*environment.textDensity.density
        val x=if(type==PointerType.Touch)max(0f,minimum-anchor.width)*.5f else 0f
        val y=if(type==PointerType.Touch)max(0f,minimum-anchor.height)*.5f else 0f
        return point.x< -x||point.x>anchor.width+x||point.y< -y||point.y>anchor.height+y
    }
    private fun finishGesture() {
        timer?.cancel();timer=null;awaiting=false;held.value=false;longPressed=false
    }
    private suspend fun show(priority:MutatePriority=MutatePriority.Default) {
        nativeTooltipMutex.mutate(priority) {
            try {
                withTimeout(1500L) {
                    suspendCancellableCoroutine<Unit> {next->
                        continuation=next
                        target(true)
                    }
                }
            } finally {if(priority!=MutatePriority.PreventUserInput)dismiss()}
        }
    }
    private fun target(value:Boolean) {
        if(disposed)return
        motion.target(value)
        if(motion.shouldExist&&popup==null&&anchor.isAttachedToWindow) {
            popup=NativePlainTooltipWindow(anchor,environment,description,::dismiss).also {it.show()}
        }
        if(animation==null&&popup!=null)animation=scope.launch {
            try {
                while(isActive&&popup!=null)runtime.frames.withFrameNanos {time->
                    motion.frame(time,ValueAnimator.getDurationScale())
                    popup?.frame(motion.sizeScale,motion.alpha)
                    if(!motion.shouldExist) {popup?.close();popup=null}
                }
            } finally {animation=null}
        }
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(disposed||environment===next)return
        environment=next;popup?.bindEnvironment(next)
    }
    fun dismiss()=target(false)
    fun showFromAccessibility() {scope.launch {show()}}
    fun focusChanged(focused:Boolean) {
        scope.launch {
            if(focused)show(MutatePriority.PreventUserInput)
            if(motion.shouldExist&&!focused)dismiss()
        }
    }
    fun hover(event:MotionEvent) {
        if(event.getToolType(0)!=MotionEvent.TOOL_TYPE_MOUSE)return
        when(event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER->scope.launch {show(MutatePriority.UserInput)}
            MotionEvent.ACTION_HOVER_EXIT->dismiss()
        }
    }
    fun retire() {finishGesture();continuation?.cancel();popup?.close();popup=null;animation?.cancel()}
    override fun close() {if(disposed)return;disposed=true;retire();nodeJob.cancel()}
}

/** Nonfocusable native sub-panel, using the released Popup flags/position/size rather than TooltipText. */
private class NativePlainTooltipWindow(
    private val anchor:View,
    private var environment:ReverbViewEnvironment,
    description:String,
    private val dismiss:()->Unit,
):NativeSheetFrame(anchor.context),AutoCloseable {
    private val manager=context.getSystemService(WindowManager::class.java)
    private val params=WindowManager.LayoutParams().apply {
        gravity=Gravity.START or Gravity.TOP
        flags=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (((anchor.rootView.layoutParams as?WindowManager.LayoutParams)?.flags?:0)and WindowManager.LayoutParams.FLAG_SECURE)
        type=WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
        token=anchor.applicationWindowToken
        width=WindowManager.LayoutParams.WRAP_CONTENT;height=WindowManager.LayoutParams.WRAP_CONTENT
        format=PixelFormat.TRANSLUCENT
        title=context.getString(androidx.compose.ui.R.string.default_popup_window_title)
    }
    private val bubble=TooltipBubble(context,environment,description)
    private val visibleFrame=Rect()
    private val location=IntArray(2)
    private var added=false
    private var closed=false
    private var positioned=false
    private var previousPosition:NativeTooltipPlacement?=null
    private var observer:ViewTreeObserver?=null
    private val globalLayout=ViewTreeObserver.OnGlobalLayoutListener {position(force=true)}
    private val cursor=PlainTextAccessibilityWindowScope(this)
    private val textGeometry=NativePlainTextViewGeometry(this,NativeTextSemanticsClipPolicy {view->
        if(view===bubble.clip)NativeTextSemanticsClip.Rectangle(
            ComposeRect(0f,0f,view.width.toFloat(),view.height.toFloat()))
        else NativeTextSemanticsClip.Unclipped
    })
    private val textLease=bubble.label.bindPlainTextAccessibility(cursor,textGeometry,
        sourceNode={labelFacts()}) {labelFacts().present}
    init {
        addView(bubble)
        layoutDirection=anchor.layoutDirection
        setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
        setViewTreeViewModelStoreOwner(anchor.findViewTreeViewModelStoreOwner())
        setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
        elevation=8f*environment.textDensity.density
        outlineProvider=object:ViewOutlineProvider() {
            override fun getOutline(view:View,result:Outline) {result.setRect(0,0,width,height);result.alpha=0f}
        }
        if(Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion=ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        accessibilityPaneTitle=context.getString(androidx.compose.foundation.R.string.tooltip_description)
        bubble.alpha=0f
        bubble.label.isScreenReaderFocusable=true
    }
    fun bindEnvironment(next:ReverbViewEnvironment) {
        if(closed||environment===next)return
        val densityChanged=environment.textDensity!=next.textDensity
        environment=next; bubble.bindEnvironment(next)
        if(densityChanged) {elevation=8f*next.textDensity.density;requestLayout()}
    }
    fun show() {
        check(!added&&!closed);added=true
        try {manager.addView(this,params)}
        catch(error:Throwable) {
            added=isAttachedToWindow||parent!=null
            try {close()}catch(cleanup:Throwable){error.addSuppressed(cleanup)}
            throw error
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer=anchor.viewTreeObserver.also {it.addOnGlobalLayoutListener(globalLayout)}
    }
    override fun onDetachedFromWindow() {
        observer?.takeIf {it.isAlive}?.removeOnGlobalLayoutListener(globalLayout);observer=null
        super.onDetachedFromWindow()
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        bubble.measure(sheetTooltipAtMost(visibleFrame.width()),sheetTooltipAtMost(visibleFrame.height()))
        setMeasuredDimension(bubble.measuredWidth,bubble.measuredHeight)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        bubble.layout(0,0,bubble.measuredWidth,bubble.measuredHeight)
        params.width=bubble.measuredWidth;params.height=bubble.measuredHeight
        position(force=true);invalidateOutline()
    }
    fun frame(scale:Float,alpha:Float) {
        position()
        bubble.scaleX=scale;bubble.scaleY=scale
        bubble.alpha=if(positioned&&anchor.isAttachedToWindow)alpha else 0f
    }
    private fun position(force:Boolean=false) {
        if(!added||closed||!anchor.isAttachedToWindow||!isLaidOut)return
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        anchor.getLocationInWindow(location)
        val next=nativeTooltipAbove(location[0],location[1],location[0]+anchor.width,
            location[1]+anchor.height,bubble.width,bubble.height,visibleFrame.width(),
            (4f*environment.textDensity.density).roundToInt())
        if(!force&&next==previousPosition)return
        previousPosition=next;params.x=next.x;params.y=next.y
        if(Build.VERSION.SDK_INT>=29)systemGestureExclusionRects=listOf(Rect(0,0,visibleFrame.width(),visibleFrame.height()))
        manager.updateViewLayout(this,params);positioned=true
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_OUTSIDE ||
            event.actionMasked==MotionEvent.ACTION_DOWN&&
            (event.x<0||event.x>=width||event.y<0||event.y>=height)) {dismiss();return true}
        return super.onTouchEvent(event)
    }
    override fun close() {
        if(closed)return;closed=true
        try {
            if(added) {added=false;manager.removeViewImmediate(this)}
        } finally {textLease.close()}
    }
    private fun labelFacts():NativeSourceAccessibilityNode {
        if(!isAttachedToWindow)return NativeSourceAccessibilityNode.absent()
        val label=bubble.label
        val captured=textGeometry.capture(label,Offset.Zero,
            ComposeRect(0f,0f,label.width.toFloat(),label.height.toFloat()))
            ?:return NativeSourceAccessibilityNode.absent()
        val bounds=nativeMergedRoleBoundsInScreen(captured,label.isLaidOut)
        val hidden=bubble.alpha==0f
        return NativeSourceAccessibilityNode(!bounds.isEmpty,hidden,!hidden&&!bounds.isEmpty,bounds)
    }
    private class TooltipBubble(context:android.content.Context,private var env:ReverbViewEnvironment,text:String):
        ViewGroup(context) {
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color=env.palette.inverseSurface
        }
        val label=env.paragraph(context,text,ReverbTextRole.BODY_SMALL,env.palette.inverseOnSurface)
        val clip=NativeSheetSurface.SheetClip(context)
        private val content=object:NativeSheetFrame(context) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),MeasureSpec.getSize(heightMeasureSpec))
            }
        }
        private val shape=Path()
        init {
            setWillNotDraw(false);clipChildren=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            content.addView(label);clip.addView(content);addView(clip)
        }
        fun bindEnvironment(next:ReverbViewEnvironment) {
            val densityChanged=env.textDensity!=next.textDensity
            env=next;fill.color=next.palette.inverseSurface
            next.bindParagraphStyle(label,ReverbTextRole.BODY_SMALL,next.palette.inverseOnSurface)
            if(densityChanged) {onSizeChanged(width,height,width,height);requestLayout()}
            invalidate()
        }
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val d=env.textDensity.density
            val horizontal=(8f*d).roundToInt();val vertical=(4f*d).roundToInt()
            val maxWidth=min((200f*d).roundToInt(),MeasureSpec.getSize(widthMeasureSpec))
            val maxHeight=MeasureSpec.getSize(heightMeasureSpec)
            label.measure(sheetTooltipAtMost(max(0,maxWidth-horizontal*2)),sheetTooltipAtMost(max(0,maxHeight-vertical*2)))
            setMeasuredDimension(min(maxWidth,max((40f*d).roundToInt(),label.measuredWidth+horizontal*2)),
                min(maxHeight,max((24f*d).roundToInt(),label.measuredHeight+vertical*2)))
            // The outer size owner keeps text's wrap-content measure; only its clipping layer
            // spans the whole tooltip. No second measurement replaces the source Box constraints.
            clip.measure(View.MeasureSpec.makeMeasureSpec(measuredWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(measuredHeight,View.MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            clip.layout(0,0,width,height)
            val x=(8f*env.textDensity.density).roundToInt()
            val y=(4f*env.textDensity.density).roundToInt()
            val actualX=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-x-label.measuredWidth else x
            label.layout(actualX,y,actualX+label.measuredWidth,y+label.measuredHeight)
        }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            val radius=min(4f*env.textDensity.density,min(w,h)*.5f)
            shape.rewind();shape.addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),radius,radius,Path.Direction.CW)
            clip.setShape(shape)
        }
        override fun onDraw(canvas:Canvas) {
            val radius=min(4f*env.textDensity.density,min(width,height)*.5f)
            canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,fill)
        }
    }
}
private fun sheetTooltipAtMost(value:Int)=View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.AT_MOST)
