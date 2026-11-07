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
    private lateinit var fixtureSaveCoordinator: DurableSaveCoordinator

    override fun onCreate() {
        check(packageName.endsWith(".reliability"))
        admission = checkNotNull(frameFixtureAdmission.get()) { "No synthetic capture admission" }
        if (admission.isolateSaveCoordinator) {
            // Each fixture owns a new journal root; never borrow or reset the process singleton.
            val delegate = ReverbService::class.java.getDeclaredField("durableSaveCoordinator\$delegate")
                .apply { isAccessible = true }
            check(!(delegate.get(this) as Lazy<*>).isInitialized()) { "Save coordinator already initialized" }
            fixtureSaveCoordinator = DurableSaveCoordinator::class.java.getDeclaredConstructor(Context::class.java).let {
                it.isAccessible = true
                it.newInstance(admission.context)
            }
            delegate.set(this, lazyOf(fixtureSaveCoordinator))
            fixtureSaveJournalRoot()
        }
        super.onCreate()
        if (admission.isolateSaveCoordinator) fixtureSaveJournalRoot()
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
        admission.replacementInput.getAndSet(null)?.let { replacement ->
            replacement.entered.countDown()
            check(replacement.proceed.await(10, TimeUnit.SECONDS)) { "Synthetic replacement factory was not released" }
            return replacement.input
        }
        return null // Never call super or construct any platform input, including on queued restart.
    }

    internal fun fixtureSaveJournalRoot(): File {
        check(admission.isolateSaveCoordinator) { "No scoped save coordinator admission" }
        val delegate = ReverbService::class.java.getDeclaredField("durableSaveCoordinator\$delegate")
            .apply { isAccessible = true }.get(this) as Lazy<*>
        check(delegate.isInitialized() && delegate.value === fixtureSaveCoordinator) { "Fixture save coordinator replaced" }
        val context = DurableSaveCoordinator::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.get(fixtureSaveCoordinator)
        check(context === admission.context) { "Fixture coordinator context escaped" }
        val journal = DurableSaveCoordinator::class.java.getDeclaredField("journal")
            .apply { isAccessible = true }.get(fixtureSaveCoordinator)
        val root = DurableSaveJournal::class.java.getDeclaredField("root")
            .apply { isAccessible = true }.get(journal) as File
        check(root == durableSaveJournalRoot(admission.context)) { "Fixture coordinator journal escaped: $root" }
        return root
    }

    override fun onDestroy() {
        try { super.onDestroy() } finally {
            try { if (admission.isolateSaveCoordinator) fixtureSaveJournalRoot() } finally { admission.destroyed.countDown() }
        }
    }
}

internal data class FrameFixtureAdmission(
    val root: File,
    val context: Context,
    val destroyed: CountDownLatch = CountDownLatch(1),
    val factoryCalls: AtomicInteger = AtomicInteger(),
    val handoffFault: FrameHandoffFault? = null,
    val isolateSaveCoordinator: Boolean = false,
    val replacementInput: AtomicReference<FrameReplacementInput?> = AtomicReference(),
)
internal class FrameReplacementInput(val input: PipelineInput) {
    val entered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
}
internal val frameFixtureAdmission = AtomicReference<FrameFixtureAdmission?>()

/** Context normalization must retain fixture ownership, including deferred incident/tile writes. */
internal class FrameFixtureContext(
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


private enum class PipelineCase {
    FOREGROUND, BACKGROUND, DESTINATION, OVERFLOW, STOP, ZERO, BLOCKED_WRITER, EXHAUSTED,
}

/** All cases use the same startQueuedCapture/coordinator/native-read/writer path as production. */
internal fun verifyCaptureFrameIdentity(context: Context, report: StringBuilder) {
    for (case in PipelineCase.entries) {
        withFramePipeline(context, "pipeline_${case.name.lowercase()}") { fixture ->
            val slot = if (case == PipelineCase.DESTINATION || case == PipelineCase.OVERFLOW)
                ReverbService.BufferSlot.ONE_SHOT else ReverbService.BufferSlot.LOOPING
            val firstIds = if (case == PipelineCase.ZERO) emptyList() else (1..512).toList()
            val first = pcmFrameIds(firstIds)
            val streaming = case == PipelineCase.BLOCKED_WRITER || case == PipelineCase.EXHAUSTED
            val delivered = ByteArrayOutputStream()
            var nextId = 1
            val input = PipelineInput(holdFirst = !streaming,
                automaticReads = if (case == PipelineCase.EXHAUSTED) 1000 else if (streaming || case == PipelineCase.DESTINATION) 2 else 1,
                payload = { ordinal, requested ->
                    val bytes = when {
                        ordinal == 1 -> first
                        (case == PipelineCase.BLOCKED_WRITER || case == PipelineCase.DESTINATION) && ordinal == 2 -> pcmFrameIds((513..1024).toList())
                        case == PipelineCase.EXHAUSTED -> ByteBuffer.allocate(requested).order(ByteOrder.LITTLE_ENDIAN).apply {
                            repeat(requested / 2) { putShort((++nextId).toShort()) }
                        }.array()
                        else -> ByteArray(0)
                    }
                    synchronized(delivered) { check(delivered.size() + bytes.size <= 256_000); delivered.write(bytes) }
                    bytes
                })
            val barrierEntered = CountDownLatch(1)
            val releaseBarrier = CountDownLatch(1)
            var nativeReadsWhileWriterBlocked = 0
            var packetsWhileWriterBlocked = 0
            fixture.configure(input, slot, oneShotBytes = if (case == PipelineCase.OVERFLOW) 512L else 1_048_576L,
                foreground = case == PipelineCase.FOREGROUND)
            if (streaming) fixture.onAudio {
                fixture.injectFirstAppendBarrier(barrierEntered, releaseBarrier)
            }
            try {
                fixture.start()
                check(input.firstEntered.await(5, TimeUnit.SECONDS))
                when (case) {
                    PipelineCase.DESTINATION -> check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING).accepted)
                    PipelineCase.STOP -> check(fixture.service.disableListening().accepted)
                    else -> Unit
                }
                input.allowFirst.countDown()
                if (streaming) {
                    check(barrierEntered.await(5, TimeUnit.SECONDS)) { "Actual writer append barrier not reached" }
                    if (case == PipelineCase.BLOCKED_WRITER) {
                        check(input.thirdEntered.await(5, TimeUnit.SECONDS)) {
                            "Native reader did not complete a second batch while writer append was blocked"
                        }
                        nativeReadsWhileWriterBlocked = input.reads.get()
                        packetsWhileWriterBlocked = fixture.io.currentInput()!!.storage.ring.status().occupiedPackets
                        check(nativeReadsWhileWriterBlocked == 3 && packetsWhileWriterBlocked >= 2)
                    } else {
                        fixture.awaitCondition { fixture.io.currentInput()?.storage?.ring?.status()?.terminal ==
                            CapturePcmRing.Terminal.EXHAUSTED }
                    }
                }
            } finally { releaseBarrier.countDown(); input.allowFirst.countDown() }
            if (case == PipelineCase.DESTINATION) check(input.thirdEntered.await(5, TimeUnit.SECONDS))
            if (case == PipelineCase.STOP || case == PipelineCase.EXHAUSTED) {
                input.unblockAll()
                check(input.released.await(10, TimeUnit.SECONDS)) { "Terminal input was not released" }
                if (case == PipelineCase.EXHAUSTED) {
                    check(fixture.service.frameField("persistenceFailureBlocked") == true)
                    check(fixture.service.frameField("state") != ReverbService.STATE_LISTENING)
                }
            } else fixture.fence { fixture.service.frameInvoke("finishSilenceGate") }
            val actual = fixture.onAudio {
                fixture.loop.checkpoint(); fixture.one.checkpoint()
                fixture.loop.pipelinePcm() to fixture.one.pipelinePcm()
            }
            val allDelivered = synchronized(delivered) { delivered.toByteArray() }
            val expectedLoop = when (case) {
                PipelineCase.STOP, PipelineCase.ZERO -> ByteArray(0)
                PipelineCase.DESTINATION -> pcmFrameIds((513..1024).toList())
                PipelineCase.OVERFLOW -> first.copyOfRange(512, first.size)
                PipelineCase.BLOCKED_WRITER, PipelineCase.EXHAUSTED -> allDelivered
                else -> first
            }
            val expectedOne = when (case) {
                PipelineCase.DESTINATION -> first
                PipelineCase.OVERFLOW -> first.copyOfRange(0, 512)
                else -> ByteArray(0)
            }
            File(fixture.root, "loop-output.pcm16le").writeBytes(actual.first)
            File(fixture.root, "one-shot-output.pcm16le").writeBytes(actual.second)
            File(fixture.root, "expected-loop.pcm16le").writeBytes(expectedLoop)
            File(fixture.root, "expected-one-shot.pcm16le").writeBytes(expectedOne)
            check(actual.first.contentEquals(expectedLoop)) { "Looping retained frame identities differ" }
            check(actual.second.contentEquals(expectedOne)) { "One-shot retained frame identities differ" }
            check(input.firstRequested == if (case == PipelineCase.FOREGROUND) 1280 else 32000)
            File(fixture.root, "observations.txt").writeText(
                "path=production_coordinator\nnativeSource=synthetic\n" +
                    "readCalls=${input.reads.get()}\ndeliveredBytes=${allDelivered.size}\n" +
                    "retainedBytes=${actual.first.size + actual.second.size}\n" +
                    "nativeReadsWhileWriterBlocked=$nativeReadsWhileWriterBlocked\n" +
                    "packetsWhileWriterBlocked=$packetsWhileWriterBlocked\n" +
                    "blockedWriterSecondRead=${case == PipelineCase.BLOCKED_WRITER}\n" +
                    "explicitBacklogInterruption=${case == PipelineCase.EXHAUSTED}\n")
        }
        report.append("PASS capture_frame_identity_").append(case.name.lowercase()).append('\n')
    }
    verifyBlockedWriterStopPrefix(context, report)
    verifyStopSealFailureEvidence(context, report)
    verifyAutomaticStopSealFailureEvidence(context, report)
    verifyForcedTeardownPrefix(context, report)
    verifyBlockedWriterNewStart(context, report)
    verifyCoordinatorDispatchRejection(report)
    verifyCoordinatorTerminalAdmission(report)
    verifyCapturePipelineBoundaries(context, report)
}

