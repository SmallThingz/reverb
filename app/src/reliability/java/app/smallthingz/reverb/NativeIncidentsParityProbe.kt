package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AndroidUiDispatcher
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
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full released/native Incidents content, with a fixture-only in-memory history access.
 * The source renderer is the unchanged, reconstruction-guarded IncidentsScreenRenderers.kt.
 * No store, clipboard, acknowledgement, deletion, recorder, permission or system-setting action.
 */
internal fun verifyNativeIncidentsParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-incidents-parity-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val entries = JSONArray()
    val result = JSONObject().put("passed", false).put("cases", entries)
        .put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "14aa05d813ea29785d946e71259df0c3ecfdadb68696b2c60a0118c63610ca00")
        .put("comparison", "Exact ARGB ABAB, text metrics, actual exposed-node payloads; no masks")
        .put("scope", "Full Incidents surface/header/noise and empty/loading/error/populated/stale body")
        .put("insets", "Same parent-consumed safeDrawing viewport; native receives zero remaining status inset")
        .put("motion_scale", 0)
        .put("loading_scope", "Static pinned zero-duration terminal spinner pose only")
        .put("unqualified", JSONArray(listOf("Live spinner timing", "Panel open/close or interruption",
            "Row acknowledgement/menu/Back", "Scroll gestures", "System insets dispatch",
            "Original incident history and storage", "TalkBack delivery")))
    writeIncidentsEvidence(directory, result) // Preserve an initial false receipt before any Activity work.
    var activity: NativeParityActivity? = null
    var board: IncidentsParityBoard? = null
    var failure: Throwable? = null
    var completed = 0
    var failed = 0
    try {
        val host = launchNativeParityActivity(instrumentation).also { activity = it }
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        for (dark in listOf(false, true)) for (scene in IncidentParityScene.entries) {
            requireNativeParityHost(instrumentation, host)
            val name = "${if (dark) "dark-rtl" else "light-ltr"}-${scene.name.lowercase()}"
            val entry = JSONObject().put("name", name).put("passed", false)
            entries.put(entry)
            val current = nativeParityOnMain(instrumentation) {
                IncidentsParityBoard(host, dark, scene)
            }.also { board = it }
            val images = ArrayList<Bitmap>(4)
            val evidence = ArrayList<JSONObject>(4)
            var sceneFailure: Throwable? = null
            try {
                current.awaitReady(instrumentation)
                for (pass in 0..3) {
                    val reference = pass % 2 == 0
                    val label = listOf("released", "native", "released-repeat", "native-repeat")[pass]
                    val image = captureNativeParityContent(instrumentation, host) { current.show(reference) }
                    images += image
                    // Persist the actual pixels before any subsequent evidence operation can fail.
                    saveNativeParityBitmap(directory, "$name-$label.png", image)
                    val view = nativeParityOnMain(instrumentation) { current.shown(reference) }
                    val geometry = nativeParityOnMain(instrumentation) {
                        current.checkState()
                        onboardingGeometryEvidence(view, reference)
                    }
                    val nodes = onboardingNativeNodeEvidence(instrumentation, host, view) { partial ->
                        File(directory, "$name-$label-partial-nodes.json").writeText(partial.toString(2))
                    }
                    val observed = JSONObject().put("geometry", geometry).put("nodes", nodes)
                    evidence += observed
                    File(directory, "$name-$label.json").writeText(observed.toString(2))
                }
                val pixels = compareNativeParityPixels(images[0], images[1])
                val sourceStable = compareNativeParityPixels(images[0], images[2])
                val nativeStable = compareNativeParityPixels(images[1], images[3])
                val geometryEqual = onboardingTextGeometrySignature(evidence[0]) ==
                    onboardingTextGeometrySignature(evidence[1])
                val nodesEqual = onboardingNodeSignature(evidence[0]) == onboardingNodeSignature(evidence[1])
                fun signature(index: Int) = onboardingTextGeometrySignature(evidence[index]) + "\n" +
                    onboardingNodeSignature(evidence[index])
                val stableEvidence = signature(0) == signature(2) && signature(1) == signature(3)
                val passed = pixels.getLong("different_pixels") == 0L &&
                    sourceStable.getLong("different_pixels") == 0L &&
                    nativeStable.getLong("different_pixels") == 0L && geometryEqual && nodesEqual && stableEvidence
                entry.put("pixels", pixels).put("released_stability", sourceStable)
                    .put("native_stability", nativeStable).put("text_geometry_equal", geometryEqual)
                    .put("nodes_equal", nodesEqual).put("evidence_stable", stableEvidence)
                    .put("declared_colors", nativeParityOnMain(instrumentation) { current.colorEvidence() })
                    .put("state", nativeParityOnMain(instrumentation) { current.stateEvidence() })
                    .put("passed", passed)
                completed++
                if (!passed) failed++
                report.appendLine("${if (passed) "PASS" else "FAIL"} native_incidents $name pixels=${pixels.getLong("different_pixels")} nodes=$nodesEqual geometry=$geometryEqual")
            } catch (error: Throwable) {
                sceneFailure = error
                entry.put("error", error.stackTraceToString())
                throw error
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (error: Throwable) {
                        if (sceneFailure != null) {
                            if (sceneFailure !== error) sceneFailure!!.addSuppressed(error)
                        }
                        else if (cleanupFailure == null) cleanupFailure = error
                        else if (cleanupFailure !== error) cleanupFailure!!.addSuppressed(error)
                    }
                }
                images.forEach { image -> cleanup { image.recycle() } }
                board = null
                cleanup { nativeParityOnMain(instrumentation) { current.close() } }
                cleanup { nativeParityOnMain(instrumentation) { current.requireNoAsyncFailure() } }
                cleanup { writeIncidentsEvidence(directory, result) }
                if (sceneFailure == null) cleanupFailure?.let { throw it }
            }
        }
        check(completed == 10 && failed == 0) { "Incidents parity failed: $failed of $completed scenes" }
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        cleanup { nativeParityOnMain(instrumentation) { board?.close(); board = null } }
        activity?.let { host ->
            cleanup { nativeParityOnMain(instrumentation) { host.fixtureRoot.removeAllViews() } }
            cleanup { nativeParityOnMain(instrumentation) { host.finish() } }
        }
        result.put("completed", completed).put("failed", failed)
            .put("passed", failure == null && completed == 10 && failed == 0)
        failure?.let { result.put("failure", it.stackTraceToString()) }
        var evidenceWritten = false
        try {
            writeIncidentsEvidence(directory, result)
            evidenceWritten = true
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            result.put("passed", false).put("failure", checkNotNull(failure).stackTraceToString())
            // Atomic replacement preserves the previous false receipt if the failure write also fails.
            try { writeIncidentsEvidence(directory, result); evidenceWritten = true }
            catch (writeFailure: Throwable) { if (writeFailure !== failure) failure!!.addSuppressed(writeFailure) }
        }
        report.appendLine(if (evidenceWritten) "PARITY_EVIDENCE ${directory.name}/result.json"
            else "PARITY_EVIDENCE_WRITE_FAILED ${directory.name}/result.json")
    }
    failure?.let { throw it }
}

