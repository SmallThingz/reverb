package app.smallthingz.reverb

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit
import java.util.concurrent.Executors

internal data class RecordingTileSnapshot(
    val listening: Boolean,
    val activeBuffer: ReverbService.BufferSlot?,
    val oneShotEnabled: Boolean,
    val oneShotFull: Boolean,
    val loopingEnabled: Boolean,
    val oneShotSeconds: Float = 0f,
    val loopingSeconds: Float = 0f,
)

internal enum class RecordingTileUiState {
    RECORDING,
    ACTIVE,
    AVAILABLE,
    FULL,
    DISABLED,
}

internal enum class RecordingTileClickAction {
    START,
    SWITCH,
    STOP,
    NONE,
}

internal fun recordingTileUiState(
    bufferSlot: ReverbService.BufferSlot,
    snapshot: RecordingTileSnapshot,
): RecordingTileUiState {
    val enabled = when (bufferSlot) {
        ReverbService.BufferSlot.ONE_SHOT -> snapshot.oneShotEnabled
        ReverbService.BufferSlot.LOOPING -> snapshot.loopingEnabled
    }
    return when {
        !enabled -> RecordingTileUiState.DISABLED
        bufferSlot == ReverbService.BufferSlot.ONE_SHOT && snapshot.oneShotFull -> RecordingTileUiState.FULL
        snapshot.listening && snapshot.activeBuffer == bufferSlot -> RecordingTileUiState.RECORDING
        snapshot.activeBuffer == bufferSlot -> RecordingTileUiState.ACTIVE
        else -> RecordingTileUiState.AVAILABLE
    }
}

internal fun recordingTileDurationSeconds(
    bufferSlot: ReverbService.BufferSlot,
    snapshot: RecordingTileSnapshot,
): Float = when (bufferSlot) {
    ReverbService.BufferSlot.ONE_SHOT -> snapshot.oneShotSeconds
    ReverbService.BufferSlot.LOOPING -> snapshot.loopingSeconds
}.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f

internal fun recordingTileShowsDuration(
    bufferSlot: ReverbService.BufferSlot,
    snapshot: RecordingTileSnapshot,
): Boolean = !(snapshot.listening && snapshot.activeBuffer == bufferSlot)

internal fun recordingTileRefreshPriority(
    bufferSlot: ReverbService.BufferSlot,
    snapshot: RecordingTileSnapshot,
): Int = if (recordingTileUiState(bufferSlot, snapshot) == RecordingTileUiState.RECORDING) 1 else 0

internal fun isTileCaptureActuallyRecording(
    listeningIntentEnabled: Boolean,
    runtimeCaptureActive: Boolean,
): Boolean = listeningIntentEnabled && runtimeCaptureActive

internal fun recordingTileSnapshot(
    listeningIntentEnabled: Boolean,
    runtimeCaptureActive: Boolean,
    activeBuffer: ReverbService.BufferSlot?,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    oneShotSeconds: Float = 0f,
    loopingSeconds: Float = 0f,
): RecordingTileSnapshot = RecordingTileSnapshot(
    listening = isTileCaptureActuallyRecording(listeningIntentEnabled, runtimeCaptureActive),
    activeBuffer = activeBuffer,
    oneShotEnabled = oneShotEnabled,
    oneShotFull = oneShotFull,
    loopingEnabled = loopingEnabled,
    oneShotSeconds = oneShotSeconds,
    loopingSeconds = loopingSeconds,
)

internal fun recordingTileClickAction(
    bufferSlot: ReverbService.BufferSlot,
    snapshot: RecordingTileSnapshot,
): RecordingTileClickAction = when (recordingTileUiState(bufferSlot, snapshot)) {
    RecordingTileUiState.RECORDING -> RecordingTileClickAction.STOP
    RecordingTileUiState.ACTIVE,
    RecordingTileUiState.AVAILABLE,
    -> if (snapshot.listening) RecordingTileClickAction.SWITCH else RecordingTileClickAction.START
    RecordingTileUiState.FULL,
    RecordingTileUiState.DISABLED,
    -> RecordingTileClickAction.NONE
}