/** Invokes the actual timeout-release path directly; this is not a wall-clock timeout test. */
private fun verifyForcedTeardownPrefix(context: Context, report: StringBuilder) {
    withFramePipeline(context, "pipeline_forced_teardown_prefix") { fixture ->
        val input = PipelineInput(holdFirst = false, automaticReads = 4, holdReadOrdinal = 4) { ordinal, _ ->
            pcmFrameIds(((ordinal - 1) * 512 + 1..ordinal * 512).toList())
        }
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val nativeReturnPending = CountDownLatch(1)
        val acknowledgeNativeReturn = CountDownLatch(1)
        input.beforeReadReturn = { ordinal ->
            if (ordinal == 4) {
                nativeReturnPending.countDown()
                check(acknowledgeNativeReturn.await(10, TimeUnit.SECONDS)) { "Forced native return was not acknowledged" }
            }
        }
        fixture.configure(input, ReverbService.BufferSlot.ONE_SHOT, oneShotBytes = 1024L)
        fixture.onAudio {
            // Establish isolated fixture intent matching its already-admitted synthetic input.
            // The transition under test is generic teardown/forced release, not capture Start.
            check(getRecorderPreferences(fixture.admission.context).edit()
                .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true)
                .putInt(PrefKey.CAPTURE_BUFFER_SLOT, ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt()).commit())
            fixture.injectFirstAppendBarrier(appendEntered, releaseAppend, fixture.one)
        }
        try {
            fixture.start()
            check(appendEntered.await(5, TimeUnit.SECONDS) && input.heldReadEntered.await(5, TimeUnit.SECONDS))
            val owner = checkNotNull(fixture.io.currentInput())
            val releaseGeneration = fixture.service.frameField("audioRecordReleaseGeneration") as AtomicLong
            val beforeReleaseGeneration = releaseGeneration.get()
            check(owner.storage.ring.status().occupiedPackets == 4 && input.reads.get() == 4)
            fixture.unbind()
            fixture.awaitCondition { fixture.service.frameField("serviceDestroying") == true }
            fixture.service.frameInvoke("forceReleaseAudioRecordAfterShutdownTimeout")
            check(nativeReturnPending.await(5, TimeUnit.SECONDS))
            check(input.releases.get() == 1 && fixture.service.frameField("audioRecord") == null)
            check(releaseGeneration.get() > beforeReleaseGeneration)
            check(fixture.io.currentInput() === owner && owner.storage.ring.status().reading)
            check(owner.storage.ring.status().occupiedPackets == 4 && releaseAppend.count == 1L) {
                "Forced native release recycled a lease before its admitted read acknowledged return"
            }
            acknowledgeNativeReturn.countDown()
            fixture.awaitCondition { !owner.storage.ring.status().reading }
            val afterAck = owner.storage.ring.status()
            check(afterAck.terminal == CapturePcmRing.Terminal.STOP && afterAck.occupiedPackets == 3)
            check(input.reads.get() == 4)
            releaseAppend.countDown()
            fixture.awaitDestroyed()
            check(input.releases.get() == 1 && input.reads.get() == 4 && fixture.admission.factoryCalls.get() == 0)
            check(!(fixture.service.frameField("captureCommandAdmissionFailed") as java.util.concurrent.atomic.AtomicBoolean).get())
            check(fixture.service.frameField("persistenceFailureBlocked") == false)
            val mailbox = CaptureIoCoordinator::class.java.getDeclaredField("commands").apply { isAccessible = true }
                .get(fixture.io) as CaptureControlMailbox<*>
            check(!mailbox.hasFailed()) { "Normal forced teardown produced a writer-dispatch failure" }
            val prefs = getRecorderPreferences(fixture.admission.context)
            check(prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false))
            check(prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt())
            val loop = PersistentAudioChunkStore(fixture.service, legacyCacheFolderName = null)
            val one = PersistentAudioChunkStore(fixture.service, cacheFolderName = ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
                legacyCacheFolderName = null, overwriteOldest = false)
            val actual = try {
                val deadline = System.nanoTime() + 5_000_000_000L
                PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(loop, one)) { System.nanoTime() >= deadline }
                loop.pipelinePcm() to one.pipelinePcm()
            } finally { try { one.close() } finally { loop.close() } }
            val expectedOne = pcmFrameIds((1..512).toList())
            val expectedLoop = pcmFrameIds((513..1536).toList())
            check(actual.first.contentEquals(expectedLoop) && actual.second.contentEquals(expectedOne)) {
                "Forced release discarded the completed prefix or retained the revoked in-flight read"
            }
            File(fixture.root, "loop-output.pcm16le").writeBytes(actual.first)
            File(fixture.root, "one-shot-output.pcm16le").writeBytes(actual.second)
            File(fixture.root, "expected-loop.pcm16le").writeBytes(expectedLoop)
            File(fixture.root, "expected-one-shot.pcm16le").writeBytes(expectedOne)
            File(fixture.root, "observations.txt").writeText(
                "path=production_coordinator\nnativeSource=synthetic\n" +
                    "forcedRelease=actual_private_timeout_release_method\nwallClockTimeout=NOT_TESTED\n" +
                    "completedPrefixReads=3\nrevokedInFlightOrdinal=4\nleaseHeldUntilNativeAck=true\n" +
                    "packetsAfterNativeAck=${afterAck.occupiedPackets}\nreleaseAttempts=${input.releases.get()}\n" +
                    "durableListening=true\ndurableDestination=ONE_SHOT\nwriterDispatchFailure=false\n")
        } finally {
            acknowledgeNativeReturn.countDown(); input.unblockAll(); releaseAppend.countDown()
        }
    }
    report.append("PASS capture_frame_identity_forced_teardown_prefix actual forced-release path; wall-clock timeout untested\n")
}

