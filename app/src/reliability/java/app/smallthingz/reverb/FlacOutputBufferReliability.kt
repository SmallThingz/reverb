package app.smallthingz.reverb

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Real codec buffers; synthetic PCM stays entirely inside the isolated QA package. */
internal fun verifyFlacOutputBufferRoundTrip(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    // Exercise the existing managed internal fallback so the owned QA archive retains the output.
    val storageContext = object : ContextWrapper(context) {
        override fun getExternalFilesDir(type: String?): File? = null
        override fun getApplicationContext(): Context = this
    }
    val sampleRate = 48_000
    val channels = 2
    // More than the maximum FLAC block length, with a partial final block.
    val frames = 65_537
    val frameBytes = channels * 2
    val pcm = ByteArray(frames * frameBytes) { (it * 73 + it / 17 + 11).toByte() }
    // Keep the arbitrary multiblock payload, with one wide antiphase DC interval.
    // Its interior has an exact downsampling oracle independent of filter implementation.
    for (frame in 8_192 until 16_384) {
        val offset = frame * frameBytes
        pcm[offset] = 0
        pcm[offset + 1] = 32 // +8192 left
        pcm[offset + 2] = 0
        pcm[offset + 3] = (-32).toByte() // -8192 right
    }
    val original = pcm.clone()
    val directory = getSavedRecordingsDirectory(storageContext)
    check(directory.absolutePath in managedRecordingFileDirectoryIds(storageContext))
    check(directory.mkdirs() || directory.isDirectory)
    val displayName = "flac-buffer-${UUID.randomUUID()}.flac"
    val stagingName = stagingOutputName(displayName, UUID.randomUUID().toString(),
        kind = StagingOutputKind.EXPORT_TRACKED)
    val file = File(directory, stagingName)
    check(file.createNewFile())
    val target = RecordingOutputTarget(
        id = file.absolutePath,
        displayName = displayName,
        mimeType = "audio/flac",
        storageType = RecordingStorageType.FILE,
        directoryId = directory.absolutePath,
        startedAtMillis = System.currentTimeMillis(),
        file = file,
        staging = true,
        stagingDisplayName = stagingName,
        stagingIdentity = resolveFileIdentity(file),
    )
    val writer = FlacAudioFileWriter(storageContext, target, sampleRate, channels, DEFAULT_FLAC_COMPRESSION)
    writer.use {
        val batches = intArrayOf(1, 257, 4_093, 17, 1_024)
        var writtenFrames = 0
        var batch = 0
        while (writtenFrames < frames) {
            val count = minOf(batches[batch++ % batches.size], frames - writtenFrames)
            it.write(pcm, writtenFrames * frameBytes, count * frameBytes)
            writtenFrames += count
        }
    }
    check(writer.totalSampleBytesWritten == pcm.size.toLong())
    check(writer.totalFileBytesWritten == file.length())
    // Production verification checks the encoded digest and a complete native PCM decode.
    val verified = writer.verify(storageContext) {}
    check(original.contentEquals(pcm)) { "FLAC encoding modified source PCM" }
    val decoded = ByteArray(pcm.size)
    var decodedBytes = 0
    val nativePreviewDigest = MessageDigest.getInstance("SHA-256")
    var nativePreviewRanges = 0
    var downsampledPreviewFrames = 0
    var rawBorrowedSliceVerified = false
    RandomAccessFile(file, "r").use { source ->
        FlacPcmDecoder(source.channel, RecordingReadCleanup.Propagate).use { decoder ->
            check(decoder.info.sampleRate == sampleRate && decoder.info.channelCount == channels)
            check(decoder.info.bitsPerSample == 16 && decoder.info.frameCount == frames.toLong())
            check(decoder.info.compressionLevel == DEFAULT_FLAC_COMPRESSION)
            check(decoder.info.md5.contentEquals(MessageDigest.getInstance("MD5").digest(original)))
            var firstBlockFrames = 0
            decoder.readFrames(0, frames.toLong()) { bytes, offset, count ->
                check(count <= decoded.size - decodedBytes) { "FLAC decoded too many bytes" }
                if (decodedBytes == 0) firstBlockFrames = count / frameBytes
                bytes.copyInto(decoded, decodedBytes, offset, offset + count)
                decodedBytes += count
            }
            if (decoder.rawBlockDecodeCount > 0L) {
                check(firstBlockFrames >= 3) { "FLAC fixture needs three frames in its first raw block" }
                val beforeSeek = decoder.rawBlockDecodeCount
                var callbacks = 0
                decoder.readFrames(1L, 1L) { bytes, offset, count ->
                    check(offset == frameBytes && count == frameBytes)
                    check((0 until count).all { bytes[offset + it] == original[frameBytes + it] })
                    callbacks++
                }
                check(decoder.rawBlockDecodeCount > beforeSeek && callbacks == 1)
                check(decoder.hasRetainedRawFrames(2L, 1L))
                val beforeHit = decoder.rawBlockDecodeCount
                decoder.readFrames(2L, 1L) { bytes, offset, count ->
                    check(offset == 2 * frameBytes && count == frameBytes)
                    check((0 until count).all { bytes[offset + it] == original[2 * frameBytes + it] })
                    callbacks++
                }
                check(decoder.rawBlockDecodeCount == beforeHit && callbacks == 2)
                rawBorrowedSliceVerified = true
            }
            // These times are binary-exact; every requested native-rate frame is known.
            for ((first, end) in listOf(0 to 6_000, 24_000 to 30_000, 60_000 to 63_000)) {
                val selected = readFlacPcm16Range(decoder,
                    first.toDouble() / sampleRate, end.toDouble() / sampleRate, sampleRate)
                val expected = original.copyOfRange(first * frameBytes, end * frameBytes)
                check(selected.contentEquals(expected)) { "FLAC preview changed interleaved selected frames $first..$end" }
                nativePreviewDigest.update(selected)
                nativePreviewRanges++
            }
            // 9000..12000 source frames stay well inside the DC interval including filter halo.
            val downsampled = readFlacPcm16Range(decoder, 0.1875, 0.25, 24_000)
            val expectedFrame = byteArrayOf(0, 32, 0, -32)
            check(downsampled.size == 1_500 * frameBytes) { "FLAC stereo preview frame count changed" }
            check(downsampled.indices.all { downsampled[it] == expectedFrame[it % frameBytes] }) {
                "FLAC downsampled preview mixed or reordered opposite-polarity channels"
            }
            downsampledPreviewFrames = downsampled.size / frameBytes
        }
    }
    check(decodedBytes == original.size && decoded.contentEquals(original)) {
        "FLAC round trip changed PCM frames"
    }
    // Exercise the shared recording-copy decoder on real Android codec buffers.
    // Expected samples remain the independent original PCM, not decoder output.
    var frameCopyCases = 0
    val checksumSource = File(directory, "frame-copy-checksum-${UUID.randomUUID()}.flac")
    check(checksumSource.createNewFile())
    file.inputStream().use { input -> checksumSource.outputStream().use { output -> input.copyTo(output) } }
    RandomAccessFile(checksumSource, "rw").use {
        it.seek(26); val value = it.readUnsignedByte(); it.seek(26); it.writeByte(value xor 1)
    }
    for (corruptChecksum in listOf(false, true)) for (first in listOf(0L, 221L)) {
        val actual = java.io.ByteArrayOutputStream()
        var closes = 0
        val copyWriter = object : RecordingAudioWriter {
            override val totalSampleBytesWritten get() = actual.size().toLong()
            override val totalFileBytesWritten get() = totalSampleBytesWritten
            override fun write(bytes: ByteArray, offset: Int, count: Int) { actual.write(bytes, offset, count) }
            override fun close() { closes++ }
            override fun verify(context: Context, checkCancelled: () -> Unit): StableOutputFingerprint =
                error("Frame copying must not acquire publication authority")
        }
        RandomAccessFile(if (corruptChecksum) checksumSource else file, "r").use { source ->
            var failure: java.io.IOException? = null
            try {
                copyRecordingFrames(source.channel,
                    WavPcmLayout(sampleRate, channels, PcmSampleFormat.PCM_16, 0, original.size.toLong()),
                    true, RecordingConversionPlan(first, frames - first, PcmSampleFormat.PCM_16), copyWriter)
            } catch (error: java.io.IOException) { failure = error }
            if (corruptChecksum && first == 0L) {
                check(failure?.message == "Original FLAC audio checksum does not match")
            } else check(failure == null) { "Shared FLAC copy failed: $failure" }
            check(source.channel.isOpen)
        }
        check(actual.toByteArray().contentEquals(original.copyOfRange(first.toInt() * frameBytes, original.size)))
        check(closes == 1)
        frameCopyCases++
    }
    report.append("FLAC_FRAME_COPY cases=$frameCopyCases full_checksum_rejected=true selected_pcm_exact=true\n")
    val rawSliceResult = if (rawBorrowedSliceVerified) "seek_and_cache_hit_exact" else "codec_path_raw_unexercised"
    report.append("FLAC_RAW_PCM_SLICE result=$rawSliceResult\n")
    check(fileIdentityMatches(requireNotNull(verified.fileKey), resolveFileIdentity(file))) {
        "FLAC fixture identity changed before evidence retention"
    }
    val pcmSha256 = MessageDigest.getInstance("SHA-256").digest(decoded).toHexString()
    report.append("FLAC_ROUNDTRIP frames=$frames rate=$sampleRate channels=$channels")
        .append(" pcm_bytes=$decodedBytes pcm_sha256=$pcmSha256")
        .append(" encoded_bytes=${writer.totalFileBytesWritten}\n")
    report.append("FLAC_PREVIEW native_ranges=$nativePreviewRanges channels=$channels")
        .append(" native_pcm_sha256=${nativePreviewDigest.digest().toHexString()}")
        .append(" downsampled_rate=24000 downsampled_frames=$downsampledPreviewFrames")
        .append(" left=8192 right=-8192\n")
    val encodedDigest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val block = ByteArray(65_536)
        while (true) {
            val count = input.read(block)
            if (count < 0) break
            encodedDigest.update(block, 0, count)
        }
    }
    check(fileIdentityMatches(requireNotNull(verified.fileKey), resolveFileIdentity(file))) {
        "FLAC fixture identity changed during evidence retention"
    }
    report.append("FLAC_RETAINED path=${file.absolutePath} bytes=${file.length()}")
        .append(" sha256=${encodedDigest.digest().toHexString()}\n")
}
