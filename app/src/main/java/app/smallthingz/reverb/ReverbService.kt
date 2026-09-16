package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference


internal class UiForegroundOwnerTracker {
    private val owners = IdentityHashMap<Any, Unit>()

    @Synchronized
    fun update(owner: Any, foreground: Boolean): Boolean {
        if (foreground) owners[owner] = Unit else owners.remove(owner)
        return owners.isNotEmpty()
    }

    @Synchronized
    fun clear() {
        owners.clear()
    }

    @Synchronized
    fun size(): Int = owners.size
}

internal class IdentityOwnerRegistry<T : Any> {
    private val owners = ArrayList<T>()

    @Volatile
    private var active: T? = null

    fun current(): T? = active

    @Synchronized
    fun register(owner: T): Boolean {
        owners.removeAll { it === owner }
        val changed = active !== owner
        owners += owner
        active = owner
        return changed
    }

    @Synchronized
    fun unregister(owner: T): Boolean {
        val wasActive = active === owner
        owners.removeAll { it === owner }
        if (wasActive) active = owners.lastOrNull()
        return wasActive
    }

    @Synchronized
    fun clearAll() {
        owners.clear()
        active = null
    }

    @Synchronized
    fun size(): Int = owners.size
}

@SuppressLint("ImplicitSamInstance")
class ReverbService : Service() {
    @Volatile
    private var sampleRate = PREFERRED_DEFAULT_SAMPLE_RATE

    @Volatile
    private var fillRate = 96_000L

    @Volatile
    private var audioSource = AudioSourceMode.defaultMode().sourceValue

    @Volatile
    private var sourceMode = AudioSourceMode.defaultMode()

    @Volatile
    private var channelMode = ChannelMode.MONO

    @Volatile
    private var pcmSampleFormat = PcmSampleFormat.PCM_16

    @Volatile
    private var outputFormat = ExportFormat.WAV

    @Volatile
    private var outputCodec = ExportCodec.PCM_16

    @Volatile
    private var inputRouteMode = InputRouteMode.AUTO

    @Volatile
    private var state = STATE_READY

    @Volatile
    private var activeBufferSlot = BufferSlot.ONE_SHOT

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioRecordGeneration = Long.MIN_VALUE

    private val listeningCommandGeneration = AtomicLong()
    private val listeningIntentLock = Any()

    @Volatile
    private var foregroundStartBlocked = false

    @Volatile
    private var activeExportToken: ExportCancellationToken? = null

    @Volatile
    private var activeExportFuture: Future<*>? = null

    @Volatile
    private var activeExportReceiver: AudioFileReceiver? = null

    private val exportStateLock = Any()

    @Volatile
    private var foregroundServiceTypes = 0

    @Volatile
    private var foregroundServiceTimedOut = false

    @Volatile
    private var persistenceFailureBlocked = false

    @Volatile
    private var cachedRetentionSampleBytes = 0L

    @Volatile
    private var oneShotBufferEnabled = true

    @Volatile
    private var loopingBufferEnabled = true

    @Volatile
    private var cachedConfigSnapshot: RecorderConfigurationSnapshot? = null

    @Volatile
    private var configuredCaptureSnapshot: RecorderConfigurationSnapshot? = null

    private val visualizationCallbacks = IdentityOwnerRegistry<VisualizationCallback>()

    private val captureScratch = ByteArray(CAPTURE_SCRATCH_BYTES)
    private val captureBuffer = ByteBuffer.allocateDirect(CAPTURE_SCRATCH_BYTES)
        .order(ByteOrder.nativeOrder())
    private val visualizationAnalyzer = AudioVisualizationAnalyzer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingVisualizationFrame = AtomicReference<VisualizationFrame?>(null)
    private val visualizationDispatchScheduled = AtomicBoolean(false)
    private var visualizationFaulted = false

    private lateinit var audioThread: HandlerThread
    private lateinit var audioHandler: Handler
    private lateinit var exportWorkExecutor: ExecutorService
    private lateinit var durabilitySyncExecutor: ExecutorService
    private val durabilitySyncInFlight = AtomicBoolean(false)
    @Volatile private var lastDurabilitySyncRequestNanos = 0L
    private lateinit var loopingAudioChunkStore: PersistentAudioChunkStore
    private lateinit var oneShotAudioChunkStore: PersistentAudioChunkStore

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val appUiForegroundOwners = UiForegroundOwnerTracker()
    @Volatile private var appUiForeground = false
    private var powerManager: PowerManager? = null

    private val pendingError = AtomicReference<String?>(null)

