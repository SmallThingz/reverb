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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.nativeui.ReverbAppTopBarActions
import app.smallthingz.reverb.nativeui.ReverbHomeActions
import app.smallthingz.reverb.nativeui.HomeClickable
import app.smallthingz.reverb.nativeui.ReverbHomeBlobControl
import app.smallthingz.reverb.nativeui.ReverbHomeBlobState
import app.smallthingz.reverb.nativeui.ReverbHomeControls
import app.smallthingz.reverb.nativeui.ReverbHomeControlsState
import app.smallthingz.reverb.nativeui.ReverbHomeFlipFrame
import app.smallthingz.reverb.nativeui.ReverbHomeBodyState
import app.smallthingz.reverb.nativeui.ReverbHomeInsets
import app.smallthingz.reverb.nativeui.ReverbHomeScreenView
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.HomeBufferSegment
import app.smallthingz.reverb.nativeui.NativeMergedRowAccessibilityHost
import app.smallthingz.reverb.nativeui.NativeTextSemanticsClip
import app.smallthingz.reverb.nativeui.NativeTextSemanticsClipPolicy
import app.smallthingz.reverb.nativeui.NativeWindowTextAccessibilityOwner
import app.smallthingz.reverb.nativeui.ReverbGroupLayout
import app.smallthingz.reverb.nativeui.ReverbHomeControlSurface
import app.smallthingz.reverb.nativeui.ReverbParagraphView
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
    val serviceConnected: Boolean = true,
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
        .put("approved_reference_changes", JSONArray().put(APP_TOP_BAR_APPROVED_REFERENCE_CHANGE))
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
    commonGrain: NativeParityCommonGrainSession? = null,
) : AutoCloseable {
    private val configuration = Configuration(host.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    private val context = host.createConfigurationContext(configuration)
    private val referenceResources = commonGrain?.referenceResources(context.resources) ?: context.resources
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
        case.retention, case.listening, false, case.serviceConnected, true, false, nativeOne, nativeLoop, false,
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
                LocalResources provides referenceResources, LocalDensity provides density,
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
                                fixtureServiceConnected = case.serviceConnected, serviceStateHydrated = true,
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

    // Bind the actual mounted leaves: rebinding only the screen would let the owner's existing
    // presentationActions indirection conceal a stale callback retained inside a leaf.
    private fun nativeDescendants(): List<View> = buildList {
        fun visit(view: View) {
            add(view)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(checkNotNull(native))
    }

    fun bindingTarget(blob: Boolean): View {
        val views = nativeDescendants()
        if (blob) return views.filterIsInstance<ReverbHomeBlobControl>().single()
        val description = context.getString(R.string.record_all_memory)
        val glyph = views.single { it.contentDescription == description }
        return checkNotNull(glyph.parent as? HomeClickable)
    }

    fun bindNativeLeaves(label: String, enabled: Boolean = true, slot: BufferSlot = BufferSlot.ONE_SHOT) {
        val views = nativeDescendants()
        fun record(action: String) = event(nativeEvents, "$label:$action")
        val callbacks = ReverbHomeActions(
            selectBuffer = { record("select:" + it.name) },
            activateBuffer = { record("activate:" + it.name) },
            openBufferSettings = { record("buffer-settings:" + it.name) },
            exportFull = { record("export-full:" + it.name) },
            exportCustom = { record("export-custom:" + it.name) },
            clearBuffer = { record("clear:" + it.name) },
            openLibrary = { record("library") },
        )
        val frame = ReverbHomeFlipFrame()
        views.filterIsInstance<ReverbHomeControls>().single().bind(ReverbHomeControlsState(
            slot, null, false, true, false, true, true, !enabled, true, false,
        ), frame, callbacks)
        views.filterIsInstance<ReverbHomeBlobControl>().single().bind(ReverbHomeBlobState(
            if (slot == BufferSlot.ONE_SHOT) nativeOne else nativeLoop,
            false, !enabled, true, enabled, false, false, null, null, false,
        ), frame) { callbacks.activateBuffer(slot) }
    }

    fun reattachNative() {
        val view = checkNotNull(native)
        host.fixtureRoot.removeView(view)
        check(!view.isAttachedToWindow)
        host.fixtureRoot.addView(view, FrameLayout.LayoutParams(-1, -1))
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
    verifyNativeHomeInputParity(instrumentation, report, normalizeGrain = false)
}

/** Separate supplemental selector; the raw released-resource selector above remains unchanged. */
internal fun verifyNativeHomeInputCommonGrainParity(instrumentation: Instrumentation, report: StringBuilder) {
    verifyNativeHomeInputParity(instrumentation, report, normalizeGrain = true)
}

private fun verifyNativeHomeInputParity(
    instrumentation: Instrumentation, report: StringBuilder, normalizeGrain: Boolean,
) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val selector = if (normalizeGrain) "native_home_input_common_grain" else "native_home_input"
    val directoryPrefix = if (normalizeGrain) "native-home-input-common-grain-" else "native-home-input-"
    val directory = File(context.filesDir, directoryPrefix + System.currentTimeMillis())
    check(directory.mkdir())
    val entries = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("scope", "Synthetic touch dispatch to the actual own-view roots; no physical/system injected input")
        .put("approved_reference_changes", JSONArray().put(APP_TOP_BAR_APPROVED_REFERENCE_CHANGE))
        .put("motion_limit", "Sequential reference/native captures have independent clocks; request/callback wall times are not presentation timestamps. Intermediate images are retained, not used to claim frame parity.")
        .put("cases", entries).put("passed", false)
    var host: NativeParityActivity? = null
    var active: HomeParityBoard? = null
    var primary: Throwable? = null
    var completed = 0
    var mismatches = 0
    var commonGrain: NativeParityCommonGrainSession? = null
    try {
        if (normalizeGrain) {
            commonGrain = NativeParityCommonGrainSession()
            result.put("supplemental", true)
                .put("reference_input_override", "settings_noise_tile only: exact native procedural grain")
        }
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
                        HomeParityBoard(activity, homeCases()[1], dark, commonGrain).also { active = it }
                    }
                    // Compose creates the shared, correctly configured window frame scope even
                    // for a native-only route. No reference operation is dispatched on that route.
                    captureNativeParityContent(instrumentation, activity) { board.show(true) }.recycle()
                    commonGrain?.verifyReferenceLoads()
                    commonGrain?.verifyNativeReady()
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
                report.append(if (passed) "PASS " else "FAIL ").append(selector).append(' ')
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
                commonGrain?.let { result.put("common_grain", it.evidence()) }
                writeHomeEvidence(directory, result, caseFailure ?: cleanup)
                if (caseFailure == null) cleanup?.let { throw it }
            }
        }
        val bindings = JSONObject().put("cases", JSONArray()).put("passed", false)
        result.put("home_binding", bindings)
        verifyNativeHomeLeafBindings(instrumentation, activity, bindings, report)
        commonGrain?.verifyReferenceLoads()
        commonGrain?.verifyNativeReady()
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
        commonGrain?.let { result.put("common_grain", it.evidence()) }
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeHomeEvidence(directory, result, primary ?: cleanup)
        if (primary == null) cleanup?.let { throw it }
    }
}

