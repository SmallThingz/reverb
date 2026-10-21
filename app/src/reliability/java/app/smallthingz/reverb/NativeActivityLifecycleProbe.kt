package app.smallthingz.reverb

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import app.smallthingz.reverb.nativeui.ReverbPalette
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Actual framework lifecycle; pending onboarding only. No replacement Activity or production seam. */
internal fun verifyNativeActivityLifecycle(instrumentation: Instrumentation, report: StringBuilder) {
    check(Build.VERSION.SDK_INT >= 29) { "Final Activity saved-state observation requires the public post-save callback" }
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    val application = context.applicationContext
    check(application.javaClass == Application::class.java)
    val app = application as Application
    val classes = listOf(ReleasedMainActivityOracle::class.java, MainActivity::class.java)
    for (type in classes) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(ComponentName(context, type), 0)
        check(info.enabled && !info.exported) { "Explicit isolated lifecycle build flag is required" }
        check(info.applicationInfo.uid == android.os.Process.myUid())
    }
    val prefs = getRecorderPreferences(context)
    val before = prefs.all.toMap()
    val themeBefore = durablePreferenceValueSnapshot(before, PrefKey.THEME_MODE)
    val onboardingBefore = durablePreferenceValueSnapshot(before, PrefKey.ONBOARDING_SHOWN)
    check(durablePreferenceSnapshotIsExactlyRestorable(themeBefore))
    val permissionNames = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE)
    val grantsBefore = permissionNames.associateWith(context::checkSelfPermission)
    val directory = File(context.filesDir, "native-activity-lifecycle-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val runs = JSONArray()
    val comparisons = JSONArray()
    val result = JSONObject().put("passed", false).put("runs", runs).put("comparisons", comparisons)
        .put("scope", "Actual released/native Activity creation, onboarding draft, framework recreation, ordinary Back")
        .put("requested_themes", JSONArray(listOf("LIGHT", "DARK")))
        .put("light_appearance_verified", false)
        .put("unqualified", JSONArray(listOf("OS predictive Back", "process death", "live Settings theme preview",
            "permission results", "Finish/runtime handoff", "recording", "TalkBack delivery",
            "launcher-task root background behavior",
            "Light appearance under unchanged device inversion/force-dark state")))
    val ledger = ActivityLifecycleLedger(classes.mapTo(HashSet()) { it.name }, result)
    writeLifecycleEvidence(directory, result) // A failed later publication must never leave an old PASS receipt.
    // Process death bypasses finally. Publish the exact two-key QA baseline before any theme edit.
    // ONBOARDING_SHOWN is observation-only; this fixture never resets setup or invokes Finish.
    val originalPreferences = JSONObject().put("schema", 1)
        .put("package", context.packageName).put("uid", android.os.Process.myUid())
        .put("pid", android.os.Process.myPid()).put("preference_file", context.packageName)
        .put("observed_uptime_ms", SystemClock.uptimeMillis())
        .put("keys", JSONObject()
            .put(PrefKey.THEME_MODE.name, lifecyclePreferenceEvidence(themeBefore))
            .put(PrefKey.ONBOARDING_SHOWN.name, lifecyclePreferenceEvidence(onboardingBefore)))
        .put("fixture_write_keys", JSONArray(listOf(PrefKey.THEME_MODE.name)))
        .put("automatic_recovery", false)
    val originalName = "original-qa-preferences.json"
    val originalSha = writeOriginalLifecyclePreferences(directory, originalName, originalPreferences)
    result.put("original_qa_preferences", JSONObject().put("file", originalName).put("sha256", originalSha))
        .put("preference_restoration_verified", false)
    writeLifecycleEvidence(directory, result)
    // This initial false receipt remains discoverable even if the launched Activity kills the process.
    instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "PARITY_EVIDENCE ${directory.name}/result.json\n")
    })
    var failure: Throwable? = null
    var themeWritten = false
    var comparisonSucceeded = false
    var pixelMismatch = false
    val images = LinkedHashMap<String, File>()
    fun conditions() {
        check(isOnboardingPending(context)) { "QA onboarding must already be pending; do not reset it" }
        nativeParityOnMain(instrumentation) {
            check(processOnboardingFinish.state.value == OnboardingFinishState.IDLE)
            check(processCommittedTheme.state.value == null) { "Existing committed theme must not be overwritten" }
        }
        @Suppress("DEPRECATION")
        val ownServices = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .filter { it.service.packageName == context.packageName }
        check(ownServices.isEmpty()) { "Existing QA service work prevents this UI-only path" }
        check(permissionNames.associateWith(context::checkSelfPermission) == grantsBefore)
    }
    app.registerActivityLifecycleCallbacks(ledger)
    try {
        conditions()
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) for (type in classes) {
            check(nativeParityOnMain(instrumentation) { ledger.live.isEmpty() })
            conditions()
            val expectedPrefs = before.toMutableMap().apply { put(PrefKey.THEME_MODE.name, mode.storageCode.toInt()) }
            themeWritten = true // commit may fail after updating the in-memory map; finally restores the exact prior value.
            check(prefs.edit().putInt(PrefKey.THEME_MODE.name, mode.storageCode.toInt()).commit())
            check(prefs.all == expectedPrefs)
            val reference = type == ReleasedMainActivityOracle::class.java
            val name = "${mode.name.lowercase()}-${if (reference) "released" else "native"}"
            val run = JSONObject().put("name", name).put("steps", JSONArray())
            runs.put(run)
            val launched = instrumentation.startActivitySync(
                Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle(),
            ) as ComponentActivity
            var activity = launched
            awaitLifecycle(instrumentation, ledger, null) { activity.hasWindowFocus() && activity.window.decorView.isShown }
            check(nativeParityOnMain(instrumentation) { ledger.requireObservations(); ledger.created.getValue(activity) == null }) {
                "Initial Activity unexpectedly restored an existing task; do not clear it"
            }
            fun capture(step: String) {
                conditions()
                val bitmap = settledLifecycleCapture(instrumentation, activity)
                try {
                    val file = File(directory, "$name-$step.png")
                    saveNativeParityBitmap(directory, file.name, bitmap)
                    images["$name-$step"] = file
                    run.getJSONArray("steps").put(nativeParityOnMain(instrumentation) {
                        lifecycleSceneEvidence(activity, reference, mode).put("step", step)
                    })
                } finally { bitmap.recycle() }
                check(prefs.all == expectedPrefs) { "Onboarding draft changed durable QA preferences" }
            }
            awaitLifecyclePage(instrumentation, activity, reference, 0)
            capture("initial")
            lifecycleClick(instrumentation, activity, reference, R.string.onboarding_continue)
            awaitLifecyclePage(instrumentation, activity, reference, 1)
            settledLifecycleCapture(instrumentation, activity).recycle()
            lifecycleClick(instrumentation, activity, reference, R.string.onboarding_continue)
            awaitLifecyclePage(instrumentation, activity, reference, 2)
            settledLifecycleCapture(instrumentation, activity).recycle()
            val initialChecked = lifecycleChecked(instrumentation, activity, reference, R.string.silence_title)
            lifecycleClick(instrumentation, activity, reference, R.string.silence_title)
            awaitLifecycleChecked(instrumentation, activity, reference, R.string.silence_title, !initialChecked)
            capture("draft")
            val old = activity
            requireLifecycleWindow(instrumentation, old)
            nativeParityOnMain(instrumentation) { old.recreate() }
            awaitLifecycle(instrumentation, ledger, old) {
                ledger.live.singleOrNull()?.let {
                    it !== old && it.javaClass == type && it.hasWindowFocus() && it.window.decorView.isShown
                } == true && old.isDestroyed
            }
            activity = nativeParityOnMain(instrumentation) { ledger.live.single() as ComponentActivity }
            val saved = nativeParityOnMain(instrumentation) { ledger.requireObservations(); checkNotNull(ledger.saved[old]) }
            val received = nativeParityOnMain(instrumentation) { checkNotNull(ledger.created[activity]) }
            val requestedKeys = listOf("microphone_permission_requested", "storage_permission_requested",
                "recovery_permission_requested", "notification_permission_requested")
            for (key in requestedKeys) {
                check(saved.containsKey(key) && received.containsKey(key))
                check(!saved.getBoolean(key) && !received.getBoolean(key)) { "A permission request was accepted" }
            }
            run.put("saved_bundle", nativeParityOnMain(instrumentation) { ledger.savedEvidence.getValue(old) })
                .put("created_bundle", nativeParityOnMain(instrumentation) { ledger.createdEvidence.getValue(activity) })
                .put("save_callback", "onActivityPostSaveInstanceState (final registry payload)")
            awaitLifecyclePage(instrumentation, activity, reference, 2)
            awaitLifecycleChecked(instrumentation, activity, reference, R.string.silence_title, !initialChecked)
            capture("restored")
            val beforeRecreate = BitmapFactory.decodeFile(images.getValue("$name-draft").path)
            val afterRecreate = BitmapFactory.decodeFile(images.getValue("$name-restored").path)
            val restoredPixels = try { compareNativeParityPixels(checkNotNull(beforeRecreate), checkNotNull(afterRecreate)) }
                finally { beforeRecreate?.recycle(); afterRecreate?.recycle() }
            run.put("recreation_pixels", restoredPixels)
            if (restoredPixels.getLong("different_pixels") != 0L) pixelMismatch = true
            for (page in listOf(1, 0)) {
                requireLifecycleWindow(instrumentation, activity)
                nativeParityOnMain(instrumentation) { activity.onBackPressedDispatcher.onBackPressed() }
                awaitLifecyclePage(instrumentation, activity, reference, page)
                // Complete the source's Back/pager motion before the next dispatch.
                settledLifecycleCapture(instrumentation, activity).recycle()
            }
            requireLifecycleWindow(instrumentation, activity)
            nativeParityOnMain(instrumentation) { activity.onBackPressedDispatcher.onBackPressed() }
            awaitLifecycle(instrumentation, ledger, activity) { activity.isDestroyed && ledger.live.isEmpty() }
            conditions()
            check(prefs.all == expectedPrefs)
            run.put("ordinary_back_exited", true)
            nativeParityOnMain(instrumentation) { ledger.releaseCompleted() }
        }
        for (mode in listOf("light", "dark")) for (step in listOf("initial", "draft", "restored")) {
            val a = BitmapFactory.decodeFile(images.getValue("$mode-released-$step").path)
            val b = BitmapFactory.decodeFile(images.getValue("$mode-native-$step").path)
            val comparison = try { compareNativeParityPixels(checkNotNull(a), checkNotNull(b)) }
                finally { a?.recycle(); b?.recycle() }
            comparisons.put(comparison.put("theme", mode).put("step", step))
            if (comparison.getLong("different_pixels") != 0L) pixelMismatch = true
        }
        check(!pixelMismatch) { "Actual Activity/recreation raster differs; preserve exact captures without tolerance" }
        comparisonSucceeded = true
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        cleanup { nativeParityOnMain(instrumentation) {
            ledger.live.toList().forEach { if (!it.isFinishing) it.finish() }
        } }
        cleanup { awaitLifecycle(instrumentation, ledger, null) { ledger.live.isEmpty() } }
        cleanup {
            check(nativeParityOnMain(instrumentation) { ledger.live.isEmpty() }) {
                "Do not restore theme while an owned Activity still exists"
            }
            if (themeWritten) check(prefs.edit().restoreDurablePreferenceValue(PrefKey.THEME_MODE, themeBefore).commit())
            check(prefs.all == before) { "QA preference restoration did not reproduce the original typed map" }
            result.put("preference_restoration_verified", true)
        }
        cleanup { conditions() }
        cleanup { app.unregisterActivityLifecycleCallbacks(ledger) }
        result.put("passed", comparisonSucceeded && failure == null)
        failure?.let { result.put("failure", it.stackTraceToString()) }
        var evidenceWritten = false
        try {
            writeLifecycleEvidence(directory, result)
            evidenceWritten = true
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            result.put("passed", false).put("failure", checkNotNull(failure).stackTraceToString())
            // One bounded failure publication. Atomic rename leaves the initial false receipt intact if this also fails.
            try { writeLifecycleEvidence(directory, result); evidenceWritten = true }
            catch (writeFailure: Throwable) { if (writeFailure !== failure) failure!!.addSuppressed(writeFailure) }
        }
        report.appendLine(if (evidenceWritten) "PARITY_EVIDENCE ${directory.name}/result.json"
            else "PARITY_EVIDENCE_WRITE_FAILED ${directory.name}/result.json")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_activity_lifecycle: 4 actual Activity routes; $directory")
}

