package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ComposeViewContext
import androidx.compose.ui.platform.ExperimentalComposeViewContextApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.nativeui.ReverbAppTopBarActions
import app.smallthingz.reverb.nativeui.ReverbHomeActions
import app.smallthingz.reverb.nativeui.ReverbHomeBodyState
import app.smallthingz.reverb.nativeui.ReverbHomeInsets
import app.smallthingz.reverb.nativeui.ReverbHomeScreenView
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject

private data class HomeCase(
    val name: String, val selected: BufferSlot = BufferSlot.ONE_SHOT,
    val active: BufferSlot? = null, val listening: Boolean = false,
    val oneShot: BufferMetrics = BufferMetrics(0f, 0),
    val looping: BufferMetrics = BufferMetrics(0f, 0),
    val oneShotEnabled: Boolean = true, val loopingEnabled: Boolean = true,
    val full: Boolean = false, val retention: RetentionMode = RetentionMode.TIME,
    val hasIncidents: Boolean = false,
)
private fun homeCases() = listOf(
    HomeCase("blank"),
    HomeCase("paused-history", oneShot = BufferMetrics(65.25f, 6264000),
        looping = BufferMetrics(120.5f, 11568000)),
    HomeCase("recording-one-shot", active = BufferSlot.ONE_SHOT, listening = true,
        oneShot = BufferMetrics(65.25f, 6264000)),
    HomeCase("recording-loop", selected = BufferSlot.LOOPING, active = BufferSlot.LOOPING,
        listening = true, looping = BufferMetrics(125.5f, 12048000), retention = RetentionMode.SIZE),
    HomeCase("filled", oneShot = BufferMetrics(300f, 28800000), full = true, hasIncidents = true),
    HomeCase("disabled", oneShotEnabled = false),
)

/** Full normal Home only; synthetic recorder state never owns a service, capture or durable write. */
internal fun verifyNativeHomeParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-home-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "5acd675302d79e1aad10f5ec603fce7d631b164834c7cd9e0773bfdc5ab5b5c1")
        .put("comparison", "Exact full-content ARGB and ABAB repeatability; no masking/tolerance")
        .put("scope", "Normal Home top bar/noise/body; explicit zero local system insets; full physical fixture viewport")
        .put("limits", "visualizerVisible=false preserves initial blob pixels only; no live audio phase, permission/range/save feedback, navigation overlays or system-inset dispatch qualification")
        .put("cases", cases).put("passed", false)
    var host: NativeParityActivity? = null
    var board: HomeParityBoard? = null
    var primary: Throwable? = null
    var completed = 0
    var mismatches = 0
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        for (dark in listOf(false, true)) for (case in homeCases()) {
            requireNativeParityHost(instrumentation, activity)
            val current = nativeParityOnMain(instrumentation) { HomeParityBoard(activity, case, dark) }
            board = current
            val entry = JSONObject().put("name", case.name + if (dark) "-dark" else "-light")
                .put("dark", dark).put("rtl", dark).put("passed", false)
                .put("density_dpi", context.resources.displayMetrics.densityDpi)
                .put("font_scale", context.resources.configuration.fontScale.toDouble())
            cases.put(entry)
            val images = mutableListOf<Bitmap>()
            val geometry = mutableListOf<JSONObject>()
            var caseFailure: Throwable? = null
            try {
                repeat(4) { pass ->
                    images += captureNativeParityContent(instrumentation, activity) {
                        current.show(compose = pass % 2 == 0)
                    }
                    val label = listOf("compose", "native", "compose-repeat", "native-repeat")[pass]
                    saveNativeParityBitmap(directory, entry.getString("name") + "-" + label + ".png", images.last())
                    geometry += nativeParityOnMain(instrumentation) { current.geometry(pass % 2 == 0) }
                }
                val difference = compareNativeParityPixels(images[0], images[1])
                val sourceStable = compareNativeParityPixels(images[0], images[2])
                val nativeStable = compareNativeParityPixels(images[1], images[3])
                val equalGeometry = geometry.all { it.toString() == geometry.first().toString() }
                val passed = difference.getLong("different_pixels") == 0L &&
                    sourceStable.getLong("different_pixels") == 0L &&
                    nativeStable.getLong("different_pixels") == 0L && equalGeometry
                entry.put("difference", difference).put("reference_stability", sourceStable)
                    .put("native_stability", nativeStable).put("geometry", JSONArray(geometry))
                    .put("geometry_equal", equalGeometry).put("passed", passed)
                completed++
                if (!passed) mismatches++
                report.append(if (passed) "PASS " else "FAIL ").append("native_home_parity ")
                    .append(entry.getString("name")).append(" differing_pixels=")
                    .append(difference.getLong("different_pixels")).append('\n')
            } catch (failure: Throwable) {
                caseFailure = failure
                entry.put("error", android.util.Log.getStackTraceString(failure))
                throw failure
            } finally {
                images.forEach(Bitmap::recycle)
                var cleanupFailure: Throwable? = null
                try { nativeParityOnMain(instrumentation) { current.close(); board = null } }
                catch (failure: Throwable) {
                    cleanupFailure = failure
                    caseFailure?.addSuppressed(failure)
                    entry.put("cleanup_error", android.util.Log.getStackTraceString(failure))
                }
                writeHomeEvidence(directory, result, caseFailure ?: cleanupFailure)
                if (caseFailure == null) cleanupFailure?.let { throw it }
            }
        }
        check(completed == 12 && mismatches == 0) { "Home mismatch: $mismatches/$completed; ${directory.name}" }
    } catch (failure: Throwable) {
        primary = failure
        result.put("terminal_error", android.util.Log.getStackTraceString(failure))
        throw failure
    } finally {
        var cleanup: Throwable? = null
        try { nativeParityOnMain(instrumentation) { try { board?.close() } finally { host?.finish() } } }
        catch (failure: Throwable) {
            cleanup = failure
            primary?.addSuppressed(failure)
            result.put("cleanup_error", android.util.Log.getStackTraceString(failure))
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", primary == null && cleanup == null && completed == 12 && mismatches == 0)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeHomeEvidence(directory, result, primary ?: cleanup)
        if (primary == null) cleanup?.let { throw it }
    }
}

