package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun verifyWavPublicationMatrix(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    runCase: (String, () -> Unit) -> Unit,
) {
    check(context.packageName.endsWith(".reliability"))
    runCase("file_identity_write_and_same_byte_replacement") {
        val directory = File(context.filesDir, "reliability-identity-${UUID.randomUUID()}")
        check(directory.mkdir())
        val source = File(directory, "source.wav")
        check(source.createNewFile())
        check(source.setLastModified(System.currentTimeMillis() - 2_000L))
        val createdIdentity = resolveFileIdentity(source)
        Thread.sleep(25)
        val payload = ByteArray(128) { (it * 11 + 7).toByte() }
        FileOutputStream(source).use { it.write(payload); it.fd.sync() }
        val writtenIdentity = resolveFileIdentity(source)
        check(sameFileObjectAcrossRename(createdIdentity, writtenIdentity)) {
            "Legitimate file write was misclassified as object replacement"
        }
        check(!fileIdentityMatches(createdIdentity, writtenIdentity)) {
            "A prior revision was incorrectly accepted after a file write"
        }
        val legacyBirth = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).creationTime().to(TimeUnit.NANOSECONDS)
        val legacyIdentity = writtenIdentity.substringBeforeLast(':') + ":$legacyBirth"
        check(fileIdentityMatches(legacyIdentity, writtenIdentity)) {
            "Same-revision legacy identity could not migrate from unsupported creation time"
        }
        val replacement = File(directory, "replacement.wav").apply { writeBytes(payload) }
        val preserved = File(directory, "preserved-original.wav")
        Files.move(source.toPath(), preserved.toPath())
        Files.move(replacement.toPath(), source.toPath())
        val replacedIdentity = resolveFileIdentity(source)
        check(!fileIdentityMatches(writtenIdentity, replacedIdentity))
        check(!sameFileObjectAcrossRename(writtenIdentity, replacedIdentity)) {
            "Byte-identical replacement incorrectly inherited the original object's authority"
        }
        check(preserved.readBytes().contentEquals(payload) && source.readBytes().contentEquals(payload))
    }
    for (storage in listOf(RecordingStorageType.FILE, RecordingStorageType.DOCUMENT)) {
        for (format in PcmSampleFormat.entries) {
            for (channels in 1..2) {
                for (sampleRate in listOf(8_000, 16_000, 48_000, 96_000)) {
                    val name = "wav_${storage.name}_${format.name}_${channels}ch_$sampleRate"
                    runCase(name) {
                        provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
                        // Odd mono PCM8 covers RIFF padding; float fixtures use finite samples.
                        val pcm = ByteArray(257 * channels * format.bytesPerSample) { (it * 29 + 5).toByte() }
                        if (format == PcmSampleFormat.PCM_FLOAT) {
                            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).apply {
                                repeat(257 * channels) { putFloat(((it % 31) - 15) / 16f) }
                            }
                        }
                        val original = pcm.clone()
                        val displayName = "${name}-${UUID.randomUUID()}.wav"
                        val target = if (storage == RecordingStorageType.DOCUMENT) {
                            createOutputTargetInDirectory(
                                context, tree, displayName, "audio/wav", System.currentTimeMillis(),
                                StagingOutputKind.EXPORT_TRACKED,
                            )
                        } else {
                            val directory = File(context.filesDir, "reliability-file-outputs")
                            check(directory.mkdirs() || directory.isDirectory)
                            val stagingName = stagingOutputName(displayName, UUID.randomUUID().toString(),
                                kind = StagingOutputKind.EXPORT_TRACKED)
                            val file = File(directory, stagingName)
                            check(file.createNewFile())
                            if (format == PcmSampleFormat.PCM_16 && channels == 1 && sampleRate == 16_000) {
                                // Deterministically exercise Android's creationTime -> mtime
                                // fallback across the writer's legitimate file mutation.
                                check(file.setLastModified(System.currentTimeMillis() - 2_000L))
                            }
                            RecordingOutputTarget(
                                id = file.absolutePath,
                                displayName = displayName,
                                mimeType = "audio/wav",
                                storageType = RecordingStorageType.FILE,
                                directoryId = directory.absolutePath,
                                startedAtMillis = System.currentTimeMillis(),
                                file = file,
                                staging = true,
                                stagingDisplayName = stagingName,
                                stagingIdentity = resolveFileIdentity(file),
                            )
                        }
                        val writer = WavAudioFileWriter(context, target, sampleRate, channels, format)
                        writer.use { it.write(pcm, 0, pcm.size) }
                        val verified = verifyWavOutputTargetAndDigest(
                            context, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
                            writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
                        )
                        requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, target, verified))
                        val published = finalizeOutputTarget(context, target, verified)
                        val actual = if (published.storageType == RecordingStorageType.FILE) {
                            requireNotNull(published.file).readBytes()
                        } else {
                            requireNotNull(context.contentResolver.openInputStream(requireNotNull(published.uri)))
                                .use { it.readBytes() }
                        }
                        val expected = writer.expectedHeaderBytes + pcm +
                            if (pcm.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
                        check(expected.contentEquals(actual)) { "WAV header, frames, or padding changed during publication" }
                        check(original.contentEquals(pcm)) { "Publication modified source samples" }
                        check(removeVerifiedExportStaging(context, target.storageType, target.id, verified))
                    }
                }
            }
        }
    }
}
