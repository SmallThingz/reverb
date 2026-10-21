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

/** Mechanical imperative extraction of the released AndroidView update/onRelease binding. */
internal class NativeNotificationDurationWheel(
    context:Context,
    private val lifecycle:Lifecycle,
    interactionState:NotificationDurationInteractionState,
    private val readOwner:()->Long?,
    private val change:(Int)->NotificationDurationCommit?,
    private val stepDuration:(Int,Int,Boolean)->Int=::notificationDurationStepSeconds,
    private val interactionChanged:()->Unit={},
):AutoCloseable {
    val view=RangeDurationWheelView(context)
    private val binding=NotificationDurationBinding(interactionState)
    private var maximum=1
    private var enabled=false
    private var closed=false
    private val observer=LifecycleEventObserver {_,event->
        if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP) {
            binding.invalidateInteraction();interactionChanged()
        }
    }
    init {
        lifecycle.addObserver(observer)
        view.setAccessibilityLabel(context.getString(R.string.range_export_duration))
        view.onInteractionStart={binding.beginInteraction(readOwner());interactionChanged()}
        view.onDurationChanged=binding::select
        view.onInteractionEnd={
            try {
                binding.finishInteraction(readOwner(),change) { canonical->
                    view.setDurationSeconds(canonical.toDouble())
                }
            } finally {interactionChanged()}
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
    fun bind(seconds:Int,maximumSeconds:Int,interactionReady:Boolean,editRevision:Long,
             environment:ReverbViewEnvironment) {
        maximum=maximumSeconds.coerceIn(1,86_400);enabled=interactionReady
        binding.update(seconds,maximum,enabled,editRevision)
        view.isEnabled=enabled
        with(environment.palette) {view.setPalette(ink=onSurface,muted=onSurfaceVariant,border=outlineVariant,error=error)}
        view.setMaximumDurationSeconds(maximum.toDouble())
        view.setDurationSeconds(binding.seconds.toDouble())
    }
    private fun commit(value:Int):Boolean {
        val accepted=binding.commitInput(value,readOwner(),change)?:return false
        view.setDurationSeconds(accepted.toDouble())
        interactionChanged()
        return true
    }
    private fun step(forward:Boolean)=commit(stepDuration(binding.seconds,maximum,forward))
    override fun close() {
        if(closed)return
        closed=true
        lifecycle.removeObserver(observer)
        binding.invalidateInteraction()
        binding.release()
        interactionChanged()
        view.onInteractionStart=null;view.onDurationChanged=null;view.onInteractionEnd=null
        view.setOnKeyListener(null);view.accessibilityDelegate=null
    }
}
