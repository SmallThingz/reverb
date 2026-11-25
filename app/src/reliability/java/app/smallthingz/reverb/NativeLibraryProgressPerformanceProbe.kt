package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeModalWindowRuntime
import kotlin.coroutines.CoroutineContext

internal const val LIBRARY_PROGRESS_PERF_SELECTOR = "native_library_progress_performance"
internal const val LIBRARY_DISPOSAL_MEMORY_SELECTOR = "native_library_disposal_memory"
private const val PROGRESS_STEPS = 30
private const val PROGRESS_CADENCE_MS = 150L
private const val PROGRESS_BUDGET_MS = 70_000L // Leave the host claim time for final cleanup/evidence.

/** QA-only synchronous production controller/adapter measurement; no repository flow or save IO. */
internal fun verifyNativeLibraryProgressPerformance(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val started = SystemClock.elapsedRealtime()
    fun budget(reserve: Long = 0L) = check(SystemClock.elapsedRealtime() - started + reserve < PROGRESS_BUDGET_MS) {
        "Library measurement budget exhausted; preserve partial evidence, no automatic retry"
    }
    val directory = File(context.filesDir, "library-progress-performance-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val blocks = JSONArray()
    val result = JSONObject().put("selector", LIBRARY_PROGRESS_PERF_SELECTOR).put("passed", false)
        .put("workload_revision", "actual-controller-prefix-v2").put("pid", Process.myPid())
        .put("requested_updates_per_block", PROGRESS_STEPS).put("cadence_ms", PROGRESS_CADENCE_MS)
        .put("scope", "Synthetic immutable metadata; actual synchronous controller deliver and RecyclerView; async effects parked")
        .put("control", "B flattens current controller output to a fresh plain List inside timing; not an old APK/controller")
        .put("timing_scope", "Main-thread synchronous delivery CPU/wall; later layout/draw measured separately; not input latency")
        .put("memory_scope", "Process-wide counters include QA/framework work; no forced GC or absolute RAM pass gate")
        .put("blocks", blocks)
    var host: NativeParityActivity? = null
    var failure: Throwable? = null
    val worker = HandlerThread("Library QA frames").apply { start() }
    val handler = Handler(worker.looper)
    try {
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        requireNativeParityHost(instrumentation, activity, "library-perf-start")
        val initialConfig = nativeParityOnMain(instrumentation) { progressConfiguration(activity) }
        result.put("configuration_before", initialConfig).put("host_before", progressMemory())
        // Six candidate cells; the N1000 visible cell is expanded to ABBA (nine blocks total).
        val cells = listOf(Triple(0, true, false), Triple(0, false, false),
            Triple(100, true, false), Triple(100, false, false), Triple(1000, false, false),
            Triple(1000, true, false), Triple(1000, true, true), Triple(1000, true, true),
            Triple(1000, true, false))
        cells.forEachIndexed { ordinal, (count, active, control) ->
            budget(7_000)
            requireNativeParityHost(instrumentation, activity, "library-perf-block-$ordinal")
            val entry = JSONObject().put("ordinal", ordinal).put("finished_count", count)
                .put("active", active).put("arm", if (control) "B-flat-list-control" else "A-production")
                .put("passed", false).put("final_draw_observed", false)
            blocks.put(entry)
            var fixture: ProgressPerfFixture? = null
            val frames = ProgressPerfFrames(activity.window, handler)
            val wall = LongArray(PROGRESS_STEPS)
            val cpu = LongArray(PROGRESS_STEPS)
            val triggers = LongArray(PROGRESS_STEPS)
            var delivered = 0
            var blockFailure: Throwable? = null
            try {
                val mountStart = System.nanoTime()
                nativeParityOnMain(instrumentation) {
                    val created = ProgressPerfFixture(activity, count, active, control)
                    fixture = created // Publish cleanup ownership before attachment or first delivery can throw.
                    activity.fixtureRoot.removeAllViews()
                    activity.fixtureRoot.addView(created.view, FrameLayout.LayoutParams(-1, -1))
                    created.deliver(0)
                }
                val current = checkNotNull(fixture)
                awaitNativeParityDraw(instrumentation, current.view)
                entry.put("fixture_mount_to_observed_draw_ns", System.nanoTime() - mountStart)
                    .put("mount_scope", "Fresh synthetic fixture construction, initial submission and observed draw; includes metadata setup")
                SystemClock.sleep(200)
                nativeParityOnMain(instrumentation) {
                    current.begin()
                    // A physically hidden retained list still receives controller progress.
                    if (!active) current.view.visibility = View.INVISIBLE
                    entry.put("list_shown_during_block", current.view.isShown)
                    frames.attach()
                }
                entry.put("before", progressMemory())
                repeat(PROGRESS_STEPS) { step ->
                    budget(2_000)
                    frames.requireNoOverflow()
                    requireNativeParityHost(instrumentation, activity, "library-perf-$ordinal-$step")
                    nativeParityOnMain(instrumentation) {
                        val start = System.nanoTime()
                        if (step == 0) frames.start = start
                        triggers[step] = start
                        val cpuStart = Debug.threadCpuTimeNanos()
                        current.deliver(step + 1)
                        cpu[step] = Debug.threadCpuTimeNanos() - cpuStart
                        wall[step] = System.nanoTime() - start
                        delivered++
                        current.verifyRetained()
                    }
                    // Relative to actual dispatch: no catch-up burst if the device stalls.
                    val remaining = PROGRESS_CADENCE_MS - (System.nanoTime() - triggers[step]) / 1_000_000
                    if (remaining > 0) SystemClock.sleep(remaining)
                }
                // A cadence sleep does not prove that the last visible update reached a draw.
                // This explicit boundary invalidates the view; it is not animation-settled proof.
                if (active) awaitNativeParityDraw(instrumentation, current.view)
                entry.put("final_draw_observed", active)
                frames.end = System.nanoTime()
                frames.detach(instrumentation)
                frames.requireNoOverflow()
                entry.put("after", progressMemory())
                nativeParityOnMain(instrumentation) { entry.put("work", current.workJson()); current.verifyWork() }
                requireNativeParityHost(instrumentation, activity, "library-perf-block-end-$ordinal")
                // Verify actual returned presentation outside timing, including the hidden cell.
                nativeParityOnMain(instrumentation) { current.reveal() }
                awaitNativeParityDraw(instrumentation, current.view)
                nativeParityOnMain(instrumentation) {
                    val target = current.pendingTarget()
                    check(target == PROGRESS_STEPS / 31f)
                    entry.put("latest_bound_target", target.toDouble())
                }
                if (count == 1000 && !active) {
                    nativeParityOnMain(instrumentation) { current.view.restorePosition(NativeListSavedPosition(20, 11)) }
                    awaitNativeParityDraw(instrumentation, current.view)
                    nativeParityOnMain(instrumentation) {
                        check(current.view.scrollView.findViewHolderForAdapterPosition(0) == null)
                        current.view.restorePosition(NativeListSavedPosition(0, 0))
                    }
                    awaitNativeParityDraw(instrumentation, current.view)
                    nativeParityOnMain(instrumentation) { check(current.pendingTarget() == PROGRESS_STEPS / 31f) }
                    entry.put("offscreen_return_target_checked", true)
                }
                entry.put("passed", true)
            } catch (error: Throwable) {
                blockFailure = error
                entry.put("failure", error.stackTraceToString())
                throw error
            } finally {
                var cleanup = captureNativeParityCleanup(blockFailure) { frames.detach(instrumentation) }
                entry.put("delivered", delivered).put("trigger_ns", JSONArray(triggers.take(delivered)))
                    .put("main_wall_ns", JSONArray(wall.take(delivered))).put("main_cpu_ns", JSONArray(cpu.take(delivered)))
                    .put("first_action_index", 0).put("warm_action_indexes", "1..29").put("frames", frames.json())
                cleanup = captureNativeParityCleanup(cleanup) {
                    nativeParityOnMain(instrumentation) {
                        // Retain the last actual counts even when admission/work assertions failed mid-block.
                        if (!entry.has("work")) entry.put("work", fixture?.workJson() ?: JSONObject.NULL)
                        try { fixture?.close() } finally { activity.fixtureRoot.removeAllViews(); fixture = null }
                    }
                }
                cleanup?.let {
                    entry.put("passed", false).put("failure", it.stackTraceToString())
                    if (blockFailure == null) throw it
                }
            }
        }
        SystemClock.sleep(400)
        result.put("closed_400ms", progressMemory())
        budget(5_000)
        SystemClock.sleep(4_600)
        result.put("closed_5s", progressMemory())
        requireNativeParityHost(instrumentation, activity, "library-perf-complete")
        val finalConfig = nativeParityOnMain(instrumentation) { progressConfiguration(activity) }
        result.put("configuration_after", finalConfig)
        check(initialConfig.toString() == finalConfig.toString()) { "Stable display/configuration changed" }
        result.put("passed", true)
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) {
            nativeParityOnMain(instrumentation) { host?.fixtureRoot?.removeAllViews(); host?.finish() }
        }
        failure = captureNativeParityCleanup(failure) {
            worker.quitSafely()
            worker.join(1_000)
            check(!worker.isAlive) { "Frame worker did not retire" }
        }
        failure?.let { result.put("passed", false).put("failure", it.stackTraceToString()) }
        result.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
        report.appendLine("LIBRARY_PROGRESS_PERFORMANCE_EVIDENCE ${directory.name}/result.json")
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray(Charsets.UTF_8)) }
        }
    }
    failure?.let { throw it }
}

