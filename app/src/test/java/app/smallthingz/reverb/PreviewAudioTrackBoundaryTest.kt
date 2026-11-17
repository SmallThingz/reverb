package app.smallthingz.reverb

import android.app.Application
import android.media.AudioTrack
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
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

    private fun fixture(blockFirstWrite: Boolean, test: (TimelineAudioPreviewController, RecordingEntity) -> Unit) {
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
            writeWav(source)
            val recording = RecordingEntity(id = source.absolutePath, displayName = source.name, mimeType = "audio/wav",
                startedAtMillis = 1L, durationMillis = 10L, sizeBytes = source.length(), codecSummary = "PCM",
                storageType = RecordingStorageType.FILE, directoryId = root.toString(), fileIdentity = resolveFileIdentity(source),
                createdAtMillis = 1L, lastSeenAtMillis = 1L)
            val owner = TimelineAudioPreviewController(onTrackReleaseFailure = {
                synchronized(cleanupFailures) { cleanupFailures += it }
            }).also { controller = it }
            val prepared = CountDownLatch(1)
            val errors = mutableListOf<Throwable>()
            owner.prepareRecording(RuntimeEnvironment.getApplication(), recording,
                { assertEquals(10, it); prepared.countDown() }, { errors += it; prepared.countDown() },
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
                clean { PreviewBoundaryTrackShadow.acquired()?.let(::assertTerminalOnce) }
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

    private fun writeWav(file: File) {
        val buffer = ByteBuffer.allocate(44 + 480 * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(text: String) { text.forEach { buffer.put(it.code.toByte()) } }
        tag("RIFF"); buffer.putInt(36 + 960); tag("WAVE"); tag("fmt "); buffer.putInt(16)
        buffer.putShort(1); buffer.putShort(1); buffer.putInt(48_000); buffer.putInt(96_000)
        buffer.putShort(2); buffer.putShort(16); tag("data"); buffer.putInt(960)
        for (frame in 0 until 480) buffer.putShort((if (frame in 96 until 240) frame - 168 else 30_000).toShort())
        file.writeBytes(buffer.array())
    }
}

/** Only the platform sink is simulated; inherited Robolectric native_setup builds the real AudioTrack API owner. */
@Implements(AudioTrack::class)
class PreviewBoundaryTrackShadow : ShadowAudioTrack() {
    private val sink = ByteArrayOutputStream()
    private val recorded = mutableListOf<Pair<String, String>>()
    private var started = false
    val events: List<Pair<String, String>> get() = synchronized(this) { recorded.toList() }
    fun acceptedBytes(): ByteArray = synchronized(this) { sink.toByteArray() }
    private fun event(name: String) = synchronized(this) { recorded += name to Thread.currentThread().name }

    @Implementation fun setStartThresholdInFrames(value: Int): Int {
        check(value == 1)
        synchronized(tracks) { if (!tracks.contains(this)) tracks += this }
        return value
    }
    @Implementation fun setVolume(value: Float): Int { check(value == 1f); return AudioTrack.SUCCESS }
    @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
        check(size > 0 && offset >= 0 && offset + size <= bytes.size && size % 2 == 0)
        check(mode == AudioTrack.WRITE_BLOCKING || mode == AudioTrack.WRITE_NON_BLOCKING)
        event("write")
        writeEntered.countDown()
        if (blockFirst && events.count { it.first == "write" } == 1) check(allowWriteReturn.await(10, TimeUnit.SECONDS))
        val accepted = minOf(size, 34)
        synchronized(this) { sink.write(bytes, offset, accepted) }
        return accepted
    }
    @Implementation override fun play() { synchronized(this) { started = true }; event("play") }
    @Implementation fun pause() { event("pause") }
    @Implementation override fun flush() { event("flush"); quiet.countDown() }
    @Implementation fun release() { event("release"); released.countDown() }
    @Implementation override fun getPlaybackHeadPosition(): Int = synchronized(this) { if (started) sink.size() / 2 else 0 }

    companion object {
        private val tracks = mutableListOf<PreviewBoundaryTrackShadow>()
        private var blockFirst = false
        lateinit var writeEntered: CountDownLatch
        lateinit var allowWriteReturn: CountDownLatch
        lateinit var quiet: CountDownLatch
        lateinit var released: CountDownLatch
        fun single(): PreviewBoundaryTrackShadow = synchronized(tracks) { tracks.single() }
        fun acquired(): PreviewBoundaryTrackShadow? = synchronized(tracks) { tracks.singleOrNull() }
        fun reset(block: Boolean) {
            synchronized(tracks) { tracks.clear() }
            blockFirst = block
            writeEntered = CountDownLatch(1); allowWriteReturn = CountDownLatch(1)
            quiet = CountDownLatch(1); released = CountDownLatch(1)
        }
    }
}
