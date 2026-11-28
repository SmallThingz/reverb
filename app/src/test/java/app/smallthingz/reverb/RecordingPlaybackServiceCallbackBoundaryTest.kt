package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.media.AudioTrack
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowPausedMessageQueue
import smallthingz.ui.util.closePreservingPrimaryFailure

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

    @Test fun consentLaunchStopsMatchingPendingStartAndPinnedTransportOnly() = withPlaybackService { media, _, _, _ ->
        val app = RuntimeEnvironment.getApplication()
        val audio = checkNotNull(app.getSystemService(AudioManager::class.java))
        Shadows.shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        media.invokePreparedListener()
        val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
        val selected = checkNotNull(RecordingPlayback.state.value.recording)
        assertTrue(RecordingPlayback.state.value.playing)
        assertTrue(media.isReallyPlaying)
        assertTrue(RecordingPlayback.play(app, selected))
        val heldStart = checkNotNull(Shadows.shadowOf(app).nextStartedService)
        val sender = android.app.PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE).intentSender
        val launched = mutableListOf<RecordingEntity>()
        fun prompt(target: RecordingEntity, onLaunch: () -> Unit) {
            val proof = PendingDeletionIntent(target.id, target.sizeBytes, "01".repeat(32), false)
            RecordingDeletionConsentLaunch("prompt", sender, listOf(RecordingDeletionConsentTarget(target, proof)))
                .launchAfterStoppingPlayback {
                    assertSame(sender, it)
                    launched += target
                    onLaunch()
                }
        }
        val replacement = selected.copy(fileIdentity = selected.fileIdentity + "-replacement")
        prompt(replacement) {
            assertTrue(RecordingPlayback.hasPendingStart())
            assertTrue(service.ownsRecording(selected))
            assertTrue(media.isReallyPlaying)
        }
        prompt(selected) {
            assertFalse(RecordingPlayback.hasPendingStart())
            assertFalse(service.ownsRecording(selected))
            assertNull(readField(service, "player"))
            assertNull(RecordingPlayback.state.value.recording)
        }
        service.onStartCommand(heldStart, 0, 2)
        ShadowLooper.idleMainLooper()
        assertFalse(service.ownsRecording(selected))
        assertNull(RecordingPlayback.state.value.recording)
        assertEquals(listOf(replacement, selected), launched)
    }

    @Test fun currentSessionPauseCancelsHeldPlay() = heldPlayCancelledByControl(stop = false)

    @Test fun currentSessionStopCancelsHeldPlay() = heldPlayCancelledByControl(stop = true)

    @Test fun currentSessionPauseCancelsHeldPlayAcrossVerifiedRename() =
        heldPlayCancelledByControl(stop = false, rename = true)

    @Test fun currentNotificationPauseCancelsHeldPlay() =
        heldPlayCancelledByControl(stop = false, notification = true)

    @Test fun currentNotificationStopCancelsHeldPlay() =
        heldPlayCancelledByControl(stop = true, notification = true)

    private fun heldPlayCancelledByControl(stop: Boolean, rename: Boolean = false, notification: Boolean = false) =
        withPlaybackService { media, handler, callback, _ ->
            val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
            val action = if (notification) notificationTerminalIntent(service, stop) else null
            callback.onPause()
            media.invokePreparedListener()
            val prepared = RecordingPlayback.state.value
            val original = checkNotNull(prepared.recording)
            val session = readField(service, "session") as MediaSession
            val player = readField(service, "player") as MediaPlayer
            assertTrue(prepared.prepared)
            assertFalse(prepared.wantsPlayback)

            val heldStart = holdSessionPlay(handler, callback)
            val expected = if (rename) original.copy(id = original.id + ".renamed", displayName = "renamed.wav")
                else original
            try {
                if (rename) {
                    // Exercise the verified rename rebind while the prepared stream and session are pinned.
                    RecordingPlayback.renamed(original, expected)
                    assertSame(session, readField(service, "session"))
                    assertEquals(expected, RecordingPlayback.state.value.recording)
                    assertEquals(prepared.generation, RecordingPlayback.state.value.generation)
                    val request = readField(RecordingPlayback, "pending") as RecordingPlayback.Start
                    assertEquals(expected, request.recording)
                }
                assertTrue(handler.post {
                    if (action != null) service.onStartCommand(action, 0, 2)
                    else if (stop) callback.onStop() else callback.onPause()
                })
                ShadowLooper.idleMainLooper()
                val pendingAtDelivery = RecordingPlayback.hasPendingStart()

                service.onStartCommand(heldStart, 0, 3)
                ShadowLooper.idleMainLooper()

                val settled = RecordingPlayback.state.value
                assertFalse(settled.playing)
                assertFalse(settled.wantsPlayback)
                assertFalse(media.isReallyPlaying)
                assertFalse("The terminal command must retire its held start before delivery", pendingAtDelivery)
                assertFalse(RecordingPlayback.hasPendingStart())
                if (stop) {
                    assertNull(settled.recording)
                    assertNull(readField(service, "session"))
                    assertNull(readField(service, "player"))
                    assertNull(readField(service, "opening"))
                } else {
                    assertTrue(settled.prepared)
                    assertEquals(expected, settled.recording)
                    assertEquals(prepared.generation, settled.generation)
                    assertSame(session, readField(service, "session"))
                    assertSame(player, readField(service, "player"))
                }
            } finally {
                if (rename) RecordingPlayback.stop(expected)
            }
        }

    @Test fun currentSessionStopPreservesHeldPlayForAnotherRecording() =
        terminalControlPreservesHeldReplacement(stop = true)

    @Test fun currentNotificationPausePreservesHeldPlayForAnotherRecording() =
        terminalControlPreservesHeldReplacement(stop = false, notification = true)

    @Test fun currentNotificationStopPreservesHeldPlayForAnotherRecording() =
        terminalControlPreservesHeldReplacement(stop = true, notification = true)

    private fun terminalControlPreservesHeldReplacement(stop: Boolean, notification: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        val secondFile = File.createTempFile("session-pending-replacement-", ".wav", app.cacheDir)
        withPlaybackService(additionalSource = secondFile) { media, handler, callback, _ ->
            val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
            val action = if (notification) notificationTerminalIntent(service, stop) else null
            callback.onPause()
            media.invokePreparedListener()
            val prepared = RecordingPlayback.state.value
            val first = checkNotNull(prepared.recording)
            assertTrue(prepared.prepared)
            val firstSession = readField(service, "session") as MediaSession
            holdSessionPlay(handler, callback)
            File(first.id).copyTo(secondFile, overwrite = true)
            PlaybackSessionOsShadow.targetPath = secondFile.absolutePath
            val second = fileRecording(secondFile)
            assertTrue(second.fileIdentity.isNotBlank())
            try {
                assertTrue(RecordingPlayback.play(app, second))
                val heldReplacement = checkNotNull(Shadows.shadowOf(app).nextStartedService)
                assertTrue(handler.post {
                    if (action != null) service.onStartCommand(action, 0, 2)
                    else callback.onStop()
                })
                ShadowLooper.idleMainLooper()
                if (stop) assertNull(readField(service, "session"))
                else assertSame(firstSession, readField(service, "session"))
                assertFalse(RecordingPlayback.state.value.wantsPlayback)
                assertTrue("A's terminal control must leave B's accepted start armed", RecordingPlayback.hasPendingStart())

                service.onStartCommand(heldReplacement, 0, 3)
                val adopted = RecordingPlayback.state.value
                assertEquals(second, adopted.recording)
                assertTrue(adopted.generation != prepared.generation)
                assertTrue(adopted.wantsPlayback)
                val secondSession = readField(service, "session")
                assertNotNull(secondSession)
                assertTrue(firstSession !== secondSession)
                assertFalse(RecordingPlayback.hasPendingStart())
                awaitPlayer(service)
            } finally {
                RecordingPlayback.stop(second)
            }
        }
    }

    private fun notificationTerminalIntent(service: RecordingPlaybackService, stop: Boolean): Intent {
        val manager = checkNotNull(service.getSystemService(NotificationManager::class.java))
        val notice = manager.activeNotifications.single { it.id == 7391 }.notification
        val label = service.getString(if (stop) R.string.recording_playback_stop else R.string.player_pause)
        val action = notice.actions.single { it.title.toString() == label }
        return Shadows.shadowOf(action.actionIntent).savedIntent
    }

    private fun holdSessionPlay(handler: Handler, callback: MediaSession.Callback): Intent {
        assertTrue(handler.post { callback.onPlay() })
        ShadowLooper.idleMainLooper()
        assertTrue(RecordingPlayback.hasPendingStart())
        return checkNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            .also { assertTrue(it.hasExtra(RecordingPlaybackService.EXTRA_REQUEST)) }
    }

    @Test fun queuedPausedSessionSeekSurvivesVerifiedRename() = queuedSeekAcrossRename(positionedStart = false)

    @Test fun queuedPositionedStartSurvivesVerifiedRename() = queuedSeekAcrossRename(positionedStart = true)

    private fun queuedSeekAcrossRename(positionedStart: Boolean) = withPlaybackService(prepareDelay = 120_000) { media, _, callback, main ->
        val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
        val idle = readField(service, "idleRelease") as Runnable
        val original = checkNotNull(RecordingPlayback.state.value.recording)
        val renamed = original.copy(id = original.id + ".renamed", displayName = "renamed.wav")
        val generation = RecordingPlayback.state.value.generation
        callback.onPause()
        media.setSeekDelay(-1)
        media.invokePreparedListener()
        assertTrue(main.hasCallbacks(idle))
        if (positionedStart) {
            val app = RuntimeEnvironment.getApplication()
            assertTrue(RecordingPlayback.play(app, original, 900))
            service.onStartCommand(checkNotNull(Shadows.shadowOf(app).nextStartedService), 0, 2)
        } else callback.onSeekTo(900)
        assertFalse(main.hasCallbacks(idle))
        assertFalse(RecordingPlayback.state.value.prepared)
        RecordingPlayback.renamed(original, renamed)
        assertEquals(generation, RecordingPlayback.state.value.generation)
        ShadowLooper.idleMainLooper()
        assertEquals(900, media.pendingSeek)
        val state = RecordingPlayback.state.value
        assertEquals(renamed, state.recording)
        assertTrue(state.prepared)
        assertFalse(state.playing)
        assertEquals(positionedStart, state.wantsPlayback)
        assertEquals(!positionedStart, main.hasCallbacks(idle))
        // The old FILE path is still inadmissible for an externally supplied seek.
        RecordingPlayback.seek(original, 100, resume = false)
        assertEquals(state, RecordingPlayback.state.value)
        if (!positionedStart) {
            media.invokeSeekCompleteListener()
            assertEquals(900, RecordingPlayback.state.value.positionMillis)
            ShadowLooper.idleMainLooper(60_000L, TimeUnit.MILLISECONDS)
            assertFalse(service.ownsRecording(renamed))
            assertEquals(renamed, RecordingPlayback.state.value.recording)
            assertEquals(900, RecordingPlayback.state.value.positionMillis)
            assertFalse(main.hasCallbacks(idle))
        }
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
    fun scrubAuthorityCarriesOnlyTheMatchingPreparedPlayerSession() {
        ShadowMediaPlayer.setCreateListener { player, _ -> player.audioSessionId = 273 }
        withPlaybackService(startPosition = 300) { media, _, _, _ ->
            val row = checkNotNull(RecordingPlayback.state.value.recording)
            assertNull(RecordingPlayback.acquireScrub(row))
            media.setSeekDelay(-1)
            media.invokePreparedListener()
            val authority = checkNotNull(RecordingPlayback.acquireScrub(row))
            assertEquals(273, authority.playbackAudioSessionId)
            assertNull(RecordingPlayback.acquireScrub(row.copy(fileIdentity = row.fileIdentity + "-different")))

            RecordingPlayback.pause(row)
            assertFalse(authority.active)
            val paused = checkNotNull(RecordingPlayback.acquireScrub(row))
            assertEquals(273, paused.playbackAudioSessionId)
            RecordingPlayback.stop(row)
            assertFalse(paused.active)
            assertNull(RecordingPlayback.acquireScrub(row))
        }
    }

    @Test fun pausedScrubCrossesOriginalIdleDeadlineAndCommitsSeek() =
        withPlaybackService(prepareDelay = 180_000) { media, _, callback, _ ->
            val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
            val session = readField(service, "session") as MediaSession
            callback.onPause()
            media.setSeekDelay(-1)
            media.invokePreparedListener()
            val paused = RecordingPlayback.state.value
            val row = checkNotNull(paused.recording)
            assertTrue(paused.prepared)
            assertFalse(paused.wantsPlayback)

            ShadowLooper.idleMainLooper(59_999L, TimeUnit.MILLISECONDS)
            val scrub = checkNotNull(RecordingPlayback.acquireScrub(row))
            assertFalse(scrub.resumePlayback)
            ShadowLooper.idleMainLooper(1L, TimeUnit.MILLISECONDS)
            assertTrue(RecordingPlayback.isScrubCurrent(row, scrub))
            assertEquals(paused.generation, RecordingPlayback.state.value.generation)
            assertSame(session, readField(service, "session"))

            RecordingPlayback.finishScrub(scrub)
            RecordingPlayback.seek(row, 900, resume = false)
            assertEquals(900, media.pendingSeek)
            media.invokeSeekCompleteListener()
            assertEquals(900, RecordingPlayback.state.value.positionMillis)
            assertFalse(media.isReallyPlaying)

            ShadowLooper.idleMainLooper(60_000L, TimeUnit.MILLISECONDS)
            assertFalse(service.ownsRecording(row))
            assertNull(readField(service, "session"))
            assertEquals(row, RecordingPlayback.state.value.recording)
            assertEquals(900, RecordingPlayback.state.value.positionMillis)
        }

    @Test fun suspendedPlayingScrubCrossesIdleDeadlineAndResumesOnRelease() =
        withPlaybackService(startPosition = 300, prepareDelay = 180_000) { media, _, _, _ ->
            val audio = checkNotNull(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
            Shadows.shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            media.setSeekDelay(-1)
            media.invokePreparedListener()
            media.invokeSeekCompleteListener()
            ShadowLooper.idleMainLooper()
            assertTrue(media.isReallyPlaying)
            val playing = RecordingPlayback.state.value
            val row = checkNotNull(playing.recording)
            val scrub = checkNotNull(RecordingPlayback.acquireScrub(row))
            assertTrue(scrub.resumePlayback)

            RecordingPlayback.suspendForGesture(row, scrub)
            assertTrue(scrub.playbackSuspended)
            assertFalse(media.isReallyPlaying)
            ShadowLooper.idleMainLooper(60_000L, TimeUnit.MILLISECONDS)
            assertTrue(RecordingPlayback.isScrubCurrent(row, scrub))
            assertEquals(playing.generation, RecordingPlayback.state.value.generation)
            assertFalse(media.isReallyPlaying)

            RecordingPlayback.finishScrub(scrub)
            RecordingPlayback.seek(row, 1_100, resume = scrub.resumePlayback)
            assertEquals(1_100, media.pendingSeek)
            assertFalse(media.isReallyPlaying)
            media.invokeSeekCompleteListener()
            ShadowLooper.idleMainLooper()
            assertTrue(media.isReallyPlaying)
            assertEquals(1_100, RecordingPlayback.state.value.positionMillis)
        }

    @Test fun deferredIdleDeadlineRetiresInactiveScrubWithoutTransportCommand() =
        withPlaybackService(prepareDelay = 240_000) { media, _, callback, _ ->
            val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
            callback.onPause()
            media.invokePreparedListener()
            val row = checkNotNull(RecordingPlayback.state.value.recording)
            val scrub = checkNotNull(RecordingPlayback.acquireScrub(row))
            ShadowLooper.idleMainLooper(60_000L, TimeUnit.MILLISECONDS)
            assertTrue(RecordingPlayback.isScrubCurrent(row, scrub))

            RecordingPlayback.finishScrub(scrub)
            ShadowLooper.idleMainLooper(60_000L, TimeUnit.MILLISECONDS)
            assertFalse(service.ownsRecording(row))
            assertNull(readField(service, "player"))
            assertNull(readField(service, "session"))
            val retained = checkNotNull(RecordingPlayback.acquireScrub(row))
            assertTrue(retained.key.retainedIdle)
            RecordingPlayback.finishScrub(retained)
        }

    @Test
    @Config(shadows = [JournalHostIoShadow::class, ShuttleEndpointAudioTrackShadow::class,
        ServiceReplacementTrackBuilderShadow::class])
    fun serviceReplacementRevokesHeldSharedSessionBeforeNextRecordingScrub() {
        val app = RuntimeEnvironment.getApplication()
        val secondFile = File.createTempFile("service-builder-replacement-", ".wav", app.cacheDir)
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        ServiceStateOsShadow.fixtureRoot = app.cacheDir.toPath().toAbsolutePath().normalize()
        ShuttleEndpointAudioTrackShadow.reset()
        ShuttleEndpointAudioTrackShadow.allowFirstWrite.countDown()
        ServiceReplacementTrackBuilderShadow.reset()
        ShadowAudioTrack.setMinBufferSize(256)
        val players = CopyOnWriteArrayList<MediaPlayer>()
        ShadowMediaPlayer.setCreateListener { player, _ ->
            player.audioSessionId = if (players.isEmpty()) 273 else 487
            players += player
        }
        var previewWorkersStopped = true
        var serviceJob: Job? = null
        var primary: Throwable? = null
        try {
            withPlaybackService(additionalSource = secondFile,
                additionalOwnersStopped = { previewWorkersStopped }) { firstMedia, _, _, _ ->
                val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
                serviceJob = checkNotNull((readField(service, "scope") as CoroutineScope).coroutineContext[Job])
                val audio = checkNotNull(app.getSystemService(AudioManager::class.java))
                Shadows.shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                firstMedia.invokePreparedListener()
                assertTrue(firstMedia.isReallyPlaying)
                val first = checkNotNull(RecordingPlayback.state.value.recording)
                val firstAuthority = checkNotNull(RecordingPlayback.acquireScrub(first))
                assertEquals(273, players.single().audioSessionId)
                assertEquals(players.single().audioSessionId, firstAuthority.playbackAudioSessionId)
                assertTrue(firstAuthority.active)
                val failures = ConcurrentLinkedQueue<Throwable>()
                val firstHandoffs = AtomicInteger()
                val secondHandoffs = AtomicInteger()
                val clock = AtomicLong(1_000_000_000L)
                val preview = TimelineAudioPreviewController(onTrackReleaseFailure = { failures += it },
                    shuttleNowNanos = clock::get)
                previewWorkersStopped = false
                withOwnedResource(preview, release = { owner ->
                    // Always release the builder gate before joining; no live worker sees reset shadows/files.
                    ServiceReplacementTrackBuilderShadow.allowReturn.countDown()
                    owner.close()
                    for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
                        val worker = readField(owner, name) as ExecutorService
                        assertTrue("$name must terminate before fixture retirement",
                            worker.awaitTermination(10, TimeUnit.SECONDS))
                    }
                    previewWorkersStopped = true
                    ShadowLooper.idleMainLooper()
                    assertTrue(failures.toString(), failures.isEmpty())
                    ServiceReplacementTrackBuilderShadow.built.forEach { track ->
                        assertEquals("Every native owner has one terminal release", 1,
                            Shadow.extract<ShuttleEndpointAudioTrackShadow>(track).releaseCalls)
                    }
                }) { owner ->
                    fun await(label: String, ready: () -> Boolean) {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                        while (!ready() && System.nanoTime() < deadline) {
                            ShadowLooper.idleMainLooper()
                            failures.peek()?.let { throw it }
                            Thread.sleep(1)
                        }
                        assertTrue(label, ready())
                    }
                    owner.startRecordingShuttle(app, first, .100, 1f,
                        onStarted = {
                            firstHandoffs.incrementAndGet()
                            RecordingPlayback.suspendForGesture(first, firstAuthority)
                        }, onFailureAfterStart = { failures += AssertionError("Stale A failure callback") },
                        onSourceCloseFailure = { failures += it }, fromSeconds = .021, untilSeconds = .300,
                        ordinaryAuthority = firstAuthority)
                    await("A native builder held before returning") {
                        ServiceReplacementTrackBuilderShadow.entered.count == 0L
                    }
                    val oldTrack = ServiceReplacementTrackBuilderShadow.built.single()
                    val oldSink = Shadow.extract<ShuttleEndpointAudioTrackShadow>(oldTrack)
                    assertEquals(players.single().audioSessionId, oldTrack.audioSessionId)
                    assertEquals(0, oldSink.acceptedWrites)
                    assertEquals(0, oldSink.playCalls)
                    assertEquals(0, firstHandoffs.get())
                    assertFalse(firstAuthority.playbackSuspended)
                    assertTrue(firstMedia.isReallyPlaying)
                    val previewEpoch = (readField(owner, "generation") as AtomicLong).get()

                    File(first.id).copyTo(secondFile, overwrite = true)
                    val second = fileRecording(secondFile)
                    assertTrue(RecordingPlayback.play(app, second))
                    assertFalse("Actual facade replacement must revoke A before service delivery", firstAuthority.active)
                    service.onStartCommand(checkNotNull(Shadows.shadowOf(app).nextStartedService), 0, 2)
                    await("B's actual MediaPlayer is created without advancing delayed media timers") {
                        (readField(service, "player") as? MediaPlayer)?.let { it !== players[0] } == true
                    }
                    val secondPlayer = readField(service, "player") as MediaPlayer
                    val secondMedia = Shadow.extract<ShadowMediaPlayer>(secondPlayer)
                    secondMedia.invokePreparedListener()
                    assertEquals(2, players.size)
                    assertSame(players[1], secondPlayer)
                    assertTrue(secondPlayer.audioSessionId > 0)
                    assertTrue(secondPlayer.audioSessionId != oldTrack.audioSessionId)
                    assertTrue(secondMedia.isReallyPlaying)
                    val secondTransport = RecordingPlayback.state.value
                    assertEquals(second, secondTransport.recording)
                    assertTrue(secondTransport.prepared && secondTransport.playing && secondTransport.wantsPlayback)
                    assertEquals(1L, ServiceReplacementTrackBuilderShadow.allowReturn.count)

                    // Do NOT start B's preview or stop/update A's controller yet: either would hide
                    // broken facade revocation by advancing the independent preview generation.
                    ServiceReplacementTrackBuilderShadow.allowReturn.countDown()
                    val worker = readField(owner, "executor") as ExecutorService
                    val oldWorkerFinished = worker.submit { }
                    await("A worker fully returns after its held native build") { oldWorkerFinished.isDone }
                    oldWorkerFinished.get(1, TimeUnit.SECONDS)
                    assertSame("A build must return its exact owner normally, not timeout-clean it",
                        oldTrack, ServiceReplacementTrackBuilderShadow.returned.single())
                    await("A native owner released") { oldSink.released }
                    ShadowLooper.idleMainLooper()
                    assertEquals(previewEpoch, (readField(owner, "generation") as AtomicLong).get())
                    assertEquals(0, oldSink.acceptedWrites)
                    assertEquals(0, oldSink.frameCount)
                    assertEquals(0, oldSink.playCalls)
                    assertEquals(0, firstHandoffs.get())
                    assertEquals(1, oldSink.releaseCalls)
                    assertFalse(firstAuthority.playbackSuspended)
                    assertEquals(1, ServiceReplacementTrackBuilderShadow.built.size)
                    assertEquals(secondTransport, RecordingPlayback.state.value)
                    assertSame(secondPlayer, readField(service, "player"))
                    assertTrue(secondMedia.isReallyPlaying)
                    assertFalse(RecordingPlayback.hasPendingStart())
                    assertTrue(failures.toString(), failures.isEmpty())

                    val secondAuthority = checkNotNull(RecordingPlayback.acquireScrub(second))
                    assertEquals(secondPlayer.audioSessionId, secondAuthority.playbackAudioSessionId)
                    owner.startRecordingShuttle(app, second, .100, 1f,
                        onStarted = {
                            secondHandoffs.incrementAndGet()
                            RecordingPlayback.suspendForGesture(second, secondAuthority)
                        }, onFailureAfterStart = { failures += AssertionError("B scrub failure callback") },
                        onSourceCloseFailure = { failures += it }, fromSeconds = .021, untilSeconds = .300,
                        ordinaryAuthority = secondAuthority)
                    await("Only B's shared session reaches playing after the actual handoff") {
                        secondHandoffs.get() == 1 && ServiceReplacementTrackBuilderShadow.built.size == 2 &&
                            Shadow.extract<ShuttleEndpointAudioTrackShadow>(ServiceReplacementTrackBuilderShadow.built[1]).playing
                    }
                    assertEquals(listOf(273, 487), ServiceReplacementTrackBuilderShadow.built.map { it.audioSessionId })
                    val secondSink = Shadow.extract<ShuttleEndpointAudioTrackShadow>(ServiceReplacementTrackBuilderShadow.built[1])
                    assertTrue(secondSink.acceptedWrites > 0)
                    assertEquals(1, secondSink.playCalls)
                    assertEquals(1, secondHandoffs.get())
                    assertEquals(0, firstHandoffs.get())
                    assertTrue(secondAuthority.playbackSuspended)
                    assertTrue(RecordingPlayback.isScrubCurrent(second, secondAuthority))
                    assertEquals(second, RecordingPlayback.state.value.recording)
                    assertSame(secondPlayer, readField(service, "player"))
                    assertFalse(secondMedia.isReallyPlaying)
                    RecordingPlayback.stop(second)
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            ServiceReplacementTrackBuilderShadow.allowReturn.countDown()
            // The existing service fixture separately joins its coroutine owner before deleting WAVs.
            if (previewWorkersStopped && serviceJob?.isCompleted != false) {
                val failure = closePreservingPrimaryFailure(primary) {
                    try { JournalHostIoShadow.finishFixture()?.let { throw it } }
                    finally { ServiceStateOsShadow.fixtureRoot = null }
                }
                if (primary == null && failure != null) throw failure
            }
        }
    }

    @Test
    fun matchingScrubAcknowledgesSuppressedPendingSeekResume() =
        withPlaybackService(startPosition = 300) { media, _, _, _ ->
            val audio = checkNotNull(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
            Shadows.shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            media.setSeekDelay(-1)
            media.invokePreparedListener()

            val beforeHandoff = RecordingPlayback.state.value
            val recording = checkNotNull(beforeHandoff.recording)
            assertTrue(beforeHandoff.prepared)
            assertTrue(beforeHandoff.wantsPlayback)
            assertFalse(beforeHandoff.playing)
            assertFalse(media.isReallyPlaying)
            assertEquals(300, media.pendingSeek)

            val revoked = checkNotNull(RecordingPlayback.acquireScrub(recording))
            val authority = checkNotNull(RecordingPlayback.acquireScrub(recording))
            assertFalse(revoked.active)
            RecordingPlayback.suspendForGesture(recording, revoked)
            assertFalse(revoked.playbackSuspended)
            assertEquals(beforeHandoff, RecordingPlayback.state.value)

            assertTrue(authority.resumePlayback)
            assertFalse(authority.playbackSuspended)
            RecordingPlayback.suspendForGesture(recording, authority)
            assertTrue(authority.playbackSuspended)
            assertTrue(RecordingPlayback.isScrubCurrent(recording, authority))
            assertFalse(RecordingPlayback.state.value.wantsPlayback)
            assertEquals(300, media.pendingSeek)

            media.invokeSeekCompleteListener()
            ShadowLooper.idleMainLooper()
            assertEquals(-1, media.pendingSeek)
            assertEquals(300, media.currentPositionRaw)
            assertFalse("The accepted seek must finish without restarting playback during the scrub", media.isReallyPlaying)
            assertFalse(RecordingPlayback.state.value.playing)
            assertTrue(RecordingPlayback.isScrubCurrent(recording, authority))
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

    @Test
    @Config(shadows = [PlaybackMetadataShadow::class])
    fun replacingRecordingRetiresSessionTokenAndQueuedTransport() {
        val app = RuntimeEnvironment.getApplication()
        val secondFile = File.createTempFile("session-replacement-", ".wav", app.cacheDir)
        withPlaybackService(additionalSource = secondFile) { firstPlayer, firstHandler, firstCallback, _ ->
            val service = readField(RecordingPlayback, "service") as RecordingPlaybackService
            val notices = checkNotNull(service.getSystemService(NotificationManager::class.java))
            fun publishedToken(): MediaSession.Token = checkNotNull(notices.activeNotifications
                .single { it.id == 7391 }.notification.extras
                .getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))

            val first = checkNotNull(RecordingPlayback.state.value.recording)
            val firstGeneration = RecordingPlayback.state.value.generation
            val firstToken = publishedToken()
            firstCallback.onPause()
            firstPlayer.invokePreparedListener()
            assertEquals(firstToken, publishedToken())
            // A's accepted seek has already posted its service command when B is adopted.
            firstCallback.onSeekTo(1_300)
            File(first.id).copyTo(secondFile, overwrite = true)
            PlaybackSessionOsShadow.targetPath = secondFile.absolutePath
            val second = fileRecording(secondFile).copy(displayName = first.displayName)
            assertTrue(RecordingPlayback.play(app, second, 0))
            service.onStartCommand(checkNotNull(Shadows.shadowOf(app).nextStartedService), 0, 2)
            val adopted = RecordingPlayback.state.value
            assertTrue(adopted.generation != firstGeneration)
            assertEquals(second, adopted.recording)
            val secondSession = readField(service, "session") as MediaSession
            val secondToken = publishedToken()
            assertTrue("A retained notification must address a distinct session", firstToken != secondToken)
            assertEquals(secondSession.sessionToken, secondToken)
            val metadata = Shadow.extract<PlaybackMetadataShadow>(secondSession).updates.single()
            assertEquals(second.displayName, metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals(second.durationMillis, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))

            // Deliver a retained callback on its actual handler even if release removed older messages.
            var dispatched = false
            assertTrue(firstHandler.post {
                firstCallback.onPause()
                firstCallback.onPlay()
                firstCallback.onSeekTo(700)
                firstCallback.onStop()
                dispatched = true
            })
            ShadowLooper.idleMainLooper()
            assertTrue(dispatched)
            assertEquals(adopted, RecordingPlayback.state.value)
            assertFalse(RecordingPlayback.hasPendingStart())
            assertNull(Shadows.shadowOf(app).nextStartedService)

            val currentHandler = readField(secondSession, "mCallback") as Handler
            val current = readField(currentHandler, "mCallback") as MediaSession.Callback
            val player = Shadow.extract<ShadowMediaPlayer>(awaitPlayer(service))
            current.onPause()
            player.setSeekDelay(-1)
            player.invokePreparedListener()
            assertTrue(currentHandler.post { current.onSeekTo(450) })
            ShadowLooper.idleMainLooper()
            assertEquals(450, player.pendingSeek)
            assertEquals(second, RecordingPlayback.state.value.recording)
            assertEquals(secondToken, publishedToken())
            current.onStop()
            assertNull(RecordingPlayback.state.value.recording)
        }
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
        prepareDelay: Int = 60_000,
        additionalSource: File? = null,
        additionalOwnersStopped: () -> Boolean = { true },
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
            ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, prepareDelay) }
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
            if (serviceJob?.isCompleted != false && additionalOwnersStopped()) {
                cleanup { ShadowLooper.idleMainLooper() }
                cleanup { ShadowMediaPlayer.resetStaticState() }
                cleanup { PlaybackSessionOsShadow.targetPath = "" }
                cleanup { additionalSource?.let { check(!it.exists() || it.delete()) } }
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

/** Hold the first actual platform build, without advancing/replacing the preview controller. */
@Implements(AudioTrack.Builder::class)
class ServiceReplacementTrackBuilderShadow {
    @RealObject lateinit var builder: AudioTrack.Builder

    @Implementation fun build(): AudioTrack {
        val track = Shadow.directlyOn<AudioTrack, AudioTrack.Builder>(
            builder, AudioTrack.Builder::class.java, "build")
        built += track
        if (built.size == 1) {
            entered.countDown()
            try {
                check(allowReturn.await(15, TimeUnit.SECONDS)) { "A held builder was not released" }
            } catch (failure: Throwable) {
                throw checkNotNull(closePreservingPrimaryFailure(failure) { track.release() })
            }
        }
        returned += track
        return track
    }

    companion object {
        val built = CopyOnWriteArrayList<AudioTrack>()
        val returned = CopyOnWriteArrayList<AudioTrack>()
        lateinit var entered: CountDownLatch
        lateinit var allowReturn: CountDownLatch
        fun reset() {
            built.clear()
            returned.clear()
            entered = CountDownLatch(1)
            allowReturn = CountDownLatch(1)
        }
    }
}
