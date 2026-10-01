package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingReliabilitySweepTest {
    @Test
    fun legacyCreationTimeFallbackMigrationKeepsExactRevisionAuthority() {
        val legacy = "stat:1:2:100:5:100000000000"
        val current = "stat:1:2:100:5:0"
        assertTrue(fileIdentityMatches(legacy, current))
        assertTrue(fileIdentityMatches(current, legacy))
        assertFalse(fileIdentityMatches(legacy, "stat:1:2:100:6:0"))
        assertFalse(fileIdentityMatches(legacy, "stat:1:2:101:5:0"))
        assertFalse(fileIdentityMatches(legacy, "stat:1:3:100:5:0"))
        assertFalse(fileIdentityMatches(legacy, "stat:2:2:100:5:0"))
        assertFalse(fileIdentityMatches(legacy, "stat:1:2:100:5:100000000001"))
        assertFalse(fileIdentityMatches("stat:1:2:100:5:invalid", current))
        assertFalse(fileIdentityMatches("nio:file:0", current))
    }

    @Test
    fun finalDocumentNameMustBeObservedAndBoundButMayBeProviderSelected() {
        val identity = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/tree/root/document/item", 100L, 12L,
        )
        assertTrue(documentPublishedNameIsVerified(identity, ProviderCatalogObservation("clip (1).wav", identity)))
        assertFalse(documentPublishedNameIsVerified(identity, null))
        assertFalse(documentPublishedNameIsVerified(identity, ProviderCatalogObservation("", identity)))
        assertFalse(documentPublishedNameIsVerified(identity, ProviderCatalogObservation("clip.wav", "")))
        assertFalse(documentPublishedNameIsVerified(identity, ProviderCatalogObservation(
            stagingOutputName("clip.wav", "fixture", kind = StagingOutputKind.EXPORT_TRACKED), identity,
        )))
        val changed = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/tree/root/document/item", 100L, 13L,
        )
        assertFalse(documentPublishedNameIsVerified(identity, ProviderCatalogObservation("clip.wav", changed)))
    }

    @Test
    fun renamedDocumentRetirementRequiresCompleteTreeAndPublishedFile() {
        assertEquals(RecordingAssetState.MISSING, documentRetirementStateFromCompleteListing(false, true))
        assertEquals(RecordingAssetState.UNAVAILABLE, documentRetirementStateFromCompleteListing(false, false))
        assertEquals(RecordingAssetState.PRESENT, documentRetirementStateFromCompleteListing(true, true))
        assertEquals(RecordingAssetState.PRESENT, documentRetirementStateFromCompleteListing(true, false))
    }

    @Test
    fun loadingOrErroredProviderListingsNeverProveAbsence() {
        requireCompleteDocumentListing(false, null)
        for (loading in listOf(false, true)) {
            for (error in listOf(null, "", "temporarily unavailable")) {
                if (!loading && error == null) continue
                assertThrows(IOException::class.java) { requireCompleteDocumentListing(loading, error) }
            }
        }
    }

    @Test
    fun randomizedServiceRetentionAndRestartMatchesIndependentByteModel() {
        for (looping in listOf(false, true)) {
            repeat(12) { seed ->
                withStoreRoot { root ->
                    val random = Random(seed.toLong() + if (looping) 10_000L else 0L)
                    var capacity = 8_192L
                    var model = byteArrayOf()
                    var store = PersistentAudioChunkStore(root, overwriteOldest = looping)
                    fun configure() = store.configure(
                        RetentionMode.SIZE, capacity, 8_000, 1, PcmSampleFormat.PCM_16,
                        // Match ReverbService: incremental maintenance preserves exact frame
                        // boundaries. The legacy eager path intentionally evicts whole chunks.
                        deferRetentionCleanup = true,
                    )
                    fun maintain() {
                        var steps = 0
                        while (store.retentionMaintenanceNeeded()) {
                            check(++steps <= 256) { "Retention maintenance failed to converge" }
                            check(store.performRetentionMaintenanceStep().progressed) {
                                "Unleased retention maintenance unexpectedly blocked"
                            }
                        }
                    }
                    fun retained(bytes: ByteArray): ByteArray {
                        if (capacity == 0L || bytes.size <= capacity) return bytes
                        return if (looping) bytes.takeLast(capacity.toInt()).toByteArray()
                        else bytes.take(capacity.toInt()).toByteArray()
                    }
                    configure()
                    try {
                        repeat(96) { step ->
                            when (random.nextInt(10)) {
                                in 0..5 -> {
                                    // Keep each generated read within a realistic capture batch.
                                    // Tiny retention settings otherwise turn one artificial 16 KiB
                                    // append into hundreds of fsynced chunks, without adding new
                                    // state transitions to this model-based sweep.
                                    val input = ByteArray((1 + random.nextInt(512)) * 2).also(random::nextBytes)
                                    val expectedWrite = when {
                                        capacity == 0L -> 0
                                        looping -> input.size
                                        else -> minOf(input.size.toLong(), (capacity - model.size).coerceAtLeast(0L)).toInt()
                                    }
                                    assertEquals("accepted seed=$seed step=$step looping=$looping", expectedWrite,
                                        store.append(input, 0, input.size))
                                    model = retained(model + input.copyOf(expectedWrite))
                                }
                                6 -> {
                                    capacity = listOf(0L, 1_024L, 4_096L, 8_192L, 32_768L)[random.nextInt(5)]
                                    configure()
                                    model = retained(model)
                                }
                                7 -> store.sealActiveChunk()
                                8 -> {
                                    // A checkpoint is the promised durable boundary. Retire the descriptor
                                    // without normal store close/finalization, then exercise startup recovery.
                                    store.checkpoint()
                                    val access = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess")
                                        .apply { isAccessible = true }
                                    (access.get(store) as? RandomAccessFile)?.close()
                                    access.set(store, null)
                                    store = PersistentAudioChunkStore(root, overwriteOldest = looping)
                                    configure()
                                }
                                9 -> {
                                    store.clear()
                                    model = byteArrayOf()
                                }
                            }
                            maintain()
                            assertArrayEquals("bytes seed=$seed step=$step looping=$looping capacity=$capacity",
                                model, readAll(store))
                        }
                        store.close()
                        store = PersistentAudioChunkStore(root, overwriteOldest = looping)
                        configure()
                        maintain()
                        assertArrayEquals(model, readAll(store))
                    } finally {
                        store.close()
                    }
                }
            }
        }
    }

    @Test
    fun acceptedExportLeaseSurvivesNewCaptureRetentionClearAndReopen() = withStoreRoot { root ->
        val first = ByteArray(8_192) { (it * 7).toByte() }
        val second = ByteArray(8_192) { (it * 13 + 1).toByte() }
        val newest = ByteArray(4_096) { (it * 19 + 2).toByte() }
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 8_192L, 8_000, 1, PcmSampleFormat.PCM_16)
            assertEquals(first.size, store.append(first, 0, first.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                assertEquals(second.size, store.append(second, 0, second.size))
                assertArrayEquals(first, readLease(lease))
                store.clear()
                assertEquals(newest.size, store.append(newest, 0, newest.size))
                assertArrayEquals(first, readLease(lease))
            }
            assertArrayEquals(newest, readAll(store))
        }
        PersistentAudioChunkStore(root).use { reopened ->
            reopened.configure(RetentionMode.SIZE, 8_192L, 8_000, 1, PcmSampleFormat.PCM_16)
            assertArrayEquals(newest, readAll(reopened))
        }
    }

    private fun readAll(store: PersistentAudioChunkStore): ByteArray =
        store.acquireRange(0.0, store.durationSeconds())?.use(::readLease) ?: byteArrayOf()

    private fun readLease(lease: PersistentAudioChunkStore.RangeLease): ByteArray {
        val output = ByteArrayOutputStream()
        lease.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        return output.toByteArray()
    }

    private inline fun withStoreRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/reliability-sweep").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
