package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.material3.Typography
import app.smallthingz.reverb.nativeui.*
import java.io.File
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

/** Current UI only: synthetic metadata/models, no recorder, player, repository or permissions. */
internal fun verifyNativeThemeMatrix(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-theme-matrix-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val evidence = JSONArray()
    val result = JSONObject().put("selector", "native_theme_matrix").put("passed", false)
        .put("historical_parity_claim", false).put("scenes", evidence)
    val host = launchCurrentUiSmokeActivity(instrumentation)
    fun main(action: () -> Unit) = nativeParityOnMain(instrumentation, action)
    fun settle() { repeat(24) { awaitNativeParityDraw(instrumentation, host.fixtureRoot); instrumentation.waitForIdleSync() } }
    fun capture(name: String, root: View, targets: (ReverbParagraphView) -> Boolean = { false }) {
        settle()
        val hardware = captureNativeParityContent(instrumentation, host) { root }
        try { saveNativeParityBitmap(directory, "$name.png", hardware) } finally { hardware.recycle() }
        val scene = JSONObject().put("name", name).put("hardware_png", "$name.png")
        scene.put("hardware_glyph_observations", measureThemeText(instrumentation, host, host.fixtureRoot, targets))
        evidence.put(scene)
    }
    var failure: Throwable? = null
    try {
        for (dark in listOf(false, true)) {
            val mode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT
            val name = if (dark) "dark" else "light"
            val palette = ReverbPalette.resolve(host, dark)
            val runtime = nativeParityOnMain(instrumentation) { NativeAboutWindowRuntime(host).apply { setStarted(true) } }
            try {
                val home = nativeParityOnMain(instrumentation) {
                    val environment = nativeParityEnvironment(host,
                        androidx.compose.ui.unit.Density(host.resources.displayMetrics.density, host.resources.configuration.fontScale), palette)
                    val forbidden: () -> Unit = { error("Theme fixture must not perform app actions") }
                    val actions = ReverbHomeActions({ forbidden() }, { forbidden() }, { forbidden() },
                        { forbidden() }, { forbidden() }, { forbidden() }, forbidden)
                    ReverbHomeScreenView(host, environment,
                        ReverbHomeBodyState(null, BufferMetrics(65f, 6000000), BufferMetrics(120f, 11000000),
                            true, false, true, RetentionMode.TIME, false, false, true, true, false,
                            AudioBlobController(), AudioBlobController(), false),
                        ReverbService.BufferSlot.ONE_SHOT, actions,
                        ReverbAppTopBarActions(forbidden, forbidden, forbidden, null), false,
                        ReverbHomeInsets(0, 0, 0, 0), host.fixtureRoot, runtime.scope, { _, _, _ -> }).also {
                            host.fixtureRoot.setBackgroundColor(palette.surface)
                            host.fixtureRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
                        }
                }
                try { capture("$name-home", home) { it.getText() == host.getString(R.string.buffer_loop) } }
                finally { main { home.close(); host.fixtureRoot.removeView(home) } }
                val editor = nativeParityOnMain(instrumentation) { RangeExportEditorState(120f) }
                val range = nativeParityOnMain(instrumentation) {
                    val environment = nativeParityEnvironment(host,
                        androidx.compose.ui.unit.Density(host.resources.displayMetrics.density, host.resources.configuration.fontScale), palette)
                    val density = host.resources.displayMetrics.density
                    android.widget.LinearLayout(host).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setBackgroundColor(palette.field)
                        val timeline = ReverbRangeTimelineView(host, environment) {}
                        timeline.bind(editor, NativeRangeExportPresentation(null, null, true, true, 0f,
                            false, false, 120.0, 1f,
                            ProgressiveWaveformData(FloatArray(32) { .35f }, 32, FloatArray(64) { .4f }, 64, RangeWaveformPass.COARSE),
                            ProgressiveWaveformFrame(1f, 1f, 1f, 0f, 0f, 1f, false)))
                        addView(timeline, android.widget.LinearLayout.LayoutParams(-1, (180 * density).toInt()))
                        val controls = ReverbRangeExportControls(host, environment, { host.fixtureRoot }, runtime.scope)
                        controls.bind(environment, editor, false, false, ReverbService.BufferSlot.ONE_SHOT,
                            { error("No range cancellation") }, { error("No export") })
                        addView(controls, android.widget.LinearLayout.LayoutParams(-1, (70 * density).toInt()))
                        host.fixtureRoot.addView(this, FrameLayout.LayoutParams(-1, -1))
                    }
                }
                try { capture("$name-range", range) {
                    it.getText() in setOf(formatRangeTimeInput(0.0), formatRangeTimeInput(120.0), host.getString(R.string.buffer_one_shot))
                } } finally { main { editor.close(); host.fixtureRoot.removeView(range) } }
                val environment = nativeParityOnMain(instrumentation) { nativeParityEnvironment(host,
                    androidx.compose.ui.unit.Density(host.resources.displayMetrics.density, host.resources.configuration.fontScale), palette) }
                val library = nativeParityOnMain(instrumentation) {
                    createNativeThemeLibraryFixture(host, environment, runtime.scope, host.fixtureRoot).also {
                        host.fixtureRoot.addView(it.view, FrameLayout.LayoutParams(-1, -1))
                    }
                }
                try { capture("$name-library", library.view) { it.getText() in library.contrastLabels }; main { library.verify() } }
                finally { main { library.close(); host.fixtureRoot.removeView(library.view) } }
                val calibrationFailure = actionableErrorMessage(host, host.getString(R.string.silence_sample_failed),
                    java.io.IOException("The calibration result could not be stored by the selected provider. Reconnect the provider account and retry calibration after checking its available storage."))
                for ((clearance, progress, terminalError) in listOf(
                    Triple(0f, null, null), Triple(6f, null, null), Triple(12f, null, null),
                    Triple(6f, 0f, null), Triple(6f, .5f, null), Triple(6f, 1f, null),
                    Triple(6f, null, calibrationFailure))) {
                    val calibrationContent = nativeParityOnMain(instrumentation) {
                        SettingsSilenceView(host, SettingsSilenceState(
                            config = SilenceGateConfig(enabled = true, clearanceDb = clearance),
                            sampledPeaks = List(SILENCE_GRAPH_BINS) { -70f },
                            calibrating = progress != null, calibrationProgress = progress, calibrationError = terminalError),
                            environment, { host.fixtureRoot }, runtime.scope, object : SettingsSilenceActions {
                                override fun configChanged(config: SilenceGateConfig) { error("No settings writes") }
                                override fun calibrate() { error("No microphone") }
                                override fun beginHeld() = false
                                override fun completeTap() { error("No microphone") }
                                override fun finishHeld() { error("No microphone") }
                                override fun cancel() {}
                            })
                    }
                    val calibration = nativeParityOnMain(instrumentation) {
                        android.widget.ScrollView(host).apply {
                            setBackgroundColor(palette.field)
                            addView(calibrationContent, FrameLayout.LayoutParams(-1, -2))
                            host.fixtureRoot.addView(this, FrameLayout.LayoutParams(-1, -1))
                        }
                    }
                    settle()
                    main { calibration.fullScroll(View.FOCUS_DOWN) }
                    try { capture("$name-calibration-$clearance-${progress ?: if (terminalError == null) "idle" else "error"}", calibration) {
                        it.getText() in setOf(host.getString(R.string.silence_calibration_heading),
                            host.getString(R.string.silence_calibration_hint), host.getString(R.string.silence_sampling),
                            host.getString(R.string.silence_calibrate), terminalError)
                    }
                        if (terminalError != null) main {
                            fun find(view: View): ReverbParagraphView? {
                                if (view is ReverbParagraphView && view.getText() == terminalError) return view
                                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                                return null
                            }
                            val text = checkNotNull(find(calibrationContent))
                            val bounds = Rect()
                            check(text.intrinsicTextHeight(text.width) <= text.height) { "Calibration error text clipped" }
                            check(text.getGlobalVisibleRect(bounds) && bounds.height() == text.height) { "Calibration error not fully visible after scroll" }
                        }
                    } finally { main { calibrationContent.close(); host.fixtureRoot.removeView(calibration) } }
                }
            } finally { main { runtime.close() } }
            val settings = nativeParityOnMain(instrumentation) { MainSettingsParityRender(host, false, mode, dark, null) }
            try {
                settle()
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (nativeParityOnMain(instrumentation) { settings.actionCount(host.getString(R.string.open_settings)) } != 1) {
                    check(android.os.SystemClock.uptimeMillis() < deadline) { "Settings action unavailable" }
                    settle()
                }
                main { settings.click(host.getString(R.string.open_settings)) }
                capture("$name-settings", settings.board) { it.getText() in setOf(host.getString(R.string.theme_title), host.getString(R.string.retention_mode_title)) }
                val longError = actionableErrorMessage(host, host.getString(R.string.share_recording_failed),
                    java.io.IOException("The selected storage provider cannot create a shareable document while its account is offline. Reconnect that account in the provider app and retry this operation."))
                val oldIds = AppFeedbackCenter.events.value.map { it.id }.toSet()
                AppFeedbackCenter.post(longError, FeedbackTone.ERROR)
                val synthetic = AppFeedbackCenter.events.value.single { it.id !in oldIds && it.message == longError }
                try {
                    val errorDeadline = android.os.SystemClock.uptimeMillis() + 5000
                    var visible: ReverbParagraphView? = null
                    while (visible == null) {
                        main {
                            fun find(view: View): ReverbParagraphView? {
                                if (view is ReverbParagraphView && view.getText() == longError && view.isShown) return view
                                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                                return null
                            }
                            visible = find(settings.board)
                        }
                        check(android.os.SystemClock.uptimeMillis() < errorDeadline) { "Long error was not shown" }
                        if (visible == null) settle()
                    }
                    capture("$name-actionable-error", settings.board) { it.getText() == longError }
                    main {
                        val text = checkNotNull(visible)
                        val bounds = Rect()
                        check(text.getGlobalVisibleRect(bounds) && bounds.width() == text.width && bounds.height() == text.height)
                        check(text.intrinsicTextHeight(text.width) <= text.height) { "Actionable error text clipped" }
                        val node = text.createAccessibilityNodeInfo()
                        check(node?.text?.toString() == longError) { "Actionable error accessibility text incomplete" }
                        @Suppress("DEPRECATION")
                        node?.recycle()
                    }
                } finally {
                    // Never clear another queued event; this fixture owns only its exact synthetic ID.
                    AppFeedbackCenter.acknowledge(synthetic.id)
                }
                main { check(settings.forbiddenCalls == 0) }
            } finally { main { settings.close(); host.fixtureRoot.removeAllViews() } }
            for (ready in listOf(false, true)) {
                val onboarding = nativeParityOnMain(instrumentation) {
                    showOnboardingScreen(host, Typography(), ScreenParityCase(dark, false, 1f, ready), false,
                        null, { error("No permission requests") }, { error("No onboarding persistence") })
                }
                try { capture("$name-onboarding-${if (ready) "ready" else "permissions"}", onboarding.view) { it.getText() in setOf(host.getString(R.string.onboarding_permissions_title), host.getString(R.string.onboarding_permissions_body), host.getString(R.string.onboarding_title),
                        host.getString(R.string.onboarding_one_shot_body), host.getString(R.string.onboarding_looping_body)) } }
                finally { main { onboarding.close(); host.fixtureRoot.removeAllViews() } }
            }
        }
        check(getRecorderPreferences(context).all == preferences)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error; result.put("error", android.util.Log.getStackTraceString(error)) }
    finally {
        failure = captureNativeParityCleanup(failure) { main { host.finish() } }
        File(directory, "result.json").writeText(result.toString(2))
    }
    failure?.let { throw it }
    report.appendLine("PASS native_theme_matrix current isolated UI; evidence ${directory.absolutePath}")
}