private enum class IncidentParityScene { EMPTY, LOADING, ERROR, POPULATED, STALE }

/** No durable incident path is constructed; only these immutable QA values can be observed. */
private fun incidentParityHistory(): IncidentHistoryPresentation = prepareIncidentHistory(
    listOf(
        RecordingIncident(1_700_000_000_000, resumedAtMillis = 1_700_000_005_000,
            acknowledgedAtMillis = 1_700_000_006_000),
        RecordingIncident(1_700_010_000_000, resumedAtMillis = 0,
            exitReason = android.app.ApplicationExitInfo.REASON_LOW_MEMORY, pid = 101,
            rssKb = 24_576, description = "QA retained audio buffer"),
        RecordingIncident(1_700_020_000_000, kind = RecordingIncidentKind.UNEXPECTED_ERROR,
            description = "QA synthetic error detail"),
    ), Locale.US, ZoneOffset.UTC,
)

private class IncidentsFixtureAccess(private val scene: IncidentParityScene,
                                    private val rows: IncidentHistoryPresentation) : IncidentHistoryAccess {
    override val revisions = MutableStateFlow(0L)
    var mutations = 0
        private set
    override suspend fun readHistory(): IncidentHistoryPresentation = when (scene) {
        IncidentParityScene.LOADING -> awaitCancellation()
        IncidentParityScene.ERROR -> error("QA synthetic unavailable history")
        IncidentParityScene.STALE -> if (revisions.value == 0L) rows else error("QA synthetic stale history")
        IncidentParityScene.EMPTY -> IncidentHistoryPresentation(emptyList())
        IncidentParityScene.POPULATED -> rows
    }
    override fun toggleAcknowledgedInBackground(incident: RecordingIncident) {
        mutations++; error("Static Incidents fixture must not acknowledge")
    }
    override fun deleteInBackground(incident: RecordingIncident) {
        mutations++; error("Static Incidents fixture must not delete")
    }
}

