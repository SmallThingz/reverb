package app.smallthingz.reverb

import android.app.Application
import android.view.Choreographer
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import smallthingz.ui.platform.AndroidUiFrameClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PlaybackSessionOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLibraryTrimSaveOwnershipTest {
    @Test fun otherRowsCannotDisplaceCommittedTrimBeforeItsInlineResult() = Fixture().use { f ->
        val expansion = f.enterTrim()
        f.onInlineSaved = {
            // The local lock bridges the handoff from the physical reservation to the UI result.
            assertFalse(recordingMutations.isActive(f.a.id))
            f.library.activate(f.b)
            assertTrue(f.library.isCurrentPlaybackOwner(expansion))
        }
        f.startSave(reserveImmediately = false)
        // These commands arrive before the writer reserves A or the busy state is published.
        assertFalse(recordingMutations.isActive(f.a.id))
        f.library.activate(f.b)
        f.library.leadingLongClick(f.b)
        assertFalse(f.library.fallbackExpandedBack())
        f.library.deleteRecordings(listOf(f.a))
        f.library.localBusy(f.a.id, false, Any()) // Unrelated lease retirement.
        f.acquireReservation()
        assertEquals(setOf(f.a.id), f.libraryState.list.localOperationBusyIds)
        assertTrue(f.playerState.state.trimSaving)

        f.library.longClick(f.b)
        f.pump()
        assertEquals(f.b, f.libraryState.menuRecording)
        for (command in listOf<(RecordingEntity) -> Unit>(
            f.library::info, f.library::rename, f.library::trim, f.library::multiSelect,
        )) command(f.b)
        f.library.collapse()
        f.library.deleteRecordings(listOf(f.a))
        f.pump()
        assertSame(expansion, f.libraryState.list.expandedOwner)
        assertEquals(0, f.libraryState.selectedCount)
        assertNull(f.libraryState.infoRecording)
        assertNull(f.libraryState.renameRecording)
        assertTrue(f.libraryState.recordings.any { it.id == f.a.id })
        assertTrue(f.inlineResults.isEmpty())

        f.completeSave()
        assertEquals(listOf(f.trimmed), f.inlineResults)
        assertSame(expansion, f.libraryState.list.expandedOwner)
        assertFalse(f.playerState.state.trimMode)
        assertFalse(f.playerState.state.trimSaving)
        assertTrue(f.libraryState.list.localOperationBusyIds.isEmpty())
        f.library.activate(f.b)
        f.pump()
        assertEquals(f.b.id, f.libraryState.list.expandedOwner?.recordingId)
    }

    @Test fun disposingTheRowReleasesOnlyTheLocalLockWhileAcceptedSaveFinishes() = Fixture().use { f ->
        f.enterTrim()
        f.startSave()
        val failure = IOException("held trim writer failed")
        val priorFeedback = AppFeedbackCenter.events.value.mapTo(mutableSetOf()) { it.id }
        f.player.close()
        f.library.activate(f.b)
        f.pump()
        assertEquals(f.b.id, f.libraryState.list.expandedOwner?.recordingId)
        assertTrue(f.libraryState.list.localOperationBusyIds.isEmpty())
        assertTrue(recordingMutations.isActive(f.a.id))
        assertNull(recordingMutations.tryBegin(f.a.id))

        f.completeSave(failure)
        assertTrue(f.inlineResults.isEmpty())
        assertEquals(f.b.id, f.libraryState.list.expandedOwner?.recordingId)
        val delivered = AppFeedbackCenter.events.value.filterNot { it.id in priorFeedback }.single()
        assertEquals(FeedbackTone.ERROR, delivered.tone)
        assertEquals(recordingTrimFailureMessage(f.app, failure), delivered.message)
        AppFeedbackCenter.acknowledge(delivered.id)
    }

    @Test fun aSaveActionFromTheOldExpansionCannotCommitBeforeItsNextBind() = Fixture().use { f ->
        f.enterTrim()
        val staleSave = f.playerState.actions.saveTrim
        f.library.activate(f.b)
        staleSave()
        f.pump()
        assertFalse(f.saveStarted.isCompleted)
        assertEquals(f.b.id, f.libraryState.list.expandedOwner?.recordingId)
    }

    private class Fixture : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val app = RuntimeEnvironment.getApplication()
        private val previousShadowPath = PlaybackSessionOsShadow.targetPath
        private val wav = File.createTempFile("trim-owner-", ".wav", app.cacheDir).apply {
            val pcm = ByteArray(16_000)
            writeBytes(buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm)
            PlaybackSessionOsShadow.targetPath = absolutePath
        }
        val a = RecordingEntity(wav.absolutePath, "A.wav", "audio/wav", 1L, 1_000L, wav.length(), "PCM",
            RecordingStorageType.FILE, app.cacheDir.absolutePath, resolveFileIdentity(wav))
        val b = a.copy(id = File(app.cacheDir, "trim-owner-b.wav").absolutePath, displayName = "B.wav")
        val trimmed = a.copy(id = File(app.cacheDir, "trim-owner-result.wav").absolutePath, displayName = "A trim.wav")
        private val host = View(app)
        // Leave catalog and refresh jobs parked while the real main-thread presentations run.
        private val libraryScope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        private val playerDispatcher = QueuedDispatcher()
        private val playerScope = CoroutineScope(playerDispatcher)
        private val busyLease = Any()
        val saveStarted = CompletableDeferred<Unit>()
        private val admitReservation = CompletableDeferred<Unit>()
        private val writerResult = CompletableDeferred<Result<RecordingEntity>>()
        val inlineResults = mutableListOf<RecordingEntity>()
        var onInlineSaved: () -> Unit = {}
        lateinit var libraryState: NativeLibraryPresentation
        lateinit var playerState: NativeInlinePlayerPresentation
        val library = NativeLibraryController(app, host, this, libraryScope,
            AndroidUiFrameClock(Choreographer.getInstance()), null,
            NativeLibraryInput(true, listOf(a, b), NativeLibraryCallbacks()), publish = { libraryState = it })
        lateinit var player: NativeRecordingInlinePlayerController

        fun enterTrim(): InlinePlaybackRowOwner {
            assertTrue(a.fileIdentity.isNotBlank())
            library.trim(a)
            library.publishInitialPresentation()
            val owner = requireNotNull(libraryState.list.expandedOwner)
            val callbacks = NativeInlinePlayerCallbacks(
                onTrimRequestConsumed = { library.trimRequestConsumed(a.id) },
                onTrimSaved = { inlineResults += it; onInlineSaved() },
                onTrimStateUncertain = { error("fixture never changes catalog identity") },
                onWaveformCached = {},
                onCollapse = library::collapse,
                onPlaybackFailed = { throw AssertionError("Trim preview failed to prepare", it) },
                onBusyChange = { library.localBusy(a.id, it, busyLease) },
            )
            val input = NativeInlinePlayerInput(a, true, true, owner, library::isCurrentPlaybackOwner,
                callbacks, waveformReadAllowed = false)
            player = NativeRecordingInlinePlayerController(app, host, this, playerScope, input,
                publish = { playerState = it }, saveTrimCopy = { context, recording, start, end ->
                    assertSame(app, context)
                    assertEquals(a, recording)
                    assertEquals(0, start)
                    assertEquals(1_000, end)
                    saveStarted.complete(Unit)
                    admitReservation.await()
                    val reservation = requireNotNull(recordingMutations.tryBegin(recording.id))
                    try {
                        writerResult.await().getOrThrow()
                    } finally {
                        recordingMutations.finish(reservation)
                    }
                })
            player.publishInitialPresentation()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!playerState.state.trimMode || !playerState.state.prepared) {
                check(System.nanoTime() < deadline) { "Trim preview did not prepare" }
                pump()
                playerDispatcher.tasks.poll(5, TimeUnit.MILLISECONDS)?.run()
            }
            player.bind(input.copy(trimRequested = false))
            pump()
            return owner
        }

        fun startSave(reserveImmediately: Boolean = true) {
            playerState.actions.saveTrim()
            assertTrue(saveStarted.isCompleted)
            if (reserveImmediately) acquireReservation()
        }

        fun acquireReservation() {
            assertTrue(admitReservation.complete(Unit))
            pump()
            assertTrue(recordingMutations.isActive(a.id))
        }

        fun completeSave(failure: Throwable? = null) {
            val result = if (failure == null) Result.success(trimmed) else Result.failure(failure)
            assertTrue(writerResult.complete(result))
            pump()
            assertFalse(recordingMutations.isActive(a.id))
        }

        fun pump() {
            playerDispatcher.drain()
            ShadowLooper.idleMainLooper()
        }

        override fun close() {
            try {
                if (::player.isInitialized) player.close()
            } finally {
                admitReservation.cancel()
                writerResult.complete(Result.failure(IOException("fixture retired")))
                playerScope.cancel()
                playerDispatcher.drain()
                library.close()
                libraryScope.cancel()
                PlaybackSessionOsShadow.targetPath = previousShadowPath
                wav.delete()
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }
}
