package app.smallthingz.reverb

import java.io.File
import java.io.FileDescriptor
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DurableSaveOwnershipTest {
    @Test
    fun durablePlanAndConcurrentRetirementDoNotInvertJournalAndPinLocks() {
        val root = Files.createTempDirectory("save-lock-order").toFile()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val source = sourceFile(root)
            val directory = jobDirectory(root)
            val range = range(source)
            val journalLock = ReentrantLock()
            val planEntered = CountDownLatch(1)
            val cleanupHasJournal = CountDownLatch(1)
            val admission = workers.submit<DurablePcmPins.Snapshot> {
                DurablePcmPins.link(directory, range, identityResolver = ::nativeLikeIdentity,
                    descriptorMatches = ::hostDescriptorProof) {
                    assertFalse("Disk journal callback must not hold the pin mutation lock", Thread.holdsLock(DurablePcmPins.mutationLock))
                    planEntered.countDown()
                    assertTrue(cleanupHasJournal.await(5, TimeUnit.SECONDS))
                    assertTrue("Admission was deadlocked behind retirement", journalLock.tryLock(5, TimeUnit.SECONDS))
                    journalLock.unlock()
                }
            }
            val retirement = workers.submit {
                assertTrue(planEntered.await(5, TimeUnit.SECONDS))
                journalLock.lock()
                try {
                    cleanupHasJournal.countDown()
                    synchronized(DurablePcmPins.mutationLock) { /* One retired object mutation. */ }
                } finally { journalLock.unlock() }
            }
            val snapshot = admission.get(10, TimeUnit.SECONDS)
            retirement.get(10, TimeUnit.SECONDS)
            assertEquals(source.absolutePath, snapshot.objects.single().sourcePath)
            assertEquals(source, snapshot.range.segments.single().record.file)
            assertTrue(DurablePcmPins.referenceFile(directory, snapshot.objects.single()).isFile)
            assertTrue(DurablePcmPins.isProtected(source))
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(10, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test
    fun failedDurablePlanCreatesNoUnjournaledReferences() {
        val root = Files.createTempDirectory("save-plan-failure").toFile()
        try {
            val source = sourceFile(root)
            val directory = jobDirectory(root)
            try {
                DurablePcmPins.link(directory, range(source), identityResolver = ::nativeLikeIdentity,
                    descriptorMatches = ::hostDescriptorProof) {
                    throw java.io.IOException("directory barrier failed")
                }
                fail("Admission must reject failed durable plan")
            } catch (expected: java.io.IOException) {
                assertEquals("directory barrier failed", expected.message)
            }
            assertTrue(directory.listFiles()!!.isEmpty())
            assertFalse(DurablePcmPins.isProtected(source))
            assertEquals(20L, source.length())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun overlappingReferencesRetireIndependentlyWithoutChangingSourceLinkCount() {
        val root = Files.createTempDirectory("save-overlapping-references").toFile()
        try {
            val source = sourceFile(root)
            val expected = source.readBytes()
            val links = Files.getAttribute(source.toPath(), "unix:nlink")
            val firstDirectory = jobDirectory(root)
            val secondDirectory = jobDirectory(root)
            val first = DurablePcmPins.link(firstDirectory, range(source), ::nativeLikeIdentity, ::hostDescriptorProof) { }
            val second = DurablePcmPins.link(secondDirectory, range(source), ::nativeLikeIdentity, ::hostDescriptorProof) { }
            val firstMarker = DurablePcmPins.referenceFile(firstDirectory, first.objects.single())
            val secondMarker = DurablePcmPins.referenceFile(secondDirectory, second.objects.single())
            assertNotEquals(firstMarker, secondMarker)
            assertEquals(links, Files.getAttribute(source.toPath(), "unix:nlink"))
            assertTrue(firstMarker.isFile && secondMarker.isFile)
            DurablePcmPins.release(firstDirectory, first.objects, ::hostDescriptorProof)
            assertFalse(firstMarker.exists())
            assertTrue(firstMarker.parentFile!!.isDirectory)
            assertTrue(secondMarker.isFile)
            assertTrue(DurablePcmPins.isProtected(source))
            assertArrayEquals(expected, source.readBytes())
            DurablePcmPins.release(secondDirectory, second.objects, ::hostDescriptorProof)
            assertTrue(secondMarker.parentFile!!.listFiles()!!.isEmpty())
            assertFalse(DurablePcmPins.isProtected(source))
            assertArrayEquals(expected, source.readBytes())
            repeat(8) {
                val directory = jobDirectory(root)
                val snapshot = DurablePcmPins.link(directory, range(source), ::nativeLikeIdentity, ::hostDescriptorProof) { }
                val marker = DurablePcmPins.referenceFile(directory, snapshot.objects.single())
                assertTrue(marker.isFile)
                DurablePcmPins.release(directory, snapshot.objects, ::hostDescriptorProof)
                assertTrue(marker.parentFile!!.listFiles()!!.isEmpty())
            }
            assertEquals(links, Files.getAttribute(source.toPath(), "unix:nlink"))
            assertArrayEquals(expected, source.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun durableReferencePreservesRealStorePcmAfterClearAndLeaseRelease() {
        val root = Files.createTempDirectory("save-reference-clear").toFile()
        try {
            PersistentAudioChunkStore(File(root, "store")).use { store ->
                store.configure(RetentionMode.SIZE, 4096, 48_000, 1)
                val pcm = byteArrayOf(1, 2, 3, 4)
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                val directory = jobDirectory(root)
                val snapshot = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    lease.withSealedRange { frozen ->
                        DurablePcmPins.link(directory, frozen, ::nativeLikeIdentity, ::hostDescriptorProof) { }
                    }
                }
                val source = snapshot.range.segments.single().record.file
                store.clear()
                assertEquals(0.0, store.durationSeconds(), 0.0)
                assertTrue(source.isFile)
                assertTrue(DurablePcmPins.isProtected(source))
                val output = ByteArrayOutputStream()
                snapshot.range.readNormalized(48_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, size ->
                    output.write(bytes, offset, size); size
                }
                assertArrayEquals(pcm, output.toByteArray())
                DurablePcmPins.release(directory, snapshot.objects, ::hostDescriptorProof)
                assertFalse(DurablePcmPins.isProtected(source))
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun overlappingSavesSurviveClearAndStoreRecoveryBeforeJobRecovery() {
        val root = Files.createTempDirectory("save-overlap-reopen").toFile()
        try {
            val storeRoot = File(root, "store")
            val rate = 8192
            val pcm = ByteArray(4096) { ((it * 37 + it / 11) and 255).toByte() }
            fun openStore() = PersistentAudioChunkStore(storeRoot).also {
                it.configure(RetentionMode.SIZE, 16_384, rate, 1, deferRetentionCleanup = true)
            }
            fun read(snapshot: DurablePcmPins.Snapshot): ByteArray = ByteArrayOutputStream().also { output ->
                snapshot.range.readNormalized(rate, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, size ->
                    output.write(bytes, offset, size); size
                }
            }.toByteArray()
            val firstDirectory = jobDirectory(root)
            val secondDirectory = jobDirectory(root)
            lateinit var first: DurablePcmPins.Snapshot
            lateinit var second: DurablePcmPins.Snapshot
            openStore().use { store ->
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                fun pin(directory: File, start: Int, end: Int) =
                    requireNotNull(store.acquireRange(start.toDouble() / rate, end.toDouble() / rate)).use { lease ->
                        lease.withSealedRange { frozen ->
                            DurablePcmPins.link(directory, frozen, ::nativeLikeIdentity, ::hostDescriptorProof) { }
                        }
                    }
                first = pin(firstDirectory, 256, 1536)
                second = pin(secondDirectory, 1024, 1920)
                assertTrue(first.range.segments.size > 1)
                assertTrue(second.range.segments.size > 1)
                assertEquals(4, (first.objects + second.objects).map { it.sourcePath }.toSet().size)
                assertTrue(first.objects.map { it.sourcePath }.intersect(second.objects.map { it.sourcePath }.toSet()).isNotEmpty())
                store.clear()
                assertEquals(0.0, store.durationSeconds(), 0.0)
            }
            val firstExpected = pcm.copyOfRange(256 * 2, 1536 * 2)
            val secondExpected = pcm.copyOfRange(1024 * 2, 1920 * 2)
            // No coordinator or job replay runs before either store recovery pass.
            openStore().use { reopened ->
                assertEquals(0.0, reopened.durationSeconds(), 0.0)
                assertNull(reopened.acquireRange(0.0, 1.0))
                assertArrayEquals(firstExpected, read(first))
                assertArrayEquals(secondExpected, read(second))
                assertEquals(1280, read(first).size / 2)
                assertEquals(896, read(second).size / 2)
                DurablePcmPins.release(firstDirectory, first.objects, ::hostDescriptorProof)
                assertTrue(second.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
            }
            openStore().use { reopened ->
                assertEquals(0.0, reopened.durationSeconds(), 0.0)
                assertArrayEquals(secondExpected, read(second))
                DurablePcmPins.release(secondDirectory, second.objects, ::hostDescriptorProof)
            }
            openStore().use { reopened ->
                assertEquals(0.0, reopened.durationSeconds(), 0.0)
                repeat(32) {
                    if (reopened.retentionMaintenanceNeeded()) reopened.performRetentionMaintenanceStep()
                }
                assertFalse(reopened.retentionMaintenanceNeeded())
                assertTrue((first.objects + second.objects).all { !File(it.sourcePath).exists() })
                val remaining = storeRoot.walkTopDown().filter { it.isFile }
                    .map { it.relativeTo(storeRoot).path }.filter { it !in setOf("index.a", "index.b") }.toList()
                assertEquals("Hidden claims and reference metadata must also retire", emptyList<String>(), remaining)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun legacyPartialReferenceReleaseWakesCleanupAndPreservesFailedAndSharedPcm() {
        val root = Files.createTempDirectory("save-partial-release").toFile()
        try {
            val storeRoot = File(root, "store")
            val rate = 8192
            val pcm = ByteArray(4096) { ((it * 17 + it / 13) and 255).toByte() }
            val notificationFailure = IllegalStateException("maintenance notification failed")
            var failNotification = false
            PersistentAudioChunkStore(storeRoot, onMaintenanceNeeded = {
                if (failNotification) throw notificationFailure
            }).use { store ->
                store.configure(RetentionMode.SIZE, 16_384, rate, 1, deferRetentionCleanup = true)
                assertEquals(2048, store.append(pcm, 0, 2048))
                // A real capture boundary keeps the second save's context out of the exclusive prefix.
                store.sealActiveChunk()
                assertEquals(2048, store.append(pcm, 2048, 2048))
                fun pin(directory: File, start: Int) = requireNotNull(
                    store.acquireRange(start.toDouble() / rate, 2048.0 / rate),
                ).use { lease ->
                    lease.withSealedRange { frozen ->
                        DurablePcmPins.link(directory, frozen, ::nativeLikeIdentity, ::hostDescriptorProof) { }
                    }
                }
                val firstDirectory = jobDirectory(root)
                val secondDirectory = jobDirectory(root)
                val first = pin(firstDirectory, 0)
                val second = pin(secondDirectory, 1536)
                installLegacyReferences(firstDirectory, first)
                installLegacyReferences(secondDirectory, second)
                assertEquals(4, first.objects.size)
                val releasedSource = File(first.objects.first().sourcePath)
                assertFalse(second.objects.any { it.sourcePath == releasedSource.absolutePath })
                val failedSource = File(first.objects[1].sourcePath)
                val failedBytes = failedSource.readBytes()
                val corruptMarker = DurablePcmPins.referenceFile(firstDirectory, first.objects[1])
                val originalMarker = corruptMarker.readBytes()
                store.clear()
                repeat(8) { if (store.retentionMaintenanceNeeded()) store.performRetentionMaintenanceStep() }
                assertFalse(store.retentionMaintenanceNeeded())
                corruptMarker.writeBytes(originalMarker.copyOf(7))
                failNotification = true
                val failure = assertThrows(java.io.IOException::class.java) {
                    DurablePcmPins.release(firstDirectory, first.objects, ::hostDescriptorProof)
                }
                assertTrue("An earlier removed reference must wake blocked retirement", store.retentionMaintenanceNeeded())
                assertSame(notificationFailure, failure.suppressed.single())
                failNotification = false
                assertTrue(store.performRetentionMaintenanceStep().progressed)
                assertFalse(releasedSource.exists())
                assertArrayEquals(failedBytes, failedSource.readBytes())
                assertTrue(DurablePcmPins.isProtected(failedSource))
                assertTrue(second.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
                val output = ByteArrayOutputStream()
                second.range.readNormalized(rate, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, size ->
                    output.write(bytes, offset, size); size
                }
                assertArrayEquals(pcm.copyOfRange(1536 * 2, pcm.size), output.toByteArray())
                corruptMarker.writeBytes(originalMarker)
                DurablePcmPins.release(firstDirectory, first.objects, ::hostDescriptorProof)
                DurablePcmPins.release(secondDirectory, second.objects, ::hostDescriptorProof)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun releasedReferencesNotifyEveryStoreAndAggregateCallbackFailures() {
        val root = Files.createTempDirectory("save-release-listeners").toFile()
        try {
            val storeRoot = File(root, "store")
            val firstFailure = IllegalStateException("first listener")
            val secondFailure = IllegalStateException("second listener")
            var notify = false
            var secondCalls = 0
            PersistentAudioChunkStore(storeRoot, onMaintenanceNeeded = { if (notify) throw firstFailure }).use { firstStore ->
                firstStore.configure(RetentionMode.SIZE, 4096, 8192, 1, deferRetentionCleanup = true)
                val pcm = byteArrayOf(1, 2, 3, 4)
                firstStore.append(pcm, 0, pcm.size)
                val directory = jobDirectory(root)
                val snapshot = requireNotNull(firstStore.acquireRange(0.0, firstStore.durationSeconds())).use { lease ->
                    lease.withSealedRange { frozen ->
                        DurablePcmPins.link(directory, frozen, ::nativeLikeIdentity, ::hostDescriptorProof) { }
                    }
                }
                firstStore.clear()
                PersistentAudioChunkStore(storeRoot, onMaintenanceNeeded = {
                    if (notify) { secondCalls++; throw secondFailure }
                }).use { secondStore ->
                    secondStore.configure(RetentionMode.SIZE, 4096, 8192, 1, deferRetentionCleanup = true)
                    notify = true
                    val failure = assertThrows(IllegalStateException::class.java) {
                        DurablePcmPins.release(directory, snapshot.objects, ::hostDescriptorProof)
                    }
                    notify = false
                    assertSame(firstFailure, failure)
                    assertEquals(1, secondCalls)
                    assertSame(secondFailure, failure.suppressed.single())
                    assertTrue(firstStore.retentionMaintenanceNeeded())
                    assertTrue(secondStore.retentionMaintenanceNeeded())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun corruptReferenceFailsReleaseAndContinuesProtectingTheSource() {
        val root = Files.createTempDirectory("save-corrupt-reference").toFile()
        try {
            val source = sourceFile(root)
            val expected = source.readBytes()
            val directory = jobDirectory(root)
            val snapshot = DurablePcmPins.link(directory, range(source), ::nativeLikeIdentity, ::hostDescriptorProof) { }
            val marker = DurablePcmPins.referenceFile(directory, snapshot.objects.single())
            val torn = marker.readBytes().copyOf(7)
            marker.writeBytes(torn)
            assertThrows(java.io.IOException::class.java) {
                DurablePcmPins.release(directory, snapshot.objects, ::hostDescriptorProof)
            }
            assertArrayEquals(torn, marker.readBytes())
            assertTrue(DurablePcmPins.isProtected(source))
            assertArrayEquals(expected, source.readBytes())
        } finally { root.deleteRecursively() }
    }

    private fun sourceFile(root: File) = File(root, "store/chunks/1").absoluteFile.apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(16) + byteArrayOf(1, 2, 3, 4))
    }

    private fun jobDirectory(root: File) = File(File(root, "jobs"), UUID.randomUUID().toString()).apply { mkdirs() }

    private fun installLegacyReferences(directory: File, snapshot: DurablePcmPins.Snapshot) {
        val batch = DurablePcmPins.referenceFile(directory, snapshot.objects.first())
        for (proof in snapshot.objects) {
            val source = File(proof.sourcePath)
            val marker = File(File(batch.parentFile, source.name), directory.name)
            marker.parentFile.mkdirs()
            marker.writeText("reverb-pcm-reference-v1\n${directory.name}\n${proof.name}\n${proof.sourcePath}\n${proof.identity}\n" +
                "${proof.payloadOffset}\n${proof.payloadBytes}\n${proof.checksum}\n${proof.sourceDirectoryIdentity}\n${proof.storeDirectoryIdentity}\n")
        }
        assertTrue(batch.delete())
    }

    // Model only Android's unavailable descriptor syscall; marker IO/checksums/renames are real.
    private fun hostDescriptorProof(identity: String, descriptor: FileDescriptor) =
        identity.isNotBlank() && descriptor.valid()

    private fun range(file: File): FrozenPcmRange {
        val checksum = CRC32().apply { update(byteArrayOf(1, 2, 3, 4)) }.value.toInt()
        val record = PersistentAudioChunkStore.ChunkRecord(1u, file, PersistentAudioChunkStore.ChunkState.FINALIZED,
            1000L, 4L, 2L, 48000, 1, PcmSampleFormat.PCM_16, checksum, 1L, 16L)
        val segment = PersistentAudioChunkStore.Segment(record, 0L, 2L, 16L, 4L, checksum, 0.0, 2.0 / 48000,
            timelineStartFrame = 0L)
        return FrozenPcmRange(listOf(segment), emptyList(), emptyList(), 1000L, 1001L, 2.0 / 48000)
    }

    private fun nativeLikeIdentity(file: File): String {
        val device = Files.getAttribute(file.toPath(), "unix:dev") as Long
        val inode = Files.getAttribute(file.toPath(), "unix:ino") as Long
        return "stat:$device:$inode:0:0:0"
    }
}
