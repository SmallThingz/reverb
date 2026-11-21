package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewConfiguration
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import app.smallthingz.reverb.R
import app.smallthingz.reverb.SilenceCurveView
import app.smallthingz.reverb.SilenceGateConfig
import app.smallthingz.reverb.sampledSilenceCurve
import app.smallthingz.reverb.SILENCE_DEFAULT_CLEARANCE_DB
import app.smallthingz.reverb.SILENCE_MAX_CLEARANCE_DB
import app.smallthingz.reverb.SILENCE_MIN_CLEARANCE_DB
import smallthingz.ui.components.NativeArcDialRange
import smallthingz.ui.components.NativeCalibrationBodyLayout
import smallthingz.ui.components.NativeExpandableSettingsSectionLayout
import kotlinx.coroutines.CoroutineScope

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
):NativeExpandableSettingsSectionLayout(context),AutoCloseable {
    private var state=initial
    private var actions:SettingsSilenceActions?=actions
    private var selected=1.coerceAtMost(initial.config.points.lastIndex)
    private val row=SettingsToggleRowView(context,R.string.silence_title,
        initial.config.enabled,initial.enabled,environment,inputRoot,false,changed={checked->
            if(state.calibrating)this.actions?.cancel()
            this.actions?.configChanged(state.config.copy(enabled=checked))
        })
    private val expansion=SettingsExpansionView(context,initial.config.enabled,parentScope,alignBottom=true) {
        SilenceBody(context,state,environment,inputRoot,{selected},{selected=it},{this.actions})
    }
    /** Custom pointer node is outside TextButton's minimum-interactive-size wrapper. */
    val calibrationPointerNode:NativeViewPhasePointerNode? get()=(expansion.content as? SilenceBody)?.pointerNode
    init {installExpansion(row, expansion)}
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
): NativeCalibrationBodyLayout(context, environment,
    context.getString(R.string.silence_calibration_heading), context.getString(R.string.silence_calibration_hint)),
    SettingsExpansionContent {
    override val view:View get()=this
    private var state=initial
    val curve=SilenceCurveView(context, environment.appearance.calibration)
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
    private val fit=SettingsCalibrationFitDial(context, environment,
        NativeArcDialRange(SILENCE_MIN_CLEARANCE_DB, SILENCE_MAX_CLEARANCE_DB, SILENCE_DEFAULT_CLEARANCE_DB),
        context.getString(R.string.silence_fit_description), R.id.silence_fit_reset,
        { context.getString(R.string.silence_fit_reset) },
        { context.getString(R.string.silence_fit_short, it) },
        { context.getString(R.string.silence_fit_value, it) }) { clearance ->
        if(canEdit()) {
            val points=state.sampledPeaks?.let {sampledSilenceCurve(it,state.maximumHz,clearance)} ?: state.config.points
            actions()?.configChanged(state.config.copy(points=points,clearanceDb=clearance))
        }
    }
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
        installControls(curve, calibrate, fit)
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
        val pointerDown=gesture.pointerDown
        val buttonEnabled=canCancel()||state.enabled&&state.config.enabled&&
            (state.calibrationAllowed||pointerDown)&&(!state.calibrating||pointerDown)
        calibrate.bind(context.getString(if(state.holdingCalibration)R.string.silence_release_to_finish
            else if(canCancel())R.string.cancel else R.string.silence_calibrate),buttonEnabled,environment)
        calibrate.bindProgress(state.calibrationProgress,state.calibrating)
        bindContent(environment, state.calibrationError)
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
}
