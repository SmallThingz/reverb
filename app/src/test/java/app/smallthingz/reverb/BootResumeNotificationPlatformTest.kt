package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.Closeable
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BootResumeNotificationPlatformTest {
    @Test fun onlyFrameworkAcceptedBindingCanAttributeARecorderFailureToAutoStart() {
        for (reply in listOf(false, null, true)) {
            val queue = ArrayDeque<() -> Unit>()
            fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
            var posted: BootAutomaticNotice? = null
            lateinit var events: BootResumeEvents
            val notices = BootResumeNoticeController(
                enqueue = queue::addLast, readEnabled = { true },
                deliveryAvailable = { false }, post = { error("Manual channel is blocked") }, cancel = {},
                report = { throw AssertionError(it) }, automaticDeliveryAvailable = { true },
                postAutomatic = { posted = it; true },
                resume = { _, callbacks ->
                    events = callbacks
                    callbacks.attempting(60_000L)
                    Closeable {}
                },
            )
            notices.onBoot {}
            drain()
            val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val activity = host.get()
            var binderCalls = 0
            var unbinds = 0
            val platform = object : ContextWrapper(activity) {
                override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                    binderCalls++
                    return reply ?: throw SecurityException("fixture bind denial")
                }
                override fun unbindService(conn: ServiceConnection) { unbinds++ }
            }
            val view = View(activity)
            activity.setContentView(view)
            val binding = CaptureServiceUiOwner().bind(platform, activity, view, isAlive = { true },
                callbacks = object : CaptureServiceUiOwner.BindingCallbacks {
                    override fun bindingAccepted() { events.captureBindingAccepted() }
                    override fun connected(connectedService: ReverbService) = error("The fixture does not deliver a binder")
                    override fun unavailable(markSavingAsCancelRequested: Boolean) = Unit
                    override fun stopped() = Unit
                })
            try {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
                assertTrue(binderCalls > 0)
                val terminal = BootAutomaticNotice.Failed(BootResumeFailure.AUDIO_INPUT, intentCleared = true)
                assertEquals(reply == true, notices.captureFailed(terminal))
                drain()
                assertEquals(if (reply == true) terminal else BootAutomaticNotice.Trying(60_000L), posted)
            } finally {
                binding.close()
                host.pause().stop().destroy()
            }
            assertEquals(if (reply == true) 1 else 0, unbinds)
        }
    }

    @Test fun throwingBindAcceptanceRetiresConnectionAndKeepsUnbindFailureSuppressed() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = host.get()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val events = mutableListOf<String>()
        val callbackFailure = IllegalStateException("Bind acceptance callback failed")
        val unbindFailure = IllegalArgumentException("Platform unbind failed")
        lateinit var retiredConnection: ServiceConnection
        val platform = object : ContextWrapper(activity) {
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                events.add("bind")
                retiredConnection = conn
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                assertSame(retiredConnection, conn)
                events.add("unbind")
                throw unbindFailure
            }
        }
        val callbacks = object : CaptureServiceUiOwner.BindingCallbacks {
            override fun bindingAccepted() {
                events.add("accepted")
                throw callbackFailure
            }
            override fun connected(connectedService: ReverbService) = error("The fixture does not deliver a binder")
            override fun unavailable(markSavingAsCancelRequested: Boolean) { events.add("unavailable") }
            override fun stopped() { events.add("stopped") }
        }
        val view = View(activity)
        activity.setContentView(view)
        val owner = CaptureServiceUiOwner()
        val binding = owner.bind(platform, lifecycleOwner, view, isAlive = { true }, callbacks = callbacks)
        val loop = shadowOf(Looper.getMainLooper())
        try {
            assertEquals(1, lifecycleOwner.lifecycle.observerCount)
            val delivered = runCatching { loop.idleFor(Duration.ofMillis(300)) }.exceptionOrNull()
            assertSame(callbackFailure, generateSequence(delivered) { it.cause }.lastOrNull())
            assertSame(unbindFailure, callbackFailure.suppressed.single())
            assertSame(unbindFailure, owner.lastUnbindResult?.exceptionOrNull())
            assertEquals(0, lifecycleOwner.lifecycle.observerCount)

            retiredConnection.onServiceDisconnected(ComponentName(activity, ReverbService::class.java))
            lifecycleOwner.lifecycle.currentState = Lifecycle.State.CREATED
            lifecycleOwner.lifecycle.currentState = Lifecycle.State.RESUMED
            loop.idleFor(Duration.ofMillis(300))
            assertEquals(listOf("bind", "accepted", "stopped", "unbind"), events)

            // A replacement may reserve this owner before a caller closes its retired handle.
            owner.bind(platform, lifecycleOwner, view, isAlive = { false }, callbacks = callbacks).use {
                binding.close()
                assertEquals(1, lifecycleOwner.lifecycle.observerCount)
                assertEquals(listOf("bind", "accepted", "stopped", "unbind"), events)
            }
        } finally {
            binding.close()
            host.pause().stop().destroy()
        }
        assertEquals(listOf("bind", "accepted", "stopped", "unbind", "stopped"), events)
    }

    @Test fun earlyAutoFailureUsesASeparateKeyAndSurvivesTheOriginalTryingDeadline() {
        val app = RuntimeEnvironment.getApplication()
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        val manual = ReverbNotificationChannel.RESTART_REMINDERS
        manager.createNotificationChannel(NotificationChannel(manual.id, "Blocked reminder", NotificationManager.IMPORTANCE_NONE))
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        lateinit var events: BootResumeEvents
        val controller = BootResumeNoticeController(
            enqueue = { it() }, readEnabled = { true }, deliveryAvailable = { false },
            post = { error("Manual channel is blocked") }, cancel = {}, report = { throw AssertionError(it) },
            automaticDeliveryAvailable = { true }, postAutomatic = { publishAutomatic(app, it) },
            cancelAutomatic = { cancelBootAutomaticNotifications(manager::cancel) },
            resume = { _, callbacks -> events = callbacks; Closeable {} },
        )
        controller.onBoot {}
        events.attempting(deadline)
        val trying = manager.activeNotifications.single()
        val channel = ReverbNotificationChannel.AUTO_START.id
        assertEquals(47, trying.id)
        assertEquals(channel, trying.notification.channelId)
        assertEquals(app.getString(R.string.boot_auto_start_trying), trying.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertTrue(trying.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(trying.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(trying.notification.timeoutAfter in 1L..60_000L)

        assertTrue(SystemClock.elapsedRealtime() < deadline)
        events.ended(BootResumeFailure.OVERLAY_PERMISSION)
        val failed = manager.activeNotifications.single()
        assertEquals(48, failed.id)
        assertFalse(trying.key == failed.key)
        assertEquals(channel, failed.notification.channelId)
        assertEquals(app.getString(R.string.boot_auto_start_failed), failed.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(app.getString(R.string.boot_auto_start_overlay_unavailable), failed.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(failed.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(failed.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(0L, failed.notification.timeoutAfter)
        val review = shadowOf(failed.notification.contentIntent).savedIntent
        assertEquals(ComponentName(app, MainActivity::class.java), review.component)
        assertFalse(review.hasExtra("boot-resume-launch"))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(manual.id).importance)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60_000L))
        assertTrue(SystemClock.elapsedRealtime() >= deadline)
        // Simulate a legacy timeout already dispatched for the original Android key.
        manager.activeNotifications.firstOrNull { it.key == trying.key }?.let { manager.cancel(it.tag, it.id) }
        assertEquals(failed.key, manager.activeNotifications.single().key)
    }

    @Test fun expiredProgressAndDeniedAutoStartDeliveryNeverPublish() {
        val app = RuntimeEnvironment.getApplication()
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        assertFalse(publishAutomatic(app, BootAutomaticNotice.Trying(SystemClock.elapsedRealtime())))

        val channel = ReverbNotificationChannel.AUTO_START.id
        manager.createNotificationChannel(NotificationChannel(channel, "Blocked auto-start", NotificationManager.IMPORTANCE_NONE))
        assertFalse(publishAutomatic(app, BootAutomaticNotice.Failed(BootResumeFailure.OVERLAY_PERMISSION)))
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(channel).importance)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(publishAutomatic(app, BootAutomaticNotice.Trying(SystemClock.elapsedRealtime() + 60_000L)))
        assertTrue(manager.activeNotifications.isEmpty())
    }

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
        val unrelated = setOf(45 to "other-tag", 46 to null, 47 to "other-tag", 48 to "other-tag")
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
            manager.notify("other-tag", 47, unrelatedNotice())
            manager.notify("other-tag", 48, unrelatedNotice())
            manager.cancel(45)
            resetProcessOwner()
            BootResumeNotification.discoverExistingNotice(app)
            drain()
            assertFalse(BootResumeNotification.needsCaptureProof)
            assertEquals(unrelated, ids())

            val automatic = listOf(
                47 to BootAutomaticNotice.Trying(SystemClock.elapsedRealtime() + 60_000L),
                48 to BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT),
            )
            for ((id, notice) in automatic) {
                assertTrue(publishAutomatic(app, notice))
                resetProcessOwner()
                BootResumeNotification.discoverExistingNotice(app)
                drain()
                assertTrue(BootResumeNotification.needsCaptureProof)
                assertEquals(unrelated + (id to null), ids())
                BootResumeNotification.dismissAutomaticStatus(app)
                drain()
                assertFalse(BootResumeNotification.needsCaptureProof)
                assertEquals(unrelated, ids())
            }

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

    private fun publishAutomatic(context: Context, notice: BootAutomaticNotice): Boolean =
        BootResumeNotification::class.java.getDeclaredMethod("postAutomatic", Context::class.java, BootAutomaticNotice::class.java)
            .apply { isAccessible = true }.invoke(BootResumeNotification, context, notice) as Boolean
}
