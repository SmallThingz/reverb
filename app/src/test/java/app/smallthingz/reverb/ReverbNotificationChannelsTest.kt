package app.smallthingz.reverb

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "en")
class ReverbNotificationChannelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun producersExposeExactlyTheFourRequestedCategories() {
        ReverbNotificationChannel.entries.forEach { ReverbNotificationChannels.ensure(context, it) }
        assertEquals(setOf("Recording and background tasks", "Save results", "Restart reminders", "Recording playback"),
            manager.notificationChannels.map { it.name.toString() }.toSet())
        assertEquals("Recording save progress, recovery, confirmations, and failures.",
            manager.getNotificationChannel(ReverbService.NOTIFICATION_CHANNEL_ID).description)
    }

    @Test fun metadataRefreshPreservesExistingSaveResultsPreferences() {
        val existing = NotificationChannel(ReverbService.NOTIFICATION_CHANNEL_ID, "Old name", NotificationManager.IMPORTANCE_NONE)
        existing.description = "Old description"
        existing.setSound(null, null)
        existing.enableVibration(true)
        manager.createNotificationChannel(existing)
        ReverbNotificationChannels.ensure(context, ReverbNotificationChannel.SAVE_RESULTS)
        ReverbNotificationChannels.ensure(context, ReverbNotificationChannel.SAVE_RESULTS)
        val refreshed = manager.getNotificationChannel(existing.id)
        assertEquals("Save results", refreshed.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_NONE, refreshed.importance)
        assertNull(refreshed.sound)
        assertTrue(refreshed.shouldVibrate())
        // A previously allowed progress channel must not bypass the combined category's block.
        val legacy = "durable-save-progress"
        manager.createNotificationChannel(NotificationChannel(legacy, "Old progress", NotificationManager.IMPORTANCE_LOW))
        manager.notify("old-progress", 44, Notification.Builder(context, legacy)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        ReverbNotificationChannels.retireLegacySaveProgress(manager)
        assertNull(manager.getNotificationChannel(legacy))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(existing.id).importance)
    }

    @Test fun oldChannelRetiresOnlyAfterItsActiveNotificationMovesWithoutLosingOtherNotifications() {
        val legacy = "durable-save-progress"
        manager.createNotificationChannel(NotificationChannel(legacy, "Old progress", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("unrelated", "Other", NotificationManager.IMPORTANCE_LOW))
        val job = PendingSave("test-job", "Recording", 1, 1000, SavePhase.RECOVERING)
        val tag = durableSaveNotificationTag(job.id)
        manager.notify(tag, DURABLE_SAVE_NOTIFICATION_ID, Notification.Builder(context, legacy)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Recovering").build())
        manager.notify("other", 7, Notification.Builder(context, "unrelated")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Unrelated").build())
        ReverbNotificationChannels.retireLegacySaveProgress(manager)
        assertNotNull(manager.getNotificationChannel(legacy))
        assertEquals(2, manager.activeNotifications.size)

        ReverbNotificationChannels.ensure(context, ReverbNotificationChannel.SAVE_RESULTS)
        manager.notify(tag, DURABLE_SAVE_NOTIFICATION_ID, buildSaveNotification(job))
        ReverbNotificationChannels.retireLegacySaveProgress(manager)
        ReverbNotificationChannels.retireLegacySaveProgress(manager)
        assertNull(manager.getNotificationChannel(legacy))
        assertEquals(2, manager.activeNotifications.size)
        assertEquals(ReverbService.NOTIFICATION_CHANNEL_ID,
            manager.activeNotifications.single { it.tag == tag }.notification.channelId)
        assertEquals("Unrelated", manager.activeNotifications.single { it.tag == "other" }
            .notification.extras.getCharSequence(Notification.EXTRA_TITLE))
    }

    @Test fun everySavePhaseUsesSaveResultsWithoutIntroducingProgressAlerts() {
        for (phase in SavePhase.entries) {
            val notice = buildSaveNotification(PendingSave("job", "Recording", 1, 1000, phase, .5f, "Failure detail"))
            assertEquals(ReverbService.NOTIFICATION_CHANNEL_ID, notice.channelId)
            assertNull(notice.sound)
            assertTrue(notice.vibrate?.isEmpty() != false)
            assertEquals(0, notice.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
            assertEquals(Notification.GROUP_ALERT_SUMMARY, notice.groupAlertBehavior)
            assertNotNull(notice.group)
            assertTrue(notice.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(phase != SavePhase.FAILED, notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
        }
    }

    private fun buildSaveNotification(job: PendingSave): Notification =
        DurableSaveNotifications::class.java.getDeclaredMethod("buildNotification", Context::class.java,
            PendingSave::class.java, RenderedSaveNotification::class.java).apply { isAccessible = true }
            .invoke(DurableSaveNotifications, context, job, renderedSaveNotification(job)) as Notification
}
