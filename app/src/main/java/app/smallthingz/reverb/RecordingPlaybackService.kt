package app.smallthingz.reverb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved-file transport only. Capture, durable saves and previews retain their own owners. */
class RecordingPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ownership = RecordingPlaybackOwnership(RecordingPlayback::nextGeneration)
    private val instanceToken = java.util.UUID.randomUUID().toString()
    private var recording: RecordingEntity? = null
    private var player: MediaPlayer? = null
    private var source: InlinePlayerMediaSource? = null
    private var opening: Job? = null
    private var quietStart: Job? = null
    private var quietStartPending = false
    private var prepared = false
    private var playing = false
    private var position = 0
    private var duration = 0
    private var foreground = false
    private var destroyed = false
    private var retainedPause = false
    private var externalCommandPending = false
    private val idleRelease = Runnable {
        if (ownership.idleRetirementAllowed && !playing) finishPlayback(retainPosition = true)
    }
    private var focus: AudioFocusRequest? = null
    private lateinit var audio: AudioManager
    private lateinit var session: MediaSession
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseCurrent()
        }
    }
    private var noisyRegistered = false
    private val progress = object : Runnable {
        override fun run() {
            if (destroyed || !playing || !RecordingPlayback.positionObserved()) return
            guarded {
                if (!ownership.seeks.pending) position = player?.currentPosition ?: position
                publish(updateNotification = false)
            }
            if (playing && RecordingPlayback.positionObserved()) main.postDelayed(this, 250L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "Reverb saved recording").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { recording?.let { RecordingPlayback.play(this@RecordingPlaybackService, it) } }
                override fun onPause() = pauseCurrent()
                override fun onStop() = finishPlayback()
                override fun onSeekTo(pos: Long) {
                    recording?.let { selected ->
                        val resume = ownership.wantsPlayback
                        queueExternalTransport {
                            seekMatching(selected, pos.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), resume)
                        }
                    }
                }
            }, main)
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_playback_channel), NotificationManager.IMPORTANCE_LOW))
        RecordingPlayback.attach(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (destroyed) return START_NOT_STICKY
        guarded {
            val request = RecordingPlayback.takeStart(intent?.getLongExtra(EXTRA_REQUEST, -1L) ?: -1L)
            if (request != null) {
                // Android 15+ requires foreground ownership before the delayed prepared callback asks for focus.
                start(request.recording, request.positionMillis, promote = true)
            } else if (intent?.hasExtra(EXTRA_REQUEST) == true) {
                // A newer foreground start can supersede this token before Android delivers it.
                // Honor the foreground-start deadline even if a paused owner already exists.
                if (ownership.active || RecordingPlayback.hasPendingStart()) {
                    ensureForeground()
                    if (ownership.active && !ownership.wantsPlayback) pauseCurrent()
                } else stopSelfResult(startId)
            } else if (intent?.getStringExtra(EXTRA_INSTANCE) == instanceToken &&
                ownership.current(intent.getLongExtra(EXTRA_GENERATION, -1L))) {
                when (intent.action) {
                    ACTION_PAUSE -> pauseCurrent()
                    ACTION_STOP -> finishPlayback()
                    ACTION_PLAY -> { ensureForeground(); ownership.resume(); queueExternalTransport(::resume) }
                }
            } else if (!ownership.active && !RecordingPlayback.hasPendingStart()) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun start(next: RecordingEntity, requestedPosition: Int?, promote: Boolean = false) {
        retainedPause = false
        main.removeCallbacks(idleRelease)
        if (recording?.let { samePlaybackRecording(it, next) } == true && ownership.active) {
            if (promote) ensureForeground()
            ownership.resume()
            queueExternalTransport {
                if (requestedPosition != null) seekMatching(next, requestedPosition, true) else resume()
            }
            return
        }
        releaseTransport()
        recording = next
        position = requestedPosition?.coerceAtLeast(0) ?: 0
        duration = next.durationMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val token = ownership.replace()
        // Establish the exact token before rendering controls, still before opening any source.
        if (promote) ensureForeground()
        publish()
        opening = scope.launch {
            val opened = AtomicReference<InlinePlayerMediaSource?>(null)
            try {
                withContext(Dispatchers.IO) {
                    opened.set(openInlinePlayerMediaSource(applicationContext, next,
                        RecordingReadCleanup(::reportCleanup)))
                }
                if (!ownership.current(token) || destroyed) return@launch
                val pinned = opened.getAndSet(null) ?: return@launch
                source = pinned
                val created = configureOwnedResourceOrRelease(MediaPlayer(), { it.release() }) { media ->
                    media.setAudioAttributes(attributes)
                    media.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                    media.setOnPreparedListener { current ->
                        if (currentPlayer(token, current)) guarded {
                            prepared = true
                            duration = current.duration.coerceAtLeast(1)
                            position = position.coerceIn(0, duration)
                            if (externalCommandPending) {
                                // Preserve a retained start position without changing the newer command's intent.
                                if (position > 0) ownership.seeks.request(position, false)?.let { current.seekTo(it) }
                                publish()
                                return@guarded
                            }
                            if (position > 0) recording?.let { seekMatching(it, position, ownership.wantsPlayback) }
                            else if (ownership.wantsPlayback) resume()
                            else publish()
                        }
                    }
                    media.setOnSeekCompleteListener { current ->
                        if (currentPlayer(token, current) && ownership.seeks.pending) guarded {
                            val queued = ownership.seeks.completed()
                            if (queued != null) current.seekTo(queued)
                            else {
                                position = current.currentPosition.coerceIn(0, duration)
                                val shouldResume = ownership.seeks.resume && ownership.wantsPlayback
                                ownership.seeks.resume = false
                                if (shouldResume) resume() else publish()
                            }
                        }
                    }
                    media.setOnCompletionListener { current ->
                        if (currentPlayer(token, current) && playing) guarded {
                            position = duration
                            playing = false
                            if (externalCommandPending) {
                                main.removeCallbacks(progress)
                                publish()
                            } else pauseCurrent()
                        }
                    }
                    media.setOnErrorListener { current, what, extra ->
                        if (currentPlayer(token, current)) fail(IllegalStateException("Android media error: $what, $extra"))
                        true
                    }
                    media.setDataSource(pinned.descriptor)
                }
                player = created
                created.prepareAsync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ownership.current(token)) fail(error)
            } finally {
                cleanup { opened.getAndSet(null)?.close() }
            }
        }
    }

    private fun currentPlayer(token: Long, candidate: MediaPlayer) =
        !destroyed && ownership.current(token) && player === candidate

    internal fun ownsRecording(expected: RecordingEntity): Boolean = ownership.active &&
        recording?.let { samePlaybackRecording(it, expected) } == true

    internal fun positionObservationChanged() {
        main.removeCallbacks(progress)
        if (playing && RecordingPlayback.positionObserved()) main.post(progress)
    }

    private fun queueExternalTransport(command: () -> Unit) = guarded {
        quietStart?.cancel()
        quietStart = null
        quietStartPending = false
        main.removeCallbacks(idleRelease)
        val generation = ownership.generation
        val revision = ownership.beginExternalCommand()
        externalCommandPending = true
        // Publish revocation before the next Main turn: UI collectors retire their AudioTrack
        // generation before ordinary transport restarts. No UI callback is retained by the service.
        publish()
        check(main.post {
            if (!destroyed && ownership.currentGesture(generation, revision)) {
                externalCommandPending = false
                guarded(command)
            }
        }) { "Playback command dispatch was rejected" }
    }

    private fun resume() {
        if (externalCommandPending) return
        main.removeCallbacks(idleRelease)
        ownership.resume()
        if (!prepared || ownership.seeks.pending) {
            if (ownership.seeks.pending) ownership.seeks.resume = true
            publish()
            return
        }
        if (position >= duration) {
            recording?.let { seekMatching(it, 0, true) }
            return
        }
        ensureForeground()
        val selectedPlayer = player ?: return
        val generation = ownership.generation
        val revision = ownership.transportIntentRevision
        quietStart?.cancel()
        quietStartPending = true
        publish()
        quietStart = scope.launch {
            try {
                check(previewQuietCoordinator.awaitQuiet()) { "Previous audio preview could not be silenced" }
                if (!currentPlayer(generation, selectedPlayer) || !ownership.currentGesture(generation, revision) ||
                    !ownership.wantsPlayback || externalCommandPending || !prepared || ownership.seeks.pending) return@launch
                if (!acquireFocus()) {
                    pauseCurrent()
                    AppFeedbackCenter.post(getString(R.string.recording_playback_focus_denied), FeedbackTone.ERROR)
                    return@launch
                }
                quietStartPending = false
                selectedPlayer.start()
                playing = true
                positionObservationChanged()
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentPlayer(generation, selectedPlayer) && ownership.currentGesture(generation, revision)) fail(error)
            }
        }
    }

    private fun acquireFocus(): Boolean {
        if (focus != null) return true
        val token = ownership.generation
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (ownership.current(token) && focus === request && change != AudioManager.AUDIOFOCUS_GAIN) {
                    pauseCurrent()
                }
            }, main).build()
        focus = request
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) abandonFocus()
        return granted
    }

    private fun abandonFocus() {
        val owned = focus
        focus = null
        if (owned != null) cleanup { audio.abandonAudioFocusRequest(owned) }
    }

    internal fun pauseMatching(expected: RecordingEntity) {
        if (recording?.let { samePlaybackRecording(it, expected) } == true) pauseCurrent()
    }
    internal fun pauseCurrent() = guarded {
        externalCommandPending = false
        ownership.pause()
        pauseTransport(releaseFocus = true)
    }
    internal fun suspendForGestureMatching(expected: RecordingEntity, generation: Long, transportIntentRevision: Long) = guarded {
        if (!ownsRecording(expected) || !ownership.currentGesture(generation, transportIntentRevision)) return@guarded
        ownership.suspendForGesture()
        // The gesture AudioTrack owns audible playback; keep focus until it resumes or is cancelled.
        pauseTransport(releaseFocus = false)
    }
    private fun pauseTransport(releaseFocus: Boolean) {
        quietStart?.cancel()
        quietStart = null
        quietStartPending = false
        if (playing && prepared) {
            if (!ownership.seeks.pending) position = player?.currentPosition ?: position
            player?.pause()
        }
        playing = false
        main.removeCallbacks(progress)
        if (releaseFocus) abandonFocus()
        publish()
        demotePaused()
    }
    internal fun seekMatching(expected: RecordingEntity, target: Int, resume: Boolean) = guarded {
        if (recording?.let { samePlaybackRecording(it, expected) } != true) return@guarded
        quietStart?.cancel()
        quietStart = null
        quietStartPending = false
        if (resume) {
            main.removeCallbacks(idleRelease)
            ensureForeground()
            ownership.resume()
        } else {
            externalCommandPending = false
            ownership.pause()
        }
        position = target.coerceIn(0, duration.coerceAtLeast(1))
        if (prepared) {
            if (playing) player?.pause()
            playing = false
            main.removeCallbacks(progress)
            ownership.seeks.request(position, resume)?.let { player?.seekTo(it) }
        }
        if (!resume) abandonFocus()
        publish()
        if (!resume) demotePaused()
    }
    internal fun stopMatching(expected: RecordingEntity) {
        if (recording?.let { samePlaybackRecording(it, expected) } == true) finishPlayback()
    }
    internal fun renameMatching(expected: RecordingEntity, renamed: RecordingEntity) = guarded {
        if (recording?.let { samePlaybackRecording(it, expected) } != true) return@guarded
        if (!prepared && source == null) {
            val resume = ownership.wantsPlayback
            start(renamed, position)
            if (!resume) pauseCurrent()
            return@guarded
        }
        // Repository rename terminal proves selected-byte continuity; keep the pinned stream and position.
        recording = renamed
        publish()
    }

    private fun ensureForeground() {
        if (foreground) return
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        foreground = true
    }

    private fun demotePaused() {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
        }
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, 60_000L)
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(this, 0,
        Intent(this, RecordingPlaybackService::class.java).apply {
            this.action = action
            data = android.net.Uri.parse("reverb-playback://control/$instanceToken/${ownership.generation}/$action")
            putExtra(EXTRA_GENERATION, ownership.generation)
            putExtra(EXTRA_INSTANCE, instanceToken)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val transport = recordingPlaybackPhase(playing, ownership.wantsPlayback)
        val statusText = when (transport) {
            RecordingPlaybackPhase.PREPARING -> R.string.recording_playback_preparing
            RecordingPlaybackPhase.PLAYING -> R.string.recording_playback_playing
            RecordingPlaybackPhase.PAUSED -> R.string.recording_playback_paused
        }
        val open = recording?.let { RecordingPlaybackRequest.intentFor(this, it) } ?: Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_saved)
            .setContentTitle(recording?.displayName ?: getString(R.string.app_name))
            .setContentText(getString(statusText))
            .setContentIntent(PendingIntent.getActivity(this, 7391, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOnlyAlertOnce(true).setOngoing(transport.offersPause).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(if (transport.offersPause) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (transport.offersPause) R.string.player_pause else R.string.player_play),
                actionIntent(if (transport.offersPause) ACTION_PAUSE else ACTION_PLAY))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.recording_playback_stop),
                actionIntent(ACTION_STOP))
            .setDeleteIntent(actionIntent(ACTION_STOP))
        // Platform MediaStyle avoids adding a second media framework to the app.
        return Notification.Builder.recoverBuilder(this, builder.build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun publish(updateNotification: Boolean = true) {
        if (destroyed) return
        RecordingPlayback.publish(this, RecordingPlaybackState(recording, prepared && !externalCommandPending && !quietStartPending, playing,
            position, duration, ownership.generation, wantsPlayback = ownership.wantsPlayback,
            transportIntentRevision = ownership.transportIntentRevision))
        if (!updateNotification) return
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, recording?.displayName.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong()).build())
        session.setPlaybackState(PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else if (ownership.wantsPlayback) PlaybackState.STATE_BUFFERING
                else PlaybackState.STATE_PAUSED, position.toLong(), if (playing) 1f else 0f).build())
        if (updateNotification && ownership.active) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun releaseTransport() {
        quietStart?.cancel()
        quietStart = null
        quietStartPending = false
        externalCommandPending = false
        main.removeCallbacks(idleRelease)
        ownership.retire()
        opening?.cancel()
        opening = null
        main.removeCallbacks(progress)
        val ownedPlayer = player
        player = null
        val ownedSource = source
        source = null
        prepared = false
        playing = false
        cleanup { ownedPlayer?.release() }
        cleanup { ownedSource?.close() }
        abandonFocus()
    }
    private fun finishPlayback(retainPosition: Boolean = false) {
        val retained = if (retainPosition) RecordingPlaybackState(recording, false, false,
            position, duration, ownership.generation) else null
        retainedPause = retainPosition
        releaseTransport()
        recording = null
        position = 0
        duration = 0
        if (!destroyed) cleanup {
            publish()
            if (retained != null) RecordingPlayback.publish(this, retained)
        }
        cleanup { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
        cleanup { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        if (!RecordingPlayback.hasPendingStart()) stopSelf()
    }
    private fun fail(error: Throwable) {
        finishPlayback()
        reportPlaybackFailure(applicationContext, error)
    }
    private inline fun guarded(block: () -> Unit) {
        if (destroyed) return
        try { block() } catch (error: Exception) { fail(error) }
    }
    private fun cleanup(block: () -> Unit) {
        runCleanupReportingThrowable(block, ::reportCleanup)
    }
    private fun reportCleanup(error: Throwable) {
        RecordingIncidentStore.recordUnexpectedErrorInBackground(applicationContext, "Saved recording playback cleanup", error)
        reportPlaybackFailure(applicationContext, error)
    }
    override fun onDestroy() {
        if (destroyed) return
        if (retainedPause) releaseTransport() else finishPlayback()
        destroyed = true
        RecordingPlayback.detach(this)
        scope.cancel()
        if (noisyRegistered) { noisyRegistered = false; cleanup { unregisterReceiver(noisy) } }
        cleanup { session.release() }
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_REQUEST = "playback_request"
        private const val EXTRA_GENERATION = "playback_generation"
        private const val EXTRA_INSTANCE = "playback_instance"
        private const val ACTION_PLAY = "app.smallthingz.reverb.playback.PLAY"
        private const val ACTION_PAUSE = "app.smallthingz.reverb.playback.PAUSE"
        private const val ACTION_STOP = "app.smallthingz.reverb.playback.STOP"
        private const val CHANNEL = "saved_recording_playback"
        private const val NOTIFICATION_ID = 7391
    }
}
