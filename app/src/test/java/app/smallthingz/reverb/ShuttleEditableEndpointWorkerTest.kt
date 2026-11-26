package app.smallthingz.reverb

import android.app.Application
import android.media.AudioTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ServiceStateOsShadow::class, ShuttleEndpointAudioTrackShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ShuttleEditableEndpointWorkerTest {
    @Test fun pausedInteriorCaptureEndpointEmitsAnAuditionThatFinishesQuietly() = runEndpoint(saved = false, grow = false)

    @Test fun lastGrowingCaptureEndpointFinishesQuietlyWithoutFadingTheEarlierSeam() = runEndpoint(saved = false, grow = true)

    @Test fun savedRowUnityMatchesNormalGainAndPcmAcrossGrowthAndTheFinalTail() = runEndpoint(saved = true, grow = true)

    @Test fun savedRowCanReturnFromFastGrainsToUnityWithoutASilentOrClippedSeam() =
        runEndpoint(saved = true, grow = true, changeModes = true)

    private fun runEndpoint(saved: Boolean, grow: Boolean, changeModes: Boolean = false) {
        val sampleRate = 96_000
        val duration = .40
        val lower = .060
        val firstUpper = .180
        val finalUpper = if (grow) .210 else firstUpper
        val sourceFrames = (duration * sampleRate).roundToInt()
        val pcm = ByteBuffer.allocate(sourceFrames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (frame in 0 until sourceFrames) {
                val phase = 2 * PI * frame / sampleRate
                putShort((13_000 + 7_000 * sin(731 * phase)).roundToInt().toShort())
                putShort((-11_000 + 6_000 * sin(1_811 * phase + .37)).roundToInt().toShort())
            }
        }.array()
        val root = Files.createTempDirectory("shuttle-endpoint-worker-")
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root
        ShuttleEndpointAudioTrackShadow.reset()
        ShadowAudioTrack.setMinBufferSize(256)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val handoffs = AtomicInteger(0)
        val clock = AtomicLong(1_000_000_000L)
        try {
            PersistentAudioChunkStore(root.toFile(), overwriteOldest = false, directorySync = {}).use { store ->
                store.configure(RetentionMode.SIZE, 2_000_000L, sampleRate, 2, PcmSampleFormat.PCM_16)
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                store.sealActiveChunk()
                ReverbService.TimelineSnapshot(requireNotNull(store.acquireRange(0.0, duration))).use { snapshot ->
                    val controller = TimelineAudioPreviewController(onTrackReleaseFailure = { errors += it },
                        shuttleNowNanos = clock::get)
                    try {
                        if (saved) {
                            val wav = File(root.toFile(), "saved.wav").apply {
                                writeBytes(buildWavHeaderBytes(sampleRate, 2, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm)
                            }
                            val recording = RecordingEntity(wav.absolutePath, wav.name, "audio/wav", 1L,
                                400L, wav.length(), "PCM", RecordingStorageType.FILE, root.toString(), resolveFileIdentity(wav))
                            controller.startRecordingShuttle(RuntimeEnvironment.getApplication(), recording,
                                atSeconds = firstUpper, rate = 1f, onStarted = { handoffs.incrementAndGet() },
                                onFailureAfterStart = { errors += AssertionError("Saved-row shuttle failed after handoff") },
                                onSourceCloseFailure = { errors += it }, fromSeconds = lower, untilSeconds = firstUpper)
                        } else controller.startShuttle(snapshot, firstUpper, 1f, lower, firstUpper)

                        await("first native write") { ShuttleEndpointAudioTrackShadow.firstWrite.count == 0L }
                        val track = ShuttleEndpointAudioTrackShadow.single()
                        assertEquals(sampleRate, track.formatRate())
                        assertEquals(2, track.formatChannels())
                        assertEquals(1f, track.gain, 0f)
                        assertEquals(0, handoffs.get())
                        assertFalse(track.playing)
                        // Admit the expansion while the outgoing write owns the worker,
                        // before it can settle the first endpoint as stale.
                        if (changeModes) controller.updateShuttle(firstUpper - .002, 2f, lower, finalUpper)
                        else if (grow) controller.updateShuttle(finalUpper, 1f, lower, finalUpper)
                        ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()

                        val referenceStart = ((firstUpper - .024) * sampleRate).roundToInt()
                        val referenceEnd = (finalUpper * sampleRate).roundToInt()
                        val taperFrames = sampleRate * 2 / 1000
                        val expectedFrames = if (changeModes) {
                            await("unity to grain handoff") { track.acceptedWrites >= 2 && track.playing }
                            controller.updateShuttle(finalUpper, 1f, lower, finalUpper)
                            await("grain to unity handoff") { track.acceptedWrites >= 3 }
                            // Duration changes with pitch in the middle. The last unity packet
                            // must still hold exactly the same bounded terminal edge.
                            track.frameCount + taperFrames
                        } else {
                            (referenceEnd - referenceStart).also { total ->
                                val steady = total - taperFrames
                                await("steady body with an unsubmitted tail") { track.frameCount >= steady && track.playing }
                                assertEquals(steady, track.frameCount)
                            }
                        }
                        assertEquals(if (saved) 1 else 0, handoffs.get())
                        clock.addAndGet(26_000_000L)
                        await("terminal taper after the command stalls") { track.frameCount >= expectedFrames }
                        assertEquals(expectedFrames, track.frameCount)
                        controller.stopShuttle()
                        await("shuttle track release") { track.released }
                        assertEquals(expectedFrames, track.frameCount)
                        val actual = ByteBuffer.wrap(track.bytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val reference = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val lastUnityFrames = sampleRate * 6 / 1000
                        val steadyStart = if (changeModes) expectedFrames - lastUnityFrames else taperFrames
                        for (frame in steadyStart until expectedFrames - taperFrames) for (channel in 0..1) {
                            val normalFrame = if (changeModes) referenceEnd - (expectedFrames - frame) else referenceStart + frame
                            val normalSample = reference.get(normalFrame * 2 + channel).toInt()
                            val actualSample = actual.get(frame * 2 + channel).toInt()
                            assertTrue("PCM discontinuity at frame $frame channel $channel: $normalSample vs $actualSample",
                                abs(actualSample - normalSample) <= 1)
                        }
                        if (changeModes) {
                            assertTrue("The mode transition produced too little or replayed too much PCM",
                                expectedFrames in sampleRate * 50 / 1000..sampleRate * 56 / 1000)
                            for (frame in taperFrames + 1 until expectedFrames - taperFrames) for (channel in 0..1) {
                                val actualSample = actual.get(frame * 2 + channel).toInt()
                                val prior = actual.get((frame - 1) * 2 + channel).toInt()
                                assertTrue("Unexpected silence or clipping at $frame", abs(actualSample) in 4_000..21_000)
                                assertTrue("Abrupt sample step at $frame", abs(actualSample - prior) <= 3_000)
                            }
                        }
                        for (channel in 0..1) {
                            assertEquals("No untapered sample may be the last queued frame", 0,
                                actual.get((expectedFrames - 1) * 2 + channel).toInt())
                            for (frame in expectedFrames - taperFrames / 4 until expectedFrames) {
                                val normalFrame = referenceEnd - (expectedFrames - frame)
                                val expected = reference.get(normalFrame * 2 + channel).toInt()
                                assertTrue("Terminal taper collapsed before frame $frame",
                                    abs(actual.get(frame * 2 + channel).toInt()) <= abs(expected) * .20 + 1)
                            }
                        }
                    } finally {
                        ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
                        controller.close()
                        for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                            val worker = TimelineAudioPreviewController::class.java.getDeclaredField(name)
                                .apply { isAccessible = true }.get(controller) as ExecutorService
                            assertTrue("$name did not terminate", worker.awaitTermination(5, TimeUnit.SECONDS))
                        }
                        ShadowLooper.idleMainLooper()
                        assertTrue(errors.toString(), errors.isEmpty())
                    }
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
            root.toFile().deleteRecursively()
        }
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(2L)
        }
        assertTrue(label, ready())
    }
}

@Implements(AudioTrack::class)
class ShuttleEndpointAudioTrackShadow : ShadowAudioTrack() {
    @RealObject lateinit var track: AudioTrack
    private val sink = ByteArrayOutputStream()
    private var writeCount = 0
    fun formatRate(): Int = track.sampleRate
    fun formatChannels(): Int = track.channelCount
    val frameCount: Int get() = synchronized(sink) { sink.size() / (formatChannels() * 2) }
    val acceptedWrites: Int get() = synchronized(sink) { writeCount }
    @Volatile var playing = false
        private set
    @Volatile var playCalls = 0
        private set
    @Volatile var released = false
        private set
    @Volatile var releaseCalls = 0
        private set
    @Volatile var gain = Float.NaN
        private set

    fun bytes(): ByteArray = synchronized(sink) { sink.toByteArray() }

    @Implementation fun setStartThresholdInFrames(threshold: Int): Int {
        synchronized(tracks) { tracks += this }
        onTrackSetup?.invoke()
        return threshold
    }

    @Implementation fun setVolume(volume: Float): Int {
        gain = volume
        return AudioTrack.SUCCESS
    }

    @Implementation fun write(bytes: ByteArray, offset: Int, length: Int, mode: Int): Int {
        check(mode == AudioTrack.WRITE_BLOCKING || mode == AudioTrack.WRITE_NON_BLOCKING)
        check(length > 0 && length % (formatChannels() * 2) == 0)
        if (frameCount == 0) {
            firstWrite.countDown()
            check(allowFirstWrite.await(5, TimeUnit.SECONDS))
        }
        synchronized(sink) {
            sink.write(bytes, offset, length)
            writeCount++
        }
        return length
    }

    @Implementation override fun play() {
        playCalls++
        playing = true
    }
    @Implementation fun pause() { playing = false }
    @Implementation override fun flush() = Unit
    @Implementation fun release() {
        releaseCalls++
        released = true
    }
    @Implementation override fun getPlaybackHeadPosition(): Int = if (playing) frameCount else 0
    @Implementation fun getUnderrunCount(): Int = 0

    companion object {
        private val tracks = mutableListOf<ShuttleEndpointAudioTrackShadow>()
        @Volatile var onTrackSetup: (() -> Unit)? = null
        lateinit var firstWrite: CountDownLatch
        lateinit var allowFirstWrite: CountDownLatch
        fun single(): ShuttleEndpointAudioTrackShadow = synchronized(tracks) { tracks.single() }
        fun acquiredTracks(): List<ShuttleEndpointAudioTrackShadow> = synchronized(tracks) { tracks.toList() }
        fun reset() {
            synchronized(tracks) { tracks.clear() }
            onTrackSetup = null
            firstWrite = CountDownLatch(1)
            allowFirstWrite = CountDownLatch(1)
        }
    }
}
