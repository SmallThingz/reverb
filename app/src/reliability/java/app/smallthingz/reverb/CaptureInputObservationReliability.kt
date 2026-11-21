package app.smallthingz.reverb

import android.content.Context
import android.media.AudioRecord
import android.os.SystemClock
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
            replacement.emit(false, 13_000, 100)
            replacement.emit(true, 14_000, 200)
            fixture.awaitCondition { observations().any { it.occurredAtMillis == 14_000L && it.ongoingPolicyDuration != null } }
            val active = observations().single { it.occurredAtMillis == 14_000L }
            check(active.ongoingPolicyDuration?.startedAtElapsedMillis == 200L)
            replacement.emit(false, 12_000, 2_150) // Wall clock rolled back while muted.
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
            val restored = beforeStop.single { it.observationId == active.observationId }
            check(restored.ongoingPolicyDuration == null && recordingIncidentDowntimeMillis(restored) == 1_950L)
            check(recordingIncidentPresentationKey(active) == recordingIncidentPresentationKey(restored))
            check(marker.readBytes().contentEquals(armed))

            // Keep a cached enabled flag and the exact recorder while durable authority is absent.
            fixture.service.frameSet("durableCaptureIntentAuthorityValid", false)
            replacement.emitStale(true, 15_001)
            replacement.emit(true, 15_002)
            replacement.emit(false, 15_003)
            fixture.service.frameSet("durableCaptureIntentAuthorityValid", true)
            awaitObservationHistoryWork()
            check(observations() == beforeStop)

            val startedAtElapsed = (SystemClock.elapsedRealtime() - 1_000L).coerceAtLeast(0L)
            replacement.emit(true, 16_000, startedAtElapsed)
            val beforeStopClock = SystemClock.elapsedRealtime()
            replacement.allowReadReturn.countDown()
            fixture.stopAndDrain()
            check(replacement.closed.await(10, TimeUnit.SECONDS))
            val afterStopClock = SystemClock.elapsedRealtime()
            // Deliberately deliver a retained callback after unregister/Stop.
            replacement.emitStale(true, 17_000)
            awaitObservationHistoryWork()
            val stopped = observations().single { it.occurredAtMillis == 16_000L }
            val stoppedDuration = requireNotNull(recordingIncidentDowntimeMillis(stopped))
            check(stoppedDuration in (beforeStopClock - startedAtElapsed)..(afterStopClock - startedAtElapsed))
            check(observations().filter { it.observationId != stopped.observationId } == beforeStop)
            check(fixture.onAudio { fixture.loop.pipelinePcm() }.contentEquals(originalPcm))
            File(fixture.root, "input-observations.txt").writeText(
                "pcm=${originalPcm.joinToString()}\nepisodes=3\nrestarts=1\nmarker_unchanged_before_stop=true\n" +
                    "restored_duration_millis=1950\nstopped_duration_millis=${recordingIncidentDowntimeMillis(stopped)}\n" +
                    "observations_before_stop=${beforeStop.joinToString()}\ninvalid_intent_and_stale_after_stop=ignored\n")
            report.append("CAPTURE_INPUT_OBSERVATIONS pcm_bytes=4 policy_episodes=3 restarts=1 restored_duration_millis=1950 invalid_intent_and_stale_after_stop=ignored\n")
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
        onFailure: (Exception) -> Unit, onCallbackStateChanged: () -> Unit,
        onDropoutsAvailable: () -> Unit): CapturePolicySubscription {
        events += "register"; callback = onState
        witnesses = CapturePolicyWitnesses(execute = { it() }, snapshot = { true to 10_000L },
            deliver = { onState(true, it) }, failed = onFailure, callbackStateChanged = onCallbackStateChanged,
            dropoutsAvailable = onDropoutsAvailable)
        return object : CapturePolicySubscription {
            override val callbackSilenced: Boolean? get() = witnesses.callbackSilenced
            override fun pollDropout(): CapturePolicyDropout? = witnesses.pollDropout()
            override fun sealDropouts(endedAtElapsedMillis: () -> Long) = witnesses.sealDropouts(endedAtElapsedMillis)
            override fun activate() { events += "activate"; witnesses.activate(); activated.countDown() }
            override fun close() { witnesses.close(); closed.countDown() }
        }
    }
    fun emit(state: Boolean?, observedAtMillis: Long, elapsedMillis: Long = SystemClock.elapsedRealtime()) =
        witnesses.observe(state, observedAtMillis, elapsedMillis)
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