private fun verifyBlockedWriterStopPrefix(context: Context, report: StringBuilder) {
    for (teardownOverflow in listOf(false, true)) {
        val token = if (teardownOverflow) "blocked_writer_stop_teardown_overflow" else "blocked_writer_stop_prefix"
        withFramePipeline(context, "pipeline_$token") { fixture ->
            val completed = pcmFrameIds((1..1536).toList())
            val expectedOne = if (teardownOverflow) completed.copyOfRange(0, 1024) else ByteArray(0)
            val expectedLoop = if (teardownOverflow) completed.copyOfRange(1024, completed.size) else completed
            val rejectedInFlight = pcmFrameIds((1537..2048).toList())
            val input = PipelineInput(holdFirst = false, automaticReads = 4, holdReadOrdinal = 4) { ordinal, _ ->
                pcmFrameIds(((ordinal - 1) * 512 + 1..ordinal * 512).toList())
            }
            val sealedAtRelease = java.util.concurrent.atomic.AtomicBoolean()
            input.beforeRelease = {
                val field = PersistentAudioChunkStore::class.java.getDeclaredField("activeRecord").apply { isAccessible = true }
                sealedAtRelease.set(field.get(fixture.loop) == null && field.get(fixture.one) == null)
            }
            val appendEntered = CountDownLatch(1)
            val releaseAppend = CountDownLatch(1)
            val initialSlot = if (teardownOverflow) ReverbService.BufferSlot.ONE_SHOT else ReverbService.BufferSlot.LOOPING
            fixture.configure(input, initialSlot, oneShotBytes = if (teardownOverflow) 1024L else 1_048_576L)
            val prefs = getRecorderPreferences(fixture.admission.context)
            check(prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false) &&
                prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == initialSlot.storageCode.toInt()) {
                "Synthetic running owner did not begin with its matching durable intent"
            }
            fixture.onAudio {
                fixture.injectFirstAppendBarrier(appendEntered, releaseAppend, if (teardownOverflow) fixture.one else fixture.loop)
            }
            try {
                fixture.start()
                check(appendEntered.await(5, TimeUnit.SECONDS)) { "Actual first append was not blocked" }
                check(input.heldReadEntered.await(5, TimeUnit.SECONDS)) { "Three completed reads did not reach the queued prefix" }
                val owner = checkNotNull(fixture.io.currentInput())
                val beforeStop = owner.storage.ring.status()
                check(input.reads.get() == 4 && beforeStop.reading && beforeStop.occupiedPackets == 4)
                check(fixture.service.disableListening().accepted) { "Actual Stop was rejected" }
                check(!prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true) &&
                    prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == initialSlot.storageCode.toInt()) {
                    "Stop did not preserve the accepted destination before queued PCM drained"
                }
                val stoppedBoundary = if (!teardownOverflow) captureStoppedMarkerBoundary(fixture) else null
                if (teardownOverflow) {
                    fixture.unbind()
                    fixture.awaitCondition { fixture.service.frameField("serviceDestroying") == true }
                }
                input.allowHeldRead.countDown()
                fixture.awaitCondition { !fixture.io.inputIsReading() }
                val afterNativeAck = owner.storage.ring.status()
                check(afterNativeAck.terminal == CapturePcmRing.Terminal.STOP)
                check(afterNativeAck.occupiedPackets == 3 && input.reads.get() == 4) {
                    "Stop did not preserve exactly the returned prefix while revoking the in-flight read"
                }
                check(input.releases.get() == 0) { "Stop released input before the blocked writer drained" }
                releaseAppend.countDown()
                check(input.released.await(10, TimeUnit.SECONDS)) { "Stop did not release the drained input" }
                check(sealedAtRelease.get()) { "Actual Stop/teardown released input before sealing the completed prefix" }
                val actual = if (teardownOverflow) {
                    fixture.awaitDestroyed()
                    val loop = PersistentAudioChunkStore(fixture.service, legacyCacheFolderName = null)
                    val one = PersistentAudioChunkStore(fixture.service, cacheFolderName = ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
                        legacyCacheFolderName = null, overwriteOldest = false)
                    try {
                        val deadline = System.nanoTime() + 5_000_000_000L
                        PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(loop, one)) { System.nanoTime() >= deadline }
                        loop.pipelinePcm() to one.pipelinePcm()
                    } finally { try { one.close() } finally { loop.close() } }
                } else fixture.onAudio {
                    check(fixture.io.currentInput() == null && fixture.service.frameField("state") == ReverbService.STATE_READY)
                    fixture.loop.pipelinePcm() to fixture.one.pipelinePcm()
                }
                check(actual.first.contentEquals(expectedLoop) && actual.second.contentEquals(expectedOne)) {
                    "Stop lost completed PCM, misrouted One-shot overflow, or retained the revoked in-flight batch"
                }
                check(!getRecorderPreferences(fixture.admission.context).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
                if (teardownOverflow) check(getRecorderPreferences(fixture.admission.context)
                    .getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt()) {
                    "Old queued overflow replaced the newer durable Stop destination"
                }
                check(input.reads.get() == 4 && input.releases.get() == 1)
                if (!teardownOverflow) {
                    check(!RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context).armed)
                    check(RecordingIncidentStore.readIncidents(fixture.admission.context).isEmpty()) {
                        "Successful drain/seal produced a false interruption"
                    }
                    recoverCopiedStoppedMarker(fixture, checkNotNull(stoppedBoundary))
                }
                File(fixture.root, "loop-output.pcm16le").writeBytes(actual.first)
                File(fixture.root, "one-shot-output.pcm16le").writeBytes(actual.second)
                File(fixture.root, "expected-loop.pcm16le").writeBytes(expectedLoop)
                File(fixture.root, "expected-one-shot.pcm16le").writeBytes(expectedOne)
                File(fixture.root, "excluded-in-flight.pcm16le").writeBytes(rejectedInFlight)
                File(fixture.root, "observations.txt").writeText(
                    "path=production_coordinator\nnativeSource=synthetic\n" +
                        "completedReadsBeforeStop=3\nheldReadOrdinal=4\nfinalReadCalls=${input.reads.get()}\n" +
                        "packetsBeforeStop=${beforeStop.occupiedPackets}\n" +
                        "packetsAfterNativeAck=${afterNativeAck.occupiedPackets}\n" +
                        "retainedBytes=${actual.first.size + actual.second.size}\nexcludedInFlightBytes=${rejectedInFlight.size}\n" +
                        "sealedBeforeInputRelease=true\ninputReleasedAfterWriterDrain=true\n" +
                        "genericTeardown=$teardownOverflow\ndurableListening=false\n" +
                        "durableDestination=${if (teardownOverflow) "ONE_SHOT" else "LOOPING"}\n")
            } finally { input.allowHeldRead.countDown(); releaseAppend.countDown() }
        }
        report.append("PASS capture_frame_identity_").append(token).append('\n')
    }
}

private data class StoppedMarkerBoundary(
    val marker: ActiveRecordingSessionMarker, val bytes: ByteArray, val destination: Int,
)

private fun captureStoppedMarkerBoundary(fixture: FramePipelineFixture): StoppedMarkerBoundary {
    val prefs = getRecorderPreferences(fixture.admission.context)
    check(!prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
    val token = RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context)
    check(token.armed) { "Accepted Stop retired the armed marker before its queued PCM was sealed" }
    val marker = checkNotNull(token.marker)
    val file = File(fixture.root, "recording-session.bin")
    check(file.length() in 1..1024)
    val bytes = file.readBytes()
    java.io.DataInputStream(bytes.inputStream()).use { input ->
        check(RecordingIncidentStore.readSessionPayload(input) == marker && input.available() == 0)
    }
    return StoppedMarkerBoundary(marker, bytes, prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1))
}

