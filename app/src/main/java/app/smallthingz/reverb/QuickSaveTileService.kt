package app.smallthingz.reverb

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Each manifest component is a stable slot; its saved buffer and duration are independent. */
abstract class QuickSaveTileService : TileService() {
    protected abstract val optionSlot: Int
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: Job? = null
    private var destroyed = false
    private var displayed: QuickSaveTileOption? = null
    private lateinit var observationSession: QuickSaveTileObservationSession

    override fun onCreate() {
        super.onCreate()
        observationSession = QuickSaveTileOptions.beginObservation(applicationContext, optionSlot)
    }

    override fun onTileAdded() {
        super.onTileAdded()
        QuickSaveTileOptions.recordObservation(applicationContext, observationSession, QuickSaveTileAddedState.ADDED)
    }

    override fun onTileRemoved() {
        QuickSaveTileOptions.recordObservation(applicationContext, observationSession, QuickSaveTileAddedState.NOT_ADDED)
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
        QuickSaveTileOptions.recordObservation(applicationContext, observationSession, QuickSaveTileAddedState.ADDED)
        listener?.cancel()
        listener = scope.launch {
            combine(QuickSaveTileOptions.options, QuickSaveTileOptions.error) { options, error ->
                options?.find { it.slot == optionSlot }.takeIf { error == null }
            }.collect { option ->
                displayed = option
                qsTile?.apply {
                    label = option?.let { getString(if (it.buffer == ReverbService.BufferSlot.LOOPING)
                        R.string.quick_save_looping else R.string.quick_save_one_shot) }
                        ?: getString(R.string.tile_slot_label, optionSlot + 1)
                    icon = option?.let { Icon.createWithBitmap(quickSaveTileBitmap(it.buffer, it.amount, it.unit)) }
                        ?: Icon.createWithResource(this@QuickSaveTileService, R.drawable.ic_qs_quick_save)
                    state = if (option == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
                    val duration = option?.let { "${it.amount} ${quickSaveUnitLabel(this@QuickSaveTileService, it.unit, it.amount)}" }
                    contentDescription = listOfNotNull(label, duration).joinToString(", ")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = duration
                    updateTile()
                }
            }
        }
        QuickSaveTileOptions.refresh(this)
    }

    override fun onStopListening() {
        listener?.cancel(); listener = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val tapped = displayed ?: return
        val save = {
            // Unlock delays cannot retarget a removed, edited or recycled option.
            if (!destroyed && QuickSaveTileOptions.options.value?.find { it.slot == optionSlot } == tapped &&
                QuickSaveTileOptions.error.value == null) {
                try {
                    requireServiceStarted {
                        ContextCompat.startForegroundService(this,
                            Intent(this, ReverbService::class.java).setAction(ReverbService.ACTION_QUICK_SAVE)
                                .putExtra(ReverbService.EXTRA_QUICK_TILE_BUFFER_SLOT, tapped.buffer.storageCode.toInt())
                                .putExtra(ReverbService.EXTRA_QUICK_SAVE_SECONDS, tapped.seconds))
                    }
                } catch (error: RuntimeException) {
                    NotifyFileReceiver(applicationContext).fileFailed(getString(R.string.save_failed), error)
                }
            }
        }
        if (isLocked) unlockAndRun(save) else save()
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        super.onDestroy()
    }
}

// Keep the original two component identities; neither now fixes the user's buffer choice.
class LoopingQuickSaveTileService : QuickSaveTileService() { override val optionSlot = 0 }
class OneShotQuickSaveTileService : QuickSaveTileService() { override val optionSlot = 1 }
class QuickSaveTile3 : QuickSaveTileService() { override val optionSlot = 2 }
class QuickSaveTile4 : QuickSaveTileService() { override val optionSlot = 3 }
class QuickSaveTile5 : QuickSaveTileService() { override val optionSlot = 4 }
class QuickSaveTile6 : QuickSaveTileService() { override val optionSlot = 5 }
class QuickSaveTile7 : QuickSaveTileService() { override val optionSlot = 6 }
class QuickSaveTile8 : QuickSaveTileService() { override val optionSlot = 7 }
