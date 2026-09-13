package app.smallthingz.reverb

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

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

internal fun isTileCaptureActuallyRecording(
    listeningIntentEnabled: Boolean,
    runtimeCaptureActive: Boolean,
): Boolean = listeningIntentEnabled && runtimeCaptureActive

internal fun readRecordingTileSnapshot(context: Context): RecordingTileSnapshot {
    val prefs = getRecorderPreferences(context)
    val activeBuffer = prefs.getString(PrefKey.CAPTURE_BUFFER_SLOT, null)?.let { stored ->
        runCatching { ReverbService.BufferSlot.valueOf(stored) }.getOrNull()
    }
    return RecordingTileSnapshot(
        listening = isTileCaptureActuallyRecording(
            listeningIntentEnabled = prefs.getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false),
            runtimeCaptureActive = prefs.getBoolean(PrefKey.QUICK_TILE_RECORDING_ACTIVE, false),
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
                TileService.requestListeningState(
                    appContext,
                    ComponentName(appContext, tileService),
                )
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

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(readRecordingTileSnapshot(this))
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(readRecordingTileSnapshot(this))
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val snapshot = readRecordingTileSnapshot(this)
        when (recordingTileUiState(bufferSlot, snapshot)) {
            RecordingTileUiState.FULL,
            RecordingTileUiState.DISABLED,
            -> return
            RecordingTileUiState.RECORDING -> return
            RecordingTileUiState.ACTIVE,
            RecordingTileUiState.AVAILABLE,
            -> Unit
        }
        val launch = {
            val intent = Intent(this, QuickTileActionActivity::class.java)
                .setAction(QuickTileActionActivity.ACTION_START)
                .putExtra(QuickTileActionActivity.EXTRA_BUFFER_SLOT, bufferSlot.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val requestCode = if (bufferSlot == ReverbService.BufferSlot.ONE_SHOT) 701 else 702
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(launch) else launch()
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
                RecordingTileUiState.ACTIVE -> when (bufferSlot) {
                    ReverbService.BufferSlot.ONE_SHOT -> getString(R.string.quick_tile_one_shot_active)
                    ReverbService.BufferSlot.LOOPING -> getString(R.string.quick_tile_looping_active)
                }
                RecordingTileUiState.AVAILABLE -> getString(R.string.quick_tile_stopped)
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
                RecordingTileUiState.RECORDING,
                RecordingTileUiState.ACTIVE,
                -> Tile.STATE_ACTIVE
                RecordingTileUiState.AVAILABLE -> Tile.STATE_INACTIVE
                RecordingTileUiState.FULL,
                RecordingTileUiState.DISABLED,
                -> Tile.STATE_UNAVAILABLE
            }
        }
        tile.contentDescription = "${tile.label}, $status"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = status
        tile.updateTile()
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
