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
import app.smallthingz.reverb.sampledSilenceCurve
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
    private val calibrate:SettingsCalibrationActionView=SettingsCalibrationActionView(context,environment,inputRoot,
        stableLabels=listOf(context.getString(R.string.silence_calibrate),context.getString(R.string.silence_release_to_finish),
            context.getString(R.string.cancel)),
        progressSurface=true) {
        if(!gesture.pointerDown) {
            if(canCancel())actions()?.cancel()
            else if(canBegin())actions()?.calibrate()
        }
    }
    private val fit=SettingsCalibrationFitDial(context,environment) {clearance->
        if(canEdit()) {
            val points=state.sampledPeaks?.let {sampledSilenceCurve(it,state.maximumHz,clearance)} ?: state.config.points
            actions()?.configChanged(state.config.copy(points=points,clearanceDb=clearance))
        }
    }
    private val heading=environment.paragraph(context,context.getString(R.string.silence_calibration_heading),
        ReverbTextRole.TITLE_SMALL,environment.palette.ink)
    private val hint=environment.paragraph(context,context.getString(R.string.silence_calibration_hint),
        ReverbTextRole.BODY_SMALL,environment.palette.muted)
    private val buttonRow=CalibrationButtonRow(context,calibrate,fit)
    private val error=environment.paragraph(context,"",ReverbTextRole.BODY_SMALL,environment.palette.error)
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
        ViewCompat.setAccessibilityHeading(heading,true)
        ViewCompat.setAccessibilityLiveRegion(error,ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        addView(heading);addView(hint);addView(curve);addView(buttonRow);addView(error)
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
    private fun canCancel()=state.calibrating&&!state.holdingCalibration
    private fun canEdit()=state.enabled&&state.config.enabled&&!state.calibrating
    fun bind(next:SettingsSilenceState,colors:ReverbViewEnvironment) {
        state=next;environment=colors;refresh()
    }
    fun bindProgress(next:SettingsSilenceState) {
        state=next
        calibrate.bindProgress(next.calibrationProgress,next.calibrating)
    }
    private fun bindCurve() {
        val presentation=state.config
        curve.contentDescription=context.getString(R.string.silence_graph_description)
        curve.bind(presentation,selected().coerceIn(presentation.points.indices),state.sampledPeaks,state.maximumHz,
            environment.palette.primary,
            environment.palette.ink,environment.palette.muted,canEdit())
    }
    private fun refresh() {
        if(closed)return
        bindCurve()
        fit.bind(state.config.clearanceDb,canEdit(),environment)
        environment.bindParagraphStyle(heading,ReverbTextRole.TITLE_SMALL,environment.palette.ink)
        environment.bindParagraphStyle(hint,ReverbTextRole.BODY_SMALL,environment.palette.muted)
        val pointerDown=gesture.pointerDown
        val buttonEnabled=canCancel()||state.enabled&&state.config.enabled&&
            (state.calibrationAllowed||pointerDown)&&(!state.calibrating||pointerDown)
        calibrate.bind(context.getString(if(state.holdingCalibration)R.string.silence_release_to_finish
            else if(canCancel())R.string.cancel else R.string.silence_calibrate),buttonEnabled,environment)
        environment.bindParagraphStyle(error,ReverbTextRole.BODY_SMALL,environment.palette.error)
        error.setText(state.calibrationError.orEmpty())
        calibrate.bindProgress(state.calibrationProgress,state.calibrating)
        error.visibility=if(!state.calibrationError.isNullOrBlank())VISIBLE else GONE
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(16)).coerceAtLeast(0)
        curve.measure(exact(inner),unspecified())
        buttonRow.measure(exact(inner),unspecified())
        heading.measure(atMost(inner),unspecified())
        hint.measure(atMost(inner),unspecified())
        val errorHeight=if(error.visibility==VISIBLE) {
            error.measure(atMost(inner),unspecified())
            dp(8)+error.measuredHeight
        } else 0
        val height=dp(12)+3*dp(8)+heading.measuredHeight+hint.measuredHeight+curve.measuredHeight+buttonRow.measuredHeight+errorHeight
        setMeasuredDimension(resolveSize(width,w),resolveSize(height,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        var y=dp(12)
        fun place(child:View) {
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-dp(16)-child.measuredWidth else dp(16)
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
        }
        place(heading);y+=heading.measuredHeight+dp(8)
        place(hint);y+=hint.measuredHeight+dp(8)
        place(curve);y+=curve.measuredHeight+dp(8)
        place(buttonRow)
        if(error.visibility==VISIBLE) {y+=buttonRow.measuredHeight+dp(8);place(error)}
    }
    override fun onDetachedFromWindow() {gesture.cancel();super.onDetachedFromWindow()}
    override fun close() {
        if(closed)return
        closed=true
        try {gesture.cancel()}finally {
            calibrate.close();fit.close()
            curve.onCommit=null;curve.onSelect=null
            val presentation=state.config
            curve.bind(presentation,selected().coerceIn(presentation.points.indices),null,state.maximumHz,
                environment.palette.primary,environment.palette.ink,environment.palette.muted,false)
        }
    }
    private fun dp(value:Int)=(value*environment.textDensity.density).roundToInt()
}

private class CalibrationButtonRow(
    context:Context,
    private val calibrate:View,
    private val fit:View,
):ViewGroup(context),app.smallthingz.reverb.NativeAndroidInteropHost {
    override val nativeInteropRequests=app.smallthingz.reverb.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept:Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;addView(calibrate);addView(fit)}
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        fit.measure(atMost(width/3),unspecified())
        val trailing=fit.measuredWidth
        calibrate.measure(exact((width-trailing).coerceAtLeast(0)),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(max(calibrate.measuredHeight,fit.measuredHeight),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        val first=if(rtl)width-calibrate.measuredWidth else 0
        val top=(height-calibrate.measuredHeight)/2
        calibrate.layout(first,top,first+calibrate.measuredWidth,top+calibrate.measuredHeight)
        val fx=if(rtl)0 else width-fit.measuredWidth
        fit.layout(fx,0,fx+fit.measuredWidth,fit.measuredHeight)
    }
}
