package app.smallthingz.reverb

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val BOOT_QA_PACKAGE = "app.smallthingz.reverb.reliability"
private const val BOOT_QA_TAG = "BootResumeQA"
private const val BOOT_QA_LAUNCH_TOKEN = "boot-resume-launch"

/** Real helper, base recorder Service and AudioRecord. Only the boot broadcast is simulated. */
internal fun verifyBootResumeActivity(instrumentation: Instrumentation, report: StringBuilder, scenario: String) {
    val app = instrumentation.targetContext.applicationContext as Application
    check(app.packageName == BOOT_QA_PACKAGE && instrumentation.context.packageName == BOOT_QA_PACKAGE &&
        app.applicationInfo.uid == Process.myUid() && app is ReverbApplication)
    check(scenario in setOf("retry", "unlock", "failure"))
    val waitForUnlock = scenario == "unlock"
    val keepDisabled = scenario == "failure"
    check(BootResumeActivity.unlockedInteractive(app)) { "Start with the QA phone interactive and unlocked" }
    val keyguard = checkNotNull(app.getSystemService(KeyguardManager::class.java))
    val power = checkNotNull(app.getSystemService(PowerManager::class.java))
    if (waitForUnlock) check(!keyguard.isDeviceSecure) { "The unlock mode requires a credential-free test phone" }
    check(BootResumeActivity.permissionsAvailable(app)) { "The isolated QA package needs microphone and overlay grants" }
    val manager = checkNotNull(app.getSystemService(ActivityManager::class.java))
    check(bootQaServices(manager).isEmpty()) { "Existing QA service work must settle before this case" }
    val audioRoots = listOf(BUFFER_CACHE_FOLDER_NAME, ONE_SHOT_BUFFER_CACHE_FOLDER_NAME, LEGACY_BUFFER_CACHE_FOLDER_NAME)
    check(audioRoots.none { File(app.noBackupFilesDir, it).exists() }) { "Fresh isolated QA audio stores are required" }
    check(!SilenceGatePreferences.read(app).config.enabled) { "Fresh QA capture must have its default ungated input" }
    val notices = checkNotNull(app.getSystemService(NotificationManager::class.java))
    check(notices.activeNotifications.none { it.tag == null && it.id in setOf(45, 47, 48) }) {
        "An existing QA boot notification needs separate disposition"
    }
    if (keepDisabled) {
        check(notices.areNotificationsEnabled()) { "The notification oracle needs QA notification access" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            check(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        check(notices.getNotificationChannel(ReverbNotificationChannel.AUTO_START.id)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    val helper = ComponentName(app, BootResumeActivity::class.java)
    val bootReceiver = ComponentName(app, BootCompletedReceiver::class.java)
    val serviceIntent = Intent(app, ReverbService::class.java)
    val pm = app.packageManager
    val originalHelperState = pm.getComponentEnabledSetting(helper)
    @Suppress("DEPRECATION")
    val helperInfo = pm.getActivityInfo(helper, PackageManager.MATCH_DISABLED_COMPONENTS)
    @Suppress("DEPRECATION")
    val bootReceiverInfo = pm.getReceiverInfo(bootReceiver, PackageManager.MATCH_DISABLED_COMPONENTS)
    check(originalHelperState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !helperInfo.enabled && !helperInfo.exported)
    check(pm.getComponentEnabledSetting(bootReceiver) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !bootReceiverInfo.enabled)
    val prefs = getRecorderPreferences(app)
    val keys = listOf(PrefKey.AUDIO_MEMORY_ENABLED, PrefKey.CAPTURE_BUFFER_SLOT, PrefKey.AUTO_START_AFTER_RESTART)
    val original = keys.associateWith(prefs::snapshotDurablePreferenceValue)
    check(original.values.all(::durablePreferenceSnapshotIsExactlyRestorable))
    check(!readDurableCaptureIntentPreferences(prefs).enabled) { "The QA package already intends to capture" }
    val main = Handler(Looper.getMainLooper())
    val ledger = BootQaHelperLedger()
    val helperDestroyed = ledger.destroyed
    val dispatches = ArrayList<Pair<String, Long>>() // Main-owned.
    val enableFailure = AtomicReference<Exception?>()
    var firstDispatchDisabled = false
    fun enableHelper() = pm.setComponentEnabledSetting(helper, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP)
    val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component != helper) return null
            dispatches.add(intent.getStringExtra(BOOT_QA_LAUNCH_TOKEN).orEmpty() to SystemClock.elapsedRealtime())
            if (!waitForUnlock && dispatches.size == 1) {
                firstDispatchDisabled = pm.getComponentEnabledSetting(helper) == originalHelperState
                // Run after the actual disabled-component start returns. No canned instrumentation result.
                if (!keepDisabled) main.post {
                    try { enableHelper() } catch (error: Exception) { enableFailure.set(error) }
                }
            }
            Log.i(BOOT_QA_TAG, "dispatch=${dispatches.size} ready=${BootResumeActivity.unlockedInteractive(app)} " +
                "firstDisabled=$firstDispatchDisabled elapsedMs=${dispatches.last().second}")
            return null
        }
    }
    fun milestone(name: String) {
        val line = "$name scenario=$scenario pid=${Process.myPid()} elapsedMs=${SystemClock.elapsedRealtime()} " +
            "interactive=${power.isInteractive} keyguardLocked=${keyguard.isKeyguardLocked} secure=${keyguard.isDeviceSecure}"
        Log.i(BOOT_QA_TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }

    var seeded = false
    var helperClosed = false
    var failedStatus: String? = null
    var failure: Throwable? = null
    fun cleanup(work: () -> Unit) {
        try { work() } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
        }
    }
    app.registerActivityLifecycleCallbacks(ledger)
    instrumentation.addMonitor(monitor)
    try {
        if (waitForUnlock) {
            milestone("BOOT_QA_READY_FOR_SCREEN_OFF")
            awaitBootQa(15_000L, "No noninteractive screen state arrived") { !power.isInteractive }
            check(!BootResumeActivity.unlockedInteractive(app))
            nativeParityOnMain(instrumentation) { enableHelper() }
        }
        synchronized(captureIntentPreferenceLock) {
            seeded = true // Android may update its in-memory preference map even when commit fails.
            check(prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true)
                .putInt(PrefKey.CAPTURE_BUFFER_SLOT, ReverbService.BufferSlot.LOOPING.storageCode.toInt())
                .putBoolean(PrefKey.AUTO_START_AFTER_RESTART, true).commit())
        }
        val bootFinished = CountDownLatch(1)
        val bootAt = SystemClock.elapsedRealtime()
        BootResumeNotification.onBoot(app, bootFinished::countDown)
        check(bootFinished.await(5, TimeUnit.SECONDS)) { "The genuine boot worker did not finish" }
        if (waitForUnlock) {
            nativeParityOnMain(instrumentation) { check(dispatches.isEmpty() && ledger.live == null) }
            check(!BootResumeActivity.unlockedInteractive(app) && bootQaServices(manager).isEmpty())
            milestone("BOOT_QA_WAITING_FOR_UNLOCK")
        }
        if (keepDisabled) {
            failedStatus = observeBootQaFailure(app, notices, manager, bootAt)
            nativeParityOnMain(instrumentation) {
                check(firstDispatchDisabled && pm.getComponentEnabledSetting(helper) == originalHelperState)
                check(ledger.created == 0 && ledger.live == null && !ledger.captureConfirmed)
                check(dispatches.size > 1 && dispatches.map { it.first }.toSet().size == dispatches.size)
            }
            check(bootQaServices(manager).isEmpty() && audioRoots.none { File(app.noBackupFilesDir, it).exists() })
        } else {
            check(helperDestroyed.await(if (waitForUnlock) 25 else 18, TimeUnit.SECONDS)) { "The helper did not finish" }
            enableFailure.get()?.let { throw it }
            nativeParityOnMain(instrumentation) {
                check(ledger.created == 1 && ledger.live == null && ledger.finishingPauseAt > 0L)
                check(ledger.captureConfirmed) { "The helper finished without accepting Service PCM proof" }
                val expectedDispatches = if (waitForUnlock) 1 else 2
                check(dispatches.size == expectedDispatches && dispatches.map { it.first }.toSet().size == expectedDispatches)
                check(dispatches.last().first == ledger.createdToken && ledger.createdToken.isNotEmpty())
                check(waitForUnlock || firstDispatchDisabled)
            }
            helperClosed = true
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        if (seeded) {
            cleanup { BootResumeNotification.dismiss(app) }
            cleanup { // Retire actual Service ownership before restoring the original preferences.
                val recorderRunning = bootQaServices(manager).any { it.service == serviceIntent.component }
                val stopped = if (helperClosed || recorderRunning) stopBootQaRecorder(app, serviceIntent) else false
                check(stopped || !helperClosed && !recorderRunning) {
                    "The QA recorder did not accept Stop"
                }
            }
            cleanup { nativeParityOnMain(instrumentation) { ledger.live?.finish() } }
            cleanup {
                synchronized(captureIntentPreferenceLock) {
                    val editor = prefs.edit()
                    original.forEach { (key, value) -> editor.restoreDurablePreferenceValue(key, value) }
                    check(editor.commit() && original.all { (key, value) -> prefs.snapshotDurablePreferenceValue(key) == value })
                    check(!readDurableCaptureIntentPreferences(prefs).enabled)
                }
            }
            if (failure != null) cleanup { app.stopService(serviceIntent) }
            cleanup { awaitBootQa(5_000L, "The isolated recorder Service did not retire") { bootQaServices(manager).isEmpty() } }
            cleanup { awaitBootQa(3_000L, "The isolated boot notification did not retire") {
                notices.activeNotifications.none { it.tag == null && it.id in setOf(45, 47, 48) }
            } }
        }
        cleanup {
            nativeParityOnMain(instrumentation) {
                instrumentation.removeMonitor(monitor)
                app.unregisterActivityLifecycleCallbacks(ledger)
                pm.setComponentEnabledSetting(helper, originalHelperState, PackageManager.DONT_KILL_APP)
            }
            check(pm.getComponentEnabledSetting(helper) == originalHelperState)
        }
    }
    failure?.let { throw it }
    if (keepDisabled) {
        report.appendLine("PASS boot_resume_activity_failure dispatches=${dispatches.size} " +
            "created=0 serviceStarted=false prefsRestored=true helperRestored=true ${checkNotNull(failedStatus)}")
        return
    }
    report.appendLine("PASS boot_resume_activity_lifecycle scenario=$scenario dispatches=${dispatches.size} " +
        "created=${ledger.created} finishingPauseElapsedMs=${ledger.finishingPauseAt} " +
        "destroyElapsedMs=${ledger.destroyedAt} stopped=true prefsRestored=true helperRestored=true " +
        "helperCaptureConfirmed=${ledger.captureConfirmed} requiresPcmAndTimingLogs=true")
}

private fun observeBootQaFailure(app: Context, notices: NotificationManager, manager: ActivityManager, bootAt: Long): String {
    val channel = ReverbNotificationChannel.AUTO_START.id
    val tryingTitle = app.getString(R.string.boot_auto_start_trying)
    val failedTitle = app.getString(R.string.boot_auto_start_failed)
    val failedText = app.getString(R.string.boot_auto_start_timeout)
    fun automatic(): StatusBarNotification? {
        val visible = notices.activeNotifications.filter { it.tag == null }
        val automaticSlots = visible.filter { it.id == 47 || it.id == 48 }
        check(automaticSlots.size <= 1) { "Trying and Failed overlapped" }
        val automatic = automaticSlots.singleOrNull() ?: return null
        check(automatic.notification.channelId == channel && visible.none { it.id == 45 })
        return automatic
    }
    fun title(item: StatusBarNotification?): String? = item?.notification?.extras
        ?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    var status: StatusBarNotification? = null
    awaitBootQa(5_000L, "No Trying notice became active on Auto-start") {
        status = automatic()
        title(status) == tryingTitle
    }
    val trying = checkNotNull(status)
    val tryingAt = SystemClock.elapsedRealtime()
    check(trying.id == 47 && trying.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
        trying.notification.timeoutAfter in 1L..60_000L && bootQaServices(manager).isEmpty())
    Log.i(BOOT_QA_TAG, "failure-trying id=${trying.id} channel=$channel elapsedMs=$tryingAt timeoutMs=${trying.notification.timeoutAfter}")

    val remaining = (bootAt + 66_000L - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    awaitBootQa(remaining, "No Failed notice arrived at the actual boot retry deadline", pollMillis = 250L) {
        status = automatic()
        title(status) == failedTitle
    }
    val failed = checkNotNull(status)
    val terminalAt = SystemClock.elapsedRealtime()
    val notification = failed.notification
    val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    val autoCancel = notification.flags and Notification.FLAG_AUTO_CANCEL != 0
    val timeout = notification.timeoutAfter
    val distinctKeys = trying.key != failed.key
    val textMatches = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == failedText
    val contentIntent = notification.contentIntent
    val creator = contentIntent?.creatorPackage
    Log.i(BOOT_QA_TAG, "failure-terminal id=${failed.id} channel=$channel elapsedMs=$terminalAt " +
        "afterMs=${terminalAt - bootAt} flags=0x${notification.flags.toString(16)} ongoing=$ongoing " +
        "autoCancel=$autoCancel timeoutMs=$timeout key=${failed.key} distinctKeys=$distinctKeys " +
        "textMatches=$textMatches creator=$creator sdk=${Build.VERSION.SDK_INT}")
    check(failed.id == 48 && distinctKeys) { "Failed notification reused the Trying identity" }
    check(terminalAt - bootAt >= 60_000L) { "Failed notification appeared before the retry deadline" }
    check(textMatches) { "Failed notification explanation did not match the timeout" }
    check(!ongoing) { "Failed notification retained the ongoing flag" }
    check(autoCancel) { "Failed notification lacked the auto-cancel flag" }
    check(creator == app.packageName) { "Failed notification did not carry a Reverb-owned review action" }
    // Android can replace an app-requested zero timeout with its notification default.
    val defaultTimeout = verifyBootQaDefaultTimeout(app, notices, channel, checkNotNull(contentIntent), timeout)
    val tryingExpiry = tryingAt + trying.notification.timeoutAfter
    val timeLeft = (bootAt + 66_000L - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    awaitBootQa(timeLeft, "Failed was not observed past the old Trying timeout") {
        val current = automatic()
        check(current?.key == failed.key && title(current) == failedTitle) { "Failed disappeared at the old Trying timeout" }
        SystemClock.elapsedRealtime() > tryingExpiry
    }
    return "tryingStatusId=${trying.id} statusId=${failed.id} channel=$channel distinctKeys=true " +
        "survivedTryingTimeout=true tryingTimeoutMs=${trying.notification.timeoutAfter} " +
        "terminalTimeoutMs=$timeout platformDefaultTimeoutMs=$defaultTimeout terminalAfterMs=${terminalAt - bootAt}"
}

private fun verifyBootQaDefaultTimeout(app: Context, notices: NotificationManager, channel: String,
    contentIntent: PendingIntent, terminalTimeout: Long): Long {
    val tag = "boot-qa-default-ttl"
    val id = 47
    fun reference() = notices.activeNotifications.singleOrNull { it.tag == tag && it.id == id }
    check(reference() == null) { "A previous QA notification timeout reference is active" }
    val title = "QA notification timeout reference"
    val request = Notification.Builder(app, channel)
        .setSmallIcon(R.drawable.ic_notification_recording)
        .setContentTitle(title)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .build()
    check(request.timeoutAfter == 0L && request.contentIntent?.creatorPackage == app.packageName)
    var primary: Throwable? = null
    try {
        notices.notify(tag, id, request)
        var observed: StatusBarNotification? = null
        awaitBootQa(2_000L, "Android did not publish the QA timeout reference") {
            observed = reference()
            observed != null
        }
        val control = checkNotNull(observed)
        check(control.packageName == app.packageName && control.notification.channelId == channel &&
            control.notification.contentIntent?.creatorPackage == app.packageName &&
            control.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title)
        val defaultTimeout = control.notification.timeoutAfter
        Log.i(BOOT_QA_TAG, "failure-platform-timeout terminalMs=$terminalTimeout platformDefaultMs=$defaultTimeout")
        check(terminalTimeout == defaultTimeout) {
            "Failed timeout ${terminalTimeout}ms differs from Android's default ${defaultTimeout}ms"
        }
        return defaultTimeout
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try {
            notices.cancel(tag, id)
            awaitBootQa(1_000L, "The QA timeout reference did not retire") { reference() == null }
        } catch (cleanupError: Throwable) {
            val original = primary ?: throw cleanupError
            if (original !== cleanupError) original.addSuppressed(cleanupError)
        }
    }
}

private class BootQaHelperLedger : Application.ActivityLifecycleCallbacks {
    private val admissionField = BootResumeActivity::class.java.getDeclaredField("admission").apply { isAccessible = true }
    val destroyed = CountDownLatch(1)
    var live: BootResumeActivity? = null
        private set
    var created = 0
        private set
    var createdToken = ""
        private set
    var finishingPauseAt = 0L
        private set
    var captureConfirmed = false
        private set
    var destroyedAt = 0L
        private set

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is BootResumeActivity) return
        live = activity
        created++
        createdToken = activity.intent.getStringExtra(BOOT_QA_LAUNCH_TOKEN).orEmpty()
        Log.i(BOOT_QA_TAG, "helper-created elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity == live) Log.i(BOOT_QA_TAG, "helper-resumed elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity != live || !activity.isFinishing || finishingPauseAt != 0L) return
        finishingPauseAt = SystemClock.elapsedRealtime()
        captureConfirmed = (admissionField.get(activity) as? BootResumeActivityAdmission)?.captureConfirmed == true
        Log.i(BOOT_QA_TAG, "helper-finishing-pause elapsedMs=$finishingPauseAt captureConfirmed=$captureConfirmed")
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity != live) return
        live = null
        destroyedAt = SystemClock.elapsedRealtime()
        Log.i(BOOT_QA_TAG, "helper-destroyed elapsedMs=$destroyedAt")
        destroyed.countDown()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

@Suppress("DEPRECATION")
private fun bootQaServices(manager: ActivityManager): List<ActivityManager.RunningServiceInfo> =
    manager.getRunningServices(Int.MAX_VALUE).filter { it.service.packageName == BOOT_QA_PACKAGE }

private fun awaitBootQa(timeoutMillis: Long, failure: String, pollMillis: Long = 20L, ready: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (!ready()) {
        check(SystemClock.elapsedRealtime() < deadline) { failure }
        Thread.sleep(pollMillis)
    }
}

private fun stopBootQaRecorder(app: Context, serviceIntent: Intent): Boolean {
    val connected = CountDownLatch(1)
    val recorder = AtomicReference<ReverbService?>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (name == serviceIntent.component) recorder.set((binder as? ReverbService.BackgroundRecorderBinder)?.service)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    val bound = app.bindService(serviceIntent, connection, 0) // Never create a new recorder for cleanup.
    try {
        if (!bound || !connected.await(3, TimeUnit.SECONDS)) return false
        val service = recorder.get() ?: return false
        return service.disableListening().accepted
    } finally {
        try { app.unbindService(connection) } catch (error: IllegalArgumentException) { if (bound) throw error }
    }
}
