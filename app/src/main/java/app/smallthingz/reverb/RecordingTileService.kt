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
import java.util.UUID

internal data class RecordingTileSnapshot(
    val listening: Boolean,
    val activeBuffer: ReverbService.BufferSlot?,
    val oneShotEnabled: Boolean,
    val oneShotFull: Boolean,
    val loopingEnabled: Boolean,
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

internal val recordingRuntimeSessionId: String = UUID.randomUUID().toString()

internal fun isCurrentRecordingRuntimeSession(
    storedSessionId: String?,
    currentSessionId: String,
): Boolean = storedSessionId != null && storedSessionId == currentSessionId

internal fun isTileCaptureActuallyRecording(
    listeningIntentEnabled: Boolean,
    runtimeCaptureActive: Boolean,
): Boolean = listeningIntentEnabled && runtimeCaptureActive

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

internal fun revalidateRecordingTileClickAction(
    requestedAction: RecordingTileClickAction,
    bufferSlot: ReverbService.BufferSlot,
    liveSnapshot: RecordingTileSnapshot,
): RecordingTileClickAction {
    val liveState = recordingTileUiState(bufferSlot, liveSnapshot)
    return when (requestedAction) {
        RecordingTileClickAction.NONE -> RecordingTileClickAction.NONE
        RecordingTileClickAction.STOP -> {
            if (liveState == RecordingTileUiState.RECORDING) RecordingTileClickAction.STOP
            else RecordingTileClickAction.NONE
        }
        RecordingTileClickAction.START,
        RecordingTileClickAction.SWITCH,
        -> when (liveState) {
            RecordingTileUiState.FULL,
            RecordingTileUiState.DISABLED,
            -> RecordingTileClickAction.NONE
            RecordingTileUiState.RECORDING -> RecordingTileClickAction.NONE
            RecordingTileUiState.ACTIVE,
            RecordingTileUiState.AVAILABLE,
            -> if (liveSnapshot.listening) RecordingTileClickAction.SWITCH
            else RecordingTileClickAction.START
        }
    }
}

internal fun readRecordingTileSnapshot(context: Context): RecordingTileSnapshot {
    val prefs = getRecorderPreferences(context)
    val activeBuffer = prefs.getString(PrefKey.CAPTURE_BUFFER_SLOT, null)?.let { stored ->
        runCatching { ReverbService.BufferSlot.valueOf(stored) }.getOrNull()
    }
    return RecordingTileSnapshot(
        listening = isTileCaptureActuallyRecording(
            listeningIntentEnabled = prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false),
            runtimeCaptureActive = isCurrentRecordingRuntimeSession(
                storedSessionId = prefs.getString(PrefKey.QUICK_TILE_RECORDING_SESSION, null),
                currentSessionId = recordingRuntimeSessionId,
            ),
        ),
        activeBuffer = activeBuffer,
        oneShotEnabled = isConfiguredOneShotBufferEnabled(context),
        oneShotFull = prefs.getBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, false),
        loopingEnabled = isConfiguredLoopingBufferEnabled(context),
    )
}

internal object RecordingQuickTiles {
    fun requestRefresh(context: Context) {
        val appContext = context.applicationContext
        for (tileService in TILE_SERVICES) {
            runCatching {
                TileService.requestListeningState(appContext, ComponentName(appContext, tileService))
            }
        }
    }

    private val TILE_SERVICES = arrayOf(
        OneShotRecordingTileService::class.java,
        LoopingRecordingTileService::class.java,
    )
}

abstract class RecordingTileService : TileService() {
    protected abstract val bufferSlot: ReverbService.BufferSlot
    protected abstract val labelRes: Int
    protected abstract val iconRes: Int

