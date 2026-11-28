package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.ViewCompat
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import smallthingz.ui.reverb.ReverbIncidentActions
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.ReverbIncidentCardView
import smallthingz.ui.reverb.ReverbIncidentsContentView
import smallthingz.ui.reverb.reverbIncidentSafeInsets
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            val label = if (reference) checkNotNull(row.description)
                else row.startLabel
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
                "$renderer-menu", pictures, run)
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
            comparisons.put(comparison.put("step", step).put("scope",
                if (step == "menu") "app viewport excluding platform system overlays" else "app surface"))
        }
        check(runs.getJSONObject(0).getJSONObject("menu_capture_region").toString() ==
            runs.getJSONObject(1).getJSONObject("menu_capture_region").toString()) {
            "Released/native app viewport geometry changed"
        }
        var fullReleased: Bitmap? = null
        var fullNative: Bitmap? = null
        try {
            fullReleased = checkNotNull(android.graphics.BitmapFactory.decodeFile(File(directory, "released-menu.png").path))
            fullNative = checkNotNull(android.graphics.BitmapFactory.decodeFile(File(directory, "native-menu.png").path))
            result.put("menu_full_fixture_observation", compareNativeParityPixels(fullReleased, fullNative))
                .put("menu_comparison_scope", "Exact app viewport from current system-bar/cutout insets. All pixels in those platform inset regions are excluded from this menu comparison; full fixture diff retained separately.")
        } finally { fullReleased?.recycle(); fullNative?.recycle() }
        result.put("interaction_pixels", "Reference and grouped native endpoints are captured for inspection.")
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

