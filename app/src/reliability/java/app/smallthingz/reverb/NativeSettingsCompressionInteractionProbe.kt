package app.smallthingz.reverb

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateOf
import app.smallthingz.reverb.nativeui.SettingsOutputCompressionState
import app.smallthingz.reverb.nativeui.SettingsOutputCompressionView
import org.json.JSONObject

/** Actual released/native control callbacks; no encoder or Settings persistence owner is created. */
internal fun verifyNativeSettingsCompressionInteraction(instrumentation: Instrumentation, report: StringBuilder) {
    SettingsInteractionSuite(instrumentation, report, "native_settings_compression").run { suite, host, reference ->
        val state = mutableStateOf(SettingsOutputCompressionState(false, 5, true))
        val callbacks = mutableListOf<String>()
        var native: SettingsOutputCompressionView? = null
        var update: () -> Unit = {}
        fun enabled(value: Boolean) {
            callbacks += "enabled:$value"
            state.value = state.value.copy(enabled = value)
            update()
        }
        fun level(value: Int) {
            callbacks += "level:$value"
            state.value = state.value.copy(level = value)
            update()
        }
        val render = suite.mount(host, reference, source = {
            val current = state.value
            OutputCompressionSettings(current.enabled, current.level, ::enabled, ::level,
                interactionEnabled = current.interactionEnabled)
        }, native = { parent ->
            val view = SettingsOutputCompressionView(host, state.value, parent.environment,
                { parent.inputRoot }, parent.scope, { false }, ::enabled, ::level)
            native = view
            update = { view.bind(state.value, parent.environment) }
            SettingsInteractionNativeContent(view, { update = {}; view.close(); native = null }) { child ->
                view.currentSlider?.takeIf { it.pointerView === child }?.pointerNodes.orEmpty()
            }
        })
        fun checkpoint(name: String) = suite.capture(name, { callbacks.toList() }, { JSONObject()
            .put("enabled", state.value.enabled).put("level", state.value.level) })
        checkpoint("collapsed")
        suite.main { render.click(host.getString(R.string.output_compression)) }
        suite.observeDraws("compression-expand")
        checkpoint("expanded")
        suite.main {
            check(render.setProgress(host.getString(R.string.compression_level), 7f))
            check(render.setProgress(host.getString(R.string.compression_level), 2f))
            check(render.setProgress(host.getString(R.string.compression_level), 6f))
        }
        checkpoint("rapid-progress")
        val beforeGesture = callbacks.size
        suite.main {
            val bounds = render.target(host.getString(R.string.compression_level))
            val down = SystemClock.uptimeMillis()
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            check(render.motion(MotionEvent.ACTION_DOWN, down, x, y)) { "Slider did not accept DOWN" }
            render.motion(MotionEvent.ACTION_MOVE, down, x + 32 * render.density.density, y)
            render.motion(MotionEvent.ACTION_CANCEL, down, x + 32 * render.density.density, y)
        }
        checkpoint("drag-cancel")
        check(callbacks.drop(beforeGesture).any { it.startsWith("level:") }) {
            "Pointer drag produced no level callback; not credited as gesture coverage"
        }
        suite.main { render.click(host.getString(R.string.output_compression)) }
        awaitNativeParityDraw(instrumentation, render.board)
        suite.main { render.click(host.getString(R.string.output_compression)) }
        suite.observeDraws("compression-rapid-reopen")
        checkpoint("rapid-reopen")
        suite.main { render.click(host.getString(R.string.output_compression)) }
        checkpoint("collapsed-final")
        check(!state.value.enabled)
        // Actual renderer disposal happens in Suite finally; no hidden model teardown here.
    }
}
