package app.smallthingz.reverb

import smallthingz.ui.components.NativeDurationWheelView
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbRangeExportContent
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.input.phase.PhasePointerRoot
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.reverb.NativeRangeTimelineInput.Gesture
import smallthingz.ui.reverb.ReverbIcon

/**
 * Four actual settled Range bodies, plus the existing duration widget's safe discrete action.
 * No recorder/service/permission/persistence actions, audition, playback or export are invoked.
 */
internal fun verifyNativeRangeParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-range-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val entries = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "cf325e83be5dfacc7a48725a5210d36fcf5f971d1c4617ca5843c65f5011b73b")
        .put("comparison", "Exact unmasked ARGB, ABAB stability and actual state; no tolerance")
        .put("scope", "Range content viewport; regular/compact Light/Dark; existing wheel set-progress action")
        .put("limits", "Settled pixels only; no opening-frame alignment, endpoint pointer/audition, FineSeek playback, export, provider/codec, system inset or full accessibility-service proof")
        .put("qa_preference_digest_format", QA_PREFERENCE_DIGEST_FORMAT)
        .put("cases", entries).put("passed", false)
    writeRangeEvidence(directory, result, null)
    val preferencesBefore = rangePreferenceDigest(context)
    var host: NativeParityActivity? = null
    var board: RangeParityBoard? = null
    var hostTrace: RangeHostTrace? = null
    var signal: RangeParitySignal? = null
    var failure: Throwable? = null
    var completed = 0
    var mismatches = 0
    try {
        signal = RangeParitySignal(File(directory, "owned-known-signal"))
        result.put("signal", signal.receipt())
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        val trace = RangeHostTrace(instrumentation, activity)
        hostTrace = trace
        trace.mark("initial-draw")
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        trace.awaitInitialReady()
        for (dark in listOf(false, true)) for (compact in listOf(false, true)) {
            trace.guard("case-start dark=$dark compact=$compact")
            // Distinct parent leases; the fixture owns their close, neither renderer does.
            val referenceSnapshot = signal.snapshot()
            val nativeSnapshot = try { signal.snapshot() } catch (error: Throwable) {
                try { referenceSnapshot.close() } catch (close: Throwable) { error.addSuppressed(close) }
                throw error
            }
            val current = try {
                nativeParityOnMain(instrumentation) {
                    trace.markOnMain("construct-board dark=$dark compact=$compact")
                    RangeParityBoard(activity, dark, compact, referenceSnapshot, nativeSnapshot)
                }
            } catch (error: Throwable) {
                try { referenceSnapshot.close() } catch (close: Throwable) { error.addSuppressed(close) }
                try { nativeSnapshot.close() } catch (close: Throwable) { error.addSuppressed(close) }
                throw error
            }
            board = current
            var caseFailure: Throwable? = null
            try {
                for (reference in listOf(true, false)) {
                    nativeParityOnMain(instrumentation) { current.show(reference) }
                    awaitRangeReady(instrumentation, activity, current, reference, trace)
                }
                fun captureStep(step: String) {
                    val entry = JSONObject().put("name", current.name + "-" + step).put("passed", false)
                    entries.put(entry)
                    val images = mutableListOf<Bitmap>()
                    val states = JSONArray()
                    val geometry = JSONArray()
                    try {
                        repeat(4) { pass ->
                            val reference = pass % 2 == 0
                            trace.mark("${current.name}-$step-capture-$pass")
                            images += captureNativeParityContent(instrumentation, activity) { current.show(reference) }
                            val suffix = listOf("compose", "native", "compose-repeat", "native-repeat")[pass]
                            saveNativeParityBitmap(directory, entry.getString("name") + "-" + suffix + ".png", images.last())
                            nativeParityOnMain(instrumentation) {
                                states.put(current.stateEvidence(reference))
                                geometry.put(current.geometry(reference))
                            }
                        }
                        val diff = compareNativeParityPixels(images[0], images[1])
                        val referenceStable = compareNativeParityPixels(images[0], images[2])
                        val nativeStable = compareNativeParityPixels(images[1], images[3])
                        val statesEqual = (1 until states.length()).all {
                            states.getJSONObject(it).toString() == states.getJSONObject(0).toString()
                        }
                        val passed = diff.getLong("different_pixels") == 0L &&
                            referenceStable.getLong("different_pixels") == 0L &&
                            nativeStable.getLong("different_pixels") == 0L && statesEqual
                        entry.put("difference", diff).put("reference_stability", referenceStable)
                            .put("native_stability", nativeStable).put("state", states)
                            .put("state_equal", statesEqual).put("geometry", geometry).put("passed", passed)
                        completed++
                        if (!passed) mismatches++
                    } finally { images.forEach(Bitmap::recycle) }
                }
                captureStep("settled")
                if (!compact) {
                    val actions = JSONArray()
                    for (reference in listOf(true, false)) {
                        trace.guard("${current.name}-duration-action reference=$reference")
                        nativeParityOnMain(instrumentation) { current.show(reference) }
                        awaitNativeParityDraw(instrumentation, current.content(reference))
                        val action = nativeParityOnMain(instrumentation) { current.resizeWithWheel(reference, 3f) }
                        actions.put(action)
                        check(action.getBoolean("accepted")) { "Existing duration action rejected: $action" }
                        awaitRangeReady(instrumentation, activity, current, reference, trace)
                    }
                    result.put(current.name + "-duration_actions", actions)
                    captureStep("duration-3s")
                }
            } catch (error: Throwable) { caseFailure = error; throw error }
            finally {
                var closeFailure: Throwable? = null
                try { nativeParityOnMain(instrumentation) { current.close(); board = null } }
                catch (error: Throwable) { closeFailure = error; caseFailure?.addSuppressed(error) }
                for (snapshot in listOf(referenceSnapshot, nativeSnapshot)) {
                    try { snapshot.close() } catch (error: Throwable) {
                        if (caseFailure != null) caseFailure.addSuppressed(error)
                        else if (closeFailure == null) closeFailure = error else closeFailure.addSuppressed(error)
                    }
                }
                try { result.put("host_lifecycle_trace", trace.snapshot()) }
                catch (error: Throwable) {
                    if (caseFailure != null) caseFailure.addSuppressed(error)
                    else if (closeFailure == null) closeFailure = error else closeFailure.addSuppressed(error)
                }
                writeRangeEvidence(directory, result, caseFailure ?: closeFailure)
                if (caseFailure == null) closeFailure?.let { throw it }
            }
        }
        check(completed == 6 && mismatches == 0) { "Range mismatch: $mismatches/$completed" }
    } catch (error: Throwable) {
        failure = error
        try { hostTrace?.recordFailure(error) } catch (traceError: Throwable) { error.addSuppressed(traceError) }
    }
    finally {
        fun release(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release { hostTrace?.close() }
        release { hostTrace?.let { result.put("host_lifecycle_trace", it.snapshot()) } }
        release { nativeParityOnMain(instrumentation) { board?.close() } }
        release { nativeParityOnMain(instrumentation) { host?.fixtureRoot?.removeAllViews() } }
        release { nativeParityOnMain(instrumentation) { host?.finish() } }
        release { signal?.close() }
        release {
            result.put("qa_preferences_unchanged", rangePreferenceDigest(context) == preferencesBefore)
            check(result.getBoolean("qa_preferences_unchanged")) { "QA preference mutation was not expected" }
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
            .put("passed", failure == null && completed == 6 && mismatches == 0)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeRangeEvidence(directory, result, failure)
    }
    failure?.let { throw it }
}

internal fun verifyNativeRangeInteractionCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-range-interaction-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val result = JSONObject().put("selector", "native_range_interaction_current")
        .put("historical_parity_claim", false).put("passed", false)
        .put("scope", "Current native range preparation and interaction against an isolated synthetic audio lease")
        .put("unqualified", JSONArray(listOf("physical touch", "audible fine-seek", "actual export", "TalkBack delivery")))
    writeRangeEvidence(directory, result, null)
    val preferencesBefore = rangePreferenceDigest(context)
    var host: NativeParityActivity? = null
    var trace: RangeHostTrace? = null
    var runtime: NativeModalWindowRuntime? = null
    var signal: RangeParitySignal? = null
    var snapshot: ReverbService.TimelineSnapshot? = null
    var board: RangeParityBoard? = null
    var failure: Throwable? = null
    try {
        val fixture = RangeParitySignal(File(directory, "owned-known-signal")).also { signal = it }
        val source = fixture.snapshot().also { snapshot = it }
        result.put("signal", fixture.receipt())
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        val hostTrace = RangeHostTrace(instrumentation, activity).also { trace = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        hostTrace.awaitInitialReady()
        val perf = ownerPerfCycle.get()
        perf?.opening(activity)
        val current = nativeParityOnMain(instrumentation) {
            val clock = NativeModalWindowRuntime(activity).also { runtime = it; it.setStarted(true) }
            RangeParityBoard(activity, dark = false, compact = perf != null, referenceSnapshot = null,
                nativeSnapshot = source, currentScope = clock.scope, routedChecks = perf == null)
                .also { board = it; it.show(false) }
        }
        if (perf == null) {
            result.put("interaction_revision", 2)
                .put("draw_scope", "Attached View pre-draw and completed draw boundaries; no compositor presentation proof")
                .put("input_scope", "Owned PhasePointerRoot MotionEvents and production NativeRangePointerBinding; no system input injection")
            nativeParityOnMain(instrumentation) { result.put("preparation_draws", current.preparationEvidence) }
            repeat(2) { awaitNativeParityDraw(instrumentation, current.content(false)) }
            nativeParityOnMain(instrumentation) {
                current.preparationEvidence.put("completed_draw_boundaries", 2)
                current.attachPreparedSnapshot()
            }
        }
        awaitRangeReady(instrumentation, activity, current, false, hostTrace, settleForParity = perf == null)
        perf?.openSettled(current.content(false))
        if (perf == null) {
            val image = captureNativeParityContent(instrumentation, activity) { current.content(false) }
            try { saveNativeParityBitmap(directory, "range-settled.png", image) }
            finally { image.recycle() }
            result.put("capture", "range-settled.png")
        }
        result.put("native_range_interactions", nativeParityOnMain(instrumentation) {
            current.verifyInteractionRegressions()
        })
        if (perf == null) {
            // Let the original check's newly eligible Export colors finish their ordinary transition.
            awaitRangeReady(instrumentation, activity, current, false, hostTrace)
            val routed = JSONObject().put("passed", false)
            result.put("routed_range_interactions", routed)
            current.verifyRoutedInteractions(instrumentation, routed)
        }
    } catch (error: Throwable) {
        failure = error
        try { trace?.recordFailure(error) } catch (traceError: Throwable) { error.addSuppressed(traceError) }
    } finally {
        fun release(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release { trace?.close() }
        release { trace?.let { result.put("host_lifecycle_trace", it.snapshot()) } }
        release { nativeParityOnMain(instrumentation) { board?.close() } }
        release { nativeParityOnMain(instrumentation) { runtime?.close() } }
        release { nativeParityOnMain(instrumentation) { host?.finish() } }
        release { snapshot?.close() }
        release { signal?.close() }
        release {
            result.put("qa_preferences_unchanged", rangePreferenceDigest(context) == preferencesBefore)
            check(result.getBoolean("qa_preferences_unchanged"))
        }
        result.put("passed", failure == null).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        report.append("RANGE_INTERACTION_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeRangeEvidence(directory, result, failure)
    }
    failure?.let { throw it }
}

private class RangeParitySignal(private val directory: File) : AutoCloseable {
    private val rate = 8_000
    private val duration = 8
    private val pcm = ByteArray(rate * duration * 2) { 0 }
    private val store: PersistentAudioChunkStore
    init {
        check(directory.mkdir())
        // Exact integer triangular carriers with per-second amplitude steps; no microphone/file import.
        for (frame in 0 until rate * duration) {
            val amplitude = 2500 + (frame / rate) * 1900
            val triangle = (frame % 64).let { if (it < 32) it - 16 else 48 - it }
            val sample = triangle * amplitude / 16
            pcm[frame * 2] = sample.toByte()
            pcm[frame * 2 + 1] = (sample shr 8).toByte()
        }
        store = PersistentAudioChunkStore(directory, overwriteOldest = false)
        try {
            store.configure(RetentionMode.SIZE, 1_048_576L, rate, 1, PcmSampleFormat.PCM_16)
            check(store.append(pcm, 0, pcm.size) == pcm.size)
            store.sealActiveChunk()
            check(store.durationSeconds() == duration.toDouble())
        } catch (error: Throwable) {
            try { store.close() } catch (close: Throwable) { error.addSuppressed(close) }
            throw error
        }
    }
    fun snapshot() = ReverbService.TimelineSnapshot(checkNotNull(store.acquireRange(0.0, duration.toDouble())))
    fun receipt() = JSONObject().put("sample_rate", rate).put("channels", 1).put("encoding", "PCM16LE")
        .put("frames", rate * duration).put("duration_seconds", duration).put("bytes", pcm.size)
        .put("sha256", rangeSha256(pcm)).put("path", directory.absolutePath)
    override fun close() = store.close()
}

@OptIn(ExperimentalComposeViewContextApi::class)
private class RangeParityBoard(
    private val host: NativeParityActivity,
    dark: Boolean,
    private val compact: Boolean,
    referenceSnapshot: ReverbService.TimelineSnapshot?,
    private val nativeSnapshot: ReverbService.TimelineSnapshot,
    private val currentScope: CoroutineScope? = null,
    private val routedChecks: Boolean = false,
) : AutoCloseable {
    val name = (if (dark) "dark" else "light") + (if (compact) "-compact" else "-regular")
    private val configuration = Configuration(host.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        // This QA Activity is manifest-locked landscape. Fit the regular body in its actual
        // attached viewport using only this context's density; record the override as proof scope.
        if (routedChecks) densityDpi = minOf(densityDpi,
            (host.fixtureRoot.height * 160f / 680f).toInt().coerceAtLeast(1))
    }
    private val context = host.createConfigurationContext(configuration)
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    private val environment = nativeParityEnvironment(context, density,
        ReverbPalette.resolve(context, dark))
    private val height = if (compact && currentScope == null) (420 * density.density).roundToInt()
        else host.fixtureRoot.height
    private val width = host.fixtureRoot.width
    private val source = RangeMorphSourceGeometry(0f, 0f, width * .5f, height * .5f, 180 * density.density)
    private val referenceHost by lazy { FrameLayout(context) }
    private val reference by lazy { ComposeView(context) }
    private var pointerBinding: NativeRangePointerBinding? = null
    private val pointerCoordinates = NativeWindowPointerCoordinates { nativeHost }
    private val pointerLayout = object : NativeViewPointerHitLayout {
        override val placed get() = nativeHost.isAttachedToWindow && nativeHost.isShown
        override val size get() = Size(nativeHost.width.toFloat(), nativeHost.height.toFloat())
        override val minimumTouchTarget get() = Size.Zero
        override val nodes: List<NativeViewPhasePointerNode> get() = emptyList()
        override val childrenInHitOrder get() = listOfNotNull(pointerBinding?.layout)
        override fun screenToLocal(screen: Offset) = pointerCoordinates.coordinates(nativeHost).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }
    private val nativeHost: FrameLayout = (if (routedChecks) PhasePointerRoot(context,
        pointerCoordinates::rootToScreen, pointerCoordinates::coordinates, pointerLayout, pointerCoordinates::begin)
        else FrameLayout(context)).apply {
        // The released18dp inset is padding inside a full-window ComposeView, not a clip.
        // Match NativeCaptureContentView: child shadows may extend into that inset.
        clipChildren = false
        clipToPadding = false
        // Match the real NativeActivityContentView / released AndroidComposeView focus owner.
        // Otherwise an unchanged AndroidView wheel becomes this fixture's implicit first focus.
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }
    private val blob by lazy { AudioBlobController() }
    private var referenceState: LegacyRangeExportEditorState? = null
    private var referenceScope: CoroutineScope? = null
    private var nativeScope: CoroutineScope? = null
    private var nativeOwner: NativeRangeExportOwner? = null
    private var native: ReverbRangeExportContent? = null
    private var nativeFrame: NativeRangeExportPresentation? = null
    val preparationEvidence = JSONObject().put("pre_draws", JSONArray()).put("draw_callbacks", 0)
    private var preparationObserver: ViewTreeObserver? = null
    private var preparationFailure: Throwable? = null
    private val preparationDraw = ViewTreeObserver.OnDrawListener {
        preparationEvidence.put("draw_callbacks", preparationEvidence.getInt("draw_callbacks") + 1)
    }
    private val preparationPreDraw = ViewTreeObserver.OnPreDrawListener {
        try {
            val content = checkNotNull(native)
            val glyph = content.controls.close.visual.glyph
            val icon = glyph.javaClass.getDeclaredField("currentIcon").apply { isAccessible = true }.get(glyph)
            val bounds = android.graphics.Rect()
            val visible = glyph.getGlobalVisibleRect(bounds) && !bounds.isEmpty
            val sample = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                .put("attached", content.isAttachedToWindow).put("shown", content.isShown)
                .put("glyph_visible", visible).put("glyph", icon.toString())
                .put("close_enabled", content.controls.close.visual.isEnabled)
                .put("export_enabled", content.controls.exportAction.isEnabled)
                .put("controls_alpha", content.controls.alpha.toDouble())
                .put("snapshot_present", checkNotNull(nativeFrame).snapshotPresent)
            preparationEvidence.getJSONArray("pre_draws").put(sample)
            check(content.isAttachedToWindow && content.isShown && visible && icon == ReverbIcon.Close &&
                glyph.contentDescription == context.getString(R.string.close) &&
                content.controls.close.visual.isEnabled && !content.controls.exportAction.isEnabled &&
                content.controls.alpha == 1f && !checkNotNull(nativeFrame).snapshotPresent) { sample.toString() }
        } catch (error: Throwable) { if (preparationFailure == null) preparationFailure = error }
        true
    }
    private var exportCallbacks = 0
    private var closed = false

    init {
        try {
            check(height > 0 && height <= host.fixtureRoot.height)
            host.fixtureRoot.removeAllViews()
            host.fixtureRoot.setBackgroundColor(colors.surface.toArgb())
            val views = if (referenceSnapshot == null) listOf(nativeHost) else listOf(referenceHost, nativeHost)
            for (view in views) {
                view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (Build.VERSION.SDK_INT >= 29) view.isForceDarkAllowed = false
                view.setBackgroundColor(colors.surface.toArgb())
                host.fixtureRoot.addView(view, FrameLayout.LayoutParams(width, height))
            }
            nativeHost.visibility = View.GONE
            if (referenceSnapshot != null) mountReference(referenceSnapshot)
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun mountReference(snapshot: ReverbService.TimelineSnapshot) {
        val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
            .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
        val composeContext = ComposeViewContext(referenceHost)
        reference.setContent {
            val scope = rememberCoroutineScope()
            SideEffect { referenceScope = scope }
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides configuration,
                LocalResources provides context.resources, LocalDensity provides density,
                LocalLayoutDirection provides LayoutDirection.Ltr,
            ) {
                MaterialTheme(colorScheme = colors, typography = typography) {
                    Box(Modifier.fillMaxSize().background(colors.surface)) {
                        ReleasedRangeFixtureRangeExportHomeContent(
                            snapshot = snapshot, fixtureStateObserver = { referenceState = it },
                            initialDurationSeconds = 8f, selectedBuffer = ReverbService.BufferSlot.ONE_SHOT,
                            activeBuffer = null, blobMetrics = BufferMetrics(8f, 128_000),
                            blobEnabled = true, blobController = blob, sourceGeometry = source,
                            isListening = false, isSaving = false, service = null,
                            oneShotEnabled = true, oneShotFull = false, loopingEnabled = true,
                            maxExportDurationSeconds = 6.0, modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                            visualizerVisible = true, onCancel = { error("No Close action in this fixture") },
                            onExport = { _, _ -> error("No export action in this fixture") },
                        )
                    }
                }
            }
        }
        reference.createComposition(composeContext)
        referenceHost.addView(reference, FrameLayout.LayoutParams(-1, -1))
    }

    fun show(compose: Boolean): View {
        check(!closed && host.hasWindowFocus() && (!compose || currentScope == null))
        if (!compose && native == null) {
            val inherited = currentScope?.coroutineContext ?: checkNotNull(referenceScope) {
                "Released reference scope is not ready"
            }.coroutineContext.withNativeParityClock()
            val scope = CoroutineScope(inherited + SupervisorJob(inherited[Job]))
            nativeScope = scope
            val owner = NativeRangeExportOwner(context, host, ReverbService.BufferSlot.ONE_SHOT, 8f,
                scope, true, ::renderNative)
            nativeOwner = owner
            val content = ReverbRangeExportContent(context, environment, owner.state, host.lifecycle,
                scope, if (routedChecks) nativeHost else host.fixtureRoot,
                { if (routedChecks) nativeHost else host.fixtureRoot }) { waveform ->
                val rootLocation = IntArray(2).also(host.fixtureRoot::getLocationInWindow)
                val location = IntArray(2).also(waveform::getLocationInWindow)
                val left = (location[0] - rootLocation[0]).toFloat()
                val top = (location[1] - rootLocation[1]).toFloat()
                owner.waveformMeasured(smallthingz.ui.geometry.Rect(left, top, left + waveform.width, top + waveform.height))
            }
            native = content
            val side = (18 * density.density).roundToInt()
            nativeHost.addView(content, FrameLayout.LayoutParams((width - side - side).coerceAtLeast(0), height)
                .apply { leftMargin = side; rightMargin = side })
            if (routedChecks) pointerBinding = NativeRangePointerBinding(content, pointerCoordinates,
                { density.density }, { ViewConfiguration.get(context).scaledTouchSlop.toFloat() })
            if (currentScope != null) {
                owner.bind(null, source, 6.0, 0f, true, ::renderNative)
                val first = checkNotNull(nativeFrame) { "Preparation must bind before the next display frame" }
                check(!first.snapshotPresent && !first.interactionReady)
                check(content.controls.close.visual.glyph.contentDescription == context.getString(R.string.close))
                check(content.controls.close.visual.isEnabled && !content.controls.exportAction.isEnabled)
            }
            if (routedChecks) {
                preparationObserver = nativeHost.viewTreeObserver.also {
                    it.addOnPreDrawListener(preparationPreDraw)
                    it.addOnDrawListener(preparationDraw)
                }
            } else owner.bind(nativeSnapshot, source, 6.0, 0f, true, ::renderNative)
        }
        if (currentScope == null) referenceHost.visibility = if (compose) View.VISIBLE else View.GONE
        nativeHost.visibility = if (compose) View.GONE else View.VISIBLE
        return content(compose)
    }
    private fun renderNative(state: RangeExportEditorState, frame: NativeRangeExportPresentation) {
        check(!state.isPlaying && (routedChecks || !state.isScrubbing))
        nativeFrame = frame
        native?.bind(environment, frame, ReverbService.BufferSlot.ONE_SHOT,
            { error("No Close action") }, {
                check(routedChecks) { "No export action" }
                exportCallbacks++ // Dry callback only. No capture owner or export Service is installed.
            })
    }
    private fun stopPreparationObservation() {
        preparationObserver?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(preparationPreDraw)
            it.removeOnDrawListener(preparationDraw)
        }
        preparationObserver = null
    }
    fun attachPreparedSnapshot() {
        stopPreparationObservation()
        preparationFailure?.let { throw it }
        check(preparationEvidence.getInt("completed_draw_boundaries") == 2 &&
            preparationEvidence.getInt("draw_callbacks") >= 2 &&
            preparationEvidence.getJSONArray("pre_draws").length() >= 2)
        preparationEvidence.put("passed", true)
        checkNotNull(nativeOwner).bind(nativeSnapshot, source, 6.0, 0f, true, ::renderNative)
    }
    fun content(reference: Boolean): View = if (reference) referenceHost else nativeHost
    private fun wheels(reference: Boolean): Sequence<View> = rangeDescendants(content(reference)).filter {
        if (reference) it is RangeDurationWheelView else it is NativeDurationWheelView
    }
    fun state(reference: Boolean) = if (reference) checkNotNull(referenceState).parityState() else checkNotNull(nativeOwner).state.parityState()
    fun ready(reference: Boolean): Boolean {
        val state = if (reference) referenceState?.parityState() else nativeOwner?.state?.parityState()
        if (state == null || !state.snapshotReady || state.waveformLoading ||
            state.coarseWaveformBuiltCount != state.coarseWaveform.size ||
            state.detailWaveformBuiltCount != state.detailWaveform.size) return false
        check(!state.isPlaying && !state.isScrubbing && !state.selectionDurationEditing)
        if (!reference) {
            val frame = nativeFrame ?: return false
            if (!frame.interactionReady || frame.chromeAlpha != 1f || frame.waveformFrame.morphProgress != 1f ||
                frame.waveformFrame.visibleCoarseFraction != 1f || frame.waveformFrame.visibleDetailFraction != 1f) return false
        }
        val wheels = wheels(reference).toList()
        if (currentScope == null) check(wheels.size == if (compact) 0 else 1)
        return wheels.isEmpty() || wheels.single().isEnabled
    }
    fun settleMillis() = maxOf(1_000L, (1_000 * (referenceScope?.coroutineContext?.get(MotionDurationScale)?.scaleFactor ?: 1f)).toLong())
    fun stateEvidence(reference: Boolean): JSONObject {
        val state = state(reference)
        return JSONObject().put("duration", state.durationSeconds.toDouble())
            .put("start", state.startSeconds.toDouble()).put("end", state.endSeconds.toDouble())
            .put("selected_duration", state.selectionDurationExactSeconds).put("target", state.lastTarget.name)
            .put("snapshot_ready", state.snapshotReady).put("loading", state.waveformLoading)
            .put("playing", state.isPlaying).put("scrubbing", state.isScrubbing)
            .put("coarse_count", state.coarseWaveformBuiltCount).put("detail_count", state.detailWaveformBuiltCount)
            .put("coarse_bits", JSONArray(state.coarseWaveform.map(Float::toRawBits)))
            .put("detail_bits", JSONArray(state.detailWaveform.map(Float::toRawBits)))
    }
    fun geometry(reference: Boolean): JSONObject {
        val content = content(reference)
        val position = IntArray(2).also(content::getLocationInWindow)
        val wheel = wheels(reference).singleOrNull()
        val wheelBounds = wheel?.let {
            val xy = IntArray(2).also(it::getLocationInWindow)
            JSONArray(listOf(xy[0] - position[0], xy[1] - position[1], it.width, it.height))
        }
        val endpoints = JSONArray()
        if (reference) {
            val root = rangeDescendants(content).filterIsInstance<ViewRootForTest>().single()
            root.semanticsOwner.getAllSemanticsNodes(false).filter {
                it.layoutInfo.isAttached && it.layoutInfo.isPlaced &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text ->
                        text == context.getString(R.string.range_start_accessibility) ||
                            text == context.getString(R.string.range_end_accessibility)
                    } == true
            }.forEach { node ->
                endpoints.put(JSONObject().put("description", JSONArray(node.config.getOrNull(SemanticsProperties.ContentDescription)))
                    .put("position", JSONArray(listOf(node.positionInRoot.x.toDouble(), node.positionInRoot.y.toDouble())))
                    .put("size", JSONArray(listOf(node.size.width, node.size.height))))
            }
        } else {
            val timeline = checkNotNull(native).timeline
            listOf(timeline.endpointStart, timeline.endpointEnd).forEach { view ->
                val xy = IntArray(2).also(view::getLocationInWindow)
                endpoints.put(JSONObject().put("position", JSONArray(listOf(xy[0] - position[0], xy[1] - position[1])))
                    .put("size", JSONArray(listOf(view.width, view.height))))
            }
        }
        return JSONObject().put("width", content.width).put("height", content.height)
            .put("compact", compact).put("wheel_bounds", wheelBounds ?: JSONObject.NULL)
            .put("endpoints_diagnostic", endpoints)
            .put("focus_diagnostic", focusEvidence(content, wheel))
    }
    private fun focusEvidence(content: View, wheel: View?): JSONObject {
        val focused = host.window.decorView.findFocus()
        return JSONObject().put("in_touch_mode", content.isInTouchMode)
            .put("focused_class", focused?.javaClass?.name ?: JSONObject.NULL)
            .put("focused_inside_board", focused != null && rangeDescendants(content).any { it === focused })
            .put("native_board_itself_focused", nativeHost.isFocused)
            .put("wheel_focused", wheel?.isFocused ?: JSONObject.NULL)
            .put("wheel_focusable", wheel?.isFocusable ?: JSONObject.NULL)
            .put("wheel_focusable_in_touch_mode", wheel?.isFocusableInTouchMode ?: JSONObject.NULL)
            .put("wheel_default_focus_highlight_enabled", wheel?.defaultFocusHighlightEnabled ?: JSONObject.NULL)
    }
    fun resizeWithWheel(reference: Boolean, seconds: Float): JSONObject {
        check(!compact && host.hasWindowFocus() && ready(reference))
        val wheel = wheels(reference).single()
        check(wheel.isShown && wheel.isAttachedToWindow && wheel.hasWindowFocus() && wheel.isEnabled)
        val before = stateEvidence(reference)
        val accepted = wheel.performAccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, seconds) })
        val state = state(reference)
        check(!state.isPlaying && !state.isScrubbing && !state.selectionDurationEditing)
        check(!accepted || state.selectionDurationExactSeconds == seconds.toDouble())
        return JSONObject().put("reference", reference).put("accepted", accepted)
            .put("before", before).put("after", stateEvidence(reference))
    }

    /** View drawing and the real Range hit tree, with a dry export callback and pending-play token.
     * The context density is controlled. This does not qualify system input, compositor frames,
     * acoustic silence, audible fine-seek, or the production Home/Service snapshot handoff.
     */
    fun verifyRoutedInteractions(instrumentation: Instrumentation, evidence: JSONObject) {
        check(routedChecks)
        val content = checkNotNull(native)
        val state = checkNotNull(nativeOwner).state
        val root = nativeHost as PhasePointerRoot
        val events = JSONArray()
        val paintSamples = JSONArray()
        evidence.put("events", events).put("export_paint", paintSamples)
            .put("device_density_dpi", host.resources.configuration.densityDpi)
            .put("fixture_density_dpi", configuration.densityDpi)
            .put("viewport_pixels", JSONArray(listOf(width, height)))
            .put("paint_scope", "Exact software Canvas pixels of the Export surface subtree at pre-draw, plus parent alpha; hardware/compositor output unqualified")
            .put("export_callback_scope", "Dry app-binding callback count; no export owner or Service")
            .put("silent_drag_scope", "Pending saved-playback command survives each routed event; no acoustic measurement")
        fun <T> main(block: () -> T): T = nativeParityOnMain(instrumentation) {
            check(!closed && !host.isFinishing && host.hasWindowFocus() && root.hasWindowFocus() &&
                root.isAttachedToWindow && root.isShown && root.display?.displayId == Display.DEFAULT_DISPLAY)
            block()
        }
        fun point(view: View, x: Float = view.width * .5f, y: Float = view.height * .5f): Offset {
            val origin = IntArray(2).also(root::getLocationOnScreen)
            val location = IntArray(2).also(view::getLocationOnScreen)
            return Offset(location[0] - origin[0] + x, location[1] - origin[1] + y)
        }
        var downTime = 0L
        var expectPendingPlayback = false
        fun dispatch(action: Int, at: Offset) {
            check(at.x >= 0 && at.y >= 0 && at.x < root.width && at.y < root.height)
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val screen = IntArray(2).also(root::getLocationOnScreen)
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                x = at.x + screen[0]; y = at.y + screen[1]
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            })
            val event = MotionEvent.obtain(downTime, now, action, 1, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            event.offsetLocation(-screen[0].toFloat(), -screen[1].toFloat())
            try {
                val sample = JSONObject().put("action", action).put("uptime_ms", now)
                    .put("root_xy", JSONArray(listOf(at.x, at.y)))
                events.put(sample)
                val handled = root.dispatchTouchEvent(event)
                sample.put("handled", handled).put("pending_playback", RecordingPlayback.hasPendingStart())
                    .put("start_seconds", state.startSeconds.toDouble()).put("end_seconds", state.endSeconds.toDouble())
                check(handled)
                check(!expectPendingPlayback || RecordingPlayback.hasPendingStart()) { "Routed drag attempted audition" }
                check(!state.isPlaying)
            } finally { event.recycle() }
        }
        val wheel = main {
            check(ready(false) && exportCallbacks == 0)
            checkNotNull(content.durationWheel).also {
                val bounds = android.graphics.Rect()
                check(it.isEnabled && it.isShown && it.getGlobalVisibleRect(bounds) &&
                    bounds.width() == it.width && bounds.height() == it.height) { "Whole duration wheel must be visible" }
                evidence.put("duration_dial_present_in_viewport", true)
            }
        }
        val export = content.controls.exportAction
        val surface = main {
            generateSequence(export.parent as? View) { it.parent as? View }
                .filterIsInstance<smallthingz.ui.components.NativeGroupLayout>().first()
        }
        fun paint(): Bitmap {
            val image = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            try { surface.draw(Canvas(image)); return image }
            catch (error: Throwable) { image.recycle(); throw error }
        }
        val before = main {
            check(export.isEnabled && content.controls.alpha == 1f)
            paint().also { bitmap ->
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                check(pixels.any { android.graphics.Color.alpha(it) > 0 } && pixels.toSet().size > 2)
                evidence.put("export_before_argb_sha256", nativeParityPixelHash(pixels))
                    .put("export_before_fill", surface.paintedFillColor)
            }
        }
        var phase = "before"
        var paintFailure: Throwable? = null
        val observer = main { root.viewTreeObserver }
        val observePaint = ViewTreeObserver.OnPreDrawListener {
            try {
                val image = paint()
                val difference = try { compareNativeParityPixels(before, image) } finally { image.recycle() }
                val sample = JSONObject().put("phase", phase).put("uptime_ms", SystemClock.uptimeMillis())
                    .put("editing", state.selectionDurationEditing).put("export_enabled", export.isEnabled)
                    .put("callbacks", exportCallbacks).put("fill", surface.paintedFillColor)
                    .put("controls_alpha", content.controls.alpha.toDouble()).put("difference", difference)
                paintSamples.put(sample)
                check(difference.getLong("different_pixels") == 0L && content.controls.alpha == 1f &&
                    surface.paintedFillColor == evidence.getInt("export_before_fill")) { sample.toString() }
                if (state.selectionDurationEditing) {
                    export.performClick()
                    check(exportCallbacks == 0) { "Export accepted while duration wheel owns the edit" }
                }
            } catch (error: Throwable) { if (paintFailure == null) paintFailure = error }
            true
        }
        try {
            val (down, moved) = main {
                observer.addOnPreDrawListener(observePaint)
                val style = environment.appearance.durationWheel
                val timeWidth = wheel.width * (1f - style.profileWidthFraction) - style.profileGapDp * density.density
                val down = point(wheel, timeWidth * 5f / 6f, wheel.height * .5f)
                val moved = Offset(down.x, down.y + style.rowDp * density.density * .37f)
                check(moved.y - down.y > ViewConfiguration.get(context).scaledTouchSlop)
                phase = "held"
                dispatch(MotionEvent.ACTION_DOWN, down)
                check(state.selectionDurationEditing && export.isEnabled)
                check(export.performClick() && exportCallbacks == 0) { "Live app guard must reject the stale enabled action" }
                evidence.put("stale_enabled_callback_rejected", true)
                dispatch(MotionEvent.ACTION_MOVE, moved)
                down to moved
            }
            repeat(3) { awaitNativeParityDraw(instrumentation, root) }
            main {
                paintFailure?.let { throw it }
                check(state.selectionDurationEditing && !export.isEnabled && exportCallbacks == 0)
                phase = "settling"
                dispatch(MotionEvent.ACTION_UP, moved)
                check(state.selectionDurationEditing) { "Fractional drag must exercise asynchronous snap" }
                evidence.put("wheel_drag_pixels", (moved.y - down.y).toDouble())
            }
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (main { state.selectionDurationEditing || !export.isEnabled }) {
                check(SystemClock.uptimeMillis() < deadline) { "Wheel did not settle and restore Export within 3s" }
                awaitNativeParityDraw(instrumentation, root)
                main { paintFailure?.let { throw it } }
            }
            main { phase = "settled" }
            awaitNativeParityDraw(instrumentation, root)
            main {
                paintFailure?.let { throw it }
                val samples = (0 until paintSamples.length()).map(paintSamples::getJSONObject)
                check(samples.any { it.getString("phase") == "held" && it.getBoolean("editing") && !it.getBoolean("export_enabled") })
                check(samples.any { it.getString("phase") == "settling" && it.getBoolean("editing") }) {
                    "No pre-draw sampled the settle lock; coverage incomplete"
                }
                check(exportCallbacks == 0 && export.performClick() && exportCallbacks == 1)
                evidence.put("settled_dry_export_callbacks", exportCallbacks)
            }
        } finally {
            try {
                nativeParityOnMain(instrumentation) {
                    if (observer.isAlive) observer.removeOnPreDrawListener(observePaint)
                    root.cancelPointerInput()
                }
            } finally { before.recycle() }
        }

        var playbackToken = -1L
        val playbackContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName {
                playbackToken = service.getLongExtra(RecordingPlaybackService.EXTRA_REQUEST, -1L)
                return checkNotNull(service.component)
            }
        }
        try {
            main {
                check(!RecordingPlayback.hasPendingStart())
                val recording = RecordingEntity(id = "/unused-synthetic/range-routed.wav", displayName = "range-routed.wav",
                    mimeType = "audio/wav", startedAtMillis = 1L, durationMillis = 8_000L, sizeBytes = 128_044L,
                    codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/unused-synthetic",
                    fileIdentity = "range-routed-pending-playback", createdAtMillis = 1L, lastSeenAtMillis = 1L)
                check(RecordingPlayback.play(playbackContext, recording))
                expectPendingPlayback = true
                state.setTarget(RangeEditTarget.END, 7f)
                state.setTarget(RangeEditTarget.START, 3f)
            }
            awaitNativeParityDraw(instrumentation, root)
            val timeline = content.timeline
            main {
                val wave = timeline.waveformInput
                val down = point(wave, timeline.timelineWidthPixels() * .5f)
                val moved = point(wave, timeline.timelineWidthPixels() * .625f)
                dispatch(MotionEvent.ACTION_DOWN, down)
                dispatch(MotionEvent.ACTION_MOVE, moved)
                dispatch(MotionEvent.ACTION_UP, moved)
                check(kotlin.math.abs(state.startSeconds - 5f) < .001f && state.endSeconds == 7f && !state.isScrubbing)
            }
            // Both markers move twice, with a real owner render between MOVE events. The second
            // delta is measured from the original DOWN in stationary timeline coordinates.
            for (target in listOf(RangeEditTarget.START, RangeEditTarget.END)) {
                awaitNativeParityDraw(instrumentation, root)
                val marker = if (target == RangeEditTarget.START) timeline.endpointStart else timeline.endpointEnd
                val (down, origin) = main {
                    val down = point(marker)
                    val origin = state.targetSeconds(target)
                    dispatch(MotionEvent.ACTION_DOWN, down)
                    dispatch(MotionEvent.ACTION_MOVE, Offset(down.x - timeline.timelineWidthPixels() * .125f, down.y))
                    check(kotlin.math.abs(state.targetSeconds(target) - (origin - 1f)) < .001f)
                    down to origin
                }
                awaitNativeParityDraw(instrumentation, root)
                main {
                    val movedCenter = point(marker)
                    check(movedCenter.x < down.x - timeline.timelineWidthPixels() * .10f) { "Marker did not relocate before second MOVE" }
                    val second = Offset(down.x - timeline.timelineWidthPixels() * .25f, down.y)
                    dispatch(MotionEvent.ACTION_MOVE, second)
                    dispatch(MotionEvent.ACTION_UP, second)
                    check(kotlin.math.abs(state.targetSeconds(target) - (origin - 2f)) < .001f && !state.isScrubbing)
                    evidence.put(target.name.lowercase() + "_marker_relocated_between_moves", true)
                }
            }
            main { check(RecordingPlayback.hasPendingStart() && !state.isPlaying && !state.isScrubbing) }
            evidence.put("routed_body_and_both_markers_preserved_pending_playback", true).put("passed", true)
        } finally {
            nativeParityOnMain(instrumentation) {
                try { root.cancelPointerInput() } finally { RecordingPlayback.takeStart(playbackToken) }
            }
        }
    }

    fun verifyInteractionRegressions(): JSONObject {
        val content = checkNotNull(native)
        val owner = checkNotNull(nativeOwner)
        val timeline = content.timeline
        val state = owner.state
        val frame = checkNotNull(nativeFrame)
        check(ready(false) && !RecordingPlayback.hasPendingStart())

        // The old drag audition synchronously revoked pending saved playback before starting native
        // preview work. Observe the command boundary without launching a playback service.
        var playbackToken = -1L
        val playbackContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName {
                playbackToken = service.getLongExtra(RecordingPlaybackService.EXTRA_REQUEST, -1L)
                return checkNotNull(service.component)
            }
        }
        val recording = RecordingEntity(id = "/unused-synthetic/range-test.wav", displayName = "range-test.wav",
            mimeType = "audio/wav", startedAtMillis = 1L, durationMillis = 8_000L, sizeBytes = 128_044L,
            codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/unused-synthetic",
            fileIdentity = "range-test-saved-playback", createdAtMillis = 1L, lastSeenAtMillis = 1L)
        state.setTarget(RangeEditTarget.END, 8f)
        state.setTarget(RangeEditTarget.START, 5f)
        renderNative(state, frame)
        check(content.controls.exportAction.isEnabled)
        val oldMarkerLeft = (timeline.endpointStart.parent as View).left
        try {
            check(RecordingPlayback.play(playbackContext, recording))
            NativeRangeTimelineInput(state, { content.interactionEnabled }, timeline::timelineWidthPixels,
                timeline::secondsForWaveformX, { it.position.x }, { 8f }).use { input ->
                fun drag(gesture: Gesture, from: Float, to: Float) {
                    fun dispatch(x: Float, pressed: Boolean, previousDown: Boolean) {
                        val position = Offset(x, 0f)
                        val change = NativeDragPointerChange(1L, SystemClock.uptimeMillis(), position, position,
                            pressed, previousDown, PointerType.Touch, position)
                        gesture.dispatch(NativeDragPointerEvent(listOf(change), Offset.Zero), NativePointerPass.MAIN)
                        check(RecordingPlayback.hasPendingStart()) { "Range drag attempted to audition" }
                    }
                    dispatch(from, true, false)
                    dispatch(to, true, true)
                    dispatch(to, false, true)
                }
                val width = timeline.timelineWidthPixels()
                drag(input.waveform, width * .25f, width * .375f)
                check(kotlin.math.abs(state.startSeconds - 3f) < .001f)
                drag(input.end, width * .875f, width * .75f)
                check(kotlin.math.abs(state.endSeconds - 7f) < .001f)
                check(!state.isPlaying && !state.isScrubbing)
            }
        } finally { RecordingPlayback.takeStart(playbackToken) }
        renderNative(state, frame)
        check((timeline.endpointStart.parent as View).left < oldMarkerLeft) {
            "The dragged marker must move in this render without another layout traversal"
        }

        val wheel = NativeDurationWheelView(context, environment.appearance.durationWheel)
        wheel.setMaximumDurationSeconds(16.8)
        wheel.setDurationSeconds(16.2)
        wheel.setDurationSeconds(16.7)
        check(wheel.contentDescription == "0:00:16 1x")
        wheel.setMaximumDurationSeconds(16.6)
        check(wheel.contentDescription == "0:00:17 1x")
        wheel.setMaximumDurationSeconds(16.7)
        check(wheel.contentDescription == "0:00:16 1x")
        return JSONObject().put("first_render_prepared_controls", true).put("drag_audition_revoked", false)
            .put("immediate_marker_geometry", true).put("exact_fractional_wheel_limit", true)
            .put("duration_dial_present_in_viewport", content.durationWheel != null)
    }

    override fun close() {
        if (closed) return
        closed = true
        var error: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (next: Throwable) {
            if (error == null) error = next else if (error !== next) error!!.addSuppressed(next)
        } }
        release(::stopPreparationObservation)
        release { (nativeHost as? PhasePointerRoot)?.cancelPointerInput() }
        release { pointerBinding?.close(); pointerBinding = null }
        release { native?.close() }
        release { nativeOwner?.close() }
        release { nativeScope?.cancel() }
        if (currentScope == null) release { reference.disposeComposition() }
        release(host.fixtureRoot::removeAllViews)
        error?.let { throw it }
    }
}

