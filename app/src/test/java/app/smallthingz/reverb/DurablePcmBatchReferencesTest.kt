package app.smallthingz.reverb

import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class DurablePcmBatchReferencesTest {
    @Test
    fun fanoutPublishesOneSyncedReferenceAndNoPcmCopy() = fixture(256) { root, range ->
        val directory = job(root)
        var fileBarriers = 0
        val directoryBarriers = ArrayList<File>()
        var planSeen = false
        val originalBytes = range.segments.associate { it.record.file to it.record.file.readBytes() }
        val links = originalBytes.keys.associateWith { Files.getAttribute(it.toPath(), "unix:nlink") }
        val snapshot = DurablePcmPins.link(directory, range, ::nativeIdentity, ::descriptorProof,
            syncFile = { fd ->
                assertTrue(planSeen)
                assertFalse(Thread.holdsLock(DurablePcmPins.mutationLock))
                fileBarriers++
                fd.sync()
            },
            syncDirectory = {
                assertFalse(Thread.holdsLock(DurablePcmPins.mutationLock))
                directoryBarriers += it
                forceRecordingDirectoryDurable(it)
            }) {
            assertEquals(256, it.objects.size)
            assertFalse(File(root, "store/durable-pcm-refs").exists())
            planSeen = true
        }
        assertEquals(1, fileBarriers)
        assertEquals(3, directoryBarriers.size)
        val markers = requireNotNull(File(root, "store/durable-pcm-refs").listFiles())
        assertEquals(1, markers.size)
        assertEquals(snapshot.objects.associateBy { it.sourcePath },
            DurablePcmBatchReferences.decode(markers.single().readBytes(), directory.name))
        originalBytes.forEach { (file, bytes) ->
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(links[file], Files.getAttribute(file.toPath(), "unix:nlink"))
            assertTrue(DurablePcmPins.isProtected(file, ::descriptorProof))
        }
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof)
        assertTrue(originalBytes.keys.none { DurablePcmPins.isProtected(it, ::descriptorProof) })
    }

    @Test
    fun overlappingBatchesProtectOnlyTheirSourcesAndRejectSubsetRelease() = fixture(3) { root, range ->
        val firstDirectory = job(root)
        val secondDirectory = job(root)
        val first = pin(firstDirectory, range.copy(segments = range.segments.take(2)))
        val second = pin(secondDirectory, range.copy(segments = range.segments.drop(1)))
        assertThrows(IOException::class.java) {
            DurablePcmPins.release(firstDirectory, first.objects.take(1), ::descriptorProof)
        }
        assertTrue(DurablePcmPins.hasReference(firstDirectory, first.objects.first()))
        DurablePcmPins.release(firstDirectory, first.objects, ::descriptorProof)
        assertFalse(DurablePcmPins.isProtected(range.segments[0].record.file, ::descriptorProof))
        assertTrue(DurablePcmPins.isProtected(range.segments[1].record.file, ::descriptorProof))
        assertTrue(DurablePcmPins.isProtected(range.segments[2].record.file, ::descriptorProof))
        DurablePcmPins.release(secondDirectory, second.objects, ::descriptorProof)
        assertTrue(range.segments.none { DurablePcmPins.isProtected(it.record.file, ::descriptorProof) })
    }

    @Test
    fun unknownTornAndUnprovableBatchesVetoAllStoreRetirement() = fixture(2) { root, range ->
        val directory = job(root)
        val snapshot = pin(directory, range.copy(segments = range.segments.take(1)))
        val marker = DurablePcmPins.referenceFile(directory, snapshot.objects.single())
        val bytes = marker.readBytes()
        val unrelated = range.segments[1].record.file
        assertFalse(DurablePcmPins.isProtected(unrelated, ::descriptorProof))
        assertTrue(DurablePcmPins.isProtected(unrelated) { _, _ -> false })
        for (corrupt in listOf(bytes.copyOf(7), bytes + byteArrayOf(1), bytes.clone().apply { this[20] = (this[20].toInt() xor 1).toByte() })) {
            marker.writeBytes(corrupt)
            assertTrue(DurablePcmPins.isProtected(unrelated, ::descriptorProof))
            assertThrows(IOException::class.java) { DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof) }
            assertTrue(marker.exists())
        }
        marker.writeBytes(bytes)
        val partial = File(marker.parentFile, marker.name + ".pending").apply { writeBytes(bytes.copyOf(1)) }
        assertTrue(DurablePcmPins.isProtected(unrelated, ::descriptorProof))
        assertThrows(IOException::class.java) { DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof) }
        assertTrue(marker.exists())
        partial.delete()
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof)
    }

    @Test
    fun recoveredMissingBatchSyncsItsParentAndPreservesOverlappingLegacyOwner() = fixture(1) { root, range ->
        val directory = job(root)
        val snapshot = pin(directory, range)
        val marker = DurablePcmPins.referenceFile(directory, snapshot.objects.single())
        val refsRoot = marker.parentFile
        val otherDirectory = job(root)
        val other = pin(otherDirectory, range)
        val otherBatch = DurablePcmPins.referenceFile(otherDirectory, other.objects.single())
        val proof = other.objects.single()
        val legacy = File(File(refsRoot, File(proof.sourcePath).name), otherDirectory.name)
        legacy.parentFile.mkdirs()
        legacy.writeText("reverb-pcm-reference-v1\n${otherDirectory.name}\n${proof.name}\n${proof.sourcePath}\n${proof.identity}\n" +
            "${proof.payloadOffset}\n${proof.payloadBytes}\n${proof.checksum}\n${proof.sourceDirectoryIdentity}\n${proof.storeDirectoryIdentity}\n")
        assertTrue(otherBatch.delete())
        forceRecordingDirectoryDurable(legacy.parentFile)
        forceRecordingDirectoryDurable(refsRoot)
        val legacyBytes = legacy.readBytes()
        // Model restart after the batch unlink: no process-local removal barrier exists.
        assertTrue(marker.delete())
        val barriers = ArrayList<File>()
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof, syncDirectory = {
            barriers += it
            forceRecordingDirectoryDurable(it)
        })
        assertEquals(listOf(refsRoot), barriers)
        assertArrayEquals(legacyBytes, legacy.readBytes())
        assertFalse(DurablePcmPins.hasReference(directory, snapshot.objects.single()))
        assertTrue(DurablePcmPins.hasReference(otherDirectory, proof))
        assertTrue(DurablePcmPins.isProtected(File(proof.sourcePath), ::descriptorProof))
        DurablePcmPins.release(otherDirectory, other.objects, ::descriptorProof)
    }

    @Test
    fun recoveredMissingBatchBarrierFailureRetainsOwnershipUntilRetry() = fixture(1) { root, range ->
        val directory = job(root)
        val snapshot = pin(directory, range)
        val marker = DurablePcmPins.referenceFile(directory, snapshot.objects.single())
        assertTrue(marker.delete())
        val failure = IOException("recovered batch absence barrier failed")
        assertSame(failure, assertThrows(IOException::class.java) {
            DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof, syncDirectory = { throw failure })
        })
        assertTrue(DurablePcmPins.hasReference(directory, snapshot.objects.single()))
        assertTrue(DurablePcmPins.isProtected(range.segments.single().record.file, ::descriptorProof))
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof)
        assertFalse(DurablePcmPins.hasReference(directory, snapshot.objects.single()))
    }

    @Test
    fun recoveredMissingRootMustRemainAbsentAcrossParentBarrier() = fixture(1) { root, range ->
        val directory = job(root)
        val snapshot = pin(directory, range)
        val marker = DurablePcmPins.referenceFile(directory, snapshot.objects.single())
        val refsRoot = marker.parentFile
        assertTrue(marker.delete())
        assertTrue(refsRoot.delete())
        assertThrows(IOException::class.java) {
            DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof, syncDirectory = {
                assertEquals(refsRoot.parentFile, it)
                forceRecordingDirectoryDurable(it)
                assertTrue(refsRoot.mkdir())
            })
        }
        assertTrue(DurablePcmPins.hasReference(directory, snapshot.objects.single()))
        val retryBarriers = ArrayList<File>()
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof, syncDirectory = {
            retryBarriers += it
            forceRecordingDirectoryDurable(it)
        })
        assertEquals(listOf(refsRoot), retryBarriers)
        assertFalse(DurablePcmPins.hasReference(directory, snapshot.objects.single()))
    }

    @Test
    fun failedRemovalBarrierKeepsProtectionUntilExactRetry() = fixture(2) { root, range ->
        val directory = job(root)
        val snapshot = pin(directory, range.copy(segments = range.segments.take(1)))
        val marker = DurablePcmPins.referenceFile(directory, snapshot.objects.single())
        val failure = IOException("directory sync failed after unlink")
        assertSame(failure, assertThrows(IOException::class.java) {
            DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof, syncDirectory = { throw failure })
        })
        assertFalse(marker.exists())
        assertTrue(DurablePcmPins.hasReference(directory, snapshot.objects.single()))
        assertTrue(range.segments.all { DurablePcmPins.isProtected(it.record.file, ::descriptorProof) })
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof)
        assertFalse(DurablePcmPins.hasReference(directory, snapshot.objects.single()))
        assertTrue(range.segments.none { DurablePcmPins.isProtected(it.record.file, ::descriptorProof) })
    }

    @Test
    fun admissionBarrierFailureCannotReturnAcceptedPins() = fixture(2) { root, range ->
        val directory = job(root)
        var plan: DurablePcmPins.Snapshot? = null
        val failure = IOException("pin publication barrier failed")
        assertSame(failure, assertThrows(IOException::class.java) {
            DurablePcmPins.link(directory, range, ::nativeIdentity, ::descriptorProof,
                syncDirectory = { throw failure }) { plan = it }
        })
        val snapshot = requireNotNull(plan)
        assertTrue(snapshot.objects.all { DurablePcmPins.isProtected(File(it.sourcePath), ::descriptorProof) })
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof)
    }

    @Test
    fun codecRejectsWrongJobDuplicateGeometryAndTrailingBytes() = fixture(1) { root, range ->
        val directory = job(root)
        val snapshot = pin(directory, range)
        val encoded = DurablePcmBatchReferences.encode(directory.name, snapshot.objects)
        assertThrows(IOException::class.java) { DurablePcmBatchReferences.decode(encoded, UUID.randomUUID().toString()) }
        assertThrows(IOException::class.java) { DurablePcmBatchReferences.decode(encoded + byteArrayOf(0), directory.name) }
        assertThrows(IOException::class.java) { DurablePcmBatchReferences.encode(directory.name, snapshot.objects + snapshot.objects) }
        assertThrows(IOException::class.java) {
            DurablePcmBatchReferences.encode(directory.name, listOf(snapshot.objects.single().copy(payloadBytes = -1)))
        }
        DurablePcmPins.release(directory, snapshot.objects, ::descriptorProof)
    }

    private fun pin(directory: File, range: FrozenPcmRange) =
        DurablePcmPins.link(directory, range, ::nativeIdentity, ::descriptorProof) { }
    private fun job(root: File) = File(File(root, "jobs"), UUID.randomUUID().toString()).apply { mkdirs() }
    private fun descriptorProof(identity: String, fd: FileDescriptor) = identity.isNotBlank() && fd.valid()
    private fun nativeIdentity(file: File): String = "stat:${Files.getAttribute(file.toPath(), "unix:dev")}:${Files.getAttribute(file.toPath(), "unix:ino")}:0:0:0"

    private fun fixture(count: Int, action: (File, FrozenPcmRange) -> Unit) {
        val root = Files.createTempDirectory("pcm-batch-references").toFile().absoluteFile
        try {
            val payload = byteArrayOf(1, 2, 3, 4)
            val checksum = CRC32().apply { update(payload) }.value.toInt()
            val segments = (1..count).map { index ->
                val file = File(root, "store/$BUFFER_CHUNKS_FOLDER_NAME/$index").apply {
                    parentFile.mkdirs(); writeBytes(ByteArray(16) + payload)
                }
                val record = PersistentAudioChunkStore.ChunkRecord(index.toUInt(), file,
                    PersistentAudioChunkStore.ChunkState.FINALIZED, 1000L, 4L, 2L,
                    48000, 1, PcmSampleFormat.PCM_16, checksum, 1L, 16L)
                PersistentAudioChunkStore.Segment(record, 0L, 2L, 16L, 4L, checksum, 0.0,
                    2.0 / 48000, timelineStartFrame = (index - 1L) * 2L)
            }
            action(root, FrozenPcmRange(segments, emptyList(), emptyList(), 1000L, 1001L, count * 2.0 / 48000))
        } finally { root.deleteRecursively() }
    }
}
