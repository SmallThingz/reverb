package app.smallthingz.reverb

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object BootResumeNotification {
    private val CHANNEL_ID = ReverbNotificationChannel.RESTART_REMINDERS.id
    private const val NOTIFICATION_ID = 45
    private val worker = ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
        runnable -> Thread(runnable, "reverb-boot-notice").apply { isDaemon = true }
    }
    @Volatile private var controller: BootResumeNoticeController? = null

    val needsCaptureProof: Boolean get() = controller?.needsCaptureProof == true

    fun discoverExistingNotice(context: Context) {
        controller(context).discoverExistingNotice()
    }

    fun onBoot(context: Context, finished: () -> Unit) {
        controller(context).onBoot(finished)
    }

    fun dismiss(context: Context) {
        try {
            controller(context).dismiss()
        } catch (error: Exception) {
            Log.w("BootResumeNotification", "Unable to cancel recording resume notification", error)
            RecordingIncidentStore.recordUnexpectedErrorInBackground(context,
                "Cancel recording resume notification", error)
        }
    }

    fun deliveryAvailable(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            appNotificationsAvailable(context, manager) &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } catch (error: Exception) {
            report(context, error)
            false
        }
    }

    fun reviewIntent(context: Context): Intent {
        val action = try {
            val manager = context.getSystemService(NotificationManager::class.java)
            bootResumeNotificationSettingsAction(
                appAllowed = manager != null && appNotificationsAvailable(context, manager),
                channelImportance = manager?.getNotificationChannel(CHANNEL_ID)?.importance,
            )
        } catch (error: Exception) {
            report(context, error)
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        }
        return Intent(action).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (action == Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
    }

    private fun appNotificationsAvailable(context: Context, manager: NotificationManager): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    @Synchronized
    private fun controller(context: Context): BootResumeNoticeController {
        controller?.let { return it }
        val app = context.applicationContext
        return BootResumeNoticeController(
            enqueue = { worker.execute(it) },
            readEnabled = { readDurableCaptureIntentPreferences(getRecorderPreferences(app)).enabled },
            deliveryAvailable = { deliveryAvailable(app) },
            post = { post(app) },
            cancel = { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) },
            isNoticeActive = {
                checkNotNull(app.getSystemService(NotificationManager::class.java)).activeNotifications.any {
                    it.id == NOTIFICATION_ID && it.tag == null
                }
            },
            report = { error -> report(app, error) },
            resume = { allowed -> BootResumeActivity.launch(app, allowed) },
        ).also { controller = it }
    }

    private fun report(context: Context, error: Exception) {
        Log.w("BootResumeNotification", "Unable to update recording resume notification", error)
        if (error !is SecurityException) RecordingIncidentStore.recordUnexpectedErrorInBackground(
            context, "Recording resume notification", error)
    }

    private fun post(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ReverbNotificationChannels.ensure(context, ReverbNotificationChannel.RESTART_REMINDERS)
        if (!deliveryAvailable(context)) return false
        // Opening the ordinary app re-reads current capture intent. This token never carries Start authority.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.REVIEW_RECORDING_AFTER_BOOT"
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = context.getString(R.string.boot_resume_detail)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setContentTitle(context.getString(R.string.boot_resume_title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build())
        return true
    }
}

internal fun bootResumeNotificationSettingsAction(appAllowed: Boolean, channelImportance: Int?): String =
    if (appAllowed && channelImportance == NotificationManager.IMPORTANCE_NONE)
        Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS

/** All platform work is serialized off the caller thread; dismissal invalidates queued boot work immediately. */
internal class BootResumeNoticeController(
    private val enqueue: (() -> Unit) -> Unit,
    private val readEnabled: () -> Boolean,
    private val deliveryAvailable: () -> Boolean,
    private val post: () -> Boolean,
    private val cancel: () -> Unit,
    private val report: (Exception) -> Unit,
    private val isNoticeActive: () -> Boolean = { false },
    private val resume: (() -> Boolean) -> Unit = {},
) {
    private val dismissed = AtomicBoolean(false)
    private val discoveryQueued = AtomicBoolean(false)
    @Volatile private var awaitingCaptureProof = false
    private var posted = false // Worker-owned; duplicate broadcasts must not redisplay a dismissed notice.
    private var resumeAttempted = false

    val needsCaptureProof: Boolean get() = awaitingCaptureProof && !dismissed.get()

    fun discoverExistingNotice() {
        if (!discoveryQueued.compareAndSet(false, true)) return
        enqueue {
            for (attempt in 0..1) {
                if (dismissed.get() || awaitingCaptureProof) return@enqueue
                try {
                    if (isNoticeActive()) {
                        posted = true
                        awaitingCaptureProof = true
                    }
                } catch (error: Exception) {
                    if (attempt == 0) continue
                    discoveryQueued.set(false)
                    report(error)
                }
                return@enqueue
            }
        }
    }

    fun onBoot(finished: () -> Unit) {
        enqueue {
            try {
                if (dismissed.get() || !readEnabled()) {
                    awaitingCaptureProof = false
                    cancel()
                    return@enqueue
                }
                try {
                    if (!deliveryAvailable()) {
                        awaitingCaptureProof = false
                        cancel()
                    } else if (!posted && !dismissed.get()) {
                        awaitingCaptureProof = post()
                        posted = true
                    }
                } catch (error: Exception) { report(error) }
                // Notification permission is independent of the visible-Activity route.
                if (!resumeAttempted && !dismissed.get()) {
                    resumeAttempted = true
                    try { resume { !dismissed.get() } } catch (error: Exception) { report(error) }
                }
            } catch (error: Exception) {
                awaitingCaptureProof = false
                try { cancel() } catch (cancelError: Exception) { error.addSuppressed(cancelError) }
                report(error)
            } finally {
                finished()
            }
        }
    }

    fun dismiss() {
        if (!dismissed.compareAndSet(false, true)) return
        // If publication has already passed its check, this cancel runs after that publication.
        enqueue {
            try { cancel() } catch (error: Exception) { report(error) }
        }
    }
}
