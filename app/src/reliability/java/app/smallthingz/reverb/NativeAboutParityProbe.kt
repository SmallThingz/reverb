package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ComposeViewContext
import androidx.compose.ui.platform.ExperimentalComposeViewContextApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import smallthingz.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import app.smallthingz.reverb.nativeui.AboutContentAppearance
import app.smallthingz.reverb.nativeui.NativeVerticalViewport
import app.smallthingz.reverb.nativeui.ReverbAboutDialog
import app.smallthingz.reverb.nativeui.ReverbPalette
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Proposal: actual released AboutDialog versus native dialog, in the isolated QA process.
 * No production route, AccessibilityService, browser launch, settings, permissions, audio,
 * recordings or backend callback is used. Keyboard-mode entry uses only a host-coordinated
 * explicitly physical-display-0 TAB; all other existing own-window actions remain local.
 */
internal fun verifyNativeAboutParity(
    instrumentation: Instrumentation,
    report: StringBuilder,
    keyboardSessionNonce: String?,
) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(Build.VERSION.SDK_INT >= 29) { "WindowInspector is public from API29; no hidden API fallback" }
    val directory = File(context.filesDir, "native-about-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val entries = JSONArray()
    val keyboardRequests = JSONArray()
    val result = JSONObject()
        .put("oracle", "Unchanged released AboutDialog called directly; zero source substitutions")
        .put("comparison", "Exact ARGB; no masks/tolerance; short case constrains both own windows to240dp")
        .put("physical_input_injected", false)
        .put("keyboard_requests", keyboardRequests)
        .put("physical_input_scope", "Only nonce-bound host keyboard TAB on explicitly physical display0; no app-side injection")
        .put("scene_labels", "Requested palette/configuration only; actual rendered colors are retained separately")
        .put("motion_phase_qualified", false)
        .put("limits", "Own-window synthetic activation and read-only semantics, plus explicitly targeted host keyboard-mode entry; actual system predictive dispatch, TalkBack and stopped/resumed Activity remain separate")
        .put("cases", entries)
    var host: NativeParityActivity? = null
    var current: AboutFixture? = null
    var failure: Throwable? = null
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val scale = nativeParityOnMain(instrumentation) { android.animation.ValueAnimator.getDurationScale() }
        check(scale.isFinite() && scale in 0f..10f) {
            "Current animation scale $scale exceeds finite fixture timing admission; it is not changed"
        }
        val settledMillis = (300f * scale).toLong() + 120L
        for (case in listOf(AboutCase(false), AboutCase(true), AboutCase(true, short = true))) {
            val captures = mutableListOf<Bitmap>()
            val repeats = mutableListOf<Bitmap>()
            val runResults = JSONArray()
            val entry = JSONObject().put("name", case.name).put("runs", runResults)
            entries.put(entry)
            try {
                for (compose in listOf(true, false)) {
                    current = AboutFixture.open(instrumentation, activity, case, compose) { current = it }
                    val fixture = checkNotNull(current)
                    fixture.awaitSettled(settledMillis)
                    fixture.guard()
                    captures += fixture.capture()
                    saveNativeParityBitmap(directory, case.name + "-" + fixture.renderer + ".png", captures.last())
                    fixture.awaitFrames(2)
                    repeats += fixture.capture()
                    saveNativeParityBitmap(directory, case.name + "-" + fixture.renderer + "-repeat.png", repeats.last())
                    val run = JSONObject().put("renderer", fixture.renderer)
                        .put("window", fixture.windowSnapshot())
                        .put("semantics", fixture.semanticSnapshot())
                    runResults.put(run)
                    if (case.short) {
                        val before = fixture.scrollOffset()
                        fixture.scrollForward()
                        fixture.awaitSettled((650f * maxOf(1f, scale)).toLong())
                        val after = fixture.scrollOffset()
                        check(after > before) { "Short About window did not scroll forward: $before->$after" }
                        run.put("scroll_before", before.toDouble()).put("scroll_after", after.toDouble())
                        val scrolled = fixture.capture()
                        try { saveNativeParityBitmap(directory, case.name + "-" + fixture.renderer + "-scrolled.png", scrolled) }
                        finally { scrolled.recycle() }
                        fixture.ordinaryBack()
                    } else {
                        fixture.pointerClose()
                    }
                    fixture.awaitDismissed()
                    check(fixture.dismissCount == 1) { "Close must deliver one owner dismissal" }
                    run.put("trace", fixture.trace).put("dismiss_count", fixture.dismissCount)
                    fixture.close()
                    current = null
                }
                val diff = compareNativeParityPixels(captures[0], captures[1])
                val referenceStability = compareNativeParityPixels(captures[0], repeats[0])
                val nativeStability = compareNativeParityPixels(captures[1], repeats[1])
                entry.put("difference", diff).put("compose_stability", referenceStability)
                    .put("native_stability", nativeStability)
                val passed = diff.getLong("different_pixels") == 0L &&
                    referenceStability.getLong("different_pixels") == 0L &&
                    nativeStability.getLong("different_pixels") == 0L
                entry.put("passed", passed)
                // Retain all visual cases rather than stop after the first raster discrepancy.
            } finally {
                captures.forEach(Bitmap::recycle)
                repeats.forEach(Bitmap::recycle)
                current?.let { entry.put("unfinished_trace", it.trace); it.close() }
                current = null
                File(directory, "result.json").writeText(result.toString(2))
            }
        }

        for (scenario in AboutInteraction.entries) {
            val entry = JSONObject().put("name", scenario.name)
            val runs = JSONArray()
            entries.put(entry.put("runs", runs))
            for (compose in listOf(true, false)) {
                current = AboutFixture.open(instrumentation, activity, AboutCase(true), compose) { current = it }
                val fixture = checkNotNull(current)
                fixture.awaitSettled(settledMillis)
                var exercised = true
                when (scenario) {
                    AboutInteraction.KEYBOARD_CLOSE -> {
                        fixture.keyboardClose(directory, keyboardSessionNonce) { request ->
                            keyboardRequests.put(request)
                            if (!result.optBoolean("physical_input_injected", false)) {
                                // A pending command can have an unknown outcome if the lane stops.
                                result.put("physical_input_injected", JSONObject.NULL)
                            }
                            File(directory, "result.json").writeText(result.toString(2))
                        }
                        result.put("physical_input_injected", true)
                    }
                    AboutInteraction.ORDINARY_BACK -> fixture.ordinaryBack()
                    AboutInteraction.REPEATED_BACK -> {
                        fixture.ordinaryBack()
                        awaitAboutFrame(instrumentation)
                        // On duration-scale0 the first event may already dismiss. Do not send to a
                        // retired Window or mislabel an unexercised repeated event as a pass.
                        if (fixture.dismissCount == 0) fixture.ordinaryBack()
                        else { exercised = false; fixture.mark("repeat_unexercised_already_dismissed") }
                    }
                    AboutInteraction.PREDICTIVE_CANCEL_COMMIT -> {
                        val original = fixture.capture()
                        try {
                            fixture.predictiveStart()
                            fixture.predictiveProgress(.6f)
                            fixture.awaitFrames(2)
                            val partial = fixture.capture()
                            try { saveNativeParityBitmap(directory, "back-partial-" + fixture.renderer + ".png", partial) }
                            finally { partial.recycle() }
                            fixture.predictiveCancel()
                            fixture.awaitFrames(2)
                            check(fixture.dismissCount == 0) { "Predictive cancellation dismissed About" }
                            val restored = fixture.capture()
                            try {
                                val restoredDiff = compareNativeParityPixels(original, restored)
                                check(restoredDiff.getLong("different_pixels") == 0L) {
                                    "Cancelled predictive Back did not restore the unchanged rendered window: $restoredDiff"
                                }
                            } finally { restored.recycle() }
                            fixture.predictiveStart()
                            fixture.predictiveProgress(.6f)
                            fixture.awaitFrames(2)
                            fixture.ordinaryBack() // Dispatcher commit of the in-flight predictive session.
                        } finally { original.recycle() }
                    }
                }
                fixture.awaitDismissed()
                check(fixture.dismissCount == 1)
                runs.put(JSONObject().put("renderer", fixture.renderer)
                    .put("dismiss_count", fixture.dismissCount).put("trace", fixture.trace).put("exercised", exercised))
                fixture.close()
                current = null
            }
            entry.put("passed", (0 until runs.length()).all { runs.getJSONObject(it).getBoolean("exercised") })
            File(directory, "result.json").writeText(result.toString(2))
        }
        val mismatches = (0 until entries.length()).count { !entries.getJSONObject(it).optBoolean("passed") }
        check(mismatches == 0) { "About parity has $mismatches failing case(s); preserve result.json" }
        result.put("passed", true)
        report.append("PASS native_about_parity bounded own-window raster/action checks; frame-phase/TalkBack/system gestures unqualified\n")
    } catch (error: Throwable) {
        failure = error
        result.put("passed", false).put("terminal_error", android.util.Log.getStackTraceString(error))
        current?.let { result.put("unfinished_trace", it.trace) }
        throw error
    } finally {
        var cleanupError: Throwable? = null
        try {
            current?.close()
            host?.let { activity -> nativeParityOnMain(instrumentation) { activity.finish() } }
        } catch (error: Throwable) {
            cleanupError = error
            failure?.addSuppressed(error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error)).put("passed", false)
        }
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        try { File(directory, "result.json").writeText(result.toString(2)) }
        catch (writeError: Throwable) {
            val previous = failure ?: cleanupError
            if (previous == null) throw writeError else previous.addSuppressed(writeError)
        }
        if (failure == null) cleanupError?.let { throw it }
    }
}