/** New kind-3 native scenario, explicitly outside the historical source/native oracle. */
internal fun verifyNativeIncidentsObservation(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    val currentUiSmoke = context.packageName == "app.smallthingz.reverb.uitheme.reliability"
    check(currentUiSmoke || context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(Build.VERSION.SDK_INT >= 30)
    val directory = File(context.filesDir, "native-incidents-observation-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val states = JSONArray()
    val result = JSONObject().put("passed", false).put("states", states)
        .put("scope", "Grouped observation rows, selection and clear confirmation")
        .put("fixture_package", context.packageName)
        .put("historical_parity", false)
        .put("unqualified", JSONArray(listOf("Physical input", "Clipboard", "Historical kind-3 parity")))
    publishIncidentInteraction(directory, result)
    var host: NativeParityActivity? = null
    var windows: NativeParityInteractiveWindows? = null
    var board: IncidentsParityBoard? = null
    var failure: Throwable? = null
    var completed = false
    try {
        val windowScope = NativeParityInteractiveWindows.open(instrumentation, result).also { windows = it }
        val activity = (if (currentUiSmoke) launchCurrentUiSmokeActivity(instrumentation)
            else launchNativeParityActivity(instrumentation)).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        windowScope.recordHost(instrumentation, activity)
        val message = CaptureInputObservationReason.POLICY_SILENCED.description
        val duration = CaptureObservationDuration(SystemClock.elapsedRealtime())
        val incidents = listOf(
            RecordingIncident(1_700_000_000_000, resumedAtMillis = 0L,
                kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION, pid = 73,
                processStartedAtMillis = 1_699_000_000_000, observationId = 11L,
                description = message, ongoingPolicyDuration = duration),
            RecordingIncident(1_700_000_010_000, resumedAtMillis = 1_700_000_011_000,
                kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION, pid = 73,
                processStartedAtMillis = 1_699_000_000_000, observationId = 12L, description = message),
            RecordingIncident(1_700_000_020_000, resumedAtMillis = 1_700_000_021_000,
                kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION, pid = 73,
                processStartedAtMillis = 1_699_000_000_000, observationId = 13L, description = message),
        )
        val keys = incidents.map(::recordingIncidentPresentationKey)
        val history = prepareIncidentHistory(incidents, java.util.Locale.US, java.time.ZoneOffset.UTC)
        check(history.days.single().groups.single().occurrences.map { it.key } == keys.asReversed())
        val labels = keys.map { key -> history.rows.single { it.key == key }.startLabel }
        val ongoingPrefix = activity.getString(R.string.incident_occurrence_ongoing, "").trimEnd()
        val content = nativeParityOnMain(instrumentation) {
            IncidentsParityBoard(activity, dark = true, scene = IncidentParityScene.POPULATED,
                interactive = true, nativeOnly = true, nativeFixtureHistory = history)
        }.also { board = it }
        content.awaitReady(instrumentation)
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val pictures = LinkedHashMap<String, File>()
        fun list() = onboardingViewDescendants(content.interactiveContent(false))
            .filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
        fun snapshot(stage: String): Map<String, Long> = nativeParityOnMain(instrumentation) {
            content.requireNoAsyncFailure()
            val rows = content.interactiveState(false).history.rows
            val recycler = list()
            val adapter = checkNotNull(recycler.adapter)
            check(adapter.hasStableIds() && adapter.itemCount == rows.size + 2 && !recycler.isComputingLayout)
            val ids = rows.indices.associate { rows[it].key to adapter.getItemId(it + 2) }
            check(ids.size == rows.size && ids.values.toSet().size == rows.size)
            check(ids.values.none { it == androidx.recyclerview.widget.RecyclerView.NO_ID })
            states.put(JSONObject().put("stage", stage).put("rows", JSONArray(rows.mapIndexed { index, row ->
                JSONObject().put("key", row.key).put("stable_id", adapter.getItemId(index + 2))
                    .put("observation_id", row.incident.observationId).put("acknowledged", row.incident.acknowledged)
            })))
            ids
        }
        fun reveal(label: String, name: String) {
            nativeParityOnMain(instrumentation) {
                val rows = content.interactiveState(false).history.rows
                list().scrollToPosition(rows.indexOfFirst { it.startLabel == label }
                    .also { check(it >= 0) } + 2)
            }
            awaitIncidentCondition(instrumentation, activity, "visible exact observation row") {
                val rows = content.interactiveState(false).history.rows
                val position = rows.indexOfFirst {
                    it.startLabel == label
                } + 2
                val recycler = list()
                if (recycler.isLayoutRequested || recycler.isComputingLayout) return@awaitIncidentCondition false
                val holder = recycler.findViewHolderForAdapterPosition(position) ?: return@awaitIncidentCondition false
                check(holder.itemId == checkNotNull(recycler.adapter).getItemId(position))
                val visible = onboardingViewDescendants(holder.itemView).mapNotNull { view ->
                    val info = view.createAccessibilityNodeInfo() ?: return@mapNotNull null
                    try { info.text?.toString()?.takeIf { info.isVisibleToUser } }
                    finally { @Suppress("DEPRECATION") info.recycle() }
                }
                visible.any { it.startsWith("$label · ") } && visible.any { it.startsWith("PID 73") }
            }
            captureIncidentStep(instrumentation, activity, content, false, directory, name, pictures)
        }
        fun visibleRowHas(position: Int, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
            val recycler = list()
            if (recycler.isLayoutRequested || recycler.isComputingLayout) return false
            val row = recycler.findViewHolderForAdapterPosition(position)?.itemView ?: return false
            return onboardingViewDescendants(row).any { view ->
                val node = view.createAccessibilityNodeInfo() ?: return@any false
                try { node.isVisibleToUser && predicate(node) }
                finally { @Suppress("DEPRECATION") node.recycle() }
            }
        }
        fun awaitAcknowledgementNode(key: String, checked: Boolean) {
            val label = activity.getString(if (checked) smallthingz.ui.R.string.reverb_incident_checked
                else smallthingz.ui.R.string.reverb_incident_unchecked)
            awaitIncidentCondition(instrumentation, activity, "visible acknowledgement state: $label") {
                val index = content.interactiveState(false).history.rows.indexOfFirst { it.key == key }
                index >= 0 && visibleRowHas(index + 2) { it.isClickable && it.stateDescription?.toString() == label }
            }
        }
        fun resubmit(stage: String, expected: Map<String, Long>) {
            nativeParityOnMain(instrumentation) {
                // Exercise the actual View/adapter same-state submission, not a reimplemented ID map.
                val view = onboardingViewDescendants(content.interactiveContent(false))
                    .filterIsInstance<ReverbIncidentsContentView>().single()
                view.bind(content.interactiveState(false).nativeHistory)
            }
            awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
            check(snapshot(stage) == expected)
        }
        val initial = snapshot("initial")
        nativeParityOnMain(instrumentation) {
            verifyIncidentRowFirstPaint(list(), content.interactiveState(false).history.rows, content.nativeFixturePalette())
        }
        result.put("rebound_occurrence_paints_own_state_before_draw", true)
        resubmit("same-initial-submission", initial)
        reveal(labels[0], "ongoing-initial")
        val incidentsView = nativeParityOnMain(instrumentation) {
            onboardingViewDescendants(content.interactiveContent(false))
                .filterIsInstance<ReverbIncidentsContentView>().single()
        }
        fun ongoingText() = onboardingViewDescendants(incidentsView)
            .filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
            .map { it.getText() }.single { it.contains(ongoingPrefix) }
        val firstElapsed = nativeParityOnMain(instrumentation, ::ongoingText)
        awaitIncidentCondition(instrumentation, activity, "visible ongoing duration advances") { ongoingText() != firstElapsed }
        val hiddenElapsed = nativeParityOnMain(instrumentation) {
            ongoingText().also { incidentsView.visibility = View.INVISIBLE }
        }
        try {
            val hiddenInterval = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed(hiddenInterval::countDown, 1_200L)
            check(hiddenInterval.await(5, TimeUnit.SECONDS))
            check(nativeParityOnMain(instrumentation, ::ongoingText) == hiddenElapsed) { "Hidden incident duration advanced" }
        } finally { nativeParityOnMain(instrumentation) { incidentsView.visibility = View.VISIBLE } }
        awaitIncidentCondition(instrumentation, activity, "shown ongoing duration catches up") { ongoingText() != hiddenElapsed }
        result.put("ongoing_visible_hidden_visible", true)
        val settledOccurrence = incidents.first().copy(resumedAtMillis = incidents.first().occurredAtMillis + 2_000L,
            ongoingPolicyDuration = null)
        nativeParityOnMain(instrumentation) {
            duration.finish(duration.startedAtElapsedMillis + 2_000L)
            content.replaceNativeFixtureHistory(listOf(settledOccurrence) + incidents.drop(1))
        }
        awaitIncidentCondition(instrumentation, activity, "ongoing occurrence displays its end time") {
            val row = content.interactiveState(false).history.rows.single { it.key == keys[0] }
            if (row.ended == null) return@awaitIncidentCondition false
            onboardingViewDescendants(incidentsView).filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
                .any { it.getText() == row.timeLabel }
        }
        check(snapshot("same-identities-after-live-end") == initial)
        for ((index, label) in labels.withIndex()) reveal(label, "initial-$index")
        for ((index, label) in labels.withIndex()) {
            reveal(label, "before-ack-$index")
            awaitAcknowledgementNode(keys[index], checked = false)
            incidentAction(instrumentation, activity, content, false, label, longClick = false)
            awaitIncidentCondition(instrumentation, activity, "acknowledge exact observation") {
                val rows = content.interactiveState(false).history.rows
                rows.map { it.key }.toSet() == keys.toSet() && rows.all { row ->
                    row.incident.acknowledged == (keys.indexOf(row.key) <= index)
                }
            }
            awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
            check(snapshot("ack-$index") == initial)
            reveal(label, "acknowledged-$index")
            awaitAcknowledgementNode(keys[index], checked = true)
            if (index == 0) resubmit("same-acknowledged-submission", initial)
        }
        reveal(labels[0], "before-delete")
        val mainWindow = currentIncidentApplicationWindow(instrumentation, activity)
        incidentAction(instrumentation, activity, content, false, labels[0], longClick = true)
        val menu = awaitIncidentMenu(instrumentation, activity, mainWindow, JSONObject().also { result.put("menu_readiness", it) })
        captureIncidentMenu(instrumentation, activity, menu.getInt("window_id"), directory,
            "observation-menu", pictures, result)
        result.put("menu_toolbar_safe_area", nativeParityOnMain(instrumentation) {
            verifyIncidentToolbarSafeArea(activity, incidentsView)
        })
        clickIncidentMenuDelete(instrumentation, activity, menu.getInt("window_id"))
        awaitIncidentCondition(instrumentation, activity, "delete exact observation",
            returningWindows = setOf(mainWindow, menu.getInt("window_id"))) {
            val rows = content.interactiveState(false).history.rows
            rows.map { it.key }.toSet() == keys.drop(1).toSet() && rows.all { it.incident.acknowledged }
        }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        check(snapshot("deleted-first") == initial - keys[0])
        reveal(labels[1], "remaining-observation")
        awaitIncidentCondition(instrumentation, activity, "retire ongoing row after deletion") {
            onboardingViewDescendants(incidentsView).filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
                .none { it.getText().contains(ongoingPrefix) }
        }
        fun tapToolbar(label: Int) = incidentAction(instrumentation, activity, content, false,
            activity.getString(label), longClick = false)
        requireNativeParityHost(instrumentation, activity)
        val sameTurnSelection = observeIncidentSelectionUpdates(instrumentation, { checkNotNull(list().adapter) }) {
            nativeParityOnMain(instrumentation) {
                val view = content.interactiveContent(false)
                val commands = content.interactiveCommands(false)
                check(performNativeIncidentAction(view, activity.getString(R.string.multi_select)))
                check(!performNativeIncidentAction(view, labels[1], longClick = true))
                check(performNativeIncidentAction(view, labels[1]))
                check(content.interactiveCommands(false) == commands) { "Entering selection acknowledged an occurrence" }
            }
        }
        check(sameTurnSelection == listOf(2 to 2, 3 to 1)) {
            "Select mode and the immediate individual check must update the exact rows: $sameTurnSelection"
        }
        awaitIncidentCondition(instrumentation, activity, "selection holder has rebound before reverse transition") {
            val card = list().findViewHolderForAdapterPosition(3)?.itemView ?: return@awaitIncidentCondition false
            onboardingViewDescendants(card).any { view ->
                val node = view.createAccessibilityNodeInfo() ?: return@any false
                try { node.isSelected }
                finally { @Suppress("DEPRECATION") node.recycle() }
            }
        }
        nativeParityOnMain(instrumentation) {
            val view = content.interactiveContent(false)
            val commands = content.interactiveCommands(false)
            check(performNativeIncidentAction(view, activity.getString(R.string.clear_selection)))
            check(performNativeIncidentAction(view, labels[1]))
            check(content.interactiveCommands(false) == commands + "ack:${keys[1]}")
        }
        awaitIncidentCondition(instrumentation, activity, "same-turn normal action acknowledges the intended incident") {
            val state = content.interactiveState(false)
            state.canMutate && state.history.rows.single { it.key == keys[1] }.incident.acknowledged == false
        }
        result.put("same_turn_selection_target_before_rebind", keys[1])
        tapToolbar(R.string.multi_select)
        val observedSelection = observeIncidentSelectionUpdates(
            instrumentation, { checkNotNull(list().adapter) },
        ) {
            incidentAction(instrumentation, activity, content, false, labels[1], longClick = false)
        }
        check(observedSelection == listOf(3 to 1)) { "Single selection updates exactly one occurrence: $observedSelection" }
        result.put("single_selection_changed_occurrences", observedSelection.single().second)
        tapToolbar(R.string.incidents_select_all)
        tapToolbar(R.string.incidents_deselect_all)
        tapToolbar(R.string.incidents_select_all)
        tapToolbar(R.string.clear_selection)
        check(nativeParityOnMain(instrumentation) { !incidentsView.selectionActive })
        tapToolbar(R.string.multi_select)
        incidentAction(instrumentation, activity, content, false, labels[1], longClick = false)
        tapToolbar(R.string.incidents_delete_selected)
        awaitIncidentCondition(instrumentation, activity, "delete selected occurrence") {
            content.interactiveState(false).history.rows.singleOrNull()?.key == keys[2]
        }
        val beforeClear = currentIncidentApplicationWindow(instrumentation, activity)
        tapToolbar(R.string.incidents_clear_all)
        val cancelWindow = clickIncidentConfirmation(instrumentation, activity, beforeClear, R.string.cancel)
        awaitIncidentCondition(instrumentation, activity, "cancel preserves the final occurrence",
            returningWindows = setOf(beforeClear, cancelWindow)) {
            content.interactiveState(false).history.rows.singleOrNull()?.key == keys[2]
        }
        tapToolbar(R.string.incidents_clear_all)
        val arrival = incidents.last().copy(occurredAtMillis = 1_700_000_030_000,
            resumedAtMillis = 1_700_000_031_000, observationId = 14L)
        nativeParityOnMain(instrumentation) {
            content.replaceNativeFixtureHistory(content.interactiveState(false).history.rows.map { it.incident } + arrival)
        }
        val confirmWindow = clickIncidentConfirmation(instrumentation, activity, beforeClear, R.string.incidents_clear_all)
        awaitIncidentCondition(instrumentation, activity, "clear only the confirmed snapshot",
            returningWindows = setOf(beforeClear, confirmWindow)) {
            content.interactiveState(false).history.rows.singleOrNull()?.incident == arrival
        }
        val expected = keys.map { "ack:$it" } + listOf("delete:${keys[0]}", "ack:${keys[1]}") +
            keys.drop(1).map { "delete:$it" }
        check(nativeParityOnMain(instrumentation) { content.interactiveCommands(false) } == expected)
        val pending = RecordingIncident(1_700_000_060_000L, resumedAtMillis = 0L,
            pid = 74, processStartedAtMillis = 1_700_000_050_000L, captureArmedAtMillis = 1_700_000_055_000L)
        val unknown = pending.copy(occurredAtMillis = 1_700_000_040_000L, resumedAtMillis = -1L,
            pid = 75, processStartedAtMillis = 1_700_000_035_000L, captureArmedAtMillis = 1_700_000_036_000L)
        val pendingKey = recordingIncidentPresentationKey(pending)
        fun awaitEndLabel(position: Int, label: String) {
            nativeParityOnMain(instrumentation) { list().scrollToPosition(position) }
            awaitIncidentCondition(instrumentation, activity, "visible occurrence end: $label") {
                visibleRowHas(position) { it.text?.toString() == label }
            }
        }
        nativeParityOnMain(instrumentation) { content.replaceNativeFixtureHistory(listOf(pending, unknown)) }
        awaitIncidentCondition(instrumentation, activity, "pending and unknown end fixture") {
            content.interactiveState(false).history.rows.map { it.incident } == listOf(pending, unknown) &&
                list().adapter?.itemCount == 4 && !list().isComputingLayout
        }
        val beforeResume = snapshot("pending-recovery")
        val pendingTime = "10:14:20 PM"
        val unknownTime = "10:14:00 PM"
        awaitEndLabel(2, "$pendingTime · ${activity.getString(R.string.incident_occurrence_pending_recovery)}")
        awaitEndLabel(3, "$unknownTime · ${activity.getString(R.string.incident_duration_unknown)}")
        val resumed = pending.copy(resumedAtMillis = 1_700_000_065_000L)
        nativeParityOnMain(instrumentation) { content.replaceNativeFixtureHistory(listOf(resumed, unknown)) }
        awaitEndLabel(2, "$pendingTime · 0:05")
        awaitEndLabel(3, "$unknownTime · ${activity.getString(R.string.incident_duration_unknown)}")
        check(snapshot("resumed-recovery") == beforeResume)
        result.put("pending_recovery_key", pendingKey)
            .put("pending_recovery_retained_id", beforeResume.getValue(pendingKey))

        val many = List(32) { index ->
            val occurredAt = arrival.occurredAtMillis + (index / 16) * 86_400_000L + (index % 16) * 1_000L
            arrival.copy(occurredAtMillis = occurredAt, resumedAtMillis = occurredAt + 500L,
                observationId = 100L + index, description = if (index % 2 == 0) message else "Second fixture message")
        }
        val manyKeys = many.map(::recordingIncidentPresentationKey).toSet()
        nativeParityOnMain(instrumentation) { content.replaceNativeFixtureHistory(many) }
        awaitIncidentCondition(instrumentation, activity, "offscreen select-all fixture") {
            val history = content.interactiveState(false).history
            history.rows.size == many.size && history.days.size == 2 && list().adapter?.itemCount == many.size + 6
        }
        fun awaitOffscreenRows() {
            awaitIncidentCondition(instrumentation, activity, "at least one group has detached occurrence holders") {
                val recycler = list()
                val lastPosition = checkNotNull(recycler.adapter).itemCount - 1
                !recycler.isLayoutRequested && !recycler.isComputingLayout &&
                    recycler.findViewHolderForAdapterPosition(2) != null &&
                    recycler.findViewHolderForAdapterPosition(lastPosition) == null
            }
        }
        nativeParityOnMain(instrumentation) { list().scrollToPosition(0) }
        awaitOffscreenRows()
        fun awaitSelectedCount(count: Int) {
            val label = activity.resources.getQuantityString(R.plurals.incidents_selected, count, count)
            awaitIncidentCondition(instrumentation, activity, "selection count: $label") {
                onboardingViewDescendants(incidentsView).any { view ->
                    val node = view.createAccessibilityNodeInfo() ?: return@any false
                    try { node.isVisibleToUser && node.text?.toString() == label }
                    finally { @Suppress("DEPRECATION") node.recycle() }
                }
            }
        }
        tapToolbar(R.string.multi_select)
        tapToolbar(R.string.incidents_select_all)
        awaitSelectedCount(many.size)
        tapToolbar(R.string.incidents_deselect_all)
        awaitSelectedCount(0)
        check(nativeParityOnMain(instrumentation) { content.interactiveCommands(false) } == expected)
        tapToolbar(R.string.incidents_select_all)
        awaitSelectedCount(many.size)
        awaitOffscreenRows()
        tapToolbar(R.string.incidents_delete_selected)
        awaitIncidentCondition(instrumentation, activity, "offscreen selected rows deleted") {
            content.interactiveState(false).showEmptyHistory
        }
        val commands = nativeParityOnMain(instrumentation) { content.interactiveCommands(false) }
        val deleted = commands.drop(expected.size)
        check(commands.take(expected.size) == expected && deleted.size == manyKeys.size &&
            deleted.toSet() == manyKeys.mapTo(mutableSetOf()) { "delete:$it" })
        result.put("offscreen_select_all_deleted_occurrences", deleted.size)
            .put("commands", JSONArray(commands)).put("captures", JSONArray(pictures.keys.toList()))
        completed = true
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        release { nativeParityOnMain(instrumentation) { board?.close() } }
        host?.let { activity ->
            release { nativeParityOnMain(instrumentation) { activity.fixtureRoot.removeAllViews() } }
            release { nativeParityOnMain(instrumentation) { activity.finish() } }
        }
        release { windows?.close() }
        result.put("passed", completed && failure == null)
        failure?.let { result.put("failure", it.stackTraceToString()) }
        var written = false
        try { publishIncidentInteraction(directory, result); written = true }
        catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) }
        report.appendLine(if (written) "PARITY_EVIDENCE ${directory.name}/result.json"
            else "PARITY_EVIDENCE_WRITE_FAILED ${directory.name}/result.json")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_incidents_observation: native-only distinct IDs, submissions and row actions")
}

private fun observeIncidentSelectionUpdates(
    instrumentation: Instrumentation,
    adapter: () -> androidx.recyclerview.widget.RecyclerView.Adapter<*>,
    select: () -> Unit,
): List<Pair<Int, Int>> {
    val changes = mutableListOf<Pair<Int, Int>>()
    val observer = object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
        override fun onChanged() = error("Selection changed the whole incident list")
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = error("Selection inserted rows")
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = error("Selection removed rows")
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = error("Selection moved rows")
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            checkNotNull(payload) { "Selection must use an occurrence update payload" }
            changes += positionStart to itemCount
        }
    }
    val previous = nativeParityOnMain(instrumentation) { adapter().also { it.registerAdapterDataObserver(observer) } }
    try { select() }
    finally { nativeParityOnMain(instrumentation) { previous.unregisterAdapterDataObserver(observer) } }
    return changes
}

