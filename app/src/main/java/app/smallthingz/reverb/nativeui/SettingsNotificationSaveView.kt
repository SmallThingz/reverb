package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.NotificationDurationCommit
import app.smallthingz.reverb.NotificationDurationInteractionState
import app.smallthingz.reverb.R
import kotlin.math.roundToInt
import smallthingz.ui.components.NativeViewAnimatorScale

internal typealias SettingsAnimatorDurationScale = NativeViewAnimatorScale

internal data class SettingsNotificationSaveState(
    val enabled:Boolean,
    val seconds:Int,
    val maximumSeconds:Int,
    val interactionReady:Boolean,
    val editRevision:Long,
)

/** The notification row expands in place and retains its disabled wheel through the reverse motion. */
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
):ViewGroup(context),AutoCloseable, smallthingz.ui.input.NativeAndroidInteropHost {
    override val nativeInteropRequests = smallthingz.ui.input.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private var state=initial
    private val row=SettingsToggleRowView(context,R.string.feature_notification_save,
        initial.enabled,initial.interactionReady,environment,inputRoot,false,onEnabledChange)
    private var onInteractionChanged:()->Unit={}
    private var wheel:NativeNotificationDurationWheel?=null
    private val expansion=ReleasedSpringValue.float(if(initial.enabled)1f else 0f)
    private val durationScale=SettingsAnimatorDurationScale(context)
    private var expansionDriver:ViewSpringDriver<Float,smallthingz.ui.animation.core.AnimationVector1D>?=null
    private var closed=false
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(row);bind(initial,environment)
    }
    fun bind(next:SettingsNotificationSaveState,colors:ReverbViewEnvironment) {
        check(!closed)
        val wasEnabled=state.enabled
        state=next;environment=colors
        row.bind(next.enabled,next.interactionReady,colors)
        if(next.enabled) {
            val current=wheel?:NativeNotificationDurationWheel(context,lifecycle,interactionState,
                {readEditRevision()},{seconds->
                    onSecondsChange(seconds)
                    readEditRevision()?.let { NotificationDurationCommit(seconds,it) }
                },interactionChanged={onInteractionChanged()}).also {wheel=it;addView(it.view)}
            current.bind(next.seconds,next.maximumSeconds,next.interactionReady,next.editRevision,colors)
        } else wheel?.let {
            it.bind(next.seconds,next.maximumSeconds,false,next.editRevision,colors)
            if(wasEnabled)it.cancelInteraction()
        }
        val target=if(next.enabled)1f else 0f
        if(durationScale.target(target)==0f||!isAttachedToWindow) {
            expansion.snapTo(target)
            publishExpansion()
        } else {
            val driver=expansionDriver?:ViewSpringDriver(this,expansion,{publishExpansion()},
                durationScale::read).also {expansionDriver=it}
            driver.retarget(target)
        }
        requestLayout()
    }
    private fun publishExpansion() {
        wheel?.view?.let {
            it.alpha=expansion.value.coerceIn(0f,1f)
            it.importantForAccessibility=if(state.enabled)IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        if(!state.enabled&&!expansion.isRunning)retireWheel()
        requestLayout()
    }
    private fun retireWheel() {
        val old=wheel?:return
        wheel=null
        try {old.close()} finally {removeView(old.view)}
    }
    fun callbacks(onSeconds:(Int)->Unit,owner:()->Long?,interactionChanged:()->Unit={}) {
        onSecondsChange=onSeconds;readEditRevision=owner;onInteractionChanged=interactionChanged
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val wheelStyle=environment.appearance.durationWheel
        row.measure(exact(width),unspecified())
        wheel?.view?.measure(exact((width-2*dp(wheelStyle.settingsInsetDp)).coerceAtLeast(0)),
            exact(dp(wheelStyle.heightDp)))
        val extra=wheel?.let {((2*dp(wheelStyle.settingsVerticalInsetDp)+it.view.measuredHeight)*
            expansion.value.coerceIn(0f,1f)).roundToInt()}?:0
        setMeasuredDimension(resolveSize(width,w),resolveSize(row.measuredHeight+extra,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        row.layout(0,0,width,row.measuredHeight)
        wheel?.view?.let {view->
            val wheelStyle=environment.appearance.durationWheel
            val y=row.measuredHeight+dp(wheelStyle.settingsVerticalInsetDp)
            val inset=dp(wheelStyle.settingsInsetDp)
            view.layout(inset,y,width-inset,y+view.measuredHeight)
        }
    }
    override fun onDetachedFromWindow() {
        expansionDriver?.close();expansionDriver=null
        expansion.snapTo(if(state.enabled)1f else 0f)
        publishExpansion()
        super.onDetachedFromWindow()
    }
    override fun close() {
        if(closed)return
        closed=true;row.close()
        expansionDriver?.close();expansionDriver=null
        retireWheel()
    }
    private fun dp(v:Float)=(v*environment.textDensity.density).roundToInt()
}

