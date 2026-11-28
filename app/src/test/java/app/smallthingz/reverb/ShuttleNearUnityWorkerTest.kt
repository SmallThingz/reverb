package app.smallthingz.reverb

import android.app.Application
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ServiceStateOsShadow::class, ShuttleEndpointAudioTrackShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ShuttleNearUnityWorkerTest {
    @Test fun rangeKeepsFreshOverlapAndTheWallFrameBudgetThroughHandJitter() = verifyPlayback(saved = false)

    @Test fun savedLibraryKeepsFreshOverlapAndTheWallFrameBudgetThroughHandJitter() = verifyPlayback(saved = true)

    @Test fun isolatedUnityReadingsDoNotChatterAcrossTheGrainBoundary() {
        for (direction in listOf(1, -1)) {
            val mode = ShuttleUnityMode()
            var observed = 1_000_000_000L
            assertTrue(mode.usesUnityStream(direction.toFloat(), observed))
            for (rate in listOf(.99f, 1f, .99985f, 1f, 1.00015f, 1f, 1.01f)) {
                observed += TICK_MS * 1_000_000L
                assertFalse("Isolated rate $rate should stay in grains after near-unity motion",
                    mode.usesUnityStream(rate * direction, observed))
            }
            observed += TICK_MS * 1_000_000L
            assertFalse(mode.usesUnityStream(direction.toFloat(), observed))
            assertFalse("Repeated worker polls of one unity command must not count as fresh observations",
                mode.usesUnityStream(direction.toFloat(), observed))
            assertTrue("Sustained fresh unity commands should recover the unity path",
                mode.usesUnityStream(direction.toFloat(), observed + TICK_MS * 1_000_000L))
        }
    }

    @Test fun retainedPhaseAt44100() = checkRateTransition(44_100)
    @Test fun retainedPhaseAt48000() = checkRateTransition(48_000)
    @Test fun retainedPhaseAt96000() = checkRateTransition(96_000)

    @Test fun settledUnityResumesAtTheSubmittedSourceEdge() = checkSettledUnitySourceEdge(48_000)

    @Test fun settledUnityResumesAtTheSubmittedSourceEdgeAt44100() = checkSettledUnitySourceEdge(44_100)

    private fun checkSettledUnitySourceEdge(hz: Int) {
        val originFrame = hz / 2
        val referenceLevel = 16_000
        val pcm = ByteBuffer.allocate(hz * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(hz) { frame ->
                putShort(((frame % 2_048 - 1_024) * 12).toShort())
                putShort(referenceLevel.toShort())
            }
        }.array()
        val source = samples(pcm)
        val root = Files.createTempDirectory("shuttle-settled-unity-")
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root
        ShadowAudioTrack.setMinBufferSize(256)
        val mismatches = mutableListOf<String>()
        try {
            val wav = File(root.toFile(), "frame-index.wav").apply {
                writeBytes(buildWavHeaderBytes(hz, 2, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm)
            }
            val recording = RecordingEntity(wav.absolutePath, wav.name, "audio/wav", 1L, 1_000L,
                wav.length(), "PCM", RecordingStorageType.FILE, root.toString(), resolveFileIdentity(wav))
            for (direction in listOf(1, -1)) {
                val clock = AtomicLong(1_000_000_000L)
                val waits = TargetWaitGate()
                val errors = ConcurrentLinkedQueue<Throwable>()
                val authority = RecordingScrubAuthority(RecordingScrubKey(1L, 1L))
                val worker = TimelineAudioPreviewController(onTrackReleaseFailure = { errors += it },
                    shuttleNowNanos = clock::get, onShuttleTargetWait = waits::reached)
                val lower = .25
                val upper = .75
                var unityFrames = 0
                ShuttleEndpointAudioTrackShadow.reset()
                ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
                try {
                    worker.startRecordingShuttle(RuntimeEnvironment.getApplication(), recording,
                        atSeconds = originFrame.toDouble() / hz, rate = direction.toFloat(), onStarted = {},
                        onFailureAfterStart = { errors += AssertionError("Shuttle lost playback") },
                        onSourceCloseFailure = { errors += it }, fromSeconds = lower, untilSeconds = upper,
                        ordinaryAuthority = authority)
                    await("first unity write in direction $direction") {
                        ShuttleEndpointAudioTrackShadow.acquiredTracks().singleOrNull()?.playing == true
                    }
                    val track = ShuttleEndpointAudioTrackShadow.single()
                    assertEquals(hz, track.formatRate())
                    assertEquals(2, track.formatChannels())
                    clock.addAndGet(64_000_000L)
                    await("settled unity tail in direction $direction") {
                        val written = samples(track.bytes())
                        if (written.limit() == 0 || written.get(written.limit() - 1).toInt() != 0) {
                            false
                        } else {
                            unityFrames = written.limit() / 2
                            true
                        }
                    }

                    // At the same clock instant the fresh command starts at the submitted
                    // edge. Rate advance belongs to later commands; there is no held overlap.
                    val edge = originFrame + direction * unityFrames
                    val writesBeforeResume = track.acceptedWrites
                    val resumedRate = direction * .99f
                    worker.updateShuttle(edge.toDouble() / hz, resumedRate, lower, upper)
                    waits.observe(resumedRate, "first grain and fresh wait in direction $direction")
                    assertSame(track, ShuttleEndpointAudioTrackShadow.single())
                    assertEquals(writesBeforeResume + 1, track.acceptedWrites)

                    val written = samples(track.bytes())
                    assertTrue(written.limit() / 2 > unityFrames)
                    assertEquals("Resumed grain must reach its untapered level",
                        referenceLevel, written.get(written.limit() - 1).toInt())
                    var mismatchCount = 0
                    var firstMismatch = ""
                    for (frame in 0 until written.limit() / 2) {
                        val sourceFrame = if (direction > 0) originFrame + frame else originFrame - 1 - frame
                        val observedIndex = written.get(frame * 2).toInt()
                        val observedLevel = written.get(frame * 2 + 1).toInt()
                        assertTrue("Invalid reference channel at output frame $frame", observedLevel in 0..referenceLevel)
                        // Both channels receive the same taper. The constant channel measures
                        // its gain so the source comparison does not repeat the ramp formula.
                        val expectedIndex = source.get(sourceFrame * 2).toInt() * observedLevel.toDouble() / referenceLevel
                        if (abs(observedIndex - expectedIndex) > 2.0) {
                            if (mismatchCount == 0) {
                                firstMismatch = "output $frame, source $sourceFrame, expected $expectedIndex, got $observedIndex"
                            }
                            mismatchCount++
                        }
                    }
                    if (mismatchCount > 0) {
                        mismatches += "direction $direction, settled at output $unityFrames, " +
                            "$mismatchCount mismatched PCM frames, first at $firstMismatch"
                    }
                } finally {
                    worker.close()
                    waits.resume()
                    authority.revoke()
                    for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                        val executor = TimelineAudioPreviewController::class.java.getDeclaredField(name)
                            .apply { isAccessible = true }.get(worker) as ExecutorService
                        assertTrue("$name did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
                    }
                    ShadowLooper.idleMainLooper()
                    assertTrue(errors.toString(), errors.isEmpty())
                }
            }
            assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
            root.toFile().deleteRecursively()
        }
    }

    private fun checkRateTransition(hz: Int) {
        val root = Files.createTempDirectory("shuttle-retained-phase-")
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root
        ShadowAudioTrack.setMinBufferSize(256)
        try {
            for (channels in 1..2) {
                val pcm = ByteBuffer.allocate(hz * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(hz) { frame ->
                    repeat(channels) { ch -> pcm.putShort(voicedSample(frame, hz, ch).toShort()) }
                }
                val wav = File(root.toFile(), "voiced-$channels.wav").apply {
                    writeBytes(buildWavHeaderBytes(hz, channels, PcmSampleFormat.PCM_16, pcm.capacity().toLong()) +
                        pcm.array())
                }
                val recording = RecordingEntity(wav.absolutePath, wav.name, "audio/wav", 1L, 1_000L,
                    wav.length(), "PCM", RecordingStorageType.FILE, root.toString(), resolveFileIdentity(wav))
                for (direction in listOf(-1, 1)) {
                    // Both rates are exercised in each direction across the channel layouts.
                    val lastRate = if ((direction > 0) == (channels == 1)) 1f else .9998f
                    checkWorkerSeams(recording, hz, channels, direction, lastRate)
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
            root.toFile().deleteRecursively()
        }
    }

    private fun voicedSample(frame: Int, hz: Int, channel: Int): Int {
        val phase = 2 * PI * 125 * frame / hz
        return if (channel == 0) (16_384 * cos(phase)).roundToInt()
            else (9_000 * cos(phase + .7)).roundToInt()
    }

    private fun checkWorkerSeams(recording: RecordingEntity, hz: Int, channels: Int, direction: Int, lastRate: Float) {
        val origin = .160
        val lower = .096
        val upper = .224
        val clock = AtomicLong(1_000_000_000L)
        val waits = TargetWaitGate()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val authority = RecordingScrubAuthority(RecordingScrubKey(1L, 1L))
        val worker = TimelineAudioPreviewController(onTrackReleaseFailure = { errors += it },
            shuttleNowNanos = clock::get, onShuttleTargetWait = waits::reached)
        ShuttleEndpointAudioTrackShadow.reset()
        ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
        try {
            worker.startRecordingShuttle(RuntimeEnvironment.getApplication(), recording, origin, direction * .75f,
                onStarted = {}, onFailureAfterStart = { errors += AssertionError("Shuttle lost playback") },
                onSourceCloseFailure = { errors += it }, fromSeconds = lower, untilSeconds = upper,
                ordinaryAuthority = authority)
            waits.observe(direction * .75f, "prime")
            val track = ShuttleEndpointAudioTrackShadow.single()
            assertEquals(hz, track.formatRate())
            assertEquals(channels, track.formatChannels())
            var target = origin
            for (magnitude in listOf(.75f, lastRate)) {
                val before = track.frameCount
                val rate = magnitude * direction
                target += rate * .016
                clock.addAndGet(16_000_000L)
                worker.updateShuttle(target, rate, lower, upper)
                waits.resume()
                waits.observe(rate, "worker consumed $rate at $hz Hz")
                val hop = track.frameCount - before
                val label = "$hz Hz, $channels channels, rate $rate, starting at output frame $before"
                assertTrue("One full body per fresh command: $label", abs(hop - .016 * hz) <= 2)
                assertSame(track, ShuttleEndpointAudioTrackShadow.single())
                val output = samples(track.bytes())
                // Compare each entire join with uninterrupted playback of the source WAV.
                // A single 1x reading is still in the production grain-to-unity debounce.
                for (i in 0 until (.008 * hz).roundToInt()) for (ch in 0 until channels) {
                    val oriented = if (direction > 0) before + i else -1 - before - i
                    val reference = voicedSample((origin * hz).roundToInt() + oriented, hz, ch)
                    val observed = output.get((before + i) * channels + ch).toInt()
                    val tolerance = if (ch == 0) 1_640 else 900 // 10% of each channel's peak, including frame rounding.
                    assertTrue("Discontinuous phase: $label, frame $i ch $ch, expected $reference got $observed",
                        abs(reference - observed) <= tolerance)
                }
            }
        } finally {
            worker.close()
            waits.resume()
            authority.revoke()
            for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                val executor = TimelineAudioPreviewController::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(worker) as ExecutorService
                assertTrue("$name did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            ShadowLooper.idleMainLooper()
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }

    private fun verifyPlayback(saved: Boolean) {
        val pcm = sourcePcm()
        val root = Files.createTempDirectory("shuttle-near-unity-")
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root
        ShuttleEndpointAudioTrackShadow.reset()
        ShadowAudioTrack.setMinBufferSize(256)
        try {
            if (saved) {
                val wav = File(root.toFile(), "saved.wav").apply {
                    outputStream().use { output ->
                        output.write(buildWavHeaderBytes(SAMPLE_RATE, 2, PcmSampleFormat.PCM_16, pcm.size.toLong()))
                        output.write(pcm)
                    }
                }
                val row = RecordingEntity(wav.absolutePath, wav.name, "audio/wav", 1L,
                    10_000L, wav.length(), "PCM", RecordingStorageType.FILE, root.toString(), resolveFileIdentity(wav))
                runWorker(expectedHandoffs = 1) { controller, handoffs, errors ->
                    controller.startRecordingShuttle(RuntimeEnvironment.getApplication(), row,
                        atSeconds = ORIGIN, rate = .99f, onStarted = { handoffs.incrementAndGet() },
                        onFailureAfterStart = { errors += AssertionError("Saved-row shuttle failed after handoff") },
                        onSourceCloseFailure = { errors += it }, fromSeconds = LOWER, untilSeconds = UPPER)
                }
            } else {
                PersistentAudioChunkStore(root.toFile(), overwriteOldest = false, directorySync = {}).use { store ->
                    store.configure(RetentionMode.SIZE, 8_000_000L, SAMPLE_RATE, 2, PcmSampleFormat.PCM_16)
                    assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                    store.sealActiveChunk()
                    ReverbService.TimelineSnapshot(requireNotNull(store.acquireRange(0.0, 10.0))).use { snapshot ->
                        runWorker(expectedHandoffs = 0) { controller, _, _ ->
                            controller.startShuttle(snapshot, atSeconds = ORIGIN, rate = .99f,
                                fromSeconds = LOWER, untilSeconds = UPPER)
                        }
                    }
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
            root.toFile().deleteRecursively()
        }
    }

    private fun runWorker(
        expectedHandoffs: Int,
        start: (TimelineAudioPreviewController, AtomicInteger, ConcurrentLinkedQueue<Throwable>) -> Unit,
    ) {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val handoffs = AtomicInteger()
        val clock = AtomicLong(1_000_000_000L)
        val waits = TargetWaitGate()
        val worker = TimelineAudioPreviewController(onTrackReleaseFailure = { errors += it },
            shuttleNowNanos = clock::get, onShuttleTargetWait = waits::reached)
        try {
            start(worker, handoffs, errors)
            await("first native write") { ShuttleEndpointAudioTrackShadow.firstWrite.count == 0L }
            val track = ShuttleEndpointAudioTrackShadow.single()
            assertEquals(SAMPLE_RATE, track.formatRate())
            assertEquals(2, track.formatChannels())
            assertEquals(1f, track.gain, 0f)
            assertEquals(0, handoffs.get())
            assertFalse(track.playing)
            ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()

            waits.observe(.99f, "fresh sub-hop wait with the initial target")
            assertEquals(1_000_000_000L, clock.get())
            assertEquals(expectedHandoffs, handoffs.get())
            assertTrue(track.playing)
            val primedFrames = track.frameCount
            // A fresh .99x wait may leave audio held back, but may not emit a terminal fade.
            // The injected signal is far from zero in either channel after admission.
            assertEquals("Fresh sub-hop wait must not submit the held overlap", 1, track.acceptedWrites)
            assertTrue("Unexpected startup lead", primedFrames in frames(12)..frames(64))
            val firstWaitSamples = samples(track.bytes())
            assertTrue(abs(firstWaitSamples.get(firstWaitSamples.limit() - 2).toInt()) >= 4_000)
            assertTrue(abs(firstWaitSamples.get(firstWaitSamples.limit() - 1).toInt()) >= 4_000)

            var target = ORIGIN
            var elapsedMs = 0
            fun advance(rate: Float) {
                target += rate.toDouble() * TICK_MS / 1_000
                elapsedMs += TICK_MS
                clock.addAndGet(TICK_MS * 1_000_000L)
                worker.updateShuttle(target, rate, LOWER, UPPER)
                waits.resume()
                waits.observe(rate, "acknowledged command at ${elapsedMs}ms")
                assertTrue("PCM lead grew at ${elapsedMs}ms", track.frameCount <= frames(elapsedMs + 64))
            }

            // Eight logical seconds. The wait acknowledgement prevents the host worker
            // from coalescing commands or creating a scheduler-dependent count deficit.
            repeat(500) { advance(.99f) }
            val wallFrames = frames(elapsedMs)
            val delivered = track.frameCount - primedFrames
            assertTrue("Expected $wallFrames output frames at wall tempo, got $delivered",
                abs(delivered - wallFrames) <= frames(32))

            // Cross both sides of the old 2% cutoff while the target keeps moving.
            // Leave exact-unity mode changes to the separate handoff tests.
            val nearbyRates = floatArrayOf(
                .99f, .981f, .979f, .96f, .94f, .92f, .9f, .92f, .94f, .96f, .979f, .981f, .99f,
                1.01f, 1.019f, 1.021f, 1.04f, 1.06f, 1.08f, 1.1f, 1.08f, 1.06f, 1.04f, 1.021f, 1.019f, 1.01f, .99f,
            )
            val beforeVariation = track.frameCount
            for (rate in nearbyRates) advance(rate)
            val variationWallFrames = frames(nearbyRates.size * TICK_MS)
            val variationDelivered = track.frameCount - beforeVariation
            assertTrue("Expected $variationWallFrames output frames during rate variation, got $variationDelivered",
                abs(variationDelivered - variationWallFrames) <= frames(32))
            assertSame(track, ShuttleEndpointAudioTrackShadow.single())
            val beforeStall = track.frameCount
            val beforeStallWrites = track.acceptedWrites
            assertSteadyPcm(track.bytes())

            // Leave the target and command untouched. Only the injected clock becomes stale.
            clock.addAndGet(64_000_000L)
            waits.resume()
            waits.observe(nearbyRates.last(), "the same command after a real stall")
            assertEquals("The held overlap should taper in one submission", beforeStallWrites + 1, track.acceptedWrites)
            assertTrue("Stall must resolve only the held edge", track.frameCount - beforeStall in 1..frames(8))
            val terminal = samples(track.bytes())
            assertEquals(0, terminal.get(terminal.limit() - 2).toInt())
            assertEquals(0, terminal.get(terminal.limit() - 1).toInt())
            val terminalFrames = track.frameCount
            worker.stopShuttle()
            waits.resume()
            await("retired audio track") { track.released }
            assertEquals("Stopping must not replay the held edge", terminalFrames, track.frameCount)
            assertEquals(expectedHandoffs, handoffs.get())
        } finally {
            ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
            worker.close()
            waits.resume()
            for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                val executor = TimelineAudioPreviewController::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(worker) as ExecutorService
                assertTrue("$name did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            ShadowLooper.idleMainLooper()
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }

    private fun assertSteadyPcm(bytes: ByteArray) {
        val pcm = samples(bytes)
        var lastMarker = -frames(10)
        val markers = mutableListOf<Int>()
        for (frame in frames(8) until pcm.limit() / 2) {
            val left = pcm.get(frame * 2).toInt()
            val right = pcm.get(frame * 2 + 1).toInt()
            if (left !in 4_000..30_000 || right !in -20_000..-4_000) {
                throw AssertionError("Interior gain trough, clipping, or channel swap at frame $frame: $left, $right")
            }
            if (left > 20_000) {
                // A marker spans several samples. Collapse nearby crossings within one event;
                // an 8ms repeated overlap returns as a distinct second event.
                if (frame - lastMarker > frames(2)) markers += frame
                lastMarker = frame
            }
        }
        assertEquals("Each distinct source transient should play once", 2, markers.size)
        assertTrue("Late marker did not follow the actual .99x source clock",
            abs(markers[1] - frames(LATE_MARKER_WALL_MS)) <= frames(32))
    }

    private fun sourcePcm(): ByteArray = ByteBuffer.allocate(SAMPLE_RATE * 10 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        val overlapMarker = ((ORIGIN + .021) * SAMPLE_RATE).roundToInt()
        val lateMarker = ((ORIGIN + .99 * LATE_MARKER_WALL_MS / 1_000) * SAMPLE_RATE).roundToInt()
        val markerRadius = frames(2)
        repeat(SAMPLE_RATE * 10) { frame ->
            val phase = 2 * PI * frame / SAMPLE_RATE
            val distance = minOf(abs(frame - overlapMarker), abs(frame - lateMarker))
            val marker = if (distance < markerRadius) 5_500 * (1.0 + cos(PI * distance / markerRadius)) else 0.0
            putShort((13_000 + 3_000 * sin(731 * phase) + marker).roundToInt().toShort())
            putShort((-11_000 + 3_000 * sin(1_811 * phase + .37)).roundToInt().toShort())
        }
    }.array()

    private class TargetWaitGate {
        private val observed = LinkedBlockingQueue<Float>()
        private val permit = Semaphore(0)

        fun reached(rate: Float) {
            observed.add(rate)
            permit.acquire()
        }

        fun observe(rate: Float, label: String) {
            var actual: Float? = null
            await(label) {
                actual = observed.poll()
                actual != null
            }
            assertEquals(label, rate, requireNotNull(actual), 0f)
        }

        fun resume() { permit.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 96_000
        const val TICK_MS = 16
        const val LATE_MARKER_WALL_MS = 7_500
        const val ORIGIN = 1.0
        const val LOWER = 0.0
        const val UPPER = 10.0

        fun frames(ms: Int) = SAMPLE_RATE * ms / 1_000

        fun samples(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        fun await(label: String, ready: () -> Boolean) {
            // This only guards a broken fixture from hanging. Transport time stays on
            // the atomic clock, and the worker acknowledgement decides when to advance it.
            repeat(5_000) {
                if (ready()) return
                ShadowLooper.idleMainLooper()
                Thread.sleep(1L)
            }
            assertTrue(label, ready())
        }
    }
}
