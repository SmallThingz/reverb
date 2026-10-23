package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.unit.dp
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import app.smallthingz.reverb.R
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** SettingsScreen1946–2094. Source uses its custom44×26 switch and NO row ripple. */
internal class SettingsWakeLockView(
    context:Context,
    checked:Boolean,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    onChecked:(Boolean)->Unit,
):ViewGroup(context),AutoCloseable {
    private val surface=ReverbGroupLayout(context,20f)
    private val row=WakeLockRow(context,checked,environment,inputRoot,onChecked)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(row,ReverbGroupLayout.LayoutParams(-1,-2))
        surface.setPalette(environment.palette.field,environment.palette.border)
        addView(surface)
    }
    fun bind(checked:Boolean,next:ReverbViewEnvironment) {
        environment=next;surface.setPalette(next.palette.field,next.palette.border);row.bind(checked,next);requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        surface.measure(exact((width-2*dp(16)).coerceAtLeast(0)),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(dp(16)+2*dp(4)+surface.measuredHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        surface.layout(dp(16),dp(16)+dp(4),width-dp(16),dp(16)+dp(4)+surface.measuredHeight)
    }
    override fun close()=row.close()
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

private class WakeLockRow(
    context:Context,
    checked:Boolean,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    private var changed:((Boolean)->Unit)?,
):ViewGroup(context),NativeClickPhaseHost,NativeMergedRowAccessibilityHost,AutoCloseable {
    private var checked=checked
    private val title=environment.paragraph(context,context.getString(R.string.wake_lock_label),ReverbTextRole.BODY_LARGE)
    private val summary=environment.paragraph(context,context.getString(R.string.wake_lock_summary),ReverbTextRole.BODY_SMALL,environment.palette.muted)
    private val switch=WakeSwitch(context,checked,environment)
    private var textHeight=0
    private var textWidth=0
    private val presses=Collections.newSetFromMap(IdentityHashMap<NativePressToken,Boolean>())
    private val indication=object:NativeClickIndication {
        override fun press(token:NativePressToken) {presses.add(token);switch.pressed(presses.isNotEmpty())}
        override fun release(token:NativePressToken) {presses.remove(token);switch.pressed(presses.isNotEmpty())}
        override fun cancel(token:NativePressToken) {presses.remove(token);switch.pressed(presses.isNotEmpty())}
        override fun stateEnter(token:NativeStateToken)=Unit
        override fun stateExit(token:NativeStateToken)=Unit
    }
    private val input=ReverbClickController(this,object:NativeClickEnvironment {
        override val nativeInputRoot:View? get()=inputRoot()
        override val minimumTouchWidthPx:Float get()=48f*environment.textDensity.density
        override val minimumTouchHeightPx:Float get()=minimumTouchWidthPx
        override fun admitDown(event:MotionEvent)=reverbGroupGeometry(width.toFloat(),height.toFloat(),
            environment.textDensity.density,0f).acceptsPointer(event.x,event.y,
                event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER,minimumTouchWidthPx)
        override fun interestedParent(event:MotionEvent)=false
        override fun longPressOwnershipChanged(owns:Boolean)=Unit
    },indication)
    override val nativeClickEndpoint:NativeClickPhaseEndpoint=ReverbClickPhaseEndpoint(input)
    override val mergedRowAccessibility=ReverbMergedRowAccessibility(this,NativeMergedRowRole.SWITCH,
        {NativeMergedRowAccessibilityState(true,this.checked,null,listOf(title,summary))},
        {activate()})

    override fun addChildrenForAccessibility(outChildren:ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)

    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable=true;isFocusable=true;descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        ViewCompat.setScreenReaderFocusable(title,false);ViewCompat.setScreenReaderFocusable(summary,false)
        switch.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(title);addView(summary);addView(switch);addView(mergedRowAccessibility.roleView)
        accessibilityDelegate=mergedRowAccessibility
        input.bind(NativeClickBinding(this,true,{performClick()}))
    }
    fun bind(checked:Boolean,next:ReverbViewEnvironment) {
        this.checked=checked;environment=next
        next.bindParagraphStyle(title,ReverbTextRole.BODY_LARGE)
        next.bindParagraphStyle(summary,ReverbTextRole.BODY_SMALL,next.palette.muted)
        switch.bind(checked,next);requestLayout()
    }
    private fun activate():Boolean {
        val callback=changed?:return false
        callback(!checked);return true
    }
    override fun performClick():Boolean {
        if(changed==null)return false
        super.performClick();return activate()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(14)).coerceAtLeast(0)
        switch.measure(exact(minOf(dp(48),inner)),exact(dp(48)))
        val column=(inner-switch.measuredWidth).coerceAtLeast(0)
        textWidth=(column-dp(8)).coerceAtLeast(0)
        title.measure(atMost(textWidth),unspecified())
        summary.measure(atMost(textWidth),unspecified())
        textHeight=title.measuredHeight+dp(2)+summary.measuredHeight
        val height=2*dp(12)+max(textHeight,switch.measuredHeight)
        mergedRowAccessibility.roleView.measure(exact(width),exact(height))
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val inner=(height-2*dp(12)).coerceAtLeast(0)
        val y=dp(12)+((inner-textHeight)/2f).roundToInt()
        place(title,dp(14),y);place(summary,dp(14),y+title.measuredHeight+dp(2))
        place(switch,width-dp(14)-switch.measuredWidth,dp(12)+((inner-switch.measuredHeight)/2f).roundToInt())
        mergedRowAccessibility.roleView.layout(0,0,width,height)
    }
    private fun place(view:View,start:Int,y:Int) {
        val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-start-view.measuredWidth else start
        view.layout(x,y,x+view.measuredWidth,y+view.measuredHeight)
    }
    override fun onTouchEvent(event:MotionEvent)=input.touch(event)
    override fun onKeyDown(code:Int,event:KeyEvent)=if(activationKey(code))input.key(event)else super.onKeyDown(code,event)
    override fun onKeyUp(code:Int,event:KeyEvent)=if(activationKey(code))input.key(event)else super.onKeyUp(code,event)
    override fun onHoverEvent(event:MotionEvent):Boolean {val native=super.onHoverEvent(event);return input.hover(event)||native}
    override fun onFocusChanged(gain:Boolean,direction:Int,previous:Rect?) {super.onFocusChanged(gain,direction,previous);input.focusChanged(gain)}
    override fun onAttachedToWindow() {super.onAttachedToWindow();input.attached();if(changed!=null)input.bind(NativeClickBinding(this,true,{performClick()}))}
    override fun onDetachedFromWindow() {input.detached();super.onDetachedFromWindow()}
    override fun onCancelPendingInputEvents() {super.onCancelPendingInputEvents();input.cancel()}
    override fun close() {changed=null;input.bind(null);switch.close()}
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
    private fun activationKey(code:Int)=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||
        code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_SPACE
}

