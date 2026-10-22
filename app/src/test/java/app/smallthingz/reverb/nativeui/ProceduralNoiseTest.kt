package app.smallthingz.reverb.nativeui

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProceduralNoiseTest {
    @Test fun grainHasStableFullTileAndReleasedPalette() {
        val pixels = generateNoisePixels()
        assertEquals(1024 * 1024, pixels.size)
        val bytes = ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("04bafc239a3167b4169e12ac5b3d4b402024bc9bf76256226fd928c7e210420a", digest)
        val counts = IntArray(26)
        for (pixel in pixels) {
            val alpha = pixel ushr 24
            val rgb = pixel and 0x00ffffff
            assertTrue(alpha in 5..17)
            assertTrue(rgb == 0 || rgb == 0x00ffffff)
            counts[alpha - 5 + if (rgb == 0) 0 else 13]++
        }
        // Fixed seed, not a probabilistic test: keep every released grain level well populated.
        assertTrue(counts.all { it in 39_000..42_000 })
        pixels[0] = 0
        assertEquals(0x0f000000, generateNoisePixels()[0])
    }

    @Test fun simultaneousRequestsScheduleOneSharedValue() {
        val value = Any()
        val generated = AtomicInteger()
        val scheduled = AtomicInteger()
        val completed = AtomicInteger()
        val cache = NoiseTileCache { generated.incrementAndGet(); value }
        val begin = CountDownLatch(1)
        val done = CountDownLatch(8)
        val jobs = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        repeat(8) {
            Thread {
                begin.await()
                cache.start({ scheduled.incrementAndGet(); jobs.add(it) }, { completed.incrementAndGet() })
                done.countDown()
            }.start()
        }
        begin.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, scheduled.get())
        assertNull(cache.valueOrNull())
        jobs.remove().invoke()
        assertSame(value, cache.valueOrNull())
        repeat(10) { cache.start({ fail("Ready cache rescheduled") }, { fail("Ready cache republished") }) }
        assertEquals(1, generated.get())
        assertEquals(1, completed.get())
    }

    @Test fun generationFailureIsPublishedOnceAndNeverRetriedPerDraw() {
        val failure = IllegalStateException("generation failed")
        var attempts = 0
        var completions = 0
        val cache = NoiseTileCache<Any> { attempts++; throw failure }
        repeat(10) { cache.start({ it() }, { completions++ }) }
        assertSame(failure, cache.failureOrNull())
        assertNull(cache.valueOrNull())
        assertEquals(1, attempts)
        assertEquals(1, completions)
    }

    @Test fun schedulingFailureDoesNotLeavePendingGenerationOrRunLateWork() {
        val failure = IllegalStateException("worker unavailable")
        var lateWork: (() -> Unit)? = null
        var generated = 0
        var completions = 0
        val cache = NoiseTileCache { generated++; Any() }
        cache.start({ lateWork = it; throw failure }, { completions++ })
        lateWork!!.invoke()
        assertSame(failure, cache.failureOrNull())
        assertEquals(0, generated)
        assertEquals(1, completions)
    }

    @Test fun schedulerErrorAfterCompletionCannotReplacePublishedValue() {
        val value = Any()
        val failure = IllegalStateException("late scheduler error")
        var completions = 0
        val cache = NoiseTileCache { value }
        try {
            cache.start({ it(); throw failure }, { completions++ })
            fail("Late error was swallowed")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertSame(value, cache.valueOrNull())
        assertNull(cache.failureOrNull())
        assertEquals(1, completions)
    }
}
