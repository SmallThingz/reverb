package app.smallthingz.reverb

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host filesystem faults at the real API boundary; no production fault-injection hooks. */
class PersistentAudioChunkStoreMissingPathTest {
    @Test
    fun syncSnapshotRetiredByClearIsObsolete() = withStore { store, _ ->
        val file = activeFile(store)
        replaceActiveFile(store, BoundaryFile(file, beforePath = { store.clear() }))
        assertEquals(0L, store.syncActivePayloadToDisk())
        assertFalse(file.exists())
        assertFalse(store.hasData())
    }

    @Test
    fun obsoleteSnapshotDoesNotCreditReplacementActivePayload() = withStore { store, _ ->
        val oldFile = activeFile(store)
        replaceActiveFile(store, BoundaryFile(oldFile, beforePath = {
            store.clear()
            assertEquals(32, store.append(ByteArray(32), 0, 32))
        }))
        assertEquals(0L, store.syncActivePayloadToDisk())
        assertFalse(oldFile.exists())
        assertEquals(0L, durableBytes(store))
        assertEquals(32L, store.syncActivePayloadToDisk())
    }

    @Test
    fun missingStillCurrentActiveChunkIsAnErrorAndIsNotRecreated() = withStore { store, _ ->
        val file = activeFile(store)
        replaceActiveFile(store, BoundaryFile(file, beforePath = { Files.delete(file.toPath()) }))
        assertThrows(NoSuchFileException::class.java) { store.syncActivePayloadToDisk() }
        assertEquals(0L, durableBytes(store))
        assertFalse(file.exists())
    }

    @Test
    fun presentNonRegularActivePathRemainsAnError() = withStore { store, _ ->
        val file = activeFile(store)
        replaceActiveFile(store, BoundaryFile(file, beforePath = {
            Files.delete(file.toPath())
            Files.createDirectory(file.toPath())
        }))
        assertThrows(IOException::class.java) { store.syncActivePayloadToDisk() }
        assertEquals(0L, durableBytes(store))
        assertTrue(file.isDirectory)
    }

    @Test
    fun ordinarySyncRemainsIdempotent() = withStore { store, _ ->
        assertEquals(64L, store.syncActivePayloadToDisk())
        assertEquals(64L, durableBytes(store))
        assertEquals(0L, store.syncActivePayloadToDisk())
    }

    @Test
    fun indexDisappearingBeforeNioSizeIsAbsent() = withStore { store, root ->
        val file = indexFixture(root)
        var paths = 0
        val boundary = object : File(file.path) {
            override fun toPath(): Path {
                if (++paths == 2) Files.delete(file.toPath())
                return file.toPath()
            }
        }
        assertEquals(null, readIndex(store, boundary))
        assertEquals(2, paths)
        assertFalse(file.exists())
    }

    @Test
    fun indexDisappearingBeforeStreamOpenIsAbsent() = withStore { store, root ->
        val file = indexFixture(root)
        val boundary = BoundaryFile(file, beforeStream = { Files.delete(file.toPath()) })
        assertEquals(null, readIndex(store, boundary))
        assertTrue(boundary.streamBoundaryReached)
        assertFalse(file.exists())
    }

    @Test
    fun streamOpenFailureWithPresentDirectoryIsNotAbsence() = withStore { store, root ->
        val file = indexFixture(root)
        val boundary = BoundaryFile(file, beforeStream = {
            Files.delete(file.toPath())
            Files.createDirectory(file.toPath())
        })
        val error = assertThrows(IOException::class.java) { readIndex(store, boundary) }
        assertTrue(boundary.streamBoundaryReached)
        assertTrue(error.cause is FileNotFoundException)
        assertTrue(error.message!!.startsWith("Unable to read chunk index:"))
        assertTrue(file.isDirectory)
    }

    @Test
    fun streamOpenFailureWithUnavailableReobservationIsNotAbsence() = withStore { store, root ->
        val file = indexFixture(root)
        val boundary = BoundaryFile(file, beforeStream = { Files.delete(file.toPath()) }, denyAfterStream = true)
        val error = assertThrows(IOException::class.java) { readIndex(store, boundary) }
        assertTrue(boundary.streamBoundaryReached)
        assertTrue(error.cause is FileNotFoundException)
    }

    @Test
    fun existingIndexStillParsesAndInitiallyMissingIndexIsAbsent() = withStore { store, root ->
        store.checkpoint()
        val existing = listOf(BUFFER_INDEX_A_FILE_NAME, BUFFER_INDEX_B_FILE_NAME)
            .map { File(root, it) }.first { it.isFile }
        assertNotNull(readIndex(store, existing))
        assertEquals(null, readIndex(store, File(root, "never-created")))
    }

    private fun indexFixture(root: File): File = File(root, "racing-index").apply { writeBytes(ByteArray(128)) }

    private fun readIndex(store: PersistentAudioChunkStore, file: File): Any? = try {
        PersistentAudioChunkStore::class.java.getDeclaredMethod("readIndex", File::class.java)
            .apply { isAccessible = true }.invoke(store, file)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun activeRecord(store: PersistentAudioChunkStore): PersistentAudioChunkStore.ChunkRecord =
        PersistentAudioChunkStore::class.java.getDeclaredField("activeRecord")
            .apply { isAccessible = true }.get(store) as PersistentAudioChunkStore.ChunkRecord

    private fun activeFile(store: PersistentAudioChunkStore): File = activeRecord(store).file

    private fun replaceActiveFile(store: PersistentAudioChunkStore, file: File) {
        PersistentAudioChunkStore.ChunkRecord::class.java.getDeclaredField("file")
            .apply { isAccessible = true }.set(activeRecord(store), file)
    }

    private fun durableBytes(store: PersistentAudioChunkStore): Long =
        PersistentAudioChunkStore::class.java.getDeclaredField("activeDurablePayloadBytes")
            .apply { isAccessible = true }.getLong(store)

    private inline fun withStore(block: (PersistentAudioChunkStore, File) -> Unit) {
        val root = Files.createTempDirectory("store-missing-path-").toFile()
        val store = PersistentAudioChunkStore(root)
        try {
            store.configure(RetentionMode.SIZE, 512 * 1024L, 8_000, 1, PcmSampleFormat.PCM_16)
            assertEquals(64, store.append(ByteArray(64), 0, 64))
            block(store, root)
        } finally {
            // Simulate process teardown without checkpointing deliberately damaged paths.
            val accessField = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess")
                .apply { isAccessible = true }
            (accessField.get(store) as? java.io.RandomAccessFile)?.close()
            accessField.set(store, null)
            root.deleteRecursively()
        }
    }
}

/** The stack check selects the actual java.io constructor, never an observation or identity read. */
internal class BoundaryFile(
    private val backing: File,
    private var beforePath: (() -> Unit)? = null,
    private var beforeStream: (() -> Unit)? = null,
    private val denyAfterStream: Boolean = false,
) : File(backing.path) {
    var streamBoundaryReached = false
        private set

    override fun toPath(): Path {
        val action = beforePath
        beforePath = null
        action?.invoke()
        if (denyAfterStream && streamBoundaryReached) throw SecurityException("Injected unavailable observation")
        return backing.toPath()
    }

    override fun getPath(): String {
        if (beforeStream != null && Thread.currentThread().stackTrace.any {
                it.className == "java.io.FileInputStream" && it.methodName == "<init>"
            }
        ) {
            val action = beforeStream
            beforeStream = null
            streamBoundaryReached = true
            action!!.invoke()
        }
        return backing.path
    }
}
