package app.smallthingz.reverb

import android.content.Context
import android.media.AudioRecord
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual production input-pump recovery with a null-only replacement factory; no microphone capture. */
internal fun verifyFailedCaptureRestartIsNotSilentlyListening(context: Context) {
    withFramePipeline(context, "pipeline_restart_failure") { fixture ->
        val disconnected = DisconnectedTestAudioInput()
        fixture.configure(disconnected, ReverbService.BufferSlot.LOOPING)
        val before = fixture.onAudio {
            val prior = ByteArray(32_000) { ((it * 13 + 7) and 0xff).toByte() }
            check(fixture.loop.append(prior, 0, prior.size) == prior.size)
            fixture.loop.checkpoint()
            fixture.loop.pipelinePcm()
        }
        fixture.start()
        check(disconnected.released.await(10, TimeUnit.SECONDS))
        fixture.awaitCondition {
            fixture.admission.factoryCalls.get() == 1 && fixture.service.frameField("audioRecord") == null &&
                fixture.service.frameField("state") != ReverbService.STATE_LISTENING
        }
        fixture.fence {
            check(disconnected.readCalls.get() == 1 && disconnected.releaseCalls.get() == 1)
            check(fixture.service.frameField("durableListeningIntentEnabled") == false)
            check(before.contentEquals(fixture.loop.pipelinePcm())) { "Failed native replacement changed retained audio" }
        }
    }
}

private class DisconnectedTestAudioInput : CaptureAudioInput {
    val readCalls = AtomicInteger()
    val releaseCalls = AtomicInteger()
    val released = CountDownLatch(1)
    override val state = AudioRecord.STATE_INITIALIZED
    override val recordingState = AudioRecord.RECORDSTATE_RECORDING
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        readCalls.incrementAndGet()
        return AudioRecord.ERROR_DEAD_OBJECT
    }
    override fun startRecording(): Unit = error("Synthetic input must not start native capture")
    override fun stop() = Unit
    override fun release() { check(releaseCalls.incrementAndGet() == 1); released.countDown() }
}