@OptIn(ExperimentalComposeViewContextApi::class)
private class HomeParityBoard(
    private val host: NativeParityActivity, private val case: HomeCase, dark: Boolean,
) : AutoCloseable {
    private val configuration = Configuration(host.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    private val context = host.createConfigurationContext(configuration)
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    private val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
    private val referenceHost = FrameLayout(context)
    private val reference = ComposeView(context)
    private val composeContext: ComposeViewContext
    private var referenceScope: CoroutineScope? = null
    private var nativeScope: CoroutineScope? = null
    private var native: ReverbHomeScreenView? = null
    private var referenceSelection by mutableStateOf(case.selected)
    private var nativeSelection = case.selected
    private val referenceOne = AudioBlobController()
    private val referenceLoop = AudioBlobController()
    private val nativeOne = AudioBlobController()
    private val nativeLoop = AudioBlobController()
    val referenceEvents = JSONArray()
    val nativeEvents = JSONArray()
    private var completion: CountDownLatch? = null
    private val insets = ReverbHomeInsets(0, 0, 0, 0)
    private val environment = nativeParityEnvironment(context, density,
        ReverbPalette.resolve(context, dark))
    private val nativeActions = actions(nativeEvents) { next ->
        nativeSelection = next
        native?.bind(nativeState(), next, nativeActionsForRebind(), navigation(nativeEvents), case.hasIncidents, insets)
        completion?.countDown()
    }
    private fun nativeActionsForRebind(): ReverbHomeActions = nativeActions
    private fun event(events: JSONArray, action: String) {
        events.put(JSONObject().put("action", action).put("uptime_ms", SystemClock.uptimeMillis()))
    }
    private fun actions(events: JSONArray, selected: (BufferSlot) -> Unit) = ReverbHomeActions(
        selectBuffer = { event(events, "select:" + it.name); selected(it) },
        activateBuffer = { event(events, "activate:" + it.name) },
        openBufferSettings = { event(events, "buffer-settings:" + it.name) },
        exportFull = { event(events, "export-full:" + it.name) },
        exportCustom = { event(events, "export-custom:" + it.name) },
        clearBuffer = { event(events, "clear:" + it.name) },
        openLibrary = { event(events, "library") },
    )
    private fun navigation(events: JSONArray) = ReverbAppTopBarActions(
        brand = { event(events, "brand") }, incidents = { event(events, "incidents") },
        settings = { event(events, "settings") }, back = null,
    )
    private fun nativeState() = ReverbHomeBodyState(
        case.active, case.oneShot, case.looping, case.oneShotEnabled, case.full, case.loopingEnabled,
        case.retention, case.listening, false, true, true, false, nativeOne, nativeLoop, false,
    )

    init {
        host.fixtureRoot.removeAllViews()
        host.fixtureRoot.setBackgroundColor(colors.surface.toArgb())
        referenceHost.layoutDirection = if (dark) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        reference.layoutDirection = referenceHost.layoutDirection
        if (Build.VERSION.SDK_INT >= 29) referenceHost.isForceDarkAllowed = false
        host.fixtureRoot.addView(referenceHost, FrameLayout.LayoutParams(-1, -1))
        composeContext = ComposeViewContext(referenceHost)
        reference.setContent {
            val scope = rememberCoroutineScope()
            SideEffect { referenceScope = scope }
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides configuration,
                LocalResources provides context.resources, LocalDensity provides density,
                LocalLayoutDirection provides if (dark) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme(colorScheme = colors, typography = typography) {
                    val noise = rememberAppNoiseBrush()
                    Scaffold(
                        modifier = Modifier.fillMaxSize().background(colors.surface).appNoise(noise),
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            AppTopBar(
                                onBrandClick = { event(referenceEvents, "brand") },
                                onIncidentsClick = { event(referenceEvents, "incidents") },
                                onSettingsClick = { event(referenceEvents, "settings") },
                                hasIncidents = case.hasIncidents, applyStatusBarPadding = false,
                            )
                        },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            ReleasedHomeMainCaptureContent(
                                selectedBuffer = referenceSelection, activeBuffer = case.active,
                                oneShotMetrics = case.oneShot, loopingMetrics = case.looping,
                                oneShotEnabled = case.oneShotEnabled, oneShotFull = case.full,
                                loopingEnabled = case.loopingEnabled, retentionMode = case.retention,
                                isListening = case.listening, isSaving = false, service = null,
                                fixtureServiceConnected = true, serviceStateHydrated = true,
                                isPreparingRange = false, oneShotBlobController = referenceOne,
                                loopingBlobController = referenceLoop, oneShotBlobActivity = 0f,
                                loopingBlobActivity = 0f, rangeSnapshot = null, rangeSnapshotBuffer = null,
                                rangeMaxExportDurationSeconds = 0.0, rangeBackProgress = 0f,
                                onCancelRangeExport = { error("Unreachable range branch") },
                                onSubmitRangeExport = { _, _ -> error("Unreachable range branch") },
                                onListenToggle = { event(referenceEvents, "activate:" + it.name) },
                                onClearBuffer = { event(referenceEvents, "clear:" + it.name) },
                                onExportFull = { event(referenceEvents, "export-full:" + it.name) },
                                onExportCustom = { event(referenceEvents, "export-custom:" + it.name) },
                                onSelectBuffer = {
                                    event(referenceEvents, "select:" + it.name)
                                    referenceSelection = it
                                    completion?.countDown()
                                },
                                onOpenBufferSettings = { event(referenceEvents, "buffer-settings:" + it.name) },
                                visualizerVisible = false, onOpenLibrary = { event(referenceEvents, "library") },
                            )
                        }
                    }
                }
            }
        }
        reference.createComposition(composeContext)
        referenceHost.addView(reference, FrameLayout.LayoutParams(-1, -1))
    }

    fun show(compose: Boolean): View {
        check(host.hasWindowFocus())
        if (!compose && native == null) {
            val inherited = checkNotNull(referenceScope) { "Reference frame scope was not applied" }
            val scope = CoroutineScope((inherited.coroutineContext +
                SupervisorJob(inherited.coroutineContext[Job])).withNativeParityClock())
            nativeScope = scope
            native = ReverbHomeScreenView(context, environment, nativeState(), nativeSelection,
                nativeActions, navigation(nativeEvents), case.hasIncidents, insets,
                host.fixtureRoot, scope, { _, _, _ -> }).also {
                it.layoutDirection = referenceHost.layoutDirection
                host.fixtureRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
        }
        referenceHost.visibility = if (compose) View.VISIBLE else View.GONE
        native?.visibility = if (compose) View.GONE else View.VISIBLE
        return if (compose) reference else checkNotNull(native)
    }

    fun geometry(compose: Boolean): JSONObject {
        val root = if (compose) reference else checkNotNull(native)
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val blobs = mutableListOf<View>()
        fun visit(view: View) {
            if (view is AudioBlobView) blobs += view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(root)
        check(blobs.size == 1) { "Normal Home must own one native blob renderer" }
        val blob = blobs.single()
        val location = IntArray(2).also(blob::getLocationInWindow)
        return JSONObject().put("width", root.width).put("height", root.height)
            .put("blob", JSONArray(listOf(location[0] - rootLocation[0], location[1] - rootLocation[1],
                blob.width, blob.height)))
    }

    fun setCompletion(latch: CountDownLatch?) { completion = latch }
    fun selected(compose: Boolean) = if (compose) referenceSelection else nativeSelection
    fun events(compose: Boolean) = if (compose) referenceEvents else nativeEvents
    fun content(compose: Boolean): View = if (compose) reference else checkNotNull(native)
    fun dp(value: Float) = value * density.density

    override fun close() {
        completion = null
        try { native?.close() } finally {
            nativeScope?.cancel()
            reference.disposeComposition()
            host.fixtureRoot.removeAllViews()
        }
    }
}

private fun writeHomeEvidence(directory: File, result: JSONObject, primary: Throwable?) {
    try { File(directory, "result.json").writeText(result.toString(2)) }
    catch (failure: Throwable) {
        if (primary == null) throw failure
        primary.addSuppressed(failure)
    }
}


private enum class HomeGesture(val expectedAction: String?) {
    TAB("select:LOOPING"),
    EXPANDED_TAB("select:LOOPING"),
    DIRECT_ACTION_BEATS_EXPANDED_TAB("clear:ONE_SHOT"),
    SWIPE_COMMIT("select:LOOPING"),
    SWIPE_CANCEL(null),
}

/**
 * Actions and final pixels have exact assertions. Intermediate captures are observed evidence,
 * not timestamp-aligned frames; no assertion silently drops them or claims continuous smoothness.
 */
internal fun verifyNativeHomeInputParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-home-input-" + System.currentTimeMillis())
    check(directory.mkdir())
    val entries = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("scope", "Synthetic touch dispatch to the actual own-view roots; no physical/system injected input")
        .put("motion_limit", "Sequential reference/native captures have independent clocks; request/callback wall times are not presentation timestamps. Intermediate images are retained, not used to claim frame parity.")
        .put("cases", entries).put("passed", false)
    var host: NativeParityActivity? = null
    var active: HomeParityBoard? = null
    var primary: Throwable? = null
    var completed = 0
    var mismatches = 0
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val scale = android.animation.ValueAnimator.getDurationScale()
        check(scale.isFinite() && scale >= 0f) { "Unknown current animator duration scale" }
        result.put("observed_animator_scale", scale.toDouble())
        // A finite QA receipt must not modify a user's scale to make the interval short.
        if (scale > 10f) {
            result.put("unqualified", "Current animator scale exceeds this finite 5-second input observation interval")
            error("Home input unqualified at current animator scale; static fixture is independent")
        }
        val scenarios = HomeGesture.entries.map { it to false } + (HomeGesture.SWIPE_COMMIT to true)
        for ((gesture, dark) in scenarios) {
            val name = gesture.name.lowercase() + if (dark) "-dark-rtl" else "-light-ltr"
            val entry = JSONObject().put("name", name).put("routes", JSONArray()).put("passed", false)
            entries.put(entry)
            val finalImages = mutableListOf<Bitmap>()
            val actionLists = mutableListOf<List<String>>()
            var caseFailure: Throwable? = null
            try {
                for (compose in listOf(true, false)) {
                    val route = if (compose) "compose" else "native"
                    val board = nativeParityOnMain(instrumentation) {
                        HomeParityBoard(activity, homeCases()[1], dark).also { active = it }
                    }
                    // Compose creates the shared, correctly configured window frame scope even
                    // for a native-only route. No reference operation is dispatched on that route.
                    captureNativeParityContent(instrumentation, activity) { board.show(true) }.recycle()
                    val initial = captureNativeParityContent(instrumentation, activity) { board.show(compose) }
                    try { saveNativeParityBitmap(directory, "$name-$route-initial.png", initial) }
                    finally { initial.recycle() }
                    val events = JSONArray()
                    val routeResult = JSONObject().put("route", route).put("input", events)
                    entry.getJSONArray("routes").put(routeResult)
                    val downTime = SystemClock.uptimeMillis()
                    fun send(action: Int, x: Float, y: Float) {
                        requireNativeParityHost(instrumentation, activity)
                        nativeParityOnMain(instrumentation) {
                            val view = board.content(compose)
                            check(view.isAttachedToWindow && view.isShown && view.hasWindowFocus())
                            val pointer = MotionEvent.PointerProperties().apply {
                                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                            }
                            val coordinates = MotionEvent.PointerCoords().apply {
                                this.x = x; this.y = y; pressure = 1f; size = 1f
                            }
                            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                                action, 1, arrayOf(pointer), arrayOf(coordinates), 0, 0,
                                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                            try {
                                events.put(JSONObject().put("action", action).put("x", x.toDouble())
                                    .put("y", y.toDouble()).put("event_ms", event.eventTime)
                                    .put("handled", view.dispatchTouchEvent(event)))
                            } finally { event.recycle() }
                        }
                    }
                    val view = board.content(compose)
                    val width = nativeParityOnMain(instrumentation) { view.width.toFloat() }
                    val height = nativeParityOnMain(instrumentation) { view.height.toFloat() }
                    val clusterTop = height - board.dp(18f).roundToInt() - board.dp(112f).roundToInt()
                    val clusterLeft = (width - board.dp(256f).roundToInt()) * 0.5f
                    // Coordinates come from released cluster/Row constants, not a tuned screenshot.
                    val loopCenter = if (dark) width * 0.5f - board.dp(59f)
                        else width * 0.5f + board.dp(59f)
                    when (gesture) {
                        HomeGesture.TAB, HomeGesture.EXPANDED_TAB,
                        HomeGesture.DIRECT_ACTION_BEATS_EXPANDED_TAB -> {
                            val x = if (gesture == HomeGesture.DIRECT_ACTION_BEATS_EXPANDED_TAB)
                                clusterLeft + board.dp(159f) else loopCenter
                            val y = clusterTop + board.dp(when (gesture) {
                                HomeGesture.TAB -> 27f
                                HomeGesture.EXPANDED_TAB -> 4f
                                else -> 51f
                            })
                            send(MotionEvent.ACTION_DOWN, x, y)
                            awaitNativeParityDraw(instrumentation, view)
                            send(MotionEvent.ACTION_UP, x, y)
                        }
                        HomeGesture.SWIPE_COMMIT, HomeGesture.SWIPE_CANCEL -> {
                            val y = (board.dp(66f) + clusterTop) * 0.5f
                            val bodyWidth = width - 2 * board.dp(18f).roundToInt()
                            val start = board.dp(18f) + bodyWidth * 0.80f
                            send(MotionEvent.ACTION_DOWN, start, y)
                            awaitNativeParityDraw(instrumentation, view)
                            // Separate slop acquisition and committed delta; source commits at16%.
                            send(MotionEvent.ACTION_MOVE, start - bodyWidth * 0.08f, y)
                            awaitNativeParityDraw(instrumentation, view)
                            send(MotionEvent.ACTION_MOVE, start - bodyWidth * 0.42f, y)
                            awaitNativeParityDraw(instrumentation, view)
                            send(if (gesture == HomeGesture.SWIPE_CANCEL) MotionEvent.ACTION_CANCEL
                                else MotionEvent.ACTION_UP, start - bodyWidth * 0.42f, y)
                        }
                    }
                    val samples = JSONArray()
                    routeResult.put("observed_samples", samples)
                    repeat(3) { index ->
                        val requested = System.nanoTime()
                        val image = captureNativeParityContent(instrumentation, activity) { board.show(compose) }
                        val captured = System.nanoTime()
                        try { saveNativeParityBitmap(directory, "$name-$route-observed-$index.png", image) }
                        finally { image.recycle() }
                        samples.put(JSONObject().put("request_ns", requested).put("complete_ns", captured))
                    }
                    // Bounded settle interval derived from released260ms flip +160ms selector tween.
                    // It does not prove the animation completed at that time: exact final actions,
                    // repeated final raster stability and A/B equality below must still agree.
                    val deadline = downTime + (420f * scale).toLong() + 350L
                    val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                    if (remaining > 0) CountDownLatch(1).await(remaining, TimeUnit.MILLISECONDS)
                    val final = captureNativeParityContent(instrumentation, activity) { board.show(compose) }
                    finalImages += final
                    val repeat = captureNativeParityContent(instrumentation, activity) { board.show(compose) }
                    try {
                        saveNativeParityBitmap(directory, "$name-$route-final.png", final)
                        saveNativeParityBitmap(directory, "$name-$route-final-repeat.png", repeat)
                        val stable = compareNativeParityPixels(final, repeat)
                        routeResult.put("final_stability", stable)
                        check(stable.getLong("different_pixels") == 0L) { "Home final state has not settled" }
                    } finally { repeat.recycle() }
                    val actions = nativeParityOnMain(instrumentation) {
                        val raw = board.events(compose)
                        routeResult.put("callbacks", raw).put("selection", board.selected(compose).name)
                        (0 until raw.length()).map { raw.getJSONObject(it).getString("action") }
                    }
                    actionLists += actions
                    nativeParityOnMain(instrumentation) { board.close(); active = null }
                }
                val difference = compareNativeParityPixels(finalImages[0], finalImages[1])
                val expected = listOfNotNull(gesture.expectedAction)
                val actionsEqual = actionLists.all { it == expected }
                val passed = actionsEqual && difference.getLong("different_pixels") == 0L
                entry.put("expected_actions", JSONArray(expected)).put("actions_equal", actionsEqual)
                    .put("final_difference", difference).put("passed", passed)
                completed++
                if (!passed) mismatches++
                report.append(if (passed) "PASS " else "FAIL ").append("native_home_input ")
                    .append(name).append('\n')
            } catch (failure: Throwable) {
                caseFailure = failure
                entry.put("error", android.util.Log.getStackTraceString(failure))
                throw failure
            } finally {
                finalImages.forEach(Bitmap::recycle)
                var cleanup: Throwable? = null
                try { nativeParityOnMain(instrumentation) { active?.close(); active = null } }
                catch (failure: Throwable) {
                    cleanup = failure; caseFailure?.addSuppressed(failure)
                    entry.put("cleanup_error", android.util.Log.getStackTraceString(failure))
                }
                writeHomeEvidence(directory, result, caseFailure ?: cleanup)
                if (caseFailure == null) cleanup?.let { throw it }
            }
        }
        check(completed == 6 && mismatches == 0) { "Home input mismatch: $mismatches/$completed" }
    } catch (failure: Throwable) {
        primary = failure
        result.put("terminal_error", android.util.Log.getStackTraceString(failure))
        throw failure
    } finally {
        var cleanup: Throwable? = null
        try { nativeParityOnMain(instrumentation) { try { active?.close() } finally { host?.finish() } } }
        catch (failure: Throwable) {
            cleanup = failure; primary?.addSuppressed(failure)
            result.put("cleanup_error", android.util.Log.getStackTraceString(failure))
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", primary == null && cleanup == null && completed == 6 && mismatches == 0)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeHomeEvidence(directory, result, primary ?: cleanup)
        if (primary == null) cleanup?.let { throw it }
    }
}
