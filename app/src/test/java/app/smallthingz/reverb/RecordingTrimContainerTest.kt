package app.smallthingz.reverb

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingTrimContainerTest {
    @Test fun supportedFlacNamedWavDelegatesToConversionInsteadOfTheWavParser() {
        // libFLAC 1.5.0: 800 mono PCM16 frames at 8 kHz, sample i = (i * 37 % 2000) - 1000.
        // Includes real encoded frames and their checksums, not just a STREAMINFO header.
        val encoded = Base64.getMimeDecoder().decode(FLAC_FIXTURE)
        val pcm = ByteArray(1_600)
        repeat(800) { i ->
            val value = (i * 37 % 2_000) - 1_000
            pcm[i * 2] = value.toByte()
            pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        withSource(encoded, ".wav") { file ->
            FileInputStream(file).channel.use { source ->
                val info = readFlacInfo(source)
                assertEquals(8_000, info.sampleRate)
                assertEquals(1, info.channelCount)
                assertEquals(16, info.bitsPerSample)
                assertEquals(800L, info.frameCount)
                assertArrayEquals(MessageDigest.getInstance("MD5").digest(pcm), info.md5)
                // This was the exact rejection reached by the old filename-based trim path.
                assertThrows(InvalidWavStructureException::class.java) { readWavPcmLayout(source) }
                source.position(31L)
                assertNull(readWavTrimLayoutOrNull(source))
                assertEquals(31L, source.position())
            }
            assertArrayEquals(encoded, file.readBytes())
        }
    }

    @Test fun wavNamedFlacKeepsItsPcmLayoutAndExactSelectedBytes() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val frameBytes = channels * format.bytesPerSample
            val pcm = ByteArray(1_024 * frameBytes) { (it * 31).toByte() }
            val encoded = buildWavHeaderBytes(8_000, channels, format, pcm.size.toLong()) + pcm
            withSource(encoded, ".flac") { file ->
                FileInputStream(file).channel.use { source ->
                    val layout = requireNotNull(readWavTrimLayoutOrNull(source))
                    assertEquals(8_000, layout.sampleRate)
                    assertEquals(channels, layout.channelCount)
                    assertEquals(format, layout.sampleFormat)
                    assertEquals(1_024L, layout.frameCount)
                    val selected = ByteArray(512 * frameBytes)
                    requireReadAt(source, layout.dataOffsetBytes + 128L * frameBytes, selected, selected.size)
                    assertArrayEquals(pcm.copyOfRange(128 * frameBytes, 640 * frameBytes), selected)
                }
                assertArrayEquals(encoded, file.readBytes())
            }
        }
    }

    @Test fun malformedNonFlacDoesNotBecomeAConversionFallback() {
        for (bytes in listOf(
            byteArrayOf(1, 2, 3),
            buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, 1_600L),
        )) {
            withSource(bytes, ".flac") { file ->
                FileInputStream(file).channel.use { source ->
                    assertThrows(InvalidWavStructureException::class.java) { readWavTrimLayoutOrNull(source) }
                }
                assertArrayEquals(bytes, file.readBytes())
            }
        }
    }

    private fun withSource(bytes: ByteArray, suffix: String, block: (File) -> Unit) {
        val directory = File("build/tmp/recording-trim-container-test").apply { mkdirs() }
        val file = File.createTempFile("source-", suffix, directory)
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }

    companion object {
        private const val FLAC_FIXTURE = """
            ZkxhQwAAACIQABAAAAItAAItAfQA8AAAAyCmcK9nsbhUYEyNXctoCK5hhAAAKCAAAAByZWZlcmVuY2UgbGliRkxBQyAxLjUuMCAyMDI1
            MDIxMQAAAAD/+HQIAAMf9xT8GPw9FD///4f///4gEAgEAgAAAz4AA0EAgEAgEAgEAgEAgEAgEAgEAgEAgAf///4gEAgEAgEAgEAgAAAz4AA0
            EAgEAgEAgEAgEAgEAgEAgAf///4gEAgEAgEAgEAgEAgEAgAAAz4AA0EAgEAgEAgEAgEAgAf///4gEAgEAgEAgEAgEAgEAgEAgEAgAAAz4AA0
            EAgEAgEAgAf///4gEAgEAgEAgEAgEAgEAgEAgEAgEAgEAgAAAz4AA0EAgAf///w////wAAM+AANBAIBAIBAIBAIBAIBAIBAIBAIBAIBAIBAIBAA
            ////xAIBAIAAAM+AANBAIBAIBAIBAIBAIBAIBAIBAIBAIBAA////xAIBAIBAIBAIAAAM+AANBAIBAIBAIBAIBAIBAIBAIBAA////xAIBAIBAIBAIBA
            IBAIAAAM+AANBAIBAIBAIBAIBAIBAA////xAIBAIBAIBAIBAIBAIBAIBAIAAAM+AANBAIBAIBAIBAA////xAIBAIBAIBAIBAIBAIBAIBAIBAIBAIAA
            AM+AANBAIBAA////vAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEAAAAAAn3AAAAAUEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQAf///4gEAgAAAz4AA0
            EAgEAgEAgEAgEAgEAgEAgEAgEAgEAgAf///4gEAgEAgEAgAAAz4AA0EAgEAgEAgEAgEAgEAgEAgEAgAf///yc7Q==
        """
    }
}
