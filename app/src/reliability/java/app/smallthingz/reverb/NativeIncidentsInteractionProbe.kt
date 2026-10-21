package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One finite source/native flow over two independent in-memory histories.
 * Actions are exact own-node semantics/accessibility actions; no global key/touch injection.
 * The fixture never selects Copy and cannot reach RecordingIncidentStore.
 */
internal fun verifyNativeIncidentsInteraction(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(Build.VERSION.SDK_INT >= 30) { "Physical-display accessibility-window proof requires API30+" }
    val directory = File(context.filesDir, "native-incidents-interaction-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val runs = JSONArray()
    val comparisons = JSONArray()
    val result = JSONObject().put("passed", false).put("runs", runs).put("comparisons", comparisons)
        .put("scope", "One memory acknowledgement/menu/delete and header-Back close interrupted by host reopen, then complete close")
        .put("theme", "Dark RTL").put("motion_scale", 1)
        .put("unqualified", JSONArray(listOf("Physical touch/long-press recognition", "OS Back/predictive Back",
            "Popup Back dispatch", "Frame-perfect animation timing", "Live incident store", "Clipboard")))
    publishIncidentInteraction(directory, result)
    var host: NativeParityActivity? = null
    var interactiveWindows: NativeParityInteractiveWindows? = null
    var board: IncidentsParityBoard? = null
    var trace: IncidentInteractionTrace? = null
    var currentRun: JSONObject? = null
    var currentReference = true
    var failure: Throwable? = null
    val pictures = LinkedHashMap<String, File>()
    var comparisonsPassed = false
    try {
        val windowScope = NativeParityInteractiveWindows.open(instrumentation, result).also { interactiveWindows = it }
        val activity = launchNativeParityActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        windowScope.recordHost(instrumentation, activity)
        val content = nativeParityOnMain(instrumentation) {
            IncidentsParityBoard(activity, dark = true, scene = IncidentParityScene.POPULATED, interactive = true)
        }.also { board = it }
        content.awaitReady(instrumentation)
        for (reference in listOf(true, false)) {
            val renderer = if (reference) "released" else "native"
            val run = JSONObject().put("renderer", renderer).put("passed", false)
            currentRun = run
            currentReference = reference
            runs.put(run)
            captureIncidentStep(instrumentation, activity, content, reference, directory,
                "$renderer-initial", pictures)
            val row = nativeParityOnMain(instrumentation) { content.interactiveState(reference).history.rows.first() }
            val label = checkNotNull(row.description)
            val key = row.key
            check(!row.incident.acknowledged)
            incidentAction(instrumentation, activity, content, reference, label, longClick = false)
            awaitIncidentCondition(instrumentation, activity, "memory acknowledgement") {
                content.interactiveState(reference).history.rows.single { it.key == key }.incident.acknowledged
            }
            captureIncidentStep(instrumentation, activity, content, reference, directory,
                "$renderer-acknowledged", pictures)
            val mainWindow = currentIncidentApplicationWindow(instrumentation, activity)
            incidentAction(instrumentation, activity, content, reference, label, longClick = true)
            val menuReadiness = JSONObject().also { run.put("menu_readiness", it) }
            val menu = awaitIncidentMenu(instrumentation, activity, mainWindow, menuReadiness)
            run.put("menu", menu)
            captureIncidentMenu(instrumentation, activity, menu.getInt("window_id"), directory,
                "$renderer-menu", pictures)
            clickIncidentMenuDelete(instrumentation, activity, menu.getInt("window_id"))
            awaitIncidentCondition(instrumentation, activity, "memory delete and own-window return",
                returningWindows = setOf(mainWindow, menu.getInt("window_id"))) {
                content.interactiveState(reference).history.rows.none { it.key == key }
            }
            captureIncidentStep(instrumentation, activity, content, reference, directory,
                "$renderer-deleted", pictures)
            val expected = listOf("ack:$key", "delete:$key")
            check(nativeParityOnMain(instrumentation) { content.interactiveCommands(reference) } == expected)

            val phase = nativeParityOnMain(instrumentation) {
                IncidentInteractionTrace(content, reference).also { it.start() }
            }.also { trace = it; run.put("phase_samples", it.samples) }
            val back = activity.getString(R.string.back)
            incidentAction(instrumentation, activity, content, reference, back, longClick = false)
            var interruptedAt: JSONObject? = null
            awaitIncidentCondition(instrumentation, activity, "strictly partial close before host retarget") {
                val progress = content.interactiveProgress(reference)
                if (progress > 0f && progress < 1f) {
                    interruptedAt = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                        .put("progress", progress.toDouble()).put("kind", "host-navigation-reopen")
                    run.put("interrupted", interruptedAt)
                    content.interactiveReopen(reference)
                    true
                } else false
            }
            awaitIncidentCondition(instrumentation, activity, "reopened terminal") {
                content.interactiveMounted(reference) && content.interactiveProgress(reference) == 1f
            }
            captureIncidentStep(instrumentation, activity, content, reference, directory,
                "$renderer-reopened", pictures)
            incidentAction(instrumentation, activity, content, reference, back, longClick = false)
            awaitIncidentCondition(instrumentation, activity, "fully closed and unmounted") {
                !content.interactiveMounted(reference) && content.interactiveProgress(reference) == 0f
            }
            val closed = captureNativeParityContent(instrumentation, activity) { content.interactiveBackdrop() }
            try {
                val path = File(directory, "$renderer-closed.png")
                saveNativeParityBitmap(directory, path.name, closed)
                pictures["$renderer-closed"] = path
            } finally { closed.recycle() }
            val phaseEvidence = nativeParityOnMain(instrumentation) { phase.stop() }
            trace = null
            check(nativeParityOnMain(instrumentation) { content.interactiveBackCount(reference) } == 2)
            run.put("commands", JSONArray(expected)).put("back_callbacks", 2)
                .put("interrupted", checkNotNull(interruptedAt)).put("phase_samples", phaseEvidence)
                .put("closed_unmounted", true).put("passed", true)
            publishIncidentInteraction(directory, result)
        }
        for (step in listOf("initial", "acknowledged", "menu", "deleted", "reopened", "closed")) {
            var a: Bitmap? = null
            var b: Bitmap? = null
            val comparison = try {
                a = checkNotNull(android.graphics.BitmapFactory.decodeFile(pictures.getValue("released-$step").path))
                b = checkNotNull(android.graphics.BitmapFactory.decodeFile(pictures.getValue("native-$step").path))
                compareNativeParityPixels(a, b)
            } finally { a?.recycle(); b?.recycle() }
            comparisons.put(comparison.put("step", step))
        }
        check((0 until comparisons.length()).all {
            comparisons.getJSONObject(it).getLong("different_pixels") == 0L
        }) { "Incidents interaction endpoint/menu pixels differ; retain captures without tolerance" }
        comparisonsPassed = true
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        release { nativeParityOnMain(instrumentation) { trace?.stop(); trace = null } }
        release { nativeParityOnMain(instrumentation) {
            board?.let { observed ->
                currentRun?.put("observed_commands", JSONArray(observed.interactiveCommands(currentReference)))
                    ?.put("observed_back_callbacks", observed.interactiveBackCount(currentReference))
            }
        } }
        release { nativeParityOnMain(instrumentation) { board?.close() } }
        release { nativeParityOnMain(instrumentation) { board?.requireNoAsyncFailure() } }
        host?.let { activity ->
            release { nativeParityOnMain(instrumentation) { activity.fixtureRoot.removeAllViews() } }
            release { nativeParityOnMain(instrumentation) { activity.finish() } }
        }
        release { interactiveWindows?.close() }
        result.put("passed", comparisonsPassed && failure == null)
        failure?.let { result.put("failure", it.stackTraceToString()) }
        var written = false
        try { publishIncidentInteraction(directory, result); written = true }
        catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            result.put("passed", false).put("failure", checkNotNull(failure).stackTraceToString())
            try { publishIncidentInteraction(directory, result); written = true }
            catch (secondary: Throwable) { if (secondary !== failure) failure!!.addSuppressed(secondary) }
        }
        report.appendLine(if (written) "PARITY_EVIDENCE ${directory.name}/result.json"
            else "PARITY_EVIDENCE_WRITE_FAILED ${directory.name}/result.json")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_incidents_interaction: one finite source/native in-memory flow")
}

private fun awaitIncidentCondition(instrumentation: Instrumentation, host: NativeParityActivity,
                                   name: String, returningWindows: Set<Int> = emptySet(),
                                   condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 8_000L
    while (SystemClock.uptimeMillis() < deadline) {
        // Returning focus from our exiting popup is the only allowed transient loss here.
        val ready = nativeParityOnMain(instrumentation) { host.hasWindowFocus() && host.fixtureRoot.isShown }
        if (ready) {
            requireNativeParityHost(instrumentation, host)
            if (nativeParityOnMain(instrumentation, condition)) return
        } else {
            check(returningWindows.isNotEmpty()) { "Unexpected own-window focus loss during $name" }
            // Focus callbacks can lag the removal of our popup by one main-loop turn. Read-only
            // waiting accepts only the two exact already-observed own windows, never a foreign dialog.
            val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            try {
                check(root.refresh() && root.packageName?.toString() == host.packageName && root.windowId in returningWindows)
                val window = checkNotNull(root.window)
                try { check(window.displayId == 0 && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) }
                finally { @Suppress("DEPRECATION") window.recycle() }
            } finally { @Suppress("DEPRECATION") root.recycle() }
        }
        Thread.sleep(16)
    }
    error("Incidents own transition did not complete: $name; no corrective input")
}

private fun incidentAction(instrumentation: Instrumentation, host: NativeParityActivity,
                           board: IncidentsParityBoard, reference: Boolean, label: String, longClick: Boolean) {
    requireNativeParityHost(instrumentation, host)
    nativeParityOnMain(instrumentation) {
        board.requireNoAsyncFailure()
        val content = board.interactiveContent(reference)
        if (reference) {
            val root = onboardingViewDescendants(content).filterIsInstance<ViewRootForTest>().single()
            val candidates = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter { node ->
                val labels = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                val action = if (longClick) node.config.getOrNull(SemanticsActions.OnLongClick)?.action
                    else node.config.getOrNull(SemanticsActions.OnClick)?.action
                label in labels && action != null && node.layoutInfo.isPlaced &&
                    node.config.getOrNull(SemanticsProperties.Disabled) == null
            }
            val target = candidates.single()
            check(if (longClick) target.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke()
                else target.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
        } else {
            val candidates = onboardingViewDescendants(content).filter { view ->
                val info = view.createAccessibilityNodeInfo() ?: return@filter false
                try {
                    info.isVisibleToUser && info.isEnabled &&
                        (if (longClick) info.isLongClickable else info.isClickable) &&
                        onboardingViewDescendants(view).any { child ->
                            val childInfo = child.createAccessibilityNodeInfo() ?: return@any false
                            try { childInfo.isVisibleToUser &&
                                (childInfo.text?.toString() == label || childInfo.contentDescription?.toString() == label) }
                            finally { @Suppress("DEPRECATION") childInfo.recycle() }
                        }
                } finally { @Suppress("DEPRECATION") info.recycle() }
            }
            check(candidates.single().performAccessibilityAction(
                if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK, null))
        }
    }
}

private fun captureIncidentStep(instrumentation: Instrumentation, host: NativeParityActivity,
                                board: IncidentsParityBoard, reference: Boolean, directory: File,
                                name: String, pictures: MutableMap<String, File>) {
    var previous: Bitmap? = null
    var stable = 0
    val deadline = SystemClock.uptimeMillis() + 8_000L
    try {
        while (SystemClock.uptimeMillis() < deadline) {
            val current = captureNativeParityContent(instrumentation, host) { board.show(reference) }
            val identical = previous?.let { compareNativeParityPixels(it, current).getLong("different_pixels") == 0L } ?: false
            previous?.recycle()
            previous = current
            stable = if (identical) stable + 1 else 0
            if (stable >= 2) {
                val file = File(directory, "$name.png")
                saveNativeParityBitmap(directory, file.name, current)
                pictures[name] = file
                return
            }
        }
        error("Incidents endpoint did not settle: $name")
    } finally { previous?.recycle() }
}

private fun currentIncidentApplicationWindow(instrumentation: Instrumentation, host: NativeParityActivity): Int {
    requireNativeParityHost(instrumentation, host)
    val node = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
    try {
        check(node.packageName?.toString() == host.packageName)
        return node.windowId
    } finally { @Suppress("DEPRECATION") node.recycle() }
}

/** Only a newly observed own root may lack its separately published window metadata. */
private class IncidentMenuWindowNotPublished(val observation: JSONObject) :
    IllegalStateException("Own Incidents popup window metadata is not published")

/** Current physical0 QA popup only. No historical window labels or app request substitute. */
private fun currentIncidentMenu(instrumentation: Instrumentation, host: NativeParityActivity,
                                expectedWindow: Int? = null): Pair<AccessibilityNodeInfo, JSONObject> {
    check(host.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
    try {
        check(root.refresh() && root.packageName?.toString() == host.packageName && root.isVisibleToUser)
        if (expectedWindow != null) check(root.windowId == expectedWindow)
        val window = root.window ?: throw IncidentMenuWindowNotPublished(JSONObject()
            .put("root_window_id", root.windowId).put("root_package", root.packageName?.toString())
            .put("root_visible", root.isVisibleToUser).put("root_class", root.className?.toString())
            .put("root_child_count", root.childCount).put("window_metadata", JSONObject.NULL))
        try {
            check(window.displayId == 0 && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused)
        } finally { @Suppress("DEPRECATION") window.recycle() }
        val labels = JSONArray()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                node.text?.toString()?.let(labels::put)
                for (i in 0 until node.childCount) {
                    val child = checkNotNull(node.getChild(i))
                    try { visit(child) } finally { @Suppress("DEPRECATION") child.recycle() }
                }
            }
        }
        visit(root)
        val values = (0 until labels.length()).map(labels::getString)
        check(host.getString(R.string.incident_copy) in values && host.getString(R.string.delete_recording) in values)
        return root to JSONObject().put("window_id", root.windowId).put("labels", labels)
    } catch (failure: Throwable) {
        @Suppress("DEPRECATION") root.recycle()
        throw failure
    }
}

private fun awaitIncidentMenu(instrumentation: Instrumentation, host: NativeParityActivity,
                              mainWindow: Int, readiness: JSONObject): JSONObject {
    val deadline = SystemClock.uptimeMillis() + 8_000L
    var pendingWindow: Int? = null
    var attempts = 0
    // Root may still be the known own main window while its new popup is attaching.
    while (SystemClock.uptimeMillis() < deadline) {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val id = try {
            check(root.packageName?.toString() == host.packageName) { "Foreign window interrupted Incidents popup" }
            root.windowId
        } finally { @Suppress("DEPRECATION") root.recycle() }
        readiness.put("reads", ++attempts).put("last_root_window_id", id)
        pendingWindow?.let { check(id == it) { "Observed Incidents popup changed before readiness" } }
        if (id != mainWindow) {
            pendingWindow = id
            try {
                val (node, evidence) = currentIncidentMenu(instrumentation, host, id)
                @Suppress("DEPRECATION") node.recycle()
                readiness.put("accepted", true).put("accepted_window_id", id)
                return evidence
            } catch (pending: IncidentMenuWindowNotPublished) {
                // Root and window info use separate accessibility Binder/cache queries. This
                // read-only wait covers only missing metadata, never wrong ownership/focus,
                // an unexpected replacement window, absent labels, or a failed user action.
                val windows = nativeParityOnMain(instrumentation) {
                    JSONArray().apply {
                        WindowInspector.getGlobalWindowViews().forEach { view ->
                            put(JSONObject().put("class", view.javaClass.name)
                                .put("package", view.context.packageName)
                                .put("uid", view.context.applicationInfo.uid)
                                .put("attached", view.isAttachedToWindow).put("shown", view.isShown)
                                .put("focused", view.hasWindowFocus()).put("display_id", view.display?.displayId))
                        }
                    }
                }
                val observation = pending.observation.put("uptime_ms", SystemClock.uptimeMillis())
                    .put("local_windows", windows)
                if (!readiness.has("first_pending")) readiness.put("first_pending", observation)
                readiness.put("last_pending", observation).put("accepted", false)
                val focused = (0 until windows.length()).map(windows::getJSONObject).filter {
                    it.getBoolean("attached") && it.getBoolean("shown") && it.getBoolean("focused")
                }.single()
                check(focused.getString("package") == host.packageName && focused.getInt("uid") == Process.myUid() &&
                    focused.getInt("display_id") == 0) { "Own physical popup focus was lost while metadata was pending" }
            }
        }
        Thread.sleep(16)
    }
    error("Own Incidents menu did not appear; do not repeat long-click")
}

private fun captureIncidentMenu(instrumentation: Instrumentation, host: NativeParityActivity, window: Int,
                                directory: File, name: String, pictures: MutableMap<String, File>) {
    val bounds = nativeParityOnMain(instrumentation) {
        val location = IntArray(2); host.fixtureRoot.getLocationOnScreen(location)
        Rect(location[0], location[1], location[0] + host.fixtureRoot.width, location[1] + host.fixtureRoot.height)
    }
    var previous: Bitmap? = null
    var stable = 0
    val deadline = SystemClock.uptimeMillis() + 8_000L
    try {
        while (SystemClock.uptimeMillis() < deadline) {
            currentIncidentMenu(instrumentation, host, window).first.recycle()
            val full = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val crop = try {
                currentIncidentMenu(instrumentation, host, window).first.recycle()
                check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= full.width && bounds.bottom <= full.height)
                val selected = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height())
                // createBitmap may return its source for a full-display rectangle.
                if (selected === full) checkNotNull(full.copy(Bitmap.Config.ARGB_8888, false)) else selected
            } finally { full.recycle() }
            val same = previous?.let { compareNativeParityPixels(it, crop).getLong("different_pixels") == 0L } ?: false
            previous?.recycle(); previous = crop
            stable = if (same) stable + 1 else 0
            if (stable >= 2) {
                val file = File(directory, "$name.png")
                saveNativeParityBitmap(directory, file.name, crop); pictures[name] = file
                return
            }
            Thread.sleep(32)
        }
        error("Incidents popup raster did not settle")
    } finally { previous?.recycle() }
}

