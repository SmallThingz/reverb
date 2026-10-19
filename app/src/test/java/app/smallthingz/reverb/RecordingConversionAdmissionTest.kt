package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RecordingConversionAdmissionTest {
    private class MemoryOutput : WavSeekableOutput {
        val bytes = ByteArray(2048)
        var position = 0
        var size = 0
        var closes = 0
        var forces = 0
        override fun position(position: Long) { this.position = position.toInt() }
        override fun write(buffer: ByteBuffer): Int {
            val count = buffer.remaining()
            buffer.get(bytes, position, count)
            position += count
            size = maxOf(size, position)
            return count
        }
        override fun truncate(size: Long) { this.size = size.toInt() }
        override fun force(metadata: Boolean) { forces++ }
        override fun close() { closes++ }
    }

    @Test fun exactBudgetWritesVerifiedCopyAndOneExtraFrameAcquiresNoOutput() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val frameBytes = channels * format.bytesPerSample
            val original = ByteArray(81 * frameBytes) { (it * 31).toByte() }
            val unchanged = original.clone()
            val layout = WavPcmLayout(8_000, channels, format, 0L, original.size.toLong())
            var targetAcquisitions = 0
            var writerAcquisitions = 0
            var sourceReads = 0
            val output = MemoryOutput()
            fun convert(endMillis: Long?) = withAdmittedRecordingConversion(
                layout, ExportFormat.WAV, 0L, endMillis, maximumOutputFrames = 80L,
            ) { plan ->
                targetAcquisitions++
                val target = RecordingOutputTarget("memory", "copy.wav", "audio/wav",
                    RecordingStorageType.FILE, "memory", 1L, staging = true)
                writerAcquisitions++
                val writer = WavAudioFileWriter(target, layout.sampleRate, channels, plan.sampleFormat,
                    output, maximumFrameCount = 80L)
                writer.use {
                    sourceReads++
                    it.write(original, (plan.firstFrame * frameBytes).toInt(), (plan.frameCount * frameBytes).toInt())
                }
                val complete = output.bytes.copyOf(output.size)
                val digest = verifyWavOutputStreamAndDigest(ByteArrayInputStream(complete),
                    writer.totalFileBytesWritten, writer.expectedHeaderBytes, writer.payloadOffsetBytes,
                    writer.totalSampleBytesWritten, writer.payloadSha256, bufferSize = 7)
                assertEquals(complete.size.toLong(), digest.byteCount)
                assertArrayEquals(original.copyOf(80 * frameBytes),
                    complete.copyOfRange(writer.payloadOffsetBytes.toInt(), writer.payloadOffsetBytes.toInt() + 80 * frameBytes))
            }
            val failure = assertThrows(RecordingOutputCapacityException::class.java) { convert(null) }
            assertEquals(ExportFormat.WAV, failure.format)
            assertEquals(10L, failure.maximumDurationMillis)
            assertEquals(0, targetAcquisitions)
            assertEquals(0, writerAcquisitions)
            assertEquals(0, sourceReads)
            assertEquals(0, output.closes)
            convert(10L)
            assertEquals(1, targetAcquisitions)
            assertEquals(1, writerAcquisitions)
            assertEquals(1, sourceReads)
            assertEquals(1, output.closes)
            assertEquals(1, output.forces)
            assertArrayEquals(unchanged, original)
        }
    }

    @Test fun selectedWavFramesRejectBeforeAcquisitionAndExactBudgetCopiesVerifiedSourceBytes() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val frameBytes = channels * format.bytesPerSample
            val source = ByteArray(83 * frameBytes) { (it * 17).toByte() }
            val unchanged = source.clone()
            val layout = WavPcmLayout(8_000, channels, format, 0L, source.size.toLong())
            val output = MemoryOutput()
            var acquiredTargets = 0
            var acquiredWriters = 0
            var payloadReads = 0
            fun trim(frames: Long) = withAdmittedRecordingFrameRange(
                layout, ExportFormat.WAV, firstFrame = 2L, frameCount = frames, maximumOutputFrames = 80L,
            ) { plan ->
                acquiredTargets++
                val target = RecordingOutputTarget("memory", "trim.wav", "audio/wav",
                    RecordingStorageType.FILE, "memory", 1L, staging = true)
                acquiredWriters++
                val writer = WavAudioFileWriter(target, layout.sampleRate, channels, plan.sampleFormat,
                    output, maximumFrameCount = 80L)
                writer.use {
                    payloadReads++
                    it.write(source, (plan.firstFrame * frameBytes).toInt(), (plan.frameCount * frameBytes).toInt())
                }
                val complete = output.bytes.copyOf(output.size)
                val verified = verifyWavOutputStreamAndDigest(ByteArrayInputStream(complete),
                    writer.totalFileBytesWritten, writer.expectedHeaderBytes, writer.payloadOffsetBytes,
                    writer.totalSampleBytesWritten, writer.payloadSha256, bufferSize = 7)
                assertEquals(complete.size.toLong(), verified.byteCount)
                assertArrayEquals(source.copyOfRange(2 * frameBytes, 82 * frameBytes),
                    complete.copyOfRange(writer.payloadOffsetBytes.toInt(), writer.payloadOffsetBytes.toInt() + 80 * frameBytes))
            }
            val failure = assertThrows(RecordingOutputCapacityException::class.java) { trim(81L) }
            assertEquals(ExportFormat.WAV, failure.format)
            assertEquals(10L, failure.maximumDurationMillis)
            assertEquals(0, acquiredTargets)
            assertEquals(0, acquiredWriters)
            assertEquals(0, payloadReads)
            assertEquals(0, output.closes)
            trim(80L)
            assertEquals(1, acquiredTargets)
            assertEquals(1, acquiredWriters)
            assertEquals(1, payloadReads)
            assertEquals(1, output.closes)
            assertEquals(1, output.forces)
            assertArrayEquals(unchanged, source)
        }
    }

    @Test fun compactFloatWavThatFitsItsSourceHeaderCannotExceedTheOutputHeaderBudget() {
        // Geometry only: the accepted source has a 44-byte header, while Reverb emits 58 bytes.
        val frames = 1_073_741_812L
        val layout = WavPcmLayout(4_000, 1, PcmSampleFormat.PCM_FLOAT, 44L, frames * 4L)
        assertEquals(4_294_967_284L, 44L - 8L + layout.dataBytes)
        assertEquals(268_435_453L, frames * 1000L / layout.sampleRate)
        val failure = assertThrows(RecordingOutputCapacityException::class.java) {
            withAdmittedRecordingFrameRange(layout, ExportFormat.WAV, 0L, frames) {
                fail("Over-limit trim acquired output or payload work")
            }
        }
        assertEquals(ExportFormat.WAV, failure.format)
        assertEquals(268_435_452L, failure.maximumDurationMillis)
        val admitted = withAdmittedRecordingFrameRange(layout, ExportFormat.WAV, 0L, frames - 1L) { it }
        assertEquals(frames - 1L, admitted.frameCount)
        assertEquals(PcmSampleFormat.PCM_FLOAT, admitted.sampleFormat)
        val header = buildWavHeaderBytes(layout.sampleRate, layout.channelCount, admitted.sampleFormat,
            admitted.frameCount * layout.frameBytes)
        assertEquals(58, header.size)
        assertEquals(4_294_967_294L, header.size - 8L + admitted.frameCount * layout.frameBytes)
    }

    @Test fun realFourHourFlacGeometryRejectsWavBeforeConversionButAdmitsFlac() {
        val layout = WavPcmLayout(96_000, 2, PcmSampleFormat.PCM_16, 0L, 96_000L * 4 * 60 * 60 * 4)
        val failure = assertThrows(RecordingOutputCapacityException::class.java) {
            withAdmittedRecordingConversion(layout, ExportFormat.WAV, 0L, null) { fail("Acquired output") }
        }
        assertEquals(11_184_810L, failure.maximumDurationMillis)
        val plan = withAdmittedRecordingConversion(layout, ExportFormat.FLAC, 0L, null) { it }
        assertEquals(layout.frameCount, plan.frameCount)
        assertEquals(PcmSampleFormat.PCM_16, plan.sampleFormat)
    }

    @Test fun admittedFailurePropagatesOnceAndClosesItsOwnedWriter() {
        val layout = WavPcmLayout(8_000, 1, PcmSampleFormat.PCM_16, 0L, 160L)
        val output = MemoryOutput()
        val expected = IOException("Source unavailable")
        var conversions = 0
        val actual = assertThrows(IOException::class.java) {
            withAdmittedRecordingConversion(layout, ExportFormat.WAV, 0L, null, 80L) {
                conversions++
                val target = RecordingOutputTarget("memory", "copy.wav", "audio/wav",
                    RecordingStorageType.FILE, "memory", 1L, staging = true)
                WavAudioFileWriter(target, 8_000, 1, PcmSampleFormat.PCM_16, output).use { throw expected }
            }
        }
        assertSame(expected, actual)
        assertEquals(1, conversions)
        assertEquals(1, output.closes)
    }
}
