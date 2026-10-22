/*
 * Specialized from Material3 1.4.0 SheetDefaults.kt and internal/AnchoredDraggable.kt.
 * Copyright 2023 The Android Open Source Project. Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.animation.core.AnimationSpec
import app.smallthingz.nativeui.animation.core.animate
import app.smallthingz.nativeui.animation.core.spring
import app.smallthingz.nativeui.foundation.MutatePriority
import app.smallthingz.nativeui.foundation.MutatorMutex
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal enum class NativeSheetValue { HIDDEN, EXPANDED }

internal data class NativeSheetAnchors(val hidden:Float,val expanded:Float?) {
    fun position(value:NativeSheetValue)=when(value) {
        NativeSheetValue.HIDDEN->hidden
        NativeSheetValue.EXPANDED->expanded?:Float.NaN
    }
    val minimum get()=expanded?.let {minOf(it,hidden)}?:hidden
    val maximum get()=expanded?.let {maxOf(it,hidden)}?:hidden
    fun nearest(offset:Float,upwards:Boolean?=null):NativeSheetValue {
        // Source LinkedHashMap insertion order is Hidden then Expanded; ties retain Hidden.
        var result=NativeSheetValue.HIDDEN
        fun distance(anchor:Float):Float {
            if(upwards==null)return abs(offset-anchor)
            val delta=if(upwards)anchor-offset else offset-anchor
            return if(delta<0)Float.POSITIVE_INFINITY else delta
        }
        val first=distance(hidden)
        if(expanded!=null && distance(expanded)<first)result=NativeSheetValue.EXPANDED
        return result
    }
}

internal interface NativeSheetDragScope {
    fun dragTo(offset:Float,velocity:Float=0f)
}

/**
 * Reverb's fixed skipPartiallyExpanded=true owner. No Composition, snapshot observer or renderer.
 * Public pinned MutatorMutex provides the same mutate/tryMutate policy as M3's private copy.
 * Only anchor/explicit-target changes publish a conflated restart input; offset frames are direct.
 * Actual restart dispatch phase relative to source Snapshot apply remains a fixture requirement.
 */