internal fun recordingTileActionSatisfied(
    action: RecordingTileClickAction,
    bufferSlot: ReverbService.BufferSlot,
    snapshot: RecordingTileSnapshot,
): Boolean = when (action) {
    RecordingTileClickAction.START,
    RecordingTileClickAction.SWITCH,
    -> snapshot.listening && snapshot.activeBuffer == bufferSlot
    RecordingTileClickAction.STOP -> !snapshot.listening
    RecordingTileClickAction.NONE -> true
}

internal fun tileActionCallbackIsCurrent(
    actionInFlight: Boolean,
    callbackGeneration: Long,
    currentGeneration: Long,
): Boolean = actionInFlight && callbackGeneration == currentGeneration

internal fun stoppedRecordingTileSnapshot(
    persisted: RecordingTileSnapshot,
    live: RecordingTileSnapshot?,
): RecordingTileSnapshot = persisted.copy(
    listening = false,
    oneShotSeconds = live?.oneShotSeconds ?: persisted.oneShotSeconds,
    loopingSeconds = live?.loopingSeconds ?: persisted.loopingSeconds,
)

internal fun failClosedRecordingTileSnapshot(
    previous: RecordingTileSnapshot? = null,
): RecordingTileSnapshot = RecordingTileSnapshot(
    listening = false,
    activeBuffer = null,
    oneShotEnabled = false,
    oneShotFull = false,
    loopingEnabled = false,
    oneShotSeconds = previous?.oneShotSeconds ?: 0f,
    loopingSeconds = previous?.loopingSeconds ?: 0f,
)

internal fun runtimeRecordingTileFallbackSnapshot(
    cached: RecordingTileSnapshot?,
    activeBuffer: ReverbService.BufferSlot?,
    oneShotEnabled: Boolean,
    loopingEnabled: Boolean,
): RecordingTileSnapshot = (cached ?: failClosedRecordingTileSnapshot()).copy(
    listening = false,
    activeBuffer = activeBuffer,
    oneShotEnabled = oneShotEnabled,
    oneShotFull = false,
    loopingEnabled = loopingEnabled,
)

internal fun tileHydrationCanApply(
    expectedGeneration: Long,
    currentGeneration: Long,
    expectedSnapshot: RecordingTileSnapshot?,
    currentSnapshot: RecordingTileSnapshot?,
): Boolean = expectedGeneration == currentGeneration && currentSnapshot === expectedSnapshot

