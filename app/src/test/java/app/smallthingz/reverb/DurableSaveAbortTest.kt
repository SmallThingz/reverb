package app.smallthingz.reverb

import android.content.Context
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableSaveAbortTest {
    @Test
    fun durableWriterFailureBeforeFirstPcmAbortsWithoutFinalizing() {
        for (failCleanup in listOf(false, true)) {
            val primary = DurableSaveLifetimeRejectedException(DurableSaveLifetimeRejection.SERVICE_STOPPING)
            val codecFailure = IOException("release")
            val outputFailure = IOException("close")
            val finalizer = FlacEncoderFinalizer()
            var codecReleases = 0
            var outputCloses = 0
            val releaseCodec: () -> Unit = {
                codecReleases++
                if (failCleanup) throw codecFailure
            }
            val closeOutput: () -> Unit = {
                outputCloses++
                if (failCleanup) throw outputFailure
            }
            val writer = object : RecordingAudioWriter {
                override val totalSampleBytesWritten = 0L
                override val totalFileBytesWritten = 0L
                override fun write(bytes: ByteArray, offset: Int, count: Int) = error("Unexpected PCM")
                override fun verify(context: Context, checkCancelled: () -> Unit): StableOutputFingerprint =
                    error("Unexpected verification")

                override fun close() = finalizer.close(
                    null,
                    { error("Queued EOS for an aborted save") },
                    { error("Checked EOS for an aborted save") },
                    { error("Drained an aborted save") },
                    { error("Finalized an aborted save") },
                    releaseCodec,
                    closeOutput,
                )

                override fun abort(primaryFailure: Throwable) =
                    finalizer.abort(primaryFailure, releaseCodec, closeOutput)
            }
            val observed = assertThrows(DurableSaveLifetimeRejectedException::class.java) {
                writer.useForDurableSave { throw primary }
            }
            assertSame(primary, observed)
            assertEquals(if (failCleanup) listOf(codecFailure, outputFailure) else emptyList(), observed.suppressed.toList())
            writer.abort(primary)
            writer.close()
            assertEquals(1, codecReleases)
            assertEquals(1, outputCloses)
        }
    }
}