private data class AboutCase(val dark: Boolean, val short: Boolean = false) {
    val name get() = (if (dark) "dark" else "light") + (if (short) "-short240dp" else "-full")
}
private enum class AboutInteraction { KEYBOARD_CLOSE, ORDINARY_BACK, REPEATED_BACK, PREDICTIVE_CANCEL_COMMIT }

@OptIn(ExperimentalComposeViewContextApi::class)
private class AboutFixture private constructor(
    private val instrumentation: Instrumentation,
    private val activity: NativeParityActivity,
    private val case: AboutCase,
    private val compose: Boolean,
) : AutoCloseable {
    val renderer get() = if (compose) "compose" else "native"
    val trace = JSONArray()
    private val sceneConfiguration = JSONObject()
    var dismissCount = 0
        private set
    private val dismissed = CountDownLatch(1)
    private val visible = mutableStateOf(true)
    private var composeHost: ComposeView? = null
    private var composeViewContext: ComposeViewContext? = null
    private var nativeDialog: ReverbAboutDialog? = null
    private lateinit var window: Window
    private var closed = false
    private var frameNumber = 0
    private var activeDownTime = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (closed) return
            frameNumber++
            if (::window.isInitialized && window.decorView.isAttachedToWindow) {
                val decor = window.decorView
                trace.put(JSONObject().put("event", "frame").put("frame", frameNumber)
                    .put("frame_nanos", frameTimeNanos).put("uptime_ms", SystemClock.uptimeMillis())
                    .put("width", decor.width).put("height", decor.height)
                    .put("focused", decor.hasWindowFocus())
                    .put("close_bounds", runCatching { closeTarget().toShortString() }
                        .getOrElse { "Unavailable: " + it.javaClass.simpleName }))
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun ownerDismissed() {
        mark("owner_dismiss")
        dismissCount++
        visible.value = false
        nativeDialog?.dismiss()
        dismissed.countDown()
    }

    companion object {
        fun open(instrumentation: Instrumentation, host: NativeParityActivity, case: AboutCase, compose: Boolean,
                 onCreated: (AboutFixture) -> Unit): AboutFixture {
            val fixture = AboutFixture(instrumentation, host, case, compose)
            // Preserve an opening failure's trace before openWindow can throw; cleanup stays owned.
            onCreated(fixture)
            try {
                fixture.recordActivityHost("before-first-host-guard")
                requireNativeParityHost(instrumentation, host)
                fixture.recordActivityHost("after-first-host-guard")
                fixture.openWindow()
                return fixture
            } catch (error: Throwable) {
                try { fixture.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }

    /** Diagnostics only: a successful first guard is observed focus, never permission to wait for reacquisition. */
    private fun recordActivityHost(event: String) = nativeParityOnMain(instrumentation) {
        trace.put(activityHostSnapshot(event))
    }

    private fun activityHostSnapshot(event: String): JSONObject {
        val root = activity.fixtureRoot
        val decor = activity.window.decorView
        return JSONObject().put("event", event).put("uptime_ms", SystemClock.uptimeMillis())
            .put("lifecycle", activity.lifecycle.currentState.name)
            .put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
            .put("activity_focused", activity.hasWindowFocus())
            .put("root_focused", root.hasWindowFocus()).put("root_shown", root.isShown)
            .put("root_attached", root.isAttachedToWindow).put("root_display", root.display?.displayId ?: JSONObject.NULL)
            .put("decor_focused", decor.hasWindowFocus()).put("decor_shown", decor.isShown)
            .put("decor_attached", decor.isAttachedToWindow).put("decor_display", decor.display?.displayId ?: JSONObject.NULL)
            .put("decor_identity", System.identityHashCode(decor))
            .put("decor_registered", decor in WindowInspector.getGlobalWindowViews())
    }

    private fun openWindow() {
        lateinit var before: Set<View>
        nativeParityOnMain(instrumentation) {
            val admission = activityHostSnapshot("open-window-guard").also(trace::put)
            check(!activity.isFinishing && activity.hasWindowFocus() && activity.fixtureRoot.isShown) {
                "About Activity readiness changed before its dialog opened: $admission"
            }
            check(activity.fixtureRoot.display?.displayId == Display.DEFAULT_DISPLAY) {
                "About Activity root left physical0 before its dialog opened: $admission"
            }
            before = WindowInspector.getGlobalWindowViews().toSet()
            val configuration = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            // Activity remains the base Context, preserving its ordinary Window token/lifecycle.
            val local = ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(configuration) }
            val density = Density(local.resources.displayMetrics.density, configuration.fontScale)
            val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
                .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (case.dark) dynamicDarkColorScheme(local) else dynamicLightColorScheme(local)
            } else if (case.dark) darkColorScheme() else lightColorScheme()
            val expectedNight = if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val resolvedNight = local.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val nativePalette = ReverbPalette.resolve(local, case.dark)
            fun argb(value: Int) = value.toLong() and 0xffffffffL
            sceneConfiguration.put("requested_dark", case.dark).put("requested_night", expectedNight)
                .put("resolved_night", resolvedNight)
                .put("activity_night", activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                .put("surface_argb", argb(colors.surface.toArgb()))
                .put("surface_high_argb", argb(colors.surfaceContainerHigh.toArgb()))
                .put("surface_highest_argb", argb(colors.surfaceContainerHighest.toArgb()))
                .put("on_surface_argb", argb(colors.onSurface.toArgb()))
                .put("native_surface_high_argb", argb(nativePalette.surfaceContainerHigh))
                .put("native_on_surface_argb", argb(nativePalette.onSurface))
                .put("launcher_background_argb", argb(local.getColor(R.color.launcher_background)))
                .put("launcher_main_argb", argb(local.getColor(R.color.launcher_main)))
                .put("typography", "Exact release ReverbTypography, same as Home/onboarding probes")
            trace.put(JSONObject().put("event", "scene_configuration").put("values", sceneConfiguration))
            check(resolvedNight == expectedNight) { "Own configured About scene did not retain the requested night qualifier" }
            check(nativePalette.surfaceContainerHigh == colors.surfaceContainerHigh.toArgb() &&
                nativePalette.onSurface == colors.onSurface.toArgb()) { "About supplied palettes disagree before rendering" }
            activity.fixtureRoot.removeAllViews()
            activity.fixtureRoot.setBackgroundColor(colors.background.toArgb())
            val anchor = if (compose) ComposeView(local) else View(local)
            anchor.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val configuredHost = FrameLayout(local)
            if (Build.VERSION.SDK_INT >= 29) configuredHost.isForceDarkAllowed = false
            activity.fixtureRoot.addView(configuredHost, FrameLayout.LayoutParams(-1, -1))
            Choreographer.getInstance().postFrameCallback(frameCallback)
            mark("open")
            if (compose) {
                composeHost = anchor as ComposeView
                // Same per-configured-board cache isolation as NativeBrandParityProbe. Dialog
                // inherits this parent composition context, rather than the Activity cache.
                val boardContext = ComposeViewContext(configuredHost)
                composeViewContext = boardContext
                anchor.setContent {
                    CompositionLocalProvider(LocalContext provides local, LocalConfiguration provides configuration,
                        LocalResources provides local.resources, LocalDensity provides density,
                        LocalLayoutDirection provides LayoutDirection.Ltr) {
                        MaterialTheme(colorScheme = colors, typography = typography) {
                            // The original source is invoked unchanged, including its actual Dialog,
                            // animation, predictive dispatcher, Insets and version-name lookup.
                            if (visible.value) AboutDialog(::ownerDismissed)
                        }
                    }
                }
                anchor.createComposition(boardContext)
                configuredHost.addView(anchor, FrameLayout.LayoutParams(-1, -1))
            } else {
                configuredHost.addView(anchor, FrameLayout.LayoutParams(-1, -1))
                nativeDialog = ReverbAboutDialog(anchor, AboutContentAppearance(
                    ReverbPalette.resolve(local, case.dark), typography.headlineSmall.nativeParityTextStyle(),
                    typography.titleSmall.nativeParityTextStyle(), typography.bodyMedium.nativeParityTextStyle(),
                    typography.bodySmall.nativeParityTextStyle(), density.nativeParityDensity(),
                    createFontFamilyResolver(local),
                ), ::ownerDismissed).also { dialog -> dialog.show(); window = checkNotNull(dialog.window) }
            }
        }
        // Wait only for the exact newly-created own dialog. Foreign focus is never reclaimed.
        repeat(30) {
            val found = nativeParityOnMain(instrumentation) {
                if (::window.isInitialized) true else {
                    val candidates = WindowInspector.getGlobalWindowViews().filter { it !in before }
                        .flatMap(::aboutDescendants).filterIsInstance<DialogWindowProvider>()
                        .map { it.window }.distinct()
                    check(candidates.size <= 1) { "Ambiguous newly created QA dialog windows" }
                    candidates.singleOrNull()?.let { window = it }
                    ::window.isInitialized
                }
            }
            if (found) {
                awaitNativeParityDraw(instrumentation, window.decorView)
                awaitOpeningWindowFocus()
                guard()
                if (case.short) {
                    nativeParityOnMain(instrumentation) {
                        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            (240f * window.context.resources.displayMetrics.density).roundToInt())
                    }
                    awaitNativeParityDraw(instrumentation, window.decorView)
                    guard()
                }
                return
            }
            awaitAboutFrame(instrumentation)
        }
        error("Released About did not create its own Window within30 frames")
    }

    fun guard() = nativeParityOnMain(instrumentation) { requireOwnWindowOnMain() }
    private fun awaitOpeningWindowFocus() {
        // First draw and native window-focus delivery are independent platform callbacks.
        // Observe only this newly-created own Window; never request or reclaim focus.
        repeat(30) {
            val ready = nativeParityOnMain(instrumentation) {
                check(!closed && !activity.isFinishing && activity.fixtureRoot.isAttachedToWindow &&
                    activity.fixtureRoot.isShown && activity.fixtureRoot.display?.displayId == Display.DEFAULT_DISPLAY)
                val decor = window.decorView
                check(decor.context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
                val registered = decor in WindowInspector.getGlobalWindowViews()
                trace.put(JSONObject().put("event", "opening_window_readiness").put("renderer", renderer)
                    .put("frame", frameNumber).put("attached", decor.isAttachedToWindow)
                    .put("shown", decor.isShown).put("focused", decor.hasWindowFocus())
                    .put("display", decor.display?.displayId).put("registered", registered))
                registered && decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus() &&
                    decor.display?.displayId == Display.DEFAULT_DISPLAY
            }
            if (ready) return
            awaitAboutFrame(instrumentation)
        }
        error("New own About Window did not become attached/shown/focused on physical0 within30 frames; no focus requested")
    }
    private fun requireOwnWindowOnMain() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val decor = window.decorView
        check(!closed && !activity.isFinishing && activity.fixtureRoot.isAttachedToWindow)
        check(decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus() &&
            decor.display?.displayId == Display.DEFAULT_DISPLAY) {
            "Exact About QA Window is no longer focused/visible on physical0; " +
                "attached=${decor.isAttachedToWindow}, shown=${decor.isShown}, focused=${decor.hasWindowFocus()}, " +
                "display=${decor.display?.displayId}, renderer=$renderer; stop without reclaiming focus"
        }
        check(decor.context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
        check(decor in WindowInspector.getGlobalWindowViews())
    }

    fun mark(event: String) {
        trace.put(JSONObject().put("event", event).put("frame", frameNumber)
            .put("uptime_ms", SystemClock.uptimeMillis()))
    }
    fun awaitFrames(count: Int) {
        repeat(count) {
            guard()
            awaitAboutFrame(instrumentation)
            guard()
        }
    }
    fun awaitSettled(millis: Long) {
        guard()
        val latch = CountDownLatch(1)
        nativeParityOnMain(instrumentation) { Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, millis) }
        check(latch.await(millis + 5_000L, TimeUnit.MILLISECONDS)) { "About settle callback timed out" }
        guard()
        awaitNativeParityDraw(instrumentation, window.decorView)
        awaitFrames(2)
    }
    fun awaitDismissed() {
        check(dismissed.await(6, TimeUnit.SECONDS)) { "About owner dismissal not delivered" }
        // Dialog detachment and Activity window-focus delivery are separate platform callbacks.
        // Observe both before the unchanged input guard; never request focus or dismiss foreign UI.
        var observedRemoval = false
        repeat(30) {
            val ready = nativeParityOnMain(instrumentation) {
                check(!closed && !activity.isFinishing && activity.fixtureRoot.isAttachedToWindow &&
                    activity.fixtureRoot.isShown &&
                    activity.fixtureRoot.display?.displayId == Display.DEFAULT_DISPLAY) {
                    "Own QA Activity disappeared while observing About dismissal; stop without reclaiming focus"
                }
                val removed = !window.decorView.isAttachedToWindow
                if (removed && !observedRemoval) {
                    observedRemoval = true
                    mark("own_dialog_removed")
                }
                removed && activity.hasWindowFocus()
            }
            if (ready) {
                requireNativeParityHost(instrumentation, activity)
                mark("own_activity_focus_returned")
                return
            }
            awaitAboutFrame(instrumentation)
        }
        error(if (observedRemoval)
            "Own About Window detached but QA Activity focus did not return within30 frames; no focus requested"
        else "Dismiss callback was delivered but the own About Window remained attached")
    }

    fun windowSnapshot() = nativeParityOnMain(instrumentation) {
        val decor = window.decorView
        val location = IntArray(2).also(decor::getLocationOnScreen)
        val a = window.attributes
        JSONObject().put("x", location[0]).put("y", location[1]).put("width", decor.width).put("height", decor.height)
            .put("gravity", a.gravity).put("flags", a.flags).put("soft_input_mode", a.softInputMode)
            .put("requested_width", a.width).put("requested_height", a.height)
            .put("in_touch_mode", decor.isInTouchMode)
            .put("focused_native_view_class", decor.findFocus()?.javaClass?.name)
            .put("focused_native_view_identity", decor.findFocus()?.let(System::identityHashCode))
            .put("focused_native_view_description", decor.findFocus()?.contentDescription?.toString())
            .put("status_inset_top", decor.rootWindowInsets?.systemWindowInsetTop ?: -1)
            .put("navigation_inset_bottom", decor.rootWindowInsets?.systemWindowInsetBottom ?: -1)
            .put("scene_configuration", JSONObject(sceneConfiguration.toString()))
            .put("window_context_night", window.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .put("decor_context_night", decor.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .put("window_theme_is_light", aboutThemeLightFlag(window.context))
            .put("decor_background", aboutBackgroundEvidence(decor.background))
    }
    fun semanticSnapshot() = nativeParityOnMain(instrumentation) {
        if (compose) JSONArray(composeRoot().semanticsOwner.getAllSemanticsNodes(false).map { node ->
            JSONObject().put("description", node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
                .put("text", node.config.getOrNull(SemanticsProperties.Text)?.joinToString())
                .put("click", node.config.getOrNull(SemanticsActions.OnClick) != null)
                .put("focused", node.config.getOrNull(SemanticsProperties.Focused) ?: JSONObject.NULL)
                .put("bounds", node.boundsInWindow.toString())
        }) else JSONArray(aboutDescendants(window.decorView).map { view ->
            val info = view.createAccessibilityNodeInfo()
                ?: return@map JSONObject().put("node", "absent")
            try {
                val bounds = Rect().also(info::getBoundsInScreen)
                JSONObject().put("class", info.className?.toString()).put("text", info.text?.toString())
                    .put("description", info.contentDescription?.toString()).put("visible", info.isVisibleToUser)
                    .put("important", info.isImportantForAccessibility).put("click", info.isClickable)
                    .put("focused", info.isFocused).put("accessibility_focused", info.isAccessibilityFocused)
                    .put("view_focused", view.isFocused).put("view_hovered", view.isHovered).put("view_pressed", view.isPressed)
                    .put("bounds", bounds.toShortString())
            } finally { @Suppress("DEPRECATION") info.recycle() }
        })
    }
    private fun composeRoot(): ViewRootForTest =
        aboutDescendants(window.decorView).filterIsInstance<ViewRootForTest>().single()
    private fun closeTarget(): Rect {
        val label = activity.getString(R.string.close)
        return if (compose) {
            val node = composeRoot().semanticsOwner.getAllSemanticsNodes(true).single {
                label in (it.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            val b = node.boundsInWindow
            Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
        } else {
            val view = nativeCloseView()
            val xy = IntArray(2).also(view::getLocationInWindow)
            Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
    }
    private fun nativeCloseView(): View {
        val label = activity.getString(R.string.close)
        val described = aboutDescendants(window.decorView).filter { view ->
            val node = view.createAccessibilityNodeInfo() ?: return@filter false
            try { node.contentDescription?.toString() == label }
            finally { @Suppress("DEPRECATION") node.recycle() }
        }.single()
        var current = described
        while (!current.isClickable) current = current.parent as? View ?: error("Close glyph has no clickable owner")
        return current
    }
    fun pointerClose() {
        guard()
        nativeParityOnMain(instrumentation) {
            requireOwnWindowOnMain()
            val target = closeTarget()
            check(!target.isEmpty)
            val now = SystemClock.uptimeMillis()
            mark("close_down")
            activeDownTime = now
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY(), 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(window.decorView.dispatchTouchEvent(down)) } finally { down.recycle() }
        }
        awaitFrames(1)
        guard()
        nativeParityOnMain(instrumentation) {
            requireOwnWindowOnMain()
            val target = closeTarget() // Fresh scene/bounds; never replay a stale location.
            val now = SystemClock.uptimeMillis()
            mark("close_up")
            val up = MotionEvent.obtain(activeDownTime, now, MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            try { window.decorView.dispatchTouchEvent(up) } finally { up.recycle() }
        }
    }
    fun keyboardClose(directory: File, sessionNonce: String?, onRequest: (JSONObject) -> Unit) {
        guard()
        awaitNativeParityKeyboardCommand(instrumentation, directory, sessionNonce, renderer,
            stage = "about-keyboard-mode", keyCodeName = "KEYCODE_TAB", guardedSnapshot = {
            nativeParityOnMain(instrumentation) {
                requireOwnWindowOnMain()
                val decor = window.decorView
                val bounds = closeTarget()
                check(!bounds.isEmpty)
                val description = activity.getString(R.string.close)
                val targetIdentity: String
                val targetEnabled: Boolean
                if (compose) {
                    val close = composeRoot().semanticsOwner.getAllSemanticsNodes(true).single {
                        description in (it.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) &&
                            it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    targetIdentity = "semantics:${close.id}"
                    targetEnabled = close.config.getOrNull(SemanticsProperties.Disabled) == null
                } else {
                    val close = nativeCloseView()
                    targetIdentity = "view:${System.identityHashCode(close)}"
                    targetEnabled = close.isEnabled && close.isShown
                }
                check(targetEnabled) { "Current About Close target is not enabled/visible" }
                JSONObject().put("display_id", checkNotNull(decor.display).displayId)
                    .put("window_title", window.attributes.title?.toString().orEmpty())
                    .put("decor_identity", System.identityHashCode(decor))
                    .put("target_bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    .put("target_identity", targetIdentity).put("scene_label", "AboutClose")
                    .put("observed_target", JSONObject()
                        .put("content_description", description).put("enabled", targetEnabled))
                    .put("in_touch_mode", decor.isInTouchMode)
            }
        }, onRequest = onRequest, validateObservedAfter = { observed ->
            check(observed.get("in_touch_mode").let { it is Boolean && !it }) {
                "Host TAB completed but own About window remains in Touch mode; focus assertion is not bypassed"
            }
        })
        guard()
        nativeParityOnMain(instrumentation) {
            requireOwnWindowOnMain()
            if (compose) {
                val label = activity.getString(R.string.close)
                val node = composeRoot().semanticsOwner.getAllSemanticsNodes(true).single {
                    label in (it.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                check(node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true)
            } else {
                val close = nativeCloseView()
                check(close.requestFocus()) {
                    "Native Close cannot obtain focus in the current input mode; no focusability/mode override is applied"
                }
            }
            mark("keyboard_enter")
            val time = SystemClock.uptimeMillis()
            check(window.decorView.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)))
            check(window.decorView.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)))
        }
    }
    private fun dispatcher() = checkNotNull(window.decorView.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher
    fun ordinaryBack() {
        guard()
        nativeParityOnMain(instrumentation) { requireOwnWindowOnMain(); mark("back_pressed"); dispatcher().onBackPressed() }
    }
    fun predictiveStart() {
        guard()
        nativeParityOnMain(instrumentation) {
            requireOwnWindowOnMain()
            mark("back_started"); dispatcher().dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        }
    }
    fun predictiveProgress(progress: Float) {
        guard()
        nativeParityOnMain(instrumentation) {
            requireOwnWindowOnMain()
            mark("back_progress_$progress")
            dispatcher().dispatchOnBackProgressed(BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT))
        }
    }
    fun predictiveCancel() {
        guard()
        nativeParityOnMain(instrumentation) { requireOwnWindowOnMain(); mark("back_cancelled"); dispatcher().dispatchOnBackCancelled() }
    }
    fun scrollOffset(): Float = nativeParityOnMain(instrumentation) {
        if (compose) composeRoot().semanticsOwner.getAllSemanticsNodes(false).single {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }.config[SemanticsProperties.VerticalScrollAxisRange].value()
        else aboutDescendants(window.decorView).filterIsInstance<NativeVerticalViewport>().single().state.value.toFloat()
    }
    fun scrollForward() {
        guard()
        nativeParityOnMain(instrumentation) {
            requireOwnWindowOnMain()
            mark("semantic_scroll_forward")
            if (compose) {
                val node = composeRoot().semanticsOwner.getAllSemanticsNodes(false).single {
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                }
                check(node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, node.boundsInWindow.height) == true)
            } else {
                val viewport = aboutDescendants(window.decorView).filterIsInstance<NativeVerticalViewport>().single()
                check(viewport.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            }
        }
    }

    fun capture(): Bitmap {
        guard()
        val rect = nativeParityOnMain(instrumentation) {
            val decor = window.decorView
            check(decor.isLaidOut && !decor.isLayoutRequested && decor.isHardwareAccelerated)
            Rect(0, 0, decor.width, decor.height)
        }
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = Int.MIN_VALUE
        var submitted = false
        var recycleLater = false
        try {
            nativeParityOnMain(instrumentation) {
                requireOwnWindowOnMain()
                PixelCopy.request(window, rect, bitmap, { code ->
                    status = code; copied.countDown()
                    if (recycleLater) bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
                submitted = true
            }
            check(copied.await(5, TimeUnit.SECONDS)) { "About PixelCopy timed out" }
            check(status == PixelCopy.SUCCESS) { "About PixelCopy status=$status" }
            guard()
            return bitmap
        } catch (error: Throwable) {
            nativeParityOnMain(instrumentation) {
                if (!submitted || copied.count == 0L) bitmap.recycle() else recycleLater = true
            }
            throw error
        }
    }
    override fun close() {
        nativeParityOnMain(instrumentation) {
            if (closed) return@nativeParityOnMain
            closed = true
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            visible.value = false
            nativeDialog?.dismiss()
            composeHost?.disposeComposition()
            activity.fixtureRoot.removeAllViews()
        }
    }
}

private fun aboutDescendants(view: View): List<View> = buildList {
    fun addTree(value: View) {
        add(value)
        if (value is ViewGroup) for (index in 0 until value.childCount) addTree(value.getChildAt(index))
    }
    addTree(view)
}
private fun awaitAboutFrame(instrumentation: Instrumentation) {
    val frame = CountDownLatch(1)
    nativeParityOnMain(instrumentation) { Choreographer.getInstance().postFrameCallback { frame.countDown() } }
    check(frame.await(5, TimeUnit.SECONDS)) { "About fixture frame callback timed out" }
}

/** Read-only source conditions used by Android16 ViewRootImpl.determineForceDarkType. */
private fun aboutThemeLightFlag(context: android.content.Context): Any {
    val id = context.resources.getIdentifier("isLightTheme", "attr", "android")
    if (id == 0) return JSONObject.NULL
    val value = android.util.TypedValue()
    return if (context.theme.resolveAttribute(id, value, true)) value.data != 0 else JSONObject.NULL
}
private fun aboutBackgroundEvidence(drawable: android.graphics.drawable.Drawable?): JSONObject {
    val evidence = JSONObject().put("class", drawable?.javaClass?.name ?: JSONObject.NULL)
    if (drawable is android.graphics.drawable.ColorDrawable)
        evidence.put("argb", drawable.color.toLong() and 0xffffffffL)
    if (drawable is android.graphics.drawable.InsetDrawable)
        evidence.put("inset_child", aboutBackgroundEvidence(drawable.drawable))
    return evidence
}
