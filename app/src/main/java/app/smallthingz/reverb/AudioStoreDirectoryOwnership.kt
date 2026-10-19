package app.smallthingz.reverb

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** Process-local mutation admission. A logical path stays reserved even if its directory is replaced. */
internal class AudioStoreDirectoryOwnership {
    private val monitor = Object()
    private val owners = HashMap<String, Any>()

    // Resolve parent aliases, but never follow the managed directory's final entry. The store's
    // existing no-follow checks still reject a symlink/non-directory at that exact entry.
    private fun key(directory: File): String = File(
        requireNotNull(directory.absoluteFile.parentFile).canonicalFile,
        directory.name,
    ).path

    fun tryAcquire(directory: File): Closeable? {
        val key = key(directory)
        return synchronized(monitor) {
            if (owners.containsKey(key)) null else claimLocked(key)
        }
    }

    /** Called off main and outside every store monitor; neither callbacks nor file I/O hold this lock. */
    fun awaitAll(directories: List<File>, cancelled: () -> Boolean): List<Closeable> {
        val keys = directories.map(::key)
        require(keys.distinct().size == keys.size) { "Duplicate raw-buffer directory" }
        while (true) {
            if (cancelled()) throw AudioStoreAdmissionCancelledException()
            synchronized(monitor) {
                if (keys.none(owners::containsKey)) return keys.map(::claimLocked)
                try {
                    monitor.wait(50L)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted awaiting previous audio work").also { it.initCause(error) }
                }
            }
        }
    }

    private fun claimLocked(key: String): Closeable {
        val token = Any()
        owners[key] = token
        return Closeable {
            synchronized(monitor) {
                if (owners[key] === token) {
                    owners.remove(key)
                    monitor.notifyAll()
                }
            }
        }
    }
}

internal class AudioStoreAdmissionCancelledException : IOException("Recorder storage admission was retired")
internal val processAudioStoreDirectoryOwnership = AudioStoreDirectoryOwnership()

/** User intent changes supersede queued QS work; store-driven configuration changes do not. */
internal class CaptureControlFence {
    private val generation = java.util.concurrent.atomic.AtomicLong()
    fun snapshot(): Long = generation.get()
    fun recordAcceptedControl(queuedOwner: Long?) {
        // Executing a queued tap is not a new arrival: other taps already in that same
        // ordered queue retain their tickets. A direct user command supersedes that queue.
        if (queuedOwner == null) generation.incrementAndGet()
    }
    fun mayApply(expected: Long?): Boolean = expected == null || expected == generation.get()
}
