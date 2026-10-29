package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** PREPARED ONLY: isolated reliability source; requires an explicit non-exported manifest service. */
class CaptureFrameIdentityService : ReverbService() {
    private lateinit var admission: FrameFixtureAdmission

    override fun onCreate() {
        check(packageName.endsWith(".reliability"))
        admission = checkNotNull(frameFixtureAdmission.get()) { "No synthetic capture admission" }
        super.onCreate()
    }

    // Fail closed before onCreate instead of ever falling back to ordinary QA capture history.
    override fun getNoBackupFilesDir(): File = admission.root
    override fun getApplicationContext(): Context = admission.context
    override fun getFilesDir(): File = admission.context.filesDir
    override fun getCacheDir(): File = admission.context.cacheDir

    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        return admission.context.getSharedPreferences(name, mode)
    }

    internal override fun createAudioRecord(): CaptureAudioInput? {
        admission.factoryCalls.incrementAndGet()
        return null // Never call super or construct any platform input, including on queued restart.
    }

    override fun onDestroy() {
        try { super.onDestroy() } finally { admission.destroyed.countDown() }
    }
}

internal data class FrameFixtureAdmission(
    val root: File,
    val context: Context,
    val destroyed: CountDownLatch = CountDownLatch(1),
    val factoryCalls: AtomicInteger = AtomicInteger(),
    val handoffFault: FrameHandoffFault? = null,
)
internal val frameFixtureAdmission = AtomicReference<FrameFixtureAdmission?>()

/** Context normalization must retain fixture ownership, including deferred incident/tile writes. */
private class FrameFixtureContext(
    base: Context,
    private val root: File,
    private val preferenceName: String,
    private val fault: FrameHandoffFault?,
) : android.content.ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = root
    override fun getFilesDir(): File = ownedDirectory("files")
    override fun getCacheDir(): File = ownedDirectory("cache")
    private fun ownedDirectory(name: String): File = File(root, name).also {
        check(it.mkdirs() || it.isDirectory)
    }
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        // All current production recorder/tile preferences use exactly this name. Fail closed
        // if a future caller requests another namespace instead of delegating to user data.
        check(name == packageName && mode == Context.MODE_PRIVATE)
        val original = super.getSharedPreferences(preferenceName, mode)
        return if (fault == null) original else FrameHandoffPreferences(original, fault)
    }
}

internal fun createFrameFixtureAdmission(
    context: Context, root: File,
    initialSlot: ReverbService.BufferSlot = ReverbService.BufferSlot.LOOPING,
    handoffFault: FrameHandoffFault? = null,
): FrameFixtureAdmission {
    check(context.packageName == "app.smallthingz.reverb.reliability")
    check(root.isDirectory && root.name.matches(Regex("[A-Za-z0-9_-]+")))
    val preferenceName = "reverb_frame_fixture_" + root.name
    val isolated = FrameFixtureContext(context.applicationContext, root, preferenceName, handoffFault)
    val prefs = getRecorderPreferences(isolated)
    check(prefs.all.isEmpty()) { "Synthetic preference namespace already exists" }
    check(prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
        .putInt(PrefKey.CAPTURE_BUFFER_SLOT, initialSlot.storageCode.toInt()).commit())
    File(root, "isolation.json").writeText(org.json.JSONObject()
        .put("preference_file", "shared_prefs/$preferenceName.xml")
        .put("no_backup_root", root.relativeTo(context.noBackupFilesDir).path)
        .put("application_context_isolated", true).toString(2))
    return FrameFixtureAdmission(root, isolated, handoffFault = handoffFault)
}


private enum class FrameCase(val token: String) {
    FOREGROUND("foreground_read"), BACKGROUND("background_read"),
    DESTINATION("destination_handoff"), OVERFLOW("one_shot_overflow"),
    STOP("stop_during_read"), ZERO("zero_read"),
}