internal fun verifyNativeLibraryDisposalMemory(instrumentation: Instrumentation, report: StringBuilder,
    apkSha256: String?, sourceManifestSha256: String?) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    check(apkSha256?.matches(Regex("[0-9a-f]{64}")) == true)
    check(sourceManifestSha256?.matches(Regex("[0-9a-f]{64}")) == true)
    val started = SystemClock.elapsedRealtime(); val pid = Process.myPid()
    // Stop admitting work at 85s, reserving 5s for cleanup/publication within the 90s session.
    // Android synchronous calls require the operator watchdog for a hung main thread.
    fun budget(reserveMs: Long = 0L) {
        check(Process.myPid() == pid)
        check(SystemClock.elapsedRealtime() - started + reserveMs < 85_000L) {
            "Disposal budget exhausted; preserve partial evidence, no retry"
        }
    }
    val directory = File(context.filesDir, "library-disposal-memory-${System.currentTimeMillis()}").also { check(it.mkdir()) }
    val checkpoints = JSONArray(); val cycles = JSONArray()
    val retired = ArrayList<DisposalRetired>(4)
    val result = JSONObject().put("selector", LIBRARY_DISPOSAL_MEMORY_SELECTOR).put("passed", false)
        .put("schema", "library-disposal-memory-v1").put("pid", pid).put("started_elapsed_ms", started)
        .put("budget_ms", 90_000).put("work_deadline_elapsed_ms", started + 85_000)
        .put("elapsed_scope", "JSON elapsed excludes publication; require successful instrumentation terminal within budget")
        .put("admitted_apk_sha256", apkSha256).put("admitted_source_manifest_sha256", sourceManifestSha256)
        .put("binding_scope", "Operator-supplied digests; external admission receipt must verify installed APK and source")
        .put("scope", "One retained empty QA host; four fresh N1000-visible A fixtures; async effects parked")
        .put("memory_scope", "Process-wide QA/framework counters; no forced GC, RAM threshold or leak verdict")
        .put("weak_scope", "Survival without natural collection is inconclusive; cleared references do not require PSS to fall")
        .put("requested_cycles", 4).put("updates_per_cycle", PROGRESS_STEPS).put("cadence_ms", PROGRESS_CADENCE_MS)
        .put("checkpoints", checkpoints).put("cycles", cycles)
    var host: NativeParityActivity? = null; var failure: Throwable? = null
    try {
        budget(5_000)
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        budget(5_000)
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val initialConfig = nativeParityOnMain(instrumentation) {
            check(activity.fixtureRoot.childCount == 0)
            progressConfiguration(activity)
        }
        result.put("configuration_before", initialConfig)
        fun checkpoint(label: String, originMs: Long, targetAgeMs: Long) {
            val waitMs = (originMs + targetAgeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            budget(waitMs + 2_000)
            if (waitMs > 0) SystemClock.sleep(waitMs)
            budget(2_000)
            requireNativeParityHost(instrumentation, activity, label)
            nativeParityOnMain(instrumentation) { check(activity.fixtureRoot.childCount == 0) }
            val before = SystemClock.elapsedRealtime()
            val sample = progressMemory().put("label", label).put("origin_elapsed_ms", originMs)
                .put("target_age_ms", targetAgeMs).put("sample_start_elapsed_ms", before)
                .put("sample_end_elapsed_ms", SystemClock.elapsedRealtime()).put("observed_age_ms", before - originMs)
            val weak = JSONArray()
            for (item in retired) weak.put(JSONObject().put("cycle", item.cycle)
                .put("fixture_alive", item.references[0].get() != null)
                .put("controller_alive", item.references[1].get() != null)
                .put("list_alive", item.references[2].get() != null))
            checkpoints.put(sample.put("retired_weak_references", weak))
            budget()
        }
        val initialEmpty = SystemClock.elapsedRealtime()
        checkpoint("initial_400ms", initialEmpty, 400)
        checkpoint("initial_5s", initialEmpty, 5_000)
        checkpoint("initial_15s", initialEmpty, 15_000)
        repeat(4) { index ->
            budget(22_000)
            val entry = JSONObject().put("cycle", index + 1).put("passed", false)
                .put("finished_count", 1000).put("active", true).put("arm", "A-production")
            cycles.put(entry)
            val item = runLibraryDisposalCycle(instrumentation, activity, index + 1, entry, ::budget)
            retired.add(item)
            checkpoint("cycle_${index + 1}_closed_400ms", item.closedElapsedMs, 400)
            checkpoint("cycle_${index + 1}_closed_5s", item.closedElapsedMs, 5_000)
        }
        checkpoint("final_closed_15s", retired.last().closedElapsedMs, 15_000)
        budget(2_000)
        val finalConfig = nativeParityOnMain(instrumentation) { progressConfiguration(activity) }
        result.put("configuration_after", finalConfig)
        check(initialConfig.toString() == finalConfig.toString()) { "Display/configuration changed" }
        result.put("passed", true)
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) {
            nativeParityOnMain(instrumentation) {
                try { host?.fixtureRoot?.removeAllViews() } finally { host?.finish(); host = null }
            }
        }
        if (SystemClock.elapsedRealtime() - started >= 90_000 && failure == null)
            failure = IllegalStateException("Disposal session exceeded 90s")
        failure?.let { result.put("passed", false).put("failure", it.stackTraceToString().take(6000)) }
        result.put("completed_cycles", retired.size).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
        report.appendLine("LIBRARY_DISPOSAL_MEMORY_EVIDENCE ${directory.name}/result.json")
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray(Charsets.UTF_8)) }
        }
        val terminalElapsed = SystemClock.elapsedRealtime() - started
        report.appendLine("LIBRARY_DISPOSAL_MEMORY_TERMINAL_ELAPSED_MS $terminalElapsed")
        if (terminalElapsed >= 90_000 && failure == null)
            failure = IllegalStateException("Disposal publication exceeded 90s")
    }
    failure?.let { throw it }
}
private data class DisposalRetired(val cycle: Int, val closedElapsedMs: Long,
    val references: List<WeakReference<Any>>)
