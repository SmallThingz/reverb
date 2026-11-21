package app.smallthingz.reverb

import android.content.Context
import android.media.AudioRecord
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual production input-pump recovery with a null-only replacement factory; no microphone capture. */
internal fun verifyFailedCaptureRestartIsNotSilentlyListening(context: Context) {
    withFramePipeline(context, "pipeline_restart_failure") { fixture ->
        val disconnected = FailingTestAudioInput { _, _ -> AudioRecord.ERROR_DEAD_OBJECT }
        fixture.configure(disconnected, ReverbService.BufferSlot.LOOPING)
        val before = fixture.onAudio {
            val prior = ByteArray(32_000) { ((it * 13 + 7) and 0xff).toByte() }
            check(fixture.loop.append(prior, 0, prior.size) == prior.size)
            fixture.loop.checkpoint()
            fixture.loop.pipelinePcm()
        }
        val worker = ConcurrentLinkedQueue<() -> Unit>()
        fun drainNotices() {
            while (true) {
                val work = worker.poll() ?: return
                work()
            }
        }
        val prefs = getRecorderPreferences(fixture.admission.context)
        var automatic: BootAutomaticNotice? = null
        var retired = false
        lateinit var canLaunch: () -> Boolean
        val notice = BootResumeNoticeController(
            enqueue = { worker.add(it) },
            readEnabled = { readDurableCaptureIntentPreferences(prefs).enabled },
            deliveryAvailable = { false }, post = { error("The manual channel is blocked in this oracle") }, cancel = {},
            report = { throw AssertionError(it) }, automaticDeliveryAvailable = { true },
            postAutomatic = { automatic = it; true }, cancelAutomatic = { automatic = null },
            resume = { allowed, events ->
                canLaunch = allowed
                events.attempting(android.os.SystemClock.elapsedRealtime() + 60_000L)
                events.captureBindingAccepted()
                Closeable { retired = true }
            },
        )
        val singleton = BootResumeNotification::class.java.getDeclaredField("controller").apply { isAccessible = true }
        val previous = singleton.get(BootResumeNotification)
        singleton.set(BootResumeNotification, notice)
        try {
            notice.onBoot {}
            drainNotices()
            check(automatic is BootAutomaticNotice.Trying && canLaunch())
            fixture.start()
            check(disconnected.released.await(10, TimeUnit.SECONDS))
            fixture.awaitCondition {
                fixture.admission.factoryCalls.get() == 1 && fixture.service.frameField("audioRecord") == null &&
                    fixture.service.frameField("state") != ReverbService.STATE_LISTENING
            }
            fixture.fence {
                check(disconnected.readCalls.get() == 1 && disconnected.releaseCalls.get() == 1)
                check(fixture.service.frameField("durableListeningIntentEnabled") == false)
                check(!readDurableCaptureIntentPreferences(prefs).enabled)
                check(before.contentEquals(fixture.loop.pipelinePcm())) { "Failed native replacement changed retained audio" }
            }
            check(!canLaunch()) { "The fatal transition must immediately revoke retry ownership" }
            drainNotices()
            val failed = automatic as? BootAutomaticNotice.Failed
            check(failed?.reason == BootResumeFailure.AUDIO_INPUT && failed.intentCleared && !failed.detail.isNullOrBlank() && retired) {
                "The exact still-unconfirmed automatic recorder failure did not reach Failed"
            }
            check(notice.needsCaptureProof)
            fixture.stopAndDrain()
            drainNotices()
            check(automatic == null && !notice.needsCaptureProof)
        } finally {
            singleton.set(BootResumeNotification, previous)
        }
    }
}

internal fun verifyEmptyCaptureReadsStopListening(context: Context) {
    withFramePipeline(context, "pipeline_empty_reads") { fixture ->
        val prefix = ByteArray(128) { (it * 7).toByte() }
        val source = FailingTestAudioInput { read, buffer ->
            if (read == 1) { buffer.put(prefix); prefix.size } else 0
        }
        fixture.configure(source, ReverbService.BufferSlot.LOOPING)
        fixture.start()
        check(source.released.await(15, TimeUnit.SECONDS)) { "Service still owns an input returning only zero bytes" }
        fixture.fence {
            check(source.readCalls.get() == 1 + CapturePcmRing.MAX_CONSECUTIVE_EMPTY_READS)
            check(source.releaseCalls.get() == 1 && fixture.admission.factoryCalls.get() == 0)
            check(fixture.service.frameField("state") != ReverbService.STATE_LISTENING)
            check(fixture.service.frameField("durableListeningIntentEnabled") == false)
            check(prefix.contentEquals(fixture.loop.pipelinePcm())) { "Empty reads changed the retained prefix" }
        }
    }
}

private class FailingTestAudioInput(val returnRead: (Int, ByteBuffer) -> Int) : CaptureAudioInput {
    val readCalls = AtomicInteger()
    val releaseCalls = AtomicInteger()
    val released = CountDownLatch(1)
    override val state = AudioRecord.STATE_INITIALIZED
    override val recordingState = AudioRecord.RECORDSTATE_RECORDING
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        return returnRead(readCalls.incrementAndGet(), buffer)
    }
    override fun startRecording(): Unit = error("Synthetic input must not start native capture")
    override fun stop() = Unit
    override fun release() { check(releaseCalls.incrementAndGet() == 1); released.countDown() }
}
