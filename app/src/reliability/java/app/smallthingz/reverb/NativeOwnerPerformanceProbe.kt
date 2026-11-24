package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

private const val PERF_CYCLES = 8
private const val PERF_SETTLE_MS = 400L
private const val PERF_FRAME_CAPACITY = 600
private val MEMORY_SUMMARY_FIELDS = listOf(
    "summary.java-heap", "summary.native-heap", "summary.code", "summary.stack", "summary.graphics",
    "summary.private-other", "summary.system", "summary.total-pss", "summary.total-swap",
)
private val ART_COUNTER_FIELDS = listOf(
    "art.gc.gc-count", "art.gc.gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed",
    "art.gc.blocking-gc-count", "art.gc.blocking-gc-time",
)

internal val nativeOwnerPerformanceSelectors = setOf(
    "native_owner_perf_menu", "native_owner_perf_home_mount", "native_owner_perf_retention_mount", "native_owner_perf_range",
    "native_owner_perf_theme", "native_owner_perf_retention",
    "native_owner_perf_theme_v2", "native_owner_perf_retention_v2",
)

/** One actual app owner per iteration, reopened inside one instrumentation process. */
internal fun verifyNativeOwnerPerformance(instrumentation: Instrumentation, report: StringBuilder, selector: String) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java && Build.VERSION.SDK_INT >= 31)
    val productionEnvironmentForProbe = selector == "native_owner_perf_theme_v2" || selector == "native_owner_perf_retention_v2"
    val retainedSettings = selector == "native_owner_perf_theme" || selector == "native_owner_perf_retention" ||
        productionEnvironmentForProbe
    val workload: ((Instrumentation, StringBuilder) -> Unit)? = when (selector) {
        "native_owner_perf_menu" -> ::verifyNativeLibraryMenuCurrent
        "native_owner_perf_home_mount" -> ::verifyNativeThemeMatrix
        "native_owner_perf_retention_mount" -> ::verifyNativeRetentionInputParity
        "native_owner_perf_theme", "native_owner_perf_retention",
        "native_owner_perf_theme_v2", "native_owner_perf_retention_v2" -> null
        "native_owner_perf_range" -> ::verifyNativeRangeInteractionCurrent
        else -> error("Unknown current owner workload $selector")
    }
    val workloadRevision = when (selector) {
        "native_owner_perf_menu" -> "library-menu-open-v1"
        "native_owner_perf_home_mount" -> "home-light-mount-v1"
        "native_owner_perf_retention_mount" -> "retention-editor-mount-v1"
        "native_owner_perf_range" -> "range-synthetic-open-v1"
        "native_owner_perf_theme" -> "settings-retained-theme-chip-v1"
        "native_owner_perf_retention" -> "settings-retained-retention-chip-v1"
        "native_owner_perf_theme_v2" -> "settings-retained-theme-chip-v2-production-environment"
        "native_owner_perf_retention_v2" -> "settings-retained-retention-chip-v2-production-environment"
        else -> error("Unknown current owner workload $selector")
    }
    val directory = File(context.filesDir, "native-owner-performance-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cycles = JSONArray()
    val result = JSONObject().put("selector", selector).put("workload_revision", workloadRevision).put("passed", false)
        .put("memory_schema_revision", "android-public-process-summary-v1")
        .put("process_id", Process.myPid()).put("requested_cycles", PERF_CYCLES)
        .put("settle_ms", PERF_SETTLE_MS).put("cycles", cycles)
        .put("scope", if (retainedSettings) "One QA PID and one retained Settings owner; alternating real chip actions"
            else "One QA PID; each iteration mounts and retires an attached activity and its actual app owner")
        .put("frame_scope", "Activity Window FrameMetrics; separately owned popup surfaces are outside this series")
    val worker = HandlerThread("Reverb QA frame metrics").apply { start() }
    val handler = Handler(worker.looper)
    var failure: Throwable? = null
    var settings: NativeSettingsOwnerPerfSession? = null
    try {
        if (retainedSettings) settings = NativeSettingsOwnerPerfSession(
            instrumentation,
            theme = selector == "native_owner_perf_theme" || selector == "native_owner_perf_theme_v2",
            productionEnvironmentForProbe = productionEnvironmentForProbe,
        )
        repeat(PERF_CYCLES) { index ->
            val entry = JSONObject().put("cycle", index)
                .put("phase", if (retainedSettings) {
                    if (index == 0) "first-timed-action-after-draft-setup" else "retained-owner-action"
                } else if (index == 0) "process-first-open" else "same-process-reopen")
            cycles.put(entry)
            val cycle = NativeOwnerPerfCycle(instrumentation, handler, directory, entry)
            var cycleFailure: Throwable? = null
            ownerPerfCycle.set(cycle)
            try {
                val retained = settings
                if (retained != null) retained.transition(cycle, index)
                else {
                    checkNotNull(workload)(instrumentation, report)
                    cycle.closed()
                }
            } catch (error: Throwable) {
                cycleFailure = error
                entry.put("failure", error.stackTraceToString())
                throw error
            } finally {
                ownerPerfCycle.remove()
                try { cycle.close() } catch (error: Throwable) {
                    if (cycleFailure == null) throw error
                    if (cycleFailure !== error) cycleFailure.addSuppressed(error)
                }
            }
        }
        result.put("passed", true)
    } catch (error: Throwable) {
        failure = error
        result.put("failure", error.stackTraceToString())
    } finally {
        try { settings?.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            result.put("passed", false).put("failure", failure?.stackTraceToString())
        }
        worker.quitSafely()
        report.appendLine("OWNER_PERFORMANCE_EVIDENCE ${directory.name}/result.json")
        try {
            writeQaEvidenceAtomically(File(directory, "result.json")) { stream ->
                stream.write(result.toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

internal val ownerPerfCycle = ThreadLocal<NativeOwnerPerfCycle>()

/** Instrumentation-thread checkpoints. Window registration and identity checks run on main. */
internal class NativeOwnerPerfCycle(
    private val instrumentation: Instrumentation,
    private val handler: Handler,
    private val directory: File,
    private val entry: JSONObject,
) : AutoCloseable {
    private val pid = Process.myPid()
    private var window: Window? = null
    @Volatile private var startedAtNanos = Long.MAX_VALUE
    private var sampled = false
    private var activity: WeakReference<NativeParityActivity>? = null
    private val durations = LongArray(PERF_FRAME_CAPACITY)
    private val intendedVsyncs = LongArray(PERF_FRAME_CAPACITY)
    private var frames = 0
    private var dropped = 0L
    private var truncated = 0
    private val listener = Window.OnFrameMetricsAvailableListener { _, frame, droppedSinceLast ->
        val intended = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        if (intended >= startedAtNanos) synchronized(durations) {
            if (frames < durations.size) {
                intendedVsyncs[frames] = intended
                durations[frames] = frame.getMetric(FrameMetrics.TOTAL_DURATION)
                frames++
            } else truncated++
            dropped += droppedSinceLast
        }
    }

    fun opening(host: NativeParityActivity, deferTrigger: Boolean = false) {
        check(activity == null && window == null)
        requireNativeParityHost(instrumentation, host, phase = "owner-perf-before-trigger")
        SystemClock.sleep(PERF_SETTLE_MS)
        requireNativeParityHost(instrumentation, host, phase = "owner-perf-idle-sample")
        entry.put("before_open", memory())
        activity = WeakReference(host)
        nativeParityOnMain(instrumentation) {
            val ownedWindow = host.window
            ownedWindow.addOnFrameMetricsAvailableListener(listener, handler)
            window = ownedWindow
            if (!deferTrigger) markTrigger()
        }
    }

    fun markTrigger(): Long = System.nanoTime().also { startedAtNanos = it }

    fun retainedTransitionSettled(view: View, evidence: (File) -> JSONObject) {
        openSettled(view)
        val frames = File(directory, "settings-frames-${entry.getInt("cycle")}.json")
        entry.put("owner_timing", evidence(frames).put("settings_frame_samples_file", frames.name))
    }

    fun openSettled(view: View, timing: JSONObject? = null) {
        check(activity != null && !sampled)
        val readyAtNanos = System.nanoTime()
        awaitNativeParityDraw(instrumentation, view)
        val drawAtNanos = System.nanoTime()
        entry.put("ready_elapsed_ns", readyAtNanos - startedAtNanos)
            .put("observed_draw_elapsed_ns", drawAtNanos - startedAtNanos)
        timing?.let { entry.put("owner_timing", it) }
        detachFrames()
        synchronized(durations) {
            entry.put("host_frame_total_duration_ns", JSONArray(durations.take(frames)))
                .put("host_frame_intended_vsync_offset_ns", JSONArray(intendedVsyncs.take(frames).map { it - startedAtNanos }))
                .put("host_frame_dropped_callbacks", dropped).put("host_frame_truncated", truncated)
        }
        SystemClock.sleep(PERF_SETTLE_MS)
        check(nativeParityOnMain(instrumentation) {
            view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
                view.display?.displayId == Display.DEFAULT_DISPLAY &&
                view.context.packageName == instrumentation.targetContext.packageName
        }) { "Measured view lost its own physical QA window" }
        entry.put("open_settled", memory())
        sampled = true
    }

    fun closed() {
        val retired = checkNotNull(activity)
        check(sampled && window == null)
        SystemClock.sleep(PERF_SETTLE_MS)
        check(nativeParityOnMain(instrumentation) { retired.get()?.fixtureRoot?.isAttachedToWindow != true }) {
            "The QA Activity did not retire before the post-close sample"
        }
        entry.put("closed_settled", memory())
            .put("closed_activity_weak_reference_cleared", retired.get() == null)
        activity = null
    }

    override fun close() = detachFrames()

    private fun detachFrames() {
        val previous = window ?: return
        window = null
        nativeParityOnMain(instrumentation) { previous.removeOnFrameMetricsAvailableListener(listener) }
        val drained = CountDownLatch(1)
        check(handler.post { drained.countDown() } && drained.await(5L, TimeUnit.SECONDS))
    }

    private fun memory(): JSONObject {
        check(Process.myPid() == pid)
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val vm = Runtime.getRuntime()
        return JSONObject().put("pid", pid).put("uptime_ms", SystemClock.uptimeMillis())
            .put("total_pss_kib", info.totalPss).put("total_private_dirty_kib", info.totalPrivateDirty)
            .put("java_heap_pss_kib", info.dalvikPss).put("native_heap_pss_kib", info.nativePss)
            .put("other_pss_kib", info.otherPss)
            .put("java_heap_private_dirty_kib", info.dalvikPrivateDirty)
            .put("native_heap_private_dirty_kib", info.nativePrivateDirty)
            .put("other_private_dirty_kib", info.otherPrivateDirty)
            .put("java_heap_used_bytes", vm.totalMemory() - vm.freeMemory())
            .put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
            .put("android_memory_stats_kib", readStatMap(MEMORY_SUMMARY_FIELDS) { info.memoryStats })
            .put("art_runtime_stats", readStatMap(ART_COUNTER_FIELDS) { Debug.getRuntimeStats() })
    }

    /** Preserve platform-provided strings and expose missing optional counters explicitly. */
    private fun readStatMap(expected: List<String>, read: () -> Map<String, String>?): JSONObject {
        val values = JSONObject()
        val observation = JSONObject().put("values", values)
        val actual = try {
            read()
        } catch (error: RuntimeException) {
            observation.put("unavailable_reason", error.javaClass.simpleName)
            null
        } catch (error: LinkageError) {
            observation.put("unavailable_reason", error.javaClass.simpleName)
            null
        }
        if (actual == null && !observation.has("unavailable_reason")) {
            observation.put("unavailable_reason", "platform_returned_null")
        }
        actual?.forEach { (key, value) -> values.put(key, value) }
        val missing = JSONArray()
        expected.forEach { key ->
            if (!values.has(key)) {
                values.put(key, JSONObject.NULL)
                missing.put(key)
            }
        }
        return observation.put("missing_expected_fields", missing).put("available", actual != null)
    }
}