private fun runLibraryDisposalCycle(instrumentation: Instrumentation, activity: NativeParityActivity,
    cycle: Int, entry: JSONObject, budget: (Long) -> Unit): DisposalRetired {
    var fixture: ProgressPerfFixture? = null
    var references: List<WeakReference<Any>> = emptyList()
    var delivered = 0; var failure: Throwable? = null; var closedAt = 0L
    try {
        budget(7_000)
        requireNativeParityHost(instrumentation, activity, "disposal-mount-$cycle")
        nativeParityOnMain(instrumentation) {
            budget(7_000)
            check(activity.fixtureRoot.childCount == 0)
            val created = ProgressPerfFixture(activity, 1000, true, false)
            fixture = created
            references = created.disposalWeakReferences()
            activity.fixtureRoot.addView(created.view, FrameLayout.LayoutParams(-1, -1))
            created.deliver(0)
        }
        val current = checkNotNull(fixture)
        budget(5_000)
        awaitNativeParityDraw(instrumentation, current.view)
        budget(1_000)
        SystemClock.sleep(200)
        nativeParityOnMain(instrumentation) { current.begin(); check(current.view.isShown) }
        entry.put("updates_start_elapsed_ms", SystemClock.elapsedRealtime())
        repeat(PROGRESS_STEPS) { step ->
            budget(2_000)
            requireNativeParityHost(instrumentation, activity, "disposal-$cycle-$step")
            val trigger = nativeParityOnMain(instrumentation) {
                budget(2_000)
                val now = SystemClock.elapsedRealtime()
                current.deliver(step + 1); delivered++; current.verifyRetained()
                now
            }
            val waitMs = (trigger + PROGRESS_CADENCE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            budget(waitMs + 1_000)
            if (waitMs > 0) SystemClock.sleep(waitMs)
        }
        entry.put("updates_end_elapsed_ms", SystemClock.elapsedRealtime())
        budget(5_000)
        awaitNativeParityDraw(instrumentation, current.view)
        nativeParityOnMain(instrumentation) {
            current.verifyWork()
            check(current.pendingTarget() == PROGRESS_STEPS / 31f)
            entry.put("work", current.workJson()).put("final_draw_observed", true)
        }
        budget(2_000)
        entry.put("mounted_after", progressMemory()).put("passed", true)
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) {
            nativeParityOnMain(instrumentation) {
                try { fixture?.close() } finally {
                    try { activity.fixtureRoot.removeAllViews() } finally { fixture = null }
                }
                check(activity.fixtureRoot.childCount == 0)
            }
        }
        closedAt = SystemClock.elapsedRealtime()
        entry.put("delivered", delivered).put("closed_elapsed_ms", closedAt).put("cleanup_complete", failure == null)
        failure?.let { entry.put("passed", false).put("failure", it.stackTraceToString().take(6000)) }
    }
    failure?.let { throw it }
    return DisposalRetired(cycle, closedAt, references)
}

