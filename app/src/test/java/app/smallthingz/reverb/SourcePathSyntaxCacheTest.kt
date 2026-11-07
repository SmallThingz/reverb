package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SourcePathSyntaxCacheTest {
    @Test
    fun repeatedExactPathsReuseValidationButEntryBoundEvictsOlderPaths() {
        var validations = 0
        val cache = BoundedSourcePathSyntaxCache(2, 1024) { validations++; File(it) }
        val first = cache.resolve("/first")
        assertSame(first, cache.resolve(String("/first".toCharArray())))
        assertEquals(1, validations)
        cache.resolve("/second")
        val third = cache.resolve("/third")
        cache.resolve("/first")
        assertEquals(4, validations)
        assertSame(third, cache.resolve("/third"))
        assertEquals(4, validations)
    }

    @Test
    fun retainedTextBudgetEvictsEvenBelowEntryLimitAndOversizedPathsAreNeverCached() {
        var validations = 0
        val cache = BoundedSourcePathSyntaxCache(100, 64) { validations++; File(it) }
        repeat(10) { cache.resolve("/source-path-$it") }
        cache.resolve("/source-path-0")
        assertEquals(11, validations)
        val oversized = "/" + "x".repeat(128)
        cache.resolve(oversized)
        cache.resolve(oversized)
        assertEquals(13, validations)
    }

    @Test
    fun invalidPathsNeverEnterCacheAndEqualLookingNoncanonicalPathsRemainRejected() {
        var validations = 0
        val cache = BoundedSourcePathSyntaxCache(16, 4096) {
            validations++
            DurablePcmPins.checkedSourcePath(it)
        }
        val canonical = "/root/store/$BUFFER_CHUNKS_FOLDER_NAME/1"
        assertEquals(canonical, cache.resolve(canonical).path)
        val invalid = listOf("relative/store/$BUFFER_CHUNKS_FOLDER_NAME/1",
            "/root/store/../store/$BUFFER_CHUNKS_FOLDER_NAME/1", "$canonical/",
            "/root/store/$BUFFER_CHUNKS_FOLDER_NAME/01", "$canonical\n")
        invalid.forEach { path ->
            repeat(2) { assertThrows(IOException::class.java) { cache.resolve(path) } }
        }
        assertEquals(1 + invalid.size * 2, validations)
        assertEquals(canonical, cache.resolve(canonical).path)
        assertEquals(1 + invalid.size * 2, validations)
    }

    @Test
    fun cachedLexicalPathNeverCachesTheObjectCurrentlyAtThatPath() {
        val root = Files.createTempDirectory("source-path-syntax").toFile()
        try {
            val file = File(root, "store/$BUFFER_CHUNKS_FOLDER_NAME/1").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val cache = BoundedSourcePathSyntaxCache(16, 4096, DurablePcmPins::checkedSourcePath)
            val selected = cache.resolve(file.absolutePath)
            val before = resolveFileIdentity(selected)
            assertTrue(before.isNotBlank())
            Files.move(file.toPath(), File(file.parentFile, "preserved-original").toPath())
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertSame(selected, cache.resolve(file.absolutePath))
            val after = resolveFileIdentity(cache.resolve(file.absolutePath))
            assertTrue(after.isNotBlank())
            assertFalse(sameFileObjectAcrossRename(before, after))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun slowValidationDoesNotHoldCacheLockOrBlockAnotherPath() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val cache = BoundedSourcePathSyntaxCache(16, 4096) { path ->
            calls.incrementAndGet()
            if (path == "/slow") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            File(path)
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val slow = workers.submit<File> { cache.resolve("/slow") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val fast = workers.submit<File> { cache.resolve("/fast") }
            assertEquals("/fast", fast.get(5, TimeUnit.SECONDS).path)
            assertEquals(1L, release.count)
            release.countDown()
            assertEquals("/slow", slow.get(5, TimeUnit.SECONDS).path)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
