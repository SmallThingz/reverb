/*
 * Default determinate LinearProgressIndicator rendering from AndroidX Material3 1.4.0.
 * Copyright 2024 The Android Open Source Project. Apache-2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.min
import kotlin.math.roundToInt

/** The SkipSilenceSettings determinate overload has no implicit progress animation. */
internal class SettingsCalibrationProgressView(
    context:Context,
    private var environment:ReverbViewEnvironment,
):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var progress=0f
    private var geometry:NativePlainTextViewGeometry?=null
    private var exposure:(()->Boolean)?=null
    private var geometryToken:Any?=null
    private val manager=context.getSystemService(AccessibilityManager::class.java)
    private var lastSemanticsProgress=0f
    private var semanticsPosted=false
    private val semanticsChanged=Runnable {
        semanticsPosted=false
        val next=progress
        val previous=lastSemanticsProgress
        lastSemanticsProgress=next
        if(next!=previous && isAttachedToWindow && manager?.isEnabled==true && sourceNode()?.present!=false) {
            sourceContentEvent(AccessibilityEventCompat.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION)
            // Pinned delegate2643–2658 refreshes its accessibility-focused node with a second event.
            sourceContentEvent(AccessibilityEventCompat.CONTENT_CHANGE_TYPE_UNDEFINED)
        }
    }

    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this,true)
        if(android.os.Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
    }
    fun bind(value:Float,next:ReverbViewEnvironment) {
        val densityChanged=environment.textDensity.density!=next.textDensity.density
        val changedProgress=calibrationProgressValue(value)
        val progressChanged=progress!=changedProgress
        environment=next
        progress=changedProgress
        if(densityChanged)requestLayout()
        invalidate()
        if(progressChanged && isAttachedToWindow && manager?.isEnabled==true && !semanticsPosted) {
            semanticsPosted=true
            post(semanticsChanged)
        } else if(!isAttachedToWindow || manager?.isEnabled!=true) lastSemanticsProgress=progress
    }

    /** Apply negative semantics padding BEFORE mapping and ancestor clipping, as the source does. */
    fun semanticsBoundsInView():Rect {
        val padding=(10f*environment.textDensity.density).roundToInt().toFloat()
        return Rect(0f,-padding,width.toFloat(),height+padding)
    }
    fun bindAccessibility(value:NativePlainTextViewGeometry,exposed:()->Boolean):AutoCloseable {
        val token=Any()
        geometryToken=token;geometry=value;exposure=exposed
        return AutoCloseable {
            if(geometryToken===token) {geometryToken=null;geometry=null;exposure=null}
        }
    }
    private fun sourceNode():NativeSourceAccessibilityNode? {
        val mapping=geometry?:return null
        val captured=mapping.capture(this,Offset.Zero,semanticsBoundsInView())
        val bounds=captured?.let {nativeMergedRoleBoundsInScreen(it,isLaidOut)}?:android.graphics.Rect()
        var hidden=exposure?.invoke()!=true
        var ancestor:View?=this
        while(ancestor!=null) {
            if(ancestor.alpha==0f || ancestor.visibility!=VISIBLE)hidden=true
            ancestor=ancestor.parent as? View
        }
        val present=isAttachedToWindow && isShown && captured!=null && !captured.visibleBoundsInRoot.isEmpty
        return NativeSourceAccessibilityNode(present,hidden,present&&!hidden,bounds)
    }

    override fun onMeasure(w:Int,h:Int) {
        val density=environment.textDensity.density
        setMeasuredDimension(resolveSize((240f*density).roundToInt(),w),resolveSize((4f*density).roundToInt(),h))
    }
    override fun onDraw(canvas:Canvas) {
        val density=environment.textDensity.density
        val stroke=height.toFloat()
        val adjustedGap=if(height>width)4f else 4f+stroke/density
        val gapFraction=adjustedGap/(width/density)
        val trackStart=progress+min(progress,gapFraction)
        if(trackStart<=1f)line(canvas,trackStart,1f,environment.palette.secondaryContainer)
        line(canvas,0f,progress,environment.palette.primary)
        val size=min(4f*density,stroke)
        val offset=min((stroke-size)/2f,6f*density)
        paint.style=Paint.Style.FILL;paint.color=environment.palette.primary
        val save=canvas.save()
        try {
            if(layoutDirection==LAYOUT_DIRECTION_RTL) {
                canvas.translate(width/2f,height/2f);canvas.scale(-1f,1f);canvas.translate(-width/2f,-height/2f)
            }
            canvas.drawCircle(width-size/2f-offset,height/2f,size/2f,paint)
        }finally {canvas.restoreToCount(save)}
    }
    private fun line(canvas:Canvas,start:Float,end:Float,color:Int) {
        paint.color=color;paint.strokeWidth=height.toFloat();paint.style=Paint.Style.STROKE
        nativeLinearProgressLine(width.toFloat(),height.toFloat(),layoutDirection==LAYOUT_DIRECTION_RTL,
            start,end) {a,b,y,round->
            paint.strokeCap=if(round)Paint.Cap.ROUND else Paint.Cap.BUTT
            canvas.drawLine(a,y,b,y,paint)
        }
    }
    override fun createAccessibilityNodeInfo():AccessibilityNodeInfo?=
        if(sourceNode()?.present==false)null else super.createAccessibilityNodeInfo()
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.widget.ProgressBar"
        info.isEnabled=true;info.isFocusable=false;info.isFocused=false
        info.isClickable=false;info.isLongClickable=false;info.isSelected=false
        info.isCheckable=false;info.isChecked=false
        info.text=null;info.contentDescription=null
        info.setBoundsInParent(android.graphics.Rect())
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0f,1f,progress)
        AccessibilityNodeInfoCompat.wrap(info).stateDescription=context.getString(
            app.smallthingz.nativeui.R.string.template_percent,calibrationProgressPercent(progress))
        info.actionList.toList().forEach(info::removeAction)
        info.addAction(if(isAccessibilityFocused)AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        sourceNode()?.populate(info)
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean=when(action) {
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS->super.performAccessibilityAction(action,args)
        else->false
    }
    override fun onInitializeAccessibilityEvent(event:AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className="android.view.View";event.isEnabled=true
        event.text.clear();event.contentDescription=null
    }
    private fun sourceContentEvent(change:Int) {
        @Suppress("DEPRECATION")
        val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            className="android.view.View";isEnabled=true;packageName=context.packageName
            contentChangeTypes=change;setSource(this@SettingsCalibrationProgressView)
        }
        parent?.requestSendAccessibilityEvent(this,event)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastSemanticsProgress=progress
    }
    override fun onRtlPropertiesChanged(layoutDirection:Int) {
        super.onRtlPropertiesChanged(layoutDirection);invalidate()
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(semanticsChanged);semanticsPosted=false
        super.onDetachedFromWindow()
    }
}

/** Released caller rejects non-finite samples before M3's coercedProgress lambda. */
internal fun calibrationProgressValue(value:Float)=if(value.isFinite())value.coerceIn(0f,1f)else 0f

/** Pinned accessibility delegate exposes endpoints only for exact endpoint values. */
internal fun calibrationProgressPercent(value:Float):Int=when(value) {
    0f->0
    1f->100
    else->(value*100).roundToInt().coerceIn(1,99)
}
