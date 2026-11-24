package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.LibraryExpansionMotion
import app.smallthingz.reverb.nativeui.LibraryExpansionViewport
import app.smallthingz.reverb.nativeui.LibraryInlineExpansionHost
import app.smallthingz.reverb.nativeui.LibraryInlineExpansionLease
import app.smallthingz.reverb.nativeui.LibraryNativeListState
import app.smallthingz.reverb.nativeui.LibraryPlayerActions
import app.smallthingz.reverb.nativeui.LibraryPlayerUiState
import app.smallthingz.reverb.nativeui.LibraryRecordingRowActions
import app.smallthingz.reverb.nativeui.ReverbLibraryListView
import app.smallthingz.reverb.nativeui.ReverbLibraryPlayerView
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.SpringFineSeekBinding
import app.smallthingz.reverb.nativeui.SpringFineSeekEnvironment
import app.smallthingz.reverb.nativeui.createReverbSpringFineSeekView
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.reverb.PlayerTrimButtons

/** Actual production motion/viewport pixels, with the former standard-dispatch behavior beside it. */
internal fun verifyNativeLibraryExpansionClipping(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-library-expansion-clipping-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_library_expansion_clipping")
        .put("sdk", Build.VERSION.SDK_INT).put("passed", false).put("cases", cases)
        .put("negative_control", "Former zero-padding standard ViewGroup dispatch and child flags; not an old APK execution")
    var host: NativeParityActivity? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    try {
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val fixture = main {
            ExpansionClipBoard(activity).also {
                check(activity.fixtureRoot.width >= it.boardWidth && activity.fixtureRoot.height >= it.boardHeight)
                activity.fixtureRoot.removeAllViews()
                activity.fixtureRoot.addView(it, FrameLayout.LayoutParams(it.boardWidth, it.boardHeight))
            }
        }
        var time = 1_000_000_000L
        fun pose(name: String, millis: Int, visible: Boolean? = null, scale: Float = 1f) {
            val entry = JSONObject().put("name", name)
            cases.put(entry)
            main {
                visible?.let(fixture.motion::setVisible)
                fixture.layoutPose()
                time += millis * 1_000_000L
                fixture.motion.frame(time, scale)
                fixture.layoutPose()
                entry.put("viewport_height", fixture.viewport.height)
                    .put("layer_top", fixture.viewport.getChildAt(0).top)
                    .put("layer_height", fixture.viewport.getChildAt(0).height)
                    .put("alpha", fixture.motion.alpha.toDouble())
                val bitmap = Bitmap.createBitmap(fixture.boardWidth, fixture.boardHeight, Bitmap.Config.ARGB_8888)
                try {
                    fixture.draw(Canvas(bitmap))
                    saveNativeParityBitmap(directory, "$name-software.png", bitmap)
                    entry.put("software", fixture.verifyPixels(bitmap))
                } finally { bitmap.recycle() }
            }
            val hardwareBefore = main { fixture.productionPaint.hardwareDraws to fixture.controlPaint.hardwareDraws }
            val bitmap = captureNativeParityContent(instrumentation, activity) {
                fixture.productionPaint.invalidate()
                fixture.controlPaint.invalidate()
                fixture.invalidate()
                fixture
            }
            try {
                saveNativeParityBitmap(directory, "$name-hardware.png", bitmap)
                main {
                    // HWUI may cull transparent/empty subtrees. Visible poses must record fresh
                    // hardware draws; culled poses still have the independent PixelCopy oracle.
                    if (fixture.viewport.height > 0 && fixture.motion.alpha > 0.01f) {
                        check(fixture.productionPaint.hardwareDraws > hardwareBefore.first &&
                            fixture.controlPaint.hardwareDraws > hardwareBefore.second)
                    }
                    entry.put("hardware", fixture.verifyPixels(bitmap))
                        .put("production_hardware_draws", fixture.productionPaint.hardwareDraws - hardwareBefore.first)
                        .put("control_hardware_draws", fixture.controlPaint.hardwareDraws - hardwareBefore.second)
                }
            } finally { bitmap.recycle() }
        }
        pose("enter-start", 0, true)
        pose("enter-80", 80)
        pose("enter-160", 80)
        pose("enter-240", 80)
        pose("entered", 160)
        pose("exit-start", 0, false)
        pose("exit-48", 48)
        pose("exit-96", 48)
        pose("reverse-start", 0, true)
        pose("reverse-48", 48)
        pose("reverse-160", 112)
        pose("reopened", 1000)
        pose("zero-exit", 0, false, 0f)
        pose("zero-enter", 0, true, 0f)
        val measured = (0 until cases.length()).map(cases::getJSONObject)
        check(measured.count { it.getInt("layer_top") < 0 && it.getDouble("alpha") > 0.01 } >= 4)
        check(measured.single { it.getString("name") == "zero-exit" }.getInt("viewport_height") == 0)
        check(measured.single { it.getString("name") == "zero-enter" }.getInt("viewport_height") == 160)
        val disposal = JSONObject().also { result.put("trim_player_disposal", it) }
        verifyTrimPlayerDisposal(instrumentation, activity, disposal)
        val retirement = JSONArray().also { result.put("throwing_expansion_retirement", it) }
        verifyThrowingExpansionRetirement(instrumentation, activity, retirement)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { host?.fixtureRoot?.removeAllViews(); host?.finish() } }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("LIBRARY_EXPANSION_CLIPPING_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_library_expansion_clipping software and hardware viewport pixels; enter, exit, reversal, zero scale, old-dispatch negative control, Trim disposal during a committed save, and list retirement after a throwing expansion")
}

