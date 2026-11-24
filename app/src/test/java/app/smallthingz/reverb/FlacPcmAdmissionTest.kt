package app.smallthingz.reverb

import android.app.Application
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Real decoder loop with deterministic extractor boundaries; no native audio or device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [AdmissionFlacExtractorShadow::class])
class FlacPcmAdmissionTest {
    @get:Rule val folder = TemporaryFolder()
    private class Revoked : RuntimeException()

    @Before fun resetExtractor() {
        AdmissionFlacExtractorShadow.reads = 0
        AdmissionFlacExtractorShadow.advances = 0
        AdmissionFlacExtractorShadow.releases = 0
        AdmissionFlacExtractorShadow.afterRead = {}
    }

    private fun withDecoder(block: (FlacPcmDecoder) -> Unit) {
        val file = folder.newFile()
        val info = ByteBuffer.allocate(34).apply {
            putShort(16)
            putShort(16)
            putLong(10, (8_000L shl 44) or (1L shl 41) or (15L shl 36) or 32L)
        }
        file.writeBytes(byteArrayOf(102, 76, 97, 67, -128, 0, 0, 34) + info.array())
        RandomAccessFile(file, "r").use { source ->
            FlacPcmDecoder(source.channel, RecordingReadCleanup.Propagate).use(block)
            assertTrue("decoder does not own the outer descriptor", source.channel.isOpen)
        }
        assertEquals("native extractor has one terminal release", 1, AdmissionFlacExtractorShadow.releases)
    }

    @Test fun revocationDuringNativeReadStopsBeforeDeliveryAndAllowsNextRead() = withDecoder { decoder ->
        val revoked = Revoked()
        var admitted = true
        var delivered = 0
        AdmissionFlacExtractorShadow.afterRead = { admitted = false }
        assertSame(revoked, assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ if (!admitted) throw revoked }) {
                decoder.readFrames(0, 32) { _, _, count -> delivered += count }
            }
        })
        assertEquals(0, delivered)
        assertEquals(1, AdmissionFlacExtractorShadow.reads)
        assertEquals(0, AdmissionFlacExtractorShadow.advances)
        assertFalse(decoder.hasRetainedRawFrames(0, 1))
        assertFalse("generation cancellation must not interrupt the reusable worker", Thread.currentThread().isInterrupted)

        // The same decoder/worker must remain usable by a new admitted gesture.
        AdmissionFlacExtractorShadow.afterRead = {}
        val output = ByteArray(128)
        var position = 0
        withPcmConversionAdmission({}) {
            decoder.readFrames(0, 32) { bytes, offset, count ->
                bytes.copyInto(output, position, offset, offset + count)
                position += count
            }
        }
        assertEquals(128, position)
        assertArrayEquals(AdmissionFlacExtractorShadow.pcm, output)
    }

    @Test fun revocationAfterFirstBlockDoesNotDecodeTheNextBlock() = withDecoder { decoder ->
        val revoked = Revoked()
        var admitted = true
        var deliveries = 0
        assertSame(revoked, assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ if (!admitted) throw revoked }) {
                decoder.readFrames(0, 32) { _, _, count ->
                    assertEquals(64, count)
                    deliveries++
                    admitted = false
                }
            }
        })
        assertEquals(1, deliveries)
        assertEquals(1, AdmissionFlacExtractorShadow.reads)
        assertFalse(decoder.hasRetainedRawFrames(0, 1))
    }

    @Test fun revokedRetainedBlockIsNotDelivered() = withDecoder { decoder ->
        decoder.readFrames(2, 1) { _, _, _ -> }
        assertTrue(decoder.hasRetainedRawFrames(3, 1))
        val revoked = Revoked()
        var admitted = true
        var delivered = false
        assertSame(revoked, assertThrows(Revoked::class.java) {
            withPcmConversionAdmission({ if (!admitted) throw revoked }) {
                admitted = false
                decoder.readFrames(3, 1) { _, _, _ -> delivered = true }
            }
        })
        assertFalse(delivered)
        assertEquals(1, AdmissionFlacExtractorShadow.reads)
    }
}

@Implements(MediaExtractor::class)
class AdmissionFlacExtractorShadow {
    companion object {
        val pcm = ByteArray(128) { (it * 47 + 13).toByte() }
        var reads = 0
        var advances = 0
        var releases = 0
        var afterRead: () -> Unit = {}
    }
    private var firstFrame = 0
    @Implementation fun __constructor__() = Unit
    @Implementation fun setDataSource(source: MediaDataSource) = Unit
    @Implementation fun getTrackCount(): Int = 1
    @Implementation fun getTrackFormat(index: Int): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 8_000, 2)
    @Implementation fun selectTrack(index: Int) = Unit
    @Implementation fun seekTo(timeUs: Long, mode: Int) {
        check(mode == MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        firstFrame = (timeUs / 2_000L).toInt() * 16
    }
    @Implementation fun getSampleTime(): Long = firstFrame * 125L
    @Implementation fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        reads++
        buffer.position(offset)
        buffer.put(pcm, firstFrame * 4, 64)
        afterRead()
        return 64
    }
    @Implementation fun advance(): Boolean {
        advances++
        firstFrame += 16
        return firstFrame < 32
    }
    @Implementation fun release() { releases++ }
}