private class ActivityLifecycleLedger(
    private val admitted: Set<String>,
    result: JSONObject,
) : Application.ActivityLifecycleCallbacks {
    val live = ArrayList<Activity>()
    val created = IdentityHashMap<Activity, Bundle?>()
    val saved = IdentityHashMap<Activity, Bundle>()
    val savedEvidence = IdentityHashMap<Activity, JSONObject>()
    val createdEvidence = IdentityHashMap<Activity, JSONObject>()
    private var observationFailure: Throwable? = null
    private fun observeBundle(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            val primary = observationFailure
            if (primary == null) observationFailure = error else if (primary !== error) primary.addSuppressed(error)
        }
    }
    fun requireObservations() { observationFailure?.let { throw it } }
    fun releaseCompleted() {
        check(live.isEmpty())
        created.clear(); saved.clear(); createdEvidence.clear(); savedEvidence.clear()
    }
    private val trace = JSONArray().also { result.put("lifecycle", it) }
    private fun record(activity: Activity, event: String) {
        if (activity.javaClass.name !in admitted) return
        trace.put(JSONObject().put("event", event).put("class", activity.javaClass.name)
            .put("identity", System.identityHashCode(activity)).put("uptime_ms", SystemClock.uptimeMillis()))
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity.javaClass.name !in admitted) return
        live += activity
        observeBundle {
            created[activity] = state?.let(::Bundle)
            createdEvidence[activity] = state?.let(::lifecycleBundleEvidence) ?: JSONObject()
        }
        record(activity, "created")
    }
    override fun onActivityStarted(activity: Activity) = record(activity, "started")
    override fun onActivityResumed(activity: Activity) = record(activity, "resumed")
    override fun onActivityPaused(activity: Activity) = record(activity, "paused")
    override fun onActivityStopped(activity: Activity) = record(activity, "stopped")
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {
        // ComponentActivity calls super before its registry save, so this callback is explicitly partial.
        record(activity, "save_callback_partial")
    }
    override fun onActivityPostSaveInstanceState(activity: Activity, state: Bundle) {
        if (activity.javaClass.name !in admitted) return
        observeBundle {
            saved[activity] = Bundle(state)
            // Snapshot evidence now, before the restored owner can consume nested registry values.
            savedEvidence[activity] = lifecycleBundleEvidence(state)
        }
        record(activity, "saved_final")
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity.javaClass.name !in admitted) return
        live.remove(activity)
        record(activity, "destroyed")
    }
}