private fun verifyIncidentToolbarSafeArea(host: NativeParityActivity, incidents: ReverbIncidentsContentView): JSONObject {
    val safe = reverbIncidentSafeInsets(checkNotNull(ViewCompat.getRootWindowInsets(host.fixtureRoot)))
    val toolbar = (incidents.getChildAt(0) as ViewGroup).getChildAt(0)
    val panelOrigin = IntArray(2).also(incidents::getLocationOnScreen)
    val toolbarOrigin = IntArray(2).also(toolbar::getLocationOnScreen)
    check(toolbarOrigin[0] >= panelOrigin[0] + safe.left)
    check(toolbarOrigin[1] >= panelOrigin[1] + safe.top)
    check(toolbarOrigin[0] + toolbar.width <= panelOrigin[0] + incidents.width - safe.right)
    return JSONObject().put("required_insets", JSONArray(listOf(safe.left, safe.top, safe.right)))
        .put("toolbar_origin", JSONArray(toolbarOrigin.toList()))
}

/** Exercise an attached holder's real bind/unbind path and read its immediate paint state. */
private fun verifyIncidentRowFirstPaint(
    list: androidx.recyclerview.widget.RecyclerView,
    rows: List<IncidentPresentation>,
    palette: NativeColorScheme,
) {
    val card = checkNotNull(list.findViewHolderForAdapterPosition(2)).itemView
        as ReverbIncidentCardView
    check(card.isAttachedToWindow && card.isShown)
    val nextCard = checkNotNull(list.findViewHolderForAdapterPosition(3)).itemView
    val heading = checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
    check(heading.bottom == card.top) { "The title must share the occurrence surface" }
    check(card.bottom == nextCard.top) { "Occurrences of the same message must share a joined surface" }
    val surface = card.getChildAt(0) as smallthingz.ui.components.NativeGroupLayout
    val views = onboardingViewDescendants(card)
    val labels = views.filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
    val glyph = views.filterIsInstance<ReverbGlyphView>().single()
    val labelColor = labels.first().javaClass.getDeclaredField("textColor").apply { isAccessible = true }
    val glyphColor = glyph.javaClass.getDeclaredField("displayedColor").apply { isAccessible = true }
    val glyphIcon = glyph.javaClass.getDeclaredField("currentIcon").apply { isAccessible = true }
    val style = ReverbDesign.appearance.groupSurfaces.recordingRow
    val original = rows.first()
    val next = rows[1]
    val actions = ReverbIncidentActions({}, {}, {}, {})
    try {
        card.unbind()
        card.bind(original, canMutate = true, selected = true, actions = actions)
        check(surface.paintedFillColor == palette.primaryContainer)
        check(glyphColor.getInt(glyph) == palette.onPrimaryContainer)
        card.unbind()
        card.bind(next, canMutate = true, selected = null, actions = actions)
        check(surface.paintedFillColor == style.surface.fillColor(palette))
        check(labelColor.getLong(labels.first()) == smallthingz.ui.graphics.Color(palette.ink).value.toLong())
        check(labelColor.getLong(labels[1]) == smallthingz.ui.graphics.Color(palette.muted).value.toLong())
        check(glyphIcon.get(glyph) == app.smallthingz.reverb.nativeui.ReverbIcon.Unchecked)
        check(glyphColor.getInt(glyph) == palette.error)

        val acknowledged = next.copy(incident = next.incident.copy(acknowledgedAtMillis = next.incident.occurredAtMillis))
        card.unbind()
        card.bind(acknowledged, canMutate = true, selected = null, actions = actions)
        check(glyphIcon.get(glyph) == app.smallthingz.reverb.nativeui.ReverbIcon.Checked)
        check(glyphColor.getInt(glyph) == palette.acknowledgedStatusTint)
    } finally {
        checkNotNull(list.adapter).notifyItemChanged(2)
    }
}