    private val mainHandler = Handler(Looper.getMainLooper())
    private var actionConnection: TileActionConnection? = null
    private var actionTimeout: Runnable? = null

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(readRecordingTileSnapshot(this))
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(readRecordingTileSnapshot(this))
    }

    override fun onClick() {
        super.onClick()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateTile(readRecordingTileSnapshot(this))
            return
        }
        val snapshot = readRecordingTileSnapshot(this)
        val requestedAction = recordingTileClickAction(bufferSlot, snapshot)
        if (requestedAction == RecordingTileClickAction.NONE) {
            updateTile(snapshot)
            return
        }
        val action = { beginTileAction(requestedAction) }
        if (isLocked) unlockAndRun(action) else action()
    }

    override fun onDestroy() {
        actionConnection?.let(::finishTileAction)
        super.onDestroy()
    }

    private fun beginTileAction(requestedAction: RecordingTileClickAction) {
        if (actionConnection != null) return
        val connection = TileActionConnection(requestedAction)
        actionConnection = connection
        val bound = runCatching {
            bindService(Intent(this, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            finishTileAction(connection)
            return
        }
        val timeout = Runnable {
            if (actionConnection === connection) finishTileAction(connection)
        }
        actionTimeout = timeout
        mainHandler.postDelayed(timeout, ACTION_TIMEOUT_MILLIS)
    }

    private fun finishTileAction(connection: TileActionConnection) {
        if (actionConnection !== connection) return
        actionTimeout?.let(mainHandler::removeCallbacks)
        actionTimeout = null
        actionConnection = null
        runCatching { unbindService(connection) }
        updateTile(readRecordingTileSnapshot(this))
        RecordingQuickTiles.requestRefresh(this)
    }

    private inner class TileActionConnection(
        private val requestedAction: RecordingTileClickAction,
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val recorder = (binder as? ReverbService.BackgroundRecorderBinder)?.service
            if (recorder == null || actionConnection !== this) {
                finishTileAction(this)
                return
            }
            recorder.getState(
                object : ReverbService.StateCallback {
                    override fun state(
                        commandGeneration: Long,
                        listeningEnabled: Boolean,
                        activeBufferSlot: ReverbService.BufferSlot?,
                        oneShotSeconds: Float,
                        oneShotBytes: Long,
                        loopingSeconds: Float,
                        loopingBytes: Long,
                        oneShotIsEnabled: Boolean,
                        oneShotIsFull: Boolean,
                        loopingIsEnabled: Boolean,
                    ) {
                        if (actionConnection !== this@TileActionConnection) return
                        val snapshot = RecordingTileSnapshot(
                            listening = listeningEnabled,
                            activeBuffer = activeBufferSlot,
                            oneShotEnabled = oneShotIsEnabled,
                            oneShotFull = oneShotIsFull,
                            loopingEnabled = loopingIsEnabled,
                        )
                        val action = revalidateRecordingTileClickAction(
                            requestedAction = requestedAction,
                            bufferSlot = bufferSlot,
                            liveSnapshot = snapshot,
                        )
                        when (action) {
                            RecordingTileClickAction.START -> recorder.enableListening(bufferSlot)
                            RecordingTileClickAction.SWITCH -> recorder.selectCaptureBuffer(bufferSlot)
                            RecordingTileClickAction.STOP -> recorder.disableListening()
                            RecordingTileClickAction.NONE -> Unit
                        }
                        finishTileAction(this@TileActionConnection)
                    }
                },
            )
        }

        override fun onServiceDisconnected(name: ComponentName) = finishTileAction(this)
        override fun onBindingDied(name: ComponentName) = finishTileAction(this)
        override fun onNullBinding(name: ComponentName) = finishTileAction(this)
    }

    private fun updateTile(snapshot: RecordingTileSnapshot) {
        val tile = qsTile ?: return
        val uiState = recordingTileUiState(bufferSlot, snapshot)
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
        tile.contentDescription = "${tile.label}, $status"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = status
        tile.updateTile()
    }

    companion object {
        private const val ACTION_TIMEOUT_MILLIS = 3_000L
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
