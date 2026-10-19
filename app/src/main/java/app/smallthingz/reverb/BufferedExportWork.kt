package app.smallthingz.reverb

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** One worker admission owns an audio-queue preparation and its eventual lease, including cancellation. */
internal class BufferedExportWork internal constructor(
    val task: FutureTask<Unit>,
    private val preparation: Runnable,
    private val rejectPreparation: (Exception) -> Unit,
) {
    private val queued = AtomicBoolean()

    /** Called immediately after worker admission, before Service teardown can queue store close. */
    fun queuePreparation(post: (Runnable) -> Boolean) {
        check(queued.compareAndSet(false, true))
        if (task.isCancelled) return
        try {
            if (!post(preparation)) rejectPreparation(RejectedExecutionException("Audio preparation was rejected"))
        } catch (error: Exception) {
            rejectPreparation(error)
        }
    }
}

internal fun <C : Closeable> createBufferedExportWork(
    started: AtomicBoolean,
    beforeAcquire: () -> Unit,
    acquire: () -> C?,
    releaseUnclaimed: (C) -> Unit,
    onReleaseFailure: (Exception) -> Unit,
    onEmpty: () -> Unit,
    onFailure: (Exception) -> Unit,
    export: (child: C, closeChildOnce: () -> Unit) -> Unit,
): BufferedExportWork {
    val lock = Any()
    val ready = CountDownLatch(1)
    var retired = false
    var prepared: C? = null
    var preparationFailure: Exception? = null

    fun retire() {
        val unclaimed = synchronized(lock) {
            retired = true
            prepared.also { prepared = null }
        }
        ready.countDown()
        // FutureTask.done can run on the UI/cancellation caller. Never close a lease monitor there.
        if (unclaimed != null) runCleanupReportingException({ releaseUnclaimed(unclaimed) }, onReleaseFailure)
    }

    fun failed(error: Exception) {
        synchronized(lock) {
            if (!retired) preparationFailure = error
        }
        ready.countDown()
    }

    val task = object : FutureTask<Unit>(Callable {
        started.set(true)
        var child: C? = null
        val childReleased = AtomicBoolean()
        fun closeChildOnce() {
            val owned = child ?: return
            if (childReleased.compareAndSet(false, true)) {
                runCleanupReportingException(owned::close, onReleaseFailure)
            }
        }
        try {
            beforeAcquire()
            ready.await()
            child = synchronized(lock) {
                if (retired) throw InterruptedIOException("Buffered export preparation retired")
                preparationFailure?.let { throw it }
                retired = true
                prepared.also { prepared = null }
            }
            beforeAcquire()
            val owned = child
            if (owned == null) onEmpty() else export(owned, ::closeChildOnce)
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            retire()
            closeChildOnce()
            Thread.interrupted()
        }
        Unit
    }) {
        override fun done() = retire()
    }
    val preparation = Runnable {
        if (synchronized(lock) { retired }) return@Runnable
        try {
            beforeAcquire()
            val owned = acquire()
            val handedOff = synchronized(lock) {
                if (retired) false else {
                    prepared = owned
                    true
                }
            }
            // A cancelled waiter never abandons a lease acquired after its terminal callback.
            if (!handedOff && owned != null) runCleanupReportingException(owned::close, onReleaseFailure)
            ready.countDown()
        } catch (error: Exception) {
            failed(error)
        }
    }
    return BufferedExportWork(task, preparation, ::failed)
}