/** Uses the actual buttons and commit/terminal owners with a controlled save completion. */
private fun verifyTrimPlayerDisposal(instrumentation: Instrumentation, host: NativeParityActivity, evidence: JSONObject) {
    evidence.put("save_source", "controlled completion of the production commit wrapper").put("passed", false)
    val allowSaveToFinish = CompletableDeferred<Unit>()
    val terminal = CompletableDeferred<Result<RecordingEntity>>()
    val recording = RecordingEntity("qa-trim-disposal:${java.util.UUID.randomUUID()}", "Trim fixture", "audio/wav",
        1, 8_000, 128_044, "PCM", RecordingStorageType.FILE, "/unused", "qa-trim-disposal")
    val state = LibraryPlayerUiState(recording.id, true, false, 8_000, 0, true, false, 0, 7_000,
        InlineFineSeekTarget.TRIM_START, null, true, false, 0f, 1f)
    val fineSeek = SpringFineSeekBinding(true, false, 8f, recording.id, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {})
    requireNativeParityHost(instrumentation, host)
    val (environment, runtime) = nativeParityOnMain(instrumentation) {
        ReverbViewEnvironment(host, false) to NativeModalWindowRuntime(host).also { it.setStarted(true) }
    }
    val rowScope = CoroutineScope(runtime.scope.coroutineContext + Job(runtime.scope.coroutineContext[Job]))
    var player: ReverbLibraryPlayerView? = null
    var saveJob: Job? = null
    var reopenedCancel = false

    fun mount(): ReverbLibraryPlayerView {
        val root = host.fixtureRoot
        val fine = createReverbSpringFineSeekView(host, SpringFineSeekEnvironment(environment.palette,
            environment.textDensity, ViewConfiguration.get(host).scaledTouchSlop.toFloat(), runtime.frames,
            runtime.scope, root, { false }), environment.appearance.springFineSeek)
        return ReverbLibraryPlayerView(host, environment, fine, root, runtime.frames, runtime.scope) { 0f }.also {
            player = it
            root.addView(it, FrameLayout.LayoutParams(-1, -2))
        }
    }
    fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }
    fun buttons(root: View) = views(root).filterIsInstance<NativeMaterialButtonView>().toList().also { check(it.size == 2) }
    fun onSave() {
        check(saveJob == null)
        val reservation = checkNotNull(recordingMutations.tryBegin(recording.id))
        saveJob = rowScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                runCommittedInlineTrim(
                    save = { allowSaveToFinish.await(); recording },
                    onTerminal = { terminal.complete(it) },
                )
            } finally { recordingMutations.finish(reservation) }
        }
    }
    val actions = LibraryPlayerActions({ _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> false }, {}, ::onSave)
    val reopenedActions = actions.copy(cancelTrim = { reopenedCancel = true }, saveTrim = { error("Unexpected second save") })
    var failure: Throwable? = null
    try {
        nativeParityOnMain(instrumentation) {
            host.fixtureRoot.removeAllViews()
            val old = mount()
            old.bind(environment, state, actions, fineSeek)
            val trimControls = views(old).filterIsInstance<PlayerTrimButtons>().single()
            val oldButtons = buttons(trimControls)
            val save = oldButtons.single { it.label.getText() == host.getString(R.string.trim_save_copy) }
            check(oldButtons.all { it.isEnabled } && save.performClick())
            check(recordingMutations.isActive(recording.id) && !terminal.isCompleted)
            old.bind(environment, state.copy(trimSaving = true), actions, fineSeek)
            val spinner = views(trimControls).filterIsInstance<NativeCircularProgress>().single()
            old.release()
            old.release()
            check(trimControls.parent == null && spinner.parent == null) { "Disposed player retained its live Trim controls" }
            old.bind(environment, state, actions, fineSeek)
            check(oldButtons.none { it.isEnabled || it.performClick() }) { "Disposed Trim actions were revived" }
            host.fixtureRoot.removeView(old)
            rowScope.cancel()

            val reopened = mount()
            reopened.bind(environment, state.copy(trimSaving = true), reopenedActions, fineSeek)
            check(buttons(reopened).none { it.isEnabled })
            check(recordingMutations.isActive(recording.id) && !terminal.isCompleted)
            evidence.put("released_controls", true).put("pending_through_reopen", true)
            allowSaveToFinish.complete(Unit)
        }
        runBlocking {
            withTimeout(15_000L) {
                check(terminal.await().getOrThrow() === recording)
                checkNotNull(saveJob).join()
            }
        }
        requireNativeParityHost(instrumentation, host)
        nativeParityOnMain(instrumentation) {
            val reopened = checkNotNull(player)
            check(!recordingMutations.isActive(recording.id))
            reopened.bind(environment, state, reopenedActions, fineSeek)
            val freshButtons = buttons(reopened)
            val cancel = freshButtons.single { it.label.getText() == host.getString(R.string.cancel) }
            check(freshButtons.all { it.isEnabled } && cancel.performClick() && reopenedCancel)
            evidence.put("terminal_completed", true).put("reopened_controls_interactive", true)
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        allowSaveToFinish.complete(Unit)
        failure = captureNativeParityCleanup(failure) {
            runBlocking { withTimeout(15_000L) { saveJob?.join() } }
        }
        failure = captureNativeParityCleanup(failure) {
            nativeParityOnMain(instrumentation) {
                try { player?.release() } finally {
                    try { player?.let(host.fixtureRoot::removeView) } finally { runtime.close() }
                }
            }
        }
    }
    failure?.let { throw it }
    evidence.put("passed", true)
}