/** A copied prior-process identity tests recovery, without killing a process or recovering volatile PCM. */
private fun recoverCopiedStoppedMarker(fixture: FramePipelineFixture, boundary: StoppedMarkerBoundary) {
    File(fixture.root, "original-preseal-recording-session.bin").writeBytes(boundary.bytes)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(boundary.bytes)
        .joinToString("") { "%02x".format(it) }
    val recoveryRoot = Files.createTempDirectory(fixture.root.toPath(), "simulated-prior-process-").toFile()
    val recovery = createFrameFixtureAdmission(fixture.context, recoveryRoot,
        initialSlot = checkNotNull(ReverbService.BufferSlot.fromStorageCode(boundary.destination)))
    val simulated = boundary.marker.copy(pid = 1_000_000_000)
    check(simulated.pid != android.os.Process.myPid())
    val writeSession = RecordingIncidentStore::class.java.getDeclaredMethod("writeSession",
        android.util.AtomicFile::class.java, ActiveRecordingSessionMarker::class.java).apply { isAccessible = true }
    synchronized(RecordingIncidentStore) {
        writeSession.invoke(RecordingIncidentStore, android.util.AtomicFile(File(recoveryRoot, "recording-session.bin")), simulated)
    }
    check(!getRecorderPreferences(recovery.context).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
    RecordingIncidentStore.recoverPriorSessionIfNeeded(recovery.context)
    val incidents = RecordingIncidentStore.readIncidents(recovery.context)
    val recovered = incidents.single { it.pid == simulated.pid && it.captureArmedAtMillis == simulated.armedAtMillis }
    check(recovered.kind == RecordingIncidentKind.UNEXPECTED_SHUTDOWN && !recovered.deleted)
    File(fixture.root, "preseal-recovery-boundary.json").writeText(org.json.JSONObject()
        .put("original_marker_sha256", digest).put("durable_listening_at_boundary", false)
        .put("durable_destination_at_boundary", boundary.destination).put("original_marker_armed", true)
        .put("simulation", "only copied prior-process PID changed")
        .put("original_pid", boundary.marker.pid).put("simulated_pid", simulated.pid)
        .put("capture_armed_at_millis", boundary.marker.armedAtMillis)
        .put("actual_process_kill", false).put("actual_exit_evidence", false)
        .put("volatile_pcm_recovery_claim", false).put("recovery_incident_visible", true)
        .put("physical_kill_followup_requires_fresh_held_session", true)
        .put("recovery_root", recoveryRoot.relativeTo(fixture.root).path).toString(2))
}

private fun verifyAutomaticStopSealFailureEvidence(context: Context, report: StringBuilder) {
    withFramePipeline(context, "pipeline_automatic_stop_seal_failure") { fixture ->
        val input = PipelineInput(holdFirst = true, automaticReads = 1) { _, _ -> pcmFrameIds((1..1024).toList()) }
        val sealFailure = java.util.concurrent.atomic.AtomicBoolean()
        val retainedBytesAtSealFailure = java.util.concurrent.atomic.AtomicLong(-1L)
        val retainedBytesField = PersistentAudioChunkStore::class.java.getDeclaredField("retainedPayloadBytes")
            .apply { isAccessible = true }
        val syncField = PersistentAudioChunkStore::class.java.getDeclaredField("mutableSlotSync").apply { isAccessible = true }
        lateinit var original: (java.io.FileDescriptor) -> Unit
        // A non-divisible retention size leaves an active tail for the actual Stop seal. A
        // power-of-two size can finalize every chunk inside append and bypass this fault entirely.
        fixture.configure(input, ReverbService.BufferSlot.ONE_SHOT, oneShotBytes = 1030L)
        val marker = checkNotNull(RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context).marker)
        check(marker.armed)
        fixture.onAudio {
            fixture.service.frameSet("loopingBufferEnabled", false)
            check(getRecorderPreferences(fixture.admission.context).edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true).commit())
            @Suppress("UNCHECKED_CAST")
            val saved = syncField.get(fixture.one) as (java.io.FileDescriptor) -> Unit
            original = saved
            syncField.set(fixture.one, { descriptor: java.io.FileDescriptor ->
                val stack = Thread.currentThread().stackTrace.map { it.methodName }
                if ("sealActiveChunk" in stack && "appendCapturedAt" !in stack &&
                    sealFailure.compareAndSet(false, true)) {
                    // The store monitor is held here. Capture accepted geometry before poisoning;
                    // normal store queries deliberately reject the uncertain finalization afterward.
                    retainedBytesAtSealFailure.set(retainedBytesField.getLong(fixture.one))
                    throw java.io.IOException("qa-automatic-stop-seal-mutable-slot-sync")
                }
                saved(descriptor)
            })
        }
        try {
            fixture.start()
            check(input.firstEntered.await(5, TimeUnit.SECONDS))
            input.allowFirst.countDown()
            fixture.awaitCondition {
                getRecorderPreferences(fixture.admission.context).all[PrefKey.AUDIO_MEMORY_ENABLED.name] == false
            }
            input.unblockAll()
            check(input.released.await(10, TimeUnit.SECONDS))
            fixture.onAudio { }
            check(sealFailure.get()) { "Automatic full-buffer Stop did not reach its own post-append seal failure" }
            check(fixture.service.frameField("serviceDestroying") == false) { "Generic teardown must not supply the tested incident" }
            check(fixture.service.frameField("state") == ReverbService.STATE_PAUSED)
            check(retainedBytesAtSealFailure.get() == 1030L)
            val matching = RecordingIncidentStore.readIncidents(fixture.admission.context)
                .filter { !it.deleted && it.pid == marker.pid && it.captureArmedAtMillis == marker.armedAtMillis }
            check(matching.isNotEmpty()) {
                "Automatic Stop seal failure remained UI feedback/armed state without a visible interruption incident"
            }
            check(matching.all { it.processStartedAtMillis == recordingSessionProcessStartedAtMillis(marker) })
            val armed = RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context).armed
            File(fixture.root, "observations.txt").writeText(
                "path=production_coordinator\nautomaticFullStop=true\nloopingEnabled=false\nretainedBytes=1030\n" +
                    "failureBoundary=sealActiveChunk_after_append\ngeometryWitness=retainedPayloadBytes_before_injected_sync_failure\nserviceAliveAtHistoryRead=true\n" +
                    "markerPid=${marker.pid}\nmarkerProcessBirth=${marker.processStartElapsedRealtimeMillis}\n" +
                    "captureArmedAtMillis=${marker.armedAtMillis}\nmarkerArmedAfterFailure=$armed\n" +
                    "matchingVisibleInterruptionIncidents=${matching.size}\n")
        } finally {
            input.unblockAll()
            fixture.onAudio { syncField.set(fixture.one, original) }
        }
    }
    report.append("PASS capture_automatic_stop_seal_failure_visible_while_service_alive\n")
}

