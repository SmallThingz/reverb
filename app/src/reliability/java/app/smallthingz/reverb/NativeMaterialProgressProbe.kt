package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import app.smallthingz.reverb.nativeui.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Synthetic presentation only: no repository, recording, microphone, or save submission. */
internal fun verifyNativeMaterialProgress(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-material-progress-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val result = JSONObject().put("passed", false).put("historical_parity_claim", false)
        .put("scope", "Actual pending recording row and RecyclerView; synthetic presentation only")
        .put("unqualified", JSONArray(listOf("Home durable acknowledgement integration", "Library navigation controller")))
    val samples = JSONArray()
    result.put("fill_samples", samples)
    val host = launchCurrentUiSmokeActivity(instrumentation)
    val runtime = nativeParityOnMain(instrumentation) { NativeAboutWindowRuntime(host).apply { setStarted(true) } }
    val environment = nativeParityOnMain(instrumentation) {
        nativeParityEnvironment(host,
            androidx.compose.ui.unit.Density(host.resources.displayMetrics.density, host.resources.configuration.fontScale),
            ReverbPalette.resolve(host, false))
    }
    var pending: LibraryPendingSaveRowPresentation? = null
    var list: ReverbLibraryListView? = null
    var customScroll: NativeLibraryScrollOwner? = null
    var failure: Throwable? = null
    var admittedScale = 0f
    fun draw() { awaitNativeParityDraw(instrumentation, host.fixtureRoot); instrumentation.waitForIdleSync() }
    fun capture(name: String, view: View) {
        val bitmap = captureNativeParityContent(instrumentation, host) { view }
        try { saveNativeParityBitmap(directory, "$name.png", bitmap) } finally { bitmap.recycle() }
    }
    try {
        admittedScale = nativeParityOnMain(instrumentation) { reverbAnimatorDurationScale(host) }
        check(admittedScale == 0f || admittedScale == 1f) {
            "Probe admits animator scale 0 or 1 only; observed $admittedScale (device setting unchanged)"
        }
        result.put("animator_duration_scale", admittedScale.toDouble())
        val row = nativeParityOnMain(instrumentation) {
            LibraryPendingSaveRowPresentation(host, environment, runtime.scope, host.fixtureRoot).also {
                pending = it
                it.setPresentationActive(true)
                it.bind(PendingSave("qa-progress", "Materializing recording", 1_700_000_000_000L,
                    80_000L, SavePhase.SAVING, 0f, codecSummary = "FLAC"), environment,
                    ReverbRecordingGroupEdges(true, true))
                host.fixtureRoot.addView(it.row, FrameLayout.LayoutParams(-1, -2))
            }
        }
        repeat(3) { draw() }
        val field = ReverbGroupLayout::class.java.getDeclaredField("progressFraction").apply { isAccessible = true }
        val driver = LibraryPendingSaveRowPresentation::class.java.getDeclaredField("driver").apply { isAccessible = true }
        nativeParityOnMain(instrumentation) {
            row.bind(PendingSave("qa-progress", "Materializing recording", 1_700_000_000_000L,
                80_000L, SavePhase.SAVING, .8f, codecSummary = "FLAC"), environment,
                ReverbRecordingGroupEdges(true, true))
            check(row.row.paragraphLeaves.none { it.getText().contains('%') })
        }
        var previous = 0f
        var intermediate = false
        val fillDeadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        do {
            draw()
            val value = nativeParityOnMain(instrumentation) {
                check(reverbAnimatorDurationScale(host) == admittedScale) { "Animator scale changed during probe" }
                field.getFloat(row.row.summarySurface)
            }
            check(value >= previous - .00001f && value <= .80001f)
            if (value > .0001f && value < .799f) intermediate = true
            samples.put(value.toDouble())
            previous = value
        } while (previous < .7995f && android.os.SystemClock.elapsedRealtime() < fillDeadline)
        if (admittedScale != 0f) check(intermediate) { "No intermediate fill frame observed" }
        check(previous >= .7995f) { "Fill did not settle within admitted-scale deadline" }
        capture("materializing", row.row)
        nativeParityOnMain(instrumentation) {
            row.setPresentationActive(false)
            check(driver.get(row) == null)
            row.setPresentationActive(true)
            row.row.visibility = View.INVISIBLE
            check(driver.get(row) == null)
            row.row.visibility = View.VISIBLE
            host.fixtureRoot.removeView(row.row)
            check(driver.get(row) == null)
            row.close()
            pending = null
        }
        val scrolling = nativeParityOnMain(instrumentation) {
            ReverbLibraryListView(host, environment, runtime.scope, host.fixtureRoot,
                LibraryRecordingRowActions(null, null, null), object : LibraryInlineExpansionHost {
                    override fun create(context: android.content.Context, recordingId: String): LibraryInlineExpansionLease =
                        error("Header fixture cannot create a player")
                }, onRetryFailedSaves = { error("Header fixture cannot retry a save") }).also {
                    list = it
                    host.fixtureRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
                    it.submit(LibraryNativeListState((0 until 400).map { index -> LibraryListItem.Header("Section $index") },
                        emptySet(), false, emptySet(), emptySet(), null, null, true))
                }
        }
        repeat(3) { draw() }
        var sideEffectPixels = 0f
        val scrollOwner = nativeParityOnMain(instrumentation) {
            scrolling.scrollView.scrollBy(0, host.fixtureRoot.height)
            val nested = object : NativeVerticalNestedHost {
                override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
                override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float {
                    if (source == NativeVerticalScrollSource.SIDE_EFFECT) sideEffectPixels += kotlin.math.abs(consumedY)
                    return 0f
                }
                override suspend fun preFling(availableY: Float) = 0f
                override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
            }
            NativeLibraryScrollOwner(scrolling.scrollView, environment, runtime.frames, runtime.scope,
                nested, { false }).also { customScroll = it }
        }
        repeat(3) { draw() }
        val old = nativeParityOnMain(instrumentation) { scrolling.savePosition() }
        check(old.firstVisibleItemIndex > 0)
        val sinkField = NativeLibraryScrollOwner::class.java.getDeclaredField("sink").apply { isAccessible = true }
        val mutationField = NativeLibraryScrollOwner::class.java.getDeclaredField("mutating").apply { isAccessible = true }
        val scopeField = NativeLibraryScrollOwner::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val sink = nativeParityOnMain(instrumentation) { sinkField.get(scrollOwner) as NativeDragSink }
        nativeParityOnMain(instrumentation) {
            sink.start(smallthingz.ui.geometry.Offset.Zero)
            sink.delta(smallthingz.ui.geometry.Offset(0f, -16f))
            sink.stop(smallthingz.ui.unit.Velocity(0f, -8_000f))
        }
        val flingDeadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        do {
            draw()
            val moving = nativeParityOnMain(instrumentation) { sideEffectPixels > 0f }
        } while (!moving && android.os.SystemClock.elapsedRealtime() < flingDeadline)
        val pixelsAtReset = nativeParityOnMain(instrumentation) {
            check(sideEffectPixels > 0f && mutationField.getBoolean(scrollOwner)) { "Fixture did not hold a live custom fling" }
            val oldScope = scopeField.get(scrollOwner) as kotlinx.coroutines.CoroutineScope
            scrolling.openAtTop()
            check(oldScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == false)
            check(scopeField.get(scrollOwner) !== oldScope)
            sideEffectPixels
        }
        repeat(3) { draw() }
        val stableDeadline = android.os.SystemClock.elapsedRealtime() + 750L
        do {
            draw()
            nativeParityOnMain(instrumentation) {
                check(scrolling.savePosition() == NativeListSavedPosition(0, 0)) { "Retired custom fling moved the reopened Library" }
                check(sideEffectPixels == pixelsAtReset)
            }
        } while (android.os.SystemClock.elapsedRealtime() < stableDeadline)
        // The retained owner must admit a fresh drag, and opening must also cancel an active drag.
        nativeParityOnMain(instrumentation) {
            sink.start(smallthingz.ui.geometry.Offset.Zero)
            sink.delta(smallthingz.ui.geometry.Offset(0f, -80f))
        }
        repeat(3) { draw() }
        nativeParityOnMain(instrumentation) {
            check(scrolling.savePosition() != NativeListSavedPosition(0, 0))
            check(mutationField.getBoolean(scrollOwner))
            scrolling.openAtTop()
            sink.delta(smallthingz.ui.geometry.Offset(0f, -80f))
            sink.stop(smallthingz.ui.unit.Velocity(0f, -8_000f))
        }
        repeat(3) { draw() }
        val top = nativeParityOnMain(instrumentation) { scrolling.savePosition() }
        check(top == NativeListSavedPosition(0, 0))
        val actual = nativeParityOnMain(instrumentation) {
            (scrolling.scrollView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        }
        check(actual == 0)
        result.put("live_custom_fling_cancelled", true).put("active_drag_cancelled", true)
            .put("fresh_drag_after_reset", true).put("post_reset_stable_millis", 750)
        capture("library-top", scrolling)
        nativeParityOnMain(instrumentation) {
            scrollOwner.close()
            customScroll = null
            host.fixtureRoot.removeView(scrolling)
            scrolling.dispose()
            list = null
        }
        verifyLibraryBoundaryKeyboard(instrumentation, host, environment, result)
        verifyMaterialHomePending(instrumentation, host, environment, runtime, directory, result, admittedScale)
        result.put("old_index", old.firstVisibleItemIndex).put("top_index", actual)
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { customScroll?.close() } }
        failure = captureNativeParityCleanup(failure) {
            nativeParityOnMain(instrumentation) { pending?.let { host.fixtureRoot.removeView(it.row); it.close() } }
        }
        failure = captureNativeParityCleanup(failure) {
            nativeParityOnMain(instrumentation) { list?.let { host.fixtureRoot.removeView(it); it.dispose() } }
        }
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { runtime.close() } }
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { host.finish() } }
        failure = captureNativeParityCleanup(failure) {
            check(getRecorderPreferences(context).all == preferences) { "Fixture changed recorder preferences" }
        }
        result.put("passed", failure == null)
        failure?.let { result.put("error", android.util.Log.getStackTraceString(it)) }
        File(directory, "result.json").writeText(result.toString(2))
    }
    failure?.let { throw it }
    report.appendLine("PASS native_material_progress_current evidence ${directory.absolutePath}")
}

