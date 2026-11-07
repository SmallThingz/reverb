package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.R
import app.smallthingz.reverb.SILENCE_DEFAULT_CLEARANCE_DB
import app.smallthingz.reverb.SILENCE_MIN_CLEARANCE_DB
import app.smallthingz.reverb.SILENCE_MAX_CLEARANCE_DB
import smallthingz.ui.animation.view.ViewColorTransition
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A crescent whose bead follows the tangential component of each pointer movement. */
internal class SettingsCalibrationFitDial(
    context:Context,
    private var environment:ReverbViewEnvironment,
    private var change:((Float)->Unit)?,
):ViewGroup(context),AutoCloseable {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var value=SILENCE_DEFAULT_CLEARANCE_DB
    private var pointer=-1
    private var lastX=0f
    private var lastY=0f
    private val valueLabel=environment.paragraph(context,context.getString(R.string.silence_fit_short,6),ReverbTextRole.LABEL_LARGE,environment.palette.ink)
    private var faceHeight=0
    private val sizingValue=environment.paragraph(context,context.getString(R.string.silence_fit_short,SILENCE_MAX_CLEARANCE_DB.roundToInt()),
        ReverbTextRole.LABEL_LARGE,environment.palette.ink)
    private val colors=ViewColorTransition(this,intArrayOf(environment.palette.muted,environment.palette.primary),{invalidate()})
    init {
        setWillNotDraw(false)
        addView(valueLabel)
        valueLabel.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        isFocusable=true
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription=context.getString(R.string.silence_fit_description)
        if(android.os.Build.VERSION.SDK_INT>=29)isForceDarkAllowed=false
    }
    fun bind(clearance:Float,enabled:Boolean,next:ReverbViewEnvironment) {
        value=clearance;environment=next;isEnabled=enabled;isFocusable=enabled
        colors.retarget(next.palette.muted,next.palette.primary)
        environment.bindParagraphStyle(valueLabel,ReverbTextRole.LABEL_LARGE,environment.palette.ink)
        valueLabel.setText(context.getString(R.string.silence_fit_short,value.roundToInt()))
        valueLabel.alpha=if(enabled)1f else .5f
        if(!enabled)cancelDrag()
        ViewCompat.setStateDescription(this,context.getString(R.string.silence_fit_value,value.roundToInt()))
        invalidate()
    }
    private fun setValue(next:Float):Boolean {
        if(!isEnabled||!next.isFinite()||change==null)return false
        val bounded=next.coerceIn(SILENCE_MIN_CLEARANCE_DB,SILENCE_MAX_CLEARANCE_DB)
        if(bounded==value)return false
        value=bounded
        valueLabel.setText(context.getString(R.string.silence_fit_short,value.roundToInt()))
        ViewCompat.setStateDescription(this,context.getString(R.string.silence_fit_value,value.roundToInt()))
        invalidate();change?.invoke(value);return true
    }
    override fun onMeasure(w:Int,h:Int) {
        val density=environment.textDensity.density
        val width=resolveSize((104*density).roundToInt(),w)
        environment.bindParagraphStyle(sizingValue,ReverbTextRole.LABEL_LARGE,environment.palette.ink)
        sizingValue.measure(atMost(width),unspecified())
        valueLabel.measure(atMost(width),unspecified())
        faceHeight=maxOf((64*density).roundToInt(),sizingValue.measuredHeight+(24*density).roundToInt())
        setMeasuredDimension(width,resolveSize(faceHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=(width-valueLabel.measuredWidth)/2
        val y=(faceHeight-valueLabel.measuredHeight)/2
        valueLabel.layout(x,y,x+valueLabel.measuredWidth,y+valueLabel.measuredHeight)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val d=environment.textDensity.density
        val cx=width/2f;val cy=faceHeight/2f;val radius=minOf(width*.36f,faceHeight*.42f)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=3*d;paint.strokeCap=Paint.Cap.ROUND
        paint.color=colors.color(0);paint.alpha=if(isEnabled)255 else 45
        canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,135f,270f,false,paint)
        paint.color=colors.color(1);paint.alpha=if(isEnabled)255 else 90
        val fraction=calibrationDialFraction(value)
        canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,135f,(270*fraction).toFloat(),false,paint)
        val angle=Math.toRadians((135+270*fraction).toDouble())
        paint.style=Paint.Style.FILL
        val bx=cx+radius*cos(angle).toFloat();val by=cy+radius*sin(angle).toFloat()
        canvas.drawCircle(bx,by,5*d,paint)
        if(isFocused) {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2*d;paint.color=colors.color(1);paint.alpha=255
            canvas.drawRoundRect(d,d,width-d,height-d,12*d,12*d,paint)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{
                pointer=event.getPointerId(0);lastX=event.x;lastY=event.y
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true);return true
            }
            MotionEvent.ACTION_MOVE->{
                val index=event.findPointerIndex(pointer)
                if(index<0){cancelDrag();return false}
                val x=event.getX(index);val y=event.getY(index)
                val delta=calibrationDialDelta(value,x-lastX,y-lastY,environment.textDensity.density)
                lastX=x;lastY=y
                setValue(value+delta);return true
            }
            MotionEvent.ACTION_UP->{cancelDrag();performClick();return true}
            MotionEvent.ACTION_CANCEL->{cancelDrag();return true}
            MotionEvent.ACTION_POINTER_UP->if(event.getPointerId(event.actionIndex)==pointer)cancelDrag()
        }
        return pointer!=-1
    }
    // A click only announces this control. It never starts sampling or jumps the fit.
    override fun performClick():Boolean {super.performClick();return true}
    private fun cancelDrag() {
        if(pointer!=-1) {pointer=-1;parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
    }
    override fun onDetachedFromWindow() {cancelDrag();super.onDetachedFromWindow()}
    override fun onCancelPendingInputEvents() {cancelDrag();super.onCancelPendingInputEvents()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean=when(keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP->setValue(value+1f)||isEnabled
        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_DOWN->setValue(value-1f)||isEnabled
        KeyEvent.KEYCODE_MOVE_HOME->setValue(SILENCE_MIN_CLEARANCE_DB)||isEnabled
        KeyEvent.KEYCODE_MOVE_END->setValue(SILENCE_MAX_CLEARANCE_DB)||isEnabled
        KeyEvent.KEYCODE_R->setValue(SILENCE_DEFAULT_CLEARANCE_DB)||isEnabled
        else->super.onKeyDown(keyCode,event)
    }
    // RecyclerView also handles Home/End on key-up when its focused child does not consume it.
    // Keep both halves of the dial's adjustment keys local; only key-down changes the value.
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean=when(keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME,KeyEvent.KEYCODE_MOVE_END,KeyEvent.KEYCODE_R->
            isEnabled||super.onKeyUp(keyCode,event)
        else->super.onKeyUp(keyCode,event)
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.widget.SeekBar"
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,SILENCE_MIN_CLEARANCE_DB,SILENCE_MAX_CLEARANCE_DB,value)
        if(isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.silence_fit_reset,context.getString(R.string.silence_fit_reset)))
        }
    }
    override fun performAccessibilityAction(action:Int,arguments:Bundle?):Boolean=when(action) {
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id->arguments?.takeIf {it.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)}
            ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.let(::setValue) ?: false
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->setValue(value+1f)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->setValue(value-1f)
        R.id.silence_fit_reset->setValue(SILENCE_DEFAULT_CLEARANCE_DB)
        else->super.performAccessibilityAction(action,arguments)
    }
    override fun close() {cancelDrag();change=null;colors.close()}
}
