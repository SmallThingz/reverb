package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartupRetentionDeferralTest {
    @Test
    fun serviceHistoryProbeBeforeConfiguration_defersBothStoresAndDrainsOneRetireePerPass() {
        for (loopingHasLiveData in listOf(false, true)) withRoot { root ->
            val looping = retiredFixture(File(root, "looping"), loopingHasLiveData, overwriteOldest = true)
            val oneShot = retiredFixture(File(root, "one-shot"), hasLiveData = true, overwriteOldest = false)
            PersistentAudioChunkStore(looping.root).use { loopingStore ->
                PersistentAudioChunkStore(oneShot.root, overwriteOldest = false).use { oneShotStore ->
                    // Match configurePersistentBuffer: policy precedes the short-circuit history
                    // probe, and format/retention selection happens only after that probe.
                    loopingStore.enableDeferredRetentionCleanup()
                    oneShotStore.enableDeferredRetentionCleanup()
                    assertTrue(loopingStore.hasData() || oneShotStore.hasData())
                    assertRetirementsPending(looping)
                    assertRetirementsPending(oneShot)
                    configure(loopingStore)
                    configure(oneShotStore)
                    assertRetirementsPending(looping)
                    assertRetirementsPending(oneShot)
                    assertArrayEquals(looping.livePcm, readAll(loopingStore))
                    assertArrayEquals(oneShot.livePcm, readAll(oneShotStore))

                    val newPcm = pcm(512, 71)
                    assertEquals(newPcm.size, loopingStore.append(newPcm, 0, newPcm.size))
                    assertEquals(newPcm.size, oneShotStore.append(newPcm, 0, newPcm.size))
                    assertArrayEquals(looping.livePcm + newPcm, readAll(loopingStore))
                    assertArrayEquals(oneShot.livePcm + newPcm, readAll(oneShotStore))
                    drainRetirements(loopingStore, looping)
                    drainRetirements(oneShotStore, oneShot)
                    assertArrayEquals(looping.livePcm + newPcm, readAll(loopingStore))
                    assertArrayEquals(oneShot.livePcm + newPcm, readAll(oneShotStore))
                }
            }
            PersistentAudioChunkStore(looping.root).use { reopened ->
                reopened.enableDeferredRetentionCleanup()
                assertTrue(reopened.hasData())
                configure(reopened)
                assertArrayEquals(looping.livePcm + pcm(512, 71), readAll(reopened))
                assertFalse(reopened.retentionMaintenanceNeeded())
            }
        }
    }

    @Test
    fun historyProbeDoesNotCertifyRetiredPayload_workerPreservesChangedNumericAndClaimBytes() {
        for (hiddenClaim in listOf(false, true)) withRoot { root ->
            val fixture = retiredFixture(root, hasLiveData = true)
            val retired = if (hiddenClaim) fixture.claim else fixture.numeric
            val changed = retired.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            retired.writeBytes(changed)
            PersistentAudioChunkStore(root).use { store ->
                store.enableDeferredRetentionCleanup()
                assertTrue(store.hasData())
                assertRetirementsPending(fixture)
                assertEquals(0, File(root, "preserved").listFiles().orEmpty().size)
                configure(store)
                assertArrayEquals(fixture.livePcm, readAll(store))

                drainRetirements(store, fixture)
                assertTrue(File(root, "preserved").listFiles().orEmpty().any {
                    it.readBytes().contentEquals(changed)
                })
                assertArrayEquals(fixture.livePcm, readAll(store))
            }
        }
    }

    @Test
    fun deferredClaimCleanupRechecksOriginalObjectAfterHistoryProbe() = withRoot { root ->
        val fixture = retiredFixture(root, hasLiveData = true)
        PersistentAudioChunkStore(root).use { store ->
            store.enableDeferredRetentionCleanup()
            assertTrue(store.hasData())
            assertRetirementsPending(fixture)
            val expected = fixture.claim.readBytes()
            val original = File(root, "original-claim")
            Files.move(fixture.claim.toPath(), original.toPath())
            fixture.claim.writeBytes(expected)
            configure(store)

            drainRetirements(store, fixture)
            assertArrayEquals(expected, original.readBytes())
            assertTrue(File(root, "preserved").listFiles().orEmpty().any {
                it.readBytes().contentEquals(expected)
            })
            assertArrayEquals(fixture.livePcm, readAll(store))
        }
    }

    @Test
    fun historyProbeStillRecoversActiveAudioAndRejectsCorruptCheckpointedPrefix() {
        for (corrupt in listOf(false, true)) withRoot { root ->
            val expected = pcm(512, 37)
            PersistentAudioChunkStore(root).use { previous ->
                configure(previous)
                previous.append(expected, 0, expected.size)
                // close checkpoints an ACTIVE span; it does not seal it as FINALIZED.
            }
            val chunk = File(root, "chunks/0")
            val original = chunk.readBytes()
            val changed = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            if (corrupt) chunk.writeBytes(changed)
            PersistentAudioChunkStore(root).use { store ->
                store.enableDeferredRetentionCleanup()
                assertEquals(!corrupt, store.hasData())
                configure(store)
                if (corrupt) {
                    assertTrue(File(root, "preserved").listFiles().orEmpty().any {
                        it.readBytes().contentEquals(changed)
                    })
                } else {
                    assertArrayEquals(expected, readAll(store))
                }
                assertFalse(store.retentionMaintenanceNeeded())
            }
        }
    }

    private data class Fixture(
        val root: File,
        val numeric: File,
        val claim: File,
        val livePcm: ByteArray,
    )

    private fun retiredFixture(
        root: File,
        hasLiveData: Boolean,
        overwriteOldest: Boolean = true,
    ): Fixture {
        val previous = PersistentAudioChunkStore(root, overwriteOldest = overwriteOldest)
        configure(previous)
        repeat(2) { index ->
            val retired = pcm(512, index + 11)
            previous.append(retired, 0, retired.size)
            previous.sealActiveChunk()
        }
        // Process death abandons this lease; terminal store close retains its referenced retirees.
        requireNotNull(previous.acquireRange(0.0, previous.durationSeconds()))
        previous.clear()
        val livePcm = if (hasLiveData) pcm(512, 53) else byteArrayOf()
        if (livePcm.isNotEmpty()) {
            previous.append(livePcm, 0, livePcm.size)
            previous.sealActiveChunk()
        }
        val numeric = File(root, "chunks/1")
        val first = File(root, "chunks/0")
        val claim = File(first.parentFile, ".reverb-retired-delete-${UUID.randomUUID()}.pending")
        Files.move(first.toPath(), claim.toPath())
        previous.close()
        return Fixture(root, numeric, claim, livePcm).also(::assertRetirementsPending)
    }

    private fun assertRetirementsPending(fixture: Fixture) {
        assertTrue(fixture.numeric.isFile)
        assertTrue(fixture.claim.isFile)
        assertTrue(File(fixture.root, "retired/0").isFile)
        assertTrue(File(fixture.root, "retired/1").isFile)
    }

    private fun drainRetirements(store: PersistentAudioChunkStore, fixture: Fixture) {
        assertTrue(store.retentionMaintenanceNeeded())
        repeat(2) { pass ->
            val step = store.performRetentionMaintenanceStep()
            assertTrue(step.progressed)
            assertFalse(step.blocked)
            assertEquals(pass == 0, step.needsMore)
            assertEquals(1 - pass, File(fixture.root, "retired").listFiles().orEmpty().size)
        }
        assertFalse(store.retentionMaintenanceNeeded())
        assertFalse(fixture.numeric.exists())
        assertFalse(fixture.claim.exists())
    }

    private fun configure(store: PersistentAudioChunkStore) = store.configure(
        RetentionMode.SIZE, 65_536L, 8_000, 1, deferRetentionCleanup = true,
    )

    private fun readAll(store: PersistentAudioChunkStore): ByteArray {
        val lease = store.acquireRange(0.0, store.durationSeconds()) ?: return byteArrayOf()
        return lease.use {
            val output = ByteArrayOutputStream()
            it.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                output.write(bytes, offset, count)
                count
            }
            output.toByteArray()
        }
    }

    private fun pcm(count: Int, salt: Int): ByteArray = ByteArray(count) { (it * 29 + salt).toByte() }

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/startup-retention-deferral-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