/** No source is captured. All bytes originate in SequenceInput, with no native input factory. */
internal fun verifyCaptureFrameIdentity(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    for (case in FrameCase.entries) {
        val parent = File(context.noBackupFilesDir, "capture-frame-identity")
        check(parent.mkdirs() || parent.isDirectory)
        val root = Files.createTempDirectory(parent.toPath(), "${case.token}-").toFile()
        val admission = createFrameFixtureAdmission(context, root)
        check(frameFixtureAdmission.compareAndSet(null, admission)) { "A prior fixture still owns the service" }
        val connected = CountDownLatch(1)
        val service = AtomicReference<CaptureFrameIdentityService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.set((binder as ReverbService.BackgroundRecorderBinder).service as CaptureFrameIdentityService)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        var failure: Throwable? = null
        var observations: String? = null
        try {
            try {
                bound = context.bindService(Intent(context, CaptureFrameIdentityService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound && connected.await(15, TimeUnit.SECONDS)) { "Synthetic service did not bind" }
                val owner = checkNotNull(service.get())
                val handler = owner.frameField("audioHandler") as Handler
                val task = FutureTask { executeFrameCase(owner, handler, case, root) }
                check(handler.post(task))
                observations = task.get(30, TimeUnit.SECONDS)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                var cleanupFailure: Throwable? = null
                var drained = !bound
                if (bound) {
                    try {
                        context.unbindService(connection)
                        check(admission.destroyed.await(30, TimeUnit.SECONDS)) { "Synthetic service teardown outcome unknown" }
                        // onDestroy queues store close; its return alone does not prove that close completed.
                        val audioThread = checkNotNull(service.get()).frameField("audioThread") as Thread
                        audioThread.join(30_000)
                        check(!audioThread.isAlive) { "Synthetic audio/store teardown outcome unknown" }
                        drained = true
                    } catch (error: Throwable) { cleanupFailure = error }
                }
                // Keep an uncertain admission claimed rather than binding a second owner over it.
                if (drained && !frameFixtureAdmission.compareAndSet(admission, null)) {
                    val error = IllegalStateException("Synthetic admission owner changed")
                    if (cleanupFailure != null) cleanupFailure.addSuppressed(error) else cleanupFailure = error
                }
                if (cleanupFailure != null) {
                    if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
                }
            }
            check(admission.factoryCalls.get() == 0) { "Unexpected capture factory request" }
            File(root, "result.txt").writeText("PASS ${case.token}\n${checkNotNull(observations)}\naudioThreadTerminated=true\nnativeFactoryCalls=0\n")
            report.append("PASS capture_frame_identity_").append(case.token).append('\n')
        } catch (error: Throwable) {
            try { File(root, "failure.txt").writeText(error.stackTraceToString()) }
            catch (evidenceError: Throwable) { error.addSuppressed(evidenceError) }
            throw error
        }
    }
}

private class SequenceInput(private val payload: ByteArray, private val duringRead: () -> Unit) : CaptureAudioInput {
    var reads = 0
    var requested = 0
    var deliveredBytes = 0
    var starts = 0
    var releases = 0
    override val state: Int get() = AudioRecord.STATE_INITIALIZED
    override val recordingState: Int get() = AudioRecord.RECORDSTATE_RECORDING
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        check(++reads == 1) { "Unbounded synthetic read loop" }
        check(readMode == AudioRecord.READ_BLOCKING && payload.size <= sizeInBytes)
        requested = sizeInBytes
        for (index in payload.indices) buffer.put(index, payload[index])
        duringRead()
        deliveredBytes = payload.size
        return deliveredBytes
    }
    override fun startRecording() { starts++; error("Synthetic fixture must never start an input") }
    override fun stop() = Unit
    override fun release() { check(++releases == 1) { "Input released twice" } }
}

