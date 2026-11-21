package app.smallthingz.reverb

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val AUTO_START_TRYING_NOTIFICATION_ID = 47
private const val AUTO_START_FAILED_NOTIFICATION_ID = 48

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

    fun cancelAutomaticResume() { controller?.cancelAutomaticResume() }

    fun dismissAutomaticStatus(context: Context) { controller(context).dismissAutomaticStatus() }

    fun captureFailed(reason: BootResumeFailure, intentCleared: Boolean,
        detail: String? = null, error: Throwable? = null): Boolean =
        controller?.captureFailed(BootAutomaticNotice.Failed(reason, detail, error, intentCleared)) == true

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
            post = { postReminder(app) },
            cancel = { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) },
            discover = {
                val ids = checkNotNull(app.getSystemService(NotificationManager::class.java)).activeNotifications
                    .filter { it.tag == null }.map { it.id }.toSet()
                BootResumeNotices(NOTIFICATION_ID in ids,
                    AUTO_START_TRYING_NOTIFICATION_ID in ids || AUTO_START_FAILED_NOTIFICATION_ID in ids)
            },
            automaticDeliveryAvailable = { automaticDeliveryAvailable(app) },
            postAutomatic = { postAutomatic(app, it) },
            cancelAutomatic = {
                app.getSystemService(NotificationManager::class.java)?.let { cancelBootAutomaticNotifications(it::cancel) }
            },
            report = { error -> report(app, error) },
            resume = { allowed, events -> startAutomaticResume(app, allowed, events) },
        ).also { controller = it }
    }

    private fun startAutomaticResume(app: Context, noticeAllowsResume: () -> Boolean,
        events: BootResumeEvents): Closeable? {
        val autoStartRevision = AutoStartAfterRestart.admission(app) ?: return null
        val mainVisibilityRevision = BootResumeActivity.mainVisibilityRevision
        val mainHandler = Handler(Looper.getMainLooper())
        return BootResumeRetry(
            elapsedMillis = SystemClock::elapsedRealtime,
            enqueue = { worker.execute(it) },
            schedule = { delay, run ->
                val callback = Runnable { worker.execute(run) }
                mainHandler.postDelayed(callback, delay)
                Closeable { mainHandler.removeCallbacks(callback) }
            },
            watchWindow = { changed -> watchResumeAvailability(app, changed) },
            windowReady = { BootResumeActivity.unlockedInteractive(app) },
            allowed = {
                noticeAllowsResume() && AutoStartAfterRestart.policy.currentAdmission(autoStartRevision) &&
                    !BootResumeActivity.mainTookOver(mainVisibilityRevision)
            },
            durableIntentEnabled = { readDurableCaptureIntentPreferences(getRecorderPreferences(app)).enabled },
            launch = { deadline, allowed, cancel ->
                BootResumeActivity.launch(app, deadline, allowed, events.captureBindingAccepted) { events.cancel(); cancel() }
            },
            report = { report(app, it) },
            permissionFailure = { permissionFailure(app) },
            attempting = events.attempting,
            ended = events.ended,
        ).also { it.start() }
    }

    private fun permissionFailure(app: Context): BootResumeFailure? = when {
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            BootResumeFailure.MICROPHONE_PERMISSION
        !BootResumeActivity.overlayAvailable(app) -> BootResumeFailure.OVERLAY_PERMISSION
        else -> null
    }

    private fun watchResumeAvailability(app: Context, changed: (screenTurnedOff: Boolean) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (BuildConfig.DEBUG) {
                    Log.i("BootResumeNotification",
                        "resume-availability-broadcast action=${intent.action} elapsedMs=${SystemClock.elapsedRealtime()}")
                }
                changed(intent.action == Intent.ACTION_SCREEN_OFF)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val appOps = app.getSystemService(AppOpsManager::class.java)
        val permissionChanged = AppOpsManager.OnOpChangedListener { _, _ -> changed(false) }
        // This filter contains only protected broadcasts. SystemUI sends USER_PRESENT from its own UID.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            appOps?.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.packageName, permissionChanged)
        } catch (error: Exception) {
            // Each retry and protected window event independently rechecks the grant.
            report(app, error)
        }
        return Closeable {
            try { appOps?.stopWatchingMode(permissionChanged) } finally { app.unregisterReceiver(receiver) }
        }
    }

    private fun report(context: Context, error: Exception) {
        Log.w("BootResumeNotification", "Unable to update recording resume notification", error)
        if (error !is SecurityException) RecordingIncidentStore.recordUnexpectedErrorInBackground(
            context, "Recording resume notification", error)
    }

    private fun postReminder(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ReverbNotificationChannels.ensure(context, ReverbNotificationChannel.RESTART_REMINDERS)
        if (!deliveryAvailable(context)) return false
        val detail = context.getString(R.string.boot_resume_detail)
        manager.notify(NOTIFICATION_ID, bootNotice(context, CHANNEL_ID, R.string.boot_resume_title, detail)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true).build())
        return true
    }

    private fun automaticDeliveryAvailable(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return appNotificationsAvailable(context, manager) &&
            manager.getNotificationChannel(ReverbNotificationChannel.AUTO_START.id)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun postAutomatic(context: Context, notice: BootAutomaticNotice): Boolean {
        if (notice is BootAutomaticNotice.Trying && notice.deadlineMillis <= SystemClock.elapsedRealtime()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = ReverbNotificationChannel.AUTO_START
        ReverbNotificationChannels.ensure(context, channel)
        if (!automaticDeliveryAvailable(context)) return false
        val notification = when (notice) {
            is BootAutomaticNotice.Trying -> {
                val remaining = notice.deadlineMillis - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return false
                bootNotice(context, channel.id, R.string.boot_auto_start_trying, context.getString(R.string.boot_auto_start_trying_detail))
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS).setOngoing(true).setTimeoutAfter(remaining)
            }
            is BootAutomaticNotice.Failed -> {
                bootNotice(context, channel.id, R.string.boot_auto_start_failed, failureDetail(context, notice))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
            }
        }
        // Older Android releases can deliver a timeout to the latest notice at the original key.
        val id = if (notice is BootAutomaticNotice.Trying) AUTO_START_TRYING_NOTIFICATION_ID else AUTO_START_FAILED_NOTIFICATION_ID
        manager.notify(id, notification.build())
        return true
    }

    private fun failureDetail(context: Context, notice: BootAutomaticNotice.Failed): String = when (notice.reason) {
        BootResumeFailure.TIMEOUT -> context.getString(R.string.boot_auto_start_timeout)
        BootResumeFailure.MICROPHONE_PERMISSION -> context.getString(R.string.boot_auto_start_microphone_unavailable)
        BootResumeFailure.OVERLAY_PERMISSION -> context.getString(R.string.boot_auto_start_overlay_unavailable)
        BootResumeFailure.UNEXPECTED -> context.getString(R.string.boot_auto_start_error)
        BootResumeFailure.NO_WRITABLE_BUFFER -> context.getString(R.string.boot_auto_start_no_writable_buffer)
        BootResumeFailure.AUDIO_INPUT -> {
            val message = notice.detail ?: context.getString(R.string.audio_input_init_failed)
            val detail = notice.error?.let { actionableErrorMessage(context, message, it) } ?: message
            context.getString(R.string.boot_auto_start_capture_error, detail)
        }
    }

    private fun bootNotice(context: Context, channel: String, title: Int, detail: String): NotificationCompat.Builder {
        // Opening the ordinary app re-reads current capture intent. This token never carries Start authority.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.REVIEW_RECORDING_AFTER_BOOT"
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setContentTitle(context.getString(title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
    }
}

internal fun cancelBootAutomaticNotifications(cancel: (Int) -> Unit) {
    val trying = runCatching { cancel(AUTO_START_TRYING_NOTIFICATION_ID) }
    val failed = runCatching { cancel(AUTO_START_FAILED_NOTIFICATION_ID) }
    trying.exceptionOrNull()?.let { error ->
        failed.exceptionOrNull()?.takeUnless { it === error }?.let(error::addSuppressed)
        throw error
    }
    failed.getOrThrow()
}

internal fun bootResumeNotificationSettingsAction(appAllowed: Boolean, channelImportance: Int?): String =
    if (appAllowed && channelImportance == NotificationManager.IMPORTANCE_NONE)
        Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS

internal data class BootResumeNotices(val reminder: Boolean = false, val automatic: Boolean = false)

internal sealed interface BootAutomaticNotice {
    data class Trying(val deadlineMillis: Long) : BootAutomaticNotice
    data class Failed(val reason: BootResumeFailure, val detail: String? = null,
        val error: Throwable? = null, val intentCleared: Boolean = false) : BootAutomaticNotice
}

internal data class BootResumeEvents(
    val attempting: (Long) -> Unit,
    val ended: (BootResumeFailure?) -> Unit,
    val cancel: () -> Unit,
    val captureBindingAccepted: () -> Unit,
)

/** Platform work is serialized on the worker. Stops and cancellations revoke ownership immediately. */
internal class BootResumeNoticeController(
    private val enqueue: (() -> Unit) -> Unit,
    private val readEnabled: () -> Boolean,
    private val deliveryAvailable: () -> Boolean,
    private val post: () -> Boolean,
    private val cancel: () -> Unit,
    private val report: (Exception) -> Unit,
    private val discover: () -> BootResumeNotices = { BootResumeNotices() },
    private val automaticDeliveryAvailable: () -> Boolean = { false },
    private val postAutomatic: (BootAutomaticNotice) -> Boolean = { false },
    private val cancelAutomatic: () -> Unit = {},
    private val resume: (() -> Boolean, BootResumeEvents) -> Closeable? = { _, _ -> null },
) {
    private class AutomaticResume {
        @Volatile var work: Closeable? = null
        @Volatile var captureBindingAccepted = false
        val terminal = AtomicReference<BootAutomaticNotice.Failed?>()
    }

    private val dismissed = AtomicBoolean(false)
    private val automaticStatusDismissed = AtomicBoolean(false)
    private val discoveryQueued = AtomicBoolean(false)
    @Volatile private var awaitingCaptureProof = false
    private val activeResume = AtomicReference<AutomaticResume?>()

    // Worker-owned. Duplicate boot broadcasts cannot reset the deadline or republish a notice.
    private var resumeStarted = false
    private var reminderOffered = false
    private var reminderPosted = false
    // Local publication and exact cancellation take precedence over a later registry snapshot.
    private var automaticPosted: Boolean? = null
    private var automaticNoticeOwner: AutomaticResume? = null
    private var automaticFailureClearedIntent = false
    private var discoveryCompleted = false

    val needsCaptureProof: Boolean get() = awaitingCaptureProof && !dismissed.get()

    fun discoverExistingNotice() {
        if (!discoveryQueued.compareAndSet(false, true)) return
        enqueue(::discoverOnWorker)
    }

    private fun discoverOnWorker() {
        if (discoveryCompleted || dismissed.get() || awaitingCaptureProof) return
        for (attempt in 0..1) {
            try {
                val visible = discover()
                reminderPosted = visible.reminder
                reminderOffered = reminderOffered || visible.reminder
                automaticPosted = automaticPosted ?: visible.automatic
                refreshProof()
                discoveryCompleted = true
            } catch (error: Exception) {
                if (attempt == 0) continue
                discoveryQueued.set(false)
                report(error)
            }
            return
        }
    }

    fun onBoot(finished: () -> Unit) {
        enqueue {
            try {
                if (dismissed.get() || !readEnabled()) {
                    if (!dismissed.get() && (automaticFailureClearedIntent ||
                        activeResume.get()?.terminal?.get()?.intentCleared == true)) return@enqueue
                    cancelAutomaticResume()
                    cancelNotices()
                    return@enqueue
                }
                // A Service can request discovery before its worker task is actually submitted.
                discoveryQueued.set(true)
                discoverOnWorker()
                try { offerReminder() } catch (error: Exception) { report(error) }
                // Notification permission is independent of the visible-Activity route.
                if (!resumeStarted && !dismissed.get() && !automaticStatusDismissed.get()) startResume()
            } catch (error: Exception) {
                cancelAutomaticResume()
                try { cancelNotices() } catch (cancelError: Exception) { error.addSuppressed(cancelError) }
                report(error)
            } finally {
                finished()
            }
        }
    }

    private fun startResume() {
        resumeStarted = true
        val owner = AutomaticResume()
        activeResume.set(owner)
        try {
            val work = resume({ retryAllowed(owner) }, BootResumeEvents(
                attempting = { deadline -> publishAutomatic(owner, BootAutomaticNotice.Trying(deadline)) },
                ended = { failure -> endResume(owner, failure) },
                cancel = { cancelAutomaticResume(owner) },
                captureBindingAccepted = { if (retryAllowed(owner)) owner.captureBindingAccepted = true },
            ))
            owner.work = work
            if (work == null || !current(owner)) {
                activeResume.compareAndSet(owner, null)
                work?.close()
            }
        } catch (error: Exception) {
            cancelAutomaticResume(owner)
            report(error)
        }
    }

    private fun endResume(owner: AutomaticResume, failure: BootResumeFailure?) {
        if (!retryAllowed(owner)) return
        if (failure == null) return cancelAutomaticResume(owner)
        val enabled = try { failure == BootResumeFailure.UNEXPECTED || readEnabled() }
        catch (error: Exception) {
            report(error)
            return cancelAutomaticResume(owner)
        }
        if (!retryAllowed(owner)) return
        if (!enabled) return cancelAutomaticResume(owner)
        val notice = BootAutomaticNotice.Failed(failure)
        if (owner.terminal.compareAndSet(null, notice)) publishTerminal(owner, notice)
    }

    /** Called at the current recorder intent transition. No platform notification work on that thread. */
    fun captureFailed(notice: BootAutomaticNotice.Failed): Boolean {
        val owner = activeResume.get() ?: return false
        if (!current(owner) || !owner.captureBindingAccepted) return false
        if (owner.terminal.compareAndSet(null, notice)) enqueue {
            closeResumeWork(owner)
            publishTerminal(owner, notice)
        }
        return true
    }

    private fun publishTerminal(owner: AutomaticResume, notice: BootAutomaticNotice.Failed) {
        if (notice.intentCleared && mayPublish(owner, notice)) {
            try {
                cancel()
                reminderPosted = false
                refreshProof()
            } catch (error: Exception) { report(error) }
        }
        publishAutomatic(owner, notice)
        if (activeResume.compareAndSet(owner, null)) automaticNoticeOwner = null
    }

    private fun current(owner: AutomaticResume): Boolean =
        !dismissed.get() && !automaticStatusDismissed.get() && activeResume.get() === owner

    private fun retryAllowed(owner: AutomaticResume): Boolean = current(owner) && owner.terminal.get() == null

    private fun mayPublish(owner: AutomaticResume, notice: BootAutomaticNotice): Boolean = current(owner) &&
        owner.terminal.get().let { it == null || it === notice }

    private fun offerReminder(replace: Boolean = false) {
        if (!discoveryCompleted || dismissed.get() || reminderPosted || automaticPosted == true ||
            (!replace && reminderOffered) || !deliveryAvailable()) return
        reminderOffered = true
        if (!dismissed.get()) reminderPosted = post()
        refreshProof()
    }

    private fun publishAutomatic(owner: AutomaticResume, notice: BootAutomaticNotice) {
        if (!mayPublish(owner, notice)) return
        // Unknown registry state cannot license a second boot notice in the other channel.
        // Keep the helper independent, and retry discovery at the next notice transition.
        discoverOnWorker()
        if (!discoveryCompleted || !mayPublish(owner, notice)) return
        try {
            if (automaticDeliveryAvailable()) {
                if (automaticPosted == true) retireAutomaticNotice()
                if (reminderPosted) {
                    cancel()
                    reminderPosted = false
                }
                if (!mayPublish(owner, notice)) return
                automaticPosted = true // A throwing notify call can leave either owned slot present.
                automaticNoticeOwner = owner
                if (postAutomatic(notice)) {
                    automaticFailureClearedIntent = (notice as? BootAutomaticNotice.Failed)?.intentCleared == true
                    return
                }
            }
            if (automaticPosted == true) retireAutomaticNotice()
            offerFallback(owner, notice)
        } catch (error: Exception) {
            try {
                // A failing platform publication may already have changed its exact notification slot.
                retireAutomaticNotice()
                offerFallback(owner, notice)
            } catch (fallbackError: Exception) {
                if (fallbackError !== error) error.addSuppressed(fallbackError)
            }
            report(error)
        } finally {
            refreshProof()
        }
    }

    private fun offerFallback(owner: AutomaticResume, notice: BootAutomaticNotice) {
        if ((notice as? BootAutomaticNotice.Failed)?.intentCleared != true && mayPublish(owner, notice))
            offerReminder(replace = true)
    }

    fun dismiss() {
        if (!dismissed.compareAndSet(false, true)) return
        cancelAutomaticResume()
        // If publication has passed its check, both cancels run after that publication.
        enqueue {
            try { cancelNotices() } catch (error: Exception) { report(error) }
        }
    }

    fun cancelAutomaticResume() { activeResume.get()?.let(::cancelAutomaticResume) }

    fun dismissAutomaticStatus() {
        automaticStatusDismissed.set(true)
        activeResume.getAndSet(null)?.let(::closeResumeWork)
        enqueue {
            try { retireAutomaticNotice() } catch (error: Exception) { report(error) }
            refreshProof()
        }
    }

    private fun cancelAutomaticResume(owner: AutomaticResume) {
        if (!activeResume.compareAndSet(owner, null)) return
        closeResumeWork(owner)
        if (dismissed.get()) return
        enqueue {
            if (automaticNoticeOwner === owner) {
                try { retireAutomaticNotice() } catch (error: Exception) { report(error) }
                refreshProof()
            }
        }
    }

    private fun closeResumeWork(owner: AutomaticResume) {
        try { owner.work?.close() } catch (error: Exception) { report(error) }
    }

    private fun retireAutomaticNotice() {
        cancelAutomatic()
        automaticNoticeOwner = null
        automaticPosted = false
        automaticFailureClearedIntent = false
    }

    private fun cancelNotices() {
        try { cancel() } finally {
            reminderPosted = false
            try { retireAutomaticNotice() } finally { refreshProof() }
        }
    }

    private fun refreshProof() { awaitingCaptureProof = reminderPosted || automaticPosted == true }
}
