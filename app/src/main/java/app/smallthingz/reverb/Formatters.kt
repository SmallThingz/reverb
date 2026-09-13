package app.smallthingz.reverb

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.floor

private val sizeFormatter = object : ThreadLocal<DecimalFormat>() {
    override fun initialValue(): DecimalFormat =
        DecimalFormat(ReverbConfig.FORMAT_SIZE_MIB, DecimalFormatSymbols(Locale.US))
}



fun formatShortTimer(seconds: Float): String {
    val totalSeconds = seconds.toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return if (hours > 0) {
        val hs = hours.toString()
        val chars = CharArray(hs.length + 6)
        var i = 0
        for (c in hs) chars[i++] = c
        chars[i++] = ':'; chars[i++] = DIGIT_0[minutes / 10]; chars[i++] = DIGIT_0[minutes % 10]
        chars[i++] = ':'; chars[i++] = DIGIT_0[secs / 10]; chars[i] = DIGIT_0[secs % 10]
        String(chars)
    } else if (minutes >= 10) {
        val chars = CharArray(5)
        chars[0] = DIGIT_0[minutes / 10]; chars[1] = DIGIT_0[minutes % 10]
        chars[2] = ':'; chars[3] = DIGIT_0[secs / 10]; chars[4] = DIGIT_0[secs % 10]
        String(chars)
    } else {
        val chars = CharArray(4)
        chars[0] = DIGIT_0[minutes]; chars[1] = ':'; chars[2] = DIGIT_0[secs / 10]; chars[3] = DIGIT_0[secs % 10]
        String(chars)
    }
}

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

internal fun parseRangeTimeInput(value: String): Double? {
    val parts = value.trim().split(':')
    if (parts.size !in 1..3 || parts.any { it.isBlank() }) return null

    val secondsPart = parts.last().toDoubleOrNull() ?: return null
    if (!secondsPart.isFinite() || secondsPart < 0.0 || (parts.size > 1 && secondsPart >= 60.0)) return null

    val minutes = if (parts.size >= 2) parts[parts.size - 2].toLongOrNull() ?: return null else 0L
    if (minutes < 0L || (parts.size == 3 && minutes >= 60L)) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    if (hours < 0L) return null

    val wholeSeconds = try {
        Math.addExact(Math.multiplyExact(hours, 3_600L), Math.multiplyExact(minutes, 60L))
    } catch (_: ArithmeticException) {
        return null
    }
    val result = wholeSeconds.toDouble() + secondsPart
    return result.takeIf { it.isFinite() }
}

fun formatShortFileSize(size: Long): String {
    val mebibytes = size.coerceAtLeast(0L) / (1024.0 * 1024.0)
    val formatter = sizeFormatter.get() ?: error("sizeFormatter not initialized")
    return "${formatter.format(mebibytes)}${ReverbConfig.MIB_SUFFIX}"
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

fun formatPlaybackTime(durationMillis: Int): String {
    return formatShortTimer(durationMillis.coerceAtLeast(0) / 1000f)
}
