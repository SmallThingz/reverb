package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

internal const val CAPTURE_PREPARED_STORAGE_MEMORY_SELECTOR = "capture_prepared_storage_memory"

/** Actual uninstalled PCM ownership; no Service startup, microphone, storage or capture queue. */
internal fun verifyCapturePreparedStorageMemory(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(Looper.myLooper() != Looper.getMainLooper())
    val directory = File(context.filesDir, "capture-prepared-storage-memory-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val started = SystemClock.elapsedRealtime()
    val cycles = JSONArray()
    val checkpoints = JSONArray()
    val result = JSONObject().put("selector", CAPTURE_PREPARED_STORAGE_MEMORY_SELECTOR).put("passed", false)
        .put("workload_revision", "uninstalled-max-pcm-v1").put("pid", Process.myPid())
        .put("sample_rate", 96_000).put("channels", 2).put("sample_format", "PCM_FLOAT")
        .put("expected_pcm_bytes_per_preparation", 4_608_000L).put("requested_cycles", 10)
        .put("scope", "Actual Service preparation/retirement; one retained unstarted Service; no native input or queue")
        .put("memory_scope", "Process-wide PSS/ART includes QA work; no forced GC, page touching, or absolute RAM pass gate")
        .put("retirement_scope", "Null Service field proves ownership release, not immediate direct-buffer reclamation")
        .put("cycles", cycles).put("checkpoints", checkpoints)
    var fixture: PreparedStorageFixture? = null
    var failure: Throwable? = null
    fun budget() = check(SystemClock.elapsedRealtime() - started < 30_000L) {
        "Capture memory probe deadline exceeded; retain partial evidence, no retry"
    }
    try {
        val current = nativeParityOnMain(instrumentation) { PreparedStorageFixture() }.also { fixture = it }
        fun checkpoint(label: String) {
            budget()
            val owner = nativeParityOnMain(instrumentation) { current.ownership() }
            checkpoints.put(capturePreparationMemory().put("label", label).put("owner", owner))
        }
        checkpoint("before") // The same Service is retained throughout all six checkpoints.
        repeat(10) { index ->
            budget()
            val cycle = JSONObject().put("cycle", index + 1).put("retired", false)
            cycles.put(cycle)
            cycle.put("prepared", nativeParityOnMain(instrumentation) { current.prepare() })
            if (index == 0) checkpoint("first_prepared")
            cycle.put("after_retire", nativeParityOnMain(instrumentation) { current.retire() })
                .put("retired", true)
            when (index) {
                0 -> checkpoint("first_retired")
                4 -> checkpoint("cycle_5_retired")
                9 -> checkpoint("cycle_10_retired")
            }
        }
        val settleStarted = SystemClock.elapsedRealtime()
        SystemClock.sleep(5_000L) // Instrumentation thread only; Service stays strongly retained.
        result.put("settle_elapsed_ms", SystemClock.elapsedRealtime() - settleStarted)
        checkpoint("retained_5s")
        result.put("completed_cycles", cycles.length()).put("passed", true)
    } catch (error: Throwable) {
        failure = error
    } finally {
        failure = captureNativeParityCleanup(failure) {
            nativeParityOnMain(instrumentation) {
                fixture?.let { current ->
                    // A failed checkpoint/negative control still retires the current allocation.
                    if (current.hasPrepared()) current.retire()
                    result.put("cleanup_owner", current.ownership())
                }
            }
        }
        // Never call onDestroy on a Service whose onCreate was deliberately never admitted.
        fixture = null
        failure?.let { result.put("passed", false).put("failure", it.stackTraceToString()) }
        result.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
        report.appendLine("CAPTURE_PREPARED_STORAGE_MEMORY_EVIDENCE ${directory.name}/result.json")
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) {
                it.write(result.toString(2).toByteArray(Charsets.UTF_8))
            }
        }
    }
    failure?.let { throw it }
}

private class PreparationOnlyService : ReverbService() {
    var forbiddenCalls = 0
        private set
    private fun forbidden(): Nothing { forbiddenCalls++; error("Unstarted memory probe attempted Service/native startup") }
    override fun onCreate() = forbidden()
    override fun onDestroy() = forbidden()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = forbidden()
    override fun createAudioRecord(): CaptureAudioInput? = forbidden()
}