/** Additive action assertions only. Existing A/B cases and the captured renderer remain unchanged. */
private fun verifyNativeHomeLeafBindings(
    instrumentation: Instrumentation, activity: NativeParityActivity,
    result: JSONObject, report: StringBuilder,
) {
    result.put("scope", "Actual mounted Home control/blob leaves; synthetic own-root touch/key dispatch")
        .put("limits", "Native callback/lifecycle assertions only; no reference, pixel or animation-midpoint claim")
    val entries = result.getJSONArray("cases")
    var board: HomeParityBoard? = null
    var primary: Throwable? = null
    var completed = 0
    try {
        val current = nativeParityOnMain(instrumentation) {
            HomeParityBoard(activity, homeCases()[1], false).also { board = it }
        }
        // Reuse the existing board's real window/frame scope and native mounting seam.
        captureNativeParityContent(instrumentation, activity) { current.show(true) }.recycle()
        captureNativeParityContent(instrumentation, activity) { current.show(false) }.recycle()
        for (blob in listOf(false, true)) for (keyboard in listOf(false, true)) {
            for (scenario in listOf("replace", "disable", "reattach", "current-slot")) {
                val name = (if (blob) "blob" else "export") + "-" +
                    (if (keyboard) "key" else "pointer") + "-" + scenario
                val entry = JSONObject().put("name", name).put("input", JSONArray()).put("passed", false)
                entries.put(entry)
                requireNativeParityHost(instrumentation, activity)
                val root = nativeParityOnMain(instrumentation) {
                    current.bindNativeLeaves("old")
                    current.content(false)
                }
                awaitNativeParityDraw(instrumentation, root)
                val target = nativeParityOnMain(instrumentation) { current.bindingTarget(blob) }
                val start = nativeParityOnMain(instrumentation) { current.nativeEvents.length() }
                var downTime = 0L
                fun send(down: Boolean) {
                    requireNativeParityHost(instrumentation, activity)
                    nativeParityOnMain(instrumentation) {
                        check(root.isAttachedToWindow && root.isShown && root.hasWindowFocus())
                        check(target === current.bindingTarget(blob) && target.isAttachedToWindow)
                        val now = SystemClock.uptimeMillis()
                        if (down) downTime = now
                        val handled = if (keyboard) {
                            // Programmatic focus belongs to this isolated fixture window.
                            if (down) check(target.requestFocusFromTouch() && target.isFocused)
                            root.dispatchKeyEvent(KeyEvent(downTime, now,
                                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_ENTER, 0))
                        } else {
                            val origin = IntArray(2).also(root::getLocationInWindow)
                            val location = IntArray(2).also(target::getLocationInWindow)
                            val x = location[0] - origin[0] + target.width / 2f
                            val y = location[1] - origin[1] + target.height / 2f
                            val pointer = MotionEvent.PointerProperties().apply {
                                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                            }
                            val coordinates = MotionEvent.PointerCoords().apply {
                                this.x = x; this.y = y; pressure = 1f; size = 1f
                            }
                            val event = MotionEvent.obtain(downTime, now,
                                if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
                                1, arrayOf(pointer), arrayOf(coordinates), 0, 0,
                                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
                        }
                        entry.getJSONArray("input").put(JSONObject().put("down", down)
                            .put("handled", handled).put("event_ms", now))
                        if (down) check(handled) { "$name did not admit DOWN" }
                    }
                }
                fun assertActions(expected: List<String>) {
                    val observed = nativeParityOnMain(instrumentation) {
                        val events = current.nativeEvents
                        (start until events.length()).map { events.getJSONObject(it).getString("action") }
                    }
                    entry.put("callbacks", JSONArray(observed)).put("expected", JSONArray(expected))
                    check(observed == expected) { "$name callbacks $observed != $expected" }
                }
                send(true)
                awaitNativeParityDraw(instrumentation, root)
                nativeParityOnMain(instrumentation) {
                    when (scenario) {
                        "disable" -> current.bindNativeLeaves("disabled", enabled = false)
                        "reattach" -> current.reattachNative()
                    }
                    current.bindNativeLeaves("new", slot = if (scenario == "current-slot")
                        BufferSlot.LOOPING else BufferSlot.ONE_SHOT)
                }
                awaitNativeParityDraw(instrumentation, root)
                send(false)
                val action = (if (blob) "activate:" else "export-full:") +
                    (if (scenario == "current-slot") "LOOPING" else "ONE_SHOT")
                val expected = listOf("new:$action")
                if (scenario == "disable" || scenario == "reattach") {
                    assertActions(emptyList()) // Old ownership must not survive either boundary.
                    send(true)
                    send(false)
                }
                assertActions(expected)
                send(false) // An unmatched second UP must never duplicate the callback.
                assertActions(expected)
                entry.put("passed", true)
                completed++
                report.append("PASS native_home_binding ").append(name).append('\n')
            }
        }
        check(completed == 16)
    } catch (failure: Throwable) {
        primary = failure
        result.put("terminal_error", android.util.Log.getStackTraceString(failure))
        throw failure
    } finally {
        var cleanup: Throwable? = null
        try { nativeParityOnMain(instrumentation) { board?.close() } }
        catch (failure: Throwable) { cleanup = failure; primary?.addSuppressed(failure) }
        result.put("completed_cases", completed).put("expected_cases", 16)
            .put("passed", primary == null && cleanup == null && completed == 16)
        if (primary == null) cleanup?.let { throw it }
    }
}

/** Focused platform-node regression; existing raw/pixel/input selectors are independent. */
internal fun verifyNativeHomeAccessibilityParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-home-accessibility-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_home_accessibility")
        .put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "f364a4f9b2a48f45bbe004e02958431d2df3898d9aa42f95ec92f100a3cc84af")
        .put("approved_reference_changes", JSONArray().put(APP_TOP_BAR_APPROVED_REFERENCE_CHANGE))
        .put("scope", "Actual standalone Home platform nodes; native production source mapper and text binding; selected/unselected tabs, service-ready/unready, LTR/RTL")
        .put("limits", "No TalkBack delivery, full Activity/recorder, overlay occlusion, pixel or motion-phase claim; no system setting changes. isFocused is retained raw and checked direct-versus-platform, but not compared across standalone renderers: this fixture lacks Main's unsolicited-focus suppression. Focusability and complete advertised actions remain exact comparisons.")
        .put("cases", cases).put("passed", false)
    var host: NativeParityActivity? = null
    var board: HomeParityBoard? = null
    var textLease: AutoCloseable? = null
    var primary: Throwable? = null
    var completed = 0
    var mismatches = 0
    try {
        val activity = launchNativeParityActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        requireNativeParityHost(instrumentation, activity)
        // Keep the user's enabled accessibility services, including ToastCaptureService, running.
        val automation = nativeParityUiAutomation(instrumentation)
        result.put("ui_automation_flags", android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val scale = android.animation.ValueAnimator.getDurationScale()
        check(scale.isFinite() && scale in 0f..10f) { "Home node follow-up unqualified at current animator scale" }
        result.put("observed_animator_scale", scale.toDouble())
        val labels = listOf(context.getString(R.string.buffer_one_shot), context.getString(R.string.buffer_loop))
        for (rtl in listOf(false, true)) for (ready in listOf(true, false)) {
            val name = (if (rtl) "rtl" else "ltr") + if (ready) "-ready" else "-service-unready"
            val entry = JSONObject().put("name", name).put("service_ready", ready)
                .put("rtl", rtl).put("routes", JSONArray()).put("passed", false)
            cases.put(entry)
            val signatures = ArrayList<String>()
            for (compose in listOf(true, false)) {
                requireNativeParityHost(instrumentation, activity)
                val current = nativeParityOnMain(instrumentation) {
                    HomeParityBoard(activity, homeCases()[1].copy(serviceConnected = ready), rtl).also { board = it }
                }
                captureNativeParityContent(instrumentation, activity) { current.show(true) }.recycle()
                captureNativeParityContent(instrumentation, activity) { current.show(compose) }.recycle()
                if (!compose) textLease = nativeParityOnMain(instrumentation) {
                    bindHomeParityAccessibility(activity, current.content(false) as ReverbHomeScreenView, current.dp(48f))
                }
                val route = JSONObject().put("renderer", if (compose) "compose" else "native")
                entry.getJSONArray("routes").put(route)
                fun snapshot(step: String): JSONArray {
                    val nodes = onboardingNativeNodeEvidence(instrumentation, activity, current.content(compose), automation) {
                        route.put("$step-partial", it)
                        writeHomeEvidence(directory, result, null)
                    }
                    route.put("$step-platform", nodes)
                    val tabs = extractHomeTabNodes(nodes.getJSONArray("raw"), labels)
                    route.put(step, tabs)
                    if (!compose) nativeParityOnMain(instrumentation) {
                        labels.forEachIndexed { index, label ->
                            val target = homeNativeTab(current.content(false), label)
                            val info = checkNotNull(target.createAccessibilityNodeInfo())
                            try {
                                val expected = tabs.getJSONObject(index).getJSONObject("parent")
                                check(homeNodeKey(directHomeNode(info, current.content(false))) == homeNodeKey(expected)) {
                                    "Direct native node disagrees with actual platform tree for $label"
                                }
                                val children = ArrayList<View>().also(target::addChildrenForAccessibility)
                                check(children.size == 2 && children[0] is ReverbParagraphView)
                                val bounds = expected.getJSONArray("bounds")
                                check(bounds.getInt(3) - bounds.getInt(1) >= kotlin.math.floor(current.dp(48f)).toInt()) {
                                    "Tab semantic target lost its released48dp minimum"
                                }
                                val role = tabs.getJSONObject(index).getJSONArray("children").getJSONObject(1)
                                val visual = IntArray(2).also(target::getLocationOnScreen)
                                val origin = IntArray(2).also(current.content(false)::getLocationOnScreen)
                                val visualBounds = JSONArray(listOf(visual[0] - origin[0], visual[1] - origin[1],
                                    visual[0] - origin[0] + target.width, visual[1] - origin[1] + target.height))
                                check(role.getJSONArray("bounds").toString() == visualBounds.toString()) {
                                    "Role-only child must retain visual bounds, separate from expanded parent"
                                }
                            } finally { @Suppress("DEPRECATION") info.recycle() }
                        }
                    }
                    return tabs
                }
                val initial = snapshot("initial")
                // ReleasedHomeCaptureControlCluster leaves selector interactionEnabled=true.
                // Service readiness gates capture/export, not navigation between retained buffers.
                check((0 until initial.length()).all {
                    initial.getJSONObject(it).getJSONObject("parent").getBoolean("enabled")
                }) { "Settled Home buffer navigation must remain enabled without the service" }
                check(initial.getJSONObject(0).getJSONObject("parent").getBoolean("selected"))
                check(!initial.getJSONObject(1).getJSONObject("parent").getBoolean("selected"))
                requireNativeParityHost(instrumentation, activity)
                val selectedResult = nativeParityOnMain(instrumentation) {
                    clickHomeTabNode(current.content(compose), compose, labels[0])
                }
                route.put("selected_action_result", selectedResult)
                check(selectedResult) { "Selected tab direct action admission differs from released contract" }
                check(nativeParityOnMain(instrumentation) { current.events(compose).length() } == 0) {
                    "Selecting the current buffer must not commit another selection"
                }
                val completion = CountDownLatch(1)
                nativeParityOnMain(instrumentation) { current.setCompletion(completion) }
                requireNativeParityHost(instrumentation, activity)
                val unselectedResult = nativeParityOnMain(instrumentation) {
                    clickHomeTabNode(current.content(compose), compose, labels[1])
                }
                route.put("unselected_action_result", unselectedResult)
                check(unselectedResult) { "Unselected tab direct action admission differs from released contract" }
                check(completion.await((420f * scale).toLong() + 2_000L, TimeUnit.MILLISECONDS)) {
                    "Home semantic selection callback did not complete"
                }
                captureNativeParityContent(instrumentation, activity) { current.show(compose) }.recycle()
                val after = snapshot("after-actions") // Fresh provider IDs and native nodes after any rebind.
                val callbacks = nativeParityOnMain(instrumentation) {
                    val events = current.events(compose)
                    JSONArray((0 until events.length()).map { events.getJSONObject(it).getString("action") })
                }
                val expectedCallbacks = JSONArray(listOf("select:LOOPING"))
                route.put("callbacks", callbacks)
                check(callbacks.toString() == expectedCallbacks.toString())
                val selection = nativeParityOnMain(instrumentation) { current.selected(compose).name }
                route.put("selection", selection)
                check(selection == "LOOPING")
                signatures += homeTabKey(initial) + "\n" + homeTabKey(after) + "\n" +
                    selectedResult + ":" + unselectedResult + ":" + callbacks + ":" + selection
                nativeParityOnMain(instrumentation) {
                    textLease?.close(); textLease = null
                    current.close(); board = null
                }
            }
            val passed = signatures[0] == signatures[1]
            entry.put("exact_node_and_action_match", passed).put("passed", passed)
            completed++
            if (!passed) mismatches++
            report.append(if (passed) "PASS " else "FAIL ").append("native_home_accessibility ").append(name).append('\n')
            writeHomeEvidence(directory, result, null)
        }
        check(completed == 4 && mismatches == 0) { "Home accessibility differs in $mismatches/$completed cases" }
    } catch (failure: Throwable) {
        primary = failure
        result.put("terminal_error", android.util.Log.getStackTraceString(failure))
        throw failure
    } finally {
        var cleanup: Throwable? = null
        try { nativeParityOnMain(instrumentation) {
            try { textLease?.close() } finally { try { board?.close() } finally { host?.finish() } }
        } } catch (failure: Throwable) { cleanup = failure; primary?.addSuppressed(failure) }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", primary == null && cleanup == null && completed == 4 && mismatches == 0)
        cleanup?.let { result.put("cleanup_error", android.util.Log.getStackTraceString(it)) }
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeHomeEvidence(directory, result, primary ?: cleanup)
        if (primary == null) cleanup?.let { throw it }
    }
}

