package app.smallthingz.reverb.nativeui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import smallthingz.ui.runtime.MonotonicFrameClock

class NativeInlineWaveformMotionTest {
    @Test fun unchangedFramesAreSkippedAcrossWaveformRevisionsAndTrueVisualChangesPublish() = runBlocking {
        val clock = ManualFrames()
        val published = mutableListOf<ProgressiveWaveformFrame>()
        var scale = 0f
        val motion = NativeInlineWaveformMotion(clock, CoroutineScope(coroutineContext), { scale }) { published += it }
        try {
            motion.bind(data(2, 1), loading = false, morphStarted = false)
            repeat(12) { motion.bind(data(2, 1), loading = false, morphStarted = false) }
            assertEquals(1, published.size)

            val full = data(4, 4)
            motion.bind(full, loading = false, morphStarted = false)
            assertEquals(2, published.size)
            assertEquals(1f, published.last().visibleCoarseFraction, 0f)
            assertEquals(1f, published.last().visibleDetailFraction, 0f)

            motion.bind(full, loading = false, morphStarted = true)
            assertEquals(3, published.size)
            assertEquals(1f, published.last().morphProgress, 0f)
            assertEquals(0, clock.pendingCount)

            scale = 1f
            motion.bind(full, loading = true, morphStarted = true)
            yield()
            assertEquals(1, clock.pendingCount)
            clock.frame(1_000_000_000L)
            yield()
            assertEquals(3, published.size)
            clock.frame(1_100_000_000L)
            yield()
            assertEquals(4, published.size)
            assertEquals(.1f, published.last().wobblePhaseSeconds, .000001f)
        } finally {
            motion.close()
        }
    }

    @Test fun zeroScalePublishesNewAvailabilityAndMorphWithoutRequestingFrames() = runBlocking {
        val clock = ManualFrames()
        var latest: ProgressiveWaveformFrame? = null
        val motion = NativeInlineWaveformMotion(clock, CoroutineScope(coroutineContext), { 0f }) { latest = it }
        try {
            motion.bind(data(2, 1), loading = true, morphStarted = true)
            yield()
            assertEquals(.5f, latest!!.visibleCoarseFraction, 0f)
            assertEquals(.25f, latest!!.visibleDetailFraction, 0f)
            assertEquals(1f, latest!!.morphProgress, 0f)
            assertEquals(0f, latest!!.wobblePhaseSeconds, 0f)
            assertEquals(0, clock.pendingCount)
            motion.bind(data(4, 4), loading = false, morphStarted = true)
            yield()
            assertEquals(1f, latest!!.visibleCoarseFraction, 0f)
            assertEquals(1f, latest!!.visibleDetailFraction, 0f)
            assertEquals(0, clock.pendingCount)
        } finally { motion.close() }
    }

    @Test fun runningLoopSnapsAtZeroAndResumesWithoutCountingTheStoppedInterval() = runBlocking {
        val clock = ManualFrames()
        var scale = 1f
        var latest: ProgressiveWaveformFrame? = null
        val motion = NativeInlineWaveformMotion(clock, CoroutineScope(coroutineContext), { scale }) { latest = it }
        try {
            motion.bind(data(4, 4), true, true); yield()
            clock.frame(10_000_000_000L); yield()
            clock.frame(10_100_000_000L); yield()
            val phase = latest!!.wobblePhaseSeconds
            assertEquals(.1f, phase, .000001f)
            assertTrue(latest!!.morphProgress < 1f)
            scale = 0f
            clock.frame(10_200_000_000L); yield()
            assertEquals(phase, latest!!.wobblePhaseSeconds, 0f)
            assertEquals(1f, latest!!.morphProgress, 0f)
            assertEquals(1f, latest!!.visibleCoarseFraction, 0f)
            assertEquals(1f, latest!!.visibleDetailFraction, 0f)
            assertEquals(0, clock.pendingCount)
            scale = 2f
            motion.bind(data(4, 4), true, true); yield()
            clock.frame(100_000_000_000L); yield()
            assertEquals(phase, latest!!.wobblePhaseSeconds, 0f)
            clock.frame(100_200_000_000L); yield()
            assertEquals(phase + .1f, latest!!.wobblePhaseSeconds, .000001f)
        } finally { motion.close() }
    }

    @Test fun sameTargetBindAtZeroCancelsTheAlreadyQueuedFrame() = runBlocking {
        val clock = ManualFrames()
        var scale = 1f
        var latest: ProgressiveWaveformFrame? = null
        val motion = NativeInlineWaveformMotion(clock, CoroutineScope(coroutineContext), { scale }) { latest = it }
        try {
            motion.bind(data(4, 4), true, true); yield()
            assertEquals(1, clock.pendingCount)
            scale = 0f
            motion.bind(data(4, 4), true, true); yield()
            assertEquals(1f, latest!!.morphProgress, 0f)
            assertEquals(0, clock.pendingCount)
        } finally { motion.close() }
    }

