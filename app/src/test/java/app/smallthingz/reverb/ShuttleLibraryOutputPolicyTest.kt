package app.smallthingz.reverb

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ServiceStateOsShadow::class, ShuttleEndpointAudioTrackShadow::class, LibraryScrubTrackBuilderShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ShuttleLibraryOutputPolicyTest {
    @Test fun ordinary44100WavRequestsNativeMediaMusicAndDefaultMode() = verifyOrdinaryNativeRate(44_100)

    @Test fun ordinary48000WavRequestsNativeMediaMusicAndDefaultMode() = verifyOrdinaryNativeRate(48_000)

    @Test fun rejectedOrdinaryConfigurationReleasesWithoutPlayingAndFallsBackToLegacyPolicy() {
        verifyPolicy(44_100, ordinary = true, rejectFirstConfiguration = true,
            expected = listOf(ordinaryRequest(44_100), legacyRequest))
    }

    @Test fun savedWavWithoutOrdinaryAuthorityKeepsLegacyPolicy() {
        verifyPolicy(44_100, ordinary = false, expected = listOf(legacyRequest))
    }

    private fun verifyOrdinaryNativeRate(rate: Int) {
        verifyPolicy(rate, ordinary = true, expected = listOf(ordinaryRequest(rate)))
    }

    private fun verifyPolicy(
        sourceRate: Int,
        ordinary: Boolean,
        expected: List<LibraryScrubTrackBuilderShadow.Request>,
        rejectFirstConfiguration: Boolean = false,
    ) {
        val root = Files.createTempDirectory("library-shuttle-output-")
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root
        ShadowAudioTrack.setMinBufferSize(256)
        LibraryScrubTrackBuilderShadow.reset()
        ShuttleEndpointAudioTrackShadow.reset()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val clock = AtomicLong(1_000_000_000L)
        val handoffs = AtomicInteger()
        val authority = if (ordinary) RecordingScrubAuthority(RecordingScrubKey(1L, 1L)) else null
        try {
            val recording = makeRecording(root.toFile(), sourceRate)
            if (rejectFirstConfiguration) {
                ShuttleEndpointAudioTrackShadow.onTrackSetup = {
                    if (ShuttleEndpointAudioTrackShadow.acquiredTracks().size == 1) {
                        throw IllegalArgumentException("Injected first track configuration rejection")
                    }
                }
            }
            ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
            val controller = TimelineAudioPreviewController(onTrackReleaseFailure = { errors += it },
                shuttleNowNanos = clock::get)
            val acquired = withOwnedResource(controller, release = { owner ->
                authority?.revoke()
                owner.close()
                for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                    val worker = TimelineAudioPreviewController::class.java.getDeclaredField(name)
                        .apply { isAccessible = true }.get(owner) as ExecutorService
                    assertTrue("$name did not terminate", worker.awaitTermination(5, TimeUnit.SECONDS))
                }
                ShadowLooper.idleMainLooper()
                assertTrue(errors.toString(), errors.isEmpty())
            }) { owner ->
                owner.startRecordingShuttle(RuntimeEnvironment.getApplication(), recording, .100, 1f,
                    onStarted = { handoffs.incrementAndGet() },
                    onFailureAfterStart = { errors += AssertionError("Saved scrub failed after admission") },
                    onSourceCloseFailure = { errors += it }, fromSeconds = .021, untilSeconds = .300,
                    ordinaryAuthority = authority)
                await("shuttle primes and plays after handoff", errors) {
                    handoffs.get() == 1 && ShuttleEndpointAudioTrackShadow.acquiredTracks().lastOrNull()?.playing == true
                }
                val tracks = ShuttleEndpointAudioTrackShadow.acquiredTracks()
                val selected = tracks.last()
                assertEquals(expected, LibraryScrubTrackBuilderShadow.requests())
                assertEquals(expected.size, tracks.size)
                assertEquals(expected.last().sampleRate, selected.formatRate())
                assertEquals(1, selected.formatChannels())
                assertEquals(1f, selected.gain, 0f)
                assertTrue("Selected track has no accepted PCM", selected.frameCount > 0)
                if (rejectFirstConfiguration) {
                    val rejected = tracks.first()
                    assertEquals("Rejected native request must already be released", 1, rejected.releaseCalls)
                    assertEquals(0, rejected.acceptedWrites)
                    assertEquals(0, rejected.playCalls)
                }

                val acceptedFrames = selected.frameCount
                if (authority != null) {
                    authority.revoke()
                    // A later gesture update cannot resurrect the revoked audio owner.
                    clock.addAndGet(16_000_000L)
                    owner.updateShuttle(.116, 1f, .021, .300)
                } else {
                    owner.stopShuttle()
                }
                await("revoked or stopped track release", errors) { selected.released }
                assertFalse(selected.playing)
                assertEquals(1, selected.playCalls)
                assertEquals(acceptedFrames, selected.frameCount)
                assertEquals(1, handoffs.get())
                assertNull(owner.observeTrack())
                tracks
            }
            assertEquals(acquired.size, ShuttleEndpointAudioTrackShadow.acquiredTracks().size)
            assertTrue("Each configured track must release exactly once", acquired.all { it.releaseCalls == 1 })
            assertEquals(expected, LibraryScrubTrackBuilderShadow.requests())
        } finally {
            ShuttleEndpointAudioTrackShadow.onTrackSetup = null
            ServiceStateOsShadow.fixtureRoot = previousRoot
            root.toFile().deleteRecursively()
        }
    }

    private fun makeRecording(root: File, rate: Int): RecordingEntity {
        val pcm = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(rate) { frame -> putShort(((frame % 200 - 100) * 120).toShort()) }
        }.array()
        val wav = File(root, "library-output.wav").apply {
            writeBytes(buildWavHeaderBytes(rate, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm)
        }
        val identity = resolveFileIdentity(wav)
        check(identity.isNotBlank())
        return RecordingEntity(wav.absolutePath, wav.name, "audio/wav", 1L, 1_000L, wav.length(), "PCM",
            RecordingStorageType.FILE, root.absolutePath, identity)
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

    private companion object {
        fun ordinaryRequest(rate: Int) = LibraryScrubTrackBuilderShadow.Request(rate,
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, AudioTrack.PERFORMANCE_MODE_NONE)

        val legacyRequest = LibraryScrubTrackBuilderShadow.Request(96_000,
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH, AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    }
}

/** Capture the requested SDK 35 builder configuration before the platform chooses an actual mode. */
@Implements(AudioTrack.Builder::class)
class LibraryScrubTrackBuilderShadow {
    @RealObject lateinit var builder: AudioTrack.Builder

    data class Request(val sampleRate: Int, val usage: Int, val contentType: Int, val performanceMode: Int)

    @Implementation fun build(): AudioTrack {
        val format = ReflectionHelpers.getField<AudioFormat>(builder, "mFormat")
        val attributes = ReflectionHelpers.getField<AudioAttributes>(builder, "mAttributes")
        val mode = ReflectionHelpers.getField<Int>(builder, "mPerformanceMode")
        requests.add(Request(format.sampleRate, attributes.usage, attributes.contentType, mode))
        return Shadow.directlyOn<AudioTrack, AudioTrack.Builder>(builder, AudioTrack.Builder::class.java, "build")
    }

    companion object {
        private val requests = ConcurrentLinkedQueue<Request>()
        fun requests(): List<Request> = requests.toList()
        fun reset() = requests.clear()
    }
}