private fun verifyStopSealFailureEvidence(context: Context, report: StringBuilder) {
    withFramePipeline(context, "pipeline_stop_seal_failure_evidence") { fixture ->
        val input = PipelineInput(holdFirst = false, automaticReads = 3, holdReadOrdinal = 3) { ordinal, _ ->
            pcmFrameIds(((ordinal - 1) * 256 + 1..ordinal * 256).toList())
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sealFailure = java.util.concurrent.atomic.AtomicBoolean()
        fixture.configure(input, ReverbService.BufferSlot.LOOPING)
        val field = PersistentAudioChunkStore::class.java.getDeclaredField("mutableSlotSync").apply { isAccessible = true }
        lateinit var original: (java.io.FileDescriptor) -> Unit
        fixture.onAudio {
            fixture.injectFirstAppendBarrier(entered, release)
            @Suppress("UNCHECKED_CAST") val saved = field.get(fixture.loop) as (java.io.FileDescriptor) -> Unit
            original = saved
            field.set(fixture.loop, { descriptor: java.io.FileDescriptor ->
                val stack = Thread.currentThread().stackTrace.map { it.methodName }
                if ("sealActiveChunk" in stack && "appendCapturedAt" !in stack &&
                    sealFailure.compareAndSet(false, true)) throw java.io.IOException("qa-stop-seal-mutable-slot-sync")
                original(descriptor)
            })
        }
        try {
            fixture.start()
            check(entered.await(5, TimeUnit.SECONDS) && input.heldReadEntered.await(5, TimeUnit.SECONDS))
            check(fixture.service.disableListening().accepted)
            val boundary = captureStoppedMarkerBoundary(fixture)
            input.allowHeldRead.countDown()
            release.countDown()
            check(input.released.await(10, TimeUnit.SECONDS))
            fixture.onAudio { }
            check(sealFailure.get()) { "Actual Stop seal did not reach the injected durability failure" }
            val armed = RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context).armed
            val matching = RecordingIncidentStore.readIncidents(fixture.admission.context)
                .filter { it.pid == boundary.marker.pid && it.captureArmedAtMillis == boundary.marker.armedAtMillis }
            check(armed || matching.isNotEmpty()) { "Failed Stop seal silently retired all interruption evidence" }
            File(fixture.root, "observations.txt").writeText(
                "path=production_coordinator\nsealFailureInjected=true\nmarkerArmedAfterSealFailure=$armed\n" +
                    "matchingInterruptionIncidents=${matching.size}\ncleanStopClaim=false\n")
        } finally {
            input.unblockAll()
            release.countDown()
            fixture.onAudio { field.set(fixture.loop, original) }
        }
    }
    report.append("PASS capture_stop_seal_failure_retains_interruption_evidence\n")
}

/** The public variant pauses after a packet, where store preflight can run without waiting on append. */
private fun verifyBlockedWriterNewStart(context: Context, report: StringBuilder) {
    for (publicApi in listOf(false, true)) {
        val token = if (publicApi) "new_start_public_api" else "new_start_private_transaction"
        withFramePipeline(context, "pipeline_blocked_writer_$token") { fixture ->
            val oldInput = PipelineInput(holdFirst = false, automaticReads = 4, holdReadOrdinal = 4) { ordinal, _ ->
                pcmFrameIds(((ordinal - 1) * 512 + 1..ordinal * 512).toList())
            }
            val newInput = PipelineInput(holdFirst = true, automaticReads = 1, allowSyntheticStart = true) { _, _ -> ByteArray(0) }
            val replacement = FrameReplacementInput(newInput)
            val appendEntered = CountDownLatch(1)
            val releaseAppend = CountDownLatch(1)
            val oneShotFrames = if (publicApi) 1024 else 512
            fixture.configure(oldInput, ReverbService.BufferSlot.ONE_SHOT, oneShotBytes = oneShotFrames * 2L, foreground = true)
            fixture.onAudio {
                if (publicApi) {
                    val coordinator = fixture.io
                    val executionLock = CaptureIoCoordinator::class.java.getDeclaredField("writerExecutionLock")
                        .apply { isAccessible = true }.get(coordinator)
                    val dispatchField = CaptureIoCoordinator::class.java.getDeclaredField("dispatchWriter").apply { isAccessible = true }
                    val gated = java.util.concurrent.atomic.AtomicBoolean()
                    val dispatch: (Runnable) -> Boolean = { task -> fixture.handler.post {
                        task.run()
                        if (coordinator.currentInput()?.processedFrames == 512L && gated.compareAndSet(false, true)) {
                            check(!Thread.holdsLock(executionLock) && !Thread.holdsLock(fixture.one))
                            appendEntered.countDown()
                            check(releaseAppend.await(15, TimeUnit.SECONDS)) { "Public API fixture writer gate was not released" }
                        }
                    } }
                    dispatchField.set(coordinator, dispatch)
                    check(dispatchField.get(coordinator) === dispatch) { "Actual coordinator dispatch injection did not bind" }
                } else fixture.injectFirstAppendBarrier(appendEntered, releaseAppend, fixture.one)
            }
            check(fixture.admission.replacementInput.compareAndSet(null, replacement))
            try {
                fixture.start()
                check(appendEntered.await(5, TimeUnit.SECONDS) && oldInput.heldReadEntered.await(5, TimeUnit.SECONDS))
                check(oldInput.reads.get() == 4)
                if (publicApi) check(fixture.one.countFilledBytes() == 1024L && !fixture.one.isFull())
                check(fixture.service.disableListening().accepted)
                val start = if (publicApi) fixture.service.enableListening(ReverbService.BufferSlot.ONE_SHOT) else {
                    val startMethod = ReverbService::class.java.getDeclaredMethod("setListeningEnabled",
                        Boolean::class.javaPrimitiveType, ReverbService.BufferSlot::class.java,
                        java.lang.Long::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                    startMethod.invoke(fixture.service, true, ReverbService.BufferSlot.ONE_SHOT, null, null)
                        as ReverbService.ListeningCommandResult
                }
                check(start.accepted) { "Actual durable Start transaction was rejected" }
                val prefs = getRecorderPreferences(fixture.admission.context)
                check(prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false))
                check(prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt())
                oldInput.allowHeldRead.countDown()
                fixture.awaitCondition { !fixture.io.inputIsReading() }
                releaseAppend.countDown()
                check(replacement.entered.await(10, TimeUnit.SECONDS)) {
                    val intentState = synchronized(checkNotNull(fixture.service.frameField("listeningIntentLock"))) {
                        "state=${fixture.service.frameField("state")}, " +
                            "foregroundStartBlocked=${fixture.service.frameField("foregroundStartBlocked")}, " +
                            "persistenceFailureBlocked=${fixture.service.frameField("persistenceFailureBlocked")}, " +
                            "durableListeningIntentEnabled=${fixture.service.frameField("durableListeningIntentEnabled")}, " +
                            "commandGeneration=${fixture.service.frameField("listeningCommandGeneration")}, " +
                            "acceptedStartGeneration=${start.generation}"
                    }
                    "Replacement input factory was not entered within 10 seconds ($token); $intentState; " +
                        "persistedListening=${prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)}; " +
                        "pendingError=${fixture.service.consumePendingError()}"
                }
                check(prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)) { "Old queued PCM changed the newer durable listening intent" }
                // Start reloads durable retention before resolving its target. The fixture's small
                // runtime-only limit may be enlarged, or a still-full One-shot may legitimately fall back.
                val oneShotFullAtFactory = fixture.one.isFull()
                val expectedTarget = checkNotNull(resolveAvailableCaptureBufferSlot(
                    preferred = ReverbService.BufferSlot.ONE_SHOT,
                    oneShotEnabled = fixture.service.frameField("oneShotBufferEnabled") == true,
                    oneShotFull = oneShotFullAtFactory,
                    loopingEnabled = fixture.service.frameField("loopingBufferEnabled") == true))
                check(prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == expectedTarget.storageCode.toInt()) {
                    "Replacement input did not use the newer Start's normally resolved destination"
                }
                check(oldInput.reads.get() == 4 && oldInput.releases.get() == 1)
                replacement.proceed.countDown()
                check(newInput.firstEntered.await(10, TimeUnit.SECONDS))
                check(newInput.starts.get() == 1 && fixture.admission.factoryCalls.get() == 1)
                check(fixture.service.disableListening().accepted)
                newInput.allowFirst.countDown()
                check(newInput.released.await(10, TimeUnit.SECONDS))
                val actual = fixture.onAudio { fixture.loop.pipelinePcm() to fixture.one.pipelinePcm() }
                val expectedLoop = pcmFrameIds((oneShotFrames + 1..1536).toList())
                val expectedOne = pcmFrameIds((1..oneShotFrames).toList())
                check(actual.first.contentEquals(expectedLoop) && actual.second.contentEquals(expectedOne))
                File(fixture.root, "loop-output.pcm16le").writeBytes(actual.first)
                File(fixture.root, "one-shot-output.pcm16le").writeBytes(actual.second)
                File(fixture.root, "expected-loop.pcm16le").writeBytes(expectedLoop)
                File(fixture.root, "expected-one-shot.pcm16le").writeBytes(expectedOne)
                File(fixture.root, "observations.txt").writeText(
                    "path=production_coordinator\nnativeSource=synthetic\n" +
                        "startAcceptance=${if (publicApi) "actual_public_service_api" else "actual_private_durable_transaction"}\n" +
                        "publicFullStoreUiPreflight=${if (publicApi) "EXECUTED" else "BYPASSED"}\n" +
                        "writerGate=${if (publicApi) "after_actual_drain_outside_store_and_execution_locks" else "actual_append_directory_sync"}\n" +
                        "realMicrophoneConstructed=false\nnewerListeningAtFactoryGate=true\nrequestedDestinationWhileWriterBlocked=ONE_SHOT\n" +
                        "resolvedDestinationAtFactoryGate=$expectedTarget\noneShotFullAtFactoryGate=$oneShotFullAtFactory\n" +
                        "completedOldReads=3\nexcludedOldInFlightRead=4\nreplacementStarts=${newInput.starts.get()}\n")
            } finally {
                oldInput.unblockAll(); releaseAppend.countDown(); replacement.proceed.countDown(); newInput.unblockAll()
            }
        }
        report.append("PASS capture_frame_identity_").append(token)
            .append(if (publicApi) " public Service API; synthetic input, UI rendering untested\n" else " private durable transition; public full-store preflight bypassed\n")
    }
}