internal object RecordingQuickTileStateCache {
    private val stateLock = Any()
    private val persistedReadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reverb-tile-persisted-state").apply { isDaemon = true }
    }

    @Volatile
    private var cachedSnapshot: RecordingTileSnapshot? = null
    private var stateGeneration = 0L
    private var hydrationGeneration: Long? = null
    private var runtimeAuthoritative = false

    fun publish(snapshot: RecordingTileSnapshot) {
        synchronized(stateLock) {
            cachedSnapshot = snapshot
            stateGeneration++
            runtimeAuthoritative = true
        }
    }

    fun invalidateRuntimeSnapshot() {
        synchronized(stateLock) {
            cachedSnapshot = null
            stateGeneration++
            runtimeAuthoritative = false
        }
    }

    /** Memory-only fallback. This is safe on the recorder audio thread and TileService main thread. */
    fun readNonBlocking(): RecordingTileSnapshot =
        cachedSnapshot ?: failClosedRecordingTileSnapshot()

    fun readCachedOrNull(): RecordingTileSnapshot? = cachedSnapshot

    fun markServiceStopped(context: Context): RecordingTileSnapshot =
        markRuntimeUnavailable(context, persistLatestDurations = true)

    fun markRuntimeUnavailable(context: Context): RecordingTileSnapshot =
        markRuntimeUnavailable(context, persistLatestDurations = false)

    private fun markRuntimeUnavailable(
        context: Context,
        persistLatestDurations: Boolean,
    ): RecordingTileSnapshot {
        val appContext = context.applicationContext
        val stopped: RecordingTileSnapshot
        val expectedGeneration: Long
        synchronized(stateLock) {
            stopped = failClosedRecordingTileSnapshot(cachedSnapshot)
            cachedSnapshot = stopped
            stateGeneration++
            runtimeAuthoritative = false
            expectedGeneration = stateGeneration
        }
        persistedReadExecutor.execute {
            // Duration cache is convenience state. Keep the latest live counters, but resolve
            // enabled/full/destination from durable settings off UI/audio threads.
            if (persistLatestDurations) persistDurations(appContext, stopped)
            val persisted = runCatching { readPersisted(appContext) }.getOrNull()
                ?: return@execute
            val hydrated = stoppedRecordingTileSnapshot(persisted, stopped)
            val accepted = synchronized(stateLock) {
                if (runtimeAuthoritative || !tileHydrationCanApply(
                        expectedGeneration = expectedGeneration,
                        currentGeneration = stateGeneration,
                        expectedSnapshot = stopped,
                        currentSnapshot = cachedSnapshot,
                    )
                ) {
                    false
                } else {
                    cachedSnapshot = hydrated
                    stateGeneration++
                    runtimeAuthoritative = false
                    true
                }
            }
            if (accepted) {
                RecordingQuickTiles.refreshCachedSnapshot(
                    context = appContext,
                    snapshot = hydrated,
                    requestSystemRefresh = true,
                )
            }
        }
        return stopped
    }

    fun hydratePersistedAsync(context: Context) {
        val appContext = context.applicationContext
        val expectedGeneration: Long
        val expectedSnapshot: RecordingTileSnapshot?
        synchronized(stateLock) {
            // Runtime Service state is authoritative. Persisted/fail-closed snapshots are only
            // fallbacks, so a later TileService listening session may retry their hydration.
            if (runtimeAuthoritative) return
            expectedGeneration = stateGeneration
            expectedSnapshot = cachedSnapshot
            if (hydrationGeneration == expectedGeneration) return
            hydrationGeneration = expectedGeneration
        }
        persistedReadExecutor.execute {
            val persisted = runCatching { readPersisted(appContext) }.getOrNull()
            if (persisted == null) {
                synchronized(stateLock) {
                    if (hydrationGeneration == expectedGeneration) hydrationGeneration = null
                }
                return@execute
            }
            val accepted = synchronized(stateLock) {
                if (hydrationGeneration == expectedGeneration) hydrationGeneration = null
                if (runtimeAuthoritative || !tileHydrationCanApply(
                        expectedGeneration = expectedGeneration,
                        currentGeneration = stateGeneration,
                        expectedSnapshot = expectedSnapshot,
                        currentSnapshot = cachedSnapshot,
                    )
                ) {
                    false
                } else {
                    cachedSnapshot = persisted
                    stateGeneration++
                    runtimeAuthoritative = false
                    true
                }
            }
            if (accepted) {
                RecordingQuickTiles.refreshCachedSnapshot(
                    context = appContext,
                    snapshot = persisted,
                    requestSystemRefresh = true,
                )
            }
        }
    }

    private fun readPersisted(context: Context): RecordingTileSnapshot {
        val prefs = getRecorderPreferences(context)
        val buffers = getConfiguredBufferAvailability(context)
        return RecordingTileSnapshot(
            listening = false,
            activeBuffer = readCaptureBufferSlotPreference(prefs),
            oneShotEnabled = buffers.oneShotEnabled,
            oneShotFull = prefs.safeBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, false),
            loopingEnabled = buffers.loopingEnabled,
            oneShotSeconds = cachedTileDurationSeconds(
                prefs.safeLong(PrefKey.QUICK_TILE_ONE_SHOT_DURATION_MILLIS, 0L),
            ),
            loopingSeconds = cachedTileDurationSeconds(
                prefs.safeLong(PrefKey.QUICK_TILE_LOOPING_DURATION_MILLIS, 0L),
            ),
        )
    }

    fun persistCurrentDurations(context: Context) {
        val snapshot = cachedSnapshot ?: return
        val appContext = context.applicationContext
        persistedReadExecutor.execute { persistDurations(appContext, snapshot) }
    }

    private fun persistDurations(context: Context, snapshot: RecordingTileSnapshot) {
        getRecorderPreferences(context).edit {
            putLong(
                PrefKey.QUICK_TILE_ONE_SHOT_DURATION_MILLIS,
                quickTileDurationMillis(snapshot.oneShotSeconds),
            )
            putLong(
                PrefKey.QUICK_TILE_LOOPING_DURATION_MILLIS,
                quickTileDurationMillis(snapshot.loopingSeconds),
            )
        }
    }
}

