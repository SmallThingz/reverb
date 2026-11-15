package app.smallthingz.reverb

import android.content.Context

/** Adds the requested-duration result to the existing save notification without changing Home. */
internal class QuickSaveFileReceiver(
    context: Context,
    private val requestedSeconds: Int,
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext
    private val delegate = NotifyFileReceiver(appContext)

    override fun fileReady(recording: RecordingEntity) {
        val message = quickSaveCompletionMessage(appContext, recording, requestedSeconds)
        delegate.fileReady(recording, message)
    }

    override fun fileFailed(message: String, error: Throwable?) = delegate.fileFailed(message, error)
    override fun fileCancelled() = delegate.fileCancelled()
}

internal fun quickSaveWasShortened(requestedSeconds: Int?, actualDurationMillis: Long): Boolean =
    requestedSeconds != null && validQuickSaveSeconds(requestedSeconds) && actualDurationMillis >= 0L &&
        requestedSeconds.toLong() * 1_000L - actualDurationMillis > 1L

internal fun quickSaveCompletionMessage(
    context: Context,
    recording: RecordingEntity,
    requestedSeconds: Int?,
): String? = if (quickSaveWasShortened(requestedSeconds, recording.durationMillis)) {
    context.getString(
        R.string.quick_save_shortened,
        formatRangeTimeInput(recording.durationMillis / 1_000.0),
        formatShortTimer(requireNotNull(requestedSeconds).toFloat()),
    )
} else null
