package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PersistentAudioSaveSealFailureTest {
    @Test fun durableCloseFailurePreservesContinuousPcmAndClosesOnce() = withRoot { root ->
        val store = newStore(root)
        val pcm = ByteArray(512) { (it * 17).toByte() }
        store.append(pcm, 0, pcm.size)
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        var closes = 0
        replaceAccess(store) { file -> FailingCloseAccess(file, "close fault") { closes++ } }
        val failure = assertThrows(PersistentAudioChunkStore.SaveSealFailure::class.java) {
            lease.withSealedRange { error("must not admit") }
        }
        assertFalse(failure.persistenceUncertain)
        assertSame(store, failure.sourceStore)
        assertEquals("close fault", failure.cause?.message)
        assertEquals(1, closes)
        store.append(pcm, 0, pcm.size)
        val actual = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { whole ->
            whole.withSealedRange { frozen ->
                assertEquals(2, frozen.segments.size)
                assertTrue(frozen.segments[1].record.continuesPreviousChunk)
            }
            read(whole)
        }
        val reference = newStore(File(root, "reference"))
        reference.append(pcm + pcm, 0, pcm.size * 2)
        val expected = requireNotNull(reference.acquireRange(0.0, reference.durationSeconds())).use { read(it) }
        assertArrayEquals(expected, actual)
        lease.close(); store.close(); reference.close()
        assertEquals(1, closes)
    }

    @Test fun partialSealWritePoisonsOwnerEvenAfterActiveRecordClearedAndPreservesCloseFailure() = withRoot { root ->
        val store = newStore(root)
        val pcm = ByteArray(512) { it.toByte() }
        store.append(pcm, 0, pcm.size)
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        var closes = 0
        replaceAccess(store) { file -> object : FailingCloseAccess(file, "secondary close fault", { closes++ }) {
            override fun write(bytes: ByteArray) {
                super.write(bytes, 0, 7)
                throw IOException("partial slot fault")
            }
        } }
        val failure = assertThrows(PersistentAudioChunkStore.SaveSealFailure::class.java) {
            lease.withSealedRange { error("must not admit") }
        }
        assertTrue(failure.persistenceUncertain)
        assertEquals("partial slot fault", failure.cause?.cause?.message)
        assertEquals("secondary close fault", failure.cause?.cause?.suppressed?.single()?.message)
        assertEquals(1, closes)
        assertThrows(IOException::class.java) { store.append(pcm, 0, pcm.size) }
        assertThrows(IOException::class.java) { configure(store) }
        assertThrows(IOException::class.java) { store.clear() }
        assertThrows(PersistentAudioChunkStore.SaveSealFailure::class.java) { lease.withSealedRange { } }
        lease.close()
        assertThrows(IOException::class.java) { store.close() }
        assertEquals(1, closes)
        newStore(root).use { reopened ->
            val recovered = requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use { read(it, 8000) }
            assertArrayEquals(pcm, recovered)
        }
    }

    @Test fun syncFailurePoisonsRetryWithoutActiveRecordAndFreshOwnerRecovers() = withRoot { root ->
        var failSync = false
        var failedSyncs = 0
        val store = PersistentAudioChunkStore(root, mutableSlotSync = { descriptor ->
            if (failSync) {
                failedSyncs++
                throw IOException("slot fsync fault")
            }
            descriptor.sync()
        }).also(::configure)
        val pcm = ByteArray(512) { (it * 19).toByte() }
        store.append(pcm, 0, pcm.size)
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        failSync = true
        val failure = assertThrows(PersistentAudioChunkStore.SaveSealFailure::class.java) {
            lease.withSealedRange { error("must not admit") }
        }
        assertTrue(failure.persistenceUncertain)
        assertEquals("slot fsync fault", failure.cause?.cause?.message)
        assertEquals(1, failedSyncs)
        failSync = false
        assertThrows(IOException::class.java) { store.append(pcm, 0, pcm.size) }
        assertThrows(PersistentAudioChunkStore.SaveSealFailure::class.java) { lease.withSealedRange { } }
        assertEquals(1, failedSyncs)
        lease.close()
        assertThrows(IOException::class.java) { store.close() }
        newStore(root).use { reopened ->
            assertArrayEquals(pcm, requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use { read(it, 8000) })
        }
    }

    @Test fun preexistingAppendPoisonCannotBecomeSuccessfulSaveSeal() = withRoot { root ->
        val store = newStore(root)
        val pcm = ByteArray(64)
        store.append(pcm, 0, pcm.size)
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        val field = PersistentAudioChunkStore::class.java.getDeclaredField("activeAppendBoundaryFailure").apply { isAccessible = true }
        field.set(store, IOException("prior uncertain append"))
        val failure = assertThrows(PersistentAudioChunkStore.SaveSealFailure::class.java) { lease.withSealedRange { } }
        assertTrue(failure.persistenceUncertain)
        assertThrows(IOException::class.java) { store.append(pcm, 0, pcm.size) }
        lease.close()
        assertThrows(IOException::class.java) { store.close() }
    }

    @Test fun manifestActionFailureIsNotASealFailureAndClosedLeasedStoreStillSeals() = withRoot { root ->
        val store = newStore(root)
        val pcm = ByteArray(64)
        store.append(pcm, 0, pcm.size)
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        store.close()
        val expected = IOException("manifest failure")
        val actual = assertThrows(IOException::class.java) { lease.withSealedRange { throw expected } }
        assertSame(expected, actual)
        lease.withSealedRange { assertEquals(pcm.size.toLong(), it.segments.single().record.payloadBytes) }
        lease.close()
    }

    @Test fun emptyActiveChunkCloseFailureIsObservableAndNeverInventsPcm() = withRoot { root ->
        val store = newStore(root)
        val pcm = ByteArray(64) { it.toByte() }
        store.append(pcm, 0, pcm.size)
        store.sealActiveChunk()
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        // Exercise the real empty chunk path before its first payload write.
        val create = PersistentAudioChunkStore::class.java.getDeclaredMethod("createActiveChunkLocked", java.lang.Long::class.java)
            .apply { isAccessible = true }
        synchronized(store) { assertNotNull(create.invoke(store, null)) }
        var closes = 0
        replaceAccess(store) { file -> FailingCloseAccess(file, "empty close fault") { closes++ } }
        val failure = assertThrows(PersistentAudioChunkStore.SaveSealFailure::class.java) { lease.withSealedRange { } }
        assertFalse(failure.persistenceUncertain)
        assertEquals("empty close fault", failure.cause?.message)
        assertEquals(1, closes)
        assertArrayEquals(pcm, read(lease, 8000))
        assertEquals(pcm.size.toLong(), store.countFilledBytes())
        lease.close(); store.close()
        assertEquals(1, closes)
    }

    @Test fun routingPausesOnlyUncertainExactCaptureOwnerAndRecordsCloseIncidents() = withRoot { root ->
        val source = newStore(root)
        val other = newStore(File(root, "other"))
        var pauses = 0
        var incidents = 0
        fun dispatch(uncertain: Boolean, owner: PersistentAudioChunkStore?) {
            PersistentAudioChunkStore.SaveSealFailure(source, uncertain, IOException("fault"))
                .dispatchCaptureFailure(owner, null, { pauses++ }, { incidents++ })
        }
        dispatch(true, source)
        assertEquals(1, pauses); assertEquals(0, incidents)
        dispatch(false, source)
        dispatch(true, other)
        dispatch(true, null)
        assertEquals(1, pauses); assertEquals(3, incidents)
        val reportingFailure = IOException("incident reporting failed")
        val failure = PersistentAudioChunkStore.SaveSealFailure(source, false, IOException("close"))
        failure.dispatchCaptureFailure(source, null, { error("must not pause") }, { throw reportingFailure })
        assertSame(reportingFailure, failure.suppressed.single())
        source.close(); other.close()
    }

    @Test fun closeFaultFixtureCountsLaterExternalRetryButNotNestedJdkClose() = withRoot { root ->
        var attempts = 0
        val access = FailingCloseAccess(File(root, "close-probe"), "outer fault") { attempts++ }
        access.write(ByteArray(32))
        access.fd.sync()
        access.channel // Also exercise JDKs which create the channel only on demand.
        assertEquals("outer fault", assertThrows(IOException::class.java) { access.close() }.message)
        assertEquals(1, attempts)
        assertFalse(access.fd.valid())
        assertEquals("outer fault", assertThrows(IOException::class.java) { access.close() }.message)
        assertEquals(2, attempts)
        assertFalse(access.fd.valid())
    }

    private open class FailingCloseAccess(
        file: File,
        private val failureMessage: String,
        private val onExternalClose: () -> Unit,
    ) : RandomAccessFile(file, "rw") {
        private var insideSuperClose = false

        override fun close() {
            // JDK21's channel close calls its RAF parent recursively. Count only calls
            // outside that stack; resetting the guard lets a real owner retry fail tests.
            if (insideSuperClose) {
                super.close()
                return
            }
            onExternalClose()
            insideSuperClose = true
            try { super.close() } finally { insideSuperClose = false }
            assertFalse("Fault must follow real descriptor close", fd.valid())
            throw IOException(failureMessage)
        }
    }

    private fun newStore(root: File) = PersistentAudioChunkStore(root).also(::configure)
    private fun configure(store: PersistentAudioChunkStore) =
        store.configure(RetentionMode.SIZE, Long.MAX_VALUE, 8000, 1, PcmSampleFormat.PCM_16)

    private fun read(lease: PersistentAudioChunkStore.RangeLease, rate: Int = 16000): ByteArray {
        val output = ByteArrayOutputStream()
        lease.readNormalized(rate, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE, 28) { bytes, offset, count ->
            output.write(bytes, offset, count); count
        }
        return output.toByteArray()
    }

    private fun replaceAccess(store: PersistentAudioChunkStore, factory: (File) -> RandomAccessFile) {
        val recordField = PersistentAudioChunkStore::class.java.getDeclaredField("activeRecord").apply { isAccessible = true }
        val record = recordField.get(store) as PersistentAudioChunkStore.ChunkRecord
        val accessField = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess").apply { isAccessible = true }
        (accessField.get(store) as RandomAccessFile).close()
        accessField.set(store, factory(record.file))
    }

    private fun withRoot(action: (File) -> Unit) {
        val root = Files.createTempDirectory("save-seal-failure-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