/** Cross-frame hardware observations only; host semantic composites remain the contrast gate. */
private fun measureThemeText(instrumentation: Instrumentation, host: NativeParityActivity,
    root: View, targets: (ReverbParagraphView) -> Boolean): JSONArray {
    fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }
    fun area(text: View): Rect = Rect(0, 0, text.width, text.height).also {
        (root as? ViewGroup)?.offsetDescendantRectToMyCoords(text, it)
        if (!it.intersect(0, 0, root.width, root.height)) it.setEmpty()
    }
    val candidates = nativeParityOnMain(instrumentation) {
        check(root.width.toLong() * root.height <= 12_000_000L) { "Theme readback exceeds bounded fixture size" }
        views(root).filterIsInstance<ReverbParagraphView>().filter(targets).filter {
            it.isShown && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(Rect())
        }.take(12).toList()
    }
    val rows = JSONArray()
    for (text in candidates) {
        val bounds = nativeParityOnMain(instrumentation) { area(text) }
        if (bounds.isEmpty) continue
        val label = nativeParityOnMain(instrumentation) { text.getText() }
        val visibility = nativeParityOnMain(instrumentation) { text.visibility }
        var before: Bitmap? = null
        var without: Bitmap? = null
        try {
            // PixelCopy uses the real hardware-rendered window, including RuntimeShader blobs.
            before = captureNativeParityContent(instrumentation, host) { root }
            nativeParityOnMain(instrumentation) { text.visibility = View.INVISIBLE }
            try { without = captureNativeParityContent(instrumentation, host) { root } }
            finally { nativeParityOnMain(instrumentation) { text.visibility = visibility } }
            val stable = nativeParityOnMain(instrumentation) { bounds == area(text) && label == text.getText() }
            var strongest = 1.0
            var changed = 0
            val foregroundPixels = checkNotNull(before)
            val backgroundPixels = checkNotNull(without)
            if (stable) for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                val ink = foregroundPixels.getPixel(x, y); val background = backgroundPixels.getPixel(x, y)
                if (ink == background || ink ushr 24 != 255 || background ushr 24 != 255) continue
                changed++
                strongest = maxOf(strongest, themePixelContrast(ink, background))
            }
            rows.put(JSONObject().put("text", label).put("geometry_stable", stable)
                .put("changed_pixels", changed).put("strongest_pixel_contrast", strongest)
                .put("measurement", "cross-frame window PixelCopy; target-region comparison")
                .put("contrast_gate", false)
                .put("limitation", "Animation, antialiasing and cross-frame changes can affect pixels; host role composites are the numerical gate"))
        } finally {
            nativeParityOnMain(instrumentation) { text.visibility = visibility }
            before?.recycle(); without?.recycle()
        }
    }
    check(rows.length() > 0) { "Theme scene has no visible contrast target" }
    return rows
}

private fun themePixelContrast(a: Int, b: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = (color ushr shift and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    val first = luminance(a); val second = luminance(b)
    return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
}
