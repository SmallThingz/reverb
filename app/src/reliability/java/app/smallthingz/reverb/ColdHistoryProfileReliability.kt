package app.smallthingz.reverb

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Sampling changes timings. Use this only for attribution, not performance comparisons. */
internal fun profileColdHistory(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    val root = File(context.filesDir, "performance-012-large")
    check(File(root, "fixture-ready").readText() == "16384:1048576") {
        "Run large_history_performance first to prepare the isolated sparse fixture"
    }
    val owner = Thread.currentThread()
    val sampling = AtomicBoolean(true)
    val leaves = HashMap<String, Int>()
    val inclusive = HashMap<String, Int>()
    val sampler = Thread({
        while (sampling.get()) {
            val stack = owner.stackTrace
            stack.firstOrNull()?.let { leaves.merge(it.toString(), 1, Int::plus) }
            stack.map { "${it.className}.${it.methodName}" }.distinct()
                .forEach { inclusive.merge(it, 1, Int::plus) }
            Thread.sleep(1)
        }
    }, "history-startup-profiler")
    val store = PersistentAudioChunkStore(root)
    sampler.start()
    try {
        store.configure(RetentionMode.SIZE, Long.MAX_VALUE, 48000, 1, PcmSampleFormat.PCM_16,
            deferRetentionCleanup = true)
        check(store.peekSnapshot()?.chunkCount == 16384)
    } finally {
        sampling.set(false)
        sampler.join()
        store.close()
    }
    for ((label, samples) in listOf("LEAF" to leaves, "INCLUSIVE" to inclusive)) {
        samples.entries.sortedByDescending { it.value }.take(45).forEach {
            report.append("$label ${it.value} ${it.key}\n")
        }
    }
}
