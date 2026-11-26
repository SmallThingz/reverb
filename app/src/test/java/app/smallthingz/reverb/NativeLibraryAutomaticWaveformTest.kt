package app.smallthingz.reverb

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.NativeInlineWaveformMotion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import smallthingz.ui.runtime.MonotonicFrameClock
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Actual inline owner and WAV pipeline; the fixture controls the provider and independently queues owner and IO work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLibraryAutomaticWaveformTest {
    @Test fun automaticWarmCacheWaitsForProofThenUsesLatestSameRevisionCacheWithoutOpeningReader() =
        Fixture(warmCache = true).use { f ->
            assertTrue(f.input.playbackOwner!!.automaticRestore)
            assertFalse(f.input.waveformReadAllowed)
            assertEquals("", f.input.recording.waveformData)
            assertTrue(f.verified.waveformData.isNotBlank())
            assertTrue(sameNativeInlinePlayerRevision(f.input.recording, f.verified))
            repeat(3) { f.rebind(f.input) }
            assertTrue("Deferred waveform must not suppress controls publication", f.presentations.isNotEmpty())
            assertEquals(1_000, f.latest.state.durationMillis)
            assertFalse("No loading wobble while awaiting catalog proof", f.latest.waveformLoading)
            assertFalse(f.latest.waveformMorphStarted)
            assertEquals(0, f.provider.opens.get())
            assertEquals(0, f.provider.queries.get())

            f.allowRead(f.verified)
            f.awaitWaveform()
            assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, f.latest.waveform.detail.builtCount)
            assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, f.latest.waveform.coarse.builtCount)
            assertEquals(0, f.provider.opens.get())
            assertEquals(0, f.provider.queries.get())
            assertTrue(f.cached.isEmpty())
            val waveform = f.latest.waveform
            repeat(3) { f.rebind(f.input) }
            assertSame(waveform, f.latest.waveform)
            assertEquals(0, f.provider.opens.get())
        }

    @Test fun automaticUncachedProofAdmitsExactlyOneDetailReaderAndRepeatedBindingDoesNotRestartIt() =
        Fixture().use { f ->
            assertTrue(f.presentations.isNotEmpty())
            assertFalse(f.latest.waveformLoading)
            assertEquals(0, f.provider.opens.get())
            assertFalse(f.latest.waveformMorphStarted)
            f.allowRead()
            f.awaitWaveform()
            assertEquals(1, f.provider.opens.get())
            assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, f.latest.waveform.coarse.builtCount)
            assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, f.latest.waveform.detail.builtCount)
            assertTrue(f.latest.waveform.detailAvailable)
            assertEquals(1, f.cached.size)
            assertTrue(isValidRecordingWaveformCache(f.cached.single(), f.cached.single().waveformData,
                f.cached.single().waveformRevision))
            f.provider.assertClosed()
            repeat(4) { f.rebind(f.input.copy(recording = f.cached.single())) }
            assertEquals(1, f.provider.opens.get())
            assertEquals(1, f.cached.size)
        }

    @Test fun authoritativeLastSeenRevisionReplacementStillUsesOneAutomaticDetailPass() = Fixture().use { f ->
        val verified = f.verified.copy(lastSeenAtMillis = f.verified.lastSeenAtMillis + 1)
        assertFalse(sameNativeInlinePlayerRevision(f.input.recording, verified))
        assertFalse(f.latest.waveformLoading)
        assertEquals(0, f.provider.opens.get())
        f.allowRead(verified)
        f.awaitWaveform()
        assertEquals(1, f.provider.opens.get())
        assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, f.latest.waveform.coarse.builtCount)
        assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, f.latest.waveform.detail.builtCount)
        assertEquals(1, f.cached.size)
    }

    @Test fun automaticOwnerCreatedAfterProofStillUsesOneDetailPass() = Fixture(proofAtCreation = true).use { f ->
        assertTrue(f.input.waveformReadAllowed)
        assertTrue(f.input.playbackOwner!!.automaticRestore)
        f.awaitWaveform()
        assertEquals(1, f.provider.opens.get())
        assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, f.latest.waveform.coarse.builtCount)
        assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, f.latest.waveform.detail.builtCount)
    }

    @Test fun closingBeforeProofNeverAdmitsReaderOrLateWaveformPublication() = Fixture().use { f ->
        assertFalse(f.latest.waveformLoading)
        assertEquals(0, f.provider.opens.get())
        val publications = f.presentations.size
        f.controller.close()
        f.settle()
        assertEquals(publications, f.presentations.size)
        assertEquals(0, f.provider.opens.get())
        assertEquals(0, f.provider.queries.get())
        assertTrue(f.cached.isEmpty())
        assertEquals(0, f.lifecycle.observerCount)
    }

    @Test fun proofWhileHiddenOrWithoutCurrentRowOwnerNeverAdmitsReader() {
        for (reason in listOf("hidden", "detached", "stale-owner")) Fixture().use { f ->
            if (reason == "stale-owner") f.ownsRow = false
            f.rebind(f.input.copy(
                waveformReadAllowed = true,
                screenActive = reason != "hidden",
                playbackOwner = f.input.playbackOwner.takeUnless { reason == "detached" },
            ))
            repeat(3) { f.rebind(f.input) }
            assertEquals(reason, 0, f.provider.opens.get())
            assertEquals(reason, 0, f.provider.queries.get())
            assertFalse(reason, f.latest.waveformMorphStarted)
            assertFalse(reason, f.latest.waveformLoading)
            assertTrue(f.cached.isEmpty())
        }
    }

    @Test fun proofWhileStoppedWaitsForStartedThenAdmitsExactlyOneReader() = Fixture().use { f ->
        val owner = f.input.playbackOwner
        f.lifecycle.currentState = Lifecycle.State.CREATED
        f.settle()
        f.allowRead()
        assertSame(owner, f.input.playbackOwner)
        assertTrue(f.input.screenActive)
        assertTrue(f.input.isCurrentPlaybackOwner(checkNotNull(owner)))
        assertEquals(0, f.provider.opens.get())
        assertEquals(0, f.provider.queries.get())
        assertFalse(f.latest.waveformLoading)
        assertFalse(f.latest.waveformMorphStarted)

        f.lifecycle.currentState = Lifecycle.State.STARTED
        f.awaitWaveform()
        assertEquals(1, f.provider.opens.get())
        assertEquals(1, f.cached.size)
        f.lifecycle.currentState = Lifecycle.State.CREATED
        f.lifecycle.currentState = Lifecycle.State.STARTED
        f.settle()
        assertEquals("Another start must not repeat the admitted waveform", 1, f.provider.opens.get())
    }

    @Test fun closeAfterStoppedProofPreventsReadWhenActivityStartsLater() = Fixture().use { f ->
        f.lifecycle.currentState = Lifecycle.State.CREATED
        f.allowRead()
        assertEquals(0, f.provider.opens.get())
        f.controller.close()
        val publications = f.presentations.size
        f.lifecycle.currentState = Lifecycle.State.STARTED
        f.settle()
        assertEquals(publications, f.presentations.size)
        assertEquals(0, f.provider.opens.get())
        assertEquals(0, f.provider.queries.get())
        assertEquals(0, f.lifecycle.observerCount)
        assertTrue(f.cached.isEmpty())
    }

    @Test fun stoppingAfterAdmissionButBeforeQueuedJobRunsDefersReaderUntilNextStart() = Fixture().use { f ->
        f.queueBind(f.input.copy(waveformReadAllowed = true))
        // The owner has accepted admission, but its build coroutine has not executed yet.
        f.lifecycle.currentState = Lifecycle.State.CREATED
        f.settle()
        assertEquals(0, f.provider.opens.get())
        assertEquals(0, f.provider.queries.get())
        assertFalse(f.latest.waveformLoading)
        assertFalse(f.latest.waveformMorphStarted)

        f.lifecycle.currentState = Lifecycle.State.STARTED
        f.awaitWaveform()
        assertEquals(1, f.provider.opens.get())
        assertEquals(1, f.cached.size)
    }

    @Test fun stoppedQueuedIoDoesNotReadOrCacheAndRestartsOnce() {
        val io = QueuedDispatcher()
        Fixture(waveformIo = io).use { f ->
            f.allowRead()
            assertEquals(1, io.tasks.size)
            f.lifecycle.currentState = Lifecycle.State.CREATED
            io.runOneOnIo()
            f.settle()
            f.assertNoReadOrCache()
            assertFalse(f.latest.waveformLoading)
            assertFalse(f.latest.waveformMorphStarted)

            f.lifecycle.currentState = Lifecycle.State.STARTED
            f.settle()
            assertEquals(1, io.tasks.size)
            io.runOneOnIo()
            f.assertOneCompletedRead()
        }
    }

    @Test fun queuedIoCannotUseRestoredEligibilityFromALaterBindingOrStart() {
        for (change in listOf("restart", "reattach", "restore proof", "new owner", "new revision")) {
            val io = QueuedDispatcher()
            Fixture(waveformIo = io).use { f ->
                f.allowRead()
                assertEquals(change, 1, io.tasks.size)
                val previous = f.input
                when (change) {
                    "restart" -> {
                        f.lifecycle.currentState = Lifecycle.State.CREATED
                        f.lifecycle.currentState = Lifecycle.State.STARTED
                    }
                    "reattach" -> {
                        f.rebind(previous.copy(screenActive = false))
                        f.rebind(previous)
                    }
                    "restore proof" -> {
                        f.rebind(previous.copy(waveformReadAllowed = false))
                        f.rebind(previous)
                    }
                    "new owner" -> {
                        val owner = InlinePlaybackRowOwner(f.verified.id, automaticRestore = true)
                        f.rebind(previous.copy(playbackOwner = owner, isCurrentPlaybackOwner = { it === owner }))
                    }
                    "new revision" -> f.allowRead(f.verified.copy(lastSeenAtMillis = f.verified.lastSeenAtMillis + 1))
                }
                io.runOneOnIo() // Release only the original worker after eligibility has been restored.
                f.settle()
                f.assertNoReadOrCache()
                assertEquals(change, 1, io.tasks.size)
                io.runOneOnIo()
                f.assertOneCompletedRead()
                assertTrue(change, io.tasks.isEmpty())
            }
        }
    }

    @Test fun manualDefaultStillBuildsCoarseAndDetailWithTwoReaderOpens() = Fixture(automatic = false).use { f ->
        assertTrue("Manual input keeps its default admission", f.input.waveformReadAllowed)
        assertFalse(f.input.playbackOwner!!.automaticRestore)
        f.awaitWaveform()
        assertEquals(2, f.provider.opens.get())
        assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, f.latest.waveform.coarse.builtCount)
        assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, f.latest.waveform.detail.builtCount)
        assertEquals(1, f.cached.size)
        f.provider.assertClosed()
        repeat(3) { f.rebind(f.input) }
        assertEquals(2, f.provider.opens.get())
    }

    private class Fixture(
        automatic: Boolean = true,
        warmCache: Boolean = false,
        proofAtCreation: Boolean = false,
        private val waveformIo: QueuedDispatcher? = null,
    ) : LifecycleOwner, AutoCloseable {
        // Waveform admission is independent of RESUMED; avoid issuing manual audio playback.
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        private val app: Application = RuntimeEnvironment.getApplication()
        private val dispatcher = QueuedDispatcher()
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + dispatcher)
        private val previews = linkedSetOf<TimelineAudioPreviewController>()
        private val payload = File.createTempFile("automatic-waveform-", ".wav", app.cacheDir).apply {
            val pcm = ByteArray(8_000 * 2) { index -> if (index % 2 == 0) 0 else 0x40 }
            writeBytes(buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm)
        }
        val provider = CountingWaveformProvider(payload)
        private val dao = RecordingDatabase.getInstance(app).recordingDao()
        val verified: RecordingEntity
        val presentations = mutableListOf<NativeInlinePlayerPresentation>()
        val cached = mutableListOf<RecordingEntity>()
        var ownsRow = true
        var input: NativeInlinePlayerInput
            private set
        val controller: NativeRecordingInlinePlayerController
        val latest: NativeInlinePlayerPresentation get() = presentations.last()

        init {
            provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            val recording = RecordingEntity(provider.uri.toString(), "waveform.wav", "audio/wav", 1L,
                1_000L, payload.length(), "PCM", RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID,
                providerRecordingIdentity(RecordingStorageType.MEDIASTORE, provider.uri.toString(), payload.length(), 9L),
                createdAtMillis = 1L, lastSeenAtMillis = 1L)
            verified = if (warmCache) recording.copy(
                waveformRevision = recordingWaveformRevision(recording),
                waveformData = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { .4f }),
            ) else recording
            runBlocking {
                dao.upsert(verified)
                assertEquals(verified, dao.findById(verified.id))
            }
            val playbackOwner = if (automatic) InlinePlaybackRowOwner(recording.id, automaticRestore = true)
                .apply { consumeInitialAutoStart() } else InlinePlaybackRowOwner(recording.id)
            val callbacks = NativeInlinePlayerCallbacks({}, {}, {}, cached::add, {},
                { error("Waveform fixture must not start playback") }, {})
            val defaults = NativeInlinePlayerInput(
                librarySnapshotWithoutWaveformTrust(listOf(verified)).single(), false, true, playbackOwner,
                { ownsRow && it === playbackOwner }, callbacks,
            )
            // Omit the optional argument for manual rows to exercise the production default.
            input = if (automatic) defaults.copy(waveformReadAllowed = proofAtCreation) else defaults
            controller = NativeRecordingInlinePlayerController(app, View(app), this, scope, input, presentations::add,
                waveformIo ?: Dispatchers.IO)
            rememberPreview()
            settle()
        }

        fun allowRead(recording: RecordingEntity = verified) =
            rebind(input.copy(recording = recording, waveformReadAllowed = true))

        fun rebind(next: NativeInlinePlayerInput) {
            queueBind(next)
            settle()
        }

        fun queueBind(next: NativeInlinePlayerInput) {
            input = next
            controller.bind(next)
            rememberPreview()
        }

        fun assertNoReadOrCache() {
            assertEquals(0, provider.queries.get())
            assertEquals(0, provider.opens.get())
            assertTrue(cached.isEmpty())
            assertEquals(verified, runBlocking { dao.findById(verified.id) })
        }

        fun assertOneCompletedRead() {
            awaitWaveform()
            assertEquals(1, provider.opens.get())
            val result = cached.single()
            assertEquals(input.recording.lastSeenAtMillis, result.lastSeenAtMillis)
            assertTrue(checkNotNull(decodeRecordingWaveform(result.waveformData)).any { it > 0f })
            assertEquals(result.waveformData, runBlocking { dao.findById(result.id) }?.waveformData)
            provider.assertClosed()
        }

        fun awaitWaveform() {
            runUntil { presentations.isNotEmpty() && !latest.waveformLoading }
            // Loading=false on failure is insufficient: insist the real WAV reader completed.
            assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, latest.waveform.detail.builtCount)
            assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, latest.waveform.coarse.builtCount)
            assertTrue(latest.waveform.detailAvailable)
            assertSettledFullWidthFrame()
        }

        private fun assertSettledFullWidthFrame() = runBlocking {
            var frame: ProgressiveWaveformFrame? = null
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                    error("Settled zero-scale waveform must not request animation frames")
            }
            val motion = NativeInlineWaveformMotion(clock, this, { 0f }) { frame = it }
            try {
                motion.bind(latest.waveform, latest.waveformLoading, latest.waveformMorphStarted)
                yield()
                val settled = checkNotNull(frame)
                assertEquals(1f, settled.morphProgress, 0f)
                assertEquals("The renderer's coarse reveal must cover the whole recording",
                    1f, settled.visibleCoarseFraction, 0f)
                assertEquals("Detail reveal must not remain capped by an empty coarse envelope",
                    1f, settled.visibleDetailFraction, 0f)
            } finally {
                motion.close()
            }
        }

        fun settle() {
            repeat(20) {
                dispatcher.drain()
                ShadowLooper.idleMainLooper()
                if (dispatcher.tasks.isEmpty()) return
            }
            error("Inline waveform owner did not settle within twenty dispatch turns")
        }

        private fun runUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                settle()
                if (done()) return
                check(System.nanoTime() < deadline) { "Inline waveform did not reach its expected boundary" }
                dispatcher.tasks.poll(5, TimeUnit.MILLISECONDS)?.run()
            }
        }

        // Existing lifetime-fixture pattern: reflection is confined to verifying preview cleanup.
        private fun field(owner: Any, name: String): Any = checkNotNull(owner.javaClass
            .getDeclaredField(name).apply { isAccessible = true }.get(owner))

        private fun rememberPreview() {
            previews += field(field(controller, "session"), "fineSeekPreviewController") as TimelineAudioPreviewController
        }

        override fun close() {
            try {
                controller.close()
            } finally {
                scope.cancel()
                if (waveformIo != null) runBlocking(Dispatchers.IO) { waveformIo.drain() }
                runUntil { parent.isCompleted }
                try {
                    for (preview in previews) for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                        assertTrue("Preview $name must terminate", (field(preview, name) as ExecutorService)
                            .awaitTermination(5, TimeUnit.SECONDS))
                    }
                    provider.assertClosed()
                } finally {
                    provider.closeLeaked()
                    runBlocking { dao.deleteById(verified.id) }
                    RecordingDatabase.resetAfterCorruption()
                    payload.delete()
                }
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runOneOnIo() = runBlocking(Dispatchers.IO) {
            checkNotNull(tasks.poll()) { "Expected a queued waveform IO worker" }.run()
        }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    private class CountingWaveformProvider(private val payload: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/audio/media/731951")
        val opens = AtomicInteger()
        val queries = AtomicInteger()
        private val descriptors = ConcurrentLinkedQueue<Pair<ParcelFileDescriptor, FileDescriptor>>()
        private val cursors = ConcurrentLinkedQueue<Cursor>()
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            check(uri == this.uri)
            queries.incrementAndGet()
            val columns = requireNotNull(projection)
            val values: Map<String, Any> = mapOf(
                MediaStore.MediaColumns._ID to 731951L,
                MediaStore.MediaColumns.DISPLAY_NAME to "waveform.wav",
                MediaStore.MediaColumns.MIME_TYPE to "audio/wav",
                MediaStore.MediaColumns.SIZE to payload.length(),
                MediaStore.MediaColumns.DATE_MODIFIED to 100L,
                MediaStore.MediaColumns.GENERATION_MODIFIED to 9L,
                MediaStore.MediaColumns.RELATIVE_PATH to "Music/Reverb/",
                MediaStore.MediaColumns.IS_PENDING to 0,
            )
            return MatrixCursor(columns).also { cursor ->
                cursor.addRow(columns.map { checkNotNull(values[it]) { "Unexpected provider column $it" } })
                cursors.add(cursor)
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(uri == this.uri && mode == "r")
            opens.incrementAndGet()
            return ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY).also {
                descriptors.add(it to it.fileDescriptor)
            }
        }

        override fun getType(uri: Uri) = "audio/wav"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only waveform provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only waveform provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only waveform provider")

        fun assertClosed() {
            assertTrue("Waveform reader descriptors must close", descriptors.all { !it.second.valid() })
            assertTrue("Waveform identity cursors must close", cursors.all { it.isClosed })
        }

        fun closeLeaked() {
            descriptors.forEach { (owner, fd) -> if (fd.valid()) owner.close() }
            cursors.forEach { if (!it.isClosed) it.close() }
        }
    }
}
