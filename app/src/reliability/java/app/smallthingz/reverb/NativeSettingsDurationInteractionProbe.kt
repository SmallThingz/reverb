package app.smallthingz.reverb

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import app.smallthingz.reverb.nativeui.QuickTileInlineView
import app.smallthingz.reverb.nativeui.SettingsNotificationSaveState
import app.smallthingz.reverb.nativeui.SettingsNotificationSaveView
import org.json.JSONObject

/** Existing duration wheel + creation-only inline draft. Never mounts full tile controls or Save. */
internal fun verifyNativeSettingsDurationInteraction(instrumentation: Instrumentation, report: StringBuilder) {
    SettingsInteractionSuite(instrumentation, report, "native_settings_duration").run { suite, host, reference ->
        val state = mutableStateOf(SettingsNotificationSaveState(false, 60, 3600, true, 1L))
        val interaction = NotificationDurationInteractionState()
        val callbacks = mutableListOf<String>()
        var update: () -> Unit = {}
        fun enabled(value: Boolean) {
            callbacks += "enabled:$value"
            state.value = state.value.copy(enabled = value, editRevision = state.value.editRevision + 1)
            update()
        }
        fun seconds(value: Int) {
            callbacks += "seconds:$value"
            state.value = state.value.copy(seconds = value, editRevision = state.value.editRevision + 1)
            // Existing wheel publishes interactionChanged after its binding has committed.
            // Avoid rebinding midway through this callback; Compose likewise applies afterward.
        }
        val render = suite.mount(host, reference, source = {
            val current = state.value
            NotificationSaveSettings(current.enabled, current.seconds, current.maximumSeconds, ::enabled, ::seconds,
                current.interactionReady, current.editRevision, { state.value.editRevision }, interaction)
        }, native = { parent ->
            val view = SettingsNotificationSaveView(host, state.value, parent.environment, { parent.inputRoot },
                host.lifecycle, interaction, ::enabled, ::seconds, { state.value.editRevision })
            update = { view.bind(state.value, parent.environment) }
            view.callbacks(::seconds, { state.value.editRevision }, update)
            SettingsInteractionNativeContent(view, { update = {}; view.close() })
        })
        fun checkpoint(name: String) = suite.capture(name, { callbacks.toList() }, { JSONObject()
            .put("enabled", state.value.enabled).put("seconds", state.value.seconds)
            .put("revision", state.value.editRevision).put("pending", interaction.pending) })
        checkpoint("notification-collapsed")
        suite.main { render.click(host.getString(R.string.feature_notification_save)) }
        checkpoint("notification-initial")
        suite.main {
            // One dispatch turn intentionally stresses current owner revision, before recomposition.
            val label = host.getString(R.string.range_export_duration)
            check(render.setProgress(label, 59f))
            repeat(3) { check(render.wheelStep(true)) }
            check(render.wheelStep(false))
        }
        checkpoint("notification-rapid")
        check(state.value.seconds == 61)
        val acceptedBefore = callbacks.toList()
        suite.main {
            val b = render.target(host.getString(R.string.range_export_duration))
            val down = SystemClock.uptimeMillis()
            // Exact RangeDurationWheel.onSizeChanged: profile=19%, gap=14dp, then three time columns.
            val x = b.left + (b.width() * .81f - 14 * render.density.density).coerceAtLeast(1f) / 6f
            val y = b.exactCenterY()
            render.motion(MotionEvent.ACTION_DOWN, down, x, y)
            check(interaction.pending)
            render.motion(MotionEvent.ACTION_MOVE, down, x, y - 32 * render.density.density)
            state.value = state.value.copy(editRevision = state.value.editRevision + 1)
            update()
            render.motion(MotionEvent.ACTION_CANCEL, down, x, y - 32 * render.density.density)
        }
        suite.await("cancelled stale wheel settle") { !interaction.pending }
        checkpoint("notification-stale-owner")
        check(callbacks == acceptedBefore && state.value.seconds == 61)
        suite.main { render.click(host.getString(R.string.feature_notification_save)) }
        checkpoint("notification-disabled")
        suite.main { render.click(host.getString(R.string.feature_notification_save)) }
        checkpoint("notification-reopened")
        suite.closeRender()

        // Capacity reads use existing QA preferences without changing them. Full controls would
        // synchronize components, so only this exact editor and its in-memory owner are mounted.
        val capacities = readTileCapacities(host)
        check(capacities.maximumSeconds >= 120 && capacities.oneShotSeconds != null && capacities.loopingSeconds != null) {
            "Existing QA capacity must admit both buffers and a120-second draft; no preferences changed"
        }
        val editor = QuickSettingsTilesEditorState()
        var target = QuickSettingsTileEditorTarget().also { editor.target = it }
        val inputs = NativeQuickTilesInputs(true, true, true, 1L)
        var inline: NativeQuickTileInlineOwner? = null
        fun mountTile(): SettingsInteractionRender = suite.mount(host, reference, audioGroup = false, source = {
            if (editor.target === target) ReleasedSettingsQuickTileInlineEditor(target, editor,
                true, true, 1L, { 1L }, { 1L })
        }, native = { parent ->
            val container = FrameLayout(host).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            lateinit var view: QuickTileInlineView
            var retired = false
            fun changed() {
                if (retired) return
                if (editor.target === target) view.bind(parent.environment)
                else {
                    retired = true
                    try { view.close() } finally { container.removeView(view) }
                }
            }
            val owner = NativeQuickTileInlineOwner(host, host.lifecycle, parent.scope, editor,
                target, inputs, { 1L }, { 1L }, ::changed).also { inline = it }
            view = QuickTileInlineView(host, owner, host.lifecycle, parent.environment,
                { parent.inputRoot }, parent.scope)
            container.addView(view, FrameLayout.LayoutParams(-1, -2))
            owner.bind(inputs); view.bind(parent.environment)
            SettingsInteractionNativeContent(container, {
                var failure: Throwable? = null
                try { owner.close() } catch (error: Throwable) { failure = error }
                try { if (!retired) { retired = true; view.close() } } catch (error: Throwable) {
                    val first = failure; if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
                inline = null
                failure?.let { throw it }
            })
        })
        var tile = mountTile()
        suite.await("inline capacity read and wheel attachment") {
            val wheels = settingsInteractionViews(tile.board).filterIsInstance<RangeDurationWheelView>().toList()
            wheels.size == 1 && wheels.single().isLaidOut && (reference || inline?.capacityReady == true)
        }
        val originalWheel = nativeParityOnMain(instrumentation) { tile.wheel() }
        fun tileCheckpoint(name: String) = suite.capture(name, { emptyList() }, { JSONObject()
            .put("open", editor.target === target).put("buffer", target.buffer.storageCode.toInt())
            .put("seconds", target.seconds).put("edit_revision", target.editRevision).put("busy", target.busy)
            .put("maximum", capacities.maximumSeconds).put("one_shot_capacity", capacities.oneShotSeconds)
            .put("looping_capacity", capacities.loopingSeconds) })
        tileCheckpoint("tile-initial")
        for ((index, buffer) in listOf(ReverbService.BufferSlot.ONE_SHOT, ReverbService.BufferSlot.LOOPING,
            ReverbService.BufferSlot.ONE_SHOT).withIndex()) {
            suite.main { tile.click(host.getString(if (buffer == ReverbService.BufferSlot.ONE_SHOT)
                R.string.buffer_one_shot else R.string.buffer_loop)) }
            suite.await("selected buffer publication") { target.buffer == buffer }
            suite.observeDraws("tile-buffer-$index")
            suite.main { check(tile.wheel() === originalWheel) { "Buffer choice recreated the existing duration wheel" } }
            tileCheckpoint("tile-buffer-$index")
        }
        suite.main {
            tile.click(host.getString(R.string.buffer_loop))
            tile.click(host.getString(R.string.buffer_one_shot))
            check(target.buffer == ReverbService.BufferSlot.ONE_SHOT)
            check(tile.wheel() === originalWheel)
        }
        suite.observeDraws("tile-rapid-switch")
        tileCheckpoint("tile-rapid-switch")
        suite.main {
            val label = host.getString(R.string.range_export_duration)
            check(tile.setProgress(label, 60f))
            check(tile.wheelStep(true))
        }
        tileCheckpoint("tile-duration")
        check(target.seconds == 61)
        suite.main { tile.click(host.getString(R.string.cancel)) }
        suite.await("inline Cancel") { editor.target == null }
        tileCheckpoint("tile-cancelled")
        check(!target.busy && !editor.editorOpen)
        suite.closeRender()
        suite.main { target = QuickSettingsTileEditorTarget().also { editor.target = it } }
        tile = mountTile()
        suite.await("new inline draft capacity") {
            settingsInteractionViews(tile.board).filterIsInstance<RangeDurationWheelView>().count() == 1 &&
                (reference || inline?.capacityReady == true)
        }
        tileCheckpoint("tile-new-draft")
        check(target.seconds == DEFAULT_QUICK_SAVE_SECONDS && target.buffer == ReverbService.BufferSlot.LOOPING)
        suite.main { tile.click(host.getString(R.string.cancel)) }
        suite.await("new draft Cancel") { editor.target == null }
    }
}
