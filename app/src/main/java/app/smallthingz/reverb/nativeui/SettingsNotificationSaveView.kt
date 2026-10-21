package app.smallthingz.reverb.nativeui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.NotificationDurationBinding
import app.smallthingz.reverb.NotificationDurationCommit
import app.smallthingz.reverb.NotificationDurationInteractionState
import app.smallthingz.reverb.RangeDurationWheelView
import app.smallthingz.reverb.R
import app.smallthingz.reverb.notificationDurationStepSeconds
import kotlin.math.roundToInt

internal data class SettingsNotificationSaveState(
    val enabled:Boolean,
    val seconds:Int,
    val maximumSeconds:Int,
    val interactionReady:Boolean,
    val editRevision:Long,
)

/** NotificationSaveSettings39–214:conditional existing native wheel; no expansion animation. */
internal class SettingsNotificationSaveView(
    context:Context,
    initial:SettingsNotificationSaveState,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    private val lifecycle:Lifecycle,
    private val interactionState:NotificationDurationInteractionState,
    onEnabledChange:(Boolean)->Unit,
    private var onSecondsChange:(Int)->Unit,
    private var readEditRevision:()->Long?,
):ViewGroup(context),AutoCloseable {
    private var state=initial
    private val row=SettingsToggleRowView(context,context.getString(R.string.feature_notification_save),
        initial.enabled,initial.interactionReady,environment,inputRoot,false,onEnabledChange)
    private var wheel:NotificationWheelOwner?=null
    private var closed=false
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(row);bind(initial,environment)
    }
    fun bind(next:SettingsNotificationSaveState,colors:ReverbViewEnvironment) {
        check(!closed)
        state=next;environment=colors
        row.bind(next.enabled,next.interactionReady,colors)
        if(next.enabled) {
            val current=wheel?:NotificationWheelOwner(context,lifecycle,interactionState,
                {readEditRevision()},{seconds->
                    onSecondsChange(seconds)
                    readEditRevision()?.let { NotificationDurationCommit(seconds,it) }
                }).also {wheel=it;addView(it.view)}
            current.bind(next,colors)
        } else wheel?.let { old->
            wheel=null
            try {old.close()} finally {removeView(old.view)}
        }
        requestLayout()
    }
    fun callbacks(onSeconds:(Int)->Unit,owner:()->Long?) {
        onSecondsChange=onSeconds;readEditRevision=owner
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        row.measure(exact(width),unspecified())
        wheel?.view?.measure(exact((width-2*dp(14)).coerceAtLeast(0)),exact(dp(160)))
        val extra=wheel?.let {2*dp(8)+it.view.measuredHeight}?:0
        setMeasuredDimension(resolveSize(width,w),resolveSize(row.measuredHeight+extra,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        row.layout(0,0,width,row.measuredHeight)
        wheel?.view?.let {view->
            val y=row.measuredHeight+dp(8)
            view.layout(dp(14),y,width-dp(14),y+view.measuredHeight)
        }
    }
    override fun close() {
        if(closed)return
        closed=true;row.close()
        wheel?.close();wheel=null
    }
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

/** Mechanical imperative extraction of the released AndroidView update/onRelease binding. */
private class NotificationWheelOwner(
    context:Context,
    private val lifecycle:Lifecycle,
    interactionState:NotificationDurationInteractionState,
    private val readOwner:()->Long?,
    private val change:(Int)->NotificationDurationCommit?,
):AutoCloseable {
    val view=RangeDurationWheelView(context)
    private val binding=NotificationDurationBinding(interactionState)
    private var maximum=1
    private var enabled=false
    private var closed=false
    private val observer=LifecycleEventObserver {_,event->
        if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP)binding.invalidateInteraction()
    }
    init {
        lifecycle.addObserver(observer)
        view.setAccessibilityLabel(context.getString(R.string.range_export_duration))
        view.onInteractionStart={binding.beginInteraction(readOwner())}
        view.onDurationChanged=binding::select
        view.onInteractionEnd={
            binding.finishInteraction(readOwner(),change) { canonical->
                view.setDurationSeconds(canonical.toDouble())
            }
        }
        view.setOnKeyListener {_,code,event->
            if(event.action!=KeyEvent.ACTION_DOWN)false
            else when(code) {
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_RIGHT->step(true)
                KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT->step(false)
                else->false
            }
        }
        view.accessibilityDelegate=object:View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.className=android.widget.SeekBar::class.java.name
                info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,1f,maximum.toFloat(),
                    binding.seconds.coerceIn(1,maximum).toFloat())
                if(enabled) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
            }
            override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean=when(action) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id->{
                    val value=args?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN)?:Float.NaN
                    value.isFinite()&&commit(value.roundToInt())
                }
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->step(true)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->step(false)
                else->super.performAccessibilityAction(host,action,args)
            }
        }
    }
    fun bind(state:SettingsNotificationSaveState,environment:ReverbViewEnvironment) {
        maximum=state.maximumSeconds.coerceIn(1,86_400);enabled=state.interactionReady
        binding.update(state.seconds,maximum,enabled,state.editRevision)
        view.isEnabled=enabled
        with(environment.palette) {view.setPalette(ink=onSurface,muted=onSurfaceVariant,border=outlineVariant,error=error)}
        view.setMaximumDurationSeconds(maximum.toDouble())
        view.setDurationSeconds(binding.seconds.toDouble())
    }
    private fun commit(value:Int):Boolean {
        val accepted=binding.commitInput(value,readOwner(),change)?:return false
        view.setDurationSeconds(accepted.toDouble());return true
    }
    private fun step(forward:Boolean)=commit(notificationDurationStepSeconds(binding.seconds,maximum,forward))
    override fun close() {
        if(closed)return
        closed=true
        lifecycle.removeObserver(observer)
        binding.invalidateInteraction()
        binding.release()
        view.onInteractionStart=null;view.onDurationChanged=null;view.onInteractionEnd=null
        view.setOnKeyListener(null);view.accessibilityDelegate=null
    }
}
