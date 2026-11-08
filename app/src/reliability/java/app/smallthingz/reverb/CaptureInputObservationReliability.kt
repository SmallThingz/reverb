package app.smallthingz.reverb

import android.content.Context
import android.media.AudioRecord
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic production recovery; no microphone, package preferences or normal recording data. */
internal fun verifyCaptureInputObservations(context: Context, report: StringBuilder) {
    withFramePipeline(context, "input_observations", isolateSaveCoordinator = true) { fixture ->
        val originalPcm = byteArrayOf(11, 0, 12, 0)
        val oldReads = AtomicInteger()
        val old = object : CaptureAudioInput {
            override val state = AudioRecord.STATE_INITIALIZED
            override val recordingState = AudioRecord.RECORDSTATE_RECORDING
            override fun startRecording(): Unit = error("Preinstalled synthetic input must not start")
            override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int =
                when (oldReads.incrementAndGet()) {
                    1 -> { originalPcm.forEachIndexed { i, value -> buffer.put(i, value) }; originalPcm.size }
                    2 -> AudioRecord.ERROR_DEAD_OBJECT
                    else -> error("Disconnected input was read again")
                }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val replacement = ObservedSyntheticInput()
        fixture.configure(old, ReverbService.BufferSlot.LOOPING)
        val marker = File(fixture.admission.context.noBackupFilesDir, "recording-session.bin")
        val armed = marker.readBytes()
        val slot = FrameReplacementInput(replacement)
        fixture.admission.replacementInput.set(slot)
        slot.proceed.countDown()
        fun observations() = RecordingIncidentStore.readIncidents(fixture.admission.context)
            .filter { it.kind == RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION }
        try {
            fixture.start()
            check(replacement.activated.await(10, TimeUnit.SECONDS))
            fixture.awaitCondition { observations().size == 2 }
            check(replacement.events.toList() == listOf("register", "start", "activate"))
            check(oldReads.get() == 2 && fixture.admission.factoryCalls.get() == 1)
            check(fixture.onAudio { fixture.loop.pipelinePcm() }.contentEquals(originalPcm))
            check(marker.readBytes().contentEquals(armed)) { "Transparent recovery changed the armed session" }
            val first = observations()
            check(first.single { it.description == CaptureInputObservationReason.POLICY_SILENCED.description }
                .occurredAtMillis == 10_000L)
            check(first.count { it.description == CaptureInputObservationReason.INPUT_RESTARTED.description } == 1)
            replacement.emit(null, 11_000); replacement.emit(true, 12_000)
            awaitObservationHistoryWork()
            check(observations() == first) { "Unknown/repeated policy state changed observation history" }
            replacement.emit(false, 13_000); replacement.emit(true, 14_000)
            awaitObservationHistoryWork()
            val beforeStop = observations()
            check(beforeStop.size == 3 && beforeStop.containsAll(first))
            check(beforeStop.filter { it.description == CaptureInputObservationReason.POLICY_SILENCED.description }
                .map { it.occurredAtMillis }.sorted() == listOf(10_000L, 14_000L))
            check(beforeStop.single { it.description == CaptureInputObservationReason.INPUT_RESTARTED.description } ==
                first.single { it.description == CaptureInputObservationReason.INPUT_RESTARTED.description })
            check(beforeStop.all { it.observationId > 0L && it.pid > 0 && it.processStartedAtMillis > 0L })
            check(beforeStop.map { it.observationId }.distinct().size == 3)
            check(beforeStop.map { it.pid to it.processStartedAtMillis }.distinct().size == 1)
            check(marker.readBytes().contentEquals(armed))
            replacement.allowReadReturn.countDown()
            fixture.stopAndDrain()
            check(replacement.closed.await(10, TimeUnit.SECONDS))
            // Deliberately deliver a retained callback after unregister/Stop.
            replacement.emitStale(true, 16_000)
            awaitObservationHistoryWork()
            check(observations() == beforeStop) { "Stop/stale callback changed admitted observation records" }
            check(fixture.onAudio { fixture.loop.pipelinePcm() }.contentEquals(originalPcm))
            File(fixture.root, "input-observations.txt").writeText(
                "pcm=${originalPcm.joinToString()}\nepisodes=2\nrestarts=1\nmarker_unchanged_before_stop=true\n" +
                    "observations_before_stop=${beforeStop.joinToString()}\nobservations_unchanged_after_stale=true\n")
            report.append("CAPTURE_INPUT_OBSERVATIONS pcm_bytes=4 policy_episodes=2 restarts=1 stale_after_stop=ignored\n")
        } finally { replacement.allowReadReturn.countDown() }
    }
}

private class ObservedSyntheticInput : CaptureAudioInput {
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    val activated = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val allowReadReturn = CountDownLatch(1)
    private var callback: ((Boolean?, Long) -> Unit)? = null
    private lateinit var witnesses: CapturePolicyWitnesses
    override val state = AudioRecord.STATE_INITIALIZED
    override val recordingState = AudioRecord.RECORDSTATE_RECORDING
    override fun observeClientSilencing(onState: (Boolean?, Long) -> Unit,
        onFailure: (Exception) -> Unit): CapturePolicySubscription {
        events += "register"; callback = onState
        witnesses = CapturePolicyWitnesses(execute = { it() }, snapshot = { true to 10_000L },
            deliver = { onState(true, it) }, failed = onFailure)
        return object : CapturePolicySubscription {
            override fun activate() { events += "activate"; witnesses.activate(); activated.countDown() }
            override fun close() { witnesses.close(); closed.countDown() }
        }
    }
    fun emit(state: Boolean?, observedAtMillis: Long) = witnesses.observe(state, observedAtMillis)
    fun emitStale(state: Boolean?, observedAtMillis: Long) { checkNotNull(callback)(state, observedAtMillis) }
    override fun startRecording() { events += "start" }
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        check(allowReadReturn.await(15, TimeUnit.SECONDS)); return 0
    }
    override fun stop() { allowReadReturn.countDown() }
    override fun release() = Unit
}

/** All observation launches happen synchronously before this snapshot of process-owned children. */
private fun awaitObservationHistoryWork() {
    val field = RecordingIncidentStore::class.java.getDeclaredField("historyMutationScope").apply { isAccessible = true }
    val scope = field.get(RecordingIncidentStore) as kotlinx.coroutines.CoroutineScope
    kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(10_000) {
            checkNotNull(scope.coroutineContext[kotlinx.coroutines.Job]).children.toList().forEach { it.join() }
        }
    }
}
