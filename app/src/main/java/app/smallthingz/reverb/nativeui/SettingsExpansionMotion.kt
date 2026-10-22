/*
 * Default AnimatedVisibility expansion/fade math from AndroidX animation1.11.2.
 * Copyright 2020-2025 The Android Open Source Project. Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.animation.core.AnimationVector
import app.smallthingz.nativeui.animation.core.AnimationVector1D
import app.smallthingz.nativeui.animation.core.AnimationVector2D
import app.smallthingz.nativeui.animation.core.Spring
import app.smallthingz.nativeui.animation.core.SpringSpec
import app.smallthingz.nativeui.animation.core.TargetBasedAnimation
import app.smallthingz.nativeui.animation.core.TwoWayConverter
import app.smallthingz.nativeui.animation.core.VectorConverter
import app.smallthingz.nativeui.unit.IntSize
import kotlin.math.roundToLong

/** Source Transition child: raw vector momentum and a shared, delayed transition play time. */
private class ExpansionSpring<T,V:AnimationVector>(
    initial:T,
    private val converter:TwoWayConverter<T,V>,
    private val spec:SpringSpec<T>,
    private val zero:()->V,
) {
    var value=initial
        private set
    var target=initial
        private set
    private var velocity=zero()
    private var animation:TargetBasedAnimation<T,V>?=null
    private var offset=0L
    val running:Boolean get()=animation!=null
    fun target(next:T,playTime:Long,force:Boolean=false) {
        if(!force&&target==next)return
        animation=TargetBasedAnimation(spec,converter,value,next,initialVelocityVector=velocity)
        target=next;offset=playTime.coerceAtLeast(0)
    }
    fun frame(playTime:Long,toEnd:Boolean) {
        val active=animation?:return
        val local=if(toEnd)active.durationNanos else (playTime-offset).coerceAtLeast(0)
        value=active.getValueFromNanos(local);velocity=active.getVelocityVectorFromNanos(local)
        if(active.isFinishedFromNanos(local))animation=null
    }
    fun snap(next:T) {value=next;target=next;velocity=zero();animation=null;offset=0}
}

/** OutputCompressionSettings uses Top alignment; SkipSilence can reuse the same math with Bottom. */
internal class SettingsExpansionMotion(initiallyVisible:Boolean) {
    private enum class Phase {PRE_ENTER,VISIBLE,POST_EXIT}
    private var current=if(initiallyVisible)Phase.VISIBLE else Phase.PRE_ENTER
    private var target=current
    private var full:IntSize?=null
    private var size:ExpansionSpring<IntSize,AnimationVector2D>?=null
    private var fade:ExpansionSpring<Float,AnimationVector1D>?=
        if(initiallyVisible)newFade(1f) else null
    private var start:Long?=null
    private var playTime=0L
    private var stateUpdate=false
    val visibleTarget:Boolean get()=target==Phase.VISIBLE
    val running:Boolean get()=size?.running==true||fade?.running==true
    val present:Boolean get()=visibleTarget||current==Phase.VISIBLE||running
    val alpha:Float get()=fade?.value?:if(visibleTarget)1f else 0f
    val measuredSize:IntSize get()=size?.value?:full?:IntSize.Zero

    fun visible(value:Boolean) {
        if(value==visibleTarget)return
        if(current!=target)current=target
        target=if(value)Phase.VISIBLE else if(current==Phase.VISIBLE)Phase.POST_EXIT else Phase.PRE_ENTER
        // Fade is created in composition; deferred size is first created during measurement.
        if(fade==null)fade=newFade(if(current==Phase.VISIBLE)1f else 0f)
        fade!!.target(if(value)1f else 0f,playTime,true)
        stateUpdate=true
        full?.let(::measure)
    }
    fun measure(value:IntSize) {
        full=value
        if(size==null)size=ExpansionSpring(
            if(current==Phase.VISIBLE)value else IntSize(value.width,0),
            IntSize.VectorConverter,
            SpringSpec(stiffness=Spring.StiffnessMediumLow,visibilityThreshold=IntSize(1,1)),
            {AnimationVector2D(0f,0f)})
        if(fade==null)fade=newFade(if(current==Phase.VISIBLE)1f else 0f)
        if(current==target&&target==Phase.VISIBLE&&!running&&!stateUpdate) {
            size!!.snap(value);fade!!.snap(1f);return
        }
        size!!.target(if(visibleTarget)value else IntSize(value.width,0),playTime,stateUpdate)
        stateUpdate=false
    }
    fun frame(time:Long,scale:Float) {
        if(!running)return
        require(scale>=0f) {"negative scale factor"}
        val first=start?:time.also {start=it}
        playTime=if(scale==0f)time-first else ((time-first)/scale.toDouble()).roundToLong()
        size?.frame(playTime,scale==0f);fade?.frame(playTime,scale==0f)
        if(!running) {current=target;start=null;playTime=0}
    }
    private fun newFade(value:Float)=ExpansionSpring(value,Float.VectorConverter,
        SpringSpec<Float>(stiffness=Spring.StiffnessMediumLow),{AnimationVector1D(0f)})
}
