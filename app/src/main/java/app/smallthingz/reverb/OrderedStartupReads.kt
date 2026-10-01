package app.smallthingz.reverb

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Only read ahead. Recovery/mutations stay ordered on the store's owner thread. */
internal fun <I, O> forEachOrderedStartupRead(
    inputs: Array<I>,
    parallelism: Int,
    batchSize: Int = 256,
    read: (I) -> O,
    consume: (O) -> Unit,
) {
    require(parallelism in 1..4 && batchSize > 0)
    if (parallelism == 1 || inputs.size < batchSize) {
        inputs.forEach { consume(read(it)) }
        return
    }
    val executor = Executors.newFixedThreadPool(parallelism - 1) { task ->
        Thread(task, "ReverbHistoryRead").apply { isDaemon = true }
    }
    try {
        var start = 0
        while (start < inputs.size) {
            val end = start + minOf(batchSize, inputs.size - start)
            val results = arrayOfNulls<Any?>(end - start)
            val batchStart = start
            val next = AtomicInteger(parallelism)
            fun readPartition(partition: Int) {
                if (partition < results.size) results[partition] = read(inputs[batchStart + partition])
                // Small shared groups let fast cores help slower cores without one task per file.
                while (true) {
                    val first = next.getAndAdd(8)
                    if (first >= results.size) break
                    for (index in first until minOf(first + 8, results.size)) {
                        results[index] = read(inputs[batchStart + index])
                    }
                }
            }
            var failure: Throwable? = null
            fun failed(error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
            }
            val pending = ArrayList<Future<*>>(parallelism - 1)
            try {
                for (partition in 1 until parallelism) pending += executor.submit { readPartition(partition) }
                readPartition(0)
            } catch (error: Throwable) {
                failed(error)
            }
            var interrupted = false
            for (future in pending) {
                while (true) {
                    try {
                        future.get()
                        break
                    } catch (error: InterruptedException) {
                        // Do not abandon readers behind teardown or start mutating their paths.
                        interrupted = true
                        failed(error)
                    } catch (error: ExecutionException) {
                        failed(error.cause ?: error)
                        break
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            failure?.let { throw it }
            for (result in results) {
                @Suppress("UNCHECKED_CAST")
                consume(result as O)
            }
            start = end
        }
    } finally {
        // Every submitted read has reached terminal before the consumer or an error can escape.
        executor.shutdown()
    }
}