private fun homeViewDescendants(root: View): List<View> = buildList {
    fun visit(view: View) { add(view); if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
    visit(root)
}

private fun homeNativeTab(root: View, label: String): HomeBufferSegment =
    homeViewDescendants(root).filterIsInstance<HomeBufferSegment>().single { tab ->
        homeViewDescendants(tab).filterIsInstance<ReverbParagraphView>().any { it.getText() == label }
    }

private fun clickHomeTabNode(root: View, reference: Boolean, label: String): Boolean {
    if (!reference) return homeNativeTab(root, label).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
    val owner = homeViewDescendants(root).filterIsInstance<ViewRootForTest>().single()
    val semantic = owner.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            node.config.getOrNull(SemanticsProperties.Selected) != null &&
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }
    return checkNotNull(owner.view.accessibilityNodeProvider)
        .performAction(semantic.id, AccessibilityNodeInfo.ACTION_CLICK, null)
}

private fun bindHomeParityAccessibility(host: NativeParityActivity, home: ReverbHomeScreenView,
                                       minimum: Float): AutoCloseable {
    fun clip(view: View): NativeTextSemanticsClip = home.topBar.sourceClip(view) ?: when {
        (view.parent is ReverbGroupLayout && (view.parent as ReverbGroupLayout).pointerContent === view) ||
            view is ReverbHomeControlSurface -> NativeTextSemanticsClip.Rectangle(
                smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else -> NativeTextSemanticsClip.Unclipped
    }
    val source = NativeMainAccessibility(host.fixtureRoot, host.fixtureRoot, { listOf(home) },
        ownNodeHidden = { false }, clip = ::clip, minimumTouch = { view ->
            if (view is NativeClickPhaseHost &&
                (view !is NativeMergedRowAccessibilityHost || view.mergesAccessibilityDescendants)) minimum else 0f
        })
    return NativeWindowTextAccessibilityOwner(host.fixtureRoot, NativeTextSemanticsClipPolicy(::clip))
        .bindContent(home, NativeTextSemanticsClipPolicy(::clip), source::node) { source.node(it).present }
}

/** Keep the complete platform tree separately; select only the two original tab subtrees here. */
private fun extractHomeTabNodes(raw: JSONArray, labels: List<String>): JSONArray = JSONArray(labels.map { label ->
    val matches = ArrayList<JSONObject>()
    for (index in 0 until raw.length()) {
        val parent = raw.getJSONObject(index)
        val depth = parent.getInt("depth")
        val children = JSONArray()
        var child = index + 1
        while (child < raw.length() && raw.getJSONObject(child).getInt("depth") > depth) {
            val node = raw.getJSONObject(child++)
            if (node.getInt("depth") == depth + 1) children.put(node)
        }
        if (children.length() != 2 || children.getJSONObject(0).optString("text") != label ||
            children.getJSONObject(1).isNull("role_description")) continue
        check(parent.getString("class") == "android.view.View")
        check(parent.isNull("role_description") && parent.isNull("text"))
        check(!children.getJSONObject(1).getBoolean("selected"))
        check(!children.getJSONObject(1).getBoolean("clickable"))
        val selected = parent.getBoolean("selected")
        check(parent.getBoolean("clickable") == !selected)
        val click = parent.getJSONArray("actions").let { actions ->
            (0 until actions.length()).any { actions.getJSONObject(it).getInt("id") == AccessibilityNodeInfo.ACTION_CLICK }
        }
        check(click == (parent.getBoolean("enabled") && !selected))
        matches += JSONObject().put("label", label).put("parent", parent).put("children", children)
    }
    check(matches.size == 1) { "Expected one platform tab subtree for $label; found ${matches.size}" }
    matches.single()
})

private val homeNodeFields = listOf("class", "text", "description", "state_description", "role_description",
    "screen_reader_focusable", "enabled", "clickable", "long_clickable", "checkable", "checked", "selected",
    "focusable", "focused", "visible", "movement_granularities", "actions", "bounds")
private fun homeNodeKey(node: JSONObject, compareFocused: Boolean = true): String =
    JSONArray(homeNodeFields.filter { compareFocused || it != "focused" }.map { node.get(it) }).toString()
private fun homeTabKey(tabs: JSONArray): String = (0 until tabs.length()).joinToString("\n") { index ->
    val tab = tabs.getJSONObject(index)
    val children = tab.getJSONArray("children")
    homeNodeKey(tab.getJSONObject("parent"), compareFocused = false) + "\n" +
        (0 until children.length()).joinToString("\n") { homeNodeKey(children.getJSONObject(it), compareFocused = false) }
}

private fun directHomeNode(node: AccessibilityNodeInfo, root: View): JSONObject {
    val bounds = Rect().also(node::getBoundsInScreen)
    val origin = IntArray(2).also(root::getLocationOnScreen)
    val compat = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node)
    return JSONObject().put("class", node.className?.toString() ?: JSONObject.NULL)
        .put("text", node.text?.toString() ?: JSONObject.NULL)
        .put("description", node.contentDescription?.toString() ?: JSONObject.NULL)
        .put("state_description", if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() ?: JSONObject.NULL else JSONObject.NULL)
        .put("role_description", compat.roleDescription?.toString() ?: JSONObject.NULL)
        .put("screen_reader_focusable", node.isScreenReaderFocusable).put("enabled", node.isEnabled)
        .put("clickable", node.isClickable).put("long_clickable", node.isLongClickable)
        .put("checkable", node.isCheckable).put("checked", node.isChecked).put("selected", node.isSelected)
        .put("focusable", node.isFocusable).put("focused", node.isFocused).put("visible", node.isVisibleToUser)
        .put("movement_granularities", node.movementGranularities)
        .put("actions", JSONArray(node.actionList.sortedBy { it.id }.map {
            JSONObject().put("id", it.id).put("label", it.label?.toString() ?: JSONObject.NULL)
        })).put("bounds", JSONArray(listOf(bounds.left - origin[0], bounds.top - origin[1],
            bounds.right - origin[0], bounds.bottom - origin[1])))
}