private fun clickIncidentConfirmation(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    mainWindow: Int,
    actionLabel: Int,
): Int {
    val label = host.getString(actionLabel)
    val deadline = SystemClock.uptimeMillis() + 8_000L
    fun hasLabel(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString() == label) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (hasLabel(child)) return true }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return false
    }
    fun activate(node: AccessibilityNodeInfo): Boolean {
        if (node.isVisibleToUser && node.isEnabled && node.isClickable && hasLabel(node)) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (activate(child)) return true }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return false
    }
    while (SystemClock.uptimeMillis() < deadline) {
        val root = nativeParityUiAutomation(instrumentation).rootInActiveWindow
        if (root != null) {
            try {
                check(root.packageName?.toString() == host.packageName)
                val window = root.window
                if (root.windowId != mainWindow && window != null) {
                    try {
                        check(window.displayId == 0 && window.type == AccessibilityWindowInfo.TYPE_APPLICATION)
                        if (window.isFocused && activate(root)) return root.windowId
                    } finally { @Suppress("DEPRECATION") window.recycle() }
                } else window?.recycle()
            } finally { @Suppress("DEPRECATION") root.recycle() }
        }
        SystemClock.sleep(25L)
    }
    error("Incident confirmation action unavailable: $label")
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
            val root = checkNotNull(nativeParityUiAutomation(instrumentation).rootInActiveWindow)
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
            check(performNativeIncidentAction(content, label, longClick))
        }
    }
}

