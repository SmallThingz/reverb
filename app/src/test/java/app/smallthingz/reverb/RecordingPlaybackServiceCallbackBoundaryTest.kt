package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowPausedMessageQueue

/** Exercises registered service callbacks on their real Handler; platform routing is not qualified. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [PlaybackSessionOsShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingPlaybackServiceCallbackBoundaryTest {
    @Test
    fun registeredCallbacksPauseBeforeQueuedSeekAndDelayedPrepare() = withPlaybackService { media, handler, callback, commandHandler ->
        val initialRevision = RecordingPlayback.state.value.transportIntentRevision

        // Observe real queue entries, without globally replacing Handler (including the
        // Handler cached by Dispatchers.Main before this Robolectric configuration).
        val seek = Runnable { callback.onSeekTo(900L) }
        val pause = Runnable { callback.onPause() }
        assertTrue(handler.post(seek))
        assertTrue(handler.post(pause))
        assertSame(seek, checkNotNull(queueHead()).callback)
        ShadowLooper.runMainLooperOneTask()
        val stagedSeek = queuedMessages().filter { it.target === commandHandler && it.callback != null }
        assertEquals("The registered seek callback must enqueue exactly one real service command", 1, stagedSeek.size)
        val queuedSeek = stagedSeek.single()
        assertSame(pause, checkNotNull(queueHead()).callback)
        ShadowLooper.runMainLooperOneTask()
        val pausedBeforeStaleDelivery = RecordingPlayback.state.value
        assertFalse(pausedBeforeStaleDelivery.wantsPlayback)
        assertTrue("Both registered callbacks must supersede the initial intent",
            pausedBeforeStaleDelivery.transportIntentRevision >= initialRevision + 2)
        assertSame("Pause must leave the exact queued seek as the next dispatch", queuedSeek, queueHead())
        ShadowLooper.runMainLooperOneTask()
        assertFalse("The actual queued service message must dispatch", queuedMessages().any { it === queuedSeek })
        assertEquals("A stale queued seek cannot mutate service state", pausedBeforeStaleDelivery, RecordingPlayback.state.value)

        media.invokePreparedListener()
        ShadowLooper.idleMainLooper()
        val finalState = RecordingPlayback.state.value
        assertTrue(finalState.prepared)
        assertEquals(0, finalState.positionMillis)
        assertFalse(finalState.playing)
        assertFalse(finalState.wantsPlayback)
        assertFalse("Delayed preparation must not resurrect a paused owner", media.isReallyPlaying)
    }

    @Test
    fun preparedServiceSeeksOnlyToLatestTargetAndPauseRevokesResume() =
        withPlaybackService(startPosition = 300) { media, handler, callback, _ ->
            fun assertQuiet() {
                val state = RecordingPlayback.state.value
                assertFalse(state.playing)
                assertFalse(checkNotNull(state.playbackAnchor).playing)
                assertFalse(media.isReallyPlaying)
            }

            media.setSeekDelay(-1)
            media.invokePreparedListener()
            assertEquals(300, media.pendingSeek)

            for (target in listOf(900, 1_400)) {
                handler.post { callback.onSeekTo(target.toLong()) }
                ShadowLooper.idleMainLooper()
                assertEquals(target, RecordingPlayback.state.value.positionMillis)
                assertEquals("Successive commands must leave the original native seek in flight", 300, media.pendingSeek)
                assertQuiet()
            }

            media.invokeSeekCompleteListener()
            ShadowLooper.idleMainLooper()
            assertEquals(300, media.currentPositionRaw)
            assertEquals("Only the latest replacement may be sent to the player", 1_400, media.pendingSeek)
            assertEquals(1_400, RecordingPlayback.state.value.positionMillis)
            assertTrue(RecordingPlayback.state.value.wantsPlayback)
            assertQuiet()

            handler.post { callback.onPause() }
            ShadowLooper.idleMainLooper()
            assertFalse(RecordingPlayback.state.value.wantsPlayback)
            assertEquals(1_400, media.pendingSeek)
            media.invokeSeekCompleteListener()
            ShadowLooper.idleMainLooper()
            assertEquals(-1, media.pendingSeek)
            assertEquals(1_400, media.currentPositionRaw)
            assertEquals(1_400, RecordingPlayback.state.value.positionMillis)
            assertFalse(RecordingPlayback.state.value.wantsPlayback)
            assertQuiet()
        }

    @Test
    fun preparedServiceResumesOnlyAfterLatestSeekCompletes() =
        withPlaybackService(startPosition = 300) { media, handler, callback, _ ->
            val audio = checkNotNull(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
            val audioShadow = Shadows.shadowOf(audio)
            audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            media.setSeekDelay(-1)
            media.invokePreparedListener()
            assertEquals(300, media.pendingSeek)

            for (target in listOf(900L, 1_400L)) {
                handler.post { callback.onSeekTo(target) }
                ShadowLooper.idleMainLooper()
            }
            assertEquals(300, media.pendingSeek)
            media.invokeSeekCompleteListener()
            ShadowLooper.idleMainLooper()
            assertEquals(1_400, media.pendingSeek)
            assertFalse("Completing the first seek must not play from that position", media.isReallyPlaying)
            assertFalse(checkNotNull(RecordingPlayback.state.value.playbackAnchor).playing)

            media.invokeSeekCompleteListener()
            ShadowLooper.idleMainLooper()
            assertTrue("Playback must start after the latest seek completes", media.isReallyPlaying)
            assertNotNull(audioShadow.lastAudioFocusRequest)
            assertEquals(1_400, media.currentPositionRaw)
            val resumed = RecordingPlayback.state.value
            assertTrue(resumed.wantsPlayback)
            assertTrue(resumed.playing)
            assertEquals(1_400, resumed.positionMillis)
            val anchor = checkNotNull(resumed.playbackAnchor)
            assertTrue(anchor.playing)
            assertEquals(1_400, anchor.positionAt(anchor.sampledAtElapsedRealtimeMillis))
            ShadowLooper.idleMainLooper(50L, TimeUnit.MILLISECONDS)
            assertEquals(1_450, anchor.positionAt(SystemClock.elapsedRealtime()))
        }

    @Test
    @Config(shadows = [PlaybackMetadataShadow::class])
    fun pausedSeeksReuseMetadataButPreparationRenameAndStopPublishChanges() =
        withPlaybackService(declaredDuration = 1_000) { media, _, callback, _ ->
            val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
            val session = readField(service, "session") as MediaSession
            val updates = Shadow.extract<PlaybackMetadataShadow>(session).updates
            fun assertMetadata(count: Int, title: String, duration: Long) {
                assertEquals(count, updates.size)
                assertEquals(title, updates.last().getString(MediaMetadata.METADATA_KEY_TITLE))
                assertEquals(duration, updates.last().getLong(MediaMetadata.METADATA_KEY_DURATION))
            }
            val selected = checkNotNull(RecordingPlayback.state.value.recording)
            assertMetadata(1, selected.displayName, 1_000)
            callback.onPause()
            assertMetadata(1, selected.displayName, 1_000)
            media.setSeekDelay(-1)
            media.invokePreparedListener()
            assertMetadata(2, selected.displayName, 2_000)
            for (target in listOf(400, 900, 1_400)) {
                service.seekMatching(selected, target, resume = false)
                media.invokeSeekCompleteListener()
                assertEquals(target, RecordingPlayback.state.value.positionMillis)
                assertFalse(RecordingPlayback.state.value.playing)
                assertMetadata(2, selected.displayName, 2_000)
            }
            val renamed = selected.copy(displayName = "renamed.wav")
            service.renameMatching(selected, renamed)
            assertMetadata(3, renamed.displayName, 2_000)
            assertEquals(renamed, RecordingPlayback.state.value.recording)
            callback.onPause()
            assertMetadata(3, renamed.displayName, 2_000)
            callback.onStop()
            assertMetadata(4, "", 0)
            assertFalse(RecordingPlayback.state.value.playing)
        }

    @Test fun blockedDocumentOpenAdmitsOnlyTheLatestReplacement() =
        exerciseBlockedProvider(blockInVerification = false)

    @Test fun blockedPostOpenVerificationRetiresDescriptorAcrossServiceRecreation() =
        exerciseBlockedProvider(blockInVerification = true)

    private fun exerciseBlockedProvider(blockInVerification: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("playback-open", ".wav", app.cacheDir)
        val provider = BlockingPlaybackDocuments(file, blockInVerification)
        provider.attachInfo(app, ProviderInfo().apply {
            authority = provider.authority
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(provider.authority, provider)
        val jobs = mutableListOf<Job>()
        fun createService() = Robolectric.buildService(RecordingPlaybackService::class.java).create().also {
            jobs += checkNotNull((readField(it.get(), "scope") as CoroutineScope).coroutineContext[Job])
        }
        var controller = createService()
        val players = mutableListOf<ShadowMediaPlayer>()
        var startId = 0
        fun start(row: RecordingEntity) {
            assertTrue(RecordingPlayback.play(app, row))
            controller.withIntent(checkNotNull(Shadows.shadowOf(app).nextStartedService)).startCommand(0, ++startId)
        }
        try {
            writePcm16Wav(file, sampleRate = 48_000, durationMillis = 2_000)
            ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 60_000) }
            ShadowMediaPlayer.setCreateListener { _, media -> players.add(media) }
            start(provider.recording("first"))
            assertTrue("First open did not reach the held provider call", provider.entered.await(8, TimeUnit.SECONDS))
            // Before replacement the opening job has exactly one live branch, ending at
            // the IO worker held inside the provider. Observe its actual Job completion.
            val opening = readField(controller.get(), "opening") as Job
            val worker = generateSequence(opening) { it.children.singleOrNull() }.last()
            val workerDone = CountDownLatch(1)
            worker.invokeOnCompletion { workerDone.countDown() }
            start(provider.recording("second"))
            if (blockInVerification) {
                controller.destroy()
                controller = createService()
                PlaybackSessionOsShadow.targetPath = file.absolutePath
                val local = fileRecording(file)
                start(local)
                awaitPlayer(controller.get())
                RecordingPlayback.pause(local)
                players.single().invokePreparedListener()
                assertTrue(RecordingPlayback.state.value.matches(local) && RecordingPlayback.state.value.prepared)
            }
            val localPlayerCount = if (blockInVerification) 1 else 0
            start(provider.recording("third"))
            val latest = provider.recording("latest")
            start(latest)
            ShadowLooper.idleMainLooper()
            assertTrue("Retirement must signal the blocked request", provider.cancelled.await(8, TimeUnit.SECONDS))
            assertEquals(localPlayerCount, players.size)
            assertEquals(setOf("first"), provider.queries.toSet())
            provider.release.countDown()
            if (blockInVerification) {
                assertTrue("Retired IO worker must finish while cancellation is still held",
                    workerDone.await(8, TimeUnit.SECONDS))
                ShadowLooper.idleMainLooper()
                assertEquals(1, provider.closes.getValue("first").get())
                assertEquals(listOf("first"), provider.opens.toList())
                assertEquals(setOf("first"), provider.queries.toSet())
                // Also detect a successor dispatched to IO but not yet scheduled by the host.
                val waiting = readField(controller.get(), "opening") as Job
                assertTrue(waiting.isActive && waiting.children.none())
            }
            provider.finishCancel.countDown()

            val player = awaitPlayer(controller.get())
            assertEquals(localPlayerCount + 1, players.size)
            assertSame(players.last(), Shadow.extract<ShadowMediaPlayer>(player))
            assertTrue(RecordingPlayback.state.value.matches(latest))
            assertEquals(listOf("first", "latest"), provider.opens.toList())
            assertEquals(setOf("first", "latest"), provider.queries.toSet())
            assertEquals(1, provider.peak.get())
            assertEquals(1, provider.closes.getValue("first").get())
            assertEquals(0, provider.closes.getValue("latest").get())
            RecordingPlayback.pause(latest)
            players.last().invokePreparedListener()
            assertTrue(RecordingPlayback.state.value.prepared)
            RecordingPlayback.stop(latest)
            assertEquals(1, provider.closes.getValue("latest").get())
        } finally {
            provider.release.countDown()
            provider.finishCancel.countDown()
            controller.destroy()
            jobs.forEach(::awaitServiceCompletion)
            ShadowMediaPlayer.resetStaticState()
            PlaybackSessionOsShadow.targetPath = ""
            check(file.delete())
        }
    }

    private fun fileRecording(source: File) = RecordingEntity(
        id = source.absolutePath,
        displayName = source.name,
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 2_000L,
        sizeBytes = source.length(),
        codecSummary = "PCM",
        storageType = RecordingStorageType.FILE,
        directoryId = source.parentFile!!.absolutePath,
        fileIdentity = resolveFileIdentity(source),
        createdAtMillis = 1L,
        lastSeenAtMillis = 1L,
    )

    private fun withPlaybackService(
        startPosition: Int? = null,
        declaredDuration: Long = 2_000,
        exercise: (ShadowMediaPlayer, Handler, MediaSession.Callback, Handler) -> Unit,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "playback-session-${System.nanoTime()}.wav")
        var recording: RecordingEntity? = null
        var serviceJob: Job? = null
        var failure: Throwable? = null
        var serviceController: org.robolectric.android.controller.ServiceController<RecordingPlaybackService>? = null
        try {
            writePcm16Wav(source, sampleRate = 48_000, durationMillis = 2_000)
            PlaybackSessionOsShadow.targetPath = source.absolutePath
            ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 60_000) }
            val selectedRecording = fileRecording(source).copy(durationMillis = declaredDuration)
            recording = selectedRecording
            val createdController = Robolectric.buildService(RecordingPlaybackService::class.java)
            serviceController = createdController
            val service = createdController.get()
            serviceJob = checkNotNull((readField(service, "scope") as CoroutineScope).coroutineContext[Job])
            createdController.create()
            assertTrue(RecordingPlayback.play(app, selectedRecording, startPosition))
            val startIntent = Shadows.shadowOf(app).getNextStartedService()
            assertNotNull(startIntent)
            createdController.withIntent(startIntent).startCommand(0, 1)
            val media = awaitPlayer(service)
            val mediaShadow = Shadow.extract<ShadowMediaPlayer>(media)
            assertFalse("Preparation is deliberately held by the paused main looper", mediaShadow.isPrepared)
            assertFalse(RecordingPlayback.state.value.prepared)
            assertTrue(RecordingPlayback.state.value.wantsPlayback)

            val session = readField(service, "session") as MediaSession
            val callbackHandler = readField(session, "mCallback") as Handler
            val callback = readField(callbackHandler, "mCallback") as MediaSession.Callback
            assertSame("The registered MediaSession callback must use the main looper",
                Looper.getMainLooper(), callbackHandler.looper)
            exercise(mediaShadow, callbackHandler, callback, readField(service, "main") as Handler)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error
                    else if (first !== error) first.addSuppressed(error)
                }
            }
            cleanup { recording?.let { RecordingPlayback.stop(it) } }
            cleanup { serviceController?.destroy() }
            cleanup { serviceJob?.let(::awaitServiceCompletion) }
            // A timed-out IO owner must not observe reset shadows or a deleted fixture.
            if (serviceJob?.isCompleted != false) {
                cleanup { ShadowLooper.idleMainLooper() }
                cleanup { ShadowMediaPlayer.resetStaticState() }
                cleanup { PlaybackSessionOsShadow.targetPath = "" }
                cleanup { check(!source.exists() || source.delete()) { "Cannot delete owned playback fixture" } }
            }
            failure?.let { throw it }
        }
    }

    private fun awaitServiceCompletion(job: Job) {
        job.cancel()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!job.isCompleted) {
            // Drain only currently due Main continuations; do not advance delayed media timers.
            ShadowLooper.idleMainLooper()
            if (job.isCompleted) return
            check(System.nanoTime() < deadline) { "Service IO did not finish; retaining fixture and shadows" }
            Thread.sleep(5)
        }
    }

    private fun awaitPlayer(service: RecordingPlaybackService): MediaPlayer {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            val player = readField(service, "player") as? MediaPlayer
            if (player != null) return player
            if (mainQueueSize() > 0) ShadowLooper.runMainLooperOneTask() else Thread.sleep(5)
        }
        throw AssertionError("RecordingPlaybackService did not create its MediaPlayer")
    }

    private fun mainQueue(): ShadowPausedMessageQueue =
        Shadow.extract(Looper.getMainLooper().queue)

    private fun mainQueueSize(): Int = mainQueue().internalGetSize()

    // ShadowPausedMessageQueue.getHead() explicitly rejects PAUSED mode. Inspect the
    // actual platform queue field that the paused shadow itself uses; never replace it.
    private fun queueHead(): Message? = readField(Looper.getMainLooper().queue, "mMessages") as? Message

    private fun queuedMessages(): List<Message> = buildList {
        var next: Message? = queueHead()
        while (next != null) {
            val message = next
            add(message)
            next = readField(message, "next") as? Message
        }
    }

    private fun readField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun writePcm16Wav(file: File, sampleRate: Int, durationMillis: Int) {
        val frames = sampleRate * durationMillis / 1_000
        val payloadBytes = frames * 2
        val buffer = ByteBuffer.allocate(44 + payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(value: String) { value.forEach { buffer.put(it.code.toByte()) } }
        tag("RIFF"); buffer.putInt(36 + payloadBytes); tag("WAVE")
        tag("fmt "); buffer.putInt(16); buffer.putShort(1); buffer.putShort(1)
        buffer.putInt(sampleRate); buffer.putInt(sampleRate * 2); buffer.putShort(2); buffer.putShort(16)
        tag("data"); buffer.putInt(payloadBytes)
        repeat(frames) { buffer.putShort(1_000) }
        file.writeBytes(buffer.array())
    }
}

private class BlockingPlaybackDocuments(
    private val file: File,
    private val blockInVerification: Boolean,
) : DocumentsProvider() {
    val authority = "app.smallthingz.reverb.blockedplayback"
    private val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
    val entered = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finishCancel = CountDownLatch(1)
    val opens = ConcurrentLinkedQueue<String>()
    val queries = ConcurrentLinkedQueue<String>()
    val closes = ConcurrentHashMap<String, AtomicInteger>()
    private val active = AtomicInteger()
    val peak = AtomicInteger()

    fun recording(name: String): RecordingEntity {
        val id = DocumentsContract.buildDocumentUriUsingTree(tree, name).toString()
        return RecordingEntity(id = id, displayName = "$name.wav", mimeType = "audio/wav", startedAtMillis = 1,
            durationMillis = 2_000, sizeBytes = file.length(), codecSummary = "PCM",
            storageType = RecordingStorageType.DOCUMENT, directoryId = tree.toString(),
            fileIdentity = providerRecordingIdentity(RecordingStorageType.DOCUMENT, id, file.length(), 100))
    }

    override fun onCreate() = true
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root"
    override fun queryRoots(projection: Array<out String>?): Cursor = error("Unexpected roots query")
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        error("Unexpected listing")

    // Robolectric forwards the six-argument query directly. Use the platform document router
    // and observe the request signal at the resolver boundary, even if openDocument ignores it.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? {
        if (DocumentsContract.getDocumentId(uri) == "first") {
            checkNotNull(cancellationSignal).setOnCancelListener {
                check(Looper.myLooper() != Looper.getMainLooper())
                cancelled.countDown()
                if (blockInVerification) check(finishCancel.await(20, TimeUnit.SECONDS))
            }
        }
        return super.query(uri, projection, null, cancellationSignal)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = providerCall {
        queries.add(documentId)
        if (blockInVerification && documentId == "first" && documentId in opens) block()
        val values = mapOf(Document.COLUMN_DOCUMENT_ID to documentId, Document.COLUMN_DISPLAY_NAME to "$documentId.wav",
            Document.COLUMN_MIME_TYPE to "audio/wav", Document.COLUMN_SIZE to file.length(),
            Document.COLUMN_LAST_MODIFIED to 100L)
        val columns = checkNotNull(projection)
        MatrixCursor(columns).apply { addRow(columns.map(values::getValue)) }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        providerCall {
            check(mode == "r")
            opens.add(documentId)
            if (!blockInVerification && documentId == "first") block()
            val calls = AtomicInteger()
            check(closes.putIfAbsent(documentId, calls) == null)
            object : ParcelFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                override fun close() {
                    calls.incrementAndGet()
                    super.close()
                }
            }
        }

    private fun block() {
        entered.countDown()
        check(release.await(20, TimeUnit.SECONDS)) { "Playback test did not release provider" }
    }

    private fun <T> providerCall(call: () -> T): T {
        peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        return try { call() } finally { active.decrementAndGet() }
    }
}

/** Consistent path/descriptor identity for this test WAV; production verification remains enabled. */
@Implements(Os::class)
class PlaybackSessionOsShadow {
    companion object {
        @Volatile var targetPath: String = ""
        private val descriptorNumber = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }

        @JvmStatic
        @Implementation
        fun lstat(path: String): StructStat {
            if (path != targetPath) throw ErrnoException("lstat", OsConstants.ENOENT)
            return hostUnixStat(Path.of(path), noFollowLinks = true)
        }

        @JvmStatic
        @Implementation
        fun fstat(descriptor: FileDescriptor): StructStat {
            val fd = descriptorNumber.getInt(descriptor)
            val descriptorPath = Path.of("/proc/self/fd/$fd")
            if (fd < 0 || !Files.isSameFile(descriptorPath, Path.of(targetPath))) {
                throw ErrnoException("fstat", OsConstants.EBADF)
            }
            return hostUnixStat(descriptorPath, noFollowLinks = false)
        }
    }
}

/** Counts the service's platform metadata submissions; does not qualify Binder routing. */
@Implements(MediaSession::class)
class PlaybackMetadataShadow {
    val updates = mutableListOf<MediaMetadata>()

    @Implementation
    fun setMetadata(metadata: MediaMetadata?) {
        updates.add(checkNotNull(metadata))
    }
}