private class ProgressPerfFixture(
    private val host: NativeParityActivity, private val count: Int,
    private var active: Boolean, private val control: Boolean,
) : AutoCloseable, LibraryListWorkObserver {
    private val runtime = NativeModalWindowRuntime(host).also { it.setStarted(true) }
    // Deliberately excludes repository/collector IO, exactly as the passing JVM fixture.
    private val parked = CoroutineScope(object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    })
    private val recordings = List(count) { i -> RecordingEntity("/synthetic/row-$i.wav", "row-$i.wav", "audio/wav",
        1_700_000_000_000L, 1000L, 32044L, "PCM", RecordingStorageType.FILE, "/synthetic") }
    private val jobs = List(PROGRESS_STEPS + 1) { step -> listOf(PendingSave("pending", "Saving",
        1_700_000_000_000L, 1000L, SavePhase.SAVING, step / 31f)) }
    private val environment = ReverbViewEnvironment(host, false)
    val view = ReverbLibraryListView(host, environment, runtime.scope, host.fixtureRoot,
        LibraryRecordingRowActions(null, null, null), object : LibraryInlineExpansionHost {
            override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease = error("No playback")
        }, {})
    private lateinit var presentation: NativeLibraryPresentation
    private val owner = NativeLibraryController(host, view, host, parked, runtime.frames, null,
        NativeLibraryInput(false, recordings, NativeLibraryCallbacks())) { value ->
        presentation = value
        val state = value.list.copy(screenActive = active,
            items = if (control) ArrayList(value.list.items) else value.list.items)
        view.submit(state)
    }
    private val ownerClass = NativeLibraryController::class.java
    private val pendingField = ownerClass.getDeclaredField("pendingSaves").apply { isAccessible = true }
    private val dirtyField = ownerClass.getDeclaredField("pendingItemsDirty").apply { isAccessible = true }
    private val deliverMethod = ownerClass.getDeclaredMethod("deliver").apply { isAccessible = true }
    private val adapter get() = checkNotNull(view.scrollView.adapter)
    private val entriesField = adapter.javaClass.getDeclaredField("entries").apply { isAccessible = true }
    private val mapField = adapter.javaClass.getDeclaredField("recordingsById").apply { isAccessible = true }
    private val updatesField = view.javaClass.getDeclaredField("rowUpdates").apply { isAccessible = true }
    private var retainedEntries: Any? = null
    private var retainedMap: Any? = null
    private var retainedFinished: Any? = null
    private var retainedStructure: Any? = null
    private var revision = 0L
    private var position = NativeListSavedPosition(0, 0)
    private var diffs = 0
    private var created = 0
    private var pendingBinds = 0
    private var finishedBinds = 0
    private var changedNotifications = 0
    private var badNotifications = 0
    init {
        ownerClass.getDeclaredField("saveJobsReadiness").apply { isAccessible = true }
            .set(owner, DurableSaveJobs.Readiness(loaded = true))
        if (count > 0) {
            @Suppress("UNCHECKED_CAST")
            val selected = ownerClass.getDeclaredField("selectedIds").apply { isAccessible = true }
                .get(owner) as MutableMap<String, RecordingEntity>
            selected[recordings[0].id] = recordings[0]
        }
        view.workObserver = this
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changedNotifications++
                if (positionStart != 0 || itemCount != 1) badNotifications++
            }
        })
    }
    fun deliver(step: Int) {
        pendingField.set(owner, jobs[step]); dirtyField.setBoolean(owner, true); deliverMethod.invoke(owner)
    }
    fun disposalWeakReferences(): List<WeakReference<Any>> =
        listOf(WeakReference<Any>(this), WeakReference<Any>(owner), WeakReference<Any>(view))
    fun begin() {
        val snapshot = presentation.list.items as LibraryListSnapshot
        // Do not hold an obsolete control catalog just to compare candidate-only identities.
        retainedEntries = if (control) null else entriesField.get(adapter)
        retainedMap = if (control) null else mapField.get(adapter)
        retainedFinished = snapshot.finished; retainedStructure = snapshot.structure
        revision = (updatesField.get(view) as NativeLibraryListUpdates<*>).revision
        position = view.savePosition()
        diffs = 0; created = 0; pendingBinds = 0; finishedBinds = 0; changedNotifications = 0; badNotifications = 0
    }
    fun verifyRetained() {
        val snapshot = presentation.list.items as LibraryListSnapshot
        check(snapshot.finished === retainedFinished && snapshot.structure === retainedStructure)
        check(presentation.list.selectedIds == if (count == 0) emptySet<String>() else setOf(recordings[0].id))
        if (!control) {
            check(entriesField.get(adapter) === retainedEntries && mapField.get(adapter) === retainedMap)
            check((updatesField.get(view) as NativeLibraryListUpdates<*>).revision == revision)
        }
        check(view.savePosition() == position)
    }
    fun verifyWork() {
        check(diffs == if (control) PROGRESS_STEPS else 0)
        check(created == if (control) count * PROGRESS_STEPS else 0)
        check(changedNotifications == PROGRESS_STEPS && badNotifications == 0)
        if (!control) check(finishedBinds == 0)
    }
    fun workJson() = JSONObject().put("diffs", diffs).put("finished_entries_created", created)
        .put("pending_binds", pendingBinds).put("finished_binds", finishedBinds)
        .put("changed_notifications", changedNotifications).put("bad_notifications", badNotifications)
    fun reveal() { active = true; view.visibility = View.VISIBLE; deliver(PROGRESS_STEPS) }
    fun pendingTarget(): Float {
        val holder = checkNotNull(view.scrollView.findViewHolderForAdapterPosition(0))
        val row = holder.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(holder)
        return row.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(row) as Float
    }
    override fun finishedEntryCreated() { created++ }
    override fun catalogDiff() { diffs++ }
    override fun rowBound(key: String) { if (key.startsWith("save-job:")) pendingBinds++ else if (key.startsWith("recording:")) finishedBinds++ }
    override fun close() {
        retainedEntries = null; retainedMap = null; retainedFinished = null; retainedStructure = null
        var failure = captureNativeParityCleanup(null) { owner.close() }
        failure = captureNativeParityCleanup(failure) { view.dispose() }
        failure = captureNativeParityCleanup(failure) { parked.cancel() }
        failure = captureNativeParityCleanup(failure) { runtime.close() }
        failure?.let { throw it }
    }
}