private fun performNativeIncidentAction(content: View, label: String, longClick: Boolean = false): Boolean {
    val candidates = onboardingViewDescendants(content).filter { view ->
        val info = view.createAccessibilityNodeInfo() ?: return@filter false
        try {
            info.isVisibleToUser && info.isEnabled &&
                (if (longClick) info.isLongClickable else info.isClickable) &&
                onboardingViewDescendants(view).any { child ->
                    val childInfo = child.createAccessibilityNodeInfo() ?: return@any false
                    try { childInfo.isVisibleToUser &&
                        (childInfo.text?.toString() == label || childInfo.text?.toString()?.startsWith("$label · ") == true ||
                            childInfo.contentDescription?.toString() == label) }
                    finally { @Suppress("DEPRECATION") childInfo.recycle() }
                }
        } finally { @Suppress("DEPRECATION") info.recycle() }
    }
    return candidates.single().performAccessibilityAction(
        if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK, null)
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
            val old = previous
            previous = current
            val identical = try {
                old?.let { compareNativeParityPixels(it, current).getLong("different_pixels") == 0L } ?: false
            } finally { old?.recycle() }
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
    val node = checkNotNull(nativeParityUiAutomation(instrumentation).rootInActiveWindow)
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
    check(host.packageName == instrumentation.targetContext.packageName &&
        (host.packageName == "app.smallthingz.reverb.nativeparity.reliability" ||
            host.packageName == "app.smallthingz.reverb.uitheme.reliability") &&
        host.applicationContext.javaClass == Application::class.java)
    val root = checkNotNull(nativeParityUiAutomation(instrumentation).rootInActiveWindow)
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
        val root = checkNotNull(nativeParityUiAutomation(instrumentation).rootInActiveWindow)
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
                // read-only wait covers missing metadata and the observed zero-focus handoff
                // between own windows. It never admits input, another owner, or a replacement popup.
                val windows = nativeParityOnMain(instrumentation) {
                    incidentLocalWindowInventory()
                }
                val observation = pending.observation.put("uptime_ms", SystemClock.uptimeMillis())
                    .put("local_windows", windows)
                if (!readiness.has("first_pending")) readiness.put("first_pending", observation)
                readiness.put("last_pending", observation).put("accepted", false)
                try {
                    val visible = (0 until windows.length()).map(windows::getJSONObject).filter {
                        it.getBoolean("attached") && it.getBoolean("shown")
                    }
                    check(visible.isNotEmpty() && visible.all {
                        it.getString("package") == host.packageName && it.getInt("uid") == Process.myUid() &&
                            it.getInt("display_id") == 0
                    }) { "Own physical popup windows changed while metadata was pending" }
                    val focused = visible.filter { it.getBoolean("focused") }
                    observation.put("local_focused_count", focused.size)
                    check(focused.size <= 1) { "Ambiguous local popup focus while metadata was pending" }
                    if (focused.isEmpty()) {
                        // This is readiness only: currentIncidentMenu must still observe the pinned
                        // popup's focused physical window and exact labels before this loop returns.
                        readiness.put("focus_handoff_reads", readiness.optInt("focus_handoff_reads") + 1)
                        if (!readiness.has("first_focus_handoff")) readiness.put("first_focus_handoff", observation)
                        readiness.put("last_focus_handoff", observation)
                    }
                } catch (focusFailure: Throwable) {
                    try {
                        recordIncidentFocusFailure(readiness, observation, focusFailure)
                    } catch (diagnosticFailure: Throwable) {
                        if (diagnosticFailure !== focusFailure) focusFailure.addSuppressed(diagnosticFailure)
                    }
                    throw focusFailure // A later focus handoff never turns this failed admission into input.
                }
            }
        }
        Thread.sleep(16)
    }
    error("Own Incidents menu did not appear; do not repeat long-click")
}

