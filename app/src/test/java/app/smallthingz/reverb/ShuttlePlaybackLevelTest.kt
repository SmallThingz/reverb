package app.smallthingz.reverb

import android.app.Application
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowLooper

private typealias OrdinaryPlay = (TimelineAudioPreviewController, AtomicBoolean, ConcurrentLinkedQueue<Throwable>) -> Boolean
private typealias ShuttleStart = (TimelineAudioPreviewController, Float, ConcurrentLinkedQueue<Throwable>, () -> Unit) -> Unit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ServiceStateOsShadow::class, ShuttleEndpointAudioTrackShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ShuttlePlaybackLevelTest {
    @Test fun rangePcmLevelTracksOrdinaryRangePlayFromTheFirstWrite() = comparePlayback(saved = false)

    @Test fun savedScrubPcmLevelTracksOrdinaryTrimPlayFromTheFirstWrite() = comparePlayback(saved = true)

    @Test fun monoCaptureScrubMatchesRangePlayAtAndNearUnityAcrossRefills() = comparePlayback(saved = false, channels = 1)

    @Test fun monoWavScrubMatchesTrimPlayAtAndNearUnityAcrossRefills() = comparePlayback(saved = true, channels = 1)

    @Test fun verySlowNonzeroRangeGestureAuditionsOnceWithoutReplayingTheStoppedTarget() =
        withSource(saved = false, durationSeconds = 10) { _, startShuttle ->
            val clock = AtomicLong(1_000_000_000L)
            val waits = AtomicInteger()
            withController(clock::get, { waits.incrementAndGet() }) { owner, errors ->
                ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
                startShuttle(owner, .003f, errors) {}
                await("0.003x initial audition", errors) { waits.get() == 1 }
                val track = ShuttleEndpointAudioTrackShadow.single()
                assertEquals(1, track.playCalls)
                assertTrue("A real nonzero gesture must produce audible PCM", capture(track).level(1, 4, 12).rms > 10_000)
                clock.addAndGet(40_000_000L)
                await("0.003x stale target settled", errors) { waits.get() == 2 }
                val settledFrames = track.frameCount
                assertSingleGrain(track, settledFrames)
                clock.addAndGet(16_000_000L)
                owner.updateShuttle(CUE, .003f, LOWER, UPPER)
                await("unchanged target observed again", errors) { waits.get() == 3 }
                assertEquals("Freshness alone cannot replay an already consumed grain", settledFrames, track.frameCount)
                assertEquals(1, track.playCalls)
            }
        }

    @Test fun activeSavedGestureStillPrimesOnceAfterTrackSetupOutlastsFreshness() = delayedSavedStartup(null)

    @Test fun cancelDuringDelayedSetupKeepsTheAcquiredTrackSilent() = delayedSavedStartup(StartupChange.CANCEL)

    @Test fun reversalDuringDelayedSetupKeepsTheAcquiredTrackSilent() = delayedSavedStartup(StartupChange.REVERSE)

    @Test fun contractionDuringDelayedSetupKeepsTheAcquiredTrackSilent() = delayedSavedStartup(StartupChange.CONTRACT)

    private enum class StartupChange { CANCEL, REVERSE, CONTRACT }

    private fun delayedSavedStartup(change: StartupChange?) = withSource(saved = true) { _, startShuttle ->
        val clock = AtomicLong(1_000_000_000L)
        val enteredSetup = CountDownLatch(1)
        val finishSetup = CountDownLatch(1)
        val waits = AtomicInteger()
        val handoffs = AtomicInteger()
        withController(clock::get, { waits.incrementAndGet() }, releaseWait = { finishSetup.countDown() },
            onTrackSetup = {
                enteredSetup.countDown()
                check(finishSetup.await(5, TimeUnit.SECONDS)) { "Test failed to release track setup" }
            }) { owner, errors ->
            ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
            startShuttle(owner, .91f, errors) { handoffs.incrementAndGet() }
            await("track configuration reached before grain admission", errors) { enteredSetup.count == 0L }
            val first = ShuttleEndpointAudioTrackShadow.single()
            assertEquals(0, first.acceptedWrites)
            assertEquals(0, first.playCalls)
            clock.addAndGet(40_000_000L)
            when (change) {
                StartupChange.CANCEL -> owner.stopShuttle()
                StartupChange.REVERSE -> owner.updateShuttle(CUE, -.91f, LOWER, UPPER)
                StartupChange.CONTRACT -> owner.updateShuttle(CUE, .91f, LOWER, .600)
                null -> Unit
            }
            finishSetup.countDown()
            if (change == null) {
                await("aged active command considered by worker", errors) { waits.get() == 1 }
                assertEquals("Active gesture must hand off after first accepted PCM", 1, handoffs.get())
                assertEquals(1, first.playCalls)
                assertEquals(1, first.acceptedWrites)
                val primedFrames = first.frameCount
                assertSingleGrain(first, primedFrames)
                assertTrue("Delayed prime must include real audio", capture(first).level(1, 4, 12).rms > 10_000)
                clock.addAndGet(16_000_000L)
                owner.updateShuttle(CUE, .91f, LOWER, UPPER)
                await("unchanged late-prime target observed again", errors) { waits.get() == 2 }
                assertEquals(primedFrames, first.frameCount)
                assertEquals(1, handoffs.get())
            } else {
                await("invalidated track release after delayed setup", errors) { first.released }
                assertEquals("Invalidated setup must never write", 0, first.acceptedWrites)
                assertEquals("Invalidated setup must never play", 0, first.playCalls)
                if (change == StartupChange.CANCEL) {
                    assertEquals(0, handoffs.get())
                    assertNull(owner.observeTrack())
                }
            }
        }
    }

    @Test fun separatedGrainsHaveOrdinaryLevelAndQuietSmoothEdges() = withSource(saved = false) { play, startShuttle ->
        val ordinary = captureOrdinary(play)
        val clock = AtomicLong(1_000_000_000L)
        val waits = AtomicInteger()
        val grains = withController(clock::get, { waits.incrementAndGet() }) { owner, errors ->
            ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
            startShuttle(owner, .25f, errors) {}
            await("first intermittent target observed", errors) { waits.get() == 1 }
            val track = ShuttleEndpointAudioTrackShadow.single()
            clock.addAndGet(40_000_000L)
            await("first grain settled at stale command", errors) { waits.get() == 2 }
            val firstFrames = track.frameCount
            assertSingleGrain(track, firstFrames)
            val first = capture(track)
            // 96 ms of 0.25x movement advances 24 ms of source, to the consumed edge.
            clock.addAndGet(56_000_000L)
            owner.updateShuttle(CUE + .024, .25f, LOWER, UPPER)
            await("second intermittent target observed", errors) { waits.get() == 3 }
            clock.addAndGet(40_000_000L)
            await("second grain settled at stale command", errors) { waits.get() == 4 }
            assertSingleGrain(track, track.frameCount - firstFrames)
            first to capture(track, firstFrames)
        }
        for ((index, grain) in listOf(grains.first, grains.second).withIndex()) {
            for (channel in 0..1) {
                val body = ordinary.level(channel, 8, 16)
                val label = "grain ${index + 1} channel $channel"
                assertLevel(body, grain.level(channel, 0, STARTUP_MS), "$label entrance")
                assertLevel(body, grain.lastLevel(channel, STARTUP_MS), "$label exit")
                assertTrue("$label starts or ends away from silence", grain.endpointPeak(channel) <= body.peak * .02 + 1)
                // The hotter fixture tone is 750 Hz. Allow its steepest adjacent change
                // plus 4% of the reference peak for the gain ramp and frame rounding.
                val maximumStep = body.peak * (2 * PI * 750 / grain.sampleRate + .04) + 2
                assertTrue("$label abrupt adjacent sample step", grain.maximumStep(channel) <= maximumStep)
            }
        }
    }

    private fun assertSingleGrain(track: ShuttleEndpointAudioTrackShadow, frames: Int) {
        val nominal = track.formatRate() * 24 / 1_000
        assertTrue("One interior audition should deliver 24 ms, got $frames frames", abs(frames - nominal) <= 1)
    }

    private fun comparePlayback(saved: Boolean, channels: Int = 2) = withSource(saved, channels = channels) { play, startShuttle ->
        compare(play, startShuttle, channels)
    }

    private fun withSource(saved: Boolean, durationSeconds: Int = 1, channels: Int = 2,
                           block: (OrdinaryPlay, ShuttleStart) -> Unit) {
        val root = Files.createTempDirectory("scrub-playback-level-")
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root
        ShadowAudioTrack.setMinBufferSize(256)
        try {
            val pcm = fixturePcm(durationSeconds, channels)
            if (saved) {
                val wav = File(root.toFile(), "level.wav").apply {
                    writeBytes(buildWavHeaderBytes(SOURCE_RATE, channels, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm)
                }
                val recording = RecordingEntity(wav.absolutePath, wav.name, "audio/wav", 1L,
                    durationSeconds * 1_000L, wav.length(), "PCM", RecordingStorageType.FILE, root.toString(), resolveFileIdentity(wav))
                val app = RuntimeEnvironment.getApplication()
                block(
                    { owner, finished, errors ->
                        val prepared = AtomicBoolean()
                        owner.prepareRecording(app, recording, { prepared.set(true) }, { errors += it }, { errors += it })
                        await("ordinary Trim reader preparation", errors) { prepared.get() }
                        owner.playRecording(app, recording, CUE_MS, CUE_MS + 400,
                            onProgress = {}, onStarted = {}, onFinished = { finished.set(true) }, onError = { errors += it })
                    },
                    { owner, rate, errors, onStarted ->
                        owner.startRecordingShuttle(app, recording, CUE, rate, fromSeconds = LOWER, untilSeconds = UPPER,
                            onStarted = onStarted,
                            onFailureAfterStart = { errors += AssertionError("Saved shuttle failed after first write") },
                            onSourceCloseFailure = { errors += it })
                    },
                )
            } else {
                PersistentAudioChunkStore(root.toFile(), overwriteOldest = false, directorySync = {}).use { store ->
                    store.configure(RetentionMode.SIZE, 2_000_000L, SOURCE_RATE, channels, PcmSampleFormat.PCM_16)
                    assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                    store.sealActiveChunk()
                    ReverbService.TimelineSnapshot(requireNotNull(store.acquireRange(0.0, durationSeconds.toDouble()))).use { snapshot ->
                        block(
                            { owner, finished, errors ->
                                owner.play(snapshot, CUE, CUE + .400, onStarted = {},
                                    onFinished = { finished.set(true) }, onError = { errors += it })
                            },
                            { owner, rate, _, _ ->
                                owner.startShuttle(snapshot, CUE, rate, LOWER, UPPER)
                            },
                        )
                    }
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
            root.toFile().deleteRecursively()
        }
    }

    private fun captureOrdinary(play: OrdinaryPlay, channels: Int = 2): PcmPort = withController { owner, errors ->
        val finished = AtomicBoolean()
        ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
        assertTrue(play(owner, finished, errors))
        await("ordinary playback finished", errors) { finished.get() }
        capture(ShuttleEndpointAudioTrackShadow.single(), channels = channels)
    }

    private fun compare(play: OrdinaryPlay, startShuttle: ShuttleStart, channels: Int) {
        val ordinary = captureOrdinary(play, channels)
        val mono = channels == 1
        // Exact unity provides a control. Both nearby rates exercise the grain path across
        // cache refills. An isolated slow gesture checks the first audition, including its
        // entrance, without requiring continuous output at intentionally sparse rates.
        // The mono case drives exact unity as well, starting at .141 s = 6218.1 source frames.
        val rates = if (mono) listOf(1f, .99f, 1.01f) else listOf(1f, .91f, 1.09f, .35f)
        for (rate in rates) {
            val grainCadence = rate != 1f && rate != .35f
            val sustained = mono || grainCadence
            val clock = AtomicLong(1_000_000_000L)
            val waits = AtomicInteger()
            val continueWorker = Semaphore(0)
            val onWait: ((Float) -> Unit)? = if (grainCadence) {
                { _ -> waits.incrementAndGet(); continueWorker.acquire() }
            } else null
            val scrub = withController(clock::get, onWait, releaseWait = { continueWorker.release() },
                collectTimings = mono) { owner, errors ->
                startShuttle(owner, rate, errors) {}
                await("${rate}x first AudioTrack write", errors) { ShuttleEndpointAudioTrackShadow.firstWrite.count == 0L }
                val track = ShuttleEndpointAudioTrackShadow.single()
                ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
                await("${rate}x startup packet", errors) {
                    track.playing && track.frameCount >= track.formatRate() * STARTUP_MS / 1_000
                }
                if (sustained) {
                    if (grainCadence) await("initial shuttle target wait", errors) { waits.get() == 1 }
                    repeat(18) { step ->
                        val elapsedMs = (step + 1) * 16
                        val priorFrames = track.frameCount
                        clock.addAndGet(16_000_000L)
                        owner.updateShuttle(CUE + rate.toDouble() * elapsedMs / 1_000.0, rate, LOWER, UPPER)
                        if (grainCadence) {
                            continueWorker.release()
                            await("rate ${rate}x command at ${elapsedMs}ms", errors) { waits.get() == step + 2 }
                        } else {
                            // Exact unity has no grain-wait callback. Hold the fake clock
                            // until this command advances the persistent track's PCM.
                            await("unity PCM at ${elapsedMs}ms", errors) {
                                track.frameCount - priorFrames >= track.formatRate() * 12 / 1_000
                            }
                        }
                    }
                }
                if (mono) {
                    val timings = owner.drainShuttleTimings()
                    assertEquals(0, timings.dropped)
                    val reads = timings.records.count {
                        it.stage == PreviewShuttleStage.READ_NORMALIZE && it.completed &&
                            it.currentAtEnd && (it.outputBytes ?: 0) > 0
                    }
                    assertTrue("${rate}x must normalize the initial window and at least two refills, got $reads reads", reads >= 3)
                }
                capture(track, channels = channels)
            }
            for (channel in 0 until channels) {
                val expectedPeak = ordinary.level(channel, 0, 80).peak
                assertTrue("Ordinary reference channel $channel is silent", expectedPeak > 1_000)
                assertTrue("${rate}x entry must still begin quietly on channel $channel",
                    abs(scrub.samples.get(channel).toInt() * scrub.gain) <= expectedPeak * .02 + 1)
                assertLevel(ordinary, scrub, rate, channel, 0, STARTUP_MS)
                if (sustained) {
                    // Probe inward body windows before and after the normalized cache refills.
                    val starts = if (mono) (16..240 step 16).toList() else listOf(32, 144, 240)
                    for (startMs in starts) {
                        assertLevel(ordinary, scrub, rate, channel, startMs, startMs + 32)
                    }
                }
            }
        }
    }

    private fun assertLevel(ordinary: PcmPort, scrub: PcmPort, rate: Float, channel: Int, fromMs: Int, untilMs: Int) {
        assertLevel(ordinary.level(channel, fromMs, untilMs), scrub.level(channel, fromMs, untilMs),
            "${rate}x channel $channel ${fromMs}–${untilMs}ms")
    }

    private fun assertLevel(reference: Level, actual: Level, label: String) {
        // A 3 dB startup allowance leaves room for a short click-prevention taper.
        // Raw clipping and excessive peak gain remain failures regardless of RMS.
        assertTrue("$label RMS ${actual.rms} vs ordinary ${reference.rms}",
            actual.rms >= reference.rms / sqrt(2.0) && actual.rms <= reference.rms * 1.12)
        assertTrue("$label clipped or exceeded ordinary peak: ${actual.peak} vs ${reference.peak}",
            !actual.clipped && actual.peak <= reference.peak * 1.12)
    }

    private data class Level(val rms: Double, val peak: Double, val clipped: Boolean)

    private class PcmPort(bytes: ByteArray, val sampleRate: Int, val gain: Float, private val channels: Int) {
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        private val frames: Int get() = samples.limit() / channels

        fun level(channel: Int, fromMs: Int, untilMs: Int): Level =
            levelFrames(channel, fromMs * sampleRate / 1_000, untilMs * sampleRate / 1_000)

        fun lastLevel(channel: Int, ms: Int): Level = levelFrames(channel, frames - ms * sampleRate / 1_000, frames)

        fun endpointPeak(channel: Int): Double = maxOf(abs(sample(0, channel)), abs(sample(frames - 1, channel)))

        fun maximumStep(channel: Int): Double {
            var largest = 0.0
            for (frame in 1 until frames) largest = maxOf(largest, abs(sample(frame, channel) - sample(frame - 1, channel)))
            return largest
        }

        private fun sample(frame: Int, channel: Int): Double = samples.get(frame * channels + channel).toDouble() * gain

        private fun levelFrames(channel: Int, first: Int, end: Int): Level {
            assertTrue("Missing PCM for frames $first–$end at ${sampleRate}Hz", first >= 0 && end > first && end <= frames)
            var squares = 0.0
            var peak = 0.0
            var clipped = false
            for (frame in first until end) {
                val raw = samples.get(frame * channels + channel).toInt()
                clipped = clipped || raw == Short.MIN_VALUE.toInt() || raw == Short.MAX_VALUE.toInt()
                val value = raw.toDouble() * gain
                squares += value * value
                peak = maxOf(peak, abs(value))
            }
            return Level(sqrt(squares / (end - first)), peak, clipped)
        }
    }

    private fun capture(track: ShuttleEndpointAudioTrackShadow, firstFrame: Int = 0, channels: Int = 2): PcmPort {
        assertEquals(channels, track.formatChannels())
        assertTrue("Missing AudioTrack gain", track.gain.isFinite() && track.gain > 0f)
        val bytes = track.bytes()
        val pcm = if (firstFrame == 0) bytes else bytes.copyOfRange(firstFrame * channels * 2, bytes.size)
        return PcmPort(pcm, track.formatRate(), track.gain, channels)
    }

    private fun <T> withController(
        shuttleClock: () -> Long = { 1_000_000_000L },
        onWait: ((Float) -> Unit)? = null,
        releaseWait: () -> Unit = {},
        onTrackSetup: (() -> Unit)? = null,
        collectTimings: Boolean = false,
        block: (TimelineAudioPreviewController, ConcurrentLinkedQueue<Throwable>) -> T,
    ): T {
        val errors = ConcurrentLinkedQueue<Throwable>()
        ShuttleEndpointAudioTrackShadow.reset()
        ShuttleEndpointAudioTrackShadow.onTrackSetup = onTrackSetup
        val owner = TimelineAudioPreviewController(onTrackReleaseFailure = { errors += it }, collectShuttleTimings = collectTimings,
            shuttleNowNanos = shuttleClock, onShuttleTargetWait = onWait)
        return withOwnedResource(owner, release = { controller ->
            ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
            controller.close()
            releaseWait()
            for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                val executor = TimelineAudioPreviewController::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(controller) as ExecutorService
                assertTrue("$name did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            ShadowLooper.idleMainLooper()
            assertTrue(errors.toString(), errors.isEmpty())
        }) { controller -> block(controller, errors) }
    }

    private fun await(label: String, errors: ConcurrentLinkedQueue<Throwable>, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            errors.peek()?.let { throw it }
            Thread.sleep(1L)
        }
        assertTrue(label, ready())
    }

    private fun fixturePcm(durationSeconds: Int, channels: Int): ByteArray =
        ByteBuffer.allocate(SOURCE_RATE * channels * 2 * durationSeconds).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(SOURCE_RATE * durationSeconds) { frame ->
                val phase = 2 * PI * frame / SOURCE_RATE
                val quiet = 2_400 * sin(500 * phase + .73)
                if (channels == 1) {
                    putShort((quiet + 900 * sin(1_250 * phase + .29)).roundToInt().toShort())
                } else {
                    // Preserve the stereo fixture's low-level left and hot right channels.
                    putShort(quiet.roundToInt().toShort())
                    putShort((30_000 * sin(750 * phase + .47)).roundToInt().toShort())
                }
            }
        }.array()

    private companion object {
        const val SOURCE_RATE = 44_100
        const val CUE_MS = 141
        const val CUE = .141
        const val LOWER = .021
        const val UPPER = .777
        const val STARTUP_MS = 8
    }
}
