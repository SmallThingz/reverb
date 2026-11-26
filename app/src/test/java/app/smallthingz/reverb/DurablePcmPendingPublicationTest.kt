package app.smallthingz.reverb

import android.app.Application
import android.system.Os
import android.system.StructStat
import android.system.StructTimespec
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PcmPendingPublicationIoShadow::class])
class DurablePcmPendingPublicationTest {
    @Test
    fun captureAllocatesDuringBlockedPinSyncWithoutUnprotectingSource() = withPending { f ->
        val original = f.source.readBytes()
        assertTrue(f.pending.isFile)
        assertTrue(DurablePcmPins.isProtected(f.source, ::descriptorProof))
        assertFalse(DurablePcmPins.isProtected(f.next, ::descriptorProof))
        // Cross a full chunk boundary: both fresh header and mutable-slot seal must progress.
        val second = ByteArray(1024 * 1024 + 4096) { (it % 71).toByte() }
        val append = f.executor.submit<Int> {
            val count = f.store.append(second, 0, second.size)
            f.store.checkpoint()
            count
        }
        assertEquals(second.size, append.get(5, TimeUnit.SECONDS).toInt())
        assertEquals(1L, f.resume.count)
        assertFalse(f.publication.isDone)
        assertTrue(f.next.isFile)
        assertArrayEquals(original, f.source.readBytes())
        assertTrue(DurablePcmPins.isProtected(f.source, ::descriptorProof))
        f.resume.countDown()
        val snapshot = f.publication.get(5, TimeUnit.SECONDS)
        assertFalse(f.pending.exists())
        val marker = DurablePcmPins.referenceFile(f.directory, snapshot.objects.single())
        assertEquals(snapshot.objects.associateBy { it.sourcePath },
            DurablePcmBatchReferences.decode(marker.readBytes(), f.directory.name))
        DurablePcmPins.verify(f.directory, snapshot.objects.single(), ::descriptorProof, ::nativeIdentity)
        assertArrayEquals(f.pcm + second, read(f.store))
        assertArrayEquals(original, f.source.readBytes())
        DurablePcmPins.release(f.directory, snapshot.objects, ::descriptorProof)
        assertFalse(DurablePcmPins.isProtected(f.source, ::descriptorProof))
    }

    @Test
    fun failedSyncRetiresLiveExceptionWithoutAnyDirectoryMembershipChange() {
        val failure = IOException("blocked pin sync failed")
        withPending(failure, fixedRootRevision = true) { f ->
            val pendingBytes = f.pending.readBytes()
            val rootRevision = checkNotNull(f.seededRootRevision)
            assertEquals(rootRevision, productionRootRevision(f.pending.parentFile))
            // A permissive live classification must never survive in the directory cache.
            repeat(2) { assertFalse(DurablePcmPins.isProtected(f.next, ::descriptorProof)) }
            f.resume.countDown()
            assertSame(failure, assertThrows(ExecutionException::class.java) {
                f.publication.get(5, TimeUnit.SECONDS)
            }.cause)
            assertArrayEquals(pendingBytes, f.pending.readBytes())
            // This is the production Os.lstat-based revision, fixed before .pending creation,
            // not a separate NIO observation after the cache had already been bypassed.
            assertEquals(rootRevision, productionRootRevision(f.pending.parentFile))
            assertTrue(DurablePcmPins.isProtected(f.next, ::descriptorProof))
            assertTrue(DurablePcmPins.isProtected(f.source, ::descriptorProof))
            // A dead publisher's complete or torn pending marker never grants allocation.
            f.pending.writeBytes(byteArrayOf(1))
            assertTrue(DurablePcmPins.isProtected(f.next, ::descriptorProof))
            assertThrows(IOException::class.java) {
                DurablePcmPins.release(f.directory, f.plan.objects, ::descriptorProof)
            }
            assertTrue(f.pending.exists())
        }
    }

