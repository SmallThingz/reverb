package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import app.smallthingz.reverb.R
import app.smallthingz.reverb.SILENCE_DEFAULT_CLEARANCE_DB
import app.smallthingz.reverb.SILENCE_MAX_CLEARANCE_DB
import app.smallthingz.reverb.SILENCE_MIN_CLEARANCE_DB
import app.smallthingz.reverb.SilenceCurveView
import app.smallthingz.reverb.SilenceGateConfig
import app.smallthingz.reverb.sampledSilenceCurve
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.components.NativeArcDialRange
import smallthingz.ui.components.NativeCalibrationRecipe
import smallthingz.ui.reverb.settings.ReverbCalibrationGraph
import smallthingz.ui.reverb.settings.ReverbCalibrationGraphPalette
import smallthingz.ui.reverb.settings.ReverbSilenceCalibrationActions
import smallthingz.ui.reverb.settings.ReverbSilenceCalibrationLabels
import smallthingz.ui.reverb.settings.ReverbSilenceCalibrationState
import smallthingz.ui.reverb.settings.ReverbSilenceSettingsView

internal data class SettingsSilenceState(
    val config: SilenceGateConfig,
    override val enabled: Boolean = true,
    val maximumHz: Int = 20_000,
    val sampledPeaks: List<Float>? = null,
    override val calibrationAllowed: Boolean = true,
    override val calibrating: Boolean = false,
    override val holdingCalibration: Boolean = false,
    override val calibrationProgress: Float? = null,
    override val calibrationError: String? = null,
    override val calibrationUnavailableReason: String? = null,
) : ReverbSilenceCalibrationState {
    override val checked get() = config.enabled
    override val clearance get() = config.clearanceDb
}

/** Native settings and onboarding supply the same typed capture/draft actions. */
internal interface SettingsSilenceActions {
    fun configChanged(config: SilenceGateConfig)
    fun calibrate()
    fun beginHeld(): Boolean
    fun completeTap()
    fun finishHeld()
    fun cancel()
}

internal class SettingsSilenceView private constructor(
    context: Context,
    initial: SettingsSilenceState,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    parentScope: CoroutineScope,
    private val binding: SilenceBinding,
) : ReverbSilenceSettingsView(context, initial, environment, inputRoot, parentScope, calibrationLabels(context),
    NativeArcDialRange(SILENCE_MIN_CLEARANCE_DB, SILENCE_MAX_CLEARANCE_DB, SILENCE_DEFAULT_CLEARANCE_DB),
    { recipe -> binding.createGraph(context, recipe) }, binding) {
    constructor(
        context: Context,
        initial: SettingsSilenceState,
        environment: ReverbViewEnvironment,
        inputRoot: () -> View?,
        parentScope: CoroutineScope,
        actions: SettingsSilenceActions,
    ) : this(context, initial, environment, inputRoot, parentScope, SilenceBinding(initial, actions))

    fun bind(next: SettingsSilenceState, colors: ReverbViewEnvironment, callbacks: SettingsSilenceActions) {
        val previous = binding.state
        val graphChanged = previous.config.points != next.config.points || previous.maximumHz != next.maximumHz ||
            previous.sampledPeaks != next.sampledPeaks
        binding.state = next
        binding.actions = callbacks
        super.bind(next, colors, graphChanged)
    }

    override fun close() {
        try { super.close() } finally { binding.actions = null }
    }
}

private class SilenceBinding(
    var state: SettingsSilenceState,
    var actions: SettingsSilenceActions?,
) : ReverbSilenceCalibrationActions {
    private var selected = 1.coerceAtMost(state.config.points.lastIndex)

    override fun checkedChanged(checked: Boolean) { actions?.configChanged(state.config.copy(enabled = checked)) }

    override fun clearanceChanged(clearance: Float) {
        val points = state.sampledPeaks?.let { sampledSilenceCurve(it, state.maximumHz, clearance) } ?: state.config.points
        actions?.configChanged(state.config.copy(points = points, clearanceDb = clearance))
    }

    override fun calibrate() { actions?.calibrate() }
    override fun beginHeld() = actions?.beginHeld() == true
    override fun completeTap() { actions?.completeTap() }
    override fun finishHeld() { actions?.finishHeld() }
    override fun cancel() { actions?.cancel() }

    fun createGraph(context: Context, recipe: NativeCalibrationRecipe): ReverbCalibrationGraph =
        object : ReverbCalibrationGraph {
            private val curve = SilenceCurveView(context, recipe)
            override val view: View get() = curve
            private var palette: ReverbCalibrationGraphPalette? = null
            private var editable = false

            init {
                curve.onCommit = { points, index ->
                    if (editable) {
                        selected = index.coerceIn(points.indices)
                        actions?.configChanged(state.config.copy(points = points))
                    }
                }
                curve.onSelect = { index ->
                    if (editable) {
                        selected = index.coerceIn(state.config.points.indices)
                        palette?.let { bind(it, editable = true) }
                    }
                }
            }

            override fun bind(palette: ReverbCalibrationGraphPalette, editable: Boolean) {
                this.palette = palette
                this.editable = editable
                val current = state
                val config = current.config
                curve.bind(config, selected.coerceIn(config.points.indices), current.sampledPeaks, current.maximumHz,
                    palette.accent, palette.ink, palette.muted, editable)
            }

            override fun close() {
                curve.onCommit = null
                curve.onSelect = null
                palette?.let {
                    val config = state.config
                    curve.bind(config, selected.coerceIn(config.points.indices), null, state.maximumHz,
                        it.accent, it.ink, it.muted, false)
                }
                palette = null
            }
        }
}

private fun calibrationLabels(context: Context) = ReverbSilenceCalibrationLabels(
    context.getString(R.string.silence_title), context.getString(R.string.silence_calibrate),
    context.getString(R.string.silence_calibration_hint), context.getString(R.string.silence_release_to_finish),
    context.getString(R.string.cancel), context.getString(R.string.silence_sampling),
    context.getString(R.string.silence_fit_description), R.id.silence_fit_reset,
    { context.getString(R.string.silence_fit_reset) }, { context.getString(R.string.silence_fit_short, it) },
    { context.getString(R.string.silence_fit_value, it) },
)
