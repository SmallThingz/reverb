package app.smallthingz.reverb

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PlaybackSessionOsShadow::class])
class RecordingMoveFingerprintTest {
    @Test fun staleSizeUsesOneInitialReadAndStillRereadsBeforeAndAfterPublication() {
        for (reportedSize in listOf(0L, 1L)) fixture(reportedSize) { move ->
            val copied = requireNotNull(move.copy())
            assertEquals(move.bytes.size.toLong(), copied.sizeBytes)
            assertEquals(move.provider.uri.toString(), copied.id)
            assertEquals(listOf(0, 0, 1), move.updatesAtTargetRead)
            assertArrayEquals(move.bytes, move.output.readBytes())
            assertArrayEquals(move.bytes, move.sourceFile.readBytes())
        }
    }

    @Test fun initialReadRejectsShortExtraAndSameLengthChangedBytes() {
        val changes: List<(ByteArray) -> ByteArray> = listOf(
            { it.copyOf(it.size - 1) },
            { it + byteArrayOf(9, 8, 7) },
            { it.clone().also { changed -> changed[44] = 9 } },
        )
        for (change in changes) fixture { move ->
            val different = change(move.bytes)
            move.onTargetRead = { move.output.writeBytes(different) }
            assertNull(move.copy())
            assertEquals(listOf(0), move.updatesAtTargetRead)
            assertEquals(0, move.provider.updates)
            assertArrayEquals(different, move.output.readBytes())
            assertArrayEquals(move.bytes, move.sourceFile.readBytes())
        }
    }

    @Test fun equalBytesInSameUriReplacementDuringOpenDoNotVerify() = fixture { move ->
        move.onTargetRead = { move.provider.replaceOnNextRead = move.bytes }
        assertNull(move.copy())
        assertEquals(listOf(0), move.updatesAtTargetRead)
        assertEquals(1, move.provider.readReplacementCount)
        assertEquals(0, move.provider.updates)
        assertArrayEquals(move.bytes, requireNotNull(move.provider.retainedBeforeReplacement).readBytes())
        assertArrayEquals(move.bytes, move.output.readBytes())
    }

    @Test fun closeFailureAfterExactReadDoesNotGrantFingerprintOrCacheAuthority() = fixture(
        reportedSize = null, nativeReads = true,
    ) { move ->
        var revision: ProviderPayloadRevision? = null
        var failedClose = false
        move.provider.wrapReadDescriptor = { uri, descriptor ->
            if (uri != move.provider.uri) descriptor else {
                revision = requireNotNull(nativeProviderPayloadRevision(descriptor.fileDescriptor))
                object : ParcelFileDescriptor(descriptor) {
                    override fun close() {
                        super.close()
                        failedClose = true
                        throw IOException("provider descriptor close failed")
                    }
                }
            }
        }
        assertNull(move.copy())
        assertTrue(failedClose)
        assertEquals(listOf(0), move.updatesAtTargetRead)
        assertEquals(0, move.provider.updates)
        val identity = resolveProviderRecordingIdentity(move.app, RecordingStorageType.MEDIASTORE, move.provider.uri)
        assertTrue(identity.isNotBlank())
        assertNull(VerifiedProviderDigestCache.read(RecordingStorageType.MEDIASTORE, move.provider.uri.toString(),
            identity, requireNotNull(revision)))
    }

    @Test fun prepublicationAndFinalReadsRejectSameLengthChangesWithoutNativeProof() {
        for (changedRead in listOf(2, 3)) fixture { move ->
            move.onTargetRead = { read ->
                if (read == changedRead) move.output.writeBytes(move.bytes.clone().also { it[44] = 9 })
            }
            assertNull(move.copy())
            val expectedUpdates = if (changedRead == 2) listOf(0, 0) else listOf(0, 0, 1)
            assertEquals(expectedUpdates, move.updatesAtTargetRead)
            assertEquals(changedRead - 2, move.provider.updates)
            assertArrayEquals(move.bytes, move.sourceFile.readBytes())
        }
    }

    @Test fun sourceDisappearanceAfterCopyStillPublishesVerifiedBytes() = fixture { move ->
        move.onTargetRead = { read -> if (read == 1) assertTrue(move.sourceFile.delete()) }
        val copied = requireNotNull(move.copy())
        assertEquals(move.bytes.size.toLong(), copied.sizeBytes)
        assertEquals(listOf(0, 0, 1), move.updatesAtTargetRead)
        assertFalse(move.sourceFile.exists())
        assertArrayEquals(move.bytes, move.output.readBytes())
    }

    private fun fixture(reportedSize: Long? = 1L, nativeReads: Boolean = false, block: (MoveFixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "move-fingerprint-").toFile()
        val previousShadowPath = PlaybackSessionOsShadow.targetPath
        val provider = CoordinatorMediaProvider(File(directory, "target.wav"))
        try {
            provider.copiedOutputReportedSize = reportedSize
            provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            val move = MoveFixture(app, provider, File(directory, "target.wav"))
            PlaybackSessionOsShadow.targetPath = move.output.absolutePath
            provider.beforeOpenRead = { uri ->
                if (uri == provider.uri) {
                    if (!nativeReads) PlaybackSessionOsShadow.targetPath = ""
                    move.updatesAtTargetRead += provider.updates
                    move.onTargetRead(move.updatesAtTargetRead.size)
                }
            }
            block(move)
            provider.assertClosed()
            assertTrue(provider.attemptedDeleteUris.isEmpty())
        } finally {
            PlaybackSessionOsShadow.targetPath = previousShadowPath
            provider.closeLeaked()
            directory.deleteRecursively()
        }
    }

    private class MoveFixture(val app: Application, val provider: CoordinatorMediaProvider, val output: File) {
        val bytes = buildWavHeaderBytes(1_000, 1, PcmSampleFormat.PCM_16, 8L) + ByteArray(8)
        val updatesAtTargetRead = mutableListOf<Int>()
        var onTargetRead: (Int) -> Unit = {}
        val sourceFile: File get() = provider.output(provider.sentinelUri)

        init { provider.addSentinel(bytes) }

        fun copy(): RecordingEntity? {
            val identity = resolveProviderRecordingIdentity(app, RecordingStorageType.MEDIASTORE, provider.sentinelUri)
            check(identity.isNotBlank())
            return copyRecordingToDirectory(app, RecordingEntity(
                id = provider.sentinelUri.toString(), displayName = "clip.wav", mimeType = "audio/wav",
                startedAtMillis = 1L, durationMillis = 4L, sizeBytes = bytes.size.toLong(), codecSummary = "WAV",
                storageType = RecordingStorageType.MEDIASTORE, directoryId = MEDIA_STORE_DIRECTORY_ID,
                fileIdentity = identity,
            ), targetTreeUri = null)
        }
    }
}