/** Dp state is retained until layout rounding; all four released animateAsState channels are separate. */
private class WakeSwitch(context:Context,checked:Boolean,private var environment:ReverbViewEnvironment):ViewGroup(context),AutoCloseable {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val track=ReleasedSpringValue.color(Color(if(checked)environment.palette.primary else environment.palette.raised))
    private val thumb=ReleasedSpringValue.color(Color(if(checked)environment.palette.onPrimary else
        reverbColorWithAlpha(environment.palette.onSurfaceVariant,.84f)))
    private val offset=ReleasedSpringValue.dp(if(checked)18.dp else 0.dp)
    private val overlay=ReleasedSpringValue.float(0f)
    private val thumbView=object:View(context) {
        override fun onDraw(canvas:Canvas) {
            paint.color=thumb.value.toArgb()
            canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),width/2f,height/2f,paint)
        }
    }
    private val trackView=object:ViewGroup(context) {
        private val clip=Path()
        init {
            setWillNotDraw(false);clipToOutline=true
            outlineProvider=object:ViewOutlineProvider() {
                override fun getOutline(view:View,outline:Outline) {
                    outline.setRoundRect(0,0,view.width,view.height,minOf(13f*environment.textDensity.density,minOf(view.width,view.height)/2f))
                }
            }
            addView(thumbView)
        }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            super.onSizeChanged(w,h,oldw,oldh)
            val radius=minOf(13f*environment.textDensity.density,minOf(w,h)/2f)
            clip.rewind();clip.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)
        }
        override fun onMeasure(w:Int,h:Int) {
            thumbView.measure(exact(dp(18)),exact(dp(18)))
            setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-dp(4)-thumbView.measuredWidth else dp(4)
            thumbView.layout(x,dp(4),x+thumbView.measuredWidth,dp(4)+thumbView.measuredHeight)
            updateOffset();invalidateOutline()
        }
        override fun onDraw(canvas:Canvas) {
            paint.color=track.value.toArgb();canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.color=Color(environment.palette.onSurface).copy(alpha=this@WakeSwitch.overlay.value).toArgb()
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        }
        override fun draw(canvas:Canvas) {
            if(canvas.isHardwareAccelerated)super.draw(canvas)
            else {val save=canvas.save();try{canvas.clipPath(clip);super.draw(canvas)}finally{canvas.restoreToCount(save)}}
        }
    }
    private val trackDriver=ViewSpringDriver(this,track,{trackView.invalidate()})
    private val thumbDriver=ViewSpringDriver(this,thumb,{thumbView.invalidate()})
    private val offsetDriver=ViewSpringDriver(this,offset,{updateOffset()})
    private val overlayDriver=ViewSpringDriver(this,overlay,{trackView.invalidate()})
    init {addView(trackView);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
    fun bind(checked:Boolean,next:ReverbViewEnvironment) {
        environment=next
        trackDriver.retarget(Color(if(checked)next.palette.primary else next.palette.raised))
        thumbDriver.retarget(Color(if(checked)next.palette.onPrimary else reverbColorWithAlpha(next.palette.onSurfaceVariant,.84f)))
        offsetDriver.retarget(if(checked)18.dp else 0.dp)
        trackView.invalidate();requestLayout()
    }
    fun pressed(value:Boolean)=overlayDriver.retarget(if(value).12f else 0f)
    private fun updateOffset() {
        val shift=(offset.value.value*environment.textDensity.density).roundToInt()
        thumbView.translationX=(if(layoutDirection==LAYOUT_DIRECTION_RTL)-shift else shift).toFloat()
    }
    override fun onMeasure(w:Int,h:Int) {
        trackView.measure(exact(dp(44)),exact(dp(26)))
        setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-trackView.measuredWidth)/2f).roundToInt()
        val y=((height-trackView.measuredHeight)/2f).roundToInt()
        trackView.layout(x,y,x+trackView.measuredWidth,y+trackView.measuredHeight)
    }
    override fun close() {trackDriver.close();thumbDriver.close();offsetDriver.close();overlayDriver.close()}
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}
