package app.smallthingz.reverb

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val timelineSnapshotCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Cleanup outlives the Activity and a shutting-down Service; never take a lease monitor on UI. */
internal fun releaseTimelineSnapshotOffMain(
    snapshot: Closeable,
    onFailure: (Exception) -> Unit,
) {
    timelineSnapshotCleanupScope.launch {
        runCleanupReportingException(snapshot::close, onFailure)
    }
}

/** One accepted FutureTask owns preparation and export; shutdown never requires a second admission. */
internal fun <P : Closeable, C : Closeable> createTimelineSnapshotExportTask(
    snapshot: P,
    started: AtomicBoolean,
    releaseUnstarted: (P) -> Unit,
    onParentReleaseFailure: (Exception) -> Unit,
    onChildReleaseFailure: (Exception) -> Unit,
    beforeAcquire: () -> Unit,
    acquire: (P) -> C?,
    onEmpty: () -> Unit,
    onFailure: (Exception) -> Unit,
    export: (child: C, closeChildOnce: () -> Unit) -> Unit,
): FutureTask<Unit> {
    val parentReleased = AtomicBoolean(false)
    fun closeParentOnce(onWorker: Boolean) {
        if (!parentReleased.compareAndSet(false, true)) return
        runCleanupReportingException(
            cleanup = { if (onWorker) snapshot.close() else releaseUnstarted(snapshot) },
            onFailure = onParentReleaseFailure,
        )
    }
    return object : FutureTask<Unit>(
        Callable {
            started.set(true)
            var child: C? = null
            val childReleased = AtomicBoolean(false)
            fun closeChildOnce() {
                val owned = child ?: return
                if (childReleased.compareAndSet(false, true)) {
                    runCleanupReportingException(owned::close, onChildReleaseFailure)
                }
            }
            try {
                beforeAcquire()
                child = try {
                    acquire(snapshot)
                } finally {
                    closeParentOnce(onWorker = true)
                }
                val owned = child
                if (owned == null) onEmpty() else export(owned, ::closeChildOnce)
            } catch (error: Exception) {
                onFailure(error)
            } finally {
                closeParentOnce(onWorker = true)
                closeChildOnce()
            }
            Unit
        },
    ) {
        override fun done() {
            if (!started.get()) closeParentOnce(onWorker = false)
        }
    }
}

internal fun submitExportTask(
    executor: Executor,
    task: FutureTask<Unit>,
    onRejected: (RejectedExecutionException) -> Unit,
) {
    try {
        executor.execute(task)
    } catch (error: RejectedExecutionException) {
        // done() releases an unstarted source; never retry work or close the parent on the caller.
        try {
            task.cancel(false)
        } catch (cleanupError: Exception) {
            if (cleanupError !== error) error.addSuppressed(cleanupError)
        }
        onRejected(error)
    }
}