    @Test fun phaseIntegratesEachPositiveScaleAndSettlingDrains() = runBlocking {
        val clock = ManualFrames()
        var scale = 2f
        var latest: ProgressiveWaveformFrame? = null
        val motion = NativeInlineWaveformMotion(clock, CoroutineScope(coroutineContext), { scale }) { latest = it }
        try {
            motion.bind(data(0, 0), true, false); yield()
            clock.frame(0L); yield()
            clock.frame(200_000_000L); yield()
            assertEquals(.1f, latest!!.wobblePhaseSeconds, .000001f)
            scale = .5f
            clock.frame(300_000_000L); yield()
            assertEquals(.3f, latest!!.wobblePhaseSeconds, .000001f)
            motion.bind(data(0, 0), false, false); yield()
            repeat(14) { clock.frame(400_000_000L + it * 10_000_000L); yield() }
            assertEquals(0, clock.pendingCount)
            val phase = latest!!.wobblePhaseSeconds
            motion.bind(data(0, 0), true, false); yield()
            clock.frame(100_000_000_000L); yield()
            assertEquals(phase, latest!!.wobblePhaseSeconds, 0f)
            motion.close(); yield()
            assertEquals(0, clock.pendingCount)
        } finally { motion.close() }
    }

    @Test fun detachedLoadingMotionWaitsWithoutIntegratingHiddenTimeAndKeepsLatestTargets() = runBlocking {
        val clock = ManualFrames()
        val published = mutableListOf<ProgressiveWaveformFrame>()
        val motion = NativeInlineWaveformMotion(clock, CoroutineScope(coroutineContext), { 1f }) { published += it }
        try {
            motion.bind(data(2, 1), loading = true, morphStarted = true)
            yield()
            assertEquals(1, clock.pendingCount)
            clock.frame(1_000_000_000L); yield()
            clock.frame(1_100_000_000L); yield()
            val visible = published.last()
            val visibleCount = published.size
            assertEquals(.1f, visible.wobblePhaseSeconds, .000001f)
            assertTrue(visible.morphProgress > 0f && visible.morphProgress < 1f)

            motion.setPresentationActive(false)
            assertEquals(0, clock.pendingCount)
            motion.bind(data(4, 4), loading = true, morphStarted = true)
            clock.frame(20_000_000_000L); yield()
            assertEquals(0, clock.pendingCount)
            assertEquals(visibleCount, published.size)

            motion.setPresentationActive(true)
            motion.setPresentationActive(true)
            yield()
            assertEquals(1, clock.pendingCount)
            clock.frame(30_000_000_000L); yield()
            assertEquals(visibleCount, published.size)
            assertEquals(visible, published.last())
            clock.frame(30_100_000_000L); yield()
            assertEquals(.2f, published.last().wobblePhaseSeconds, .000001f)
            assertTrue(published.last().morphProgress > visible.morphProgress)
            assertTrue(published.last().morphProgress < 1f)
            clock.frame(31_000_000_000L); yield()
            assertEquals(1f, published.last().visibleCoarseFraction, 0f)
            assertEquals(1f, published.last().visibleDetailFraction, 0f)
            assertEquals(1f, published.last().morphProgress, 0f)
            motion.close()
            assertEquals(0, clock.pendingCount)
        } finally { motion.close() }
    }

    @Test fun aCapturedFrameFromBeforeDeactivationCannotSampleAfterReactivation() = runBlocking {
        val clock = ManualFrames()
        val published = mutableListOf<ProgressiveWaveformFrame>()
        val motion = NativeInlineWaveformMotion(clock, CoroutineScope(coroutineContext), { 1f }) { published += it }
        try {
            motion.bind(data(4, 4), loading = true, morphStarted = true)
            yield()
            clock.frame(1_000_000_000L); yield()
            val stale = clock.captureFrame(1_100_000_000L)
            motion.setPresentationActive(false)
            motion.setPresentationActive(true)
            yield()
            assertEquals(1, clock.pendingCount)
            val beforeStale = published.toList()
            stale(); yield()
            assertEquals(beforeStale, published)
            assertEquals(1, clock.pendingCount)
            clock.frame(30_000_000_000L); yield()
            assertEquals(beforeStale, published)
        } finally { motion.close() }
    }

    private fun data(coarse: Int, detail: Int) = ProgressiveWaveformData(
        FloatArray(4), coarse, FloatArray(4), detail, detailAvailable = true)

    internal class ManualFrames : MonotonicFrameClock {
        private class Request(val deliver: (Long) -> Unit)
        private val requests = mutableListOf<Request>()
        val pendingCount get() = requests.size
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
            val request = Request { time -> continuation.resumeWith(runCatching { onFrame(time) }) }
            requests.add(request)
            continuation.invokeOnCancellation { requests.remove(request) }
        }
        fun captureFrame(time: Long): () -> Unit {
            val ready = requests.toList()
            requests.clear()
            return { ready.forEach { it.deliver(time) } }
        }
        fun frame(time: Long) = captureFrame(time)()
    }
}
