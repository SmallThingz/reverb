/*
 * Geometry, colors, track drawing and keyboard behavior from AndroidX Material3 1.4.0 Slider.kt.
 * Copyright 2022 The Android Open Source Project. Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.graphics.Color
import app.smallthingz.nativeui.graphics.compositeOver
import app.smallthingz.nativeui.graphics.toArgb
import app.smallthingz.nativeui.input.pointer.PointerType
import app.smallthingz.nativeui.unit.Velocity
import app.smallthingz.nativeui.util.lerp
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeDragAxis
import app.smallthingz.reverb.NativeDragPhaseNode
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativeDragPointerRouter
import app.smallthingz.reverb.NativeDragSink
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Existing source compression range only. Host installs [pointerNodes] on [pointerView] in its root. */
internal class CompressionSliderView(
    context:Context,
    initialLevel:Int,
    initialEnabled:Boolean,
    private var environment:ReverbViewEnvironment,
    private var onLevelChange:((Int)->Unit)?,
    parentInterested:(NativeDragPointerChange)->Boolean,
):ViewGroup(context),AutoCloseable {
    private val body=SliderBody(context,initialLevel,initialEnabled,environment,parentInterested) { level->
        onLevelChange?.invoke(level)
    }
    val pointerView:View get()=body
    /** Source order is tap modifier then draggable; Main pass dispatches the draggable first. */
    val pointerNodes:List<NativeViewPhasePointerNode> get()=body.nodes
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=false;clipToPadding=false
        addView(body)
    }
    fun bind(level:Int,enabled:Boolean,next:ReverbViewEnvironment) {
        environment=next;body.bind(level,enabled,next);requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        body.measure(exact(width),unspecified())
        setMeasuredDimension(resolveSize(max((48f*environment.textDensity.density).roundToInt(),body.measuredWidth),w),
            resolveSize(max((48f*environment.textDensity.density).roundToInt(),body.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-body.measuredWidth)/2f).roundToInt()
        val y=((height-body.measuredHeight)/2f).roundToInt()
        body.layout(x,y,x+body.measuredWidth,y+body.measuredHeight)
    }
    override fun close() {onLevelChange=null;body.close()}
}

