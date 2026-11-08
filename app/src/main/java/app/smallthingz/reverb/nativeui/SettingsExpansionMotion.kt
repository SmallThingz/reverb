/*
 * Default AnimatedVisibility expansion/fade math from AndroidX animation1.11.2.
 * Copyright 2020-2025 The Android Open Source Project. Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.TransitionValue
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.AnimationVector2D
import smallthingz.ui.animation.core.Spring
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.unit.IntSize
import kotlin.math.roundToLong

// SpringSpec is immutable; both channels keep their exact source stiffness/threshold.
private val expansionSizeSpec = SpringSpec(stiffness=Spring.StiffnessMediumLow,visibilityThreshold=IntSize(1,1))
private val expansionFadeSpec = SpringSpec<Float>(stiffness=Spring.StiffnessMediumLow)

/** OutputCompressionSettings uses Top alignment; SkipSilence can reuse the same math with Bottom. */
internal class SettingsExpansionMotion(initiallyVisible:Boolean) {
    private enum class Phase {PRE_ENTER,VISIBLE,POST_EXIT}
    private var current=if(initiallyVisible)Phase.VISIBLE else Phase.PRE_ENTER
    private var target=current
    private var full:IntSize?=null
    private var size:TransitionValue<IntSize,AnimationVector2D>?=null
    private var fade:TransitionValue<Float,AnimationVector1D>?=
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
        fade!!.target(if(value)1f else 0f,expansionFadeSpec,playTime,true)
        stateUpdate=true
        full?.let(::measure)
    }
    fun measure(value:IntSize) {
        full=value
        if(size==null)size=TransitionValue(
            if(current==Phase.VISIBLE)value else IntSize(value.width,0),
            IntSize.VectorConverter,
            expansionSizeSpec,
            {AnimationVector2D(0f,0f)})
        if(fade==null)fade=newFade(if(current==Phase.VISIBLE)1f else 0f)
        if(current==target&&target==Phase.VISIBLE&&!running&&!stateUpdate) {
            size!!.snap(value);fade!!.snap(1f);return
        }
        size!!.target(if(visibleTarget)value else IntSize(value.width,0),expansionSizeSpec,playTime,stateUpdate)
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
    private fun newFade(value:Float)=TransitionValue(value,Float.VectorConverter,
        expansionFadeSpec,{AnimationVector1D(0f)})
}
