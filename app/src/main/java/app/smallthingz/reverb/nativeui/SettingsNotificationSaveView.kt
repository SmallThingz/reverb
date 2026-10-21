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
):ViewGroup(context),AutoCloseable, app.smallthingz.reverb.NativeAndroidInteropHost {
    override val nativeInteropRequests = app.smallthingz.reverb.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private var state=initial
    private val row=SettingsToggleRowView(context,context.getString(R.string.feature_notification_save),
        initial.enabled,initial.interactionReady,environment,inputRoot,false,onEnabledChange)
    private var onInteractionChanged:()->Unit={}
    private var wheel:NativeNotificationDurationWheel?=null
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
            val current=wheel?:NativeNotificationDurationWheel(context,lifecycle,interactionState,
                {readEditRevision()},{seconds->
                    onSecondsChange(seconds)
                    readEditRevision()?.let { NotificationDurationCommit(seconds,it) }
                },interactionChanged={onInteractionChanged()}).also {wheel=it;addView(it.view)}
            current.bind(next.seconds,next.maximumSeconds,next.interactionReady,next.editRevision,colors)
        } else wheel?.let { old->
            wheel=null
            try {old.close()} finally {removeView(old.view)}
        }
        requestLayout()
    }
    fun callbacks(onSeconds:(Int)->Unit,owner:()->Long?,interactionChanged:()->Unit={}) {
        onSecondsChange=onSeconds;readEditRevision=owner;onInteractionChanged=interactionChanged
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

