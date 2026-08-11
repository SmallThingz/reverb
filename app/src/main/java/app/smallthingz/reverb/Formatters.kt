package app.smallthingz.reverb

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

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