internal fun quickTileDurationMillis(seconds: Float): Long {
    if (!seconds.isFinite() || seconds <= 0f) return 0L
    return (seconds.toDouble() * 1_000.0)
        .coerceAtMost(Long.MAX_VALUE.toDouble())
        .toLong()
}

internal fun cachedTileDurationSeconds(durationMillis: Long): Float =
    durationMillis.coerceAtLeast(0L).toDouble().div(1_000.0).toFloat()

internal fun readRecordingTileSnapshotNonBlocking(): RecordingTileSnapshot =
    RecordingQuickTileStateCache.readNonBlocking()

internal object RecordingQuickTiles {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeningServices = java.util.WeakHashMap<RecordingTileService, Unit>()
    private data class PendingHandoff(
        val source: ReverbService.BufferSlot,
        val target: ReverbService.BufferSlot,
        val generation: Long,
        var sourceRefreshScheduled: Boolean = false,
        var sourceRefreshAttempts: Int = 0,
    )
    private var pendingHandoff: PendingHandoff? = null
    private var nextHandoffGeneration = 0L
    private const val HANDOFF_SOURCE_REFRESH_RETRY_MILLIS = 100L
    private const val MAX_HANDOFF_SOURCE_REFRESH_ATTEMPTS = 50

    fun register(service: RecordingTileService) {
        synchronized(listeningServices) { listeningServices[service] = Unit }
    }

    fun unregister(service: RecordingTileService) {
        synchronized(listeningServices) { listeningServices.remove(service) }
    }

    fun hasListeningServices(): Boolean =
        synchronized(listeningServices) { listeningServices.isNotEmpty() }

    fun beginHandoff(
        source: ReverbService.BufferSlot,
        target: ReverbService.BufferSlot,
    ) {
        if (source == target) return
        synchronized(listeningServices) {
            nextHandoffGeneration++
            pendingHandoff = PendingHandoff(source, target, nextHandoffGeneration)
        }
        // Do not ask SystemUI to refresh until the recorder publishes the target snapshot.
        // Requesting before that can paint the old state again and be coalesced with the
        // important post-switch refresh.
    }

    fun suppressRecordingState(
        bufferSlot: ReverbService.BufferSlot,
        snapshot: RecordingTileSnapshot,
    ): Boolean = synchronized(listeningServices) {
        val handoff = pendingHandoff
        handoff != null && snapshot.listening && snapshot.activeBuffer == handoff.target &&
            bufferSlot == handoff.target
    }

    fun onTileRendered(
        service: RecordingTileService,
        snapshot: RecordingTileSnapshot,
    ) {
        val targetToRefresh = synchronized(listeningServices) {
            val handoff = pendingHandoff ?: return@synchronized null
            if (
                service.tileBufferSlot() == handoff.source &&
                snapshot.listening &&
                snapshot.activeBuffer == handoff.target
            ) {
                pendingHandoff = null
                handoff.target
            } else {
                null
            }
        } ?: return
        requestRefresh(context = service, only = targetToRefresh)
    }

    private fun ensureHandoffSourceRefresh(
        context: Context,
        snapshot: RecordingTileSnapshot,
    ) {
        val handoff = synchronized(listeningServices) {
            val current = pendingHandoff ?: return
            if (!snapshot.listening || snapshot.activeBuffer != current.target) return
            if (current.sourceRefreshScheduled) return
            current.sourceRefreshScheduled = true
            current
        }
        retryHandoffSourceRefresh(context.applicationContext, handoff)
    }