/** Main-thread, in-process View observations only; no global window/dispatcher query. */
private fun incidentLocalWindowInventory() = JSONArray().apply {
    WindowInspector.getGlobalWindowViews().forEach { view ->
        put(JSONObject().put("class", view.javaClass.name).put("package", view.context.packageName)
            .put("uid", view.context.applicationInfo.uid).put("attached", view.isAttachedToWindow)
            .put("shown", view.isShown).put("focused", view.hasWindowFocus())
            .put("display_id", view.display?.displayId))
    }
}

/** Failure-only diagnostics: bounded caller wait, cancelled own callback, original failure retained. */
private fun recordIncidentFocusFailure(readiness: JSONObject, first: JSONObject, failure: Throwable) {
    val started = SystemClock.uptimeMillis()
    val samples = JSONArray().put(first)
    val trace = JSONObject().put("first_failure_uptime_ms", started).put("budget_ms", 500)
        .put("first_failure", failure.stackTraceToString()).put("samples", samples)
        .put("outcome", "original focus failure retained; no admission retry or input")
    readiness.put("focus_failure_timeline", trace)
    val lock = Any()
    val complete = CountDownLatch(1)
    val handler = Handler(Looper.getMainLooper())
    var active = true
    var diagnosticFailure: Throwable? = null
    lateinit var sample: Runnable
    sample = Runnable {
        if (!synchronized(lock) { active }) return@Runnable
        try {
            val observed = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                .put("local_windows", incidentLocalWindowInventory())
            synchronized(lock) {
                if (!active) return@Runnable
                samples.put(observed)
                val remaining = started + 500L - SystemClock.uptimeMillis()
                if (remaining > 0L) check(handler.postDelayed(sample, minOf(40L, remaining)))
                else complete.countDown()
            }
        } catch (error: Throwable) {
            synchronized(lock) { if (active) diagnosticFailure = error }
            complete.countDown()
        }
    }
    try {
        check(handler.post(sample))
        complete.await(maxOf(0L, started + 500L - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS)
    } catch (error: Throwable) {
        synchronized(lock) { diagnosticFailure = error }
    } finally {
        synchronized(lock) {
            active = false
            handler.removeCallbacks(sample)
            trace.put("elapsed_ms", SystemClock.uptimeMillis() - started)
                .put("callback_cancelled", true)
            diagnosticFailure?.let {
                trace.put("diagnostic_error", it.stackTraceToString())
                if (it !== failure) failure.addSuppressed(it)
            }
        }
    }
}

private fun captureIncidentMenu(instrumentation: Instrumentation, host: NativeParityActivity, window: Int,
                                directory: File, name: String, pictures: MutableMap<String, File>, run: JSONObject) {
    val bounds = nativeParityOnMain(instrumentation) {
        val location = IntArray(2); host.fixtureRoot.getLocationOnScreen(location)
        Rect(location[0], location[1], location[0] + host.fixtureRoot.width, location[1] + host.fixtureRoot.height)
    }
    val (viewport, region) = nativeParityOnMain(instrumentation) { incidentCaptureRegion(host, bounds) }
    run.put("menu_capture_region", region)
    var previous: Bitmap? = null
    var stable = 0
    val deadline = SystemClock.uptimeMillis() + 8_000L
    val frames = JSONArray().also { run.put("menu_capture_frames", it) }
    currentIncidentMenu(instrumentation, host, window).first.recycle()
    val popup = nativeParityOnMain(instrumentation) { incidentCapturePopup(host, window) }
    try {
        while (SystemClock.uptimeMillis() < deadline) {
            currentIncidentMenu(instrumentation, host, window).first.recycle()
            nativeParityOnMain(instrumentation) { check(incidentCapturePopup(host, window) === popup) }
            val frame = JSONObject().put("draw_requested_uptime_ms", SystemClock.uptimeMillis()).also(frames::put)
            // Identical compositor snapshots alone do not prove a completed popup render. In
            // particular, three snapshots can repeat while its entry animation is still pending.
            awaitNativeParityDraw(instrumentation, popup)
            frame.put("draw_completed_uptime_ms", SystemClock.uptimeMillis())
            currentIncidentMenu(instrumentation, host, window).first.recycle()
            frame.put("before_capture", nativeParityOnMain(instrumentation) {
                check(incidentCapturePopup(host, window) === popup)
                check(incidentCaptureRegion(host, bounds).second.toString() == region.toString())
                val location = IntArray(2).also(popup::getLocationOnScreen)
                check(viewport.contains(location[0], location[1], location[0] + popup.width, location[1] + popup.height)) {
                    "The complete popup must remain within the compared app viewport"
                }
                incidentCaptureFrameState(popup)
            })
            val full = checkNotNull(nativeParityUiAutomation(instrumentation).takeScreenshot())
            try {
                currentIncidentMenu(instrumentation, host, window).first.recycle()
                frame.put("after_capture", nativeParityOnMain(instrumentation) {
                    check(incidentCapturePopup(host, window) === popup)
                    check(incidentCaptureRegion(host, bounds).second.toString() == region.toString())
                    val location = IntArray(2).also(popup::getLocationOnScreen)
                    check(viewport.contains(location[0], location[1], location[0] + popup.width, location[1] + popup.height)) {
                        "The complete popup must remain within the compared app viewport"
                    }
                    incidentCaptureFrameState(popup)
                })
                check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= full.width && bounds.bottom <= full.height)
                val selected = Bitmap.createBitmap(full, viewport.left, viewport.top, viewport.width(), viewport.height())
                // createBitmap may return its source for a full-display rectangle.
                val crop = if (selected === full) checkNotNull(full.copy(Bitmap.Config.ARGB_8888, false)) else selected
                val previousFrame = previous
                previous = crop // Retain ownership even if exact comparison fails.
                val same = try {
                    previousFrame?.let { compareNativeParityPixels(it, crop).getLong("different_pixels") == 0L } ?: false
                } finally { previousFrame?.recycle() }
                stable = if (same) stable + 1 else 0
                frame.put("same_pixels_as_previous", same).put("consecutive_equal_pairs", stable)
                if (stable >= 2) {
                    saveNativeParityBitmap(directory, "$name-full-display.png", full)
                    val fixture = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height())
                    try { saveNativeParityBitmap(directory, "$name.png", fixture) }
                    finally { if (fixture !== full) fixture.recycle() }
                    val file = File(directory, "$name-app-content.png")
                    saveNativeParityBitmap(directory, file.name, crop); pictures[name] = file
                    currentIncidentMenu(instrumentation, host, window).first.recycle()
                    run.put("menu_text_geometry", nativeParityOnMain(instrumentation) {
                        incidentMenuTextGeometry(host, window)
                    })
                    currentIncidentMenu(instrumentation, host, window).first.recycle()
                    return
                }
            } finally { full.recycle() }
        }
        error("Incidents popup raster did not settle")
    } finally { previous?.recycle() }
}