/** The real Service coordinator and boot helper entry point receive identical zero-valued PCM. */
internal fun verifyBootResumeMicrophonePolicy(instrumentation: android.app.Instrumentation, report: StringBuilder) {
    withFramePipeline(instrumentation.targetContext, "boot_policy_confirmation", isolateSaveCoordinator = true) { fixture ->
        val policy = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val checks = AtomicInteger()
        val confirmations = AtomicInteger()
        val returned = java.util.concurrent.Semaphore(0)
        val firstQueryEntered = CountDownLatch(1)
        val allowFirstQuery = CountDownLatch(1)
        val firstStaleReadAdmitted = CountDownLatch(1)
        val secondStaleReadAdmitted = CountDownLatch(1)
        val pcm = ByteArray(4)
        val input = PipelineInput(holdFirst = false, automaticReads = 11, clientSilenced = {
            val state = policy.get()
            when (checks.incrementAndGet()) {
                1 -> {
                    firstQueryEntered.countDown()
                    check(allowFirstQuery.await(10, TimeUnit.SECONDS)) { "Held policy query was not released" }
                }
                4 -> check(firstStaleReadAdmitted.await(5, TimeUnit.SECONDS)) { "First stale read was not admitted" }
                6 -> check(secondStaleReadAdmitted.await(5, TimeUnit.SECONDS)) { "Second stale read was not admitted" }
            }
            state
        }) { _, _ -> pcm }
        input.beforeReadReturn = { ordinal ->
            if (ordinal == 6) firstStaleReadAdmitted.countDown()
            if (ordinal == 9) secondStaleReadAdmitted.countDown()
            if (ordinal <= 11) check(returned.tryAcquire(10, TimeUnit.SECONDS))
        }
        fixture.configure(input, ReverbService.BufferSlot.LOOPING, foreground = true)
        fixture.service.frameSet("foregroundServiceTypes",
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        lateinit var confirmation: java.io.Closeable
        instrumentation.runOnMainSync {
            confirmation = fixture.service.confirmBootResumeCapture(System.nanoTime()) { confirmations.incrementAndGet() }
        }
        fun deliverPacket(ordinal: Int) {
            returned.release()
            fixture.awaitCondition { fixture.onAudio { fixture.loop.pipelinePcm().size } == pcm.size * ordinal }
            instrumentation.waitForIdleSync()
        }
        fun settlePolicyQueries() {
            val reached = CountDownLatch(1)
            executeCaptureInputPolicyQuery(reached::countDown)
            check(reached.await(5, TimeUnit.SECONDS)) { "Policy worker did not drain" }
            fixture.onAudio { Unit }
            instrumentation.waitForIdleSync()
        }
        try {
            check(fixture.onAudio { fixture.service.frameField("bootCaptureConfirmation") != null })
            fixture.start()
            deliverPacket(1)
            check(confirmations.get() == 0) { "Initial unknown-policy PCM triggered Recording resumed" }
            check(firstQueryEntered.await(5, TimeUnit.SECONDS))

            deliverPacket(2)
            check(confirmations.get() == 0 && checks.get() == 1) { "Held policy probe blocked PCM or allowed confirmation" }
            allowFirstQuery.countDown()
            settlePolicyQueries()

            android.os.SystemClock.sleep(275)
            policy.set(true)
            deliverPacket(3)
            settlePolicyQueries()
            check(confirmations.get() == 0) { "Known-silenced PCM triggered Recording resumed" }

            android.os.SystemClock.sleep(275)
            policy.set(null)
            deliverPacket(4)
            settlePolicyQueries()
            check(confirmations.get() == 0) { "Missing policy state cleared known silencing" }

            android.os.SystemClock.sleep(275)
            policy.set(false)
            deliverPacket(5)
            settlePolicyQueries()
            check(confirmations.get() == 0) { "The packet that prompted the restoration sample confirmed its own PCM" }

            // The sixth read is already in flight. Mute before allowing the seventh to start.
            policy.set(true)
            deliverPacket(6)
            check(confirmations.get() == 0 && checks.get() == 4) { "A pre-restoration read reached confirmation" }
            android.os.SystemClock.sleep(275)
            deliverPacket(7)
            settlePolicyQueries()
            check(confirmations.get() == 0 && checks.get() == 5) { "A re-mute bypassed the policy check after reading" }

            android.os.SystemClock.sleep(275)
            policy.set(false)
            deliverPacket(8)
            settlePolicyQueries()
            deliverPacket(9)
            check(confirmations.get() == 0 && checks.get() == 6) { "A read predated the later unsilenced sample" }
            android.os.SystemClock.sleep(275)
            deliverPacket(10)
            settlePolicyQueries()
            check(confirmations.get() == 1 && checks.get() == 7) { "Fresh all-zero PCM was not confirmed after restoration" }
            deliverPacket(11)
            settlePolicyQueries()
            check(confirmations.get() == 1 && checks.get() == 7) { "Ordinary PCM queried policy after the helper completed" }
            check(fixture.onAudio { fixture.loop.pipelinePcm().contentEquals(ByteArray(pcm.size * 11)) })
            report.append("PASS boot_resume_policy_confirmation blocked_probe_pcm_drained=true remute=blocked restored_zero_pcm=confirmed\n")
        } finally {
            confirmation.close()
            allowFirstQuery.countDown()
            returned.release(11)
            fixture.stopAndDrain()
        }
    }
}

/** Uses the real Service and notice controller. Only the platform notification registry is modeled. */
internal fun verifyBootResumeReminderProof(instrumentation: android.app.Instrumentation, report: StringBuilder) {
    withFramePipeline(instrumentation.targetContext, "boot_reminder_proof", isolateSaveCoordinator = true) { fixture ->
        val policy = java.util.concurrent.atomic.AtomicReference<Boolean?>(true)
        val checks = AtomicInteger()
        val returned = java.util.concurrent.Semaphore(0)
        val staleReadAdmitted = CountDownLatch(1)
        val pcm = ByteArray(4)
        val input = PipelineInput(holdFirst = false, automaticReads = 11, clientSilenced = {
            if (checks.incrementAndGet() == 5) check(staleReadAdmitted.await(5, TimeUnit.SECONDS))
            policy.get()
        }) { _, _ -> pcm }
        lateinit var witnesses: CapturePolicyWitnesses
        input.observePolicy = { onState, failed, onCallbackStateChanged, onDropoutsAvailable ->
            CapturePolicyWitnesses(execute = { it() }, snapshot = { null to 0L },
                deliver = { onState(true, it) }, failed = failed,
                deliverUnsilenced = { onState(false, it) },
                callbackStateChanged = onCallbackStateChanged,
                dropoutsAvailable = onDropoutsAvailable).also { witnesses = it }
        }
        input.beforeReadReturn = { ordinal ->
            if (ordinal == 9) staleReadAdmitted.countDown()
            if (ordinal <= 11) check(returned.tryAcquire(10, TimeUnit.SECONDS))
        }
        fixture.configure(input, ReverbService.BufferSlot.LOOPING)
        fixture.service.frameSet("foregroundServiceTypes", android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val posted = java.util.concurrent.atomic.AtomicBoolean()
        val cancellations = AtomicInteger()
        lateinit var canLaunch: () -> Boolean
        fun controller() = BootResumeNoticeController(
            enqueue = { it() }, readEnabled = { true }, deliveryAvailable = { true },
            post = { posted.set(true); true }, cancel = { posted.set(false); cancellations.incrementAndGet() },
            report = { throw AssertionError(it) }, resume = { allowed, _ -> canLaunch = allowed; null },
        )
        val singleton = BootResumeNotification::class.java.getDeclaredField("controller").apply { isAccessible = true }
        val oldController = singleton.get(BootResumeNotification)
        val notice = controller()
        singleton.set(BootResumeNotification, notice)
        fun deliverPacket(ordinal: Int) {
            returned.release()
            fixture.awaitCondition { fixture.onAudio { fixture.loop.pipelinePcm().size } == pcm.size * ordinal }
            val drained = CountDownLatch(1)
            executeCaptureInputPolicyQuery(drained::countDown)
            check(drained.await(5, TimeUnit.SECONDS))
            fixture.onAudio { Unit }
        }
        try {
            fixture.onAudio {
                for (method in listOf("registerCapturePolicyObservation", "activateCapturePolicyObservation")) {
                    ReverbService::class.java.getDeclaredMethod(method, CaptureAudioInput::class.java)
                        .apply { isAccessible = true }.invoke(fixture.service, input)
                }
            }
            // No positive callback is required for the independently sampled mute.
            witnesses.observe(false, 1L)
            fixture.start()
            deliverPacket(1)
            check(checks.get() == 0) { "Capture queried policy before a boot reminder existed" }
            notice.onBoot {}
            check(posted.get() && canLaunch())
            val helperSuccesses = AtomicInteger()
            val uiOwner = Any()
            lateinit var helper: java.io.Closeable
            instrumentation.runOnMainSync {
                fixture.service.setAppUiForeground(uiOwner, true)
                helper = fixture.service.confirmBootResumeCapture(System.nanoTime()) { helperSuccesses.incrementAndGet() }
            }
            check(fixture.onAudio { fixture.service.frameField("bootCaptureConfirmation") != null })
            instrumentation.runOnMainSync {
                // The real helper performs this close when its deadline expires or it leaves the screen.
                helper.close()
                fixture.service.setAppUiForeground(uiOwner, false)
            }
            for (ordinal in 2..7) {
                if (ordinal > 2) android.os.SystemClock.sleep(275)
                deliverPacket(ordinal)
            }
            check(checks.get() == 4) { "Muted recording continued to poll after the reminder's proof budget" }
            check(posted.get() && cancellations.get() == 0 && canLaunch() && helperSuccesses.get() == 0) {
                "Muted PCM after helper close canceled the reminder or revoked boot launch"
            }

            // Both hints coalesce into one extra opening on the existing exhausted proof.
            policy.set(false)
            witnesses.observe(false, 2L)
            witnesses.observe(false, 3L)
            fixture.onAudio { Unit }
            deliverPacket(8)
            deliverPacket(9)
            check(checks.get() == 5 && posted.get())
            android.os.SystemClock.sleep(275)
            deliverPacket(10)
            check(!posted.get() && cancellations.get() == 1 && !canLaunch()) { "Verified background capture did not retire reminder ownership" }
            deliverPacket(11)
            check(checks.get() == 6 && helperSuccesses.get() == 0) { "Settled reminder queried again or revived the helper" }

            // Start an independent reminder and verify accepted Stop cancels without policy evidence.
            val stopNotice = controller()
            singleton.set(BootResumeNotification, stopNotice)
            stopNotice.onBoot {}
            check(posted.get() && canLaunch())
            fixture.stopAndDrain()
            check(!posted.get() && cancellations.get() == 2 && !canLaunch())
            report.append("PASS boot_resume_reminder_proof late_boot=true muted_reminder=kept restored_background=cleared explicit_stop=cleared\n")
        } finally {
            returned.release(11)
            try {
                fixture.stopAndDrain()
                awaitObservationHistoryWork()
            } finally { singleton.set(BootResumeNotification, oldController) }
        }
    }
}
