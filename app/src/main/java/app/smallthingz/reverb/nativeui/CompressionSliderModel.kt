/*
 * Horizontal discrete specialization of AndroidX Material3 1.4.0 Slider.kt.
 * Copyright 2022 The Android Open Source Project. Licensed under Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.util.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class CompressionSliderGeometry(
    val width:Int,
    val outerHeight:Int,
    val visualTop:Int,
    val thumbWidth:Int,
    val thumbHeight:Int,
    val thumbX:Int,
    val thumbY:Int,
    val trackX:Int,
    val trackY:Int,
    val trackWidth:Int,
    val trackHeight:Int,
) {
    companion object {
        fun measure(width:Int,density:Float,fraction:Float,dragging:Boolean,rtl:Boolean):CompressionSliderGeometry {
            val thumbWidth=((if(dragging)2f else 4f)*density).roundToInt()
            val thumbHeight=(44f*density).roundToInt()
            val trackHeight=(16f*density).roundToInt()
            val trackWidth=(width-thumbWidth).coerceAtLeast(0)
            val visualHeight=max(trackHeight,thumbHeight)
            val outerHeight=max((48f*density).roundToInt(),visualHeight)
            val top=((outerHeight-visualHeight)/2f).roundToInt()
            val corner=trackHeight/2
            val thumbPosition=if(fraction==0f||fraction==1f)(trackWidth*fraction).roundToInt()
                else ((trackWidth-corner*2)*fraction).roundToInt()+corner
            val logicalTrack=thumbWidth/2
            return CompressionSliderGeometry(width,outerHeight,top,thumbWidth,thumbHeight,
                if(rtl)width-thumbPosition-thumbWidth else thumbPosition,
                top+(visualHeight-thumbHeight)/2,
                if(rtl)width-logicalTrack-trackWidth else logicalTrack,
                top+(visualHeight-trackHeight)/2,trackWidth,trackHeight)
        }
    }
}

/**
 * Keeps the source raw/press offsets. Accepted level is controlled by the Settings draft; requested
 * values never silently update the durable or rendered level. A callback rejection stays rejected.
 */
internal class CompressionSliderModel(initial:Int,private val range:IntRange=0..8) {
    init {require(range.first<range.last)}
    private val ticks=FloatArray(range.last-range.first+1) {it.toFloat()/(range.last-range.first)}
    var value=initial.coerceIn(range).toFloat()
        private set
    var width=0
    var thumbWidth=0
    var rtl=false
    private var rawOffset=0f
    private var pressOffset=0f
    val fraction:Float get()=((value-range.first)/(range.last-range.first)).coerceIn(0f,1f)
    fun bind(level:Int) {value=level.coerceIn(range).toFloat()}
    fun press(physicalX:Float) {pressOffset=(if(rtl)width-physicalX else physicalX)-rawOffset}
    fun dragByPhysical(delta:Float):Float?=dispatch(if(rtl)-delta else delta)
    fun tap():Float?=dispatch(0f)
    private fun dispatch(delta:Float):Float? {
        val maximum=max(width-thumbWidth/2f,0f)
        val minimum=min(thumbWidth/2f,maximum)
        rawOffset=rawOffset+delta+pressOffset
        pressOffset=0f
        val snapped=snap(rawOffset,minimum,maximum)
        val fraction=if(maximum-minimum==0f)0f else ((snapped-minimum)/(maximum-minimum)).coerceIn(0f,1f)
        val next=lerp(range.first.toFloat(),range.last.toFloat(),fraction)
        return next.takeUnless {it==value}
    }
    /** Source pointer snapping keeps the FIRST equally close tick. */
    private fun snap(input:Float,minimum:Float,maximum:Float):Float {
        var nearest=ticks[0]
        var distance=abs(lerp(minimum,maximum,nearest)-input)
        for(index in 1 until ticks.size) {
            val next=abs(lerp(minimum,maximum,ticks[index])-input)
            if(next<distance) {distance=next;nearest=ticks[index]}
        }
        return lerp(minimum,maximum,nearest)
    }
    /** Source SetProgress uses <= and therefore keeps the LAST equally close tick. */
    fun semanticProgress(requested:Float):Float? {
        var next=requested.coerceIn(range.first.toFloat(),range.last.toFloat())
        val original=next
        var distance=next
        for(tick in ticks) {
            val step=lerp(range.first.toFloat(),range.last.toFloat(),tick)
            if(abs(step-original)<=distance) {distance=abs(step-original);next=step}
        }
        return next.takeUnless {it==value}
    }
    fun key(command:CompressionSliderKey):Float {
        val sign=if(rtl)-1 else 1
        val delta=(range.last-range.first).toFloat()/(ticks.size-1)
        val next=when(command) {
            CompressionSliderKey.UP,CompressionSliderKey.RIGHT->value+sign*delta
            CompressionSliderKey.DOWN,CompressionSliderKey.LEFT->value-sign*delta
            CompressionSliderKey.HOME->range.first.toFloat()
            CompressionSliderKey.END->range.last.toFloat()
            CompressionSliderKey.PAGE_UP->value-((ticks.size-1)/10).coerceIn(1,10)*delta
            CompressionSliderKey.PAGE_DOWN->value+((ticks.size-1)/10).coerceIn(1,10)*delta
        }
        return next.coerceIn(range.first.toFloat(),range.last.toFloat())
    }
}
internal enum class CompressionSliderKey {UP,DOWN,LEFT,RIGHT,HOME,END,PAGE_UP,PAGE_DOWN}
