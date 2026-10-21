package app.smallthingz.reverb

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.floor

internal fun readTileCapacities(context: Context): TileCapacities = withRetentionPersistenceLock {
    val rate = getConfiguredSampleRate(context)
    val channels = getConfiguredChannelMode(context).channelCount
    val sampleFormat = getConfiguredPcmSampleFormat(context)
    val formatLimit = exportDurationLimitExactSeconds(
        getConfiguredOutputFormat(context), ExportCodec.PCM_16, rate, channels, sampleFormat,
    )
    val retention = retentionConfigurationForRead(context)
    fun retained(buffer: ReverbService.BufferSlot) = when (retention.mode) {
        RetentionMode.TIME -> (if (buffer == ReverbService.BufferSlot.LOOPING)
            retention.loopingSeconds else retention.oneShotSeconds).takeIf { it > 0 }?.toDouble()
        RetentionMode.SIZE -> {
            val bytes = if (buffer == ReverbService.BufferSlot.LOOPING)
                retention.loopingSizeBytes else retention.oneShotSizeBytes
            val bytesPerSecond = rate.toDouble() * channels * sampleFormat.bytesPerSample
            if (bytes > 0 && bytesPerSecond > 0) bytes / bytesPerSecond else null
        }
    }
    TileCapacities(floor(formatLimit.coerceIn(0.0, 86_400.0)).toInt(),
        retained(ReverbService.BufferSlot.LOOPING), retained(ReverbService.BufferSlot.ONE_SHOT))
}

private fun Context.quickTileActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.quickTileActivity()
    else -> null
}

internal fun requestQuickTile(context: Context, option: QuickSaveTileOption, result: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        result(context.getString(R.string.tiles_ui_manual, option.slot + 1))
        return
    }
    val activity = context.quickTileActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        result(context.getString(R.string.tiles_ui_add_failed))
        return
    }
    try {
        val request = QuickSaveTileOptions.beginAddRequest(option)
        val label = context.getString(if (option.buffer == ReverbService.BufferSlot.LOOPING)
            R.string.buffer_loop else R.string.buffer_one_shot)
        activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, quickSaveTileClass(option.slot)), label,
            AndroidIcon.createWithBitmap(quickSaveTileBitmap(option.buffer, option.amount, option.unit)),
            ContextCompat.getMainExecutor(context),
        ) { code ->
            val added = code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            QuickSaveTileOptions.recordAddRequestResult(context.applicationContext, request, added)
            result(context.getString(when {
                added -> R.string.tiles_ui_added
                code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.tiles_ui_not_added
                else -> R.string.tiles_ui_add_failed
            }))
        }
    } catch (_: RuntimeException) {
        result(context.getString(R.string.tiles_ui_add_failed))
    }
}

