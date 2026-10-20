package app.smallthingz.reverb

import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeWaveformFailureTest {
    @Test
    fun readFailureAfterPartialSampling_isReportedOnceAndFinishesLoading() = runBlocking {
        val expected = IOException("chunk read failed")
        var failure: Exception? = null
        var reports = 0
        var loading = true
        var readerCloseCalls = 0
        var finishCalls = 0
        var passes = 0
        val publications = mutableListOf<Int>()
        constructRangeWaveform(
            build = {
                buildProgressiveWaveform(
                    readPass = { _, onBucket ->
                        passes++
                        try {
                            repeat(32) { onBucket(it, 0.5f) }
                            throw expected
                        } finally {
                            readerCloseCalls++
                        }
                    },
                    onSnapshot = { _, update -> publications += update.builtCount },
                )
            },
            onFailure = { reports++; failure = it },
            onFinished = { finishCalls++; loading = false },
        )
        assertPropagatedIOException(expected, failure)
        assertEquals(1, reports)
        assertEquals(1, passes)
        assertEquals(1, readerCloseCalls)
        assertEquals(1, finishCalls)
        assertFalse(loading)
        val terminalCount = publications.size
        kotlinx.coroutines.yield()
        assertEquals(terminalCount, publications.size)
    }

    @Test
    fun terminalReaderCloseFailure_remainsFailureRatherThanCompletedWaveform() = runBlocking {
        val expected = IOException("reader close failed")
        var failure: Exception? = null
        var finished = false
        var reports = 0
        var passes = 0
        var closeCalls = 0
        var finishCalls = 0
        constructRangeWaveform(
            build = {
                buildProgressiveWaveform(
                    readPass = { _, onBucket ->
                        passes++
                        try {
                            onBucket(0, 0.4f)
                        } finally {
                            closeCalls++
                            throw expected
                        }
                    },
                    onSnapshot = { _, _ -> },
                )
            },
            onFailure = { reports++; failure = it },
            onFinished = { finishCalls++; finished = true },
        )
        assertPropagatedIOException(expected, failure)
        assertEquals(1, reports)
        assertEquals(1, passes)
        assertEquals(1, closeCalls)
        assertEquals(1, finishCalls)
        assertTrue(finished)
    }

    @Test
    fun cancelledBlockingRead_cleansUpWithoutFailureOrLatePublication() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reported = false
        var finished = false
        var closed = false
        var publications = 0
        val job = launch {
            constructRangeWaveform(
                build = {
                    buildProgressiveWaveform(
                        readPass = { _, onBucket ->
                            entered.countDown()
                            try {
                                check(release.await(5, TimeUnit.SECONDS))
                                onBucket(0, 0.5f)
                                throw IOException("read failed after owner cancellation")
                            } finally {
                                closed = true
                            }
                        },
                        onSnapshot = { _, _ -> publications++ },
                    )
                },
                onFailure = { reported = true },
                onFinished = { finished = true },
            )
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel()
        } finally {
            release.countDown()
            job.join()
        }
        assertTrue(job.isCancelled)
        assertTrue(closed)
        assertTrue(finished)
        assertFalse(reported)
        assertEquals(0, publications)
    }

    @Test
    fun explicitCancellationAndFatalErrorsAreNeverConvertedToVisualizationFailure() = runBlocking {
        for (expected in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
            var observed: Throwable? = null
            var reported = false
            var finished = false
            try {
                constructRangeWaveform(
                    build = { throw expected },
                    onFailure = { reported = true },
                    onFinished = { finished = true },
                )
            } catch (error: Throwable) {
                observed = error
            }
            assertSame(expected, observed)
            assertFalse(reported)
            assertTrue(finished)
        }
    }

    private fun assertPropagatedIOException(expected: IOException, actual: Exception?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        // Coroutine stack-trace recovery may copy an exception across a suspension
        // boundary. It must preserve the original instance as a cause, not replace it
        // with an unrelated exception that happens to have the same type and message.
        val visited = IdentityHashMap<Throwable, Boolean>()
        var original: Throwable? = actual
        while (original != null && original !== expected && visited.put(original, true) == null) {
            assertEquals(expected.javaClass, original.javaClass)
            assertEquals(expected.message, original.message)
            original = original.cause
        }
        assertSame("Failure must retain the injected exception in its cause chain", expected, original)
    }
}
