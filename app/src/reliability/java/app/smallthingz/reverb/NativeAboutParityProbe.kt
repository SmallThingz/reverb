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
import androidx.compose.ui.text.font.createFontFamilyResolver
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
 * No production route, device input injection, AccessibilityService, browser launch, settings,
 * permissions, audio, recordings, or backend callback is used.
 */
internal fun verifyNativeAboutParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(Build.VERSION.SDK_INT >= 29) { "WindowInspector is public from API29; no hidden API fallback" }
    val directory = File(context.filesDir, "native-about-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val entries = JSONArray()
    val result = JSONObject()
        .put("oracle", "Unchanged released AboutDialog called directly; zero source substitutions")
        .put("comparison", "Exact ARGB; no masks/tolerance; short case constrains both own windows to240dp")
        .put("physical_input_injected", false)
        .put("motion_phase_qualified", false)
        .put("limits", "Own-window synthetic dispatch and read-only semantics; actual system predictive dispatch, TalkBack and stopped/resumed Activity remain separate")
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
                    current = AboutFixture.open(instrumentation, activity, case, compose)
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
                current = AboutFixture.open(instrumentation, activity, AboutCase(true), compose)
                val fixture = checkNotNull(current)
                fixture.awaitSettled(settledMillis)
                var exercised = true
                when (scenario) {
                    AboutInteraction.KEYBOARD_CLOSE -> fixture.keyboardClose()
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
        fun open(instrumentation: Instrumentation, host: NativeParityActivity, case: AboutCase, compose: Boolean): AboutFixture {
            requireNativeParityHost(instrumentation, host)
            val fixture = AboutFixture(instrumentation, host, case, compose)
            try {
                fixture.openWindow()
                return fixture
            } catch (error: Throwable) {
                try { fixture.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }

    private fun openWindow() {
        lateinit var before: Set<View>
        nativeParityOnMain(instrumentation) {
            check(!activity.isFinishing && activity.hasWindowFocus() && activity.fixtureRoot.isShown)
            check(activity.fixtureRoot.display?.displayId == Display.DEFAULT_DISPLAY)
            before = WindowInspector.getGlobalWindowViews().toSet()
            val configuration = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            // Activity remains the base Context, preserving its ordinary Window token/lifecycle.
            val local = ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(configuration) }
            val density = Density(local.resources.displayMetrics.density, configuration.fontScale)
            val typography = Typography()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (case.dark) dynamicDarkColorScheme(local) else dynamicLightColorScheme(local)
            } else if (case.dark) darkColorScheme() else lightColorScheme()
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
                    ReverbPalette.resolve(local, case.dark), typography.headlineSmall,
                    typography.titleSmall, typography.bodyMedium, typography.bodySmall, density,
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
    private fun requireOwnWindowOnMain() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val decor = window.decorView
        check(!closed && !activity.isFinishing && activity.fixtureRoot.isAttachedToWindow)
        check(decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus() &&
            decor.display?.displayId == Display.DEFAULT_DISPLAY) {
            "Exact About QA Window is no longer focused/visible on physical0; stop without reclaiming focus"
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
        // Await Compose owner removal before checking the Activity; never force/dismiss foreign UI.
        repeat(30) {
            val removed = nativeParityOnMain(instrumentation) { !window.decorView.isAttachedToWindow }
            if (removed) { requireNativeParityHost(instrumentation, activity); return }
            awaitAboutFrame(instrumentation)
        }
        error("Dismiss callback was delivered but the own About Window remained attached")
    }

    fun windowSnapshot() = nativeParityOnMain(instrumentation) {
        val decor = window.decorView
        val location = IntArray(2).also(decor::getLocationOnScreen)
        val a = window.attributes
        JSONObject().put("x", location[0]).put("y", location[1]).put("width", decor.width).put("height", decor.height)
            .put("gravity", a.gravity).put("flags", a.flags).put("soft_input_mode", a.softInputMode)
            .put("requested_width", a.width).put("requested_height", a.height)
            .put("status_inset_top", decor.rootWindowInsets?.systemWindowInsetTop ?: -1)
            .put("navigation_inset_bottom", decor.rootWindowInsets?.systemWindowInsetBottom ?: -1)
    }
    fun semanticSnapshot() = nativeParityOnMain(instrumentation) {
        if (compose) JSONArray(composeRoot().semanticsOwner.getAllSemanticsNodes(false).map { node ->
            JSONObject().put("description", node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
                .put("text", node.config.getOrNull(SemanticsProperties.Text)?.joinToString())
                .put("click", node.config.getOrNull(SemanticsActions.OnClick) != null)
                .put("bounds", node.boundsInWindow.toString())
        }) else JSONArray(aboutDescendants(window.decorView).map { view ->
            val info = view.createAccessibilityNodeInfo()
                ?: return@map JSONObject().put("node", "absent")
            try {
                val bounds = Rect().also(info::getBoundsInScreen)
                JSONObject().put("class", info.className?.toString()).put("text", info.text?.toString())
                    .put("description", info.contentDescription?.toString()).put("visible", info.isVisibleToUser)
                    .put("important", info.isImportantForAccessibility).put("click", info.isClickable)
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
    fun keyboardClose() {
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