/** Actual private production card, driven by the production UI gate; no Service durability claim. */
private fun verifyMaterialHomePending(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    environment: ReverbViewEnvironment,
    runtime: NativeAboutWindowRuntime,
    directory: File,
    result: JSONObject,
    admittedScale: Float,
) {
    val type = Class.forName("app.smallthingz.reverb.nativeui.CaptureSaveStatusView")
    val bind = type.getDeclaredMethod("bind", CaptureSaveStatus::class.java, ReverbViewEnvironment::class.java)
        .apply { isAccessible = true }
    val close = type.getDeclaredMethod("close").apply { isAccessible = true }
    var card: View? = null
    var saving = true
    var status: CaptureSaveStatus? = CaptureSaveStatus.Saving(false)
    var error: String? = null
    lateinit var gate: SaveUiCallbackGate
    var failure: Throwable? = null
    try {
        nativeParityOnMain(instrumentation) {
            val clips = NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }
            val text = NativeWindowTextAccessibilityOwner(host.fixtureRoot, clips)
            val geometry = NativePlainTextViewGeometry(host.fixtureRoot, clips)
            val buttons = NativeMaterialButtonHost(environment, host.fixtureRoot, { false }, text.textWindow, geometry,
                sourceFacts = { view, _ ->
                    val bounds = android.graphics.Rect()
                    val visible = view.getGlobalVisibleRect(bounds)
                    NativeSourceAccessibilityNode(view.isAttachedToWindow, false, visible, bounds)
                }, registerTextClip = { AutoCloseable {} },
                createHit = { view, nodes, children, clip -> object : NativeViewPointerHitLayout {
                    override val placed get() = view.isAttachedToWindow && view.visibility == View.VISIBLE
                    override val size get() = smallthingz.ui.geometry.Size(view.width.toFloat(), view.height.toFloat())
                    override val minimumTouchTarget get() = smallthingz.ui.geometry.Size.Zero
                    override val nodes = nodes
                    override val childrenInHitOrder get() = children()
                    override fun screenToLocal(screen: smallthingz.ui.geometry.Offset) = screen
                    override fun withinLayerBounds(local: smallthingz.ui.geometry.Offset) = clip(local)
                } })
            val constructor = type.declaredConstructors.single().apply { isAccessible = true }
            val view = constructor.newInstance(host, environment, buttons, runtime.scope, {}) as View
            card = view
            host.fixtureRoot.addView(view, FrameLayout.LayoutParams(-1, -2))
            gate = SaveUiCallbackGate(
                setSaving = { saving = it },
                onStatus = { status = it; bind.invoke(view, it, environment) },
                onError = { error = it }, onSaved = {},
            )
            bind.invoke(view, status, environment)
            check(view.visibility == View.VISIBLE) { "Saving cue was deferred" }
        }
        var heldFrames = 0
        val holdDeadline = android.os.SystemClock.elapsedRealtime() + 1_500L
        do {
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
            instrumentation.waitForIdleSync()
            nativeParityOnMain(instrumentation) {
                check(reverbAnimatorDurationScale(host) == admittedScale) { "Animator scale changed during probe" }
                status = reconcileCaptureExportStatus(false, gate.attached, status, false)
                saving = captureExportUiBusy(false, gate.attached, status)
                bind.invoke(card, status, environment)
                check(saving && card!!.visibility == View.VISIBLE)
            }
            heldFrames++
        } while (android.os.SystemClock.elapsedRealtime() < holdDeadline)
        val bitmap = captureNativeParityContent(instrumentation, host) { checkNotNull(card) }
        try { saveNativeParityBitmap(directory, "home-before-queued.png", bitmap) } finally { bitmap.recycle() }
        nativeParityOnMain(instrumentation) { gate.queued(); check(!saving && status == null) }
        val exitDeadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        do {
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
            instrumentation.waitForIdleSync()
            val gone = nativeParityOnMain(instrumentation) {
                check(reverbAnimatorDurationScale(host) == admittedScale) { "Animator scale changed during probe" }
                card!!.visibility == View.GONE
            }
        } while (!gone && android.os.SystemClock.elapsedRealtime() < exitDeadline)
        nativeParityOnMain(instrumentation) { check(card!!.visibility == View.GONE) }
        result.put("home_actual_card", true).put("home_held_frames", heldFrames)
            .put("home_synthetic_gate_only", true).put("home_service_end_to_end", false)
        // A failed admission must take the error route and retire pending, never render Saved.
        nativeParityOnMain(instrumentation) {
            saving = true
            status = CaptureSaveStatus.Saving(false)
            bind.invoke(card, status, environment)
            val failure = SaveUiCallbackGate({ saving = it }, { status = it; bind.invoke(card, it, environment) },
                { error = it }, { throw AssertionError("Failure reported saved") })
            check(failure.failed("Synthetic admission failure"))
            check(!saving && status == null && error == "Synthetic admission failure")
        }
        result.put("home_failure_callback", true).put("home_failure_sheet_rendered", false)
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) {
            card?.let { host.fixtureRoot.removeView(it); close.invoke(it) }
        } }
    }
    failure?.let { throw it }
}