private object IncidentsFixtureMotionScale : MotionDurationScale { override val scaleFactor = 0f }

@OptIn(ExperimentalComposeViewContextApi::class)
private class IncidentsParityBoard(
    private val host: NativeParityActivity,
    private val dark: Boolean,
    private val scene: IncidentParityScene,
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
    private val environment = ReverbViewEnvironment(density, createFontFamilyResolver(context),
        ReverbPalette.resolve(context, dark))
    private val history = incidentParityHistory()
    private val expected = IncidentHistoryUiState(
        if (scene == IncidentParityScene.POPULATED || scene == IncidentParityScene.STALE) history
        else IncidentHistoryPresentation(emptyList()),
        when (scene) {
            IncidentParityScene.LOADING -> IncidentHistoryLoadStatus.LOADING
            IncidentParityScene.ERROR, IncidentParityScene.STALE -> IncidentHistoryLoadStatus.UNAVAILABLE
            else -> IncidentHistoryLoadStatus.READY
        },
    )
    private val access = IncidentsFixtureAccess(scene, history)
    private var asyncFailure: Throwable? = null
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob() +
        IncidentsFixtureMotionScale + CoroutineExceptionHandler { _, failure ->
            val first = asyncFailure
            if (first == null) asyncFailure = failure else if (first !== failure) first.addSuppressed(failure)
        })
    private val frames = checkNotNull(scope.coroutineContext[MonotonicFrameClock])
    private val recomposer = Recomposer(scope.coroutineContext)
    private var recomposerJob: Job? = null
    private val referenceHost = FrameLayout(context)
    private val reference = ComposeView(context)
    private var native: NativeIncidentsScreenView? = null
    private var owner: NativeIncidentsScreenOwner? = null
    private var windowText: NativeWindowTextAccessibilityOwner? = null
    private var scrollEvents: NativeWindowScrollAccessibilityEvents? = null
    private var closed = false
    private var referenceComposed = false
    private var unexpectedCallbacks = 0

    init {
        try {
            host.fixtureRoot.removeAllViews()
            host.fixtureRoot.setBackgroundColor(colors.surface.toArgb())
            val direction = if (dark) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            referenceHost.layoutDirection = direction
            reference.layoutDirection = direction
            referenceHost.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (Build.VERSION.SDK_INT >= 29) referenceHost.isForceDarkAllowed = false
            host.fixtureRoot.addView(referenceHost, FrameLayout.LayoutParams(-1, -1))
            val compositionContext = ComposeViewContext(referenceHost, recomposer)
            recomposerJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }
            reference.setContent {
                CompositionLocalProvider(
                    LocalContext provides context, LocalResources provides context.resources,
                    LocalConfiguration provides configuration, LocalDensity provides density,
                    LocalLayoutDirection provides if (dark) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    MaterialTheme(colorScheme = colors, typography = typography) {
                        IncidentsScreen(expected,
                            onBack = { unexpectedCallbacks++; error("Static Incidents fixture must not navigate") },
                            onToggleAcknowledged = { unexpectedCallbacks++; error("Static fixture must not acknowledge") },
                            onDelete = { unexpectedCallbacks++; error("Static fixture must not delete") },
                            modifier = Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing))
                    }
                }
                androidx.compose.runtime.SideEffect { referenceComposed = true }
            }
            reference.createComposition(compositionContext)
            referenceHost.addView(reference, FrameLayout.LayoutParams(-1, -1))

            val text = NativeWindowTextAccessibilityOwner(host.fixtureRoot,
                NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
            windowText = text
            val events = text.openScrollEvents(scope, { host.fixtureRoot.invalidate() }, {})
            scrollEvents = events
            val source = NativeMainAccessibility(host.fixtureRoot, host.fixtureRoot,
                { listOfNotNull(native) }, { false }, { view ->
                    if (view.parent is ReverbGroupLayout &&
                        (view.parent as ReverbGroupLayout).pointerContent === view)
                        NativeTextSemanticsClip.Rectangle(androidx.compose.ui.geometry.Rect(
                            0f, 0f, view.width.toFloat(), view.height.toFloat()))
                    else NativeTextSemanticsClip.Unclipped
                }, minimumTouch = { view ->
                    val click = view is NativeClickPhaseHost &&
                        (view !is NativeMergedRowAccessibilityHost || view.mergesAccessibilityDescendants)
                    if (click) 48f * density.density else 0f
                })
            val historyOwner = IncidentHistoryController(access, waitBeforeRetry = { awaitCancellation() })
            val panelOwner = NativeIncidentsScreenOwner(context, scope, initiallyOpen = true,
                onBackRequested = { unexpectedCallbacks++; error("Static Incidents fixture must not navigate") },
                onAlertChanged = {}, history = historyOwner)
            owner = panelOwner
            val content = NativeIncidentsScreenView(context, environment, panelOwner, scope,
                host, host.onBackPressedDispatcher, host.fixtureRoot, text, source::node,
                scrollEvents = events)
            native = content
            content.layoutDirection = direction
            host.fixtureRoot.addView(content, FrameLayout.LayoutParams(-1, -1))
            show(true)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun show(compose: Boolean): View {
        check(!closed && host.hasWindowFocus())
        asyncFailure?.let { throw it }
        referenceHost.visibility = if (compose) View.VISIBLE else View.GONE
        native?.visibility = if (compose) View.GONE else View.VISIBLE
        return shown(compose)
    }

    fun shown(compose: Boolean): View = if (compose) reference else checkNotNull(native)

    fun awaitReady(instrumentation: Instrumentation) {
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000L
            while (SystemClock.uptimeMillis() < deadline) {
                requireNativeParityHost(instrumentation, host)
                if (nativeParityOnMain(instrumentation) {
                    asyncFailure?.let { throw it }; condition()
                }) return
                Thread.sleep(16)
            }
            error("Synthetic Incidents state did not become ready: $scene")
        }
        if (scene == IncidentParityScene.STALE) {
            await { owner?.state?.status == IncidentHistoryLoadStatus.READY }
            nativeParityOnMain(instrumentation) { access.revisions.value = 1L }
        }
        await { referenceComposed && owner?.state?.let {
            it.status == expected.status && it.history.rows == expected.history.rows
        } == true }
    }

    fun requireNoAsyncFailure() { asyncFailure?.let { throw it } }

    fun checkState() {
        requireNoAsyncFailure()
        val actual = checkNotNull(owner).state
        check(actual.status == expected.status && actual.history.rows == expected.history.rows)
        check(access.mutations == 0 && unexpectedCallbacks == 0)
        check(checkNotNull(owner).openProgress == 1f)
    }

    fun stateEvidence(): JSONObject {
        checkState()
        return JSONObject().put("status", expected.status.name).put("can_mutate", expected.canMutate)
            .put("row_keys", JSONArray(expected.history.rows.map { it.key }))
            .put("acknowledged", JSONArray(expected.history.rows.map { it.incident.acknowledged }))
            .put("accepted_mutations", access.mutations).put("unexpected_callbacks", unexpectedCallbacks)
            .put("native_open_progress", checkNotNull(owner).openProgress.toDouble())
    }

    /** These exact source expressions are metadata; only raster comparison proves actual paint. */
    fun colorEvidence(): JSONObject = JSONObject()
        .put("surface", colors.surface.toArgb()).put("on_surface", colors.onSurface.toArgb())
        .put("on_surface_variant", colors.onSurfaceVariant.toArgb())
        .put("rows", JSONArray(expected.history.rows.map { row ->
            val alert = !row.incident.acknowledged
            JSONObject().put("key", row.key)
                .put("fill", (if (alert) colors.errorContainer.copy(alpha = .42f) else colors.surfaceContainerHigh).toArgb())
                .put("border", (if (alert) colors.error.copy(alpha = .45f) else colors.outlineVariant).toArgb())
                .put("divider", (if (alert) colors.error else colors.outlineVariant).copy(alpha = .55f).toArgb())
                .put("glyph", (if (alert) colors.error else colors.onSurfaceVariant).toArgb())
        }))

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        release { native?.close() }
        release { owner?.close() }
        release { scrollEvents?.close() }
        release { reference.disposeComposition() }
        release { recomposer.cancel() }
        release { recomposerJob?.cancel() }
        release { scope.cancel() }
        release { host.fixtureRoot.removeView(referenceHost) }
        native?.let { content -> release { host.fixtureRoot.removeView(content) } }
        native = null
        owner = null
        windowText = null
        scrollEvents = null
        release { requireNoAsyncFailure() }
        failure?.let { throw it }
    }
}

/** Publish only complete JSON; an interrupted/failed write cannot replace the previous false receipt. */
private fun writeIncidentsEvidence(directory: File, result: JSONObject) {
    val pending = File(directory, "result.json.pending")
    pending.writeText(result.toString(2))
    check(pending.renameTo(File(directory, "result.json"))) { "Cannot publish complete Incidents evidence" }
}