    private fun retryHandoffSourceRefresh(
        context: Context,
        handoff: PendingHandoff,
    ) {
        val fallbackTarget = synchronized(listeningServices) {
            val current = pendingHandoff?.takeIf { it.generation == handoff.generation }
                ?: return
            current.sourceRefreshAttempts++
            if (current.sourceRefreshAttempts >= MAX_HANDOFF_SOURCE_REFRESH_ATTEMPTS) {
                pendingHandoff = null
                current.target
            } else {
                null
            }
        }
        if (fallbackTarget != null) {
            // If SystemUI never instantiates the source service (for example because the
            // tile was removed), stop retrying. A final full refresh keeps any remaining
            // configured tile converging on the live snapshot without background churn.
            requestRefresh(context)
            return
        }
        requestRefresh(context, only = handoff.source)
        mainHandler.postDelayed(
            { retryHandoffSourceRefresh(context, handoff) },
            HANDOFF_SOURCE_REFRESH_RETRY_MILLIS,
        )
    }

    fun publishSnapshot(
        context: Context,
        snapshot: RecordingTileSnapshot,
        requestSystemRefresh: Boolean,
    ) {
        RecordingQuickTileStateCache.publish(snapshot)
        dispatchSnapshot(context, snapshot, requestSystemRefresh)
    }

    internal fun refreshCachedSnapshot(
        context: Context,
        snapshot: RecordingTileSnapshot,
        requestSystemRefresh: Boolean,
    ) {
        dispatchSnapshot(context, snapshot, requestSystemRefresh)
    }

    private fun dispatchSnapshot(
        context: Context,
        snapshot: RecordingTileSnapshot,
        requestSystemRefresh: Boolean,
    ) {
        synchronized(listeningServices) {
            val handoff = pendingHandoff
            if (handoff != null && (!snapshot.listening ||
                    (snapshot.activeBuffer != handoff.source && snapshot.activeBuffer != handoff.target))) {
                pendingHandoff = null
            }
        }
        val refreshConnected = Runnable {
            val services = synchronized(listeningServices) { listeningServices.keys.toList() }
            // Deactivate the old recording tile before activating the new one. This prevents
            // a frame where SystemUI can render both buffers ACTIVE during a handoff.
            services
                .sortedBy { service -> service.refreshPriority(snapshot) }
                .forEach { service -> service.refreshFromSnapshot(snapshot) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) refreshConnected.run()
        else mainHandler.post(refreshConnected)
        if (requestSystemRefresh) {
            val handoffPending = synchronized(listeningServices) {
                pendingHandoff?.let { handoff ->
                    snapshot.listening && snapshot.activeBuffer == handoff.target
                } == true
            }
            if (handoffPending) ensureHandoffSourceRefresh(context, snapshot)
            else requestRefresh(context)
        }
    }

    fun requestRefresh(
        context: Context,
        only: ReverbService.BufferSlot? = null,
    ) {
        val appContext = context.applicationContext
        for ((slot, tileService) in TILE_SERVICES) {
            if (only != null && slot != only) continue
            runCatching {
                TileService.requestListeningState(appContext, ComponentName(appContext, tileService))
            }
        }
    }

    private val TILE_SERVICES = arrayOf(
        ReverbService.BufferSlot.ONE_SHOT to OneShotRecordingTileService::class.java,
        ReverbService.BufferSlot.LOOPING to LoopingRecordingTileService::class.java,
    )
}

abstract class RecordingTileService : TileService() {
    protected abstract val bufferSlot: ReverbService.BufferSlot
    protected abstract val labelRes: Int
    protected abstract val iconRes: Int

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tileListening = false
    private var actionConnection: TileActionConnection? = null
    private var actionTimeout: Runnable? = null
    private var actionInFlight = false
    private var actionGeneration = 0L

    private fun refreshFromCacheAndHydrate() {
        updateTile(readRecordingTileSnapshotNonBlocking())
        RecordingQuickTileStateCache.hydratePersistedAsync(this)
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshFromCacheAndHydrate()
    }