private fun awaitLifecycle(
    instrumentation: Instrumentation, ledger: ActivityLifecycleLedger, transitioning: Activity?,
    ready: () -> Boolean,
) {
    val deadline = SystemClock.uptimeMillis() + 8_000
    while (SystemClock.uptimeMillis() < deadline) {
        if (nativeParityOnMain(instrumentation, ready)) return
        Thread.sleep(16)
    }
    val details = nativeParityOnMain(instrumentation) {
        "oldDestroyed=${transitioning?.isDestroyed}, live=" + ledger.live.joinToString {
            "${it.javaClass.simpleName}(focused=${it.hasWindowFocus()}, shown=${it.window.decorView.isShown}, finishing=${it.isFinishing})"
        }
    }
    error("Own lifecycle transition did not complete; do not reclaim focus: $details")
}

private fun requireLifecycleWindow(instrumentation: Instrumentation, activity: ComponentActivity) =
    nativeParityOnMain(instrumentation) {
        val root = activity.findViewById<View>(android.R.id.content)
        check(activity.packageName == "app.smallthingz.reverb.nativeparity.reliability")
        check(activity.application.javaClass == Application::class.java)
        check(!activity.isFinishing && activity.hasWindowFocus() && root.isShown) {
            "Actual QA Activity is not the focused visible owner; stop without reclaiming focus " +
                "(finishing=${activity.isFinishing}, focus=${activity.hasWindowFocus()}, shown=${root.isShown})"
        }
        check(root.display?.displayId == Display.DEFAULT_DISPLAY)
        check(processOnboardingFinish.state.value == OnboardingFinishState.IDLE && isOnboardingPending(activity))
    }