/** Platform-reported overlay ownership, never image-derived masking or a pixel tolerance. */
private fun incidentCaptureRegion(host: NativeParityActivity, fixture: Rect): Pair<Rect, JSONObject> {
    val decor = host.window.decorView
    val location = IntArray(2).also(decor::getLocationOnScreen)
    val insets = checkNotNull(decor.rootWindowInsets).getInsets(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val viewport = Rect(location[0] + insets.left, location[1] + insets.top,
        location[0] + decor.width - insets.right, location[1] + decor.height - insets.bottom)
    check(viewport.intersect(fixture) && !viewport.isEmpty)
    return viewport to JSONObject().put("fixture_bounds", fixture.flattenToString())
        .put("decor_bounds", Rect(location[0], location[1], location[0] + decor.width,
            location[1] + decor.height).flattenToString())
        .put("system_bar_cutout_insets", JSONArray(listOf(insets.left, insets.top, insets.right, insets.bottom)))
        .put("compared_app_bounds", viewport.flattenToString())
}

/** Main-thread read of the exact current own popup; this never requests or repairs focus. */
private fun incidentCapturePopup(host: NativeParityActivity, window: Int): View {
    val popup = WindowInspector.getGlobalWindowViews().filter {
        it.isAttachedToWindow && it.isShown && it.hasWindowFocus()
    }.single()
    check(popup.context.applicationInfo.uid == Process.myUid() &&
        popup.context.packageName == host.packageName && popup.display?.displayId == 0)
    val node = checkNotNull(popup.createAccessibilityNodeInfo())
    try { check(node.windowId == window) }
    finally { @Suppress("DEPRECATION") node.recycle() }
    return popup
}

/** Image-bound View observations, not a claim about Compose's internal graphics-layer alpha. */
private fun incidentCaptureFrameState(popup: View): JSONObject {
    val focused = popup.findFocus()
    return JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
        .put("root_identity", System.identityHashCode(popup)).put("in_touch_mode", popup.isInTouchMode)
        .put("focused_view_identity", focused?.let(System::identityHashCode) ?: JSONObject.NULL)
        .put("focused_view_class", focused?.javaClass?.name ?: JSONObject.NULL)
        .put("views", JSONArray().apply {
            onboardingViewDescendants(popup).forEach { view ->
                val location = IntArray(2).also(view::getLocationOnScreen)
                put(JSONObject().put("identity", System.identityHashCode(view)).put("class", view.javaClass.name)
                    .put("alpha", view.alpha.toDouble()).put("scale_x", view.scaleX.toDouble())
                    .put("scale_y", view.scaleY.toDouble()).put("focused", view.isFocused)
                    .put("screen_x", location[0]).put("screen_y", location[1])
                    .put("width", view.width).put("height", view.height))
            }
        })
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