private class SliderBody(
    context:Context,
    initialLevel:Int,
    enabled:Boolean,
    private var environment:ReverbViewEnvironment,
    parentInterested:(NativeDragPointerChange)->Boolean,
    private var changed:((Int)->Unit)?,
):View(context),AutoCloseable {
    private val model=CompressionSliderModel(initialLevel)
    private val configuration=ViewConfiguration.get(context)
    private var dragging=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path=Path()
    private val radii=FloatArray(8)
    private var geometry=CompressionSliderGeometry.measure(0,environment.textDensity.density,model.fraction,false,false)
    private val tap=SliderTapNode(this,model,{isEnabled},{environment.textDensity.density},::requestLevel)
    private val drag=NativeDragPointerRouter(NativeDragAxis.HORIZONTAL,
        {configuration.scaledTouchSlop.toFloat()},{configuration.scaledMaximumFlingVelocity.toFloat()},
        {dragging},object:NativeDragSink {
            override fun start(position:Offset) {setDragging(true)}
            override fun delta(physicalDelta:Offset) {model.dragByPhysical(physicalDelta.x)?.let(::requestLevel)}
            override fun stop(physicalVelocity:Velocity) {setDragging(false)}
            override fun cancel() {setDragging(false)}
        },parentInterested)
    val nodes:List<NativeViewPhasePointerNode> = listOf(tap,NativeDragPhaseNode(this,drag))
    init {
        // M3 Slider keeps keyboard focus but draws only its source thumb and track.
        defaultFocusHighlightEnabled=false
        isEnabled=enabled;isFocusable=enabled
        isClickable=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this,true)
        contentDescription=context.getString(R.string.compression_level)
        if(Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
        drag.enabled=enabled
    }
    fun bind(level:Int,enabled:Boolean,next:ReverbViewEnvironment) {
        environment=next;model.bind(level)
        drag.enabled=enabled
        if(!enabled)tap.cancel()
        isEnabled=enabled;isFocusable=enabled
        requestLayout();invalidate()
    }
    private fun requestLevel(value:Float) {
        val next=value.roundToInt().coerceIn(0,8)
        // Exact OutputCompressionSettings wrapper suppresses redundant parent edits.
        if(next!=model.value.roundToInt())changed?.invoke(next)
    }
    private fun setDragging(value:Boolean) {
        if(dragging==value)return
        dragging=value;requestLayout();invalidate()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        geometry=CompressionSliderGeometry.measure(width,environment.textDensity.density,model.fraction,
            dragging,layoutDirection==LAYOUT_DIRECTION_RTL)
        model.width=width;model.thumbWidth=geometry.thumbWidth;model.rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        setMeasuredDimension(resolveSize(width,w),resolveSize(max(geometry.thumbHeight,geometry.trackHeight),h))
    }
    override fun onRtlPropertiesChanged(layoutDirection:Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        model.rtl=layoutDirection==LAYOUT_DIRECTION_RTL;requestLayout();invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        val g=geometry
        val palette=environment.palette
        val primary=if(isEnabled)palette.primary else Color(palette.onSurface).copy(alpha=.38f).toArgb()
        val secondary=if(isEnabled)palette.secondaryContainer else Color(palette.onSurface).copy(alpha=.12f).toArgb()
        val thumbColor=if(isEnabled)palette.primary else
            Color(palette.onSurface).copy(alpha=.38f).compositeOver(Color(palette.surface)).toArgb()
        val save=canvas.save()
        try {
            canvas.translate(g.trackX.toFloat(),(g.trackY-g.visualTop).toFloat())
            drawTrack(canvas,g,primary,secondary)
        }finally{canvas.restoreToCount(save)}
        paint.color=thumbColor
        val x=g.thumbX.toFloat();val y=(g.thumbY-g.visualTop).toFloat()
        val radius=minOf(g.thumbWidth,g.thumbHeight)/2f
        canvas.drawRoundRect(x,y,x+g.thumbWidth,y+g.thumbHeight,radius,radius,paint)
    }
    private fun drawTrack(canvas:Canvas,g:CompressionSliderGeometry,active:Int,inactive:Int) {
        val density=environment.textDensity.density
        val width=g.trackWidth.toFloat()
        val height=g.trackHeight.toFloat()
        // Keep source px→dp→px round trips; do not algebraically remove Float rounding.
        val corner=(height/2f/density)*density
        val inside=2f*density
        val thumbPixels=(g.thumbWidth/density)*density
        val gap=thumbPixels/2f+6f*density
        val fraction=model.fraction
        val end=if(fraction==0f||fraction==1f)width*fraction else (width-corner*2)*fraction+corner
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        if(end<width-gap-corner) {
            val start=end+gap
            shape(canvas,if(rtl)0f else start,0f,width-start,height,
                if(rtl)corner else inside,if(rtl)inside else corner,inactive)
            circle(canvas,if(rtl)corner else width-corner,height/2f,2f*density,active)
        }
        val activeEnd=end-gap
        val startCorner=if(rtl)inside else corner
        val endCorner=if(rtl)corner else inside
        if(activeEnd>startCorner)shape(canvas,if(rtl)width-activeEnd else 0f,0f,activeEnd,height,startCorner,endCorner,active)
        // Last tick is represented by the stop indicator. Remaining tick centers use float lerp.
        for(index in 0 until 8) {
            val center=lerp(corner,width-corner,index/8f)
            if(center>=end-gap&&center<=end+gap)continue
            circle(canvas,if(rtl)width-center else center,height/2f,2f*density,
                if(center>=0f&&center<=activeEnd)inactive else active)
        }
    }
    private fun shape(canvas:Canvas,x:Float,y:Float,width:Float,height:Float,left:Float,right:Float,color:Int) {
        radii[0]=left;radii[1]=left;radii[2]=right;radii[3]=right
        radii[4]=right;radii[5]=right;radii[6]=left;radii[7]=left
        path.rewind();path.addRoundRect(x,y,x+width,y+height,radii,Path.Direction.CCW)
        paint.color=color;canvas.drawPath(path,paint)
    }
    private fun circle(canvas:Canvas,x:Float,y:Float,radius:Float,color:Int) {
        paint.color=color;canvas.drawCircle(x,y,radius,paint)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        val command=sliderKey(keyCode)?:return super.onKeyDown(keyCode,event)
        if(!isEnabled||changed==null)return false
        requestLevel(model.key(command));return true
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {
        if(sliderKey(keyCode)==null)return super.onKeyUp(keyCode,event)
        return isEnabled&&changed!=null // Released onValueChangeFinished is null.
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className=android.widget.SeekBar::class.java.name
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0f,8f,model.value)
        AccessibilityNodeInfoCompat.wrap(info).stateDescription="${(model.value*100).roundToInt()/100f}"
        info.isClickable=false;info.isCheckable=false
        if(isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            if(model.value<8f)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if(model.value>0f)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        // Source IncreaseHorizontalSemanticsBounds:10dp per horizontal edge, not vertical padding.
        val bounds=Rect();info.getBoundsInScreen(bounds)
        val pad=(10f*environment.textDensity.density).roundToInt()
        bounds.left-=pad;bounds.right+=pad;info.setBoundsInScreen(bounds)
        info.getBoundsInParent(bounds)
        bounds.left-=pad;bounds.right+=pad;info.setBoundsInParent(bounds)
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {
        val next=when(action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id->{
                if(!isEnabled||args?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)!=true)return false
                model.semanticProgress(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->{if(!isEnabled)return false;model.semanticProgress(model.value+1)}
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->{if(!isEnabled)return false;model.semanticProgress(model.value-1)}
            else->return super.performAccessibilityAction(action,args)
        }?:return false
        requestLevel(next);return true
    }
    override fun onDetachedFromWindow() {tap.cancel();drag.reset();super.onDetachedFromWindow()}
    override fun close() {changed=null;tap.cancel();drag.reset();drag.enabled=false;isEnabled=false;isFocusable=false}
    private fun sliderKey(code:Int):CompressionSliderKey?=when(code) {
        KeyEvent.KEYCODE_DPAD_UP->CompressionSliderKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN->CompressionSliderKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT->CompressionSliderKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT->CompressionSliderKey.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME->CompressionSliderKey.HOME
        KeyEvent.KEYCODE_MOVE_END->CompressionSliderKey.END
        KeyEvent.KEYCODE_PAGE_UP->CompressionSliderKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN->CompressionSliderKey.PAGE_DOWN
        else->null
    }
}

/** detectTapGestures with ONLY onPress/onTap; no click indication, timeout or long-press branch. */
private class SliderTapNode(
    override val view:View,
    private val model:CompressionSliderModel,
    private val enabled:()->Boolean,
    private val density:()->Float,
    private val changed:(Float)->Unit,
):NativeViewPhasePointerNode {
    private var tracking=false
    private var verifyFinal=false
    override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass) {
        if(!enabled()) {cancel();return}
        if(pass==NativePointerPass.INITIAL)return
        if(!tracking) {
            // Android awaitFirstDown accepts any mouse button; event.isDown() is primary-only.
            if(pass==NativePointerPass.MAIN&&event.changes.isNotEmpty()&&
                event.changes.all {it.changedToDown&&!it.consumed}) {
                val down=event.changes.first()
                down.consume();tracking=true;verifyFinal=false
                model.press(down.position.x)
            }
            return
        }
        if(pass==NativePointerPass.MAIN) {
            if(event.changes.all {it.changedToUp&&!it.consumed}) {
                event.changes.firstOrNull()?.consume()
                tracking=false;verifyFinal=false;model.tap()?.let(changed)
            } else if(event.changes.any {it.consumed||outside(it)})cancel()
            else verifyFinal=true
        } else if(verifyFinal&&event.changes.any {it.consumed})cancel()
    }
    private fun outside(change:NativeDragPointerChange):Boolean {
        val minimum=48f*density()
        val horizontal=if(change.type==PointerType.Touch)max(0f,(minimum-view.width)/2f)else 0f
        val vertical=if(change.type==PointerType.Touch)max(0f,(minimum-view.height)/2f)else 0f
        return change.position.x< -horizontal||change.position.x>view.width+horizontal||
            change.position.y< -vertical||change.position.y>view.height+vertical
    }
    override fun cancel() {tracking=false;verifyFinal=false}
}