private fun lifecycleViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(lifecycleViews(root.getChildAt(i)))
}

private fun lifecycleSourceNodes(activity: ComponentActivity): List<SemanticsNode> {
    val root = activity.findViewById<View>(android.R.id.content)
    val compose = lifecycleViews(root).filterIsInstance<ViewRootForTest>().toList()
    if (compose.size != 1) return emptyList()
    return compose.single().semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter { node ->
        val bounds = node.boundsInRoot
        bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < root.width && bounds.top < root.height &&
            generateSequence(node) { it.parent }.none { it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null }
    }
}
private fun SemanticsNode.lifecycleNames() =
    config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

private fun lifecycleNativeActions(activity: ComponentActivity, label: String): List<View> =
    lifecycleViews(activity.findViewById(android.R.id.content)).filter { view ->
        val info = view.createAccessibilityNodeInfo() ?: return@filter false
        try {
            info.isVisibleToUser && info.isEnabled && info.isClickable &&
                lifecycleViews(view).any { child ->
                    val childInfo = child.createAccessibilityNodeInfo() ?: return@any false
                    try { childInfo.isVisibleToUser &&
                        (childInfo.text?.toString() == label || childInfo.contentDescription?.toString() == label) }
                    finally { @Suppress("DEPRECATION") childInfo.recycle() }
                }
        } finally { @Suppress("DEPRECATION") info.recycle() }
    }.toList()

