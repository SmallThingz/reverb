package app.smallthingz.reverb

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val sizeFormatter = object : ThreadLocal<DecimalFormat>() {
    override fun initialValue(): DecimalFormat =
        DecimalFormat(FORMAT_SIZE_MIB, DecimalFormatSymbols(Locale.US))
}

fun formatShortTimer(seconds: Float): String =
    formatDurationInput(seconds.toInt().coerceAtLeast(0))

internal fun formatRangeTimeInput(seconds: Double): String = smallthingz.ui.common.formatRangeTimeInput(seconds)

fun formatShortFileSize(size: Long): String {
    val mebibytes = size.coerceAtLeast(0L) / (1024.0 * 1024.0)
    val formatter = sizeFormatter.get() ?: error("sizeFormatter not initialized")
    return "${formatter.format(mebibytes)}${MIB_SUFFIX}"
}

fun formatSavedRecordingDuration(context: Context, durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return when {
        hours > 0L -> context.getString(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0L -> context.getString(R.string.duration_minutes_seconds, minutes, seconds)
        else -> context.getString(R.string.duration_seconds, seconds)
    }
}

fun formatPlaybackTime(durationMillis: Int): String =
    smallthingz.ui.common.formatPlaybackTime(durationMillis)