private fun awaitRangeReady(instrumentation: Instrumentation, host: NativeParityActivity,
    board: RangeParityBoard, reference: Boolean, trace: RangeHostTrace, settleForParity: Boolean = true,
) {
    val deadline = SystemClock.uptimeMillis() + 15_000
    var completeAt: Long? = null
    var finalFrames = 0
    while (SystemClock.uptimeMillis() < deadline) {
        trace.guard("${board.name}-range-ready reference=$reference")
        val ready = nativeParityOnMain(instrumentation) { board.ready(reference) }
        if (ready) {
            if (!settleForParity) return
            if (completeAt == null) completeAt = SystemClock.uptimeMillis()
            finalFrames++
            val delay = nativeParityOnMain(instrumentation) { board.settleMillis() }
            // The released loading-off waveform effect consumes14 frames; reveal uses430/330ms.
            // Keep a bounded source-derived settling margin, then ABAB independently rejects drift.
            if (finalFrames >= 16 && SystemClock.uptimeMillis() - completeAt >= delay) return
        } else { completeAt = null; finalFrames = 0 }
        awaitNativeParityDraw(instrumentation, board.content(reference))
    }
    error("Range did not complete actual snapshot construction/opening within15s; no partial-state pass")
}
/** Initial launch readiness only; a later lost-focus guard never waits or reclaims focus. */
private class RangeHostTrace(
    private val instrumentation: Instrumentation,
    private val host: NativeParityActivity,
) : AutoCloseable {
    private val samples = JSONArray()
    private var phase = "launched"
    private var closed = false
    private var initialReadinessAttempted = false
    private var focusObserved = false
    private var focusLostAfterObservation = false
    private val observer = nativeParityOnMain(instrumentation) { host.fixtureRoot.viewTreeObserver }
    private val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        recordOnMain("window-focus-callback", callbackFocus = focused)
    }

    init {
        nativeParityOnMain(instrumentation) {
            observer.addOnWindowFocusChangeListener(listener)
            try { recordOnMain("observer-attached") }
            catch (error: Throwable) {
                observer.removeOnWindowFocusChangeListener(listener)
                throw error
            }
        }
    }

    fun mark(value: String) = nativeParityOnMain(instrumentation) { markOnMain(value) }
    fun markOnMain(value: String) {
        phase = value
        recordOnMain("phase")
    }
    fun snapshot(): JSONArray = nativeParityOnMain(instrumentation) { JSONArray(samples.toString()) }
    fun recordFailure(error: Throwable) = nativeParityOnMain(instrumentation) {
        recordOnMain("failure").put("error", error.toString())
    }
    fun guard(value: String) {
        mark(value)
        try { requireNativeParityHost(instrumentation, host) }
        catch (error: Throwable) {
            try { recordFailure(error) } catch (traceError: Throwable) { error.addSuppressed(traceError) }
            throw error
        }
    }

    fun awaitInitialReady() {
        check(!initialReadinessAttempted) { "Initial readiness may only be observed once" }
        initialReadinessAttempted = true
        mark("initial-window-readiness")
        repeat(30) { frame ->
            val ready = nativeParityOnMain(instrumentation) {
                val sample = recordOnMain("initial-readiness-sample").put("frame", frame)
                val root = host.fixtureRoot
                val decor = host.window.decorView
                check(!closed && !focusLostAfterObservation && !host.isFinishing &&
                    root.isAttachedToWindow && root.isShown && root.display?.displayId == Display.DEFAULT_DISPLAY) {
                    "New Range QA host lost its attached visible physical0 identity: $sample"
                }
                check(decor.context.packageName == instrumentation.targetContext.packageName)
                decor in WindowInspector.getGlobalWindowViews() && decor.isAttachedToWindow &&
                    decor.isShown && decor.display?.displayId == Display.DEFAULT_DISPLAY &&
                    decor.hasWindowFocus() && host.hasWindowFocus()
            }
            if (ready) {
                guard("initial-window-ready")
                return
            }
            awaitFrame()
        }
        error("New Range QA Window did not become focused on physical0 within30 frames; no focus requested")
    }

    private fun awaitFrame() {
        val done = CountDownLatch(1)
        val callback = Choreographer.FrameCallback { done.countDown() }
        nativeParityOnMain(instrumentation) { Choreographer.getInstance().postFrameCallback(callback) }
        try { check(done.await(5, TimeUnit.SECONDS)) { "Range initial-readiness frame timed out" } }
        finally { nativeParityOnMain(instrumentation) { Choreographer.getInstance().removeFrameCallback(callback) } }
    }

    private fun recordOnMain(event: String, callbackFocus: Boolean? = null): JSONObject {
        val root = host.fixtureRoot
        val decor = host.window.decorView
        val focused = callbackFocus ?: host.hasWindowFocus()
        if (focused) focusObserved = true else if (focusObserved) focusLostAfterObservation = true
        return JSONObject().put("event", event).put("phase", phase)
            .put("uptime_ms", SystemClock.uptimeMillis()).put("elapsed_nanos", SystemClock.elapsedRealtimeNanos())
            .put("lifecycle", host.lifecycle.currentState.name).put("finishing", host.isFinishing)
            .put("activity_focused", host.hasWindowFocus()).put("root_focused", root.hasWindowFocus())
            .put("root_attached", root.isAttachedToWindow).put("root_shown", root.isShown)
            .put("root_display", root.display?.displayId ?: JSONObject.NULL)
            .put("decor_focused", decor.hasWindowFocus()).put("decor_attached", decor.isAttachedToWindow)
            .put("decor_shown", decor.isShown).put("decor_display", decor.display?.displayId ?: JSONObject.NULL)
            .put("decor_identity", System.identityHashCode(decor))
            .put("decor_registered", decor in WindowInspector.getGlobalWindowViews())
            .put("focus_ever_observed", focusObserved).put("focus_lost_after_observation", focusLostAfterObservation)
            .put("callback_focus", callbackFocus ?: JSONObject.NULL).also { samples.put(it) }
    }

    override fun close() = nativeParityOnMain(instrumentation) {
        if (!closed) {
            closed = true
            try { recordOnMain("observer-removed") }
            finally { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
        }
    }
}