private fun lifecycleClick(instrumentation: Instrumentation, activity: ComponentActivity, reference: Boolean, resource: Int) {
    check(resource == R.string.onboarding_continue || resource == R.string.silence_title) {
        "Only navigation before Finish and one draft switch are admitted"
    }
    requireLifecycleWindow(instrumentation, activity)
    nativeParityOnMain(instrumentation) {
        val label = activity.getString(resource)
        if (reference) {
            val nodes = lifecycleSourceNodes(activity).filter {
                label in it.lifecycleNames() && it.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
            check(nodes.size == 1) { "Expected unique current source action '$label'; got ${nodes.size}" }
            check(nodes.single().config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
        } else {
            val nodes = lifecycleNativeActions(activity, label)
            check(nodes.size == 1) { "Expected unique current native action '$label'; got ${nodes.size}" }
            check(nodes.single().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
    }
}

private fun lifecycleChecked(
    instrumentation: Instrumentation, activity: ComponentActivity, reference: Boolean, resource: Int,
): Boolean {
    requireLifecycleWindow(instrumentation, activity)
    return nativeParityOnMain(instrumentation) {
        val label = activity.getString(resource)
        if (reference) {
            val node = lifecycleSourceNodes(activity).single { label in it.lifecycleNames() &&
                it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            checkNotNull(node.config.getOrNull(SemanticsProperties.ToggleableState)) == ToggleableState.On
        } else {
            val view = lifecycleNativeActions(activity, label).single()
            val info = checkNotNull(view.createAccessibilityNodeInfo())
            try { check(info.isCheckable); info.isChecked }
            finally { @Suppress("DEPRECATION") info.recycle() }
        }
    }
}

private fun awaitLifecycleChecked(
    instrumentation: Instrumentation, activity: ComponentActivity, reference: Boolean, resource: Int, expected: Boolean,
) {
    val deadline = SystemClock.uptimeMillis() + 8_000
    while (SystemClock.uptimeMillis() < deadline) {
        // Read fresh source/native semantics after each own-window check; Compose publishes at a frame boundary.
        if (lifecycleChecked(instrumentation, activity, reference, resource) == expected) return
        Thread.sleep(16)
    }
    error("Draft switch semantics did not publish expected state $expected")
}

private fun awaitLifecyclePage(instrumentation: Instrumentation, activity: ComponentActivity, reference: Boolean, page: Int) {
    val title = activity.getString(when (page) {
        0 -> R.string.onboarding_permissions_title
        1 -> R.string.onboarding_title
        else -> R.string.recording_settings_title
    })
    val deadline = SystemClock.uptimeMillis() + 8_000
    while (SystemClock.uptimeMillis() < deadline) {
        requireLifecycleWindow(instrumentation, activity)
        val found = nativeParityOnMain(instrumentation) {
            if (reference) lifecycleSourceNodes(activity).count { title in it.lifecycleNames() } == 1
            else lifecycleViews(activity.findViewById(android.R.id.content)).count {
                val info = it.createAccessibilityNodeInfo() ?: return@count false
                try { info.isVisibleToUser && info.text?.toString() == title }
                finally { @Suppress("DEPRECATION") info.recycle() }
            } == 1
        }
        if (found) return
        Thread.sleep(16)
    }
    error("Actual onboarding page $page did not become visible")
}

private fun lifecycleSceneEvidence(activity: ComponentActivity, reference: Boolean, mode: AppThemeMode): JSONObject {
    val root = activity.findViewById<View>(android.R.id.content)
    val nodes = JSONArray()
    if (reference) for (node in lifecycleSourceNodes(activity)) {
        val b = node.boundsInRoot
        nodes.put(JSONObject().put("names", JSONArray(node.lifecycleNames())).put("checked",
            node.config.getOrNull(SemanticsProperties.ToggleableState)?.toString())
            .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom))))
    } else for (view in lifecycleViews(root)) {
        val info = view.createAccessibilityNodeInfo() ?: continue
        try {
            if (!info.isVisibleToUser) continue
            val b = Rect(); info.getBoundsInScreen(b)
            nodes.put(JSONObject().put("text", info.text?.toString()).put("description", info.contentDescription?.toString())
                .put("checkable", info.isCheckable).put("checked", info.isChecked)
                .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom))))
        } finally { @Suppress("DEPRECATION") info.recycle() }
    }
    val palette = ReverbPalette.resolve(activity, mode == AppThemeMode.DARK)
    return JSONObject().put("nodes", nodes).put("requested_theme", mode.name)
        .put("configured_theme", getConfiguredThemeMode(activity).name)
        .put("system_night", activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        .put("palette_surface_argb", palette.surface.toLong() and 0xffffffffL)
        .put("width", root.width).put("height", root.height)
        .put("density_dpi", activity.resources.configuration.densityDpi)
        .put("font_scale", activity.resources.configuration.fontScale.toDouble())
}

