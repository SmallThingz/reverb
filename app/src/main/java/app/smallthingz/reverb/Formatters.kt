package app.smallthingz.reverb

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.floor

private val sizeFormatter = object : ThreadLocal<DecimalFormat>() {
    override fun initialValue(): DecimalFormat =
        DecimalFormat(FORMAT_SIZE_MIB, DecimalFormatSymbols(Locale.US))
}

fun formatShortTimer(seconds: Float): String =
    formatDurationInput(seconds.toInt().coerceAtLeast(0))

internal fun formatRangeTimeInput(seconds: Double): String {
    val safeSeconds = if (seconds.isFinite()) seconds.coerceAtLeast(0.0) else 0.0
    // Display exactly tenths, and floor rather than round so the rendered end time can
    // never jump beyond the actual retained audio boundary.
    val totalTenths = floor(safeSeconds * 10.0).toLong()
    val totalSeconds = totalTenths / 10L
    val tenth = (totalTenths % 10L).toInt()
    val hours = totalSeconds / 3_600L
    val minutes = ((totalSeconds % 3_600L) / 60L).toInt()
    val secs = (totalSeconds % 60L).toInt()
    val base = if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${secs.toString().padStart(2, '0')}"
    }
    return "$base.$tenth"
}

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
    formatShortTimer(durationMillis.coerceAtLeast(0) / 1000f)
