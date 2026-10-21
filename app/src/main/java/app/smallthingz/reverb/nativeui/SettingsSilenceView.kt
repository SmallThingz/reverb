package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.R
import app.smallthingz.reverb.SilenceCurveView
import app.smallthingz.reverb.SilenceGateConfig
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.roundToInt

internal data class SettingsSilenceState(
    val config:SilenceGateConfig,
    val enabled:Boolean=true,
    val maximumHz:Int=20_000,
    val sampledPeaks:List<Float>?=null,
    val calibrationAllowed:Boolean=true,
    val calibrating:Boolean=false,
    val holdingCalibration:Boolean=false,
    val calibrationProgress:Float?=null,
    val calibrationError:String?=null,
    val calibrationUnavailableReason:String?=null,
)

private fun SettingsSilenceState.samePresentationExceptProgress(other:SettingsSilenceState)=
    config==other.config&&enabled==other.enabled&&maximumHz==other.maximumHz&&
        sampledPeaks==other.sampledPeaks&&calibrationAllowed==other.calibrationAllowed&&
        calibrating==other.calibrating&&holdingCalibration==other.holdingCalibration&&
        calibrationError==other.calibrationError&&calibrationUnavailableReason==other.calibrationUnavailableReason

/** Typed calls to the existing SettingsSilenceCalibration and Settings draft; no new capture owner. */
internal interface SettingsSilenceActions {
    fun configChanged(config:SilenceGateConfig)
    fun calibrate()
    fun beginHeld():Boolean
    fun completeTap()
    fun finishHeld()
    fun cancel()
}