private fun executeFrameCase(service: CaptureFrameIdentityService, handler: Handler, case: FrameCase, root: File): String {
    check(service.frameField("captureStoresReady") == true) { "Actual store initialization did not complete" }
    check(service.frameField("audioRecord") == null)
    val loop = service.frameField("loopingAudioChunkStore") as PersistentAudioChunkStore
    val one = service.frameField("oneShotAudioChunkStore") as PersistentAudioChunkStore
    check(!loop.hasData() && !one.hasData()) { "Unique fixture root unexpectedly contains PCM" }
    val initialSlot = if (case == FrameCase.DESTINATION || case == FrameCase.OVERFLOW)
        ReverbService.BufferSlot.ONE_SHOT else ReverbService.BufferSlot.LOOPING
    loop.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
    one.configure(RetentionMode.SIZE, if (case == FrameCase.OVERFLOW) 512L else 1_048_576L,
        16_000, 1, PcmSampleFormat.PCM_16)
    service.frameSet("sampleRate", 16_000)
    service.frameSet("channelMode", ChannelMode.MONO)
    service.frameSet("pcmSampleFormat", PcmSampleFormat.PCM_16)
    service.frameSet("silenceGateConfig", SilenceGateConfig(enabled = false))
    service.frameSet("silenceSampleRequest", null)
    service.frameSet("oneShotBufferEnabled", true)
    service.frameSet("loopingBufferEnabled", true)
    service.frameSet("activeBufferSlot", initialSlot)
    service.frameSet("durableCaptureBufferSlot", initialSlot)
    service.frameSet("durableCaptureIntentAuthorityValid", true)
    service.frameSet("durableListeningIntentEnabled", true)
    service.frameSet("state", ReverbService.STATE_LISTENING)
    service.frameSet("lastDurabilitySyncRequestNanos", System.nanoTime())
    service.frameInvoke("prepareCaptureReadBuffersOnAudioThread")
    val owners = service.frameField("appUiForegroundOwners") as UiForegroundOwnerTracker
    val foregroundOwner = Any()
    owners.update(foregroundOwner, case == FrameCase.FOREGROUND)
    val generation = service.frameField("listeningCommandGeneration") as AtomicLong
    val epoch = generation.incrementAndGet()
    service.frameSet("audioRecordGeneration", epoch)
    val ids = if (case == FrameCase.ZERO) emptyList() else (1..512).toList()
    val pcm = ByteBuffer.allocate(ids.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (id in ids) putShort(id.toShort())
    }.array()
    File(root, "input.pcm16le").writeBytes(pcm)
    var commandAccepted: Boolean? = null
    val input = SequenceInput(pcm) {
        when (case) {
            FrameCase.DESTINATION -> commandAccepted = service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING).accepted
            FrameCase.STOP -> commandAccepted = service.disableListening().accepted
            else -> Unit
        }
    }
    service.frameSet("audioRecord", input)
    val reader = service.frameField("audioReader") as Runnable
    var primary: Throwable? = null
    try {
        val read = ReverbService::class.java.getDeclaredMethod("readCaptureIntoScratch", Long::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(service, epoch) as Int
        // Prevent the ordinary next-read post from escaping this one-read fixture.
        handler.removeCallbacks(reader)
        check(read == pcm.size && input.deliveredBytes == read && input.reads == 1 && input.starts == 0)
        check(input.requested == if (case == FrameCase.FOREGROUND) 1_280 else 32_000)
        if (case == FrameCase.DESTINATION || case == FrameCase.STOP) check(commandAccepted == true)
        val expectedOne = when (case) {
            FrameCase.DESTINATION -> pcm
            FrameCase.OVERFLOW -> pcm.copyOfRange(0, 512)
            else -> ByteArray(0)
        }
        val expectedLoop = when (case) {
            FrameCase.DESTINATION, FrameCase.STOP, FrameCase.ZERO -> ByteArray(0)
            FrameCase.OVERFLOW -> pcm.copyOfRange(512, pcm.size)
            else -> pcm
        }
        val committedLoopBytes = loop.countFilledBytes()
        val committedOneBytes = one.countFilledBytes()
        check(committedLoopBytes == expectedLoop.size.toLong())
        check(committedOneBytes == expectedOne.size.toLong())
        loop.checkpoint(); one.checkpoint()
        val actualLoop = loop.readFrameFixturePcm()
        val actualOne = one.readFrameFixturePcm()
        File(root, "loop-output.pcm16le").writeBytes(actualLoop)
        File(root, "one-shot-output.pcm16le").writeBytes(actualOne)
        check(actualLoop.contentEquals(expectedLoop)) { "Looping frame identities differ" }
        check(actualOne.contentEquals(expectedOne)) { "One-shot frame identities differ" }
        if (case == FrameCase.DESTINATION || case == FrameCase.OVERFLOW) {
            check(service.frameField("activeBufferSlot") == ReverbService.BufferSlot.LOOPING)
            check(generation.get() > epoch)
        }
        if (case == FrameCase.STOP) check(service.frameField("durableListeningIntentEnabled") == false)
        return "requestedBytes=${input.requested}\ndeliveredFrames=${input.deliveredBytes / 2}\nreturnedBytes=$read\ncommittedOneShotFrames=${committedOneBytes / 2}\ncommittedLoopingFrames=${committedLoopBytes / 2}\nexportedOneShotFrames=${actualOne.size / 2}\nexportedLoopingFrames=${actualLoop.size / 2}\ncommandAccepted=$commandAccepted\nsource=synthetic_only\nexport=normalized_pcm_not_wav\n"
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        handler.removeCallbacks(reader)
        owners.update(foregroundOwner, false)
        service.frameSet("durableListeningIntentEnabled", false)
        service.frameSet("state", ReverbService.STATE_READY)
        service.frameSet("sampleRate", 0)
        try {
            service.frameInvoke("releaseAudioRecord")
            check(input.releases == 1 && input.starts == 0)
        } catch (cleanup: Throwable) {
            if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        }
    }
}