/** A real coordinator/Handler test: the injected rejection occurs on the native-return wakeup. */
private fun verifyCoordinatorDispatchRejection(report: StringBuilder) {
    val writer = android.os.HandlerThread("qa-capture-dispatch-writer").also { it.start() }
    val handler = Handler(writer.looper)
    val rejectDispatch = java.util.concurrent.atomic.AtomicBoolean()
    val dispatchRejected = java.util.concurrent.atomic.AtomicBoolean()
    val drainObserved = AtomicReference<CountDownLatch?>()
    val rejected = CountDownLatch(2)
    val firstRejections = AtomicInteger()
    val secondRejections = AtomicInteger()
    val workCalls = AtomicInteger()
    val dispatchFailures = AtomicInteger()
    val wrongThread = AtomicReference<String?>()
    val input = PipelineInput(holdFirst = true, automaticReads = 1) { _, _ -> pcmFrameIds(listOf(7, 8)) }
    val storage = CaptureIoCoordinator.Storage(CapturePcmBudget.forFormat(16_000, 1, 2))
    val io = CaptureIoCoordinator(handler,
        onPacket = { _, _, _ -> error("Rejected dispatch must never deliver PCM to this writer") },
        onInputFailure = { _, error -> throw AssertionError("Unexpected native failure", error) },
        onWriterFailure = { error -> throw AssertionError("Unexpected writer failure", error) },
        onDispatchFailure = { dispatchFailures.incrementAndGet() },
        dispatchWriter = { task ->
            if (rejectDispatch.get()) { dispatchRejected.set(true); false } else handler.post {
                try { task.run() } finally { drainObserved.getAndSet(null)?.countDown() }
            }
        },
    )
    fun rejection(counter: AtomicInteger): () -> Unit = {
        val thread = Thread.currentThread().name
        if (thread == "reverb-native-input" || thread == writer.name) wrongThread.set(thread)
        counter.incrementAndGet()
        rejected.countDown()
    }
    fun awaitWriterDrain(post: () -> Unit) {
        val drained = CountDownLatch(1)
        check(drainObserved.compareAndSet(null, drained))
        post()
        check(drained.await(5, TimeUnit.SECONDS)) { "Coordinator did not finish its control-prefix probe" }
    }
    try {
        val install = FutureTask(java.util.concurrent.Callable {
            io.install(input, 1, 1, storage,
                admit = { ring -> ring.reserve(CaptureReadIdentity(1, 1, 1,
                    ReverbService.BufferSlot.LOOPING.storageCode, 16_000, 1, 2,
                    PcmSampleFormat.PCM_16.storageCode.toInt(), System.nanoTime()), 1024) },
                mayComplete = { true }, captureClockRequested = { false })
            io.resumeInput()
        })
        check(handler.post(install)); install.get(5, TimeUnit.SECONDS)
        check(input.firstEntered.await(5, TimeUnit.SECONDS))
        awaitWriterDrain {
            check(io.post(pauseInput = true, rejected = rejection(firstRejections)) {
                workCalls.incrementAndGet()
            } == CaptureControlMailbox.Admission.ACCEPTED)
        }
        // The first command has been claimed and is waiting for the admitted read. The second
        // remains pending behind it; both must settle when the reader's next wake is rejected.
        awaitWriterDrain {
            check(io.post(pauseInput = true, rejected = rejection(secondRejections)) {
                workCalls.incrementAndGet()
            } == CaptureControlMailbox.Admission.ACCEPTED)
        }
        rejectDispatch.set(true)
        input.allowFirst.countDown()
        check(rejected.await(5, TimeUnit.SECONDS)) { "Accepted active/pending commands were stranded" }
        check(firstRejections.get() == 1 && secondRejections.get() == 1)
        check(workCalls.get() == 0 && dispatchFailures.get() == 1 && wrongThread.get() == null)
        check(io.post(pauseInput = true, rejected = { error("Rejected offer transferred ownership") }) {
            error("Closed dispatch accepted work")
        } == CaptureControlMailbox.Admission.DISPATCH_FAILED)
        report.append("PASS capture_coordinator_native_wake_dispatch_rejection\n")
    } finally {
        input.unblockAll()
        io.stopInput(preserveInFlight = false, writerFailed = true)
        val nativeDeadline = System.nanoTime() + 5_000_000_000L
        while (io.inputIsReading()) {
            check(System.nanoTime() < nativeDeadline) { "Synthetic native lease did not acknowledge cleanup" }
            Thread.sleep(1)
        }
        val cleanup = FutureTask(java.util.concurrent.Callable {
            check(!io.inputIsReading())
            check(io.detach(input))
            input.release()
        })
        try {
            if (dispatchRejected.get()) io.afterDispatchFailure { cleanup.run() }
            else check(handler.post(cleanup))
            cleanup.get(10, TimeUnit.SECONDS)
        }
        finally { writer.quitSafely(); writer.join(10_000); check(!writer.isAlive) }
    }
}