/** The expansion host may fail after retirement. Actual RecyclerView holders must still unbind. */
private fun verifyThrowingExpansionRetirement(instrumentation: Instrumentation, host: NativeParityActivity,
                                              evidence: JSONArray) {
    val first = RecordingEntity("qa-throwing-expansion-1", "First row", "audio/wav", 1, 1_000, 16_044,
        "PCM", RecordingStorageType.FILE, "/unused", "qa-first")
    val second = first.copy(id = "qa-throwing-expansion-2", displayName = "Second row", fileIdentity = "qa-second")
    val state = LibraryNativeListState(listOf(LibraryListItem.Recording(first), LibraryListItem.Recording(second)),
        emptySet(), false, emptySet(), emptySet(), InlinePlaybackRowOwner(first.id), null, true)
    for (dispose in listOf(false, true)) {
        requireNativeParityHost(instrumentation, host)
        val expected = IllegalStateException("Expansion lease retirement failed")
        var closeCalls = 0
        val runtime = nativeParityOnMain(instrumentation) { NativeModalWindowRuntime(host).also { it.setStarted(true) } }
        var list: ReverbLibraryListView? = null
        var failure: Throwable? = null
        try {
            val mounted = nativeParityOnMain(instrumentation) {
                val inline = object : LibraryInlineExpansionHost {
                    override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease =
                        object : LibraryInlineExpansionLease {
                            override val view = object : View(context) {
                                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 1)
                            }
                            override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
                                              screenActive: Boolean, trimRequested: Boolean) = Unit
                            override fun setEnvironment(environment: ReverbViewEnvironment) = Unit
                            override fun setPresentationAttached(attached: Boolean) = Unit
                            override fun closePresentation() { closeCalls++; throw expected }
                        }
                }
                ReverbLibraryListView(host, ReverbViewEnvironment(host, false), runtime.scope, host.fixtureRoot,
                    LibraryRecordingRowActions(null, null, null), inline, {}).also {
                    list = it
                    host.fixtureRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
                    it.submit(state)
                }
            }
            awaitNativeParityDraw(instrumentation, mounted)
            nativeParityOnMain(instrumentation) {
                val firstRow = checkNotNull(mounted.attachedRecordingAnchor(first.id))
                val secondRow = checkNotNull(mounted.attachedRecordingAnchor(second.id))
                val adapter = checkNotNull(mounted.scrollView.adapter)
                check(firstRow.isShown && secondRow.isShown && adapter.itemCount == 3)
                val observed = runCatching {
                    if (dispose) mounted.dispose() else mounted.clearListComposition()
                }.exceptionOrNull()
                check(observed === expected && closeCalls == 1)
                check(firstRow.visibility == View.INVISIBLE && secondRow.visibility == View.INVISIBLE)
                check(mounted.attachedRecordingAnchor(first.id) == null && mounted.attachedRecordingAnchor(second.id) == null)
                check(adapter.itemCount == 0 && (mounted.scrollView.adapter == null) == dispose)
                evidence.put(JSONObject().put("dispose", dispose).put("lease_close_count", closeCalls)
                    .put("both_rows_unbound", true).put("adapter_empty", true).put("primary_preserved", true))
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            failure = captureNativeParityCleanup(failure) {
                nativeParityOnMain(instrumentation) {
                    try { list?.dispose() } finally {
                        try { list?.let(host.fixtureRoot::removeView) } finally { runtime.close() }
                    }
                }
            }
        }
        failure?.let { throw it }
    }
}