internal fun ReverbService.frameField(name: String): Any? =
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)
internal fun ReverbService.frameSet(name: String, value: Any?) {
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}
internal fun ReverbService.frameInvoke(name: String) {
    ReverbService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
}
private fun PersistentAudioChunkStore.readFrameFixturePcm(): ByteArray {
    if (!hasData()) return ByteArray(0)
    val output = ByteArrayOutputStream()
    requireNotNull(acquireRange(0.0, durationSeconds())).use { lease ->
        lease.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
    }
    return output.toByteArray()
}


private enum class TeardownFrameCase(
    val token: String, val destroyDuringRead: Boolean, val gated: Boolean,
    val failHandoff: Boolean = false, val failAppend: Boolean = false,
) {
    CONTROL("ordinary_overflow", false, false),
    DESTROY("destroy_overflow", true, false),
    GATED_DESTROY("destroy_gated_tail", true, true),
    FAILED_HANDOFF("destroy_failed_handoff", true, true, true),
    FAILED_APPEND("destroy_failed_append", true, false, failAppend = true),
}

/** Actual main-thread service destruction while the audio handler owns one synthetic read. */
internal fun verifyCaptureTeardownFrameIdentity(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    val main = Handler(android.os.Looper.getMainLooper())
    for (case in TeardownFrameCase.entries) {
        val parent = File(context.noBackupFilesDir, "capture-teardown-identity")
        check(parent.mkdirs() || parent.isDirectory)
        val root = Files.createTempDirectory(parent.toPath(), "${case.token}-").toFile()
        val admission = createFrameFixtureAdmission(context, root, ReverbService.BufferSlot.ONE_SHOT,
            handoffFault = if (case.failHandoff) FrameHandoffFault() else null)
        check(frameFixtureAdmission.compareAndSet(null, admission))
        val connected = CountDownLatch(1)
        val service = AtomicReference<CaptureFrameIdentityService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.set((binder as ReverbService.BackgroundRecorderBinder).service as CaptureFrameIdentityService)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val readEntered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val unbindClaimed = java.util.concurrent.atomic.AtomicBoolean()
        val unbindError = AtomicReference<Throwable?>()
        var bound = false
        var drained = false
        var admissionReleased = false
        var primary: Throwable? = null
        fun requestUnbind() {
            check(unbindClaimed.compareAndSet(false, true)) { "Duplicate synthetic unbind" }
            check(main.post {
                try { context.unbindService(connection) }
                catch (error: Throwable) { unbindError.set(error) }
            }) { "Main rejected synthetic unbind; outcome unknown" }
        }
        try {
            val frameCount = if (case.failHandoff) 1536 else 5
            val pcm = ByteBuffer.allocate(frameCount * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(frameCount) { index ->
                    putShort(((10_001 + index) * if (index % 2 == 0) 1 else -1).toShort())
                }
            }.array()
            File(root, "input.pcm16le").writeBytes(pcm)
            val input = TeardownSequenceInput(pcm, readEntered, allowReturn)
            val appendSyncCalls = AtomicInteger()
            val appendSyncSuccesses = AtomicInteger()
            val appendFailureInjected = java.util.concurrent.atomic.AtomicBoolean()
            val appendFailureStage = AtomicReference<String?>()
            val appendFailureToken = "synthetic-append-" + root.name
            val processingErrorObserved = java.util.concurrent.atomic.AtomicBoolean()
            bound = context.bindService(Intent(context, CaptureFrameIdentityService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound && connected.await(15, TimeUnit.SECONDS)) { "Teardown service did not bind" }
            val owner = checkNotNull(service.get())
            val handler = owner.frameField("audioHandler") as Handler
            val reader = owner.frameField("audioReader") as Runnable
            val task = FutureTask {
                check(owner.frameField("captureStoresReady") == true && owner.frameField("audioRecord") == null)
                val loop = owner.frameField("loopingAudioChunkStore") as PersistentAudioChunkStore
                val one = owner.frameField("oneShotAudioChunkStore") as PersistentAudioChunkStore
                check(!loop.hasData() && !one.hasData())
                loop.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
                one.configure(RetentionMode.SIZE, 4L, 16_000, 1, PcmSampleFormat.PCM_16)
                owner.frameSet("sampleRate", 16_000)
                owner.frameSet("channelMode", ChannelMode.MONO)
                owner.frameSet("pcmSampleFormat", PcmSampleFormat.PCM_16)
                owner.frameSet("silenceGateConfig", SilenceGateConfig(case.gated,
                    listOf(SilencePoint(20, -96), SilencePoint(20_000, -96))))
                owner.frameSet("silenceSampleRequest", null)
                owner.frameSet("oneShotBufferEnabled", true)
                owner.frameSet("loopingBufferEnabled", true)
                owner.frameSet("activeBufferSlot", ReverbService.BufferSlot.ONE_SHOT)
                owner.frameSet("durableCaptureBufferSlot", ReverbService.BufferSlot.ONE_SHOT)
                owner.frameSet("durableCaptureIntentAuthorityValid", true)
                owner.frameSet("durableListeningIntentEnabled", true)
                owner.frameSet("state", ReverbService.STATE_LISTENING)
                owner.frameSet("lastDurabilitySyncRequestNanos", System.nanoTime())
                owner.frameInvoke("prepareCaptureReadBuffersOnAudioThread")
                val epoch = (owner.frameField("listeningCommandGeneration") as AtomicLong).incrementAndGet()
                val release = (owner.frameField("audioRecordReleaseGeneration") as AtomicLong).get()
                owner.frameSet("audioRecordGeneration", epoch)
                owner.frameSet("audioRecord", input)
                // Arm only after real service initialization and synthetic ownership setup.
                admission.handoffFault?.armed?.set(true)
                val continuity = (owner.frameField("captureContinuityGeneration") as AtomicLong).get()
                if (case.failAppend) {
                    // Change only the already-admitted synthetic store's durability callback.
                    val field = PersistentAudioChunkStore::class.java.getDeclaredField("directorySync").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val original = field.get(one) as (File) -> Unit
                    val callback: (File) -> Unit = { directory ->
                        appendSyncCalls.incrementAndGet()
                        if (appendFailureInjected.compareAndSet(false, true)) {
                            val methods = Thread.currentThread().stackTrace.map { it.methodName }
                            val stage = if (methods.contains("createActiveChunkLocked") &&
                                methods.contains("appendCapturedAt") && methods.contains("processCapturedAudio"))
                                "append_chunk_publication" else "unexpected_callback"
                            appendFailureStage.set(stage)
                            File(root, "append-failure-stage.txt").writeText("stage=$stage\n" + methods.joinToString("\n"))
                            throw java.io.IOException(appendFailureToken)
                        }
                        original(directory)
                        appendSyncSuccesses.incrementAndGet()
                    }
                    field.set(one, callback)
                }
                try {
                    val returned = if (case.failAppend) {
                        // Exercise the actual outer catch too: old teardown code silently swallowed this failure.
                        reader.run()
                        input.deliveredBytes.get()
                    } else ReverbService::class.java.getDeclaredMethod("readCaptureIntoScratch", Long::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(owner, epoch) as Int
                    handler.removeCallbacks(reader)
                    val oneFrames = one.countFilledBytes() / 2
                    val loopFrames = loop.countFilledBytes() / 2
                    val ownershipUnchanged = release == (owner.frameField("audioRecordReleaseGeneration") as AtomicLong).get()
                    File(root, "read-observations.txt").writeText(
                        "returnedFrames=${returned / 2}\npreCloseOneShotFrames=$oneFrames\npreCloseLoopingFrames=$loopFrames\n" +
                            "releaseRevisionUnchanged=$ownershipUnchanged\ngateEnabled=${case.gated}\n")
                    check(returned == pcm.size && input.reads.get() == 1 && input.starts.get() == 0)
                    check(ownershipUnchanged && input.releases.get() == 0) { "Shutdown forced input release before read completed" }
                    check(owner.frameField("serviceDestroying") == case.destroyDuringRead)
                    check(oneFrames == if (case.failAppend || (case.gated && !case.failHandoff)) 0L else 2L)
                    check(loopFrames == if (case.gated || case.failAppend) 0L else 3L)
                    if (case.failAppend) {
                        check(appendFailureInjected.get() && appendFailureStage.get() == "append_chunk_publication")
                        processingErrorObserved.set(AppFeedbackCenter.events.value.any {
                            it.tone == FeedbackTone.ERROR && it.message.contains(appendFailureToken)
                        })
                        File(root, "processing-error-observation.txt").writeText(
                            "observableError=${processingErrorObserved.get()}\nphase=actual_audio_reader\ntoken=$appendFailureToken\n")
                        check(processingErrorObserved.get()) { "Delivered PCM processing failure produced no observable error" }
                    }
                    if (case.failHandoff || case.failAppend) {
                        check(owner.frameField("persistenceFailureBlocked") == true)
                        check((owner.frameField("persistenceInterruptedCaptureEpoch") as Long) >= continuity)
                        check((owner.frameField("captureContinuityGeneration") as AtomicLong).get() > continuity)
                        check(owner.frameField("durableListeningIntentEnabled") == true)
                    }
                } finally {
                    // Keep input/gate ownership intact for the real terminal seal. No second read escapes.
                    handler.removeCallbacks(reader)
                }
            }
            check(handler.post(task))
            check(readEntered.await(15, TimeUnit.SECONDS)) { "Synthetic read was not entered" }
            var observedDestroy = false
            val coordinationStarted = System.nanoTime()
            try {
                if (case.destroyDuringRead) {
                    requestUnbind()
                    while (owner.frameField("serviceDestroying") != true &&
                        System.nanoTime() - coordinationStarted < 1_500_000_000L) {
                        unbindError.get()?.let { throw it }
                        Thread.sleep(1)
                    }
                    observedDestroy = owner.frameField("serviceDestroying") == true
                    check(observedDestroy) { "Actual onDestroy did not acquire its boundary within 1.5 seconds" }
                }
            } finally {
                // Never make main responsible for releasing a read that its onDestroy is awaiting.
                allowReturn.countDown()
            }
            val coordinationNanos = System.nanoTime() - coordinationStarted
            check(coordinationNanos < 1_500_000_000L) { "Slow teardown coordination; result is not the owned-read case" }
            task.get(15, TimeUnit.SECONDS)
            if (!case.destroyDuringRead) requestUnbind()
            check(admission.destroyed.await(30, TimeUnit.SECONDS)) { "Teardown outcome unknown" }
            unbindError.get()?.let { throw it }
            val audioThread = owner.frameField("audioThread") as Thread
            audioThread.join(30_000)
            check(!audioThread.isAlive) { "Terminal store close has not drained" }
            drained = true
            check(input.reads.get() == 1 && input.starts.get() == 0 && input.releases.get() == 1)
            check(admission.factoryCalls.get() == 0)
            val one = PersistentAudioChunkStore(owner, cacheFolderName = ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
                legacyCacheFolderName = null, overwriteOldest = false)
            val loop = PersistentAudioChunkStore(owner, legacyCacheFolderName = null)
            val reopenDeadline = System.nanoTime() + 5_000_000_000L
            try {
                PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(one, loop)) { System.nanoTime() >= reopenDeadline }
                val onePcm = one.readFrameFixturePcm()
                val loopPcm = loop.readFrameFixturePcm()
                File(root, "one-shot-reopened.pcm16le").writeBytes(onePcm)
                File(root, "loop-reopened.pcm16le").writeBytes(loopPcm)
                check(onePcm.contentEquals(if (case.failAppend) ByteArray(0) else pcm.copyOfRange(0, 4))) {
                    "Reopened One-shot frame identities differ"
                }
                check(loopPcm.contentEquals(if (case.failHandoff || case.failAppend) ByteArray(0) else pcm.copyOfRange(4, pcm.size))) {
                    "Reopened Looping frame identities differ"
                }
                val expectedSlot = if (case.failHandoff || case.failAppend) ReverbService.BufferSlot.ONE_SHOT else ReverbService.BufferSlot.LOOPING
                check(getRecorderPreferences(admission.context).getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) ==
                    expectedSlot.storageCode.toInt()) { "Final destination was not durably committed/rolled back" }
                admission.handoffFault?.let { fault ->
                    check(fault.attempts.get() == 1 && fault.rollbackCommits.get() == 1 && fault.failed.get()) {
                        "Failed handoff was retried or rollback did not commit"
                    }
                }
            } finally {
                try { one.close() } finally { loop.close() }
            }
            if (case.failAppend) check(appendSyncCalls.get() >= 2 && appendSyncSuccesses.get() >= 1 &&
                appendSyncSuccesses.get() < appendSyncCalls.get() && processingErrorObserved.get())
            check(frameFixtureAdmission.compareAndSet(admission, null)) { "Synthetic teardown owner changed" }
            admissionReleased = true
            File(root, "result.txt").writeText("PASS ${case.token}\nreturnedFrames=$frameCount\n" +
                "oneShotFrames=${if (case.failAppend) 0 else 2}\nloopingFrames=${if (case.failHandoff || case.failAppend) 0 else 3}\n" +
                "gateEnabled=${case.gated}\ndestroyObservedDuringRead=$observedDestroy\ncoordinationNanos=$coordinationNanos\n" +
                "inputReleases=1\nnativeFactoryCalls=0\naudioThreadTerminated=true\n" +
                "durableDestination=${if (case.failHandoff || case.failAppend) "ONE_SHOT" else "LOOPING"}\n" +
                "failedHandoffAttempts=${admission.handoffFault?.attempts?.get() ?: 0}\n" +
                "rollbackCommits=${admission.handoffFault?.rollbackCommits?.get() ?: 0}\n" +
                "appendSyncCalls=${appendSyncCalls.get()}\nappendSyncSuccesses=${appendSyncSuccesses.get()}\nappendFailureInjected=${appendFailureInjected.get()}\n" +
                "appendFailureStage=${appendFailureStage.get() ?: "none"}\nprocessingErrorObserved=${processingErrorObserved.get()}\n" +
                "source=synthetic_only\nauthority=synthetic_runtime_cache\nreopen=actual_store\n")
            report.append("PASS capture_teardown_identity_").append(case.token).append('\n')
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            allowReturn.countDown()
            var cleanupFailure: Throwable? = null
            if (bound && !drained) {
                try {
                    if (!unbindClaimed.get()) requestUnbind()
                    check(admission.destroyed.await(30, TimeUnit.SECONDS)) { "Synthetic destruction outcome unknown" }
                    val audioThread = checkNotNull(service.get()).frameField("audioThread") as Thread
                    audioThread.join(30_000)
                    check(!audioThread.isAlive) { "Synthetic audio close outcome unknown" }
                    unbindError.get()?.let { throw it }
                    drained = true
                } catch (error: Throwable) { cleanupFailure = error }
            }
            if ((!bound || drained) && !admissionReleased) {
                if (!frameFixtureAdmission.compareAndSet(admission, null)) {
                    val error = IllegalStateException("Synthetic teardown owner changed")
                    if (cleanupFailure != null) cleanupFailure.addSuppressed(error) else cleanupFailure = error
                }
            }
            if (cleanupFailure != null) {
                if (primary != null) primary.addSuppressed(cleanupFailure) else primary = cleanupFailure
            }
            primary?.let { error ->
                try { File(root, "failure.txt").writeText(error.stackTraceToString()) }
                catch (evidenceError: Throwable) { error.addSuppressed(evidenceError) }
                if (cleanupFailure === error) throw error
            }
        }
    }
}