private fun rangeDescendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) repeat(view.childCount) { yieldAll(rangeDescendants(view.getChildAt(it))) }
}
private fun rangeSha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun rangePreferenceDigest(context: android.content.Context) =
    qaPreferenceDigest(getRecorderPreferences(context).all)
private fun writeRangeEvidence(directory: File, result: JSONObject, primary: Throwable?) {
    try {
        writeQaEvidenceAtomically(File(directory, "result.json")) { output ->
            output.write(result.toString(2).toByteArray(Charsets.UTF_8))
        }
    }
    catch (error: Throwable) { if (primary == null) throw error else if (primary !== error) primary.addSuppressed(error) }
}

/** Observation-only projection: each renderer still mutates its own actual state implementation. */
private data class RangeParityState(
    val durationSeconds: Float,
    val startSeconds: Float,
    val endSeconds: Float,
    val selectionDurationExactSeconds: Double,
    val lastTarget: RangeEditTarget,
    val snapshotReady: Boolean,
    val waveformLoading: Boolean,
    val isPlaying: Boolean,
    val isScrubbing: Boolean,
    val selectionDurationEditing: Boolean,
    val coarseWaveformBuiltCount: Int,
    val detailWaveformBuiltCount: Int,
    val coarseWaveform: FloatArray,
    val detailWaveform: FloatArray,
)