internal class NativeActionSheetState(
    private val density:()->Float,
    private val changed:()->Unit,
) {
    private val mutex=MutatorMutex()
    private data class RestartInput(val anchors:NativeSheetAnchors?,val target:NativeSheetValue?)
    private val restart=MutableStateFlow(RestartInput(null,null))
    var anchors:NativeSheetAnchors?=null
        private set
    var currentValue=NativeSheetValue.HIDDEN
        private set
    var offset=Float.NaN
        private set
    var lastVelocity=0f
        private set
    private var dragTarget:NativeSheetValue?=null
    val targetValue get()=dragTarget?:if(offset.isNaN())currentValue else computeTarget(offset,currentValue,0f)
    val visible get()=currentValue!=NativeSheetValue.HIDDEN
    val animationRunning get()=dragTarget!=null
    val hasExpandedState get()=anchors?.expanded!=null
    private val dragScope=object:NativeSheetDragScope {
        override fun dragTo(offset:Float,velocity:Float) {
            this@NativeActionSheetState.offset=offset
            lastVelocity=velocity
            changed()
        }
    }

    /** Called from actual measurement; placement uses roundToInt(offset), never View.translationY. */
    fun measured(fullHeight:Int,sheetHeight:Int) {
        val next=NativeSheetAnchors(fullHeight.toFloat(),
            if(sheetHeight!=0)max(0f,fullHeight-sheetHeight.toFloat())else null)
        val previousTarget=targetValue
        val nextTarget=when(previousTarget) {
            NativeSheetValue.HIDDEN->NativeSheetValue.HIDDEN
            NativeSheetValue.EXPANDED->if(next.expanded!=null)NativeSheetValue.EXPANDED else NativeSheetValue.HIDDEN
        }
        if(anchors==next)return
        anchors=next
        val snapped=mutex.tryMutate {
            val targetOffset=next.position(nextTarget)
            if(!targetOffset.isNaN()) {
                dragScope.dragTo(targetOffset)
                dragTarget=null
            }
            currentValue=nextTarget
        }
        if(!snapped)dragTarget=nextTarget
        publishRestart()
        changed()
    }

    fun requireOffset():Float {
        check(!offset.isNaN()) {"Sheet offset was read before measurement"}
        return offset
    }

    /** Source raw nested scroll updates do not take the drag mutex or change lastVelocity. */
    fun dispatchRawDelta(delta:Float):Float {
        val original=if(offset.isNaN())0f else offset
        val next=newOffsetForDelta(delta)
        offset=next;changed()
        return next-original
    }
    fun newOffsetForDelta(delta:Float):Float {
        val a=checkNotNull(anchors)
        return ((if(offset.isNaN())0f else offset)+delta).coerceIn(a.minimum,a.maximum)
    }

    suspend fun drag(priority:MutatePriority=MutatePriority.UserInput,block:suspend NativeSheetDragScope.()->Unit) {
        try {
            mutex.mutate(priority) {
                restartable(targeted=false) { dragScope.block() }
            }
        } finally { finishClosest() }
    }

    suspend fun show()=animateTo(NativeSheetValue.EXPANDED,spatialSpec)
    suspend fun hide()=animateTo(NativeSheetValue.HIDDEN,hideSpec)
    suspend fun settle(velocity:Float)=animateTo(computeTarget(requireOffset(),currentValue,velocity),spatialSpec,velocity)

    suspend fun animateTo(
        target:NativeSheetValue,
        spec:AnimationSpec<Float>,
        velocity:Float=lastVelocity,
    ) {
        val initialAnchors=anchors
        if(initialAnchors==null || initialAnchors.position(target).isNaN()) {
            currentValue=target;changed();return
        }
        try {
            mutex.mutate {
                dragTarget=target
                publishRestart();changed()
                restartable(targeted=true) { input ->
                    val latestTarget=input.target?:targetValue
                    val targetOffset=input.anchors?.position(latestTarget)?:Float.NaN
                    if(!targetOffset.isNaN()) {
                        val start=if(offset.isNaN())0f else offset
                        // Retain the operation's initial velocity across anchor restarts, as source.
                        animate(start,targetOffset,velocity,spec) { value,speed->dragScope.dragTo(value,speed) }
                    }
                }
            }
        } finally {
            dragTarget=null;publishRestart()
            finishClosest()
        }
    }

    internal fun computeTarget(position:Float,current:NativeSheetValue,velocity:Float):NativeSheetValue {
        val a=anchors?:return current
        val currentOffset=a.position(current)
        val threshold=125f*density()
        if(currentOffset==position || currentOffset.isNaN())return current
        return if(currentOffset<position) {
            val upper=a.nearest(position,true)
            if(velocity>=threshold)upper else {
                val absolute=abs(currentOffset+abs(56f*density()))
                if(position<absolute)current else upper
            }
        } else {
            val lower=a.nearest(position,false)
            if(velocity<=-threshold)lower else {
                val absolute=abs(currentOffset-abs(56f*density()))
                if(position<0f) {
                    if(abs(position)<absolute)current else lower
                } else if(position>absolute)current else lower
            }
        }
    }

    private fun finishClosest() {
        val a=anchors?:return
        val nearest=a.nearest(offset)
        if(abs(offset-a.position(nearest))<=.5f)currentValue=nearest
        changed()
    }
    private fun publishRestart() {restart.value=RestartInput(anchors,dragTarget)}
    private suspend fun restartable(targeted:Boolean,block:suspend (RestartInput)->Unit) {
        try {
            coroutineScope {
                var previous:Job?=null
                restart.map {if(targeted)it else it.copy(target=null)}.distinctUntilChanged().collect { input->
                    previous?.apply {cancel(NativeSheetDragFinished());join()}
                    previous=launch(start=CoroutineStart.UNDISPATCHED) {
                        block(input)
                        this@coroutineScope.cancel(NativeSheetDragFinished())
                    }
                }
            }
        } catch(_:NativeSheetDragFinished) {
            // Source's private completion signal is swallowed; external cancellation propagates.
        }
    }

    companion object {
        /** ReverbTheme does not replace MaterialTheme's standard MotionScheme. */
        val spatialSpec=spring<Float>(dampingRatio=.9f,stiffness=700f)
        val hideSpec=spring<Float>(dampingRatio=1f,stiffness=3800f)
        val scrimSpec=spring<Float>(dampingRatio=1f,stiffness=1600f)
    }
}
private class NativeSheetDragFinished:CancellationException("Anchored drag finished") {
    override fun fillInStackTrace():Throwable=this
}