    @Test
    fun replacementPendingInodeCannotBorrowTheLivePublisherException() = withPending { f ->
        assertFalse(DurablePcmPins.isProtected(f.next, ::descriptorProof))
        val bytes = f.pending.readBytes()
        val heldIdentity = nativeIdentity(f.pending)
        assertTrue(f.pending.delete())
        f.pending.writeBytes(bytes)
        assertFalse(sameFileObjectAcrossRename(heldIdentity, nativeIdentity(f.pending)))
        assertTrue(DurablePcmPins.isProtected(f.next, ::descriptorProof))
        assertTrue(DurablePcmPins.isProtected(f.source, ::descriptorProof))
        f.resume.countDown()
        assertTrue(assertThrows(ExecutionException::class.java) {
            f.publication.get(5, TimeUnit.SECONDS)
        }.cause is IOException)
        assertTrue(f.pending.exists())
        assertTrue(DurablePcmPins.isProtected(f.next, ::descriptorProof))
    }

    @Test
    fun frozenMembershipProtectsMissingSourcesAndUnknownSiblingMarkers() = withPending { f ->
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (f.plan.objects as MutableList<DurablePcmPins.ObjectProof>).clear()
        }
        val moved = File(f.source.parentFile, "source-held-for-test")
        Files.move(f.source.toPath(), moved.toPath())
        try {
            assertFalse(f.source.exists())
            assertTrue(DurablePcmPins.isProtected(f.source, ::descriptorProof))
            assertFalse(DurablePcmPins.isProtected(f.next, ::descriptorProof))
        } finally { Files.move(moved.toPath(), f.source.toPath()) }
        val orphan = File(f.pending.parentFile, "batch-${UUID.randomUUID()}.pending")
        orphan.writeBytes(byteArrayOf(1))
        assertTrue(DurablePcmPins.isProtected(f.next, ::descriptorProof))
        assertTrue(orphan.delete())
        assertFalse(DurablePcmPins.isProtected(f.next, ::descriptorProof))
        f.resume.countDown()
        val snapshot = f.publication.get(5, TimeUnit.SECONDS)
        DurablePcmPins.release(f.directory, snapshot.objects, ::descriptorProof)
    }

    private data class Pending(
        val store: PersistentAudioChunkStore,
        val directory: File,
        val pcm: ByteArray,
        val plan: DurablePcmPins.Snapshot,
        val executor: ExecutorService,
        val resume: CountDownLatch,
        val publication: Future<DurablePcmPins.Snapshot>,
        val seededRootRevision: String?,
    ) {
        val source = File(plan.objects.single().sourcePath)
        val next = File(source.parentFile, (source.name.toUInt() + 1u).toString())
        val pending = File(source.parentFile.parentFile, "durable-pcm-refs/batch-${directory.name}.pending")
    }

    private fun withPending(syncFailure: IOException? = null, fixedRootRevision: Boolean = false,
        action: (Pending) -> Unit) {
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        PcmPendingPublicationIoShadow.reset()
        val root = Files.createTempDirectory("pcm-live-publication").toFile().absoluteFile
        ServiceStateOsShadow.fixtureRoot = root.toPath()
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            PersistentAudioChunkStore(File(root, "source")).use { store ->
                store.configure(RetentionMode.SIZE, Long.MAX_VALUE, 16_000, 1, PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true)
                val pcm = ByteArray(4096) { (it % 113).toByte() }
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                val directory = File(root, "jobs/${UUID.randomUUID()}").apply { mkdirs() }
                val refsRoot = File(root, "source/durable-pcm-refs")
                val seededRootRevision = if (fixedRootRevision) {
                    check(refsRoot.mkdir())
                    PcmPendingPublicationIoShadow.holdRootRevision(refsRoot.toPath())
                    assertTrue("Native revision adapter is unavailable", Os.lstat(refsRoot.path).st_ino != 0L)
                    val revision = productionRootRevision(refsRoot)
                    assertFalse(DurablePcmPins.isProtected(File(root, "source/$BUFFER_CHUNKS_FOLDER_NAME/1")))
                    assertEquals("Empty-root cache was not seeded", revision, cachedRootRevision(refsRoot))
                    revision
                } else null
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    val plan = AtomicReference<DurablePcmPins.Snapshot>()
                    val publication = executor.submit<DurablePcmPins.Snapshot> {
                        lease.withSealedRange { range ->
                            DurablePcmPins.link(directory, range, ::nativeIdentity, ::descriptorProof,
                                syncFile = { fd ->
                                    assertFalse(Thread.holdsLock(DurablePcmPins.mutationLock))
                                    entered.countDown()
                                    check(resume.await(10, TimeUnit.SECONDS)) { "Publisher fixture did not resume" }
                                    syncFailure?.let { throw it }
                                    fd.sync()
                                }, syncDirectory = {
                                    assertFalse(Thread.holdsLock(DurablePcmPins.mutationLock))
                                    forceRecordingDirectoryDurable(it)
                                }) { plan.set(it) }
                        }
                    }
                    try {
                        assertTrue("Publisher did not reach sync seam", entered.await(5, TimeUnit.SECONDS))
                        action(Pending(store, directory, pcm, checkNotNull(plan.get()), executor, resume, publication, seededRootRevision))
                    } finally {
                        resume.countDown()
                        // Always drain before the lease/store closes, including an assertion failure.
                        runCatching { publication.get(5, TimeUnit.SECONDS) }
                    }
                }
            }
        } finally {
            resume.countDown()
            executor.shutdownNow()
            try {
                check(executor.awaitTermination(5, TimeUnit.SECONDS))
            } finally {
                val leak = JournalHostIoShadow.finishFixture()
                PcmPendingPublicationIoShadow.reset()
                ServiceStateOsShadow.fixtureRoot = null
                root.deleteRecursively()
                leak?.let { throw it }
            }
        }
    }

    // Existing host stat/descriptor adapter; no real Android durability claim.
    private fun descriptorProof(identity: String, fd: FileDescriptor) =
        fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(fd))
    private fun nativeIdentity(file: File): String = resolveFileIdentity(file)

    private fun productionRootRevision(root: File): String = synchronized(DurablePcmPins.mutationLock) {
        val method = DurablePcmPins::class.java.getDeclaredMethod("referenceRootRevision", File::class.java, String::class.java)
            .apply { isAccessible = true }
        checkNotNull(method.invoke(DurablePcmPins, root, resolveDirectoryIdentity(root)) as? String) {
            "Production reference-root revision was null"
        }
    }

    private fun cachedRootRevision(root: File): String? = synchronized(DurablePcmPins.mutationLock) {
        val field = DurablePcmPins::class.java.getDeclaredField("referenceRootChecks").apply { isAccessible = true }
        val entry = (field.get(DurablePcmPins) as Map<*, *>)[root.absolutePath] ?: return@synchronized null
        entry.javaClass.getDeclaredField("revision").apply { isAccessible = true }.get(entry) as? String
    }
    private fun read(store: PersistentAudioChunkStore): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
            val bytes = ByteArrayOutputStream()
            lease.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { buffer, offset, size ->
                bytes.write(buffer, offset, size); size
            }
            bytes.toByteArray()
        }
}

