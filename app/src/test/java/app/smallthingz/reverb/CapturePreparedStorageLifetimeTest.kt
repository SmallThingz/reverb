package app.smallthingz.reverb

import android.app.Application
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual allocation/retirement methods; no Service startup, native microphone or storage IO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CapturePreparedStorageLifetimeTest {
    @Test fun failedCreationRetiresUninstalledMaximumFormatStorage() {
        val service = service()
        set(service, "sampleRate", 96_000)
        set(service, "channelMode", ChannelMode.STEREO)
        set(service, "pcmSampleFormat", PcmSampleFormat.PCM_FLOAT)
        val prepared = prepare(service)
        assertEquals(4_608_000L, prepared.budget.totalPcmBytes)
        // createAudioRecord() returning null leaves exactly this no-input/prepared state.
        assertNull(get(service, "audioRecord"))
        assertTrue(retire(service))
        assertNull(get(service, "preparedCaptureStorage"))
        assertTrue(retire(service))
        assertNull(get(service, "preparedCaptureStorage"))
    }

    @Test fun staleOwnerCannotRetireReplacementPreparation() {
        val service = service()
        val current = Input()
        set(service, "audioRecord", current)
        val prepared = prepare(service)
        val stale = inputOwner.getDeclaredConstructor(CaptureAudioInput::class.java,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(Input(), 0L, 0L)
        assertFalse(retire(service, stale))
        assertSame(prepared, get(service, "preparedCaptureStorage"))
        assertSame(current, get(service, "audioRecord"))
        assertEquals(0, current.releases)
        assertTrue(retire(service))
        assertNull(get(service, "preparedCaptureStorage"))
        assertEquals(1, current.releases)
    }

    @Test fun retirementUnpublishesBeforeNativeCallbacksAndPreservesReentrantPreparation() {
        val service = service()
        val old = prepare(service)
        var replacement: CaptureIoCoordinator.Storage? = null
        val input = Input {
            assertNull(get(service, "preparedCaptureStorage"))
            replacement = prepare(service)
        }
        set(service, "audioRecord", input)
        assertTrue(retire(service))
        assertNotNull(replacement)
        assertNotSame(old, replacement)
        assertSame(replacement, get(service, "preparedCaptureStorage"))
        assertEquals(1, input.releases)
        assertTrue(retire(service))
        assertNull(get(service, "preparedCaptureStorage"))
        assertEquals(1, input.releases)
    }

    private fun service(): ReverbService = Robolectric.buildService(ReverbService::class.java).get().also {
        set(it, "audioHandler", Handler(Looper.getMainLooper()))
    }

    private fun prepare(service: ReverbService): CaptureIoCoordinator.Storage {
        ReverbService::class.java.getDeclaredMethod("prepareCaptureReadBuffersOnAudioThread")
            .apply { isAccessible = true }.invoke(service)
        return get(service, "preparedCaptureStorage") as CaptureIoCoordinator.Storage
    }

    private val inputOwner = ReverbService::class.java.declaredClasses.single {
        it.simpleName == "CaptureInputOperationOwner"
    }
    private fun retire(service: ReverbService, owner: Any? = null): Boolean =
        ReverbService::class.java.getDeclaredMethod("releaseAudioRecordIfOwned", inputOwner)
            .apply { isAccessible = true }.invoke(service, owner) as Boolean
    private fun get(service: ReverbService, name: String): Any? =
        ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun set(service: ReverbService, name: String, value: Any) =
        ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)

    private class Input(private val stopping: () -> Unit = {}) : CaptureAudioInput {
        var releases = 0
        override val state = AudioRecord.STATE_INITIALIZED
        override val recordingState = AudioRecord.RECORDSTATE_STOPPED
        override fun startRecording() = error("No native admission in this test")
        override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int =
            error("No native read in this test")
        override fun stop() = stopping()
        override fun release() { releases++ }
    }
}