private class TeardownSequenceInput(
    private val pcm: ByteArray,
    private val readEntered: CountDownLatch,
    private val allowReturn: CountDownLatch,
) : CaptureAudioInput {
    val reads = AtomicInteger()
    val starts = AtomicInteger()
    val releases = AtomicInteger()
    val deliveredBytes = AtomicInteger()
    override val state: Int get() = AudioRecord.STATE_INITIALIZED
    override val recordingState: Int get() = AudioRecord.RECORDSTATE_RECORDING
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        check(reads.incrementAndGet() == 1 && sizeInBytes == 32_000 && readMode == AudioRecord.READ_BLOCKING)
        for (index in pcm.indices) buffer.put(index, pcm[index])
        readEntered.countDown()
        check(allowReturn.await(2, TimeUnit.SECONDS)) { "Synthetic read coordinator did not release within two seconds" }
        check(releases.get() == 0) { "Synthetic input was forcibly released" }
        deliveredBytes.set(pcm.size)
        return pcm.size
    }
    override fun startRecording() { starts.incrementAndGet(); error("Never start a synthetic input") }
    override fun stop() = Unit
    override fun release() { check(releases.incrementAndGet() == 1) }
}


internal class FrameHandoffFault {
    val armed = java.util.concurrent.atomic.AtomicBoolean()
    val failed = java.util.concurrent.atomic.AtomicBoolean()
    val attempts = AtomicInteger()
    val rollbackCommits = AtomicInteger()
}

