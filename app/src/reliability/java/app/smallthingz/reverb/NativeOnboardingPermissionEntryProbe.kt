package app.smallthingz.reverb

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import app.smallthingz.reverb.nativeui.OnboardingPermission
import java.io.File
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Real launch/result/lifecycle observation. Only the host operator answers Android permission dialogs. */
internal fun verifyNativeOnboardingPermissionEntry(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(Build.VERSION.SDK_INT >= 29)
    val app = context.applicationContext
    check(app.javaClass == Application::class.java)
    app as Application
    @Suppress("DEPRECATION")
    val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
    check(info.enabled && !info.exported && info.applicationInfo.uid == android.os.Process.myUid())
    check(isOnboardingPending(context)) { "Requires pending setup; never reset existing onboarding" }
    val permissionNames = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (requiresLegacyPublicStoragePermission()) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun grants() = permissionNames.associateWith(context::checkSelfPermission)
    val initialGrants = grants()
    val initialBatteryExemption = isIgnoringBatteryOptimizations(context)
    val initialAllFilesAccess = hasAllFilesAccess()
    // Persisted enum ordinals are not request order: ALL_FILES was appended for compatibility.
    val expectedOrder = listOf(OnboardingPermission.MICROPHONE, OnboardingPermission.STORAGE,
        OnboardingPermission.ALL_FILES, OnboardingPermission.RECOVERY,
        OnboardingPermission.NOTIFICATIONS, OnboardingPermission.BATTERY).filter { permission -> when (permission) {
        OnboardingPermission.MICROPHONE -> initialGrants[Manifest.permission.RECORD_AUDIO] != PackageManager.PERMISSION_GRANTED
        OnboardingPermission.STORAGE -> requiresLegacyPublicStoragePermission() &&
            initialGrants[Manifest.permission.WRITE_EXTERNAL_STORAGE] != PackageManager.PERMISSION_GRANTED
        OnboardingPermission.ALL_FILES -> supportsAllFilesAccess() && !initialAllFilesAccess
        OnboardingPermission.RECOVERY -> !initialAllFilesAccess && initialGrants[
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ] != PackageManager.PERMISSION_GRANTED
        OnboardingPermission.BATTERY -> !initialBatteryExemption
        OnboardingPermission.NOTIFICATIONS -> Build.VERSION.SDK_INT >= 33 &&
            initialGrants[Manifest.permission.POST_NOTIFICATIONS] != PackageManager.PERMISSION_GRANTED
    } }
    val expected = expectedOrder.fold(0) { mask, permission -> mask or (1 shl permission.ordinal) }
    check(initialGrants.values.all { it == PackageManager.PERMISSION_DENIED }) {
        "Permission-entry denial case requires naturally ungranted companion permissions; do not revoke them"
    }
    fun noService() {
        @Suppress("DEPRECATION")
        val own = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .filter { it.service.packageName == context.packageName }
        check(own.isEmpty()) { "Existing companion service work is outside this observer" }
    }
    noService()
    val prefs = getRecorderPreferences(context)
    val before = prefs.all.toMap()
    val directory = File(context.filesDir, "native-onboarding-permission-entry-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val observations = JSONArray()
    val overallDeadline = SystemClock.uptimeMillis() + 480_000
    val result = JSONObject().put("passed", false).put("observations", observations)
        .put("package", context.packageName).put("uid", android.os.Process.myUid())
        .put("pid", android.os.Process.myPid()).put("expected_attempt_mask", expected)
        .put("initial_grants", JSONObject(initialGrants))
        .put("initial_battery_exemption", initialBatteryExemption)
        .put("initial_all_files_access", initialAllFilesAccess)
        .put("expected_request_order", JSONArray(expectedOrder.map { it.name }))
        .put("overall_deadline_uptime_ms", overallDeadline)
        .put("scope", "Actual MainActivity launch, automatic claims, manually declined runtime dialogs and external Settings reviews, recreation and microphone retry")
        .put("system_dialog_visual_proof", "Retained externally by root; request bookkeeping alone is not dialog proof")
        .put("unqualified", JSONArray(listOf("process death", "TalkBack", "microphone capture", "Finish", "all-files grant/rename/delete")))
    fun publish() = writeQaEvidenceAtomically(File(directory, "result.json")) { out ->
        out.write(result.toString(2).toByteArray(Charsets.UTF_8))
    }
    fun stage(name: String) {
        result.put("stage", name)
        publish()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PERMISSION_ENTRY_STAGE $name evidence=${directory.name}/result.json\n")
        })
    }
    val ownerField = MainActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }
    val live = LinkedHashSet<MainActivity>()
    val lifecycle = ArrayList<String>()
    val saved = IdentityHashMap<Activity, Bundle>()
    val restored = IdentityHashMap<Activity, Bundle?>()
    fun event(activity: Activity, name: String) {
        if (activity is MainActivity) {
            check(lifecycle.size < 256) { "Unexpected lifecycle churn" }
            lifecycle += "${SystemClock.uptimeMillis()}:$name:${System.identityHashCode(activity)}"
        }
    }
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is MainActivity) { live += activity; restored[activity] = state?.let(::Bundle) }
            event(activity, "created")
        }
        override fun onActivityStarted(activity: Activity) = event(activity, "started")
        override fun onActivityResumed(activity: Activity) = event(activity, "resumed")
        override fun onActivityPaused(activity: Activity) = event(activity, "paused")
        override fun onActivityStopped(activity: Activity) = event(activity, "stopped")
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityPostSaveInstanceState(activity: Activity, state: Bundle) {
            if (activity is MainActivity) saved[activity] = Bundle(state)
            event(activity, "saved")
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is MainActivity) live -= activity
            event(activity, "destroyed")
        }
    }
    val attemptsKey = "native_onboarding_permission_attempts"
    val pendingKey = "native_onboarding_permission_pending"
    fun snapshot(): JSONObject = nativeParityOnMain(instrumentation) {
        val activity = live.singleOrNull()
        val state = Bundle()
        val owner = activity?.let { ownerField.get(it) as NativeOnboardingScreenOwner }
        owner?.saveInstanceState(state)
        JSONObject().put("activity", activity?.let(System::identityHashCode) ?: 0)
            .put("resumed", activity?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
            .put("focused", activity?.hasWindowFocus() == true)
            .put("showing_onboarding", owner?.snapshot?.showingOnboarding == true)
            .put("attempted", state.getInt(attemptsKey))
            .put("pending", state.getString(pendingKey) ?: "")
            .put("grants", JSONObject(grants()))
            .put("battery_exemption", isIgnoringBatteryOptimizations(context))
            .put("all_files_access", hasAllFilesAccess())
            .put("lifecycle", JSONArray(lifecycle.toList()))
    }
    var previous = ""
    var previousMask = 0
    var automaticPhase = true
    fun observe(): JSONObject {
        val current = snapshot()
        val mask = current.getInt("attempted")
        if (current.getInt("activity") != 0) {
            check(mask and expected.inv() == 0 && mask and previousMask == previousMask)
            if (automaticPhase) {
                val claimed = expectedOrder.take(Integer.bitCount(mask))
                check(mask == claimed.fold(0) { bits, permission -> bits or (1 shl permission.ordinal) }) {
                    "Automatic permission order changed"
                }
                val pending = current.getString("pending")
                if (pending.isNotEmpty()) check(OnboardingPermission.valueOf(pending) == claimed.lastOrNull())
                check(current.getBoolean("all_files_access") == initialAllFilesAccess) {
                    "Denial observer requires returning from all-files Settings without changing access"
                }
            }
            previousMask = mask
        }
        val encoded = current.toString()
        if (encoded != previous) {
            check(observations.length() < 512) { "Unexpected observer churn" }
            previous = encoded
            current.put("uptime_ms", SystemClock.uptimeMillis())
            observations.put(current)
            publish()
        }
        return current
    }
    fun await(timeout: Long, accept: (JSONObject) -> Boolean): JSONObject {
        val deadline = minOf(overallDeadline, SystemClock.uptimeMillis() + timeout)
        do {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out at ${result.optString("stage")}; observations retained" }
            val current = observe()
            if (accept(current)) return current
            SystemClock.sleep(100)
        } while (true)
    }
    var failure: Throwable? = null
    app.registerActivityLifecycleCallbacks(callbacks)
    try {
        stage("launch-deny-runtime-requests-and-return-from-all-files-settings-without-granting")
        val launched = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().apply {
                launchDisplayId = Display.DEFAULT_DISPLAY
            }.toBundle()) as MainActivity
        check(nativeParityOnMain(instrumentation) { restored.containsKey(launched) && restored[launched] == null }) {
            "Existing Activity/task restoration is not a fresh first-entry case"
        }
        val denied = await(480_000) { it.getInt("attempted") == expected && it.getString("pending").isEmpty() && it.getBoolean("focused") }
        check(grants() == initialGrants) { "First case requires observed denial, not an invented denial callback" }
        check(isIgnoringBatteryOptimizations(context) == initialBatteryExemption) { "Battery exemption changed during denial case" }
        check(denied.getBoolean("showing_onboarding"))
        automaticPhase = false
        noService()
        stage("recreate-after-denials")
        nativeParityOnMain(instrumentation) { check(launched.hasWindowFocus()); launched.recreate() }
        val after = await(20_000) { it.getInt("activity") != System.identityHashCode(launched) &&
            it.getInt("activity") != 0 && it.getBoolean("focused") && it.getBoolean("resumed") }
        nativeParityOnMain(instrumentation) {
            val replacement = live.single()
            val sent = checkNotNull(saved[launched])
            val received = checkNotNull(restored[replacement])
            check(sent.getInt(attemptsKey) == expected && received.getInt(attemptsKey) == expected)
            check(sent.getString(pendingKey) == null && received.getString(pendingKey) == null)
        }
        check(after.getInt("attempted") == expected && after.getString("pending").isEmpty())
        check(after.getBoolean("battery_exemption") == initialBatteryExemption)
        check(after.getBoolean("all_files_access") == initialAllFilesAccess)
        val quietUntil = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < quietUntil) {
            check(SystemClock.uptimeMillis() < overallDeadline) { "Permission-entry session expired" }
            val current = observe()
            check(current.getInt("attempted") == expected && current.getString("pending").isEmpty())
            check(grants() == initialGrants)
            check(current.getBoolean("battery_exemption") == initialBatteryExemption)
            check(current.getBoolean("all_files_access") == initialAllFilesAccess)
            SystemClock.sleep(100)
        }
        stage("manually-tap-microphone-row-and-grant")
        await(180_000) { it.getString("pending") == OnboardingPermission.MICROPHONE.name }
        await(180_000) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            it.getString("pending").isEmpty() && it.getBoolean("focused") }
        val finalGrants = grants()
        check(finalGrants == initialGrants + (Manifest.permission.RECORD_AUDIO to PackageManager.PERMISSION_GRANTED))
        check(isIgnoringBatteryOptimizations(context) == initialBatteryExemption) { "Microphone-only retry changed battery exemption" }
        check(hasAllFilesAccess() == initialAllFilesAccess) { "Microphone-only retry changed all-files access" }
        check(prefs.all == before) { "Permission observation unexpectedly changed durable recorder preferences" }
        noService()
        result.put("final_grants", JSONObject(finalGrants))
            .put("preferences_preserved", true)
        stage("complete")
    } catch (error: Throwable) {
        failure = error
        result.put("error", error.toString())
        throw error
    } finally {
        try {
            result.put("lifecycle", nativeParityOnMain(instrumentation) { JSONArray(lifecycle.toList()) })
            result.put("final_grants", JSONObject(grants())).put("preferences_preserved", prefs.all == before)
                .put("final_battery_exemption", isIgnoringBatteryOptimizations(context))
                .put("final_all_files_access", hasAllFilesAccess())
            nativeParityOnMain(instrumentation) {
                for (activity in live.toList()) {
                    val state = Bundle()
                    (ownerField.get(activity) as NativeOnboardingScreenOwner).saveInstanceState(state)
                    if (activity.hasWindowFocus() && state.getString(pendingKey) == null) activity.finish()
                }
            }
            result.put("passed", failure == null)
            publish()
        } catch (cleanup: Throwable) {
            result.put("passed", false)
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        } finally { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }
    report.appendLine("PASS native_onboarding_permission_entry evidence=${directory.name}/result.json")
}