/** Released SkipSilenceSettings layout; selected curve point survives expansion content disposal. */
internal class SettingsSilenceView(
    context:Context,
    initial:SettingsSilenceState,
    private var environment:ReverbViewEnvironment,
    private val inputRoot:()->View?,
    parentScope:CoroutineScope,
    actions:SettingsSilenceActions,
):ViewGroup(context),AutoCloseable {
    private var state=initial
    private var actions:SettingsSilenceActions?=actions
    private var selected=1.coerceAtMost(initial.config.points.lastIndex)
    private val row=SettingsToggleRowView(context,context.getString(R.string.silence_title),
        initial.config.enabled,initial.enabled,environment,inputRoot,false) {checked->
        if(state.calibrating)this.actions?.cancel()
        this.actions?.configChanged(state.config.copy(enabled=checked))
    }
    private val expansion=SettingsExpansionView(context,initial.config.enabled,parentScope,alignBottom=true) {
        SilenceBody(context,state,environment,inputRoot,{selected},{selected=it},{this.actions})
    }
    /** Custom pointer node is outside TextButton's minimum-interactive-size wrapper. */
    val calibrationPointerNode:NativeViewPhasePointerNode? get()=(expansion.content as? SilenceBody)?.pointerNode
    val curveView:SilenceCurveView? get()=(expansion.content as? SilenceBody)?.curve
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(row);addView(expansion)}
    fun bind(next:SettingsSilenceState,colors:ReverbViewEnvironment,callbacks:SettingsSilenceActions) {
        val progressOnly=colors===environment&&state.samePresentationExceptProgress(next)&&
            (state.calibrationProgress==null)==(next.calibrationProgress==null)
        state=next;environment=colors;actions=callbacks
        if(progressOnly) {
            (expansion.content as? SilenceBody)?.bindProgress(next)
            return
        }
        row.bind(next.config.enabled,next.enabled,colors)
        (expansion.content as? SilenceBody)?.bind(next,colors)
        expansion.visible(next.config.enabled)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        row.measure(exact(width),unspecified())
        expansion.measure(atMost(width),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(row.measuredHeight+expansion.measuredHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        row.layout(0,0,width,row.measuredHeight)
        val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-expansion.measuredWidth else 0
        expansion.layout(x,row.measuredHeight,x+expansion.measuredWidth,row.measuredHeight+expansion.measuredHeight)
    }
    override fun close() {
        // The source pointer detector must cancel its owned held sample before callbacks are retired.
        try {row.close();expansion.close()}finally {actions=null}
    }
}

private class SilenceBody(
    context:Context,
    initial:SettingsSilenceState,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    private val selected:()->Int,
    private val select:(Int)->Unit,
    private val actions:()->SettingsSilenceActions?,
):ViewGroup(context),SettingsExpansionContent, app.smallthingz.reverb.NativeAndroidInteropHost {
    override val nativeInteropRequests = app.smallthingz.reverb.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override val view:View get()=this
    private var state=initial
    val curve=SilenceCurveView(context)
    private val configuration=ViewConfiguration.get(context)
    private var closed=false
    private val calibrate:SettingsCalibrationActionView=SettingsCalibrationActionView(context,environment,inputRoot) {
        if(!gesture.pointerDown&&canBegin())actions()?.calibrate()
    }
    private val cancel=SettingsCalibrationActionView(context,environment,inputRoot) {actions()?.cancel()}
    private val buttonRow=CalibrationButtonRow(context,calibrate,cancel)
    private val sampling=environment.paragraph(context,context.getString(R.string.silence_sampling),
        ReverbTextRole.BODY_SMALL,environment.palette.muted)
    private val error=environment.paragraph(context,"",ReverbTextRole.BODY_SMALL,environment.palette.error)
    private val unavailable=environment.paragraph(context,"",ReverbTextRole.BODY_SMALL,environment.palette.muted)
    private val progress=SettingsCalibrationProgressView(context,environment)
    private val visibleItems=ArrayList<View>(6)
    private val gesture:SettingsCalibrationGesture=SettingsCalibrationGesture(::canBegin,
        {actions()?.beginHeld()==true},{actions()?.completeTap()},{actions()?.finishHeld()},
        {actions()?.cancel()},{refresh()},
        {calibrate.width},{calibrate.height},
        {configuration.scaledTouchSlop.toFloat()},{ViewConfiguration.getLongPressTimeout().toLong()})
    val pointerNode=object:NativeViewPhasePointerNode {
        override val view:View get()=calibrate
        override fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass)=gesture.dispatch(event,pass)
        override fun cancel()=gesture.cancel()
    }

    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setAccessibilityLiveRegion(sampling,View.ACCESSIBILITY_LIVE_REGION_POLITE)
        ViewCompat.setAccessibilityLiveRegion(error,View.ACCESSIBILITY_LIVE_REGION_POLITE)
        addView(curve);addView(buttonRow);addView(sampling);addView(progress);addView(error);addView(unavailable)
        curve.onCommit={points,index->
            if(canEdit()) {
                select(index.coerceIn(points.indices))
                actions()?.configChanged(state.config.copy(points=points))
            }
        }
        curve.onSelect={index->if(canEdit()){select(index.coerceIn(state.config.points.indices));bindCurve()}}
        refresh()
    }
    private fun canBegin()=state.enabled&&state.config.enabled&&state.calibrationAllowed&&!state.calibrating
    private fun canEdit()=state.enabled&&state.config.enabled&&!state.calibrating
    fun bind(next:SettingsSilenceState,colors:ReverbViewEnvironment) {
        state=next;environment=colors;refresh()
    }
    fun bindProgress(next:SettingsSilenceState) {
        state=next
        next.calibrationProgress?.let {progress.bind(it,environment)}
    }
    private fun bindCurve() {
        curve.bind(state.config,selected().coerceIn(state.config.points.indices),state.sampledPeaks,state.maximumHz,
            environment.palette.primary,environment.palette.ink,environment.palette.muted,canEdit())
    }
    private fun refresh() {
        if(closed)return
        bindCurve()
        val pointerDown=gesture.pointerDown
        val buttonEnabled=state.enabled&&state.config.enabled&&
            (state.calibrationAllowed||pointerDown)&&(!state.calibrating||pointerDown)
        calibrate.bind(context.getString(if(state.holdingCalibration)R.string.silence_release_to_finish
            else R.string.silence_calibrate),buttonEnabled,environment)
        cancel.bind(context.getString(R.string.cancel),true,environment)
        buttonRow.showCancel=state.calibrating&&!state.holdingCalibration&&!pointerDown
        environment.bindParagraphStyle(sampling,ReverbTextRole.BODY_SMALL,environment.palette.muted)
        environment.bindParagraphStyle(error,ReverbTextRole.BODY_SMALL,environment.palette.error)
        environment.bindParagraphStyle(unavailable,ReverbTextRole.BODY_SMALL,environment.palette.muted)
        error.setText(state.calibrationError.orEmpty())
        unavailable.setText(state.calibrationUnavailableReason.orEmpty())
        val showSampling=state.calibrating&&!state.holdingCalibration
        sampling.visibility=if(showSampling)VISIBLE else GONE
        progress.visibility=if(showSampling&&state.calibrationProgress!=null)VISIBLE else GONE
        state.calibrationProgress?.let {progress.bind(it,environment)}
        error.visibility=if(state.calibrationError!=null)VISIBLE else GONE
        unavailable.visibility=if(!state.calibrationAllowed&&!state.calibrating&&
            state.calibrationUnavailableReason!=null)VISIBLE else GONE
        visibleItems.clear()
        visibleItems.add(curve);visibleItems.add(buttonRow)
        if(sampling.visibility==VISIBLE)visibleItems.add(sampling)
        if(progress.visibility==VISIBLE)visibleItems.add(progress)
        if(error.visibility==VISIBLE)visibleItems.add(error)
        if(unavailable.visibility==VISIBLE)visibleItems.add(unavailable)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(16)).coerceAtLeast(0)
        var height=2*dp(12)
        visibleItems.forEachIndexed {index,child->
            // Curve, action row and progress explicitly fill; ordinary Text wraps its constraint.
            child.measure(if(child===curve||child===buttonRow||child===progress)exact(inner)else atMost(inner),unspecified())
            if(index>0)height+=dp(8)
            height+=child.measuredHeight
        }
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        var y=dp(12)
        visibleItems.forEachIndexed {index,child->
            if(index>0)y+=dp(8)
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-dp(16)-child.measuredWidth else dp(16)
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
            y+=child.measuredHeight
        }
    }
    override fun onDetachedFromWindow() {gesture.cancel();super.onDetachedFromWindow()}
    override fun close() {
        if(closed)return
        closed=true
        try {gesture.cancel()}finally {
            calibrate.close();cancel.close()
            curve.onCommit=null;curve.onSelect=null
            curve.bind(state.config,selected().coerceIn(state.config.points.indices),null,state.maximumHz,
                environment.palette.primary,environment.palette.ink,environment.palette.muted,false)
        }
    }
    private fun dp(value:Int)=(value*environment.textDensity.density).roundToInt()
}

private class CalibrationButtonRow(
    context:Context,
    private val calibrate:View,
    private val cancel:View,
):ViewGroup(context) {
    var showCancel=false
        set(value){field=value;cancel.visibility=if(value)VISIBLE else GONE;requestLayout()}
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(calibrate);addView(cancel)}
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        if(showCancel)cancel.measure(atMost(width),unspecified())
        val trailing=if(showCancel)cancel.measuredWidth else 0
        calibrate.measure(exact((width-trailing).coerceAtLeast(0)),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(max(calibrate.measuredHeight,
            if(showCancel)cancel.measuredHeight else 0),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        val first=if(rtl)width-calibrate.measuredWidth else 0
        calibrate.layout(first,0,first+calibrate.measuredWidth,calibrate.measuredHeight)
        if(showCancel) {
            val x=if(rtl)0 else calibrate.measuredWidth
            cancel.layout(x,0,x+cancel.measuredWidth,cancel.measuredHeight)
        }
    }
}
