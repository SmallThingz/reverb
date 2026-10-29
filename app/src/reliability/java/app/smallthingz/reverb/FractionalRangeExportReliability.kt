package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real snapshot-to-WAV export, using synthetic PCM and the isolated QA Service only. */
internal fun verifyFractionalRangeExport(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
) {
    check(context.packageName == "app.smallthingz.reverb.reliability")
    check(Looper.myLooper() != Looper.getMainLooper()) { "QA waits must stay off main" }
    val prefs = getRecorderPreferences(context)
    check(prefs.edit()
        .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
        .putInt(PrefKey.OUTPUT_FORMAT, ExportFormat.WAV.storageCode.toInt())
        .putInt(PrefKey.OUTPUT_CODEC, ExportCodec.PCM_16.storageCode.toInt())
        .putInt(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.storageCode.toInt())
        .putInt(PrefKey.SAMPLE_RATE, 16_000)
        .putInt(PrefKey.CHANNEL_MODE, ChannelMode.MONO.storageCode.toInt())
        .commit())
    check(!readDurableCaptureIntentPreferences(prefs).enabled)
    val connected = CountDownLatch(1)
    val service = AtomicReference<ReverbService>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service.set((binder as ReverbService.BackgroundRecorderBinder).service)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    val ownedRoots = ArrayList<FractionalFixtureEntry>()
    fun newSourceRoot(prefix: String): File {
        val path = Files.createTempDirectory(context.filesDir.toPath(), prefix)
        val identity = Os.lstat(path.toString())
        check(OsConstants.S_ISDIR(identity.st_mode))
        ownedRoots += FractionalFixtureEntry(path, identity)
        return path.toFile()
    }
    val root = newSourceRoot("fractional-range-source-")
    var ordinaryRoot: File? = null
    var bound = false
    var store: PersistentAudioChunkStore? = null
    var ordinaryStore: PersistentAudioChunkStore? = null
    var executor: ExecutorService? = null
    var primaryFailure: Throwable? = null
    val main = Handler(Looper.getMainLooper())
    fun awaitMainCallbacks() {
        val fence = FutureTask { Unit }
        check(main.post(fence))
        fence.get(15, TimeUnit.SECONDS)
    }
    try {
        check(setConfiguredExportTreeUri(context, tree))
        provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
        bound = context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound)
        check(connected.await(15, TimeUnit.SECONDS)) { "QA recorder did not bind" }
        val recorder = requireNotNull(service.get())
        val audio = ReverbService::class.java.getDeclaredField("audioHandler").apply { isAccessible = true }
            .get(recorder) as Handler
        val initialized = FutureTask {
            val record = ReverbService::class.java.getDeclaredField("audioRecord").apply { isAccessible = true }
            check(record.get(recorder) == null) { "Synthetic export unexpectedly owns microphone input" }
        }
        check(audio.post(initialized))
        initialized.get(15, TimeUnit.SECONDS)
        check(!readDurableCaptureIntentPreferences(prefs).enabled)
        val config = recorder.getConfigurationSnapshot()
        check(config.format == ExportFormat.WAV && config.codec == ExportCodec.PCM_16 &&
            config.sampleFormat == PcmSampleFormat.PCM_16 && config.sampleRate == 16_000 &&
            config.channelMode == ChannelMode.MONO) { "QA export configuration did not converge" }
        val worker = ReverbService::class.java.getDeclaredField("exportWorkExecutor").apply { isAccessible = true }
            .get(recorder) as ExecutorService
        executor = worker

        val source = PersistentAudioChunkStore(root)
        store = source
        source.configure(RetentionMode.SIZE, 134_217_728L, 16_000, 1, PcmSampleFormat.PCM_16)
        fun pcmBytes(firstFrame: Int, frames: Int): ByteArray = ByteArray(frames * 2).also { bytes ->
            for (frame in 0 until frames) {
                val sample = firstFrame + frame - 4000
                bytes[frame * 2] = sample.toByte()
                bytes[frame * 2 + 1] = (sample shr 8).toByte()
            }
        }
        fun fingerprints(directory: File): Map<String, Pair<Long, List<Byte>>> =
            directory.walkTopDown().filter { it.isFile }.associate { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                file.inputStream().use { input ->
                    val block = ByteArray(32_768)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        check(count > 0)
                        digest.update(block, 0, count)
                        length += count
                    }
                }
                file.relativeTo(directory).path to (length to digest.digest().toList())
            }
        fun checkSource(frames: Int, before: Map<String, Pair<Long, List<Byte>>>,
            selectedSource: PersistentAudioChunkStore = source, directory: File = root) {
            var position = 0L
            requireNotNull(selectedSource.acquireRange(0.0, selectedSource.durationSeconds())).use { original ->
                original.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                    for (index in 0 until count) {
                        val sample = ((position + index) / 2L).toInt() - 4000
                        val expected = (sample shr (if ((position + index) % 2L == 0L) 0 else 8)).toByte()
                        check(bytes[offset + index] == expected) { "Range exports changed source PCM" }
                    }
                    position += count
                    count
                }
            }
            check(position == frames * 2L)
            check(before == fingerprints(directory)) { "Range exports changed sealed source files" }
            Log.i("FractionalRangeQA", "Verified source ${directory.absolutePath}: $frames PCM16 mono frames at 16000 Hz")
            before.toSortedMap().forEach { (path, fingerprint) ->
                val hash = fingerprint.second.joinToString("") { "%02x".format(it.toInt() and 255) }
                Log.i("FractionalRangeQA", "${directory.name}/$path bytes=${fingerprint.first} sha256=$hash")
            }
        }
        // 8,001 frames: the 0.5000625-second tail is not exactly representable as Float.
        val pcm = pcmBytes(0, 8001)
        check(source.append(pcm, 0, pcm.size) == pcm.size)
        source.sealActiveChunk()
        check(source.durationSeconds() == 8001.0 / 16_000.0)
        val before = fingerprints(root)
        val uiConfig = ExportUiConfig(ExportFormat.WAV, ExportCodec.PCM_16, PcmSampleFormat.PCM_16, 16_000, 1)
        val boundedTail = buildCustomExportRange(source.durationSeconds(), 0.375f, 1f, uiConfig)
        check(boundedTail.startSeconds == 0.375f)
        check(boundedTail.endSeconds == source.durationSeconds().toFloat())
        check(boundedTail.warningDurationSeconds == null)

        fun exportAndCheck(name: String, start: Float, end: Float, firstFrame: Int, frames: Int,
            selectedSource: PersistentAudioChunkStore = source) {
            val providerRoot = File(context.filesDir, "reliability-documents")
            val unpublishedBefore = if (frames == 0) fingerprints(providerRoot) else null
            val writesBefore = provider.writeOpenCalls
            val delivered = CountDownLatch(1)
            val terminalCount = AtomicInteger()
            val saved = AtomicReference<RecordingEntity>()
            val failure = AtomicReference<Throwable>()
            val receiver = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) {
                    saved.set(recording); terminalCount.incrementAndGet(); delivered.countDown()
                }
                override fun fileFailed(message: String, error: Throwable?) {
                    failure.set(IllegalStateException(message, error))
                    terminalCount.incrementAndGet(); delivered.countDown()
                }
                override fun fileCancelled() {
                    failure.set(IllegalStateException("Synthetic export was unexpectedly cancelled"))
                    terminalCount.incrementAndGet(); delivered.countDown()
                }
            }
            val parent = ReverbService.TimelineSnapshot(requireNotNull(
                selectedSource.acquireRange(0.0, selectedSource.durationSeconds())))
            // Public submission owns the parent and its child, including empty selections.
            val submissionOwner = AtomicInteger(0) // 0 pending, 1 main owns submission, 2 revoked before submission.
            val submission = FutureTask {
                if (submissionOwner.compareAndSet(0, 1)) {
                    recorder.dumpRecordingRange(parent, start, end, receiver, name)
                }
                Unit
            }
            try {
                check(main.post(submission)) { "Main rejected fractional export submission" }
                submission.get(15, TimeUnit.SECONDS)
            } catch (error: Throwable) {
                val revoked = submissionOwner.compareAndSet(0, 2)
                if (revoked) main.removeCallbacks(submission)
                // A timeout after main claimed submission is an unknown handoff outcome. Its
                // parent stays owned until the main/worker fences below settle that operation.
                if (revoked || error is ExecutionException) {
                    try { parent.close() } catch (closeError: Throwable) {
                        if (closeError !== error) error.addSuppressed(closeError)
                    }
                }
                throw error
            }
            check(delivered.await(15, TimeUnit.SECONDS)) { "Fractional export did not deliver a terminal result" }
            worker.submit {}.get(15, TimeUnit.SECONDS)
            awaitMainCallbacks()
            check(terminalCount.get() == 1) { "Fractional export delivered duplicate terminal results" }
            check(!recorder.cancelCurrentExport()) { "Completed fractional export retained its token" }
            if (selectedSource.hasRangeLeaseCloseFailure()) {
                val releaseFailure = IllegalStateException("Synthetic export lease release failed; source evidence retained")
                failure.get()?.let { terminalFailure ->
                    terminalFailure.addSuppressed(releaseFailure)
                    throw terminalFailure
                }
                throw releaseFailure
            }
            if (frames == 0) {
                check(saved.get() == null) { "Empty terminal range published a recording" }
                val rejected = checkNotNull(failure.get()) { "Empty terminal range did not fail" }
                check(rejected.message == context.getString(R.string.nothing_to_export) && rejected.cause == null)
                check(provider.writeOpenCalls == writesBefore && fingerprints(providerRoot) == unpublishedBefore) {
                    "Empty terminal range changed provider output"
                }
                return
            }
            failure.get()?.let { throw it }
            val recording = checkNotNull(saved.get()) { "Fractional export did not publish a recording" }
            val expected = pcmBytes(firstFrame, frames)
            val expectedSize = 44 + expected.size
            val bytes = requireNotNull(openRecordingInputStream(context, recording)).use { input ->
                val bounded = ByteArray(expectedSize + 1)
                var length = 0
                while (length < bounded.size) {
                    val count = input.read(bounded, length, bounded.size - length)
                    if (count < 0) break
                    check(count > 0)
                    length += count
                }
                check(length == expectedSize) { "Unexpected WAV length (bounded read): $length" }
                bounded.copyOf(length)
            }
            check(expected.size == frames * 2)
            check(bytes.size == 44 + expected.size) { "Unexpected WAV length ${bytes.size}" }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(82, 73, 70, 70)))
            check(bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(87, 65, 86, 69)))
            check(bytes.copyOfRange(12, 16).contentEquals(byteArrayOf(102, 109, 116, 32)))
            check(bytes.copyOfRange(36, 40).contentEquals(byteArrayOf(100, 97, 116, 97)))
            check(header.getInt(4) == bytes.size - 8)
            check(header.getInt(16) == 16)
            check(header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 1)
            check(header.getInt(24) == 16_000 && header.getInt(28) == 32_000)
            check(header.getShort(32).toInt() == 2 && header.getShort(34).toInt() == 16)
            check(header.getInt(40) == frames * 2) { "Wrong fractional output frame count" }
            check(bytes.copyOfRange(44, bytes.size).contentEquals(expected)) { "Selected PCM bytes changed" }
        }

        exportAndCheck("fractional-middle", 0.125f, 0.375f, 2000, 4000)
        // Keep raw overshoot separate from the UI's displayed terminal endpoint.
        exportAndCheck("fractional-tail", 0.375f, 1f, 6000, 2001)
        check(source.durationSeconds().toFloat().toDouble() > source.durationSeconds())
        exportAndCheck("fractional-rounded-up-tail", 0.375f, boundedTail.endSeconds, 6000, 2001)
        checkSource(8001, before)

        // At this Float spacing, the displayed end loses a whole retained frame unless
        // the Service restores the exact terminal Double before acquiring the child range.
        val roundedDownFrames = 32_768_001
        var appendedFrames = 8001
        while (appendedFrames < roundedDownFrames) {
            // Whole 32-second chunk boundaries isolate endpoint conversion from
            // interior chunk rounding; the per-append duration cache can still differ.
            val frames = minOf(16_384, 512_000 - appendedFrames % 512_000, roundedDownFrames - appendedFrames)
            val block = pcmBytes(appendedFrames, frames)
            check(source.append(block, 0, block.size) == block.size)
            appendedFrames += frames
            if (appendedFrames % 512_000 == 0) source.sealActiveChunk()
        }
        source.sealActiveChunk()
        requireNotNull(source.acquireRange(0.0, source.durationSeconds())).use { geometry ->
            val field = geometry.javaClass.getDeclaredField("segments").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val segments = field.get(geometry) as List<PersistentAudioChunkStore.Segment>
            check(segments.map { it.frameCount } == listOf(8_001L, 503_999L) + List(63) { 512_000L } + 1L)
            check(geometry.durationSeconds == roundedDownFrames.toDouble() / 16_000.0)
        }
        val displayedEnd = source.durationSeconds().toFloat()
        check(displayedEnd == 2048f && displayedEnd.toDouble() < source.durationSeconds())
        val longBefore = fingerprints(root)
        exportAndCheck("fractional-rounded-down-tail", 2047.875f, displayedEnd, 32_766_000, 2001)
        exportAndCheck("fractional-terminal-empty", displayedEnd, displayedEnd, roundedDownFrames, 0)
        checkSource(roundedDownFrames, longBefore)

        // Exercise ordinary 1 MiB rollover separately from the exact 32-second seals above.
        val ordinaryDirectory = newSourceRoot("fractional-range-ordinary-source-")
        ordinaryRoot = ordinaryDirectory
        val ordinary = PersistentAudioChunkStore(ordinaryDirectory)
        ordinaryStore = ordinary
        ordinary.configure(RetentionMode.SIZE, 134_217_728L, 16_000, 1, PcmSampleFormat.PCM_16)
        check(ordinary.append(pcm, 0, pcm.size) == pcm.size)
        ordinary.sealActiveChunk()
        var ordinaryFrames = 8001
        while (ordinaryFrames < roundedDownFrames) {
            val frames = minOf(16_384, roundedDownFrames - ordinaryFrames)
            val block = pcmBytes(ordinaryFrames, frames)
            check(ordinary.append(block, 0, block.size) == block.size)
            ordinaryFrames += frames
        }
        ordinary.sealActiveChunk()
        requireNotNull(ordinary.acquireRange(0.0, ordinary.durationSeconds())).use { geometry ->
            val field = geometry.javaClass.getDeclaredField("segments").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val segments = field.get(geometry) as List<PersistentAudioChunkStore.Segment>
            check(segments.map { it.frameCount } == listOf(8_001L) + List(62) { 524_288L } + 254_144L)
            check(geometry.durationSeconds == roundedDownFrames.toDouble() / 16_000)
        }
        val ordinaryBefore = fingerprints(ordinaryDirectory)
        val ordinaryDisplayedEnd = ordinary.durationSeconds().toFloat()
        check(ordinaryDisplayedEnd == 2048f)
        exportAndCheck("fractional-ordinary-rounded-down-tail", 2047.875f, ordinaryDisplayedEnd,
            32_766_000, 2001, ordinary)
        exportAndCheck("fractional-ordinary-terminal-empty", ordinaryDisplayedEnd, ordinaryDisplayedEnd,
            roundedDownFrames, 0, ordinary)
        checkSource(roundedDownFrames, ordinaryBefore, ordinary, ordinaryDirectory)
        check(!readDurableCaptureIntentPreferences(prefs).enabled)
        val idle = FutureTask {
            val record = ReverbService::class.java.getDeclaredField("audioRecord").apply { isAccessible = true }
            check(record.get(recorder) == null) { "Synthetic export created microphone input" }
        }
        check(audio.post(idle))
        idle.get(15, TimeUnit.SECONDS)
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error
                else if (cleanupFailure !== error) cleanupFailure!!.addSuppressed(error)
            }
        }
        // Never reset the provider or delete source/output artifacts on failure. A timeout must
        // settle main submission before cancellation/worker fencing, or a late main handoff
        // could queue export work behind the worker fence and race synthetic source closure.
        var submissionDrained = false
        cleanup { awaitMainCallbacks(); submissionDrained = true }
        if (primaryFailure != null && submissionDrained) cleanup { service.get()?.cancelCurrentExport() }
        var drained = executor == null && submissionDrained
        if (submissionDrained) cleanup {
            executor?.submit {}?.get(15, TimeUnit.SECONDS)
            drained = true
        }
        if (drained) {
            cleanup { awaitMainCallbacks() }
            cleanup { store?.close() }
            cleanup { ordinaryStore?.close() }
        }
        else cleanup { error("QA export worker did not drain; synthetic source retained open") }
        if (bound) cleanup { context.unbindService(connection) }
        if (drained) cleanup { check(setConfiguredExportTreeUri(context, null)) }
        if (primaryFailure == null && cleanupFailure == null && drained) cleanup {
            check(store != null && ordinaryStore != null && ordinaryRoot != null && ownedRoots.size == 2)
            val firstSource = checkNotNull(store)
            val secondSource = checkNotNull(ordinaryStore)
            check(!firstSource.hasRangeLeaseCloseFailure() && !secondSource.hasRangeLeaseCloseFailure()) {
                "Synthetic export lease release failed; source evidence retained"
            }
            deleteSuccessfulFractionalFixtureSources(ownedRoots)
        }
        cleanupFailure?.let { error ->
            if (primaryFailure != null) {
                if (primaryFailure !== error) primaryFailure!!.addSuppressed(error)
            } else throw error
        }
    }
}