private fun verifyCoordinatorTerminalAdmission(report: StringBuilder) {
    val writer = android.os.HandlerThread("qa-capture-terminal-writer").also { it.start() }
    val handler = Handler(writer.looper)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val workCalls = AtomicInteger()
    val rejections = AtomicInteger()
    val reentrant = AtomicReference<CaptureControlMailbox.Admission?>()
    val failure = AtomicReference<Throwable?>()
    val io = CaptureIoCoordinator(handler,
        onPacket = { _, _, _ -> error("No native input exists in this fixture") },
        onInputFailure = { _, error -> failure.set(error) },
        onWriterFailure = { failure.set(it) },
        onDispatchFailure = { failure.set(it) })
    try {
        check(handler.post { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
        check(entered.await(5, TimeUnit.SECONDS))
        check(io.post(pauseInput = true, terminalClose = true, rejected = { rejections.incrementAndGet() }) {
            workCalls.incrementAndGet()
            reentrant.set(io.post(pauseInput = true, terminalClose = true,
                rejected = { rejections.incrementAndGet() }) { workCalls.incrementAndGet() })
            completed.countDown()
        } == CaptureControlMailbox.Admission.ACCEPTED)
        check(io.post(pauseInput = true, terminalClose = true,
            rejected = { rejections.incrementAndGet() }) { workCalls.incrementAndGet() } == CaptureControlMailbox.Admission.CLOSED)
        release.countDown()
        check(completed.await(5, TimeUnit.SECONDS))
        writer.join(10_000)
        check(!writer.isAlive && workCalls.get() == 1 && rejections.get() == 0)
        check(reentrant.get() == CaptureControlMailbox.Admission.CLOSED && failure.get() == null)
        report.append("PASS capture_coordinator_duplicate_reentrant_terminal_closed\n")
    } finally { release.countDown(); writer.quitSafely(); writer.join(10_000); check(!writer.isAlive) }
}

private enum class PipelineTeardownCase { CONTROL, DESTROY, GATED_DESTROY, FAILED_HANDOFF, FAILED_APPEND }

internal fun verifyCaptureTeardownFrameIdentity(context: Context, report: StringBuilder) {
    for (case in PipelineTeardownCase.entries) {
        val fault = if (case == PipelineTeardownCase.FAILED_HANDOFF) FrameHandoffFault() else null
        withFramePipeline(context, "pipeline_teardown_${case.name.lowercase()}", fault) { fixture ->
            val pcm = pcmFrameIds((1..5).toList())
            val input = PipelineInput(holdFirst = true, automaticReads = 1) { ordinal, _ ->
                if (ordinal == 1) pcm else ByteArray(0)
            }
            fixture.configure(input, ReverbService.BufferSlot.ONE_SHOT, oneShotBytes = 4,
                gated = case == PipelineTeardownCase.GATED_DESTROY || case == PipelineTeardownCase.FAILED_HANDOFF)
            if (case == PipelineTeardownCase.FAILED_APPEND) fixture.onAudio {
                fixture.injectAppendFailure("qa-pipeline-append")
            }
            fixture.start()
            check(input.firstEntered.await(5, TimeUnit.SECONDS))
            if (case == PipelineTeardownCase.CONTROL) {
                input.allowFirst.countDown()
                fixture.fence { }
                fixture.stopAndDrain()
                fixture.unbind()
            } else {
                fixture.unbind()
                fixture.awaitCondition { fixture.service.frameField("serviceDestroying") == true }
                input.allowFirst.countDown()
                input.unblockAll()
            }
            fixture.awaitDestroyed()
            check(input.releases.get() == 1 && input.starts.get() == 0)
            val loop = PersistentAudioChunkStore(fixture.service, legacyCacheFolderName = null)
            val one = PersistentAudioChunkStore(fixture.service, cacheFolderName = ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
                legacyCacheFolderName = null, overwriteOldest = false)
            try {
                val deadline = System.nanoTime() + 5_000_000_000L
                PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(loop, one)) { System.nanoTime() >= deadline }
                val actualOne = one.pipelinePcm()
                val actualLoop = loop.pipelinePcm()
                val expectedOne = if (case == PipelineTeardownCase.FAILED_APPEND) ByteArray(0) else pcm.copyOfRange(0, 4)
                val expectedLoop = if (case == PipelineTeardownCase.FAILED_APPEND || case == PipelineTeardownCase.FAILED_HANDOFF)
                    ByteArray(0) else pcm.copyOfRange(4, pcm.size)
                check(actualOne.contentEquals(expectedOne))
                check(actualLoop.contentEquals(expectedLoop))
                File(fixture.root, "reopened-one-shot.pcm16le").writeBytes(actualOne)
                File(fixture.root, "reopened-loop.pcm16le").writeBytes(actualLoop)
                fault?.let { check(it.failed.get() && it.attempts.get() == 1 && it.rollbackCommits.get() == 1) }
            } finally { try { one.close() } finally { loop.close() } }
        }
        report.append("PASS capture_teardown_identity_").append(case.name.lowercase()).append('\n')
    }
}

internal class PipelineInput(
    holdFirst: Boolean,
    private val automaticReads: Int,
    private val holdReadOrdinal: Int? = null,
    private val allowSyntheticStart: Boolean = false,
    private val payload: (Int, Int) -> ByteArray,
) : CaptureAudioInput {
    val reads = AtomicInteger()
    val releases = AtomicInteger()
    val starts = AtomicInteger()
    val firstEntered = CountDownLatch(1)
    val thirdEntered = CountDownLatch(1)
    val allowFirst = CountDownLatch(if (holdFirst) 1 else 0)
    val heldReadEntered = CountDownLatch(1)
    val allowHeldRead = CountDownLatch(1)
    val released = CountDownLatch(1)
    var beforeRelease: (() -> Unit)? = null
    var beforeReadReturn: ((Int) -> Unit)? = null
    private val permits = java.util.concurrent.Semaphore(0)
    private val allReturns = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile var firstRequested = 0
    override val state: Int get() = AudioRecord.STATE_INITIALIZED
    override val recordingState: Int get() = AudioRecord.RECORDSTATE_RECORDING
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        check(readMode == AudioRecord.READ_BLOCKING)
        val ordinal = reads.incrementAndGet()
        check(ordinal <= 1100) { "Synthetic input escaped its finite read budget" }
        if (ordinal == 1) {
            firstRequested = sizeInBytes; firstEntered.countDown()
            check(allowFirst.await(10, TimeUnit.SECONDS))
        }
        if (ordinal == 3) thirdEntered.countDown()
        if (ordinal == holdReadOrdinal) {
            heldReadEntered.countDown()
            check(allowHeldRead.await(10, TimeUnit.SECONDS))
        }
        if (ordinal > automaticReads && !allReturns.get()) check(permits.tryAcquire(10, TimeUnit.SECONDS))
        val bytes = if (ordinal <= automaticReads) payload(ordinal, sizeInBytes) else ByteArray(0)
        check(bytes.size <= sizeInBytes && bytes.size % 2 == 0)
        for (i in bytes.indices) buffer.put(i, bytes[i])
        beforeReadReturn?.invoke(ordinal)
        return bytes.size
    }
    fun permitFenceReturn() { permits.release() }
    fun unblockAll() { allReturns.set(true); allowFirst.countDown(); allowHeldRead.countDown(); permits.release() }
    override fun startRecording() {
        starts.incrementAndGet()
        check(allowSyntheticStart) { "Unexpected start of a preinstalled synthetic input" }
    }
    override fun stop() { unblockAll() }
    override fun release() { check(releases.incrementAndGet() == 1); beforeRelease?.invoke(); released.countDown() }
}