private fun clickIncidentMenuDelete(instrumentation: Instrumentation, host: NativeParityActivity, window: Int) {
    val (root, _) = currentIncidentMenu(instrumentation, host, window)
    val matches = ArrayList<AccessibilityNodeInfo>()
    val delete = host.getString(R.string.delete_recording)
    fun visit(node: AccessibilityNodeInfo) {
        if (node.isVisibleToUser && node.isEnabled && node.isClickable) {
            fun containsLabel(value: AccessibilityNodeInfo): Boolean {
                if (value.text?.toString() == delete) return true
                for (i in 0 until value.childCount) {
                    val child = checkNotNull(value.getChild(i))
                    try { if (containsLabel(child)) return true }
                    finally { @Suppress("DEPRECATION") child.recycle() }
                }
                return false
            }
            if (containsLabel(node)) matches += AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = checkNotNull(node.getChild(i))
            try { visit(child) } finally { @Suppress("DEPRECATION") child.recycle() }
        }
    }
    try {
        visit(root)
        val target = matches.single()
        currentIncidentMenu(instrumentation, host, window).first.recycle()
        check(target.refresh() && target.windowId == window && target.isVisibleToUser && target.isEnabled)
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    } finally {
        matches.forEach { @Suppress("DEPRECATION") it.recycle() }
        @Suppress("DEPRECATION") root.recycle()
    }
}

private class IncidentInteractionTrace(private val board: IncidentsParityBoard, private val reference: Boolean) {
    val samples = JSONArray()
    private var active = false
    private var failure: Throwable? = null
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            try {
                samples.put(JSONObject().put("frame_time_ns", frameTimeNanos)
                    .put("observed_uptime_ms", SystemClock.uptimeMillis())
                    .put("progress", board.interactiveProgress(reference).toDouble())
                    .put("mounted", board.interactiveMounted(reference)))
            } catch (error: Throwable) {
                failure = error; active = false; return
            }
            if (active) Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun start() { check(!active); active = true; Choreographer.getInstance().postFrameCallback(callback) }
    fun stop(): JSONArray {
        active = false
        Choreographer.getInstance().removeFrameCallback(callback)
        failure?.let { throw it }
        return samples
    }
}

private fun publishIncidentInteraction(directory: File, result: JSONObject) {
    val pending = File(directory, "result.json.pending")
    pending.writeText(result.toString(2))
    check(pending.renameTo(File(directory, "result.json"))) { "Cannot publish complete Incidents interaction evidence" }
}