/** QA-only false result after real mutation; real rollback must restore the previous destination. */
private class FrameHandoffPreferences(
    private val original: android.content.SharedPreferences,
    private val fault: FrameHandoffFault,
) : android.content.SharedPreferences by original {
    override fun edit(): android.content.SharedPreferences.Editor {
        val editor = original.edit()
        return object : android.content.SharedPreferences.Editor by editor {
            var destination: Int? = null
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor {
                if (key == PrefKey.CAPTURE_BUFFER_SLOT.name) destination = value
                editor.putInt(key, value); return this
            }
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                editor.putString(key, value); return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor {
                editor.putStringSet(key, values); return this
            }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor {
                editor.putLong(key, value); return this
            }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor {
                editor.putFloat(key, value); return this
            }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
                editor.putBoolean(key, value); return this
            }
            override fun remove(key: String?): android.content.SharedPreferences.Editor {
                editor.remove(key); return this
            }
            override fun clear(): android.content.SharedPreferences.Editor { editor.clear(); return this }
            override fun commit(): Boolean {
                if (fault.armed.get() && destination == ReverbService.BufferSlot.LOOPING.storageCode.toInt()) {
                    fault.attempts.incrementAndGet()
                    if (fault.failed.compareAndSet(false, true)) {
                        check(editor.commit()) { "Real QA preference commit failed before injected failure" }
                        return false
                    }
                }
                val result = editor.commit()
                if (result && fault.armed.get() && fault.failed.get() &&
                    destination == ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt()) fault.rollbackCommits.incrementAndGet()
                return result
            }
        }
    }
}