    override fun onCreate() {
        super.onCreate()
        loopingAudioChunkStore = PersistentAudioChunkStore(this)
        oneShotAudioChunkStore = PersistentAudioChunkStore(
            this,
            cacheFolderName = ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
            legacyCacheFolderName = null,
            overwriteOldest = false,
        )
        createNotificationChannel()
        powerManager = getSystemService(PowerManager::class.java)
        audioThread = HandlerThread(THREAD_NAME_AUDIO, Process.THREAD_PRIORITY_AUDIO)
            .also { it.start() }
        audioHandler = Handler(audioThread.looper)
        exportWorkExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME_EXPORT_WORK).apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
            }
        }
        durabilitySyncExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "reverb-durability-sync").apply {
                isDaemon = true
            }
        }
        audioHandler.post {
            try {
                loadConfiguredPreferences()
                configurePersistentBuffer()
                switchActiveBufferOnAudioThread(resolveConfiguredCaptureBufferSlot(), notifyTiles = false)
                syncOneShotFullQuickTileOnAudioThread()
            } catch (error: Exception) {
                pauseListeningAfterPersistenceFailure("initialize", error)
                return@post
            }
            mainHandler.post {
                val listeningIntentEnabled = isListeningEnabled()
                if (!shouldAttemptAutomaticListeningStart(
                        listeningIntentEnabled = listeningIntentEnabled,
                        foregroundStartBlocked = foregroundStartBlocked,
                        persistenceFailureBlocked = persistenceFailureBlocked,
                    )
                ) return@post

                val currentGeneration = listeningCommandGeneration.get()
                if (shouldEnsureRuntimeCaptureAfterInitialization(
                        listeningIntentEnabled = listeningIntentEnabled,
                        recorderState = state,
                        foregroundStartBlocked = foregroundStartBlocked,
                        persistenceFailureBlocked = persistenceFailureBlocked,
                    )
                ) {
                    // Sticky onStartCommand can queue a start with the pre-initialization
                    // generation. Resolving the configured buffer may advance that generation;
                    // always ensure capture with the current one once initialization settles.
                    audioHandler.post { startAudioInputOnAudioThread(currentGeneration) }
                } else {
                    innerStartListening(currentGeneration)
                }
            }
        }
    }

    override fun onDestroy() {
        // Service destruction alone is not evidence of a known/intentional capture stop.
        // Explicit user stops and known recorder failures disarm through their own transition.
        // Leaving the marker armed here lets a later Android-classified process death be recovered.
        visualizationCallbacks.clearAll()
        pendingVisualizationFrame.set(null)
        mainHandler.removeCallbacks(visualizationDispatcher)
        visualizationDispatchScheduled.set(false)
        if (::durabilitySyncExecutor.isInitialized) {
            durabilitySyncExecutor.shutdownNow()
        }
        // Service teardown is not a user cancellation. Keep any in-flight export recoverable.
        flushAndPersistBeforeShutdown()
        val stoppedTileSnapshot = RecordingQuickTileStateCache.markServiceStopped(this)
        RecordingQuickTiles.publishSnapshot(this, stoppedTileSnapshot, requestSystemRefresh = true)
        releaseWakeLock()
        stopForegroundTracked()

        if (::exportWorkExecutor.isInitialized) {
            // onDestroy runs on the main thread. Let already-started export work finish on its
            // executor; verified staging makes process loss recoverable, so waiting here only
            // adds UI/service teardown latency without strengthening durability.
            exportWorkExecutor.shutdown()
        }
        audioThread.quitSafely()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        audioHandler.post { checkpointPersistentStore("trim-memory checkpoint") }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        audioHandler.post { checkpointPersistentStore("low-memory checkpoint") }
    }

    override fun onBind(intent: Intent): IBinder {
        noteClientBound()
        return BackgroundRecorderBinder()
    }

    override fun onRebind(intent: Intent) {
        noteClientBound()
        super.onRebind(intent)
    }

    private fun noteClientBound() {
        val retryGeneration = synchronized(listeningIntentLock) {
            if (
                shouldRetrySuspendedListeningOnForegroundBind(
                    listeningIntentEnabled = isListeningEnabled(),
                    foregroundStartBlocked = foregroundStartBlocked,
                    foregroundServiceTimedOut = foregroundServiceTimedOut,
                    persistenceFailureBlocked = persistenceFailureBlocked,
                )
            ) {
                foregroundStartBlocked = false
                foregroundServiceTimedOut = false
                persistenceFailureBlocked = false
                listeningCommandGeneration.get()
            } else {
                null
            }
        }
        if (retryGeneration != null) {
            mainHandler.post { innerStartListening(retryGeneration) }
        }
    }

    override fun onUnbind(intent: Intent): Boolean {
        appUiForegroundOwners.clear()
        appUiForeground = false
        clearAllVisualizationCallbacks()
        return true
    }

    override fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>,
    ) {
        if (!isDebuggableBuild()) {
            super.dump(fd, writer, args)
            return
        }
        val persisted = if (::loopingAudioChunkStore.isInitialized) {
            runCatching { loopingAudioChunkStore.peekSnapshot() }.getOrNull()
        } else {
            null
        }
        val oneShot = if (::oneShotAudioChunkStore.isInitialized) {
            runCatching { oneShotAudioChunkStore.peekSnapshot() }.getOrNull()
        } else {
            null
        }
        writer.println("ReverbService")
        writer.println("  state=$state")
        writer.println("  listeningEnabled=${isListeningEnabled()}")
        writer.println("  appUiForeground=$appUiForeground owners=${appUiForegroundOwners.size()}")
        writer.println("  visualizerAttached=${visualizationCallbacks.current() != null} clients=${visualizationCallbacks.size()}")
        writer.println("  activeBuffer=$activeBufferSlot")
        writer.println("  sampleRate=$sampleRate")
        writer.println("  channelCount=${channelMode.channelCount}")
        writer.println("  format=${outputFormat.legacyPrefValue}")
        writer.println("  codec=${outputCodec.legacyPrefValue}")
        writer.println("  fillRate=$fillRate")
        writer.println("  exportDir=${describeConfiguredOutputDirectory(this)}")
        writer.println(
            "  persisted filled=${persisted?.filledBytes ?: 0} duration=${persisted?.durationSeconds ?: 0.0} " +
                "chunks=${persisted?.chunkCount ?: 0} sampleRate=${persisted?.currentSampleRate ?: 0} " +
                "channelCount=${persisted?.currentChannelCount ?: 0} " +
                "lastWrite=${persisted?.lastWriteAtMillis ?: 0}",
        )
        writer.println(
            "  oneShot filled=${oneShot?.filledBytes ?: 0} duration=${oneShot?.durationSeconds ?: 0.0} " +
                "chunks=${oneShot?.chunkCount ?: 0}",
        )
        writer.println("  rawHistoryDirectory=${BUFFER_CACHE_FOLDER_NAME}/${BUFFER_CHUNKS_FOLDER_NAME}")
    }

    fun enableListening(bufferSlot: BufferSlot): ListeningCommandResult {
        if (!canActivateCaptureBuffer(
                requested = bufferSlot,
                oneShotEnabled = oneShotBufferEnabled,
                oneShotFull = oneShotBufferEnabled && oneShotAudioChunkStore.isFull(),
                loopingEnabled = loopingBufferEnabled,
            )
        ) {
            return ListeningCommandResult(accepted = false, generation = listeningCommandGeneration.get())
        }
        if (isLogicalListeningState(state, isListeningEnabled()) && activeBufferSlot != bufferSlot) {
            return ListeningCommandResult(accepted = false, generation = listeningCommandGeneration.get())
        }
        return setListeningEnabled(enabled = true, requestedBufferSlot = bufferSlot)
    }

    fun disableListening(): ListeningCommandResult {
        return setListeningEnabled(enabled = false)
    }

    fun selectCaptureBuffer(bufferSlot: BufferSlot): ListeningCommandResult {
        val canActivate = canActivateCaptureBuffer(
            requested = bufferSlot,
            oneShotEnabled = oneShotBufferEnabled,
            oneShotFull = oneShotBufferEnabled && oneShotAudioChunkStore.isFull(),
            loopingEnabled = loopingBufferEnabled,
        )
        if (!canActivate) {
            return ListeningCommandResult(accepted = false, generation = listeningCommandGeneration.get())
        }

        val prefs = getRecorderPreferences(this)
        val previousActiveBuffer = activeBufferSlot
        var targetChanged = false
        val generation = synchronized(listeningIntentLock) {
            val previousStoredSlot = readCaptureBufferSlotPreference(prefs)
            if (activeBufferSlot == bufferSlot && previousStoredSlot == bufferSlot) {
                listeningCommandGeneration.get()
            } else if (!captureSlotNeedsPersistence(previousStoredSlot, bufferSlot)) {
                activeBufferSlot = bufferSlot
                targetChanged = true
                listeningCommandGeneration.incrementAndGet()
            } else if (!prefs.edit().putInt(PrefKey.CAPTURE_BUFFER_SLOT, bufferSlot.storageCode.toInt()).commit()) {
                if (!restoreCaptureIntentPreferences(prefs, previousStoredSlot = previousStoredSlot)) {
                    Log.e(TAG, "Unable to durably restore capture destination after failed selection")
                }
                null
            } else {
                activeBufferSlot = bufferSlot
                targetChanged = true
                // Target selection is observable state even while idle, so version it too. The
                // stop path follows the durable listening=false intent and is intentionally not
                // cancelled by an unrelated target version change.
                listeningCommandGeneration.incrementAndGet()
            }
        }
        if (generation == null) {
            reportError(getString(R.string.recorder_state_persist_failed))
            return ListeningCommandResult(accepted = false, generation = listeningCommandGeneration.get())
        }
        if (targetChanged) {
            if (isLogicalListeningState(state, isListeningEnabled())) {
                RecordingQuickTiles.beginHandoff(previousActiveBuffer, bufferSlot)
                audioHandler.post { adoptCaptureGenerationOnAudioThread(generation) }
            } else {
                audioHandler.post { publishQuickTileSnapshotOnAudioThread(refreshTiles = true) }
            }
        }
        return ListeningCommandResult(accepted = true, generation = generation)
    }

    private fun adoptCaptureGenerationOnAudioThread(generation: Long) {
        check(audioHandler.looper == Looper.myLooper())
        val record = audioRecord
        val recordRunning = record != null && runCatching {
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
        when (
            captureReaderTransition(
                requestedGeneration = generation,
                currentGeneration = listeningCommandGeneration.get(),
                listening = state == STATE_LISTENING && isListeningEnabled(),
                recordRunning = recordRunning,
            )
        ) {
            CaptureReaderTransition.IGNORE -> {
                publishQuickTileSnapshotOnAudioThread(refreshTiles = true)
                return
            }
            CaptureReaderTransition.RESTART -> startAudioInputOnAudioThread(generation)
            CaptureReaderTransition.ADOPT -> {
                audioHandler.removeCallbacks(audioReader)
                audioRecordGeneration = generation
                publishQuickTileSnapshotOnAudioThread(refreshTiles = true)
                audioHandler.post(audioReader)
            }
        }
    }

    private fun setListeningEnabled(
        enabled: Boolean,
        requestedBufferSlot: BufferSlot? = null,
    ): ListeningCommandResult {
        val prefs = getRecorderPreferences(this)
        val generation = synchronized(listeningIntentLock) {
            val previousEnabled = prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
            val previousStoredSlot = readCaptureBufferSlotPreference(prefs)
            val requestedSlot = requestedBufferSlot ?: persistedCaptureBufferSlot() ?: activeBufferSlot
            val runtimeSlotChanged = enabled && requestedSlot != activeBufferSlot
            val needsPersistence = captureIntentNeedsPersistence(
                previousEnabled = previousEnabled,
                requestedEnabled = enabled,
                previousStoredSlot = previousStoredSlot,
                requestedSlot = requestedSlot,
            )
            if (!needsPersistence) {
                if (enabled) {
                    activeBufferSlot = requestedSlot
                    foregroundStartBlocked = false
                    foregroundServiceTimedOut = false
                    persistenceFailureBlocked = false
                }
                if (runtimeSlotChanged) listeningCommandGeneration.incrementAndGet()
                else listeningCommandGeneration.get()
            } else {
                val editor = prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, enabled)
                if (enabled) editor.putInt(PrefKey.CAPTURE_BUFFER_SLOT, requestedSlot.storageCode.toInt())
                if (!editor.commit()) {
                    if (!restoreCaptureIntentPreferences(
                            prefs = prefs,
                            previousEnabled = previousEnabled,
                            previousStoredSlot = previousStoredSlot,
                        )) {
                        Log.e(TAG, "Unable to durably restore recorder intent after failed command")
                    }
                    null
                } else {
                    if (enabled) {
                        activeBufferSlot = requestedSlot
                        foregroundStartBlocked = false
                        foregroundServiceTimedOut = false
                        persistenceFailureBlocked = false
                    }
                    listeningCommandGeneration.incrementAndGet()
                }
            }
        }
        if (generation == null) {
            reportError(getString(R.string.recorder_state_persist_failed))
            return ListeningCommandResult(
                accepted = false,
                generation = listeningCommandGeneration.get(),
            )
        }
        if (enabled) {
            innerStartListening(generation)
        } else {
            RecordingIncidentStore.recordKnownCaptureStop(this)
            innerStopListening()
        }
        return ListeningCommandResult(accepted = true, generation = generation)
    }

    private fun restoreCaptureIntentPreferences(
        prefs: SharedPreferences,
        previousEnabled: Boolean? = null,
        previousStoredSlot: BufferSlot?,
    ): Boolean {
        val editor = prefs.edit()
        if (previousEnabled != null) editor.putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, previousEnabled)
        if (previousStoredSlot == null) editor.remove(PrefKey.CAPTURE_BUFFER_SLOT)
        else editor.putInt(PrefKey.CAPTURE_BUFFER_SLOT, previousStoredSlot.storageCode.toInt())
        return editor.commit()
    }

    private fun isListeningEnabled(): Boolean {
        return getRecorderPreferences(this).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
    }

    private fun persistedCaptureBufferSlot(): BufferSlot? =
        readCaptureBufferSlotPreference(getRecorderPreferences(this))

    private fun resolveConfiguredCaptureBufferSlot(): BufferSlot {
        val oneShotFull = oneShotBufferEnabled && oneShotAudioChunkStore.isFull()
        val persisted = persistedCaptureBufferSlot()
        return resolveAvailableCaptureBufferSlot(
            preferred = persisted ?: defaultStartupBufferSlot(
                oneShotEnabled = oneShotBufferEnabled,
                oneShotFull = oneShotFull,
                loopingEnabled = loopingBufferEnabled,
            ),
            oneShotEnabled = oneShotBufferEnabled,
            oneShotFull = oneShotFull,
            loopingEnabled = loopingBufferEnabled,
        ) ?: (persisted ?: BufferSlot.ONE_SHOT)
    }

    private fun ensureActiveCaptureTargetOnAudioThread(): Boolean {
        check(audioHandler.looper == Looper.myLooper())
        val resolved = resolveAvailableCaptureBufferSlot(
            preferred = activeBufferSlot,
            oneShotEnabled = oneShotBufferEnabled,
            oneShotFull = oneShotBufferEnabled && oneShotAudioChunkStore.isFull(),
            loopingEnabled = loopingBufferEnabled,
        ) ?: return false
        return resolved == activeBufferSlot || switchActiveBufferOnAudioThread(resolved)
    }

    private fun switchActiveBufferOnAudioThread(
        bufferSlot: BufferSlot,
        notifyTiles: Boolean = true,
    ): Boolean {
        check(audioHandler.looper == Looper.myLooper())
        if (activeBufferSlot == bufferSlot) return true
        val prefs = getRecorderPreferences(this)
        val previousStoredSlot = readCaptureBufferSlotPreference(prefs)
        if (captureSlotNeedsPersistence(previousStoredSlot, bufferSlot)) {
            if (!prefs.edit().putInt(PrefKey.CAPTURE_BUFFER_SLOT, bufferSlot.storageCode.toInt()).commit()) {
                if (!restoreCaptureIntentPreferences(prefs, previousStoredSlot = previousStoredSlot)) {
                    Log.e(TAG, "Unable to durably restore capture destination after failed handoff")
                }
                reportError(getString(R.string.recorder_state_persist_failed))
                return false
            }
        }
        val switchingWhileRecording = isLogicalListeningState(state, isListeningEnabled())
        val previousActiveBuffer = activeBufferSlot
        if (switchingWhileRecording) {
            RecordingQuickTiles.beginHandoff(previousActiveBuffer, bufferSlot)
        }
        activeBufferSlot = bufferSlot
        if (switchingWhileRecording) {
            val generation = listeningCommandGeneration.incrementAndGet()
            if (audioRecordGeneration != Long.MIN_VALUE) audioRecordGeneration = generation
        }
        publishQuickTileSnapshotOnAudioThread(refreshTiles = notifyTiles)
        return true
    }

    private fun clearOneShotFullQuickTileCacheOnAudioThread() {
        check(audioHandler.looper == Looper.myLooper())
        val prefs = getRecorderPreferences(this)
        if (prefs.getBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, false)) {
            // Tile fallback state is a cache, not capture intent. Never block the audio handler
            // on a filesystem-backed SharedPreferences commit for non-authoritative UI state.
            prefs.edit { putBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, false) }
        }
    }

    private fun syncOneShotFullQuickTileOnAudioThread(refreshTiles: Boolean = true) {
        check(audioHandler.looper == Looper.myLooper())
        val full = oneShotBufferEnabled && oneShotAudioChunkStore.isFull()
        val prefs = getRecorderPreferences(this)
        if (prefs.getBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, false) != full) {
            // apply() updates this process immediately; disk persistence can lag because the
            // authoritative value is recomputed from the chunk store whenever the service lives.
            prefs.edit { putBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, full) }
        }
        publishQuickTileSnapshotOnAudioThread(refreshTiles = refreshTiles)
    }

    private fun readConfiguredCaptureSnapshot(): RecorderConfigurationSnapshot {
        return RecorderConfigurationSnapshot(
            format = getConfiguredOutputFormat(this),
            codec = getConfiguredOutputCodec(this),
            sampleFormat = getConfiguredPcmSampleFormat(this),
            sampleRate = getConfiguredSampleRate(this).takeIf { it > 0 }
                ?: PREFERRED_DEFAULT_SAMPLE_RATE,
            sourceMode = getConfiguredAudioSourceMode(this),
            channelMode = getConfiguredChannelMode(this),
            routeMode = getConfiguredInputRouteMode(this),
        )
    }

    private fun loadConfiguredPreferences() {
        val configured = readConfiguredCaptureSnapshot()
        configuredCaptureSnapshot = configured
        sourceMode = configured.sourceMode
        channelMode = configured.channelMode
        inputRouteMode = configured.routeMode
        outputFormat = configured.format
        outputCodec = configured.codec
        pcmSampleFormat = configured.sampleFormat
        sampleRate = configured.sampleRate
        audioSource = sourceMode.sourceValue
        fillRate = sampleRate.toLong() * channelMode.channelCount * pcmSampleFormat.bytesPerSample
        publishConfigurationSnapshot()
        refreshCachedBufferSizing()
    }

    private fun loadConfiguration() {
        val configured = readConfiguredCaptureSnapshot()
        configuredCaptureSnapshot = configured
        var selectedSourceMode = configured.sourceMode
        var selectedChannelMode = configured.channelMode
        var selectedRouteMode = configured.routeMode
        var selectedFormat = configured.format
        var selectedCodec = configured.codec
        val selectedSampleFormat = configured.sampleFormat

        val requestedRate = configured.sampleRate
        val resolvedConfig = resolveOperationalConfiguration(
            preferredSourceMode = selectedSourceMode,
            preferredChannelMode = selectedChannelMode,
            preferredRouteMode = selectedRouteMode,
            preferredFormat = selectedFormat,
            preferredCodec = selectedCodec,
            preferredRate = requestedRate,
            preferredSampleFormat = selectedSampleFormat,
        )

        if (resolvedConfig != null) {
            selectedSourceMode = resolvedConfig.sourceMode
            selectedChannelMode = resolvedConfig.channelMode
            selectedRouteMode = resolvedConfig.routeMode
            selectedFormat = resolvedConfig.format
            selectedCodec = resolvedConfig.codec
        }

        sampleRate = resolvedConfig?.sampleRate ?: 48_000
        pcmSampleFormat = resolvedConfig?.sampleFormat ?: PcmSampleFormat.PCM_16
        fillRate = sampleRate.toLong() * selectedChannelMode.channelCount * pcmSampleFormat.bytesPerSample
        sourceMode = selectedSourceMode
        channelMode = selectedChannelMode
        audioSource = selectedSourceMode.sourceValue
        outputFormat = selectedFormat
        outputCodec = selectedCodec
        inputRouteMode = selectedRouteMode
        publishConfigurationSnapshot()
        refreshCachedBufferSizing()
    }

    private fun publishConfigurationSnapshot() {
        cachedConfigSnapshot = RecorderConfigurationSnapshot(
            format = outputFormat,
            codec = outputCodec,
            sampleFormat = pcmSampleFormat,
            sampleRate = sampleRate,
            sourceMode = sourceMode,
            channelMode = channelMode,
            routeMode = inputRouteMode,
        )
    }

    private fun refreshCachedBufferSizing() {
        cachedRetentionSampleBytes = getConfiguredMemorySizeBytes(
            context = this,
            sampleRate = sampleRate,
            channelMode = channelMode,
            sampleFormat = pcmSampleFormat,
        )
    }

    private fun resolveOperationalConfiguration(
        preferredSourceMode: AudioSourceMode,
        preferredChannelMode: ChannelMode,
        preferredRouteMode: InputRouteMode,
        preferredFormat: ExportFormat,
        preferredCodec: ExportCodec,
        preferredRate: Int,
        preferredSampleFormat: PcmSampleFormat,
    ): OperationalConfig? {
        val formatCandidates = buildList {
            add(preferredFormat); val formats = supportedFormats()
            for (f in formats) if (f != preferredFormat) add(f)
        }
        val routeCandidates = buildList {
            add(preferredRouteMode); val modes = supportedInputRouteModes(this@ReverbService)
            for (m in modes) if (m != preferredRouteMode) add(m)
        }
        val sourceCandidates = buildList {
            add(preferredSourceMode); val modes = AudioSourceMode.availableModes()
            for (m in modes) if (m != preferredSourceMode) add(m)
        }
        val channelCandidates = buildList {
            add(preferredChannelMode)
            for (m in ChannelMode.entries) if (m != preferredChannelMode) add(m)
        }
        val sampleFormatCandidates = buildList {
            add(preferredSampleFormat)
            for (m in PcmSampleFormat.entries) if (m != preferredSampleFormat) add(m)
        }

        formatCandidates.forEach { format ->
            val codecCandidates = listOf(preferredCodec) + supportedCodecs(format).filter { it != preferredCodec }
            codecCandidates.forEach { codec ->
                routeCandidates.forEach { routeMode ->
                    sourceCandidates.forEach { sourceMode ->
                        channelCandidates.forEach { channelMode ->
                            sampleFormatCandidates.forEach { sampleFormat ->
                                val sampleRate = resolveOperationalSampleRate(
                                    this,
                                    preferredRate,
                                    sourceMode,
                                    routeMode,
                                    format,
                                    codec,
                                    channelMode,
                                    sampleFormat,
                                )
                                if (sampleRate > 0 && isCodecSupported(format, codec, sampleRate, channelMode)) {
                                    return OperationalConfig(
                                        sourceMode = sourceMode,
                                        channelMode = channelMode,
                                        routeMode = routeMode,
                                        format = format,
                                        codec = codec,
                                        sampleFormat = sampleFormat,
                                        sampleRate = sampleRate,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun innerStartListening(generation: Long = listeningCommandGeneration.get()) {
        if (
            generation != listeningCommandGeneration.get() ||
            !isListeningEnabled() ||
            foregroundStartBlocked ||
            persistenceFailureBlocked
        ) return
        state = STATE_LISTENING
        updateWakeLockState()
        try {
            ContextCompat.startForegroundService(this, Intent(this, javaClass))
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start recorder foreground service", error)
            pauseListeningAfterForegroundStartFailure(generation, error)
            return
        }
    }

    private fun pauseListeningAfterForegroundStartFailure(
        generation: Long,
        error: RuntimeException,
    ) {
        synchronized(listeningIntentLock) {
            if (generation != listeningCommandGeneration.get()) return
            // Android 14+ can reject microphone-FGS promotion while the app is in the
            // background even though the user's durable capture intent is still valid.
            // Preserve that intent and retry only after a fresh service instance or an
            // explicit user start; otherwise a transient platform restriction silently
            // turns recording off forever.
            foregroundStartBlocked = true
            listeningCommandGeneration.incrementAndGet()
            state = STATE_PAUSED
        }
        RecordingIncidentStore.recordKnownCaptureStop(this)
        audioHandler.post {
            audioHandler.removeCallbacks(audioReader)
            try {
                sealActiveChunks()
            } catch (sealError: Exception) {
                reportPersistentStoreFailure("seal after foreground start restriction", sealError)
            } finally {
                releaseAudioRecord()
                updateWakeLockState()
                publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
            }
        }
        updateWakeLockState()
        reportError(userFacingError(getString(R.string.audio_input_init_failed), error))
        requestServiceStopWhenExportIdle()
    }

    private fun startAudioInputOnAudioThread(generation: Long = listeningCommandGeneration.get()) {
        check(audioHandler.looper == Looper.myLooper())
        if (generation != listeningCommandGeneration.get()) return
        if (audioRecordGeneration == generation && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) return
        audioHandler.removeCallbacks(audioReader)
        releaseAudioRecord()
        if (generation != listeningCommandGeneration.get() || state != STATE_LISTENING || !isListeningEnabled()) return
        // Binding the paused UI should not probe microphone hardware. Resolve the
        // requested configuration only when capture is actually about to start.
        try {
            loadConfiguration()
            configurePersistentBuffer()
        } catch (error: Exception) {
            pauseListeningAfterPersistenceFailure("configure before capture", error)
            return
        }

        if (generation != listeningCommandGeneration.get() || !isListeningEnabled()) return
        if (!ensureActiveCaptureTargetOnAudioThread() || !hasWritableCaptureTarget()) {
            pauseListeningForNoWritableBufferOnAudioThread(generation)
            return
        }
        val currentGeneration = listeningCommandGeneration.get()
        if (generation != currentGeneration) {
            if (state == STATE_LISTENING && isListeningEnabled()) {
                audioHandler.post { startAudioInputOnAudioThread(currentGeneration) }
            }
            return
        }
        val record = createAudioRecord()
        audioRecord = record
        audioRecordGeneration = generation
        if (generation != listeningCommandGeneration.get() || !isListeningEnabled()) {
            releaseAudioRecord()
            return
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            failListeningOnAudioThread(getString(R.string.audio_input_init_failed), null, generation)
            return
        }

        try {
            record.startRecording()
        } catch (error: RuntimeException) {
            Log.e(TAG, "AudioRecord.startRecording failed", error)
            failListeningOnAudioThread(getString(R.string.audio_input_init_failed), error, generation)
            return
        }
        if (generation != listeningCommandGeneration.get() || !isListeningEnabled()) {
            releaseAudioRecord()
            return
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            failListeningOnAudioThread(getString(R.string.audio_input_init_failed), null, generation)
            return
        }
        if (!armCaptureIncidentTrackingOnAudioThread()) return
        if (generation != listeningCommandGeneration.get() || state != STATE_LISTENING || !isListeningEnabled()) return
        lastDurabilitySyncRequestNanos = System.nanoTime()
        publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
        audioHandler.post(audioReader)
    }

    private fun armCaptureIncidentTrackingOnAudioThread(): Boolean {
        check(audioHandler.looper == Looper.myLooper())
        return try {
            RecordingIncidentStore.recordCaptureStarted(this)
            true
        } catch (error: Exception) {
            pauseListeningAfterPersistenceFailure("arm capture incident tracking", error)
            false
        }
    }

    private fun innerStopListening() {
        when (state) {
            STATE_READY -> return
            STATE_LISTENING, STATE_PAUSED -> Unit
            else -> return
        }
        audioHandler.post {
            // A target switch may legitimately advance the state generation after Stop was
            // requested. Only a newer durable Start intent should cancel teardown.
            if (isListeningEnabled()) return@post
            audioHandler.removeCallbacks(audioReader)
            state = STATE_READY
            updateWakeLockState()
            publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
            try {
                sealActiveChunks()
            } catch (error: Exception) {
                reportPersistentStoreFailure("seal while stopping", error)
            } finally {
                releaseAudioRecord()
                mainHandler.post {
                    if (isListeningEnabled() || state == STATE_LISTENING) return@post
                    requestServiceStopWhenExportIdle()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMode.inputChannelMask,
            pcmSampleFormat.audioEncoding,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord min buffer invalid for $sampleRate Hz")
            return null
        }

        return try {
            val record = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(pcmSampleFormat.audioEncoding)
                        .setChannelMask(channelMode.inputChannelMask)
                        .setSampleRate(sampleRate)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, MIN_AUDIO_RECORD_BUFFER_SIZE))
                .build()
            if (inputRouteMode == InputRouteMode.BUILTIN_MIC) {
                val builtInMic = findBuiltInMicrophone(this)
                val routeAccepted = builtInMic != null &&
                    runCatching { record.setPreferredDevice(builtInMic) }.getOrDefault(false)
                if (!routeAccepted) {
                    runCatching { record.release() }
                    return null
                }
            }
            record
        } catch (error: Exception) {
            Log.e(TAG, "Unable to create AudioRecord", error)
            null
        }
    }

    private fun releaseAudioRecord() {
        lastDurabilitySyncRequestNanos = 0L
        val record = audioRecord ?: run {
            audioRecordGeneration = Long.MIN_VALUE
            return
        }
        audioRecord = null
        audioRecordGeneration = Long.MIN_VALUE
        runCatching { record.stop() }
        runCatching { record.release() }
            .onFailure { Log.w(TAG, "AudioRecord.release failed", it) }
    }

    fun dumpRecording(
        memorySeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
        bufferSlot: BufferSlot = BufferSlot.LOOPING,
    ) {
        val exportToken = beginExport(receiver) ?: run {
            notifyReceiverFailure(receiver, getString(R.string.export_in_progress))
            return
        }
        if (!ensureExportForegroundLifetime(exportToken, receiver)) return

        if (!audioHandler.post {
            try {
                if (!isExportPending(exportToken)) return@post
                flushAudioRecord()
                if (!isExportPending(exportToken)) return@post

                val store = chunkStore(bufferSlot)
                val totalDuration = availableBufferedDurationSeconds(bufferSlot)
                val requestedDuration = memorySeconds.toDouble().coerceAtLeast(0.0)
                val end = totalDuration
                val start = maxOf(0.0, end - requestedDuration)
                val lease = acquireExportRange(store, start, end)
                if (lease == null) {
                    clearExportState(exportToken)
                    finishExportFailure(exportToken, receiver, getString(R.string.nothing_to_export))
                    return@post
                }
                exportBufferedRange(lease, receiver, newFileName, exportToken)
            } catch (error: Exception) {
                reportPersistentStoreFailure("prepare export", error)
                clearExportState(exportToken)
                finishExportFailure(exportToken, receiver, getString(R.string.save_failed), error)
            }
        }) {
            clearExportState(exportToken)
            finishExportFailure(exportToken, receiver, getString(R.string.save_failed))
        }
    }

    fun dumpRecordingRange(
        startOffsetSeconds: Float,
        endOffsetSeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
        bufferSlot: BufferSlot = BufferSlot.LOOPING,
    ) {
        val exportToken = beginExport(receiver) ?: run {
            notifyReceiverFailure(receiver, getString(R.string.export_in_progress))
            return
        }
        if (!ensureExportForegroundLifetime(exportToken, receiver)) return

        if (!audioHandler.post {
            try {
                if (!isExportPending(exportToken)) return@post
                flushAudioRecord()
                if (!isExportPending(exportToken)) return@post

                val store = chunkStore(bufferSlot)
                val totalDuration = availableBufferedDurationSeconds(bufferSlot)
                val boundedStart = startOffsetSeconds.toDouble().coerceIn(0.0, totalDuration)
                val boundedEnd = endOffsetSeconds.toDouble().coerceIn(boundedStart, totalDuration)
                val lease = acquireExportRange(store, boundedStart, boundedEnd)
                if (lease == null) {
                    clearExportState(exportToken)
                    finishExportFailure(exportToken, receiver, getString(R.string.nothing_to_export))
                    return@post
                }
                exportBufferedRange(lease, receiver, newFileName, exportToken)
            } catch (error: Exception) {
                reportPersistentStoreFailure("prepare range export", error)
                clearExportState(exportToken)
                finishExportFailure(exportToken, receiver, getString(R.string.save_failed), error)
            }
        }) {
            clearExportState(exportToken)
            finishExportFailure(exportToken, receiver, getString(R.string.save_failed))
        }
    }

    fun acquireTimelineSnapshot(
        bufferSlot: BufferSlot = BufferSlot.LOOPING,
        callback: (TimelineSnapshot?) -> Unit,
    ) {
        if (!audioHandler.post {
            val snapshot = try {
                flushAudioRecord()
                val duration = availableBufferedDurationSeconds(bufferSlot)
                if (duration > 0.0) {
                    chunkStore(bufferSlot).acquireRange(0.0, duration)?.let(::TimelineSnapshot)
                } else {
                    null
                }
            } catch (error: Exception) {
                reportPersistentStoreFailure("acquire timeline snapshot", error)
                null
            }
            mainHandler.post { callback(snapshot) }
        }) {
            mainHandler.post { callback(null) }
        }
    }

    fun dumpRecordingRange(
        snapshot: TimelineSnapshot,
        startOffsetSeconds: Float,
        endOffsetSeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        val exportConfig = getConfigurationSnapshot()
        val exportToken = beginExport(receiver)
        if (exportToken == null) {
            snapshot.close()
            notifyReceiverFailure(receiver, getString(R.string.export_in_progress))
            return
        }
        if (!ensureExportForegroundLifetime(exportToken, receiver)) {
            snapshot.close()
            return
        }

        val lease = try {
            val totalDuration = snapshot.durationSeconds
            val boundedStart = startOffsetSeconds.toDouble().coerceIn(0.0, totalDuration)
            val boundedEnd = endOffsetSeconds.toDouble().coerceIn(boundedStart, totalDuration)
            val maxDuration = exportDurationLimitExactSeconds(
                format = exportConfig.format,
                codec = exportConfig.codec,
                sampleRate = exportConfig.sampleRate,
                channelCount = exportConfig.channelMode.channelCount,
                sampleFormat = exportConfig.sampleFormat,
            )
            val clampedStart = maxOf(boundedStart, boundedEnd - maxDuration)
            snapshot.acquireRange(clampedStart, boundedEnd)
        } catch (error: Exception) {
            clearExportState(exportToken)
            finishExportFailure(exportToken, receiver, getString(R.string.save_failed), error)
            return
        } finally {
            runCatching { snapshot.close() }
                .onFailure { error -> Log.w(TAG, "Unable to release export-range snapshot", error) }
        }
        if (lease == null) {
            clearExportState(exportToken)
            finishExportFailure(exportToken, receiver, getString(R.string.nothing_to_export))
            return
        }
        exportBufferedRange(lease, receiver, newFileName, exportToken, exportConfig)
    }

    private fun acquireExportRange(
        store: PersistentAudioChunkStore,
        requestedStartSeconds: Double,
        requestedEndSeconds: Double,
    ): PersistentAudioChunkStore.RangeLease? {
        val maxDuration = exportDurationLimitExactSeconds(
            format = outputFormat,
            codec = outputCodec,
            sampleRate = sampleRate,
            channelCount = channelMode.channelCount,
            sampleFormat = pcmSampleFormat,
        )
        val end = requestedEndSeconds.coerceAtLeast(requestedStartSeconds)
        val start = maxOf(requestedStartSeconds, end - maxDuration)
        return store.acquireRange(start, end)
    }

    private fun ensureExportForegroundLifetime(
        token: ExportCancellationToken,
        receiver: AudioFileReceiver,
    ): Boolean {
        if (!isExportPending(token)) return false
        foregroundServiceTimedOut = false
        return try {
            if (foregroundServiceTypes == 0) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, javaClass).setAction(ACTION_EXPORT_KEEPALIVE),
                )
                promoteForeground(
                    foregroundServiceTypesForWork(listening = false, exporting = true),
                    exporting = true,
                )
            }
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to protect export with a foreground service", error)
            clearExportState(token)
            val message = userFacingError(getString(R.string.save_failed), error)
            reportError(message)
            finishExportFailure(token, receiver, message, error)
            false
        }
    }

    private fun hasActiveExport(): Boolean = synchronized(exportStateLock) {
        activeExportToken != null
    }

    private fun requestServiceStopWhenExportIdle() {
        if (hasActiveExport()) {
            ensureExportOnlyForegroundIfNeeded()
            return
        }
        stopForegroundTracked()
        stopSelf()
    }

    private fun ensureExportOnlyForegroundIfNeeded() {
        if (!hasActiveExport()) return
        if ((foregroundServiceTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0) return
        try {
            promoteForeground(
                foregroundServiceTypesForWork(listening = false, exporting = true),
                exporting = true,
            )
        } catch (error: RuntimeException) {
            // Keep the existing foreground state if Android refuses a type transition.
            // The export remains protected rather than being destroyed with the service.
            Log.e(TAG, "Unable to switch foreground service to export mode", error)
            reportError(userFacingError(getString(R.string.save_failed), error))
        }
    }

    private fun refreshForegroundAfterExport() {
        mainHandler.post {
            if (hasActiveExport()) return@post
            if (foregroundServiceTimedOut) {
                stopForegroundTracked()
                return@post
            }
            if (state == STATE_LISTENING && isListeningEnabled() && !foregroundStartBlocked) {
                try {
                    promoteForeground(
                        foregroundServiceTypesForWork(listening = true, exporting = false),
                        exporting = false,
                    )
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Unable to restore microphone foreground state after export", error)
                }
                return@post
            }
            stopForegroundTracked()
            // The export keepalive itself started this service. If capture cannot be
            // restored to an active microphone foreground service, always release that
            // started lifetime; a live UI binding may still keep the service instance.
            stopSelf()
        }
    }

    fun cancelCurrentExport(): Boolean = requestExportCancellation(preserveVerifiedOutput = false)

    private fun requestExportCancellation(preserveVerifiedOutput: Boolean): Boolean {
        var receiverToNotify: AudioFileReceiver? = null
        var tokenToNotify: ExportCancellationToken? = null
        var clearedImmediately = false
        val cancelled = synchronized(exportStateLock) {
            val token = activeExportToken ?: return@synchronized false
            if (token.committed.get()) return@synchronized false

            if (preserveVerifiedOutput) token.preserveVerifiedOutput.set(true)
            token.cancelled.set(true)
            val future = activeExportFuture
            if (!token.started.get() && (future == null || future.cancel(true))) {
                receiverToNotify = activeExportReceiver
                tokenToNotify = token
                clearedImmediately = clearExportStateLocked(token)
            } else {
                future?.cancel(true)
            }
            true
        }
        if (clearedImmediately) refreshForegroundAfterExport()
        if (cancelled && receiverToNotify != null) {
            finishExportCancelled(tokenToNotify ?: return cancelled, receiverToNotify)
        }
        return cancelled
    }

    private fun exportBufferedRange(
        lease: PersistentAudioChunkStore.RangeLease,
        receiver: AudioFileReceiver,
        newFileName: String,
        exportToken: ExportCancellationToken,
        exportConfig: RecorderConfigurationSnapshot = getConfigurationSnapshot(),
    ) {
        if (!isExportPending(exportToken)) {
            lease.close()
            return
        }
        val leaseClosed = AtomicBoolean(false)
        fun closeLeaseOnce() {
            if (leaseClosed.compareAndSet(false, true)) lease.close()
        }
        val startedAtMillis = lease.startedAtMillis
        val endedAtMillis = lease.endedAtMillis.takeIf { it > 0L }
            ?: recordingEndTimestampMillis(startedAtMillis, lease.durationSeconds)
        val exportFormat = exportConfig.format
        val exportCodec = exportConfig.codec
        val exportSampleRate = exportConfig.sampleRate
        val exportChannelMode = exportConfig.channelMode
        val exportSampleFormat = exportConfig.sampleFormat
        val exportFillRate = exportSampleRate.toLong() *
            exportChannelMode.channelCount.toLong() * exportSampleFormat.bytesPerSample.toLong()
        val exportTask =
            object : FutureTask<Unit>(
                Callable {
                    exportToken.started.set(true)
                    var outTarget: RecordingOutputTarget? = null
                    var verifiedComplete = false
                    var cleanupDigest: CopyDigest? = null
                    var committed = false
                    try {
                        ensureExportNotCancelled(exportToken)
                        val target =
                            try {
                                createOutputTarget(
                                    this@ReverbService,
                                    newFileName,
                                    startedAtMillis,
                                    exportFormat,
                                    exportCodec,
                                    defaultNameTimestampMillis = endedAtMillis,
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Unable to prepare export file", e)
                                val message = userFacingError(getString(R.string.cant_create_file_generic), e)
                                reportError(message)
                                finishExportFailure(exportToken, receiver, message, e)
                                return@Callable Unit
                            }
                        outTarget = target
                        var durationMillis = 0L
                        val writer = WavAudioFileWriter(
                            this@ReverbService,
                            target,
                            exportSampleRate,
                            exportChannelMode.channelCount,
                            exportSampleFormat,
                        )
                        writer.use {
                            lease.readNormalized(
                                targetSampleRate = exportSampleRate,
                                targetChannelCount = exportChannelMode.channelCount,
                                targetSampleFormat = exportSampleFormat,
                            ) { array, offset, count ->
                                ensureExportNotCancelled(exportToken)
                                writer.write(array, offset, count)
                                count
                            }
                            ensureExportNotCancelled(exportToken)
                            if (writer.totalSampleBytesWritten <= 0L) {
                                throw IOException("Requested timeline range produced no PCM")
                            }
                            durationMillis = (
                                writer.totalSampleBytesWritten * 1000.0 / maxOf(exportFillRate, 1L).toDouble()
                            ).toLong()
                        }
                        val expectedOutputBytes = writer.totalFileBytesWritten
                        val stagingId = target.id
                        val verifiedOutput = verifyWavOutputTargetAndDigest(
                            context = this@ReverbService,
                            target = target,
                            expectedFileBytes = expectedOutputBytes,
                            expectedPrefix = writer.expectedHeaderBytes,
                            payloadOffsetBytes = writer.payloadOffsetBytes,
                            payloadBytes = writer.totalSampleBytesWritten,
                            expectedPayloadSha256 = writer.payloadSha256,
                        )
                        cleanupDigest = verifiedOutput.digest
                        if (!putVerifiedExportStaging(this@ReverbService, target, verifiedOutput)) {
                            Log.w(TAG, "Verified export recovery marker could not be persisted: ${target.id}")
                        }
                        verifiedComplete = true
                        ensureExportNotCancelled(exportToken)
                        val finalizedTarget = finalizeOutputTarget(this@ReverbService, target, verifiedOutput)
                        outTarget = finalizedTarget
                        if (!removeVerifiedExportStaging(this@ReverbService, target.storageType, stagingId)) {
                            Log.w(TAG, "Unable to clear verified export recovery marker: $stagingId")
                        }
                        ensureExportNotCancelled(exportToken)
                        val recording = buildRecordingEntity(
                            this@ReverbService,
                            finalizedTarget,
                            durationMillis,
                            buildCodecSummary(
                                this@ReverbService,
                                exportFormat,
                                exportSampleRate,
                                exportChannelMode.channelCount,
                                exportSampleFormat,
                            ),
                            knownSizeBytes = expectedOutputBytes,
                        )
                        ensureExportNotCancelled(exportToken)
                        committed = markExportCommitted(exportToken)
                        if (!committed) {
                            throw InterruptedIOException("Export cancelled")
                        }
                        // The verified file is now irrevocably committed. Catalog registration
                        // is metadata: retry it, but never delete valid audio if SQLite fails.
                        val cataloguedRecording = runCatching {
                            runBlocking { RecordingRepository.register(this@ReverbService, recording) }
                        }.onFailure { error ->
                            Log.e(TAG, "Unable to register committed export ${recording.id}", error)
                        }.getOrDefault(recording)
                        finishExportSuccess(exportToken, receiver, cataloguedRecording)
                    } catch (cancelled: InterruptedIOException) {
                        Log.i(TAG, "Export cancelled for ${outTarget?.displayName ?: newFileName}")
                        finishExportCancelled(exportToken, receiver)
                    } catch (e: Exception) {
                        if (exportToken.cancelled.get()) {
                            Log.i(TAG, "Export cancelled for ${outTarget?.displayName ?: newFileName}", e)
                            finishExportCancelled(exportToken, receiver)
                            return@Callable Unit
                        }
                        Log.e(TAG, "Error while exporting audio history into ${outTarget?.displayName ?: newFileName}", e)
                        val message = userFacingError(
                            getString(R.string.error_during_writing_history_into) +
                                (outTarget?.displayName ?: newFileName),
                            e,
                        )
                        reportError(message)
                        finishExportFailure(exportToken, receiver, message, e)
                    } finally {
                        closeLeaseOnce()
                        if (shouldDeleteExportTarget(
                            cancelled = exportToken.cancelled.get(),
                            verifiedComplete = verifiedComplete,
                            committed = committed,
                            preserveVerifiedOutput = exportToken.preserveVerifiedOutput.get(),
                        )) {
                            deleteOutputTarget(outTarget, cleanupDigest)
                        }
                        clearExportState(exportToken)
                        Thread.interrupted()
                    }
                    Unit
                },
            ) {
                override fun done() {
                    if (!exportToken.started.get()) {
                        closeLeaseOnce()
                    }
                }
            }
        val accepted = synchronized(exportStateLock) {
            if (activeExportToken !== exportToken || exportToken.cancelled.get()) {
                false
            } else {
                activeExportFuture = exportTask
                true
            }
        }
        if (!accepted) {
            closeLeaseOnce()
            exportTask.cancel(true)
            return
        }
        try {
            exportWorkExecutor.execute(exportTask)
        } catch (e: RejectedExecutionException) {
            closeLeaseOnce()
            clearExportState(exportToken)
            Log.w(TAG, "Export rejected because the service is shutting down", e)
            finishExportFailure(exportToken, receiver, getString(R.string.save_failed), e)
        }
    }

    private fun beginExport(receiver: AudioFileReceiver): ExportCancellationToken? =
        synchronized(exportStateLock) {
            if (activeExportToken != null) {
                null
            } else {
                ExportCancellationToken(nextExportTokenId.getAndIncrement()).also { token ->
                    activeExportToken = token
                    activeExportFuture = null
                    activeExportReceiver = receiver
                }
            }
        }

    private fun isExportPending(token: ExportCancellationToken): Boolean =
        synchronized(exportStateLock) {
            activeExportToken === token && !token.cancelled.get()
        }

    private fun markExportCommitted(token: ExportCancellationToken): Boolean =
        synchronized(exportStateLock) {
            activeExportToken === token &&
                !token.cancelled.get() &&
                token.committed.compareAndSet(false, true)
        }

    private fun clearExportState(token: ExportCancellationToken) {
        val cleared = synchronized(exportStateLock) {
            clearExportStateLocked(token)
        }
        if (cleared) refreshForegroundAfterExport()
    }

    private fun clearExportStateLocked(token: ExportCancellationToken): Boolean {
        if (activeExportToken !== token) return false
        activeExportToken = null
        activeExportFuture = null
        activeExportReceiver = null
        return true
    }

    fun applyUpdatedPreferences() {
        audioHandler.post {
            try {
                applyConfiguredPreferencesOnAudioThread()
            } catch (error: Exception) {
                pauseListeningAfterPersistenceFailure("apply recorder settings", error)
            }
        }
    }

    private fun applyConfiguredPreferencesOnAudioThread() {
        check(audioHandler.looper == Looper.myLooper())
        val configured = readConfiguredCaptureSnapshot()
        val captureConfigChanged = configured != configuredCaptureSnapshot
        val restartInput = state == STATE_LISTENING && isListeningEnabled() && captureConfigChanged

        if (restartInput) {
            audioHandler.removeCallbacks(audioReader)
            sealActiveChunks()
            releaseAudioRecord()
            startAudioInputOnAudioThread()
        } else {
            // While capture is active, the fields above describe the operational
            // AudioRecord configuration, which may be a hardware fallback from the
            // user's requested configuration. Reloading requested values here would
            // relabel the existing PCM stream without reopening AudioRecord.
            if (state != STATE_LISTENING) {
                loadConfiguredPreferences()
            }
            configurePersistentBuffer()
            val hasActiveTarget = ensureActiveCaptureTargetOnAudioThread()
            if (state == STATE_LISTENING && (!hasActiveTarget || !hasWritableCaptureTarget())) {
                pauseListeningForNoWritableBufferOnAudioThread()
            }
        }
        updateWakeLockState()
        syncOneShotFullQuickTileOnAudioThread()
    }

    private fun notifyReceiver(
        receiver: AudioFileReceiver?,
        recording: RecordingEntity,
    ) {
        receiver ?: return
        mainHandler.post { receiver.fileReady(recording) }
    }

    private fun notifyReceiverFailure(
        receiver: AudioFileReceiver?,
        message: String,
        error: Throwable? = null,
    ) {
        receiver ?: return
        mainHandler.post { receiver.fileFailed(message, error) }
    }

    private fun notifyReceiverCancelled(receiver: AudioFileReceiver?) {
        receiver ?: return
        mainHandler.post { receiver.fileCancelled() }
    }

    private fun finishExportSuccess(
        token: ExportCancellationToken,
        receiver: AudioFileReceiver?,
        recording: RecordingEntity,
    ) {
        if (token.terminalDelivered.compareAndSet(false, true)) {
            notifyReceiver(receiver, recording)
        }
    }

    private fun finishExportFailure(
        token: ExportCancellationToken,
        receiver: AudioFileReceiver?,
        message: String,
        error: Throwable? = null,
    ) {
        if (token.cancelled.get()) {
            finishExportCancelled(token, receiver)
            return
        }
        if (token.terminalDelivered.compareAndSet(false, true)) {
            notifyReceiverFailure(receiver, message, error)
        }
    }

    private fun finishExportCancelled(
        token: ExportCancellationToken,
        receiver: AudioFileReceiver?,
    ) {
        if (token.terminalDelivered.compareAndSet(false, true)) {
            notifyReceiverCancelled(receiver)
        }
    }

    @Throws(InterruptedIOException::class)
    private fun ensureExportNotCancelled(token: ExportCancellationToken) {
        if (token.cancelled.get()) {
            throw InterruptedIOException("Export cancelled")
        }
    }

    private fun deleteOutputTarget(
        target: RecordingOutputTarget?,
        expectedDigest: CopyDigest?,
    ) {
        if (target == null) return
        if (expectedDigest == null) {
            Log.w(TAG, "Retaining unverified export staging because cleanup identity is uncertain: ${target.id}")
            return
        }
        if (!suppressAndDeleteOutputTarget(this, target, expectedDigest = expectedDigest)) {
            Log.w(TAG, "Deferred cleanup for export target ${target.id}")
        }
    }

    private fun alignDown(value: Long, alignment: Long): Long {
        if (value <= 0L || alignment <= 1L) return value.coerceAtLeast(0L)
        return value - value % alignment
    }

    private fun chunkStore(bufferSlot: BufferSlot): PersistentAudioChunkStore {
        return when (bufferSlot) {
            BufferSlot.ONE_SHOT -> oneShotAudioChunkStore
            BufferSlot.LOOPING -> loopingAudioChunkStore
        }
    }

    private fun availableBufferedSampleBytes(bufferSlot: BufferSlot = BufferSlot.LOOPING): Long {
        if (!::loopingAudioChunkStore.isInitialized || !::oneShotAudioChunkStore.isInitialized) return 0L
        return chunkStore(bufferSlot).countFilledBytes()
    }

    private fun availableBufferedDurationSeconds(bufferSlot: BufferSlot = BufferSlot.LOOPING): Double {
        if (!::loopingAudioChunkStore.isInitialized || !::oneShotAudioChunkStore.isInitialized) return 0.0
        return chunkStore(bufferSlot).durationSeconds()
    }

    private fun canExportBufferedAudio(): Boolean {
        return availableBufferedDurationSeconds(BufferSlot.ONE_SHOT) > 0.0 ||
            availableBufferedDurationSeconds(BufferSlot.LOOPING) > 0.0 ||
            state == STATE_LISTENING ||
            state == STATE_PAUSED
    }

    private fun appendCapturedAudio(array: ByteArray, offset: Int, count: Int) {
        when (activeBufferSlot) {
            BufferSlot.LOOPING -> {
                if (loopingBufferEnabled) {
                    loopingAudioChunkStore.append(array, offset, count)
                }
            }

            BufferSlot.ONE_SHOT -> {
                val writtenToOneShot = if (oneShotBufferEnabled) {
                    oneShotAudioChunkStore.append(array, offset, count)
                } else {
                    0
                }
                val oneShotFull = oneShotBufferEnabled && oneShotAudioChunkStore.isFull()
                if (oneShotFull) {
                    syncOneShotFullQuickTileOnAudioThread(refreshTiles = false)
                    if (loopingBufferEnabled) {
                        switchActiveBufferOnAudioThread(BufferSlot.LOOPING)
                        val overflow = count - writtenToOneShot
                        if (overflow > 0) {
                            loopingAudioChunkStore.append(array, offset + writtenToOneShot, overflow)
                        }
                    } else {
                        publishQuickTileSnapshotOnAudioThread(refreshTiles = true)
                    }
                }
            }
        }
        if (state == STATE_LISTENING && !hasWritableCaptureTarget()) {
            pauseListeningForNoWritableBufferOnAudioThread()
        }
    }

    private fun hasWritableCaptureTarget(): Boolean {
        return when (activeBufferSlot) {
            BufferSlot.ONE_SHOT -> oneShotBufferEnabled && !oneShotAudioChunkStore.isFull()
            BufferSlot.LOOPING -> loopingBufferEnabled
        }
    }

    private fun pauseListeningForNoWritableBufferOnAudioThread(
        generation: Long = listeningCommandGeneration.get(),
    ) {
        check(audioHandler.looper == Looper.myLooper())
        val persisted = synchronized(listeningIntentLock) {
            if (generation != listeningCommandGeneration.get() || state != STATE_LISTENING) return
            val prefs = getRecorderPreferences(this)
            val committed = prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false).commit()
            listeningCommandGeneration.incrementAndGet()
            state = STATE_PAUSED
            committed
        }
        RecordingIncidentStore.recordKnownCaptureStop(this)
        audioHandler.removeCallbacks(audioReader)
        try {
            sealActiveChunks()
        } catch (error: Exception) {
            reportPersistentStoreFailure("seal while auto-pausing", error)
        } finally {
            releaseAudioRecord()
            updateWakeLockState()
        }
        if (!persisted) {
            reportError(getString(R.string.recorder_state_persist_failed))
        }
        publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
        mainHandler.post {
            if (state == STATE_LISTENING) return@post
            requestServiceStopWhenExportIdle()
        }
    }

    private fun sealActiveChunks() {
        forEachAudioStore(PersistentAudioChunkStore::sealActiveChunk)
    }

    private fun requestDurabilitySyncIfDue(nowNanos: Long = System.nanoTime()) {
        check(audioHandler.looper == Looper.myLooper())
        if (state != STATE_LISTENING || audioRecord == null) return
        val previous = lastDurabilitySyncRequestNanos
        if (previous != 0L && nowNanos - previous < ACTIVE_PAYLOAD_SYNC_INTERVAL_NANOS) return
        if (!durabilitySyncInFlight.compareAndSet(false, true)) return
        lastDurabilitySyncRequestNanos = nowNanos
        try {
            durabilitySyncExecutor.execute {
                try {
                    syncDirtyAudioPayloads()
                } finally {
                    durabilitySyncInFlight.set(false)
                }
            }
        } catch (error: RejectedExecutionException) {
            durabilitySyncInFlight.set(false)
            if (state == STATE_LISTENING) {
                Log.w(TAG, "Durability sync rejected while capture is active", error)
            }
        }
    }

    private fun syncDirtyAudioPayloads() {
        try {
            forEachAudioStore { store ->
                store.syncActivePayloadToDisk()
            }
        } catch (error: Exception) {
            pauseListeningAfterPersistenceFailure("payload sync", error)
            return
        }
        if (RecordingQuickTiles.hasListeningServices()) {
            audioHandler.post {
                if (state == STATE_LISTENING) {
                    publishQuickTileSnapshotOnAudioThread(
                        refreshTiles = false,
                        persistDurations = false,
                    )
                }
            }
        }
    }

    private fun pauseListeningAfterPersistenceFailure(operation: String, error: Exception) {
        val generation = synchronized(listeningIntentLock) {
            persistenceFailureBlocked = true
            val nextGeneration = listeningCommandGeneration.incrementAndGet()
            if (state == STATE_LISTENING) state = STATE_PAUSED
            nextGeneration
        }
        reportPersistentStoreFailure(operation, error)
        RecordingIncidentStore.recordKnownCaptureStop(this)
        audioHandler.post {
            if (generation != listeningCommandGeneration.get() || state == STATE_LISTENING) return@post
            audioHandler.removeCallbacks(audioReader)
            runCatching { sealActiveChunks() }
                .onFailure { sealError -> reportPersistentStoreFailure("seal after persistence failure", sealError) }
            releaseAudioRecord()
            updateWakeLockState()
            publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
            mainHandler.post {
                if (state != STATE_LISTENING) requestServiceStopWhenExportIdle()
            }
        }
    }

    private fun checkpointAudioStores(operation: String) {
        try {
            forEachAudioStore(PersistentAudioChunkStore::checkpoint)
        } catch (error: Exception) {
            reportPersistentStoreFailure(operation, error)
        }
    }

    private inline fun forEachAudioStore(action: (PersistentAudioChunkStore) -> Unit) {
        var firstFailure: Exception? = null

        try {
            action(loopingAudioChunkStore)
        } catch (error: Exception) {
            firstFailure = error
        }
        try {
            action(oneShotAudioChunkStore)
        } catch (error: Exception) {
            firstFailure?.addSuppressed(error) ?: run { firstFailure = error }
        }

        firstFailure?.let { throw it }
    }

    private fun flushAudioRecord() {
        check(audioHandler.looper == Looper.myLooper())
        if (audioRecord == null) return
        audioHandler.removeCallbacks(audioReader)
        audioReader.run()
    }

    private fun readCaptureIntoScratch(generation: Long): Int {
        if (generation != listeningCommandGeneration.get()) return 0
        val currentRecord = audioRecord ?: return 0
        if (audioRecordGeneration != generation) return 0
        val frameBytes = (channelMode.channelCount * pcmSampleFormat.bytesPerSample).coerceAtLeast(1)
        val requestedBytes = captureReadByteCount(
            sampleRate = sampleRate,
            frameBytes = frameBytes,
            capacityBytes = captureScratch.size,
            visualizationActive = visualizationCallbacks.current() != null,
            appUiForeground = appUiForeground,
        )
        captureBuffer.clear()
        val read = currentRecord.read(captureBuffer, requestedBytes, AudioRecord.READ_BLOCKING)
        if (read == AudioRecord.ERROR_DEAD_OBJECT) {
            if (generation != listeningCommandGeneration.get() || audioRecordGeneration != generation) return 0
            if (!restartAudioRecordOnAudioThread(generation) &&
                state == STATE_LISTENING &&
                !persistenceFailureBlocked
            ) {
                throw IOException("Audio input disconnected")
            }
            return 0
        }
        if (read < 0) {
            throw IOException("AudioRecord read failed: $read")
        }

        if (generation != listeningCommandGeneration.get() || audioRecord !== currentRecord || audioRecordGeneration != generation) return read
        val alignedRead = read - read % frameBytes
        if (alignedRead > 0) {
            captureBuffer.position(0)
            captureBuffer.limit(alignedRead)
            captureBuffer.get(captureScratch, 0, alignedRead)
            publishVisualization(captureScratch, 0, alignedRead)
            appendCapturedAudio(captureScratch, 0, alignedRead)
            requestDurabilitySyncIfDue()
        }

        if (state != STATE_LISTENING) return read
        if (audioRecord !== currentRecord) return read
        if (read > 0) {
            audioHandler.post(audioReader)
        } else {
            audioHandler.postDelayed(audioReader, EMPTY_READ_RETRY_MILLIS)
        }
        return read
    }

    private fun restartAudioRecordOnAudioThread(generation: Long): Boolean {
        check(audioHandler.looper == Looper.myLooper())
        if (generation != listeningCommandGeneration.get() || audioRecordGeneration != generation) return false
        audioHandler.removeCallbacks(audioReader)
        sealActiveChunks()
        releaseAudioRecord()
        if (generation != listeningCommandGeneration.get() || state != STATE_LISTENING || !isListeningEnabled()) return false
        val record = createAudioRecord() ?: return false
        audioRecord = record
        audioRecordGeneration = generation
        if (generation != listeningCommandGeneration.get() || !isListeningEnabled()) {
            releaseAudioRecord()
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            releaseAudioRecord()
            return false
        }
        return try {
            record.startRecording()
            if (generation != listeningCommandGeneration.get() || !isListeningEnabled()) {
                releaseAudioRecord()
                false
            } else if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                if (!armCaptureIncidentTrackingOnAudioThread()) {
                    false
                } else if (generation != listeningCommandGeneration.get() || state != STATE_LISTENING) {
                    false
                } else {
                    lastDurabilitySyncRequestNanos = System.nanoTime()
                    audioHandler.post(audioReader)
                    true
                }
            } else {
                releaseAudioRecord()
                false
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to recover AudioRecord", error)
            releaseAudioRecord()
            false
        }
    }

    private val audioReader = Runnable {
        val generation = audioRecordGeneration
        if (generation == Long.MIN_VALUE || generation != listeningCommandGeneration.get()) return@Runnable
        try {
            readCaptureIntoScratch(generation)
        } catch (error: Exception) {
            Log.e(TAG, "Audio capture failed", error)
            failListeningOnAudioThread(
                getString(R.string.audio_input_init_failed),
                error,
                audioRecordGeneration,
            )
        }
    }

    private fun failListeningOnAudioThread(
        message: String,
        error: Throwable?,
        generation: Long = listeningCommandGeneration.get(),
    ) {
        check(audioHandler.looper == Looper.myLooper())
        val persisted = synchronized(listeningIntentLock) {
            if (generation != listeningCommandGeneration.get()) return
            val prefs = getRecorderPreferences(this)
            val committed = prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false).commit()
            // A fatal recorder failure must invalidate the active capture even if the
            // preference write cannot reach disk. SharedPreferences has already applied
            // the value to its in-memory map when commit() returns false, so rolling it
            // back to true here would leave the stopped service claiming listening is
            // enabled. A later process may retry the user's durable intent if disk still
            // contains true.
            listeningCommandGeneration.incrementAndGet()
            state = STATE_READY
            committed
        }
        RecordingIncidentStore.recordKnownCaptureStop(this)
        audioHandler.removeCallbacks(audioReader)
        runCatching { sealActiveChunks() }
        releaseAudioRecord()
        updateWakeLockState()
        publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
        reportError(
            if (!persisted) getString(R.string.recorder_state_persist_failed)
            else if (error == null) message
            else userFacingError(message, error),
        )
        mainHandler.post {
            if (state == STATE_LISTENING) return@post
            requestServiceStopWhenExportIdle()
        }
    }

    private fun runtimeCaptureActiveOnAudioThread(): Boolean {
        check(audioHandler.looper == Looper.myLooper())
        val record = audioRecord ?: return false
        if (state != STATE_LISTENING || !isListeningEnabled()) return false
        if (audioRecordGeneration != listeningCommandGeneration.get()) return false
        return runCatching {
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
    }

    private fun buildRecordingTileSnapshotOnAudioThread(): RecordingTileSnapshot {
        check(audioHandler.looper == Looper.myLooper())
        return recordingTileSnapshot(
            listeningIntentEnabled = isListeningEnabled(),
            runtimeCaptureActive = runtimeCaptureActiveOnAudioThread(),
            activeBuffer = activeBufferSlot,
            oneShotEnabled = oneShotBufferEnabled,
            oneShotFull = oneShotBufferEnabled && oneShotAudioChunkStore.isFull(),
            loopingEnabled = loopingBufferEnabled,
            oneShotSeconds = availableBufferedDurationSeconds(BufferSlot.ONE_SHOT).toFloat(),
            loopingSeconds = availableBufferedDurationSeconds(BufferSlot.LOOPING).toFloat(),
        )
    }

    private fun publishQuickTileSnapshotOnAudioThread(
        refreshTiles: Boolean,
        persistDurations: Boolean = false,
    ): RecordingTileSnapshot {
        check(audioHandler.looper == Looper.myLooper())
        val snapshot = try {
            buildRecordingTileSnapshotOnAudioThread()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to refresh quick tile snapshot", error)
            RecordingQuickTileStateCache.read(this).copy(
                listening = false,
                activeBuffer = activeBufferSlot,
                oneShotEnabled = oneShotBufferEnabled,
                oneShotFull = false,
                loopingEnabled = loopingBufferEnabled,
            )
        }
        RecordingQuickTiles.publishSnapshot(this, snapshot, requestSystemRefresh = refreshTiles)
        if (persistDurations) RecordingQuickTileStateCache.persistCurrentDurations(this)
        return snapshot
    }

    internal fun getRecordingTileSnapshot(callback: (RecordingTileSnapshot) -> Unit) {
        audioHandler.post {
            val snapshot = publishQuickTileSnapshotOnAudioThread(refreshTiles = false)
            mainHandler.post { callback(snapshot) }
        }
    }

    fun getState(callback: StateCallback) {
        audioHandler.post {
            val commandGeneration = listeningCommandGeneration.get()
            try {
                val oneShotSeconds = availableBufferedDurationSeconds(BufferSlot.ONE_SHOT).toFloat()
                val oneShotBytes = availableBufferedSampleBytes(BufferSlot.ONE_SHOT)
                val loopingSeconds = availableBufferedDurationSeconds(BufferSlot.LOOPING).toFloat()
                val loopingBytes = availableBufferedSampleBytes(BufferSlot.LOOPING)
                val oneShotFull = oneShotBufferEnabled && oneShotAudioChunkStore.isFull()
                val listening = isLogicalListeningState(state, isListeningEnabled())
                val activeBuffer = activeBufferSlot
                mainHandler.post {
                    callback.state(
                        commandGeneration,
                        listening,
                        activeBuffer,
                        oneShotSeconds,
                        oneShotBytes,
                        loopingSeconds,
                        loopingBytes,
                        oneShotBufferEnabled,
                        oneShotFull,
                        loopingBufferEnabled,
                    )
                }
            } catch (error: Exception) {
                reportPersistentStoreFailure("read recorder state", error)
                val listening = isLogicalListeningState(state, isListeningEnabled())
                mainHandler.post {
                    callback.state(
                        commandGeneration,
                        listening,
                        activeBufferSlot,
                        0f,
                        0L,
                        0f,
                        0L,
                        oneShotBufferEnabled,
                        false,
                        loopingBufferEnabled,
                    )
                }
            }
        }
    }

    fun setAppUiForeground(owner: Any, foreground: Boolean) {
        appUiForeground = appUiForegroundOwners.update(owner, foreground)
    }

    fun setVisualizationCallback(callback: VisualizationCallback) {
        if (!visualizationCallbacks.register(callback)) return
        pendingVisualizationFrame.set(null)
        if (!::audioHandler.isInitialized) return
        audioHandler.post {
            if (visualizationCallbacks.current() === callback) {
                visualizationAnalyzer.reset()
                visualizationFaulted = false
            }
        }
    }

    fun clearVisualizationCallback(callback: VisualizationCallback) {
        if (!visualizationCallbacks.unregister(callback)) return
        pendingVisualizationFrame.set(null)
        val fallback = visualizationCallbacks.current()
        if (!::audioHandler.isInitialized) return
        audioHandler.post {
            if (visualizationCallbacks.current() === fallback) {
                visualizationAnalyzer.reset()
                visualizationFaulted = false
            }
        }
    }

    private fun clearAllVisualizationCallbacks() {
        visualizationCallbacks.clearAll()
        pendingVisualizationFrame.set(null)
        if (!::audioHandler.isInitialized) return
        audioHandler.post {
            if (visualizationCallbacks.current() == null) {
                visualizationAnalyzer.reset()
                visualizationFaulted = false
            }
        }
    }

    private fun publishVisualization(array: ByteArray, offset: Int, count: Int) {
        val callback = visualizationCallbacks.current() ?: return
        if (visualizationFaulted) return
        // The capture read itself is display-rate while the visualizer is attached. Analyze every
        // completed read: a second coarse throttle only adds input-to-pixel latency and can skip
        // short transients entirely.
        val frame = try {
            visualizationAnalyzer.analyze(
                array = array,
                offset = offset,
                count = count,
                sampleFormat = pcmSampleFormat,
                channelCount = channelMode.channelCount,
                sampleRate = sampleRate,
            )
        } catch (error: Exception) {
            visualizationFaulted = true
            Log.w(TAG, "Audio visualization disabled until the UI reconnects", error)
            return
        }
        if (visualizationCallbacks.current() !== callback) return
        pendingVisualizationFrame.set(frame)
        if (visualizationDispatchScheduled.compareAndSet(false, true)) {
            mainHandler.post(visualizationDispatcher)
        }
    }

    private val visualizationDispatcher = object : Runnable {
        override fun run() {
            val frame = pendingVisualizationFrame.getAndSet(null)
            val callback = visualizationCallbacks.current()
            if (frame != null && callback != null) {
                callback.frame(frame)
            }

            visualizationDispatchScheduled.set(false)
            if (
                pendingVisualizationFrame.get() != null &&
                visualizationCallbacks.current() != null &&
                visualizationDispatchScheduled.compareAndSet(false, true)
            ) {
                mainHandler.post(this)
            }
        }
    }

    fun getConfigurationSnapshot(): RecorderConfigurationSnapshot {
        return cachedConfigSnapshot ?: RecorderConfigurationSnapshot(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleFormat = PcmSampleFormat.PCM_16,
            sampleRate = PREFERRED_DEFAULT_SAMPLE_RATE,
            sourceMode = AudioSourceMode.defaultMode(),
            channelMode = ChannelMode.MONO,
            routeMode = InputRouteMode.AUTO,
        )
    }

    fun clearBuffer(bufferSlot: BufferSlot = BufferSlot.LOOPING) {
        audioHandler.post {
            if (bufferSlot == BufferSlot.ONE_SHOT) {
                clearOneShotFullQuickTileCacheOnAudioThread()
            }
            try {
                chunkStore(bufferSlot).clear()
            } catch (error: Exception) {
                reportPersistentStoreFailure("clear history", error)
            } finally {
                if (bufferSlot == BufferSlot.ONE_SHOT) {
                    syncOneShotFullQuickTileOnAudioThread()
                    RecordingQuickTileStateCache.persistCurrentDurations(this)
                } else {
                    publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
                }
            }
        }
    }

    inner class BackgroundRecorderBinder : Binder() {
        val service: ReverbService
            get() = this@ReverbService
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_APPLY_SETTINGS) {
            applyUpdatedPreferences()
        }
        if (intent?.action == ACTION_EXPORT_KEEPALIVE) {
            if (!hasActiveExport()) requestServiceStopWhenExportIdle()
            return START_NOT_STICKY
        }
        if (isDebuggableBuild() && intent?.action == ACTION_DEBUG_ENABLE_LISTENING && !isListeningEnabled()) {
            setListeningEnabled(true)
        }
        // A platform sticky restart reaches onStartCommand before the audio-thread
        // initialization posted from onCreate has finished. Restore the logical
        // listening state immediately so the foreground-service deadline is met;
        // the queued audio work remains ordered behind initial configuration.
        val listeningEnabled = isListeningEnabled()
        val generation = listeningCommandGeneration.get()
        if (listeningEnabled && (foregroundStartBlocked || persistenceFailureBlocked)) {
            requestServiceStopWhenExportIdle()
            return START_NOT_STICKY
        }
        if (state != STATE_LISTENING && listeningEnabled) {
            state = STATE_LISTENING
            updateWakeLockState()
        }
        if (listeningEnabled && state == STATE_LISTENING) {
            try {
                val exporting = hasActiveExport()
                promoteForeground(
                    foregroundServiceTypesForWork(listening = true, exporting = exporting),
                    exporting = exporting,
                )
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to enter microphone foreground state", error)
                pauseListeningAfterForegroundStartFailure(generation, error)
                return START_NOT_STICKY
            }
            audioHandler.post { startAudioInputOnAudioThread(generation) }
        } else {
            requestServiceStopWhenExportIdle()
            return START_NOT_STICKY
        }
        handleDebugCommand(intent)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        audioHandler.post { checkpointAudioStores("task-removed checkpoint") }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        foregroundServiceTimedOut = true
        RecordingIncidentStore.recordCaptureInterrupted(
            this,
            "Foreground service timed out while capture was running",
        )
        if ((fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0) {
            Log.e(TAG, "Data-sync foreground-service timeout; preserving source audio and verified export output")
            requestExportCancellation(preserveVerifiedOutput = true)
        }
        // A type transition can race the timeout callback. If microphone capture became
        // active meanwhile, pause only the runtime capture and preserve the durable user
        // intent; a foreground UI bind can restart it under the microphone-only FGS type.
        synchronized(listeningIntentLock) {
            if (state == STATE_LISTENING && isListeningEnabled()) {
                listeningCommandGeneration.incrementAndGet()
                state = STATE_PAUSED
            }
        }
        audioHandler.post {
            audioHandler.removeCallbacks(audioReader)
            runCatching { sealActiveChunks() }
            releaseAudioRecord()
            updateWakeLockState()
            publishQuickTileSnapshotOnAudioThread(refreshTiles = true, persistDurations = true)
        }
        stopForegroundTracked()
        stopSelf()
    }

    private fun buildNotification(exporting: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, BACKGROUND_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (exporting) R.string.saving else R.string.quick_tile_recording))
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun promoteForeground(types: Int, exporting: Boolean) {
        require(types != 0) { "Foreground service requires at least one active type" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(exporting), types)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(exporting))
        }
        foregroundServiceTypes = types
    }

    private fun stopForegroundTracked() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundServiceTypes = 0
    }

    fun consumePendingError(): String? = pendingError.getAndSet(null)

    private fun reportError(message: String) {
        pendingError.set(message)
    }

    private fun reportPersistentStoreFailure(operation: String, error: Throwable) {
        Log.e(TAG, "Persistent audio store $operation failed", error)
        reportError(userFacingError(getString(R.string.recorder_state_persist_failed), error))
    }

    private fun checkpointPersistentStore(operation: String) {
        checkpointAudioStores(operation)
    }

    private fun userFacingError(message: String, error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank() || message.contains(detail)) message else "$message $detail"
    }

    private fun configurePersistentBuffer() {
        val configuration = withRetentionPersistenceLock {
            val historyExists = loopingAudioChunkStore.hasData() || oneShotAudioChunkStore.hasData()
            val prefs = getRecorderPreferences(this)
            val preferenceValues = readRetentionPreferenceValues(prefs)
            val recovery = readRetentionRecoveryConfiguration(this)
            val primary = retentionConfigurationFromPreferences(
                values = preferenceValues,
                recoveryFallback = recovery,
                // Legacy installs predate the checksum. Trust them only for the one-time
                // bootstrap where no independent recovery journal exists yet.
                allowLegacyWithoutDigest = recovery == null,
            )
            val resolved = resolveRetentionConfiguration(
                primary = primary,
                recovery = recovery,
                historyExists = historyExists,
            ) ?: throw IOException(
                "Retention configuration is unavailable; preserving existing buffered audio",
            )
            val resolvedConfiguration = resolved.configuration

            // This is a write-ahead durability barrier. No retention limit that can retire audio
            // reaches either chunk store until its exact configuration is independently recoverable.
            if (!writeRetentionRecoveryConfiguration(this, resolvedConfiguration)) {
                throw IOException("Unable to persist retention recovery configuration")
            }
            if (
                (resolved.source != RetentionConfigurationSource.PREFERENCES ||
                    !retentionPreferenceDigestMatches(preferenceValues, resolvedConfiguration)) &&
                !restoreRetentionConfigurationToPreferences(prefs, resolvedConfiguration)
            ) {
                // The recovery journal is already durable, so this is not grounds to mutate or
                // discard history. Keep using the journal and surface the preference failure.
                reportError(getString(R.string.recorder_state_persist_failed))
            }
            resolvedConfiguration
        }

        val mode = configuration.mode
        val frameBytes = channelMode.channelCount * pcmSampleFormat.bytesPerSample
        val retentionValue = normalizeRetentionValue(
            mode,
            when (mode) {
                RetentionMode.SIZE -> configuration.loopingSizeBytes
                RetentionMode.TIME -> configuration.loopingSeconds
            },
            frameBytes,
        )
        loopingBufferEnabled = retentionValue > 0L
        loopingAudioChunkStore.configure(
            requestedRetentionMode = mode,
            requestedRetentionValue = retentionValue,
            requestedSampleRate = sampleRate,
            requestedChannelCount = channelMode.channelCount,
            sampleFormat = pcmSampleFormat,
        )
        val oneShotRetentionValue = normalizeRetentionValue(
            mode,
            when (mode) {
                RetentionMode.SIZE -> configuration.oneShotSizeBytes
                RetentionMode.TIME -> configuration.oneShotSeconds
            },
            frameBytes,
        )
        oneShotBufferEnabled = oneShotRetentionValue > 0L
        oneShotAudioChunkStore.configure(
            requestedRetentionMode = mode,
            requestedRetentionValue = oneShotRetentionValue,
            requestedSampleRate = sampleRate,
            requestedChannelCount = channelMode.channelCount,
            sampleFormat = pcmSampleFormat,
        )
        if (
            (loopingAudioChunkStore.hasData() || oneShotAudioChunkStore.hasData()) &&
            !isListeningEnabled() &&
            state == STATE_READY
        ) {
            state = STATE_PAUSED
        }
    }

    private fun flushAndPersistBeforeShutdown() {
        if (!::audioHandler.isInitialized) {
            runCatching { loopingAudioChunkStore.close() }
                .onFailure { reportPersistentStoreFailure("close looping store during early shutdown", it) }
            runCatching { oneShotAudioChunkStore.close() }
                .onFailure { reportPersistentStoreFailure("close one-shot store during early shutdown", it) }
            return
        }
        val storeCloseOwnedByAudioThread = runOnAudioThreadAndWait {
            audioHandler.removeCallbacks(audioReader)
            state = STATE_READY
            try {
                sealActiveChunks()
            } catch (error: Exception) {
                reportPersistentStoreFailure("seal during shutdown", error)
            } finally {
                releaseAudioRecord()
                try {
                    forEachAudioStore(PersistentAudioChunkStore::close)
                } catch (error: Exception) {
                    reportPersistentStoreFailure("close during shutdown", error)
                }
            }
        }
        if (!storeCloseOwnedByAudioThread) {
            // A blocking AudioRecord.read() can prevent the audio-thread shutdown
            // work from running. Release capture here so the read is forced to
            // return instead of leaving the microphone/thread alive past onDestroy.
            state = STATE_READY
            audioHandler.removeCallbacks(audioReader)
            releaseAudioRecord()
            runCatching { loopingAudioChunkStore.close() }
                .onFailure { reportPersistentStoreFailure("close looping store after shutdown timeout", it) }
            runCatching { oneShotAudioChunkStore.close() }
                .onFailure { reportPersistentStoreFailure("close one-shot store after shutdown timeout", it) }
        }
    }

    private fun runOnAudioThreadAndWait(block: () -> Unit): Boolean {
        if (!::audioHandler.isInitialized) {
            block()
            return true
        }
        if (Looper.myLooper() == audioHandler.looper) {
            block()
            return true
        }

        val latch = CountDownLatch(1)
        val posted = audioHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            Log.w(TAG, "Audio thread rejected shutdown work")
            return false
        }
        return try {
            latch.await(3, TimeUnit.SECONDS).also { completed ->
                if (!completed) Log.w(TAG, "Timed out waiting for audio-thread shutdown work")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while waiting for audio-thread shutdown work")
            false
        }
    }

    private fun handleDebugCommand(intent: Intent?) {
        if (!isDebuggableBuild()) {
            return
        }
        val action = intent?.action ?: return
        if (!action.startsWith(DEBUG_ACTION_PREFIX)) {
            return
        }
        Log.d(TAG, "handleDebugCommand action=$action")

        val seconds = intent.getFloatExtra(EXTRA_DEBUG_SECONDS, 0f)
        audioHandler.post {
            try {
                when (action) {
                    ACTION_DEBUG_ENABLE_LISTENING -> Unit
                    ACTION_DEBUG_DISABLE_LISTENING -> mainHandler.post { disableListening() }
                    ACTION_DEBUG_CLEAR_BUFFER -> loopingAudioChunkStore.clear()
                    ACTION_DEBUG_INJECT_BUFFER -> injectDebugBuffer(seconds)
                    ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS -> {
                        check(setConfiguredExportTreeUri(this@ReverbService, null)) {
                            "Unable to persist app-storage export directory"
                        }
                        writeDebugReport("force-app-storage-exports")
                    }
                    ACTION_DEBUG_EXPORT_FULL -> exportDebug(FULL_BUFFER_SECONDS)
                    ACTION_DEBUG_EXPORT_SECONDS -> exportDebug(seconds)
                    ACTION_DEBUG_APPLY_SETTINGS -> applyConfiguredPreferencesOnAudioThread()
                    ACTION_DEBUG_CHECKPOINT -> checkpointAudioStores("debug checkpoint")
                    ACTION_DEBUG_LOG_STATE -> logDebugState()
                    ACTION_DEBUG_DUMP_REPORT -> writeDebugReport("manual-dump")
                }
                if (action == ACTION_DEBUG_APPLY_SETTINGS) {
                    writeDebugReport("apply-settings")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Debug action failed: $action", error)
                reportPersistentStoreFailure("debug action $action", error)
                runCatching { writeDebugReport("debug-action:error:${error.javaClass.simpleName}") }
            }
        }
    }

    private fun exportDebug(seconds: Float) {
        if (seconds <= 0f) {
            Log.w(TAG, "Debug export ignored; seconds=$seconds")
            return
        }
        if (!canExportBufferedAudio()) {
            Log.w(TAG, "Debug export ignored; state=$state")
            return
        }
        val bufferSlot = when {
            availableBufferedSampleBytes(BufferSlot.LOOPING) > 0L -> BufferSlot.LOOPING
            availableBufferedSampleBytes(BufferSlot.ONE_SHOT) > 0L -> BufferSlot.ONE_SHOT
            loopingBufferEnabled -> BufferSlot.LOOPING
            else -> BufferSlot.ONE_SHOT
        }
        Log.d(TAG, "exportDebug seconds=$seconds slot=$bufferSlot available=${availableBufferedSampleBytes(bufferSlot)}")
        dumpRecording(seconds, NotifyFileReceiver(this), "", bufferSlot)
    }

    private fun injectDebugBuffer(seconds: Float) {
        if (!isDebuggableBuild() || seconds <= 0f) {
            Log.w(TAG, "injectDebugBuffer ignored seconds=$seconds state=$state")
            return
        }
        if (!hasWritableCaptureTarget()) {
            Log.w(TAG, "injectDebugBuffer ignored; no writable buffer")
            return
        }
        val logicalRetentionBytes = cachedRetentionSampleBytes
        val frameBytes = (channelMode.channelCount * pcmSampleFormat.bytesPerSample).coerceAtLeast(1).toLong()
        val totalBytes = alignDown((seconds * fillRate).toLong().coerceAtLeast(0L), frameBytes)
        val chunk = ByteArray(64 * 1024)
        var remaining = totalBytes
        while (remaining > 0L) {
            val count = alignDown(minOf(chunk.size.toLong(), remaining), frameBytes).toInt()
            if (count <= 0) break
            appendCapturedAudio(chunk, 0, count)
            remaining -= count.toLong()
            if (!hasWritableCaptureTarget()) break
        }
        if (state != STATE_LISTENING) {
            state = STATE_PAUSED
            updateWakeLockState()
        }
        checkpointAudioStores("debug inject checkpoint")
        Log.d(
            TAG,
            "injectDebugBuffer seconds=$seconds logical=$logicalRetentionBytes " +
                "available=${availableBufferedSampleBytes()}",
        )
        writeDebugReport("inject-buffer-${seconds}s")
    }

    private fun logDebugState() {
        val persisted = loopingAudioChunkStore.peekSnapshot()
        val oneShot = oneShotAudioChunkStore.peekSnapshot()
        Log.d(
            TAG,
            "debug-state state=$state " +
                "sampleRate=$sampleRate channels=${channelMode.channelCount} codec=${outputCodec.legacyPrefValue} " +
                "format=${outputFormat.legacyPrefValue} logicalRetention=${cachedRetentionSampleBytes} " +
                "persistedFilled=${persisted?.filledBytes ?: 0} persistedDuration=${persisted?.durationSeconds ?: 0.0} " +
                "chunks=${persisted?.chunkCount ?: 0} oneShotFilled=${oneShot?.filledBytes ?: 0} " +
                "oneShotDuration=${oneShot?.durationSeconds ?: 0.0}",
        )
    }

    private fun writeDebugReport(reason: String) {
        val reportFile = resolveDebugReportFile()
        val persisted = loopingAudioChunkStore.peekSnapshot()
        val oneShot = oneShotAudioChunkStore.peekSnapshot()
        val status =
            buildString {
                append("reason=").append(reason)
                append(" state=").append(state)
                append(" sampleRate=").append(sampleRate)
                append(" channelCount=").append(channelMode.channelCount)
                append(" format=").append(outputFormat.legacyPrefValue)
                append(" codec=").append(outputCodec.legacyPrefValue)
                append(" persistedFilled=").append(persisted?.filledBytes ?: 0)
                append(" persistedDuration=").append(persisted?.durationSeconds ?: 0.0)
                append(" persistedChunks=").append(persisted?.chunkCount ?: 0)
                append(" persistedLastWrite=").append(persisted?.lastWriteAtMillis ?: 0)
                append(" oneShotFilled=").append(oneShot?.filledBytes ?: 0)
                append(" oneShotDuration=").append(oneShot?.durationSeconds ?: 0.0)
                append(" oneShotChunks=").append(oneShot?.chunkCount ?: 0)
                append(" exportDir=").append(describeConfiguredOutputDirectory(this@ReverbService))
            }
        reportFile.appendText(status + "\n---\n")
        Log.d(TAG, "writeDebugReport $status path=${reportFile.absolutePath}")
    }

    private fun resolveDebugReportFile(): File {
        val directory = getSavedRecordingsDirectory(this)
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IOException("Unable to create recordings directory: ${directory.absolutePath}")
        }
        return File(directory, DEBUG_REPORT_FILE_NAME)
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                BACKGROUND_NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @SuppressLint("WakelockTimeout")
    @Synchronized
    private fun updateWakeLockState() {
        if (!isWakeLockEnabled(this)) {
            releaseWakeLock()
            return
        }

        val shouldHoldWakeLock = state == STATE_LISTENING
        if (!shouldHoldWakeLock) {
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld == true) {
            return
        }

        val manager = powerManager ?: getSystemService(PowerManager::class.java)?.also { powerManager = it } ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + WAKE_LOCK_TAG_SUFFIX).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    interface AudioFileReceiver {
        fun fileReady(recording: RecordingEntity)

        fun fileFailed(message: String, error: Throwable? = null) = Unit

        fun fileCancelled() = Unit
    }

    enum class BufferSlot(val storageCode: Byte) {
        ONE_SHOT(0),
        LOOPING(1),
        ;

        companion object {
            fun fromStorageCode(value: Int): BufferSlot? =
                entries.firstOrNull { it.storageCode.toInt() == value }

            fun fromLegacyName(value: String?): BufferSlot? = entries.firstOrNull { it.name == value }
        }
    }

    class TimelineSnapshot internal constructor(
        private val lease: PersistentAudioChunkStore.RangeLease,
    ) : java.io.Closeable {
        val durationSeconds: Double
            get() = lease.durationSeconds

        internal fun acquireRange(startSeconds: Double, endSeconds: Double): PersistentAudioChunkStore.RangeLease? =
            lease.acquireSubRange(startSeconds, endSeconds)

        internal fun sampleWaveformEnvelopeProgressive(
            bucketCount: Int,
            probesPerBucket: Int,
            framesPerProbe: Int,
            onBucket: (bucketIndex: Int, magnitude: Float) -> Boolean,
        ): FloatArray = lease.sampleWaveformEnvelopeProgressive(
            bucketCount = bucketCount,
            probesPerBucket = probesPerBucket,
            framesPerProbe = framesPerProbe,
            onBucket = onBucket,
        )

        override fun close() {
            lease.close()
        }
    }

    interface StateCallback {
        fun state(
            commandGeneration: Long,
            listeningEnabled: Boolean,
            activeBufferSlot: BufferSlot?,
            oneShotSeconds: Float,
            oneShotBytes: Long,
            loopingSeconds: Float,
            loopingBytes: Long,
            oneShotIsEnabled: Boolean,
            oneShotIsFull: Boolean,
            loopingIsEnabled: Boolean,
        )
    }

    data class ListeningCommandResult(
        val accepted: Boolean,
        val generation: Long,
    )

    fun interface VisualizationCallback {
        fun frame(frame: VisualizationFrame)
    }

    data class VisualizationFrame(
        val activity: Float,
        val bins: FloatArray,
    ) {
        companion object {
            val EMPTY = VisualizationFrame(
                activity = 0f,
                bins = FloatArray(AudioVisualizationAnalyzer.OUTPUT_BINS),
            )
        }
    }

    data class RecorderConfigurationSnapshot(
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleFormat: PcmSampleFormat,
        val sampleRate: Int,
        val sourceMode: AudioSourceMode,
        val channelMode: ChannelMode,
        val routeMode: InputRouteMode,
    )

    private data class OperationalConfig(
        val sourceMode: AudioSourceMode,
        val channelMode: ChannelMode,
        val routeMode: InputRouteMode,
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleFormat: PcmSampleFormat,
        val sampleRate: Int,
    )

    private data class ExportCancellationToken(
        val id: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        // `started` flips as the callable begins. Until then, queued cancellation owns
        // lease cleanup; after that point the callable owns it.
        val started: AtomicBoolean = AtomicBoolean(false),
        val committed: AtomicBoolean = AtomicBoolean(false),
        val preserveVerifiedOutput: AtomicBoolean = AtomicBoolean(false),
        val terminalDelivered: AtomicBoolean = AtomicBoolean(false),
    )

    companion object {
        val TAG: String = ReverbService::class.java.simpleName
        const val NOTIFICATION_CHANNEL_ID = "ReverbRecorderChannel"
        const val BACKGROUND_NOTIFICATION_CHANNEL_ID = "ReverbBackgroundRecorderChannel"
        const val FOREGROUND_NOTIFICATION_ID = 458
        const val MIN_AUDIO_RECORD_BUFFER_SIZE = 16 * 1024
        const val CAPTURE_SCRATCH_BYTES = 768 * 1024
        const val ACTIVE_PAYLOAD_SYNC_INTERVAL_NANOS = 1_000_000_000L
        const val VISUALIZATION_CAPTURE_READ_TARGET_MILLIS = 8L
        const val INTERACTIVE_CAPTURE_READ_TARGET_MILLIS = 40L
        const val BACKGROUND_CAPTURE_READ_TARGET_MILLIS = 1_000L
        const val EMPTY_READ_RETRY_MILLIS = 20L
        const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f
        const val DEBUG_ACTION_PREFIX = "app.smallthingz.reverb.debug."
        val nextExportTokenId = AtomicLong(1L)
        const val ACTION_APPLY_SETTINGS = "app.smallthingz.reverb.APPLY_SETTINGS"
        const val ACTION_EXPORT_KEEPALIVE = "app.smallthingz.reverb.EXPORT_KEEPALIVE"
        const val ACTION_DEBUG_ENABLE_LISTENING = "${DEBUG_ACTION_PREFIX}ENABLE_LISTENING"
        const val ACTION_DEBUG_DISABLE_LISTENING = "${DEBUG_ACTION_PREFIX}DISABLE_LISTENING"
        const val ACTION_DEBUG_CLEAR_BUFFER = "${DEBUG_ACTION_PREFIX}CLEAR_BUFFER"
        const val ACTION_DEBUG_INJECT_BUFFER = "${DEBUG_ACTION_PREFIX}INJECT_BUFFER"
        const val ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS = "${DEBUG_ACTION_PREFIX}FORCE_APP_STORAGE_EXPORTS"
        const val ACTION_DEBUG_EXPORT_FULL = "${DEBUG_ACTION_PREFIX}EXPORT_FULL"
        const val ACTION_DEBUG_EXPORT_SECONDS = "${DEBUG_ACTION_PREFIX}EXPORT_SECONDS"
        const val ACTION_DEBUG_APPLY_SETTINGS = "${DEBUG_ACTION_PREFIX}APPLY_SETTINGS"
        const val ACTION_DEBUG_CHECKPOINT = "${DEBUG_ACTION_PREFIX}CHECKPOINT"
        const val ACTION_DEBUG_LOG_STATE = "${DEBUG_ACTION_PREFIX}LOG_STATE"
        const val ACTION_DEBUG_DUMP_REPORT = "${DEBUG_ACTION_PREFIX}DUMP_REPORT"
        const val EXTRA_DEBUG_SECONDS = "seconds"
        const val DEBUG_REPORT_FILE_NAME = "debug-report.txt"

        const val WAKE_LOCK_TAG_SUFFIX = ":reverbBuffer"

        const val STATE_READY = 0
        const val STATE_LISTENING = 1
        const val STATE_PAUSED = 2
    }

}

internal fun captureReadByteCount(
    sampleRate: Int,
    frameBytes: Int,
    capacityBytes: Int,
    visualizationActive: Boolean,
    appUiForeground: Boolean,
): Int {
    val alignedFrameBytes = frameBytes.coerceAtLeast(1)
    val targetMillis = when {
        visualizationActive -> ReverbService.VISUALIZATION_CAPTURE_READ_TARGET_MILLIS
        appUiForeground -> ReverbService.INTERACTIVE_CAPTURE_READ_TARGET_MILLIS
        else -> ReverbService.BACKGROUND_CAPTURE_READ_TARGET_MILLIS
    }
    val targetFrames = maxOf(1L, sampleRate.coerceAtLeast(1).toLong() * targetMillis / 1000L)
    val boundedBytes = minOf(
        capacityBytes.coerceAtLeast(alignedFrameBytes).toLong(),
        targetFrames * alignedFrameBytes.toLong(),
    ).toInt()
    return boundedBytes - boundedBytes % alignedFrameBytes
}

internal fun foregroundServiceTypesForWork(
    listening: Boolean,
    exporting: Boolean,
): Int = when {
    // Never combine the limited dataSync type with long-lived microphone capture.
    // The microphone FGS already owns the service lifetime while recording; if capture
    // stops during an export we switch to dataSync at that boundary.
    listening -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    exporting -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else -> 0
}

internal fun captureSlotNeedsPersistence(
    previousStoredSlot: ReverbService.BufferSlot?,
    requestedSlot: ReverbService.BufferSlot,
): Boolean = previousStoredSlot != requestedSlot

internal fun captureIntentNeedsPersistence(
    previousEnabled: Boolean,
    requestedEnabled: Boolean,
    previousStoredSlot: ReverbService.BufferSlot?,
    requestedSlot: ReverbService.BufferSlot,
): Boolean = previousEnabled != requestedEnabled ||
    (requestedEnabled && captureSlotNeedsPersistence(previousStoredSlot, requestedSlot))

internal fun shouldAttemptAutomaticListeningStart(
    listeningIntentEnabled: Boolean,
    foregroundStartBlocked: Boolean,
    persistenceFailureBlocked: Boolean,
): Boolean = listeningIntentEnabled && !foregroundStartBlocked && !persistenceFailureBlocked

internal fun shouldEnsureRuntimeCaptureAfterInitialization(
    listeningIntentEnabled: Boolean,
    recorderState: Int,
    foregroundStartBlocked: Boolean,
    persistenceFailureBlocked: Boolean,
): Boolean = recorderState == ReverbService.STATE_LISTENING &&
    shouldAttemptAutomaticListeningStart(
        listeningIntentEnabled = listeningIntentEnabled,
        foregroundStartBlocked = foregroundStartBlocked,
        persistenceFailureBlocked = persistenceFailureBlocked,
    )

internal fun shouldRetrySuspendedListeningOnForegroundBind(
    listeningIntentEnabled: Boolean,
    foregroundStartBlocked: Boolean,
    foregroundServiceTimedOut: Boolean,
    persistenceFailureBlocked: Boolean,
): Boolean = listeningIntentEnabled &&
    (foregroundStartBlocked || foregroundServiceTimedOut || persistenceFailureBlocked)

internal enum class CaptureReaderTransition {
    IGNORE,
    ADOPT,
    RESTART,
}

internal fun captureReaderTransition(
    requestedGeneration: Long,
    currentGeneration: Long,
    listening: Boolean,
    recordRunning: Boolean,
): CaptureReaderTransition = when {
    requestedGeneration != currentGeneration || !listening -> CaptureReaderTransition.IGNORE
    recordRunning -> CaptureReaderTransition.ADOPT
    else -> CaptureReaderTransition.RESTART
}

internal fun shouldDeleteExportTarget(
    cancelled: Boolean,
    verifiedComplete: Boolean,
    committed: Boolean,
    preserveVerifiedOutput: Boolean = false,
): Boolean = !committed && (!verifiedComplete || (cancelled && !preserveVerifiedOutput))

internal fun isLogicalListeningState(
    recorderState: Int,
    listeningIntentEnabled: Boolean,
): Boolean = listeningIntentEnabled && recorderState == ReverbService.STATE_LISTENING

internal fun canActivateCaptureBuffer(
    requested: ReverbService.BufferSlot,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
): Boolean = resolveCaptureBufferSlot(
    requested = requested,
    oneShotEnabled = oneShotEnabled,
    oneShotFull = oneShotFull,
    loopingEnabled = loopingEnabled,
) == requested

internal fun resolveAvailableCaptureBufferSlot(
    preferred: ReverbService.BufferSlot,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
): ReverbService.BufferSlot? = when (preferred) {
    ReverbService.BufferSlot.ONE_SHOT -> when {
        oneShotEnabled && !oneShotFull -> ReverbService.BufferSlot.ONE_SHOT
        loopingEnabled -> ReverbService.BufferSlot.LOOPING
        else -> null
    }
    ReverbService.BufferSlot.LOOPING -> when {
        loopingEnabled -> ReverbService.BufferSlot.LOOPING
        oneShotEnabled && !oneShotFull -> ReverbService.BufferSlot.ONE_SHOT
        else -> null
    }
}

internal fun resolveCaptureBufferSlot(
    requested: ReverbService.BufferSlot,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
): ReverbService.BufferSlot? {
    return when (requested) {
        ReverbService.BufferSlot.ONE_SHOT -> when {
            oneShotEnabled && !oneShotFull -> ReverbService.BufferSlot.ONE_SHOT
            oneShotEnabled && oneShotFull && loopingEnabled -> ReverbService.BufferSlot.LOOPING
            else -> null
        }
        ReverbService.BufferSlot.LOOPING -> when {
            loopingEnabled -> ReverbService.BufferSlot.LOOPING
            else -> null
        }
    }
}

internal fun defaultStartupBufferSlot(
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
): ReverbService.BufferSlot {
    return when {
        oneShotEnabled && (!oneShotFull || !loopingEnabled) -> ReverbService.BufferSlot.ONE_SHOT
        loopingEnabled -> ReverbService.BufferSlot.LOOPING
        else -> ReverbService.BufferSlot.ONE_SHOT
    }
}
