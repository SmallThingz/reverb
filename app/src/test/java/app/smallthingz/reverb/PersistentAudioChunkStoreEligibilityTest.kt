package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentAudioChunkStoreEligibilityTest {
    @Test
    fun heldHistoryLease_retirementEligibilityWorkScalesLinearly() {
        for (retirements in listOf(16, 64)) {
            withRoot { root ->
                PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
                    configure(store, Long.MAX_VALUE)
                    val part = ByteArray(160) { (it * 29).toByte() }
                    repeat(retirements + 1) {
                        assertEquals(part.size, store.append(part, 0, part.size))
                        store.sealActiveChunk()
                    }
                    val inspected = installCountingRetiredMap(store)
                    val eligible = installCountingRetiredMap(store, "eligibleRetiredById")
                    requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                        configure(store, part.size.toLong(), deferred = true)
                        inspected.inspections = 0
                        repeat(retirements) { index ->
                            val step = store.performRetentionMaintenanceStep()
                            assertTrue(step.progressed)
                            assertFalse(step.blocked)
                            assertEquals(index < retirements - 1, step.needsMore)
                            assertEquals(retirements - index, requireNotNull(store.peekSnapshot()).chunkCount)
                        }
                        val count = inspected.inspections
                        println("RETIREMENT_ELIGIBILITY retirements=$retirements inspections=$count")
                        assertEquals("Pinned retirees must never be traversed for eligibility", 0L, count)
                        assertEquals(0L, eligible.inspections)
                        assertEligibilityMatchesOwnership(store)
                        assertArrayEquals(ByteArray(part.size * (retirements + 1)) { part[it % part.size] }, read(lease))
                    }
                    var passes = 0
                    eligible.inspections = 0
                    while (store.retentionMaintenanceNeeded()) {
                        assertTrue(passes++ < retirements)
                        val step = store.performRetentionMaintenanceStep()
                        assertTrue(step.progressed)
                        assertFalse(step.blocked)
                        assertEligibilityMatchesOwnership(store)
                    }
                    assertEquals(retirements, passes)
                    assertEquals(retirements.toLong(), eligible.inspections)
                    assertEquals(0L, inspected.inspections)
                    println("RETIREMENT_CLEANUP retirements=$retirements eligibleSelections=${eligible.inspections}")
                }
                PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
                    configure(reopened, 160L, deferred = true)
                    assertFalse(reopened.retentionMaintenanceNeeded())
                    requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use {
                        assertArrayEquals(ByteArray(160) { (it * 29).toByte() }, read(it))
                    }
                }
            }
        }
    }

    @Test
    fun eligibleRetirees_followLastChildLeaseAndRetryFailedDurabilityForBothBoundaries() {
        for (looping in listOf(false, true)) {
            withRoot { root ->
                var failMarkerSync = false
                var syncs = 0
                val parts = (0 until 4).map { part -> ByteArray(160) { (part * 31 + it).toByte() } }
                val all = parts.reduce { a, b -> a + b }
                val expected = if (looping) parts[2].copyOfRange(80, 160) + parts[3]
                    else parts[0] + parts[1].copyOfRange(0, 80)
                PersistentAudioChunkStore(root, overwriteOldest = looping, directorySync = { directory ->
                    syncs++
                    if (failMarkerSync && directory.name == "retired") throw IOException("Injected marker sync failure")
                }).use { store ->
                    configure(store, Long.MAX_VALUE)
                    for (part in parts) {
                        assertEquals(part.size, store.append(part, 0, part.size))
                        store.sealActiveChunk()
                    }
                    val parent = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
                    val child = requireNotNull(parent.acquireSubRange(0.0, parent.durationSeconds))
                    configure(store, 240, deferred = true)
                    repeat(2) {
                        val step = store.performRetentionMaintenanceStep()
                        assertTrue(step.progressed)
                        assertTrue(step.needsMore)
                        assertFalse(step.blocked)
                        assertEligibilityMatchesOwnership(store)
                    }
                    val beforeReleaseSyncs = syncs
                    parent.close()
                    assertEligibilityMatchesOwnership(store)
                    val blocked = store.performRetentionMaintenanceStep()
                    assertFalse(blocked.progressed)
                    assertTrue(blocked.needsMore)
                    assertTrue(blocked.blocked)
                    assertArrayEquals(all, read(child))
                    child.close()
                    assertEquals("Lease release only changes bookkeeping", beforeReleaseSyncs, syncs)
                    assertEligibilityMatchesOwnership(store)
                    failMarkerSync = true
                    val failedCleanup = assertThrows(IOException::class.java) {
                        store.performRetentionMaintenanceStep()
                    }
                    assertEquals("Injected marker sync failure", failedCleanup.message)
                    assertTrue(store.retentionMaintenanceNeeded())
                    assertEligibilityMatchesOwnership(store)
                    failMarkerSync = false
                    var passes = 0
                    while (store.retentionMaintenanceNeeded()) {
                        assertTrue(passes++ < 4)
                        val step = store.performRetentionMaintenanceStep()
                        assertTrue(step.progressed)
                        assertFalse(step.blocked)
                        assertEligibilityMatchesOwnership(store)
                    }
                    assertEquals(3, passes)
                    requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                        assertArrayEquals(expected, read(it))
                    }
                }
                PersistentAudioChunkStore(root, overwriteOldest = looping).use { reopened ->
                    configure(reopened, 240, deferred = true)
                    assertEligibilityMatchesOwnership(reopened)
                    assertFalse(reopened.retentionMaintenanceNeeded())
                    requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use {
                        assertArrayEquals(expected, read(it))
                    }
                }
            }
        }
    }

    @Test
    fun restartWithLostIndexes_rebuildsEligibleSubsetForAbandonedLeaseRetirees() = withRoot { root ->
        val part = ByteArray(160) { (it * 29).toByte() }
        val abandoned = PersistentAudioChunkStore(root, overwriteOldest = false)
        configure(abandoned, Long.MAX_VALUE)
        repeat(3) {
            abandoned.append(part, 0, part.size)
            abandoned.sealActiveChunk()
        }
        // Process death abandons leases without running their release callbacks.
        val lease = requireNotNull(abandoned.acquireRange(0.0, abandoned.durationSeconds()))
        configure(abandoned, 160, deferred = true)
        repeat(2) { assertTrue(abandoned.performRetentionMaintenanceStep().progressed) }
        assertArrayEquals(part + part + part, read(lease))
        abandoned.close()
        File(root, BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(1))
        File(root, BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(2))

        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened, 160, deferred = true)
            assertEligibilityMatchesOwnership(reopened)
            val eligible = installCountingRetiredMap(reopened, "eligibleRetiredById")
            assertEquals(2, eligible.size)
            requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use {
                assertArrayEquals(part, read(it))
            }
            repeat(2) { index ->
                val step = reopened.performRetentionMaintenanceStep()
                assertTrue(step.progressed)
                assertFalse(step.blocked)
                assertEquals(index == 0, step.needsMore)
                assertEligibilityMatchesOwnership(reopened)
            }
            assertEquals(2L, eligible.inspections)
            assertFalse(reopened.retentionMaintenanceNeeded())
        }
    }

    private fun assertEligibilityMatchesOwnership(store: PersistentAudioChunkStore) {
        fun records(name: String): Map<UInt, PersistentAudioChunkStore.ChunkRecord> {
            val field = PersistentAudioChunkStore::class.java.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return field.get(store) as Map<UInt, PersistentAudioChunkStore.ChunkRecord>
        }
        val all = records("retiredById")
        val eligible = records("eligibleRetiredById")
        val expected = all.entries.filter { it.value.refCount == 0 }.associate { it.key to it.value }
        assertEquals(expected, eligible)
    }

    private class CountingRetiredMap : LinkedHashMap<UInt, PersistentAudioChunkStore.ChunkRecord>() {
        var inspections = 0L
        override val values: MutableCollection<PersistentAudioChunkStore.ChunkRecord>
            get() {
                val source = super.values
                return object : AbstractMutableCollection<PersistentAudioChunkStore.ChunkRecord>() {
                    override val size: Int get() = source.size
                    override fun add(element: PersistentAudioChunkStore.ChunkRecord): Boolean =
                        throw UnsupportedOperationException()
                    override fun iterator(): MutableIterator<PersistentAudioChunkStore.ChunkRecord> {
                        val delegate = source.iterator()
                        return object : MutableIterator<PersistentAudioChunkStore.ChunkRecord> {
                            override fun hasNext(): Boolean = delegate.hasNext()
                            override fun next(): PersistentAudioChunkStore.ChunkRecord {
                                inspections++
                                return delegate.next()
                            }
                            override fun remove() = delegate.remove()
                        }
                    }
                }
            }
    }

    private fun installCountingRetiredMap(
        store: PersistentAudioChunkStore,
        name: String = "retiredById",
    ): CountingRetiredMap {
        val field = PersistentAudioChunkStore::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val original = field.get(store) as Map<UInt, PersistentAudioChunkStore.ChunkRecord>
        return CountingRetiredMap().also {
            it.putAll(original)
            field.set(store, it)
        }
    }

    private fun configure(store: PersistentAudioChunkStore, bytes: Long, deferred: Boolean = false) {
        store.configure(
            requestedRetentionMode = RetentionMode.SIZE,
            requestedRetentionValue = bytes,
            requestedSampleRate = 8_000,
            requestedChannelCount = 1,
            sampleFormat = PcmSampleFormat.PCM_16,
            deferRetentionCleanup = deferred,
        )
    }

    private fun read(lease: PersistentAudioChunkStore.RangeLease): ByteArray {
        val output = ByteArrayOutputStream()
        val result = lease.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        return output.toByteArray()
    }

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/eligibility-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