private class ExpansionClipPaint(context: Context) : View(context) {
    private val paint = Paint().apply { color = Color.MAGENTA }
    var hardwareDraws = 0
        private set
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(160, widthMeasureSpec), resolveSize(160, heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) hardwareDraws++
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

private class ExpansionClipBoard(context: Context) : FrameLayout(context) {
    val boardWidth = 384
    val boardHeight = 360
    val motion = LibraryExpansionMotion(false)
    val productionPaint = ExpansionClipPaint(context)
    val controlPaint = ExpansionClipPaint(context)
    val viewport = LibraryExpansionViewport(context, { motion }, {}).apply { setContent(productionPaint) }
    private val controlLayer = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        addView(controlPaint, LayoutParams(-1, -2))
    }
    // Same child hierarchy and clipping flags as the former viewport, without its new dispatch clip.
    // Placement is copied from the measured production layer, so this is not a second motion model.
    private val control = object : ViewGroup(context) {
        init { clipChildren = true; clipToPadding = true; addView(controlLayer) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            controlLayer.measure(MeasureSpec.makeMeasureSpec(160, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(160, MeasureSpec.EXACTLY))
            setMeasuredDimension(viewport.measuredWidth, viewport.measuredHeight)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val actual = viewport.getChildAt(0)
            controlLayer.layout(actual.left, actual.top, actual.right, actual.bottom)
        }
    }
    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.WHITE)
        addView(viewport, LayoutParams(160, -2).apply { leftMargin = 16; topMargin = 180 })
        addView(control, LayoutParams(160, -2).apply { leftMargin = 208; topMargin = 180 })
    }
    fun layoutPose() {
        viewport.forceLayout()
        control.forceLayout()
        forceLayout()
        measure(MeasureSpec.makeMeasureSpec(boardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(boardHeight, MeasureSpec.EXACTLY))
        layout(0, 0, boardWidth, boardHeight)
        controlLayer.alpha = motion.alpha
        check(viewport.width == 160 && viewport.getChildAt(0).height == 160)
        check(control.width == viewport.width && control.height == viewport.height)
        check(viewport.getChildAt(0).top == viewport.height - 160)
        check(paddingTop == 0 && !clipChildren && !clipToPadding)
        invalidate()
    }
    fun verifyPixels(bitmap: Bitmap): JSONObject {
        check(bitmap.width == boardWidth && bitmap.height == boardHeight)
        fun counts(left: Int): Pair<Int, Int> {
            var inside = 0
            var outside = 0
            for (y in 0 until boardHeight) for (x in left until left + 160) {
                if (bitmap.getPixel(x, y) == Color.WHITE) continue
                if (y >= 180 && y < 180 + viewport.height) inside++ else outside++
            }
            return inside to outside
        }
        val (inside, outside) = counts(16)
        val (controlInside, controlOutside) = counts(208)
        val negativeVisible = viewport.getChildAt(0).top < 0 && motion.alpha > 0.01f
        check(outside == 0) { "Production viewport painted $outside pixels outside its animated bounds" }
        if (viewport.height > 0 && motion.alpha > 0.01f) check(inside > 0 && controlInside > 0)
        if (negativeVisible) check(controlOutside > 0) { "Negative control did not reproduce the old dispatch overflow" }
        if (viewport.height == 0) check(inside == 0 && outside == 0)
        return JSONObject().put("inside_painted", inside).put("outside_painted", outside)
            .put("control_inside_painted", controlInside).put("control_outside_painted", controlOutside)
            .put("negative_control_required", negativeVisible)
    }
}