private class ProgressPerfFrames(private val window: Window, private val handler: Handler) {
    @Volatile var start = Long.MAX_VALUE
    @Volatile var end = Long.MAX_VALUE
    private val intended = LongArray(2048)
    private val duration = LongArray(2048)
    private var size = 0
    private var dropped = 0L
    private var overflow = 0
    private var invalid = 0
    private var invalidTimestamp = 0
    private var attached = false
    private var drained = false
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
        val time = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        synchronized(this) {
            // Lost reports have no timestamps of their own. Even an out-of-window callback
            // may report a lost in-window frame; conservatively count the entire attachment.
            dropped += lost
            if (time < 0) invalidTimestamp++
            else if (time >= start && time <= end) {
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (total < 0) invalid++
                else if (size == intended.size) overflow++
                else { intended[size] = time; duration[size] = total; size++ }
            }
        }
    }
    fun attach() { window.addOnFrameMetricsAvailableListener(listener, handler); attached = true }
    fun detach(instrumentation: Instrumentation) {
        if (!attached) return
        if (end == Long.MAX_VALUE) end = System.nanoTime()
        nativeParityOnMain(instrumentation) { window.removeOnFrameMetricsAvailableListener(listener); attached = false }
        val drain = CountDownLatch(1)
        check(handler.post { drain.countDown() } && drain.await(2, TimeUnit.SECONDS)) { "Frame callback drain failed" }
        drained = true
    }
    @Synchronized fun requireNoOverflow() = check(overflow == 0) {
        "Frame evidence overflowed; preserve partial evidence, no automatic retry"
    }
    @Synchronized fun json() = JSONObject().put("scope", "Activity Window only")
        .put("window_start_ns", if (start == Long.MAX_VALUE) JSONObject.NULL else start)
        .put("window_end_ns", if (end == Long.MAX_VALUE) JSONObject.NULL else end)
        .put("window_scope", "First dispatch through final cadence; visible cells include an explicit observed-draw boundary, not animation settling")
        .put("loss_scope", "Entire listener attachment; lost reports cannot be assigned exact frame timestamps")
        .put("intended_vsync_ns", JSONArray(intended.take(size))).put("total_duration_ns", JSONArray(duration.take(size)))
        .put("sample_count", size).put("available", size > 0)
        .put("dropped_callbacks", dropped).put("overflow", overflow).put("invalid_duration", invalid)
        .put("invalid_timestamp", invalidTimestamp).put("callback_queue_drained", drained)
        .put("complete", drained && size > 0 && dropped == 0L && overflow == 0 && invalid == 0 && invalidTimestamp == 0)
}

