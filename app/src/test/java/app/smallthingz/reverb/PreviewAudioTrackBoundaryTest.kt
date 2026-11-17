package app.smallthingz.reverb

import android.app.Application
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [JournalHostIoShadow::class, PreviewBoundaryTrackShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewAudioTrackBoundaryTest {
    @Test fun actualPreviewEmitsExactSelectedFramesThroughPartialNativeWritesAndReleasesOnce() = fixture(false) { controller, recording ->
        var started = 0
        var finished = 0
        val errors = mutableListOf<Throwable>()
        val samples = mutableListOf<PreviewPlaybackSample>()
        assertTrue(controller.playRecording(RuntimeEnvironment.getApplication(), recording, 2, 5,
            samples::add, { started++ }, { finished++ }, errors::add))
        await(PreviewBoundaryTrackShadow.released, "track release")
        drainMain()
        assertEquals(1, started)
        assertEquals(1, finished)
        assertTrue(errors.toString(), errors.isEmpty())
        val track = PreviewBoundaryTrackShadow.single()
        assertArrayEquals(expectedSelection(), track.acceptedBytes())
        assertEquals(1, track.events.count { it.first == "play" })
        assertTrue(track.events.count { it.first == "write" } > 1)
        assertTrue(samples.isNotEmpty())
        assertEquals(.005, samples.last().positionSeconds, 0.0)
        assertTrue(samples.all { it.positionSeconds in .002.. .005 && it.queuedUntilSeconds <= .005 })
        assertTerminalOnce(track)
    }

    @Test fun stopDuringAdmittedNativeWriteRevokesPlayAndLaterWritesAndQueuedCallbacks() = fixture(true) { controller, recording ->
        var started = 0
        var finished = 0
        val errors = mutableListOf<Throwable>()
        val samples = mutableListOf<PreviewPlaybackSample>()
        assertTrue(controller.playRecording(RuntimeEnvironment.getApplication(), recording, 2, 5,
            samples::add, { started++ }, { finished++ }, errors::add))
        await(PreviewBoundaryTrackShadow.writeEntered, "first native write")
        controller.stop()
        // pause+flush occurs on the real control worker after synchronous ownership retirement.
        await(PreviewBoundaryTrackShadow.quiet, "retired track pause and flush")
        assertEquals(1L, PreviewBoundaryTrackShadow.allowWriteReturn.count)
        PreviewBoundaryTrackShadow.allowWriteReturn.countDown()
        await(PreviewBoundaryTrackShadow.released, "retired track release")
        drainMain()
        val track = PreviewBoundaryTrackShadow.single()
        assertArrayEquals(expectedSelection().copyOf(34), track.acceptedBytes())
        assertEquals(1, track.events.count { it.first == "write" })
        assertEquals(0, track.events.count { it.first == "play" })
        assertEquals(0, started)
        assertEquals(0, finished)
        assertTrue(samples.isEmpty())
        assertTrue(errors.isEmpty())
        controller.stop()
        controller.close()
        controller.close()
        awaitWorkers(controller)
        drainMain()
        assertEquals(0, started)
        assertEquals(0, finished)
        assertTrue(samples.isEmpty())
        assertTrue(errors.isEmpty())
        assertTerminalOnce(track)
    }

    @Test fun secondNativeWriteFailureReportsOnceAndRetiresTheActualTrackAfterItsExactPrefix() {
        var started = 0
        var finished = 0
        val errors = mutableListOf<Throwable>()
        val failureDelivered = CountDownLatch(1)
        fixture(false) { controller, recording ->
            PreviewBoundaryTrackShadow.failWriteAt = 2
            assertTrue(controller.playRecording(RuntimeEnvironment.getApplication(), recording, 2, 5,
                {}, { started++ }, { finished++ }, { error -> errors += error; failureDelivered.countDown() }))
            awaitMain(failureDelivered)
            await(PreviewBoundaryTrackShadow.released, "failed track terminal release")
            assertEquals(1, started)
            assertEquals(0, finished)
            assertEquals(1, errors.size)
            assertTrue(errors.single() is java.io.IOException)
            assertEquals("Audio preview write failed: -2", errors.single().message)
            val track = PreviewBoundaryTrackShadow.single()
            assertArrayEquals(expectedSelection().copyOf(34), track.acceptedBytes())
            assertEquals(2, track.events.count { it.first == "write" })
            assertEquals(1, track.events.count { it.first == "play" })
            assertTerminalOnce(track)
            controller.stop()
        }
        // fixture closes and joins stream/control/release workers, then drains Main.
        // Successful terminal release returns the actual controller's shared native-track permit.
        drainMain()
        assertEquals(1, started)
        assertEquals(0, finished)
        assertEquals(1, errors.size)
        assertEquals("Audio preview write failed: -2", errors.single().message)
        val retired = PreviewBoundaryTrackShadow.single()
        assertArrayEquals(expectedSelection().copyOf(34), retired.acceptedBytes())
        assertEquals(2, retired.events.count { it.first == "write" })
        assertEquals(1, retired.events.count { it.first == "play" })
        assertTerminalOnce(retired)
    }

    @Test fun rejectedNativeStartThresholdReleasesAcquiredTrackBeforeOneErrorAndNeverPlays() {
        var started = 0
        var finished = 0
        var releasedBeforeError = false
        val errors = mutableListOf<Throwable>()
        val samples = mutableListOf<PreviewPlaybackSample>()
        val failureDelivered = CountDownLatch(1)
        fixture(false, acquiredOnlyCleanup = true) { controller, recording ->
            PreviewBoundaryTrackShadow.thresholdResult = 0
            assertTrue(controller.playRecording(RuntimeEnvironment.getApplication(), recording, 2, 5,
                samples::add, { started++ }, { finished++ }, { error ->
                    releasedBeforeError = PreviewBoundaryTrackShadow.single().events.map { it.first } == listOf("release") &&
                        PreviewBoundaryTrackShadow.released.count == 0L
                    errors += error
                    failureDelivered.countDown()
                }))
            awaitMain(failureDelivered)
            assertTrue("Acquired track must release before playback failure callback", releasedBeforeError)
            assertEquals(1, errors.size)
            assertTrue(errors.single() is java.io.IOException)
            assertEquals("Unable to configure audio preview start threshold: 0", errors.single().message)
            assertEquals(0, started)
            assertEquals(0, finished)
            assertTrue(samples.isEmpty())
            assertArrayEquals(ByteArray(0), PreviewBoundaryTrackShadow.single().acceptedBytes())
            assertAcquiredReleaseOnce(PreviewBoundaryTrackShadow.single())
            controller.stop()
        }
        // All worker owners are joined and Main drained by fixture before these final assertions.
        drainMain()
        assertTrue(releasedBeforeError)
        assertEquals(1, errors.size)
        assertEquals("Unable to configure audio preview start threshold: 0", errors.single().message)
        assertEquals(0, started)
        assertEquals(0, finished)
        assertTrue(samples.isEmpty())
        assertArrayEquals(ByteArray(0), PreviewBoundaryTrackShadow.single().acceptedBytes())
        assertAcquiredReleaseOnce(PreviewBoundaryTrackShadow.single())
    }

    @Test fun actualShuttlePrimesBeforeHandoffAndQueuesTwoSelectedSourceGrainsOnOneTrackThenStops() {
        var handedOff = 0
        var failedAfterStart = 0
        val sourceFailures = CopyOnWriteArrayList<Throwable>()
        val handoff = CountDownLatch(1)
        fixture(false, shuttleSource = true) { controller, recording ->
            PreviewBoundaryTrackShadow.expectedVolume = .82f
            PreviewBoundaryTrackShadow.maximumWrite = 3072
            PreviewBoundaryTrackShadow.firstByteTarget = 3072
            PreviewBoundaryTrackShadow.secondByteTarget = 6144
            controller.startRecordingShuttle(RuntimeEnvironment.getApplication(), recording,
                atSeconds = .010, rate = 1f,
                onStarted = { handedOff++; handoff.countDown() },
                onFailureAfterStart = { failedAfterStart++ },
                onSourceCloseFailure = sourceFailures::add,
                fromSeconds = .010, untilSeconds = .070)
            await(PreviewBoundaryTrackShadow.firstBodyAccepted, "first shuttle grain primed")
            val track = PreviewBoundaryTrackShadow.single()
            assertEquals(96_000, track.formatRate())
            assertEquals(1, track.formatChannels())
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, track.formatEncoding())
            assertEquals(3072, track.acceptedBytes().size)
            assertEquals(0, handedOff)
            assertEquals(0, track.events.count { it.first == "play" })
            awaitMain(handoff)
            await(PreviewBoundaryTrackShadow.played, "shuttle native play after main handoff")
            assertEquals(1, handedOff)
            controller.updateShuttle(atSeconds = .026, rate = 1f, fromSeconds = .010, untilSeconds = .070)
            await(PreviewBoundaryTrackShadow.secondBodyAccepted, "second shuttle grain on persistent track")
            assertSame(track, PreviewBoundaryTrackShadow.single())
            assertShuttleBodies(track)
            controller.stopShuttle()
            await(PreviewBoundaryTrackShadow.released, "shuttle terminal release")
            drainMain()
            assertEquals(1, handedOff)
            assertEquals(0, failedAfterStart)
            assertTrue(sourceFailures.toString(), sourceFailures.isEmpty())
        }
        // The shared fixture has now closed and joined every worker and drained Main.
        assertShuttleBodies(PreviewBoundaryTrackShadow.single())
        assertTerminalOnce(PreviewBoundaryTrackShadow.single())
        assertEquals(1, handedOff)
        assertEquals(0, failedAfterStart)
        assertTrue(sourceFailures.toString(), sourceFailures.isEmpty())
    }

    private fun assertShuttleBodies(track: PreviewBoundaryTrackShadow) {
        val bytes = track.acceptedBytes()
        assertEquals(6144, bytes.size)
        assertEquals(2, track.events.count { it.first == "write" })
        assertEquals(1, track.events.count { it.first == "play" })
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        // Selected PCM is1000, outside PCM30000. DC survives any crossfade of identical sources.
        // The first8ms is fade-in; the remaining first plateau and complete second body are literal1000.
        for (index in 0 until samples.limit()) {
            val value = samples.get(index).toInt()
            if (index < 768) assertTrue("Excluded source sample at $index: $value", value in 0..1000)
            else assertEquals("Selected-source DC at $index", 1000, value)
        }
    }

    private fun fixture(blockFirstWrite: Boolean, acquiredOnlyCleanup: Boolean = false, shuttleSource: Boolean = false, test: (TimelineAudioPreviewController, RecordingEntity) -> Unit) {
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        val root = Files.createTempDirectory("preview-track-boundary-")
        val cleanupFailures = mutableListOf<Throwable>()
        var controller: TimelineAudioPreviewController? = null
        var trackFixtureStarted = false
        var primary: Throwable? = null
        try {
            ServiceStateOsShadow.fixtureRoot = root
            PreviewBoundaryTrackShadow.reset(blockFirstWrite)
            trackFixtureStarted = true
            ShadowAudioTrack.setMinBufferSize(256)
            val source = root.resolve("source.wav").toFile()
            writeWav(source, shuttleSource)
            val sourceDurationMillis = if (shuttleSource) 80 else 10
            val recording = RecordingEntity(id = source.absolutePath, displayName = source.name, mimeType = "audio/wav",
                startedAtMillis = 1L, durationMillis = sourceDurationMillis.toLong(), sizeBytes = source.length(), codecSummary = "PCM",
                storageType = RecordingStorageType.FILE, directoryId = root.toString(), fileIdentity = resolveFileIdentity(source),
                createdAtMillis = 1L, lastSeenAtMillis = 1L)
            val owner = TimelineAudioPreviewController(onTrackReleaseFailure = {
                synchronized(cleanupFailures) { cleanupFailures += it }
            }).also { controller = it }
            val prepared = CountDownLatch(1)
            val errors = mutableListOf<Throwable>()
            owner.prepareRecording(RuntimeEnvironment.getApplication(), recording,
                { assertEquals(sourceDurationMillis, it); prepared.countDown() }, { errors += it; prepared.countDown() },
                { synchronized(cleanupFailures) { cleanupFailures += it } })
            awaitMain(prepared)
            assertTrue(errors.toString(), errors.isEmpty())
            test(owner, recording)
        } catch (error: Throwable) {
            primary = error
        } finally {
            if (trackFixtureStarted) PreviewBoundaryTrackShadow.allowWriteReturn.countDown()
            var workersStopped = controller == null
            fun clean(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
                }
            }
            controller?.let { owner ->
                clean { owner.close() }
                clean { awaitWorkers(owner); workersStopped = true }
            }
            clean { drainMain() }
            clean { synchronized(cleanupFailures) { assertTrue(cleanupFailures.toString(), cleanupFailures.isEmpty()) } }
            if (workersStopped) {
                clean { PreviewBoundaryTrackShadow.acquired()?.let { track ->
                    if (acquiredOnlyCleanup) assertAcquiredReleaseOnce(track) else assertTerminalOnce(track)
                } }
                clean { JournalHostIoShadow.finishFixture()?.let { throw it } }
                ServiceStateOsShadow.fixtureRoot = null
            }
            if (primary == null) clean { check(root.toFile().deleteRecursively()) }
        }
        primary?.let { throw it }
    }

    private fun awaitWorkers(controller: TimelineAudioPreviewController) {
        var primary: Throwable? = null
        for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
            try {
                val worker = TimelineAudioPreviewController::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(controller) as ExecutorService
                assertTrue("$name must terminate before fixture release", worker.awaitTermination(10, TimeUnit.SECONDS))
            } catch (error: Throwable) {
                if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
            }
        }
        primary?.let { throw it }
    }

    private fun assertAcquiredReleaseOnce(track: PreviewBoundaryTrackShadow) {
        assertEquals(listOf("release"), track.events.map { it.first })
        assertNotEquals("Acquired release must stay off test/main caller", Thread.currentThread().name, track.events.single().second)
    }

    private fun assertTerminalOnce(track: PreviewBoundaryTrackShadow) {
        for (name in listOf("pause", "flush", "release")) {
            val events = track.events.filter { it.first == name }
            assertEquals(name, 1, events.size)
            assertNotEquals("$name must stay off test/main caller", Thread.currentThread().name, events.single().second)
        }
    }

    private fun await(latch: CountDownLatch, label: String) = assertTrue(label, latch.await(10, TimeUnit.SECONDS))
    private fun drainMain() = shadowOf(Looper.getMainLooper()).idle()
    private fun awaitMain(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (latch.count != 0L && System.nanoTime() < deadline) {
            drainMain()
            latch.await(5, TimeUnit.MILLISECONDS)
        }
        assertEquals("main callback", 0L, latch.count)
    }

    private fun expectedSelection(): ByteArray = ByteBuffer.allocate(288).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (frame in 96 until 240) putShort((frame - 168).toShort())
    }.array()

    private fun writeWav(file: File, shuttleSource: Boolean = false) {
        val rate = if (shuttleSource) 96_000 else 48_000
        val frames = if (shuttleSource) 7680 else 480
        val payload = frames * 2
        val buffer = ByteBuffer.allocate(44 + payload).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(text: String) { text.forEach { buffer.put(it.code.toByte()) } }
        tag("RIFF"); buffer.putInt(36 + payload); tag("WAVE"); tag("fmt "); buffer.putInt(16)
        buffer.putShort(1); buffer.putShort(1); buffer.putInt(rate); buffer.putInt(rate * 2)
        buffer.putShort(2); buffer.putShort(16); tag("data"); buffer.putInt(payload)
        for (frame in 0 until frames) {
            val sample = if (shuttleSource) { if (frame in 960 until 6720) 1000 else 30_000 }
                else if (frame in 96 until 240) frame - 168 else 30_000
            buffer.putShort(sample.toShort())
        }
        file.writeBytes(buffer.array())
    }
}