/** Reuse the real host-I/O adapter; only the reference directory ctime is held constant. */
@Implements(Os::class)
class PcmPendingPublicationIoShadow {
    companion object {
        @Volatile private var root: Path? = null
        @Volatile private var ctime: StructTimespec? = null

        fun holdRootRevision(path: Path) {
            val owned = path.toAbsolutePath().normalize()
            ctime = JournalHostIoShadow.lstat(owned.toString()).st_ctim
            root = owned
        }
        fun reset() { root = null; ctime = null }

        @JvmStatic @Implementation fun lstat(path: String): StructStat {
            val actual = JournalHostIoShadow.lstat(path)
            if (Path.of(path).toAbsolutePath().normalize() != root) return actual
            return StructStat(actual.st_dev, actual.st_ino, actual.st_mode, actual.st_nlink,
                actual.st_uid, actual.st_gid, actual.st_rdev, actual.st_size,
                actual.st_atim, actual.st_mtim, checkNotNull(ctime), actual.st_blksize, actual.st_blocks)
        }
        @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat = JournalHostIoShadow.fstat(fd)
        @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor =
            JournalHostIoShadow.open(path, flags, mode)
        @JvmStatic @Implementation fun dup(fd: FileDescriptor): FileDescriptor = JournalHostIoShadow.dup(fd)
        @JvmStatic @Implementation fun read(fd: FileDescriptor, buffer: ByteArray, offset: Int, count: Int): Int =
            JournalHostIoShadow.read(fd, buffer, offset, count)
        @JvmStatic @Implementation fun fsync(fd: FileDescriptor) = JournalHostIoShadow.fsync(fd)
        @JvmStatic @Implementation fun close(fd: FileDescriptor) = JournalHostIoShadow.close(fd)
    }
}