private fun progressMemory(): JSONObject {
    val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    val runtime = Runtime.getRuntime()
    val counters = JSONObject()
    val raw = try { Debug.getRuntimeStats() } catch (_: RuntimeException) { null }
    listOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed",
        "art.gc.blocking-gc-count", "art.gc.blocking-gc-time").forEach { counters.put(it, raw?.get(it) ?: JSONObject.NULL) }
    return JSONObject().put("pid", Process.myPid()).put("uptime_ms", SystemClock.uptimeMillis())
        .put("total_pss_kib", info.totalPss).put("private_dirty_kib", info.totalPrivateDirty)
        .put("java_pss_kib", info.dalvikPss).put("native_pss_kib", info.nativePss)
        .put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
        .put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
        .put("art_runtime_stats", counters)
}

private fun progressConfiguration(host: NativeParityActivity): JSONObject {
    val config = host.resources.configuration
    val display = checkNotNull(host.fixtureRoot.display)
    return JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT).put("fingerprint", android.os.Build.FINGERPRINT)
        .put("density_dpi", config.densityDpi).put("font_scale", config.fontScale.toDouble())
        .put("locales", config.locales.toLanguageTags()).put("orientation", config.orientation).put("ui_mode", config.uiMode)
        .put("width", host.fixtureRoot.width).put("height", host.fixtureRoot.height)
        .put("display", display.displayId).put("refresh_hz", display.refreshRate.toDouble())
        .put("animator_scale", android.provider.Settings.Global.getFloat(host.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f).toDouble())
        .put("transition_scale", android.provider.Settings.Global.getFloat(host.contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f).toDouble())
        .put("window_scale", android.provider.Settings.Global.getFloat(host.contentResolver,
            android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, 1f).toDouble())
        .put("theme", "production environment, explicit light")
}
