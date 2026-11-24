package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.SettingsRetentionInputView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeSegmentedChoice

/** Repeated actual chip activations in one retained QA Settings owner, with no settings commit. */
internal class NativeSettingsOwnerPerfSession(
    private val instrumentation: Instrumentation,
    private val theme: Boolean,
) : AutoCloseable {
    private val context = instrumentation.targetContext
    private val preferences = getRecorderPreferences(context).all.toMap()
    private val host = launchCurrentUiSmokeActivity(instrumentation)
    private lateinit var render: MainSettingsParityRender
    private lateinit var settings: NativeSettingsScreenContent
    private lateinit var group: NativeSegmentedChoice
    private lateinit var editor: SettingsRetentionInputView
    private lateinit var options: List<NativeChip>
    private var paintedPillBounds: RectF? = null
    private val owner get() = settings.owner

    init {
        try {
            render = nativeParityOnMain(instrumentation) {
                MainSettingsParityRender(host, false, AppThemeMode.LIGHT, false, null, animatedSurfaceForProbe = true)
            }
            val openLabel = host.getString(R.string.open_settings)
            await { render.actionCount(openLabel) == 1 }
            nativeParityOnMain(instrumentation) { render.click(openLabel) }
            await {
                descendants(render.board).filterIsInstance<NativeSettingsScreenContent>()
                    .firstOrNull { it.inputAllowed }?.also { settings = it } != null
            }
            val time = host.getString(smallthingz.ui.R.string.native_settings_time_option)
            val size = host.getString(smallthingz.ui.R.string.native_settings_size_option)
            await {
                descendants(settings).filterIsInstance<NativeSegmentedChoice>().firstOrNull { selector ->
                    if (theme) selector.choiceView("LIGHT") != null && selector.choiceView("DARK") != null
                    else chips(selector).map { it.text.toString() } == listOf(time, size)
                }?.also { group = it } != null
            }
            nativeParityOnMain(instrumentation) {
                val viewport = checkNotNull(settings.listViewport)
                val position = IntArray(2).also(group::getLocationOnScreen)
                val parentPosition = IntArray(2).also(viewport::getLocationOnScreen)
                viewport.scrollBy(0, position[1] + group.height / 2 - parentPosition[1] - viewport.height / 2)
                options = if (theme) listOf(checkNotNull(group.choiceView("LIGHT")), checkNotNull(group.choiceView("DARK")))
                    else chips(group)
                paintedPillBounds = actualPillBounds(group)
            }
            if (theme) selectForSetup(0)
            else {
                await {
                    descendants(settings).filterIsInstance<SettingsRetentionInputView>()
                        .firstOrNull { it.contentDescription == host.getString(R.string.buffer_one_shot) }
                        ?.also { editor = it } != null
                }
                seedDraft(0, "1:23")
                seedDraft(1, "384.50")
                selectForSetup(0)
                nativeParityOnMain(instrumentation) { verifyDrafts() }
            }
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun transition(cycle: NativeOwnerPerfCycle, iteration: Int) {
        val target = (iteration + 1) % 2
        val chip = options[target]
        val before = nativeParityOnMain(instrumentation) { observeVisual() }
        val durationScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .takeIf { it.isFinite() && it >= 0f } ?: 1f
        val settleNanos = (400_000_000.0 * maxOf(1f, durationScale)).toLong()
        val sampler = SettingsFrames(target, settleNanos)
        cycle.opening(host, deferTrigger = true)
        nativeParityOnMain(instrumentation) {
            check(settings.inputAllowed && host.hasWindowFocus() && !chip.isChecked && chip.getGlobalVisibleRect(Rect()))
            sampler.trigger = cycle.markTrigger()
            check(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            Choreographer.getInstance().postFrameCallback(sampler)
        }
        try {
            val timeoutMs = settleNanos / 1_000_000 + 5_000L
            check(sampler.ready.await(timeoutMs, TimeUnit.MILLISECONDS)) { "No Settings settling frames" }
            sampler.failure?.let { throw it }
        } finally {
            nativeParityOnMain(instrumentation) { Choreographer.getInstance().removeFrameCallback(sampler) }
        }
        check(sampler.response >= 0) { "The Settings owner did not accept the requested option" }
        nativeParityOnMain(instrumentation) {
            check(stateMatches(target))
            paintedPillBounds?.let { pill ->
                check(kotlin.math.abs(pill.left - chip.left) <= 1f && kotlin.math.abs(pill.right - chip.right) <= 1f) {
                    "Painted pill did not settle at the selected chip"
                }
            }
            if (theme) {
                check(chip.currentTextColor == settings.pointerEnvironment.palette.settingsOnSelection) {
                    "Selected theme ink did not settle"
                }
            } else {
                verifyDrafts()
                check(editor.translationY == 0f) { "Retention numeric transform did not settle" }
            }
        }
        cycle.retainedTransitionSettled(settings) { framesFile ->
            writeQaEvidenceAtomically(framesFile) { stream ->
                stream.write(sampler.toJson().toString().toByteArray(Charsets.UTF_8))
            }
            JSONObject().put("from_visual", before).put("input", "native_chip_accessibility_click")
                .put("target", if (theme) if (target == 0) "LIGHT" else "DARK" else if (target == 0) "TIME" else "SIZE")
                .put("trigger_to_first_response_vsync_ns", sampler.response)
                .put("response_kind", "owner state and checked chip at a sampled vsync")
                .put("trigger_to_first_pill_bounds_change_vsync_ns", sampler.pillMovement.takeIf { it >= 0L } ?: JSONObject.NULL)
                .put("pill_bounds_source", if (paintedPillBounds != null) "actual NativeSelectionIndicator painted bounds in selector coordinates"
                    else "this selector has no moving fill")
                .put("animator_duration_scale", durationScale.toDouble())
                .put("settings_frame_samples_count", sampler.count).put("settings_frame_samples_truncated", sampler.truncated)
        }
    }

    private inner class SettingsFrames(private val target: Int, private val settleNanos: Long) : Choreographer.FrameCallback {
        private val timestamps = LongArray(1_200)
        private val selected = BooleanArray(timestamps.size)
        private val visualFlags = IntArray(timestamps.size)
        private val textAndSurfaceColors = IntArray(timestamps.size * 3)
        private val numericOffsets = FloatArray(timestamps.size)
        private val pillBounds = FloatArray(timestamps.size * 4)
        private val pillBefore = nativeParityOnMain(instrumentation) { paintedPillBounds?.let(::RectF) }
        val ready = CountDownLatch(1)
        var trigger = Long.MAX_VALUE
        var response = -1L
            private set
        var pillMovement = -1L
            private set
        var count = 0
            private set
        var truncated = 0
            private set
        var failure: Throwable? = null
            private set

        override fun doFrame(frameTimeNanos: Long) {
            try {
                val elapsed = frameTimeNanos - trigger
                if (elapsed >= 0) record(elapsed)
                if (elapsed >= settleNanos) ready.countDown() else Choreographer.getInstance().postFrameCallback(this)
            } catch (error: Throwable) {
                failure = error
                ready.countDown()
            }
        }

        private fun record(elapsed: Long) {
            val targetState = stateMatches(target)
            val bounds = paintedPillBounds
            if (targetState && response < 0) response = elapsed
            if (pillBefore != null && pillBefore != bounds && pillMovement < 0) pillMovement = elapsed
            if (count == timestamps.size) { truncated++; return }
            val index = count++
            timestamps[index] = elapsed
            selected[index] = targetState
            if (theme) {
                visualFlags[index] = (if (render.effectiveDark) 1 else 0) or
                    (if (options[0].isChecked) 2 else 0) or (if (options[1].isChecked) 4 else 0)
                textAndSurfaceColors[index * 3] = options[0].currentTextColor
                textAndSurfaceColors[index * 3 + 1] = options[1].currentTextColor
                textAndSurfaceColors[index * 3 + 2] = settings.pointerEnvironment.palette.surface
            } else {
                visualFlags[index] = (if (owner.snapshot.retentionMode == RetentionMode.SIZE) 1 else 0) or (draftCode() shl 1)
                numericOffsets[index] = editor.translationY
            }
            if (bounds != null) {
                val slot = index * 4
                pillBounds[slot] = bounds.left
                pillBounds[slot + 1] = bounds.top
                pillBounds[slot + 2] = bounds.right
                pillBounds[slot + 3] = bounds.bottom
            }
        }

        fun toJson(): JSONArray = JSONArray().also { samples ->
            for (index in 0 until count) {
                val flags = visualFlags[index]
                val visual = if (theme) {
                    JSONObject().put("effective_dark", flags and 1 != 0).put("checked_light", flags and 2 != 0)
                        .put("checked_dark", flags and 4 != 0).put("light_text_argb", textAndSurfaceColors[index * 3])
                        .put("dark_text_argb", textAndSurfaceColors[index * 3 + 1])
                        .put("surface_argb", textAndSurfaceColors[index * 3 + 2])
                } else JSONObject().put("mode", if (flags and 1 != 0) "SIZE" else "TIME")
                    .put("number_text", when (flags shr 1) { 1 -> "1:23"; 2 -> "384.50"; else -> JSONObject.NULL })
                    .put("number_offset_y_px", numericOffsets[index].toDouble())
                val slot = index * 4
                visual.put("painted_pill_bounds_px", if (pillBefore == null) JSONObject.NULL else
                    JSONArray(listOf(pillBounds[slot], pillBounds[slot + 1], pillBounds[slot + 2], pillBounds[slot + 3])))
                samples.put(JSONObject().put("offset_ns", timestamps[index]).put("target_state", selected[index]).put("visual", visual))
            }
        }
    }

    private fun draftCode(): Int = when {
        TextUtils.equals(editor.text, "1:23") -> 1
        TextUtils.equals(editor.text, "384.50") -> 2
        else -> 0
    }

    private fun stateMatches(index: Int): Boolean {
        if (!options[index].isChecked) return false
        return if (theme) {
            owner.snapshot.themeMode == (if (index == 0) AppThemeMode.LIGHT else AppThemeMode.DARK) && render.effectiveDark == (index == 1)
        } else {
            owner.snapshot.retentionMode == (if (index == 0) RetentionMode.TIME else RetentionMode.SIZE) &&
                draftCode() == index + 1
        }
    }

    private fun actualPillBounds(group: NativeSegmentedChoice): RectF? {
        // Reliability-only observation of the exact RectF consumed by the shared pill's draw code.
        val indicator = NativeSegmentedChoice::class.java.getDeclaredField("selectionIndicator")
            .apply { isAccessible = true }.get(group) ?: return null
        return indicator.javaClass.getDeclaredField("displayed").apply { isAccessible = true }.get(indicator) as RectF
    }

    private fun observeVisual(): JSONObject = (if (theme) {
        JSONObject().put("effective_dark", render.effectiveDark).put("checked_light", options[0].isChecked)
            .put("checked_dark", options[1].isChecked).put("light_text_argb", options[0].currentTextColor)
            .put("dark_text_argb", options[1].currentTextColor)
            .put("surface_argb", settings.pointerEnvironment.palette.surface)
    } else {
        JSONObject().put("mode", owner.snapshot.retentionMode.name).put("number_text", editor.text.toString())
            .put("number_offset_y_px", editor.translationY.toDouble())
    }).put("painted_pill_bounds_px", paintedPillBounds?.let { bounds ->
        JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
    } ?: JSONObject.NULL)

    private fun verifyDrafts() {
        val drafts = owner.state.displayed
        check(drafts.oneShotTimeText == "1:23" && drafts.oneShotSizeText == "384.50")
    }

    private fun selectForSetup(index: Int) {
        nativeParityOnMain(instrumentation) {
            if (!options[index].isChecked) {
                check(options[index].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            }
        }
        await { options[index].isChecked }
    }

    private fun seedDraft(index: Int, text: String) {
        selectForSetup(index)
        nativeParityOnMain(instrumentation) {
            val input = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            try {
                check(input.setSelection(0, editor.length()) && input.commitText(text, 1))
            } finally { input.closeConnection() }
            check(editor.text.toString() == text)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!nativeParityOnMain(instrumentation, condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Actual Settings owner did not settle" }
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        }
    }

    override fun close() {
        try {
            nativeParityOnMain(instrumentation) {
                try { if (::render.isInitialized) render.close() } finally { host.finish() }
            }
        } finally { check(getRecorderPreferences(context).all == preferences) }
    }

    private fun chips(group: NativeSegmentedChoice): List<NativeChip> =
        (0 until group.childCount).mapNotNull { group.getChildAt(it) as? NativeChip }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