    override fun onStartListening() {
        super.onStartListening()
        tileListening = true
        RecordingQuickTiles.register(this)
        refreshFromCacheAndHydrate()
    }

    override fun onStopListening() {
        tileListening = false
        RecordingQuickTiles.unregister(this)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            refreshFromCacheAndHydrate()
            return
        }
        val action = { beginTileAction() }
        if (isLocked) unlockAndRun(action) else action()
    }

    override fun onDestroy() {
        tileListening = false
        RecordingQuickTiles.unregister(this)
        // Recorder snapshot callbacks are posted independently of this TileService lifecycle.
        // Invalidate their generation before unbinding so a callback that was already queued
        // cannot execute a stale START/SWITCH/STOP after SystemUI destroys the tile service.
        actionInFlight = false
        actionGeneration++
        actionTimeout = null
        mainHandler.removeCallbacksAndMessages(null)
        actionConnection?.let(::unbindActionConnection)
        super.onDestroy()
    }

    private fun beginTileAction() {
        if (actionInFlight) return
        actionInFlight = true
        val timeout = Runnable { finishTileAction() }
        actionTimeout = timeout
        mainHandler.postDelayed(timeout, ACTION_TIMEOUT_MILLIS)

        val connection = TileActionConnection()
        actionConnection = connection
        val bound = runCatching {
            bindService(Intent(this, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) finishTileAction()
    }

    private fun executeTileAction(recorder: ReverbService) {
        val generation = ++actionGeneration
        recorder.getRecordingTileSnapshot { liveSnapshot ->
            if (!tileActionCallbackIsCurrent(actionInFlight, generation, actionGeneration)) {
                return@getRecordingTileSnapshot
            }
            RecordingQuickTiles.publishSnapshot(this, liveSnapshot, requestSystemRefresh = false)
            val action = recordingTileClickAction(bufferSlot, liveSnapshot)
            val result = when (action) {
                RecordingTileClickAction.START -> recorder.enableListening(bufferSlot)
                RecordingTileClickAction.SWITCH -> recorder.selectCaptureBuffer(bufferSlot)
                RecordingTileClickAction.STOP -> recorder.disableListening()
                RecordingTileClickAction.NONE -> null
            }
            if (action == RecordingTileClickAction.NONE || result?.accepted != true) {
                finishTileAction(liveSnapshot)
                return@getRecordingTileSnapshot
            }
            awaitTileAction(recorder, action, generation)
        }
    }

    private fun awaitTileAction(
        recorder: ReverbService,
        action: RecordingTileClickAction,
        generation: Long,
    ) {
        if (!tileActionCallbackIsCurrent(actionInFlight, generation, actionGeneration)) return
        recorder.getRecordingTileSnapshot { snapshot ->
            if (!tileActionCallbackIsCurrent(actionInFlight, generation, actionGeneration)) {
                return@getRecordingTileSnapshot
            }
            RecordingQuickTiles.publishSnapshot(this, snapshot, requestSystemRefresh = false)
            if (recordingTileActionSatisfied(action, bufferSlot, snapshot)) {
                finishTileAction(snapshot)
            } else {
                mainHandler.postDelayed(
                    { awaitTileAction(recorder, action, generation) },
                    ACTION_POLL_MILLIS,
                )
            }
        }
    }

    private fun finishTileAction(snapshot: RecordingTileSnapshot? = null) {
        if (!actionInFlight) return
        actionInFlight = false
        actionGeneration++
        actionTimeout?.let(mainHandler::removeCallbacks)
        actionTimeout = null
        actionConnection?.let(::unbindActionConnection)
        if (snapshot != null) {
            RecordingQuickTiles.publishSnapshot(this, snapshot, requestSystemRefresh = true)
        } else {
            // Runtime state is unavailable. Never block this main-thread failure path on
            // retention recovery; paint the current memory-only fallback and hydrate later.
            val fallback = RecordingQuickTileStateCache.markRuntimeUnavailable(this)
            RecordingQuickTiles.refreshCachedSnapshot(this, fallback, requestSystemRefresh = true)
        }
    }

    private fun unbindActionConnection(connection: TileActionConnection) {
        if (actionConnection !== connection) return
        actionConnection = null
        runCatching { unbindService(connection) }
    }

    private inner class TileActionConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val recorder = (binder as? ReverbService.BackgroundRecorderBinder)?.service
            if (recorder == null || actionConnection !== this) {
                finishTileAction()
                return
            }
            executeTileAction(recorder)
        }

        override fun onServiceDisconnected(name: ComponentName) = finishTileAction()
        override fun onBindingDied(name: ComponentName) = finishTileAction()
        override fun onNullBinding(name: ComponentName) = finishTileAction()
    }