/** Only the platform sink is simulated; inherited Robolectric native_setup builds the real AudioTrack API owner. */
@Implements(AudioTrack::class)
class PreviewBoundaryTrackShadow : ShadowAudioTrack() {
    @RealObject lateinit var platformTrack: AudioTrack
    fun formatRate(): Int = platformTrack.sampleRate
    fun formatChannels(): Int = platformTrack.channelCount
    fun formatEncoding(): Int = platformTrack.audioFormat
    private val sink = ByteArrayOutputStream()
    private val recorded = mutableListOf<Pair<String, String>>()
    private var started = false
    val events: List<Pair<String, String>> get() = synchronized(this) { recorded.toList() }
    fun acceptedBytes(): ByteArray = synchronized(this) { sink.toByteArray() }
    private fun event(name: String) = synchronized(this) { recorded += name to Thread.currentThread().name }

    @Implementation fun setStartThresholdInFrames(value: Int): Int {
        check(value == 1)
        synchronized(tracks) { if (!tracks.contains(this)) tracks += this }
        return thresholdResult
    }
    @Implementation fun setVolume(value: Float): Int { check(value == expectedVolume); return AudioTrack.SUCCESS }
    @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
        check(size > 0 && offset >= 0 && offset + size <= bytes.size && size % 2 == 0)
        check(mode == AudioTrack.WRITE_BLOCKING || mode == AudioTrack.WRITE_NON_BLOCKING)
        event("write")
        if (failWriteAt > 0 && events.count { it.first == "write" } == failWriteAt) return AudioTrack.ERROR_BAD_VALUE
        writeEntered.countDown()
        if (blockFirst && events.count { it.first == "write" } == 1) check(allowWriteReturn.await(10, TimeUnit.SECONDS))
        val accepted = minOf(size, maximumWrite)
        val total = synchronized(this) { sink.write(bytes, offset, accepted); sink.size() }
        if (total >= firstByteTarget) firstBodyAccepted.countDown()
        if (total >= secondByteTarget) secondBodyAccepted.countDown()
        return accepted
    }
    @Implementation override fun play() { synchronized(this) { started = true }; event("play"); played.countDown() }
    @Implementation fun pause() { event("pause") }
    @Implementation override fun flush() { event("flush"); quiet.countDown() }
    @Implementation fun release() { event("release"); released.countDown() }
    @Implementation override fun getPlaybackHeadPosition(): Int = synchronized(this) { if (started) sink.size() / 2 else 0 }

    companion object {
        private val tracks = mutableListOf<PreviewBoundaryTrackShadow>()
        private var blockFirst = false
        @Volatile var expectedVolume = 1f
        @Volatile var maximumWrite = 34
        @Volatile var firstByteTarget = Int.MAX_VALUE
        @Volatile var secondByteTarget = Int.MAX_VALUE
        lateinit var firstBodyAccepted: CountDownLatch
        lateinit var secondBodyAccepted: CountDownLatch
        lateinit var played: CountDownLatch
        @Volatile var failWriteAt = 0
        @Volatile var thresholdResult = 1
        lateinit var writeEntered: CountDownLatch
        lateinit var allowWriteReturn: CountDownLatch
        lateinit var quiet: CountDownLatch
        lateinit var released: CountDownLatch
        fun single(): PreviewBoundaryTrackShadow = synchronized(tracks) { tracks.single() }
        fun acquired(): PreviewBoundaryTrackShadow? = synchronized(tracks) { tracks.singleOrNull() }
        fun reset(block: Boolean) {
            synchronized(tracks) { tracks.clear() }
            blockFirst = block
            expectedVolume = 1f; maximumWrite = 34
            firstByteTarget = Int.MAX_VALUE; secondByteTarget = Int.MAX_VALUE
            firstBodyAccepted = CountDownLatch(1); secondBodyAccepted = CountDownLatch(1)
            played = CountDownLatch(1)
            failWriteAt = 0
            thresholdResult = 1
            writeEntered = CountDownLatch(1); allowWriteReturn = CountDownLatch(1)
            quiet = CountDownLatch(1); released = CountDownLatch(1)
        }
    }
}
