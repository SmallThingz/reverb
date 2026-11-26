package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Choreographer
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.DurableSaveJobs
import app.smallthingz.reverb.InlinePlaybackRowOwner
import app.smallthingz.reverb.NativeLibraryCallbacks
import app.smallthingz.reverb.NativeLibraryController
import app.smallthingz.reverb.NativeLibraryInput
import app.smallthingz.reverb.NativeMainScreenSavedState
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingPlayback
import app.smallthingz.reverb.RecordingPlaybackState
import app.smallthingz.reverb.RecordingStorageType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLibraryAutomaticRevealSavedStateTest {
    private val rows = List(32) { index -> RecordingEntity("/saved-reveal/$index.wav", "$index.wav", "audio/wav",
        1_700_000_000_000L + index, 2_000L, 64_044L, "PCM", RecordingStorageType.FILE,
        "/saved-reveal", "identity-$index", createdAtMillis = 1L, lastSeenAtMillis = 1L) }
    private val target = rows.last()

    @Test fun automaticPendingLayoutKeyIsNotSavedOrReplayedAfterPauseOrCompletion() {
        for (position in listOf(700, 2_000)) {
            val saved = Fixture(rows, playing(target)).use { f ->
                f.publishAutomatic(target)
                assertEquals(target.id, f.list.pendingRecordingRevealId)
                assertEquals(target.id, f.screen.currentState()!!.list.expandedOwner?.recordingId)
                val snapshot = f.screen.saveScrollPosition()
                assertNull("Automatic viewport intent belongs only to this screen lifetime", snapshot.pendingRecordingRevealId)
                assertEquals(f.list.savePosition(), snapshot.listPosition)
                f.assertNoTransport()
                roundTrip(snapshot)
            }
            // For completion also model a replacement reusing the old ID: no key may jump to it.
            val recreatedRows = if (position == 2_000) rows.dropLast(1) + target.copy(fileIdentity = "replacement") else rows
            Fixture(recreatedRows, playing(target).copy(playing = false, wantsPlayback = false,
                positionMillis = position), saved).use { recreated ->
                recreated.publish()
                assertNull(recreated.screen.currentState()!!.list.expandedOwner)
                assertNull(recreated.list.pendingRecordingRevealId)
                assertNull(recreated.screen.saveScrollPosition().pendingRecordingRevealId)
                assertEquals(saved.listPosition, recreated.screen.saveScrollPosition().listPosition)
                recreated.assertNoTransport()
            }
        }
    }

    @Test fun explicitCompletionAfterAutomaticRevealPersistsIncludingSameIdSupersession() {
        for (requested in listOf(target, rows.first())) {
            val saved = Fixture(rows, playing(target)).use { f ->
                f.publishAutomatic(target)
                assertEquals(target.id, f.list.pendingRecordingRevealId)
                assertNull(f.screen.saveScrollPosition().pendingRecordingRevealId)
                f.completeExplicitReveal(requested.id)
                assertEquals(requested.id, f.list.pendingRecordingRevealId)
                assertEquals(requested.id, f.screen.saveScrollPosition().pendingRecordingRevealId)
                assertEquals(f.list.savePosition(), f.screen.saveScrollPosition().listPosition)
                f.assertNoTransport()
                roundTrip(f.screen.saveScrollPosition())
            }
            Fixture(rows, RecordingPlaybackState(), saved).use { recreated ->
                recreated.publish()
                assertNull(recreated.screen.currentState()!!.list.expandedOwner)
                assertEquals("Explicit reveal survives reconciliation without replaying transport",
                    requested.id, recreated.list.pendingRecordingRevealId)
                assertEquals(requested.id, recreated.screen.saveScrollPosition().pendingRecordingRevealId)
                recreated.assertNoTransport()
            }
        }
    }

    @Test fun cancellingAutomaticRevealPreservesSubsequentNumericSavedPosition() {
        val numeric = NativeListSavedPosition(7, 23)
        val saved = Fixture(rows, playing(target)).use { f ->
            f.publishAutomatic(target)
            assertEquals(target.id, f.list.pendingRecordingRevealId)
            f.list.cancelPendingRecordingReveal()
            f.list.restorePosition(numeric)
            assertNull(f.list.pendingRecordingRevealId)
            assertEquals(numeric, f.screen.saveScrollPosition().listPosition)
            assertNull(f.screen.saveScrollPosition().pendingRecordingRevealId)
            f.assertNoTransport()
            roundTrip(f.screen.saveScrollPosition())
        }
        Fixture(rows, RecordingPlaybackState(), saved).use { recreated ->
            recreated.publish()
            assertEquals(numeric, recreated.screen.saveScrollPosition().listPosition)
            assertNull(recreated.list.pendingRecordingRevealId)
            recreated.assertNoTransport()
        }
    }

    private fun playing(recording: RecordingEntity) = RecordingPlaybackState(recording = recording,
        prepared = true, playing = true, wantsPlayback = true, durationMillis = 2_000)

    private fun roundTrip(position: NativeLibrarySavedScrollPosition): NativeLibrarySavedScrollPosition =
        checkNotNull(NativeMainScreenSavedState.fromBundle(NativeMainScreenSavedState(libraryPosition = position)
            .toBundle())?.libraryPosition).also { assertEquals(position, it) }

    private class Fixture(
        rows: List<RecordingEntity>,
        playback: RecordingPlaybackState,
        restored: NativeLibrarySavedScrollPosition? = null,
    ) : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val root = FrameLayout(context).apply { suppressLayout(true) }
        private val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        private val frames = AndroidUiFrameClock(Choreographer.getInstance())
        // Same parked-side-effect fixture as NativeLibraryInfoWheelTest. Handler publication is
        // real; repository, playback and animation coroutines are never admitted.
        private val scope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        } + frames + object : MotionDurationScale { override val scaleFactor = 0f })
        private val transportBefore = RecordingPlayback.state.value
        private val requestCounterBefore = read(RecordingPlayback, "nextRequest")
        val screen = ReverbLibraryScreenContent(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            NativeLibraryScreenInput(NativeLibraryInput(true, rows, NativeLibraryCallbacks()),
                false, true, LibraryScaffoldInsets(), {}, {}, {}, {}),
            lifecycleOwner, OnBackPressedDispatcher(), scope, frames, { 0f }, null, root,
            object : NativeVerticalNestedHost {
                override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
                override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
                override suspend fun preFling(availableY: Float) = 0f
                override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
            }, {}, {}, restoredScrollPosition = restored)
        val list: ReverbLibraryListView get() = read(screen, "list") as ReverbLibraryListView

        init {
            // Seed the completed owner hydration boundary, without running storage or replacing
            // Screen.publish/saveScrollPosition. Global playback state remains untouched.
            val type = NativeLibraryController::class.java
            type.getDeclaredField("playbackState").apply { isAccessible = true }
                .set(screen.owner, MutableStateFlow(playback))
            type.getDeclaredField("openingPlayback").apply { isAccessible = true }.set(screen.owner, null)
            type.getDeclaredField("playingRecording").apply { isAccessible = true }
                .set(screen.owner, playback.recording.takeIf { playback.playing })
            type.getDeclaredMethod("setHasLoaded", java.lang.Boolean.TYPE).apply { isAccessible = true }
                .invoke(screen.owner, true)
            type.getDeclaredField("saveJobsReadiness").apply { isAccessible = true }
                .set(screen.owner, DurableSaveJobs.Readiness(loaded = true))
            root.addView(screen, FrameLayout.LayoutParams(-1, -1))
            context.setContentView(root)
            list.restorePosition(NativeListSavedPosition(0, 0), cancelRecordingReveal = false)
        }

        fun publishAutomatic(recording: RecordingEntity) {
            // Result of automatic restoration: a spent row grant and a reveal awaiting the real
            // Screen publication, where takePlaybackRestoreReveal still validates live playback.
            val owner = InlinePlaybackRowOwner(recording.id, automaticRestore = true).apply { consumeInitialAutoStart() }
            NativeLibraryController::class.java.getDeclaredMethod("setExpandedRecordingOwner", InlinePlaybackRowOwner::class.java)
                .apply { isAccessible = true }.invoke(screen.owner, owner)
            NativeLibraryController::class.java.getDeclaredField("playbackRestoreReveal")
                .apply { isAccessible = true }.set(screen.owner, recording)
            publish()
        }

        fun completeExplicitReveal(id: String) {
            // Inject only the already-completed explicit request's visual result; no play command.
            val reveal = read(screen, "playbackReveal") as NativeRecordingRevealRequest
            assertTrue(reveal.complete(reveal.begin("explicit-$id"), id))
            screen.owner.dateFormattingChanged()
            publish()
        }

        fun publish() {
            screen.publishInitialPresentation()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Layout must remain held until after the saved-state boundary", 0, list.height)
        }

        fun assertNoTransport() {
            assertEquals(transportBefore, RecordingPlayback.state.value)
            assertEquals(requestCounterBefore, read(RecordingPlayback, "nextRequest"))
            assertFalse(RecordingPlayback.hasPendingStart())
            assertNull(shadowOf(context.applicationContext as Application).nextStartedService)
        }

        override fun close() {
            try { screen.close() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }

        private fun read(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
    }
}
