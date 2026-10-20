package app.smallthingz.reverb

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Real codec buffers; synthetic PCM stays entirely inside the isolated QA package. */
internal fun verifyFlacOutputBufferRoundTrip(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    val sampleRate = 48_000
    val channels = 2
    // More than the maximum FLAC block length, with a partial final block.
    val frames = 65_537
    val frameBytes = channels * 2
    val pcm = ByteArray(frames * frameBytes) { (it * 73 + it / 17 + 11).toByte() }
    val original = pcm.clone()
    val directory = getSavedRecordingsDirectory(context)
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
    val writer = FlacAudioFileWriter(context, target, sampleRate, channels, DEFAULT_FLAC_COMPRESSION)
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
    val verified = writer.verify(context) {}
    check(original.contentEquals(pcm)) { "FLAC encoding modified source PCM" }
    val decoded = ByteArray(pcm.size)
    var decodedBytes = 0
    RandomAccessFile(file, "r").use { source ->
        FlacPcmDecoder(source.channel, RecordingReadCleanup.Propagate).use { decoder ->
            check(decoder.info.sampleRate == sampleRate && decoder.info.channelCount == channels)
            check(decoder.info.bitsPerSample == 16 && decoder.info.frameCount == frames.toLong())
            check(decoder.info.compressionLevel == DEFAULT_FLAC_COMPRESSION)
            check(decoder.info.md5.contentEquals(MessageDigest.getInstance("MD5").digest(original)))
            decoder.readFrames(0, frames.toLong()) { bytes, offset, count ->
                check(count <= decoded.size - decodedBytes) { "FLAC decoded too many bytes" }
                bytes.copyInto(decoded, decodedBytes, offset, offset + count)
                decodedBytes += count
            }
        }
    }
    check(decodedBytes == original.size && decoded.contentEquals(original)) {
        "FLAC round trip changed PCM frames"
    }
    check(fileIdentityMatches(requireNotNull(verified.fileKey), resolveFileIdentity(file))) {
        "FLAC fixture identity changed before cleanup"
    }
    val pcmSha256 = MessageDigest.getInstance("SHA-256").digest(decoded).toHexString()
    report.append("FLAC_ROUNDTRIP frames=$frames rate=$sampleRate channels=$channels")
        .append(" pcm_bytes=$decodedBytes pcm_sha256=$pcmSha256")
        .append(" encoded_bytes=${writer.totalFileBytesWritten}\n")
    check(file.delete()) { "Could not remove the exact verified QA fixture" }
}