private fun captureLifecycle(instrumentation: Instrumentation, activity: ComponentActivity): Bitmap {
    requireLifecycleWindow(instrumentation, activity)
    val root = nativeParityOnMain(instrumentation) { activity.findViewById<View>(android.R.id.content) }
    awaitNativeParityDraw(instrumentation, root)
    requireLifecycleWindow(instrumentation, activity)
    val bounds = nativeParityOnMain(instrumentation) {
        check(root.isLaidOut && !root.isLayoutRequested && root.isHardwareAccelerated)
        val location = IntArray(2); root.getLocationInWindow(location)
        Rect(location[0], location[1], location[0] + root.width, location[1] + root.height)
    }
    val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
    val done = CountDownLatch(1)
    var status = Int.MIN_VALUE
    var submitted = false
    var recycleWhenDone = false
    try {
        nativeParityOnMain(instrumentation) {
            PixelCopy.request(activity.window, bounds, bitmap, {
                status = it
                done.countDown()
                if (recycleWhenDone) bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
            submitted = true
        }
        check(done.await(5, TimeUnit.SECONDS)) { "Actual Activity PixelCopy timed out" }
        check(status == PixelCopy.SUCCESS)
        requireLifecycleWindow(instrumentation, activity)
        return bitmap
    } catch (error: Throwable) {
        try { nativeParityOnMain(instrumentation) {
            if (!submitted || done.count == 0L) bitmap.recycle() else recycleWhenDone = true
        } } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
        throw error
    }
}

private fun settledLifecycleCapture(instrumentation: Instrumentation, activity: ComponentActivity): Bitmap {
    val deadline = SystemClock.uptimeMillis() + 8_000
    var previous: Bitmap? = null
    var stable = 0
    try {
        while (SystemClock.uptimeMillis() < deadline) {
            val next = captureLifecycle(instrumentation, activity)
            val equal = previous?.sameAs(next) == true
            previous?.recycle()
            previous = next
            stable = if (equal) stable + 1 else 0
            if (stable >= 2) return next.also { previous = null }
        }
        error("Actual Activity did not produce three consecutive identical settled captures")
    } finally { previous?.recycle() }
}

@Suppress("DEPRECATION")
private fun lifecycleBundleEvidence(bundle: Bundle): JSONObject {
    val result = JSONObject()
    for (key in bundle.keySet().sorted()) {
        val value = bundle.get(key)
        result.put(key, when (value) {
            null -> JSONObject.NULL
            is Bundle -> lifecycleBundleEvidence(value)
            is Boolean, is String, is Int, is Long, is Float, is Double -> value
            is IntArray -> JSONArray(value.toList())
            else -> JSONObject().put("type", value.javaClass.name)
        })
    }
    return result
}

private fun writeLifecycleEvidence(directory: File, result: JSONObject) {
    val pending = File(directory, "result.json.pending")
    pending.writeText(result.toString(2))
    check(pending.renameTo(File(directory, "result.json"))) { "Cannot publish complete lifecycle evidence" }
}

/** Exact SharedPreferences wire types; deliberately called for only the two named QA keys above. */
private fun lifecyclePreferenceEvidence(snapshot: DurablePreferenceValueSnapshot): JSONObject {
    val result = JSONObject().put("present", snapshot.present)
    if (!snapshot.present) return result.put("type", "absent")
    when (val value = snapshot.value) {
        null -> result.put("type", "null").put("value", JSONObject.NULL)
        is Boolean -> result.put("type", "Boolean").put("value", value)
        is Int -> result.put("type", "Int").put("value", value)
        // Decimal String avoids lossy downstream JSON number conversions for 64-bit values.
        is Long -> result.put("type", "Long").put("decimal", value.toString())
        // Raw bits preserve negative zero and non-finite values without invalid JSON numbers.
        is Float -> result.put("type", "Float")
            .put("raw_bits_hex", value.toRawBits().toUInt().toString(16).padStart(8, '0'))
        is String -> result.put("type", "String").put("value", value)
        is Set<*> -> {
            check(value.all { it is String }) { "Unsupported original QA preference set; no theme mutation" }
            result.put("type", "StringSet").put("value", JSONArray(value.map { it as String }.sorted()))
        }
        else -> error("Unsupported original QA preference type ${value.javaClass.name}; no theme mutation")
    }
    return result
}

/** One immutable recovery-evidence publication, not a recovery command or preference journal. */
private fun writeOriginalLifecyclePreferences(directory: File, name: String, value: JSONObject): String {
    val target = File(directory, name)
    val pending = File(directory, "$name.pending")
    check(!target.exists() && !pending.exists()) { "Original QA baseline must never be overwritten" }
    val bytes = (value.toString(2) + "\n").toByteArray(Charsets.UTF_8)
    FileOutputStream(pending).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    check(pending.renameTo(target)) { "Cannot publish original QA preferences before mutation" }
    check(target.readBytes().contentEquals(bytes)) { "Original QA preference evidence failed readback" }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
