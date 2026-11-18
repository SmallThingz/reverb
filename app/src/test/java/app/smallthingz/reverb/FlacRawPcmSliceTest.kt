package app.smallthingz.reverb

import android.app.Application
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [FlacRawExtractorShadow::class])
class FlacRawPcmSliceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun seekAndRetainedBlockBorrowTheOriginalArrayAtExactFrameOffsets() {
        val file = folder.newFile()
        val streamInfo = ByteBuffer.allocate(34).apply {
            putShort(16)
            putShort(16)
            putLong(10, (8_000L shl 44) or (1L shl 41) or (15L shl 36) or 32L)
        }
        file.writeBytes(byteArrayOf(102, 76, 97, 67, -128, 0, 0, 34) + streamInfo.array())
        val original = ByteArray(32 * 4) { (it * 47 + 13).toByte() }
        FlacRawExtractorShadow.original = original
        FlacRawExtractorShadow.extractorArray = null
        FlacRawExtractorShadow.releases = 0

        RandomAccessFile(file, "r").use { source ->
            FlacPcmDecoder(source.channel, RecordingReadCleanup.Propagate).use { decoder ->
                fun assertRead(first: Long, count: Long, expectedOffset: Int) {
                    var calls = 0
                    decoder.readFrames(first, count) { bytes, offset, length ->
                        calls++
                        assertSame(FlacRawExtractorShadow.extractorArray, bytes)
                        assertEquals(expectedOffset, offset)
                        assertEquals(count * 4, length.toLong())
                        val firstByte = first.toInt() * 4
                        assertArrayEquals(original.copyOfRange(firstByte, firstByte + length),
                            bytes.copyOfRange(offset, offset + length))
                    }
                    assertEquals(1, calls)
                }

                assertRead(18L, 3L, 8)
                assertEquals(1L, decoder.rawBlockDecodeCount)
                assertTrue(decoder.hasRetainedRawFrames(21L, 2L))
                assertRead(21L, 2L, 20)
                assertEquals(1L, decoder.rawBlockDecodeCount)
                assertRead(2L, 1L, 8)
                assertEquals(2L, decoder.rawBlockDecodeCount)
            }
            assertEquals(1, FlacRawExtractorShadow.releases)
            assertTrue(source.channel.isOpen)
        }
    }
}

@Implements(MediaExtractor::class)
class FlacRawExtractorShadow {
    companion object {
        lateinit var original: ByteArray
        var extractorArray: ByteArray? = null
        var releases = 0
    }

    private var blockFirstFrame = 0

    @Implementation fun __constructor__() = Unit
    @Implementation fun setDataSource(source: MediaDataSource) = Unit
    @Implementation fun getTrackCount(): Int = 1
    @Implementation fun getTrackFormat(index: Int): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 8_000, 2)
    @Implementation fun selectTrack(index: Int) = Unit
    @Implementation fun seekTo(timeUs: Long, mode: Int) {
        check(mode == MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        blockFirstFrame = (timeUs / 2_000L).toInt() * 16
    }
    @Implementation fun getSampleTime(): Long = blockFirstFrame * 125L
    @Implementation fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        extractorArray = buffer.array()
        buffer.position(offset)
        buffer.put(original, blockFirstFrame * 4, 64)
        return 64
    }
    @Implementation fun advance(): Boolean {
        blockFirstFrame += 16
        return blockFirstFrame < 32
    }
    @Implementation fun release() { releases++ }
}
