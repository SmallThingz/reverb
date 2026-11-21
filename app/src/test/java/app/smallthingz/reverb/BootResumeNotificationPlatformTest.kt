package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BootResumeNotificationPlatformTest {
    @Test fun publishesDiscoversAndCancelsOnlyTheOwnUntaggedReminder() {
        val app = RuntimeEnvironment.getApplication()
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        val prefs = getRecorderPreferences(app)
        assertTrue(prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true).commit())
        val singleton = BootResumeNotification::class.java.getDeclaredField("controller").apply { isAccessible = true }
        val worker = BootResumeNotification::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(BootResumeNotification) as ThreadPoolExecutor
        val priorController = singleton.get(BootResumeNotification)
        fun resetProcessOwner() = singleton.set(BootResumeNotification, null)
        fun drain() {
            val finished = CountDownLatch(1)
            worker.execute(finished::countDown)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
        fun boot() {
            val finished = CountDownLatch(1)
            BootResumeNotification.onBoot(app, finished::countDown)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
        fun ids() = manager.activeNotifications.map { it.id to it.tag }.toSet()
        val reminder = 45 to null
        val unrelated = setOf(45 to "other-tag", 46 to null)
        try {
            resetProcessOwner()
            boot()
            assertEquals(setOf(reminder), ids())
            val channel = manager.activeNotifications.single().notification.channelId
            assertEquals(ReverbNotificationChannel.RESTART_REMINDERS.id, channel)
            fun unrelatedNotice() = Notification.Builder(app, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Control notification").build()
            manager.notify("other-tag", 45, unrelatedNotice())
            manager.notify(46, unrelatedNotice())
            manager.cancel(45)
            resetProcessOwner()
            BootResumeNotification.discoverExistingNotice(app)
            drain()
            assertFalse(BootResumeNotification.needsCaptureProof)
            assertEquals(unrelated, ids())

            boot()
            assertEquals(unrelated + reminder, ids())
            resetProcessOwner()
            BootResumeNotification.discoverExistingNotice(app)
            drain()
            assertTrue(BootResumeNotification.needsCaptureProof)
            BootResumeNotification.dismiss(app)
            drain()
            assertFalse(BootResumeNotification.needsCaptureProof)
            assertEquals(unrelated, ids())
        } finally {
            drain()
            singleton.set(BootResumeNotification, priorController)
            manager.cancelAll()
            prefs.edit().remove(PrefKey.AUDIO_MEMORY_ENABLED).commit()
        }
    }
}