private fun RangeExportEditorState.parityState() = RangeParityState(
    durationSeconds = durationSeconds,
    startSeconds = startSeconds,
    endSeconds = endSeconds,
    selectionDurationExactSeconds = selectionDurationExactSeconds,
    lastTarget = lastTarget,
    snapshotReady = snapshotReady,
    waveformLoading = waveformLoading,
    isPlaying = isPlaying,
    isScrubbing = isScrubbing,
    selectionDurationEditing = selectionDurationEditing,
    coarseWaveformBuiltCount = coarseWaveformBuiltCount,
    detailWaveformBuiltCount = detailWaveformBuiltCount,
    coarseWaveform = coarseWaveform,
    detailWaveform = detailWaveform,
)

private fun LegacyRangeExportEditorState.parityState() = RangeParityState(
    durationSeconds = durationSeconds,
    startSeconds = startSeconds,
    endSeconds = endSeconds,
    selectionDurationExactSeconds = selectionDurationExactSeconds,
    lastTarget = lastTarget,
    snapshotReady = snapshotReady,
    waveformLoading = waveformLoading,
    isPlaying = isPlaying,
    isScrubbing = isScrubbing,
    selectionDurationEditing = selectionDurationEditing,
    coarseWaveformBuiltCount = coarseWaveformBuiltCount,
    detailWaveformBuiltCount = detailWaveformBuiltCount,
    coarseWaveform = coarseWaveform,
    detailWaveform = detailWaveform,
)
