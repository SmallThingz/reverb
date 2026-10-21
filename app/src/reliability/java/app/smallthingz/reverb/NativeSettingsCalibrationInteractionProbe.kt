package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.mutableStateOf
import app.smallthingz.reverb.nativeui.SettingsSilenceActions
import app.smallthingz.reverb.nativeui.SettingsSilenceState
import app.smallthingz.reverb.nativeui.SettingsSilenceView
import org.json.JSONArray
import org.json.JSONObject

/** Deterministic callback seam only: no SettingsSilenceCalibration, sampler, service or microphone. */
internal fun verifyNativeSettingsCalibrationInteraction(instrumentation: Instrumentation, report: StringBuilder) {
    SettingsInteractionSuite(instrumentation, report, "native_settings_calibration").run { suite, host, reference ->
        val input = SilenceCalibrationInput(48_000, PcmSampleFormat.PCM_16, ChannelMode.MONO,
            AudioSourceMode.MIC, InputRouteMode.AUTO)
        val peaks = List(SILENCE_GRAPH_BINS) { index -> -85f + (index % 17) * 2f }
        val state = mutableStateOf(SettingsSilenceState(SilenceGateConfig(enabled = true)))
        val callbacks = mutableListOf<String>()
        var update: () -> Unit = {}
        var savedHistogram: String? = null
        fun change(next: SettingsSilenceState) { state.value = next; update() }
        fun accepted() {
            check(state.value.calibrating)
            savedHistogram = encodeSilenceCalibrationHistogram(SilenceCalibrationHistogram(input, 48_000, peaks))
            callbacks += "accepted"
            change(state.value.copy(calibrating = false, holdingCalibration = false,
                calibrationProgress = null, sampledPeaks = checkNotNull(decodeSilenceCalibrationHistogram(savedHistogram)).peaksFor(input)))
        }
        val actions = object : SettingsSilenceActions {
            override fun configChanged(config: SilenceGateConfig) {
                callbacks += "config:${config.enabled}"; change(state.value.copy(config = config))
            }
            override fun calibrate() {
                check(!state.value.calibrating)
                callbacks += "timed-semantic"
                change(state.value.copy(calibrating = true, holdingCalibration = false, calibrationProgress = 0f))
            }
            override fun beginHeld(): Boolean {
                check(!state.value.calibrating)
                callbacks += "begin-down"
                change(state.value.copy(calibrating = true, holdingCalibration = true))
                return true
            }
            override fun completeTap() {
                check(state.value.calibrating && state.value.holdingCalibration)
                callbacks += "tap-release"
                change(state.value.copy(holdingCalibration = false, calibrationProgress = 0f))
            }
            override fun finishHeld() {
                check(state.value.calibrating && state.value.holdingCalibration)
                callbacks += "held-release"; accepted()
            }
            override fun cancel() {
                callbacks += "cancel"
                change(state.value.copy(calibrating = false, holdingCalibration = false, calibrationProgress = null))
            }
        }
        fun mount(): SettingsInteractionRender = suite.mount(host, reference, source = {
            val current = state.value
            SkipSilenceSettings(current.config, actions::configChanged, actions::calibrate, actions::beginHeld,
                actions::completeTap, actions::finishHeld, actions::cancel, enabled = current.enabled,
                maximumHz = current.maximumHz, sampledPeaks = current.sampledPeaks,
                calibrationAllowed = current.calibrationAllowed, calibrating = current.calibrating,
                holdingCalibration = current.holdingCalibration, calibrationProgress = current.calibrationProgress,
                calibrationError = current.calibrationError, calibrationUnavailableReason = current.calibrationUnavailableReason)
        }, native = { parent ->
            val view = SettingsSilenceView(host, state.value, parent.environment, { parent.inputRoot },
                parent.scope, actions)
            update = { view.bind(state.value, parent.environment, actions) }
            SettingsInteractionNativeContent(view, { try { view.close() } finally { update = {} } }) { child ->
                view.calibrationPointerNode?.takeIf { it.view === child }?.let(::listOf).orEmpty()
            }
        })
        var render = mount()
        fun checkpoint(name: String) = suite.capture(name, { callbacks.toList() }, { JSONObject()
            .put("running", state.value.calibrating).put("holding", state.value.holdingCalibration)
            .put("config", encodeSilenceGate(state.value.config))
            .put("histogram", state.value.sampledPeaks?.let(::JSONArray) ?: JSONObject.NULL) })
        fun down(): Pair<Long, Rect> {
            var answer: Pair<Long, Rect>? = null
            suite.main {
                val bounds = render.target(host.getString(R.string.silence_calibrate))
                val clock = SystemClock.uptimeMillis()
                val count = callbacks.size
                render.motion(MotionEvent.ACTION_DOWN, clock, bounds.exactCenterX(), bounds.exactCenterY())
                check(callbacks.drop(count) == listOf("begin-down")) { "Calibration did not begin on DOWN" }
                check(state.value.calibrating && state.value.holdingCalibration)
                answer = clock to bounds
            }
            return checkNotNull(answer)
        }
        checkpoint("initial")
        // Ordinary tap: release in the same own-root dispatch turn so it cannot become a hold
        // merely because instrumentation scheduling is slow. DOWN still must begin immediately.
        suite.main {
            val b = render.target(host.getString(R.string.silence_calibrate))
            val clock = SystemClock.uptimeMillis(); val count = callbacks.size
            render.motion(MotionEvent.ACTION_DOWN, clock, b.exactCenterX(), b.exactCenterY())
            check(callbacks.drop(count) == listOf("begin-down"))
            render.motion(MotionEvent.ACTION_UP, clock, b.exactCenterX(), b.exactCenterY())
            check(callbacks.drop(count) == listOf("begin-down", "tap-release"))
        }
        suite.main { change(state.value.copy(calibrationProgress = .5f)) }
        checkpoint("timed-running")
        suite.main { accepted() }
        checkpoint("timed-histogram")

        val held = down()
        val releaseAt = held.first + ViewConfiguration.getLongPressTimeout().toLong() + 20L
        suite.await("source long-press threshold") { SystemClock.uptimeMillis() >= releaseAt }
        suite.main {
            render.motion(MotionEvent.ACTION_UP, held.first, held.second.exactCenterX(), held.second.exactCenterY())
        }
        checkpoint("held-histogram")
        check(callbacks.takeLast(2) == listOf("held-release", "accepted"))

        val cancelled = down()
        suite.main {
            val b = cancelled.second
            val move = ViewConfiguration.get(host).scaledTouchSlop * 2f
            render.motion(MotionEvent.ACTION_MOVE, cancelled.first, b.exactCenterX() + move, b.exactCenterY())
            render.motion(MotionEvent.ACTION_UP, cancelled.first, b.exactCenterX() + move, b.exactCenterY())
        }
        checkpoint("slop-cancelled")
        check(callbacks.takeLast(2) == listOf("begin-down", "cancel"))

        suite.main {
            val b = render.target(host.getString(R.string.silence_calibrate))
            val clock = SystemClock.uptimeMillis()
            render.motion(MotionEvent.ACTION_DOWN, clock, b.exactCenterX(), b.exactCenterY())
            render.motion(MotionEvent.ACTION_UP, clock, b.exactCenterX(), b.exactCenterY())
        }
        checkpoint("tap-before-cancel")
        suite.main { render.click(host.getString(R.string.cancel)) }
        checkpoint("tap-cancelled")
        check(!state.value.calibrating)
        suite.main { render.click(host.getString(R.string.silence_calibrate)) }
        checkpoint("semantic-timed")
        suite.main { render.click(host.getString(R.string.cancel)) }
        checkpoint("explicit-cancel")
        check(!state.value.calibrating)

        // Detach while held: the actual source detector's disposal must cancel once.
        down()
        val beforeDetach = callbacks.size
        suite.closeRender()
        check(callbacks.drop(beforeDetach) == listOf("cancel"))
        check(!state.value.calibrating && !state.value.holdingCalibration)

        // Dispose/recreate the actual leaves with the exact encoded presentation histogram.
        // This is deterministic hydration, not evidence that a microphone session was persisted.
        suite.closeRender()
        suite.main {
            val decoded = checkNotNull(decodeSilenceCalibrationHistogram(savedHistogram))
            check(decoded.peaksFor(input) == peaks)
            state.value = SettingsSilenceState(state.value.config, sampledPeaks = decoded.peaksFor(input))
        }
        render = mount()
        checkpoint("reopened-histogram")
        check(state.value.sampledPeaks == peaks)
        suite.main {
            val decoded = checkNotNull(decodeSilenceCalibrationHistogram(savedHistogram))
            val mismatched = decoded.peaksFor(input.copy(sampleRate = 44_100))
            check(mismatched == null)
            change(state.value.copy(sampledPeaks = mismatched))
        }
        checkpoint("different-input-hides-histogram")
    }
}
