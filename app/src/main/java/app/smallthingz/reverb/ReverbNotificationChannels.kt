package app.smallthingz.reverb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal enum class ReverbNotificationChannel(
    val id: String,
    val nameResource: Int,
    val descriptionResource: Int,
    val initialImportance: Int,
) {
    BACKGROUND(ReverbService.BACKGROUND_NOTIFICATION_CHANNEL_ID, R.string.notification_channel_background,
        R.string.notification_channel_background_description, NotificationManager.IMPORTANCE_LOW),
    SAVE_RESULTS(ReverbService.NOTIFICATION_CHANNEL_ID, R.string.notification_channel_save_results,
        R.string.notification_channel_save_results_description, NotificationManager.IMPORTANCE_DEFAULT),
    PLAYBACK("saved_recording_playback", R.string.recording_playback_channel,
        R.string.notification_channel_playback_description, NotificationManager.IMPORTANCE_LOW),
    RESTART_REMINDERS("recording-resume", R.string.boot_resume_channel,
        R.string.notification_channel_restart_description, NotificationManager.IMPORTANCE_LOW),
    AUTO_START("recording-auto-start", R.string.boot_auto_start_channel,
        R.string.boot_auto_start_channel_description, NotificationManager.IMPORTANCE_LOW),
}

internal object ReverbNotificationChannels {
    private const val LEGACY_SAVE_PROGRESS_ID = "durable-save-progress"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Create only at the existing producer boundary; existing user settings remain authoritative. */
    fun ensure(context: Context, metadata: ReverbNotificationChannel) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(metadata.id)
        val channel = existing ?: NotificationChannel(metadata.id,
            context.getString(metadata.nameResource), metadata.initialImportance)
        updateMetadata(context, manager, channel, metadata, createMissing = existing == null)
    }

    /** Refresh dormant categories after an app update or locale change without creating new ones. */
    fun refreshExisting(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val manager = app.getSystemService(NotificationManager::class.java) ?: return@launch
                for (channel in manager.notificationChannels) {
                    val metadata = ReverbNotificationChannel.entries.firstOrNull { it.id == channel.id } ?: continue
                    updateMetadata(app, manager, channel, metadata)
                }
                retireLegacySaveProgress(manager)
            } catch (error: Exception) {
                Log.w("ReverbNotificationChannels", "Unable to refresh notification category descriptions", error)
            }
        }
    }

    private fun updateMetadata(context: Context, manager: NotificationManager, channel: NotificationChannel,
        metadata: ReverbNotificationChannel, createMissing: Boolean = false) {
        val name = context.getString(metadata.nameResource)
        val description = context.getString(metadata.descriptionResource)
        if (!createMissing && channel.name?.toString() == name && channel.description == description) return
        // Re-submit the fetched object: using default importance here could lower a channel
        // the user has not customized. Only these two metadata fields are changed.
        channel.name = name
        channel.description = description
        manager.createNotificationChannel(channel)
    }

    // Deleting a channel also cancels its notifications. Keep active notices until they move
    // or retire, unless the user has blocked the combined Save results category.
    fun retireLegacySaveProgress(manager: NotificationManager) {
        if (manager.getNotificationChannel(LEGACY_SAVE_PROGRESS_ID) != null &&
            (manager.activeNotifications.none { it.notification.channelId == LEGACY_SAVE_PROGRESS_ID } ||
                manager.getNotificationChannel(ReverbNotificationChannel.SAVE_RESULTS.id)?.importance == NotificationManager.IMPORTANCE_NONE)) {
            manager.deleteNotificationChannel(LEGACY_SAVE_PROGRESS_ID)
        }
    }
}