internal class FramePipelineFixture(
    val context: Context, val root: File, val admission: FrameFixtureAdmission,
    val service: CaptureFrameIdentityService, private val connection: ServiceConnection,
) {
    val handler = service.frameField("audioHandler") as Handler
    val io get() = service.frameField("captureIo") as CaptureIoCoordinator
    val loop get() = service.frameField("loopingAudioChunkStore") as PersistentAudioChunkStore
    val one get() = service.frameField("oneShotAudioChunkStore") as PersistentAudioChunkStore
    private var input: PipelineInput? = null
    private var unbound = false
    private val foregroundOwner = Any()
    fun <T> onAudio(work: () -> T): T {
        check(android.os.Looper.myLooper() != handler.looper)
        val future = FutureTask(java.util.concurrent.Callable { work() })
        check(handler.post(future)); return future.get(15, TimeUnit.SECONDS)
    }
    fun configure(source: CaptureAudioInput, slot: ReverbService.BufferSlot,
        oneShotBytes: Long = 1_048_576L, foreground: Boolean = false, gated: Boolean = false) = run {
        awaitCondition { service.frameField("captureStoresReady") == true }
        onAudio {
        check(service.frameField("captureStoresReady") == true && service.frameField("audioRecord") == null)
        loop.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
        one.configure(RetentionMode.SIZE, oneShotBytes, 16_000, 1, PcmSampleFormat.PCM_16)
        service.frameSet("sampleRate", 16_000)
        service.frameSet("channelMode", ChannelMode.MONO)
        service.frameSet("pcmSampleFormat", PcmSampleFormat.PCM_16)
        service.frameSet("silenceGateConfig", SilenceGateConfig(gated,
            listOf(SilencePoint(20, -96), SilencePoint(20_000, -96))))
        service.frameSet("silenceSampleRequest", null)
        service.frameSet("oneShotBufferEnabled", true)
        service.frameSet("loopingBufferEnabled", true)
        // The synthetic running owner must mirror a real accepted durable Start. Stop
        // preserves a valid stored destination rather than repairing fixture-only cache state.
        val prefs = getRecorderPreferences(admission.context)
        check(prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true)
            .putInt(PrefKey.CAPTURE_BUFFER_SLOT, slot.storageCode.toInt()).commit())
        check(prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false) &&
            prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == slot.storageCode.toInt())
        service.frameSet("activeBufferSlot", slot)
        service.frameSet("durableCaptureBufferSlot", slot)
        service.frameSet("durableCaptureIntentAuthorityValid", true)
        service.frameSet("durableListeningIntentEnabled", true)
        service.frameSet("state", ReverbService.STATE_LISTENING)
        service.frameSet("lastDurabilitySyncRequestNanos", System.nanoTime())
        service.frameInvoke("prepareCaptureReadBuffersOnAudioThread")
        (service.frameField("appUiForegroundOwners") as UiForegroundOwnerTracker).update(foregroundOwner, foreground)
        val epoch = (service.frameField("listeningCommandGeneration") as AtomicLong).incrementAndGet()
        service.frameSet("audioRecordGeneration", epoch)
        service.frameSet("audioRecord", source)
        input = source as? PipelineInput
        val arm = ReverbService::class.java.getDeclaredMethod("armCaptureIncidentTrackingOnAudioThread",
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        check(arm.invoke(service, false) == true) { "Actual capture-session arm failed for isolated synthetic input" }
        check(RecordingIncidentStore.beginKnownCaptureStop(admission.context).armed)
        admission.handoffFault?.armed?.set(true)
        }
    }
    fun start() = onAudio { service.frameInvoke("startQueuedCapture") }
    fun fence(work: () -> Unit) {
        val done = FutureTask(java.util.concurrent.Callable { work() })
        check(io.post(pauseInput = true, allowTerminal = true, rejected = { done.cancel(false) }) { done.run() } ==
            CaptureControlMailbox.Admission.ACCEPTED)
        // Acceptance has already reserved the pausing prefix, so this permit cannot feed a later read.
        input?.permitFenceReturn()
        done.get(15, TimeUnit.SECONDS)
    }
    fun stopAndDrain() {
        input?.unblockAll()
        if (service.frameField("serviceDestroying") != true) check(service.disableListening().accepted)
        input?.let { check(it.released.await(15, TimeUnit.SECONDS)) }
    }
    fun unbind() { if (!unbound) { unbound = true; context.unbindService(connection) } }
    fun awaitDestroyed() {
        check(admission.destroyed.await(30, TimeUnit.SECONDS))
        val writer = service.frameField("audioThread") as Thread
        writer.join(30_000); check(!writer.isAlive)
        // Service shutdown remains nonblocking; the instrumentation owner drains accepted workers.
        for (name in listOf("durableSaveAdmissionExecutor", "exportWorkExecutor")) {
            val worker = service.frameField(name) as java.util.concurrent.ExecutorService
            check(worker.awaitTermination(30, TimeUnit.SECONDS)) { "Fixture worker did not drain: $name" }
        }
        if (admission.isolateSaveCoordinator) service.fixtureSaveJournalRoot()
    }
    fun awaitCondition(checkReady: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!checkReady()) { check(System.nanoTime() < deadline); Thread.sleep(1) }
    }
    fun injectFirstAppendBarrier(entered: CountDownLatch, release: CountDownLatch, store: PersistentAudioChunkStore = loop) {
        injectSync(store) { original, directory ->
            entered.countDown(); check(release.await(8, TimeUnit.SECONDS)); original(directory)
        }
    }
    fun injectAppendFailure(token: String) {
        injectSync(one) { _, _ -> throw java.io.IOException(token) }
    }
    private fun injectSync(store: PersistentAudioChunkStore, action: ((File) -> Unit, File) -> Unit) {
        val field = PersistentAudioChunkStore::class.java.getDeclaredField("directorySync").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val original = field.get(store) as (File) -> Unit
        val claimed = java.util.concurrent.atomic.AtomicBoolean()
        field.set(store, { directory: File ->
            if (claimed.compareAndSet(false, true)) {
                val names = Thread.currentThread().stackTrace.map { it.methodName }
                check(names.containsAll(listOf("createActiveChunkLocked", "appendCapturedAt", "consumeQueuedCapture")))
                File(root, "append-barrier-stack.txt").writeText(names.joinToString("\n"))
                action(original, directory)
            } else original(directory)
        })
    }
    fun cleanup() {
        input?.unblockAll()
        try { if (!unbound) stopAndDrain() }
        finally { unbind(); awaitDestroyed() }
    }
}

private fun admitScopedSaveFixture() {
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    // Application.onCreate and older Service main callbacks finish before this fence.
    val mainFence = FutureTask(java.util.concurrent.Callable { Unit })
    check(Handler(android.os.Looper.getMainLooper()).post(mainFence))
    mainFence.get(30, TimeUnit.SECONDS)
    val startup = Thread.getAllStackTraces().filter { (thread, _) ->
        thread.name == "reverb-save-recovery" && thread.isAlive
    }
    check(startup.size <= 1) { "Ambiguous application save-recovery thread" }
    startup.forEach { (thread, stack) ->
        val owner = ReverbApplication::class.java.name
        check(!thread.isAlive || stack.any { it.className == owner || it.className.startsWith(owner + "$" ) }) {
            "Save-recovery thread has no application owner proof"
        }
        thread.join(30_000)
        check(!thread.isAlive) { "Application save recovery did not finish" }
    }
    check(Thread.getAllStackTraces().keys.none { it.name == "reverb-save-recovery" && it.isAlive }) {
        "Application save-recovery thread changed during admission"
    }
    check(DurableSaveJobs.state.value.isEmpty()) {
        "Existing QA save publications require separate evidence/disposition: " +
            DurableSaveJobs.state.value.joinToString { "${it.id}:${it.phase}" }
    }
}

internal fun withFramePipeline(context: Context, token: String, fault: FrameHandoffFault? = null,
    isolateSaveCoordinator: Boolean = false, body: (FramePipelineFixture) -> Unit) {
    check(context.packageName == "app.smallthingz.reverb.reliability")
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    if (isolateSaveCoordinator) admitScopedSaveFixture()
    val parent = File(context.noBackupFilesDir, "capture-pipeline").also { check(it.mkdirs() || it.isDirectory) }
    val root = Files.createTempDirectory(parent.toPath(), "$token-").toFile()
    val admission = createFrameFixtureAdmission(context, root, handoffFault = fault)
        .copy(isolateSaveCoordinator = isolateSaveCoordinator)
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
    var bound = false
    var fixture: FramePipelineFixture? = null
    var primary: Throwable? = null
    var drained = false
    try {
        bound = context.bindService(Intent(context, CaptureFrameIdentityService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound && connected.await(15, TimeUnit.SECONDS))
        fixture = FramePipelineFixture(context, root, admission, checkNotNull(service.get()), connection)
        body(fixture)
    } catch (error: Throwable) { primary = error }
    finally {
        try {
            if (fixture != null) fixture.cleanup()
            else if (bound) { context.unbindService(connection); check(admission.destroyed.await(30, TimeUnit.SECONDS)) }
            drained = true
        } catch (error: Throwable) { if (primary != null) primary.addSuppressed(error) else primary = error }
        if (drained) check(frameFixtureAdmission.compareAndSet(admission, null))
        File(root, "result.txt").writeText(if (primary == null) "PASS $token\npath=production_coordinator\nnativeFactoryCalls=${admission.factoryCalls.get()}\n"
            else "FAIL $token\n${primary.stackTraceToString()}")
    }
    primary?.let { throw it }
}

internal fun ReverbService.frameField(name: String): Any? =
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)
internal fun ReverbService.frameSet(name: String, value: Any?) {
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}
internal fun ReverbService.frameInvoke(name: String) {
    ReverbService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
}
private fun pcmFrameIds(ids: List<Int>): ByteArray = ByteBuffer.allocate(ids.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    .apply { ids.forEach { putShort(it.toShort()) } }.array()
internal fun PersistentAudioChunkStore.pipelinePcm(): ByteArray {
    if (!hasData()) return ByteArray(0)
    val output = ByteArrayOutputStream()
    requireNotNull(acquireRange(0.0, durationSeconds())).use { lease ->
        lease.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
            output.write(bytes, offset, count); count
        }
    }
    return output.toByteArray()
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