    internal fun tileBufferSlot(): ReverbService.BufferSlot = bufferSlot

    internal fun refreshPriority(snapshot: RecordingTileSnapshot): Int =
        recordingTileRefreshPriority(bufferSlot, snapshot)

    internal fun refreshFromSnapshot(snapshot: RecordingTileSnapshot) {
        if (tileListening) updateTile(snapshot)
    }

    private fun updateTile(snapshot: RecordingTileSnapshot) {
        val tile = qsTile ?: return
        val rawUiState = recordingTileUiState(bufferSlot, snapshot)
        val uiState = if (
            rawUiState == RecordingTileUiState.RECORDING &&
            RecordingQuickTiles.suppressRecordingState(bufferSlot, snapshot)
        ) {
            RecordingTileUiState.ACTIVE
        } else {
            rawUiState
        }
        val permissionGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val status = if (!permissionGranted) {
            getString(R.string.quick_tile_permission)
        } else {
            when (uiState) {
                RecordingTileUiState.RECORDING -> getString(R.string.quick_tile_recording)
                RecordingTileUiState.ACTIVE,
                RecordingTileUiState.AVAILABLE,
                -> getString(R.string.quick_tile_stopped)
                RecordingTileUiState.FULL -> getString(R.string.quick_tile_full)
                RecordingTileUiState.DISABLED -> getString(R.string.quick_tile_off)
            }
        }
        val duration = formatShortTimer(recordingTileDurationSeconds(bufferSlot, snapshot))
        val showDuration = permissionGranted && recordingTileShowsDuration(bufferSlot, snapshot)
        tile.label = getString(labelRes)
        tile.icon = Icon.createWithResource(this, iconRes)
        tile.state = if (!permissionGranted) {
            Tile.STATE_UNAVAILABLE
        } else {
            when (uiState) {
                RecordingTileUiState.RECORDING -> Tile.STATE_ACTIVE
                RecordingTileUiState.ACTIVE,
                RecordingTileUiState.AVAILABLE,
                -> Tile.STATE_INACTIVE
                RecordingTileUiState.FULL,
                RecordingTileUiState.DISABLED,
                -> Tile.STATE_UNAVAILABLE
            }
        }
        tile.contentDescription = when {
            !permissionGranted -> "${tile.label}, $status"
            showDuration -> "${tile.label}, $status, $duration"
            else -> "${tile.label}, $status"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !permissionGranted -> status
                showDuration -> duration
                else -> status
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = when {
                !permissionGranted -> status
                showDuration -> "$status, $duration"
                else -> status
            }
        }
        tile.updateTile()
        RecordingQuickTiles.onTileRendered(this, snapshot)
    }

    companion object {
        private const val ACTION_POLL_MILLIS = 80L
        private const val ACTION_TIMEOUT_MILLIS = 4_000L
    }
}

class OneShotRecordingTileService : RecordingTileService() {
    override val bufferSlot = ReverbService.BufferSlot.ONE_SHOT
    override val labelRes = R.string.quick_tile_one_shot
    override val iconRes = R.drawable.ic_qs_one_shot
}

class LoopingRecordingTileService : RecordingTileService() {
    override val bufferSlot = ReverbService.BufferSlot.LOOPING
    override val labelRes = R.string.quick_tile_looping
    override val iconRes = R.drawable.ic_qs_looping
}
