package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PersistentAudioCriticalCoverageTest {
    @Test fun pairAdmissionKeepsIndependentPcmAndOwnershipUntilEachClose() = withFixture { f ->
        val first = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val second = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
        assertThrows(IOException::class.java) { f.a.hasData() }
        PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.b)) { false }
        assertNull(f.registry.tryAcquire(f.aPath))
        assertNull(f.registry.tryAcquire(f.bPath))
        configure(f.a); configure(f.b)
        assertEquals(first.size, f.a.append(first, 0, first.size))
        assertEquals(second.size, f.b.append(second, 0, second.size))
        assertArrayEquals(first, read(f.a))
        assertArrayEquals(second, read(f.b))
        f.a.close()
        assertAvailable(f.registry, f.aPath)
        assertNull(f.registry.tryAcquire(f.bPath))
        f.b.close()
        assertAvailable(f.registry, f.bPath)
        val reopenedA = f.store(f.aPath)
        val reopenedB = f.store(f.bPath)
        reopenedA.use { reopenedB.use {
            PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(reopenedA, reopenedB)) { false }
            assertArrayEquals(first, read(reopenedA))
            assertArrayEquals(second, read(reopenedB))
        } }
    }

    @Test fun cancellationWhileOtherDirectoryHeldLeavesNoPartialPair() = withFixture { f ->
        val held = requireNotNull(f.registry.tryAcquire(f.bPath))
        val seen = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.b)) {
                    seen.countDown()
                    cancelled.get()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.isDaemon = true
        held.use { Closeable {
            cancelled.set(true)
            worker.interrupt()
            worker.join(5000)
            check(!worker.isAlive) { "admission worker did not terminate" }
        }.use {
            worker.start()
            assertTrue(seen.await(5, TimeUnit.SECONDS))
            assertAvailable(f.registry, f.aPath)
            assertNull(f.registry.tryAcquire(f.bPath))
            cancelled.set(true)
            worker.join(5000)
            assertFalse("admission worker did not stop", worker.isAlive)
            assertTrue(failure.get() is AudioStoreAdmissionCancelledException)
            assertAvailable(f.registry, f.aPath)
            assertNull(f.registry.tryAcquire(f.bPath))
        } }
        assertAvailable(f.registry, f.bPath)
        PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.b)) { false }
        configure(f.a); configure(f.b)
        assertEquals(2, f.a.append(byteArrayOf(21, 22), 0, 2))
        assertEquals(2, f.b.append(byteArrayOf(23, 24), 0, 2))
        assertArrayEquals(byteArrayOf(21, 22), read(f.a))
        assertArrayEquals(byteArrayOf(23, 24), read(f.b))
    }

    @Test fun cancellationAfterClaimAndAfterPublicationRollsBackBothOwners() {
        for (cancelAt in 2..3) withFixture { f ->
            var calls = 0
            assertThrows(AudioStoreAdmissionCancelledException::class.java) {
                PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.b)) { ++calls == cancelAt }
            }
            assertEquals(cancelAt, calls)
            assertAvailable(f.registry, f.aPath)
            assertAvailable(f.registry, f.bPath)
            PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.b)) { false }
            configure(f.a); configure(f.b)
            val first = byteArrayOf(31, 32, 33, 34)
            val second = byteArrayOf(41, 42, 43, 44)
            assertEquals(first.size, f.a.append(first, 0, first.size))
            assertEquals(second.size, f.b.append(second, 0, second.size))
            assertArrayEquals(first, read(f.a))
            assertArrayEquals(second, read(f.b))
        }
    }

    @Test fun duplicateStoresDirectoriesAndMixedRegistriesDoNotClaimAnything() = withFixture { f ->
        assertThrows(IllegalArgumentException::class.java) {
            PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.a)) { false }
        }
        val duplicatePath = f.store(f.aPath)
        val otherRegistry = AudioStoreDirectoryOwnership()
        val mixed = f.store(f.bPath, otherRegistry)
        duplicatePath.use { mixed.use {
            assertThrows(IllegalArgumentException::class.java) {
                PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, duplicatePath)) { false }
            }
            assertThrows(IllegalArgumentException::class.java) {
                PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, mixed)) { false }
            }
            assertAvailable(f.registry, f.aPath)
            assertAvailable(f.registry, f.bPath)
            assertAvailable(otherRegistry, f.bPath)
            PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.b)) { false }
            configure(f.a); configure(f.b)
            assertEquals(2, f.a.append(byteArrayOf(51, 52), 0, 2))
            assertEquals(2, f.b.append(byteArrayOf(53, 54), 0, 2))
            assertArrayEquals(byteArrayOf(51, 52), read(f.a))
            assertArrayEquals(byteArrayOf(53, 54), read(f.b))
        } }
    }

    @Test fun failurePreparingSecondStoreDoesNotStrandFirstStore() = withFixture { f ->
        f.b.close()
        assertThrows(IllegalStateException::class.java) {
            PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, f.b)) { false }
        }
        assertAvailable(f.registry, f.aPath)
        assertAvailable(f.registry, f.bPath)
        val replacement = f.store(f.bPath)
        replacement.use {
            PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(f.a, replacement)) { false }
            configure(f.a); configure(replacement)
            assertEquals(2, f.a.append(byteArrayOf(61, 62), 0, 2))
            assertEquals(2, replacement.append(byteArrayOf(63, 64), 0, 2))
            assertArrayEquals(byteArrayOf(61, 62), read(f.a))
            assertArrayEquals(byteArrayOf(63, 64), read(replacement))
        }
    }

    private class Fixture(root: File) {
        val registry = AudioStoreDirectoryOwnership()
        val aPath = File(root, "a")
        val bPath = File(root, "b")
        val a = store(aPath)
        val b = store(bPath)
        fun store(path: File, owner: AudioStoreDirectoryOwnership = registry) =
            PersistentAudioChunkStore(path, directoryOwnership = owner, requireDirectoryAdmission = true)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val parent = File("build/tmp/critical-admission-tests")
        Files.createDirectories(parent.toPath())
        val root = Files.createTempDirectory(parent.toPath(), "pair-").toFile()
        val fixture = Fixture(root)
        fixture.a.use { fixture.b.use { block(fixture) } }
        check(root.deleteRecursively()) { "Could not remove completed fixture: $root" }
    }

    private fun assertAvailable(registry: AudioStoreDirectoryOwnership, path: File) {
        requireNotNull(registry.tryAcquire(path)).close()
    }

    private fun configure(store: PersistentAudioChunkStore) {
        store.configure(RetentionMode.SIZE, 1024L, 8_000, 1, PcmSampleFormat.PCM_16)
    }

    private fun read(store: PersistentAudioChunkStore): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use(::readStoreTestPcm16Mono8Khz)
}
