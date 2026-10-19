package app.smallthingz.reverb

import java.io.IOException
import java.io.InterruptedIOException

internal fun checkFlacExportNotInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("FLAC export cancelled")
}

/** Owns the one terminal drain/finalization attempt and both native resource releases. */
internal class FlacEncoderFinalizer {
    private var closed = false

    fun close(
        previousFailure: Throwable?,
        queueEnd: () -> Unit,
        endOfStream: () -> Boolean,
        drain: () -> Boolean,
        finalizeOutput: () -> Unit,
        releaseCodec: () -> Unit,
        closeOutput: () -> Unit,
        nowNanos: () -> Long = System::nanoTime,
    ) {
        if (closed) return
        closed = true
        var error: Throwable? = previousFailure?.let {
            IOException("FLAC write failed; preserving unverified staging without finalization", it)
        }
        if (error == null) try {
            checkFlacExportNotInterrupted()
            queueEnd()
            var deadline = nowNanos() + 30_000_000_000L
            while (!endOfStream()) {
                checkFlacExportNotInterrupted()
                val progressed = drain()
                // Cancellation wins even if the encoder continually produces progress or EOS.
                checkFlacExportNotInterrupted()
                if (progressed) deadline = nowNanos() + 30_000_000_000L
                if (nowNanos() > deadline) throw IOException("FLAC finalization timed out")
            }
            checkFlacExportNotInterrupted()
            finalizeOutput()
        } catch (caught: Throwable) { error = caught }
        error = closePreservingPrimaryFailure(error, releaseCodec)
        error = closePreservingPrimaryFailure(error, closeOutput)
        if (error != null) throw error
    }
}
