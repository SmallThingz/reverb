package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.View
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
        .put("cases", entries).put("passed", false)
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
    private val referenceSnapshot: ReverbService.TimelineSnapshot,
    private val nativeSnapshot: ReverbService.TimelineSnapshot,
) : AutoCloseable {
    val name = (if (dark) "dark" else "light") + (if (compact) "-compact" else "-regular")
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
    private val environment = ReverbViewEnvironment(density, createFontFamilyResolver(context),
        ReverbPalette.resolve(context, dark))
    private val height = if (compact) (420 * density.density).roundToInt() else host.fixtureRoot.height
    private val width = host.fixtureRoot.width
    private val source = RangeMorphSourceGeometry(0f, 0f, width * .5f, height * .5f, 180 * density.density)
    private val referenceHost = FrameLayout(context)
    private val reference = ComposeView(context)
    private val nativeHost = FrameLayout(context)
    private val composeContext: ComposeViewContext
    private val blob = AudioBlobController()
    private var referenceState: RangeExportEditorState? = null
    private var referenceScope: CoroutineScope? = null
    private var nativeScope: CoroutineScope? = null
    private var nativeOwner: NativeRangeExportOwner? = null
    private var native: ReverbRangeExportContent? = null
    private var nativeFrame: NativeRangeExportPresentation? = null
    private var closed = false

    init {
        try {
            check(height > 0 && height <= host.fixtureRoot.height)
            host.fixtureRoot.removeAllViews()
            host.fixtureRoot.setBackgroundColor(colors.surface.toArgb())
            for (view in listOf(referenceHost, nativeHost)) {
                view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (Build.VERSION.SDK_INT >= 29) view.isForceDarkAllowed = false
                view.setBackgroundColor(colors.surface.toArgb())
                host.fixtureRoot.addView(view, FrameLayout.LayoutParams(width, height))
            }
            nativeHost.visibility = View.GONE
            composeContext = ComposeViewContext(referenceHost)
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
                                snapshot = referenceSnapshot, fixtureStateObserver = { referenceState = it },
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
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun show(compose: Boolean): View {
        check(!closed && host.hasWindowFocus())
        if (!compose && native == null) {
            val inherited = checkNotNull(referenceScope) { "Released reference scope is not ready" }
            val scope = CoroutineScope(inherited.coroutineContext + SupervisorJob(inherited.coroutineContext[Job]))
            nativeScope = scope
            val owner = NativeRangeExportOwner(context, host, ReverbService.BufferSlot.ONE_SHOT, 8f,
                scope, true, ::renderNative)
            nativeOwner = owner
            val content = ReverbRangeExportContent(context, environment, owner.state, host.lifecycle,
                scope, host.fixtureRoot, { host.fixtureRoot }) { waveform ->
                val rootLocation = IntArray(2).also(host.fixtureRoot::getLocationInWindow)
                val location = IntArray(2).also(waveform::getLocationInWindow)
                val left = (location[0] - rootLocation[0]).toFloat()
                val top = (location[1] - rootLocation[1]).toFloat()
                owner.waveformMeasured(Rect(left, top, left + waveform.width, top + waveform.height))
            }
            native = content
            val side = (18 * density.density).roundToInt()
            nativeHost.addView(content, FrameLayout.LayoutParams((width - side - side).coerceAtLeast(0), height)
                .apply { leftMargin = side; rightMargin = side })
            owner.bind(nativeSnapshot, source, 6.0, 0f, true, ::renderNative)
        }
        referenceHost.visibility = if (compose) View.VISIBLE else View.GONE
        nativeHost.visibility = if (compose) View.GONE else View.VISIBLE
        return content(compose)
    }
    private fun renderNative(state: RangeExportEditorState, frame: NativeRangeExportPresentation) {
        check(!state.isPlaying && !state.isScrubbing)
        nativeFrame = frame
        native?.bind(environment, frame, ReverbService.BufferSlot.ONE_SHOT,
            { error("No Close action") }, { error("No export action") })
    }
    fun content(reference: Boolean): View = if (reference) referenceHost else nativeHost
    fun state(reference: Boolean) = if (reference) checkNotNull(referenceState) else checkNotNull(nativeOwner).state
    fun ready(reference: Boolean): Boolean {
        val state = if (reference) referenceState else nativeOwner?.state
        if (state == null || !state.snapshotReady || state.waveformLoading ||
            state.coarseWaveformBuiltCount != state.coarseWaveform.size ||
            state.detailWaveformBuiltCount != state.detailWaveform.size) return false
        check(!state.isPlaying && !state.isScrubbing && !state.selectionDurationEditing)
        if (!reference) {
            val frame = nativeFrame ?: return false
            if (!frame.interactionReady || frame.chromeAlpha != 1f || frame.waveformFrame.morphProgress != 1f ||
                frame.waveformFrame.visibleCoarseFraction != 1f || frame.waveformFrame.visibleDetailFraction != 1f) return false
        }
        val wheels = rangeDescendants(content(reference)).filterIsInstance<RangeDurationWheelView>().toList()
        check(wheels.size == if (compact) 0 else 1)
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
        val wheel = rangeDescendants(content).filterIsInstance<RangeDurationWheelView>().singleOrNull()
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
    }
    fun resizeWithWheel(reference: Boolean, seconds: Float): JSONObject {
        check(!compact && host.hasWindowFocus() && ready(reference))
        val wheel = rangeDescendants(content(reference)).filterIsInstance<RangeDurationWheelView>().single()
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
    override fun close() {
        if (closed) return
        closed = true
        var error: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (next: Throwable) {
            if (error == null) error = next else if (error !== next) error!!.addSuppressed(next)
        } }
        release { native?.close() }
        release { nativeOwner?.close() }
        release { nativeScope?.cancel() }
        release(reference::disposeComposition)
        release(host.fixtureRoot::removeAllViews)
        error?.let { throw it }
    }
}

private fun awaitRangeReady(instrumentation: Instrumentation, host: NativeParityActivity,
    board: RangeParityBoard, reference: Boolean, trace: RangeHostTrace) {
    val deadline = SystemClock.uptimeMillis() + 15_000
    var completeAt: Long? = null
    var finalFrames = 0
    while (SystemClock.uptimeMillis() < deadline) {
        trace.guard("${board.name}-range-ready reference=$reference")
        val ready = nativeParityOnMain(instrumentation) { board.ready(reference) }
        if (ready) {
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
                check(decor.context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
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
    rangeSha256(getRecorderPreferences(context).all.toSortedMap().toString().toByteArray(Charsets.UTF_8))
private fun writeRangeEvidence(directory: File, result: JSONObject, primary: Throwable?) {
    try { File(directory, "result.json").writeText(result.toString(2)) }
    catch (error: Throwable) { if (primary == null) throw error else primary.addSuppressed(error) }
}