private class PreparedStorageFixture {
    private val service = PreparationOnlyService()
    private val serviceClass = ReverbService::class.java
    private val storageField = serviceClass.getDeclaredField("preparedCaptureStorage").apply { isAccessible = true }
    private val prepareMethod = serviceClass.getDeclaredMethod("prepareCaptureReadBuffersOnAudioThread")
        .apply { isAccessible = true }
    private val inputOwner = serviceClass.declaredClasses.single { it.simpleName == "CaptureInputOperationOwner" }
    private val retireMethod = serviceClass.getDeclaredMethod("releaseAudioRecordIfOwned", inputOwner)
        .apply { isAccessible = true }
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        set("audioHandler", Handler(Looper.getMainLooper()))
        set("sampleRate", 96_000)
        set("channelMode", ChannelMode.STEREO)
        set("pcmSampleFormat", PcmSampleFormat.PCM_FLOAT)
        check(!hasPrepared())
        ownership()
    }
    private fun get(name: String): Any? = serviceClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun set(name: String, value: Any) = serviceClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(service, value)
    private fun invoke(method: java.lang.reflect.Method, vararg arguments: Any?): Any? = try {
        method.invoke(service, *arguments)
    } catch (error: InvocationTargetException) { throw error.targetException }

    fun hasPrepared(): Boolean = storageField.get(service) != null
    fun ownership(): JSONObject {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(service.forbiddenCalls == 0)
        for (field in listOf("audioRecord", "captureIo", "audioThread", "loopingAudioChunkStore", "oneShotAudioChunkStore")) {
            check(get(field) == null) { "Unexpected live capture owner: $field" }
        }
        check(get("durableListeningIntentEnabled") == false && get("captureStoresReady") == false)
        return JSONObject().put("service_identity", System.identityHashCode(service))
            .put("prepared_storage_present", hasPrepared()).put("native_record_present", false)
            .put("capture_io_present", false).put("audio_thread_present", false).put("stores_present", false)
            .put("forbidden_startup_calls", service.forbiddenCalls)
            .put("release_generation", (get("audioRecordReleaseGeneration") as AtomicLong).get())
    }
    fun prepare(): JSONObject {
        ownership()
        check(!hasPrepared()) { "Previous preparation still owned" }
        invoke(prepareMethod)
        // Return only scalar evidence; no storage/buffer references escape this call.
        val storage = checkNotNull(storageField.get(service) as? CaptureIoCoordinator.Storage)
        val budget = storage.budget
        val ringBytes = CapturePcmRing::class.java.getDeclaredField("bytes").apply { isAccessible = true }
            .get(storage.ring) as ByteArray
        check(budget.frameBytes == 8 && budget.maxReadBytes == 768_000 && budget.ringBytes == 3_072_000)
        check(budget.totalPcmBytes == 4_608_000L && budget.packetCount == 502)
        check(ringBytes.size == budget.ringBytes && storage.ring.budget == budget)
        check(storage.native.isDirect && storage.native.capacity() == budget.maxReadBytes)
        check(storage.writerScratch.size == budget.maxReadBytes)
        val status = storage.ring.status()
        check(status.occupiedBytes == 0 && status.occupiedPackets == 0 && !status.reading && !status.fence)
        return ownership().put("storage_identity", System.identityHashCode(storage))
            .put("frame_bytes", budget.frameBytes).put("ring_bytes", ringBytes.size)
            .put("native_direct_capacity_bytes", storage.native.capacity())
            .put("writer_scratch_bytes", storage.writerScratch.size).put("packet_count", budget.packetCount)
            .put("total_pcm_bytes", budget.totalPcmBytes)
    }
    fun retire(): JSONObject {
        ownership()
        val previous = (get("audioRecordReleaseGeneration") as AtomicLong).get()
        check(invoke(retireMethod, null) == true)
        check(!hasPrepared()) { "Actual retirement retained uninstalled PCM" }
        check((get("audioRecordReleaseGeneration") as AtomicLong).get() == previous + 1)
        return ownership()
    }
}

private fun capturePreparationMemory(): JSONObject {
    val began = SystemClock.elapsedRealtime()
    val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    val runtime = Runtime.getRuntime()
    val counters = JSONObject()
    var counterError: String? = null
    val raw = try { Debug.getRuntimeStats() } catch (error: RuntimeException) {
        counterError = error.toString(); null
    }
    listOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed",
        "art.gc.blocking-gc-count", "art.gc.blocking-gc-time").forEach { counters.put(it, raw?.get(it) ?: JSONObject.NULL) }
    return JSONObject().put("pid", Process.myPid()).put("elapsed_realtime_ms", began)
        .put("sample_elapsed_ms", SystemClock.elapsedRealtime() - began)
        .put("total_pss_kib", info.totalPss).put("private_dirty_kib", info.totalPrivateDirty)
        .put("java_pss_kib", info.dalvikPss).put("native_pss_kib", info.nativePss)
        .put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
        .put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
        .put("art_runtime_stats", counters).put("art_counter_error", counterError ?: JSONObject.NULL)
}
