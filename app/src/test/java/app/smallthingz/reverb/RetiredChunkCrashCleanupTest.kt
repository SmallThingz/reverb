package app.smallthingz.reverb

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RetiredChunkCrashCleanupTest {
    @Test fun deferredRecoveryWithLostIndexesCanCaptureBeforeRetiredClaimsAreCleaned() = fixture { root ->
        val claim = interruptedRetirement(root)
        File(root, BUFFER_INDEX_A_FILE_NAME).delete()
        File(root, BUFFER_INDEX_B_FILE_NAME).delete()
        PersistentAudioChunkStore(root).use { recovered ->
            recovered.configure(RetentionMode.SIZE, 65536L, 8000, 1, deferRetentionCleanup = true)
            assertTrue(claim.isFile)
            val newest = ByteArray(512) { (it * 31 + 5).toByte() }
            assertEquals(newest.size, recovered.append(newest, 0, newest.size))
            val bytes = java.io.ByteArrayOutputStream()
            requireNotNull(recovered.acquireRange(0.0, recovered.durationSeconds())).use { lease ->
                lease.readNormalized(8000, 1, PcmSampleFormat.PCM_16) { source, offset, count ->
                    bytes.write(source, offset, count); count
                }
            }
            assertArrayEquals(newest, bytes.toByteArray())
        }
        assertFalse(claim.exists())
    }

    @Test fun serviceStartupDefersAbandonedClaimWorkButKeepsItsJournalUntilVerifiedCleanup() = fixture { root ->
        val claim = interruptedRetirement(root)
        PersistentAudioChunkStore(root).use { recovered ->
            recovered.configure(RetentionMode.SIZE, 65536L, 8000, 1, deferRetentionCleanup = true)
            assertFalse(recovered.hasData())
            assertTrue("Startup deleted a claim before the background step", claim.isFile)
            assertTrue("Startup lost the claim's durable authority", File(root, "retired/0").isFile)
            assertTrue(recovered.retentionMaintenanceNeeded())
            assertTrue(recovered.performRetentionMaintenanceStep().progressed)
            assertFalse(claim.exists())
            assertFalse(File(root, "retired/0").exists())
            assertFalse(recovered.retentionMaintenanceNeeded())
        }
    }

    @Test fun deferredCrashClaimRevalidatesChangedBytesBeforeCleanup() = fixture { root ->
        val claim = interruptedRetirement(root)
        PersistentAudioChunkStore(root).use { recovered ->
            recovered.configure(RetentionMode.SIZE, 65536L, 8000, 1, deferRetentionCleanup = true)
            val changed = claim.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            claim.writeBytes(changed)
            assertTrue(recovered.performRetentionMaintenanceStep().progressed)
            assertFalse(recovered.hasData())
            assertTrue("Changed claim bytes were destroyed", claim.exists() ||
                File(root, "preserved").listFiles().orEmpty().any { it.readBytes().contentEquals(changed) })
        }
    }

    @Test fun deferredRangeReleaseDoesNoDiskWorkAndMaintenanceRetiresOneChunkAtATime() = fixture { root ->
        var barriers = 0
        var notifications = 0
        PersistentAudioChunkStore(root, directorySync = { barriers++ },
            onMaintenanceNeeded = { notifications++ }).use { store ->
            store.configure(RetentionMode.SIZE, 65536L, 8000, 1, deferRetentionCleanup = true)
            val pcm = ByteArray(16384) { (it * 17).toByte() }
            store.append(pcm, 0, pcm.size)
            store.sealActiveChunk()
            val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
            store.clear()
            val before = barriers
            val pending = File(root, "chunks").listFiles()!!.size
            assertTrue(pending > 1)
            lease.close()
            assertEquals("Lease release performed synchronous durability work", before, barriers)
            assertEquals(1, notifications)
            assertTrue(store.retentionMaintenanceNeeded())
            store.performRetentionMaintenanceStep()
            assertEquals(pending - 1, File(root, "chunks").listFiles()!!.size)
            var steps = 1
            while (store.retentionMaintenanceNeeded()) {
                assertTrue(++steps <= pending)
                assertTrue(store.performRetentionMaintenanceStep().progressed)
            }
            assertEquals(0, File(root, "chunks").listFiles()!!.size)
        }
    }

    @Test fun abandonedRangeRetirementClaimIsRemovedWithoutResurrectingAudio() = fixture { root ->
        val claim = interruptedRetirement(root)
        PersistentAudioChunkStore(root).use { recovered ->
            recovered.configure(RetentionMode.SIZE, 65536L, 8000, 1)
            assertFalse(recovered.hasData())
        }
        assertFalse("A completed retirement claim was stranded", claim.exists())
        assertEquals("Known retired PCM was needlessly preserved", 0,
            File(root, "preserved").listFiles()?.size ?: 0)
        assertEquals(0, File(root, "retired").listFiles()?.size ?: 0)
    }

    @Test fun replacementClaimIsPreservedEvenWithTheSameHeaderAndBytes() = fixture { root ->
        val claim = interruptedRetirement(root)
        val bytes = claim.readBytes()
        val original = File(root, "original-owned-claim")
        Files.move(claim.toPath(), original.toPath())
        claim.writeBytes(bytes) // Different object, even when its content is equal.
        PersistentAudioChunkStore(root).use { recovered ->
            recovered.configure(RetentionMode.SIZE, 65536L, 8000, 1)
            assertFalse(recovered.hasData())
        }
        val preserved = File(root, "preserved").listFiles().orEmpty()
        assertTrue("Replacement bytes were deleted", claim.exists() || preserved.any { it.readBytes().contentEquals(bytes) })
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun malformedRetirementMarkerNeverAuthorizesClaimDeletion() = fixture { root ->
        val claim = interruptedRetirement(root)
        val bytes = claim.readBytes()
        File(root, "retired/0").writeText("v3|0|torn")
        PersistentAudioChunkStore(root).use { recovered ->
            recovered.configure(RetentionMode.SIZE, 65536L, 8000, 1)
            assertFalse(recovered.hasData())
        }
        assertTrue(claim.exists() || File(root, "preserved").listFiles().orEmpty().any { it.readBytes().contentEquals(bytes) })
    }

    @Test fun explicitClearSealsActiveChunkBeforeLeaseRetirement() = fixture { root ->
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
            val pcm = ByteArray(512) { it.toByte() }
            store.append(pcm, 0, pcm.size)
            val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
            store.clear()
            lease.close()
            assertFalse(store.hasData())
            assertEquals("Clear preserved its own ACTIVE chunk as changed", 0,
                File(root, "preserved").listFiles()?.size ?: 0)
            assertEquals(0, File(root, "chunks").listFiles()?.size ?: 0)
        }
    }

    private fun interruptedRetirement(root: File): File {
        val store = PersistentAudioChunkStore(root)
        store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
        val pcm = ByteArray(512) { (it * 23).toByte() }
        store.append(pcm, 0, pcm.size)
        store.sealActiveChunk()
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())) // Dies with the simulated process.
        store.clear()
        val chunk = File(root, "chunks/0")
        assertTrue(chunk.isFile)
        val claim = File(chunk.parentFile, ".reverb-retired-delete-${UUID.randomUUID()}.pending")
        Files.move(chunk.toPath(), claim.toPath()) // Crash between rename claim and unlink.
        store.close()
        return claim
    }

    private fun fixture(block: (File) -> Unit) {
        val parent = File("build/tmp/retired-crash-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
