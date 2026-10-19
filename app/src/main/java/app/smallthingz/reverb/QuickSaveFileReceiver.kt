package app.smallthingz.reverb

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val quickSaveResultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Adds the requested-duration result to the existing save notification without changing Home. */
internal class QuickSaveFileReceiver(
    context: Context,
    private val requestedSeconds: Int,
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext
    private val delegate = NotifyFileReceiver(appContext)

    override fun fileReady(recording: RecordingEntity) {
        val message = quickSaveCompletionMessage(appContext, recording, requestedSeconds)
        if (message == null) {
            delegate.fileReady(recording)
            return
        }
        quickSaveResultScope.launch {
            var preparedNotification: Notification? = null
            deliverDetachedSaveSuccess(
                notificationAvailable = {
                    val manager = NotificationManagerCompat.from(appContext)
                    val runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    val runtimePermissionGranted = !runtimePermissionRequired ||
                        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    // The existing builder creates the shared channel and retains the recording's
                    // identity-bound PendingIntent; use that same channel for availability below.
                    preparedNotification = buildCaptureNotification(appContext, recording)
                    !detachedSaveSuccessNeedsInAppFallback(
                        notificationsEnabled = manager.areNotificationsEnabled(),
                        channelEnabled = appContext.getSystemService(NotificationManager::class.java)
                            ?.getNotificationChannel(ReverbService.NOTIFICATION_CHANNEL_ID)
                            ?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } ?: false,
                        runtimePermissionRequired = runtimePermissionRequired,
                        runtimePermissionGranted = runtimePermissionGranted,
                    )
                },
                notify = {
                    val notification = Notification.Builder.recoverBuilder(
                        appContext,
                        requireNotNull(preparedNotification),
                    ).setContentText(message)
                        .setStyle(Notification.BigTextStyle().bigText("$message\n${recording.displayName}"))
                        .build()
                    // Same identity as the normal saved-result notification in CaptureScreen.
                    NotificationManagerCompat.from(appContext).notify(
                        recordingSavedNotificationTag(recording.id), 43, notification,
                    )
                },
                fallback = { AppFeedbackCenter.post(message, FeedbackTone.SUCCESS) },
            )
        }
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