private data class FractionalFixtureEntry(val path: Path, val identity: StructStat)

/** Preflight both current-run roots before deleting anything; never follow a symbolic link. */
private fun deleteSuccessfulFractionalFixtureSources(roots: List<FractionalFixtureEntry>) {
    fun checkIdentity(entry: FractionalFixtureEntry) {
        val current = Os.lstat(entry.path.toString())
        check(current.st_dev == entry.identity.st_dev && current.st_ino == entry.identity.st_ino &&
            current.st_mode == entry.identity.st_mode && current.st_size == entry.identity.st_size) {
            "Synthetic fixture changed before cleanup: ${entry.path}"
        }
    }
    val entries = ArrayList<FractionalFixtureEntry>()
    for (root in roots) {
        // Directory size changes during fixture creation; device/inode/type own the root.
        val current = Os.lstat(root.path.toString())
        check(current.st_dev == root.identity.st_dev && current.st_ino == root.identity.st_ino &&
            OsConstants.S_ISDIR(current.st_mode)) { "Synthetic fixture root replaced: ${root.path}" }
        Files.walkFileTree(root.path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                val identity = Os.lstat(path.toString())
                check(attrs.isDirectory && OsConstants.S_ISDIR(identity.st_mode))
                entries += FractionalFixtureEntry(path, identity)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                val identity = Os.lstat(path.toString())
                check(attrs.isRegularFile && OsConstants.S_ISREG(identity.st_mode)) {
                    "Non-regular synthetic fixture entry preserved: $path"
                }
                entries += FractionalFixtureEntry(path, identity)
                return FileVisitResult.CONTINUE
            }
        })
    }
    for (entry in entries.asReversed()) {
        // Directory sizes change as children are removed; file bytes must stay unchanged.
        if (OsConstants.S_ISDIR(entry.identity.st_mode)) {
            val current = Os.lstat(entry.path.toString())
            check(current.st_dev == entry.identity.st_dev && current.st_ino == entry.identity.st_ino &&
                OsConstants.S_ISDIR(current.st_mode)) { "Synthetic fixture directory replaced: ${entry.path}" }
        } else checkIdentity(entry)
        Files.delete(entry.path)
    }
}
