package app.smallthingz.reverb

import android.Manifest
import android.animation.ValueAnimator
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ComposeViewContext
import androidx.compose.ui.platform.ExperimentalComposeViewContextApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** QA-owned files only. Every storage mutation is the same visible action in the released screen. */
internal fun verifyNativeLibraryTransactions(instrumentation: Instrumentation, report: StringBuilder) {
    check(Build.VERSION.SDK_INT >= 31) // Exact current physical-phone fixture; no software fallback.
    val context = instrumentation.targetContext
    requireTransactionIdentity(context)
    val directory = File(context.filesDir, "native-library-transactions-" + System.currentTimeMillis())
    check(directory.mkdir())
    val preferences = getRecorderPreferences(context).all.toMap()
    val feedback = AppFeedbackCenter.events.value.toList()
    check(recordingMutations.activeIds.value.isEmpty())
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "eee4b6985bb0ada6b8127a49ea4925055afe46bb8ea75cb067780c02e4503489")
        .put("scope", "One Light/LTR menu/Info, Rename raw-newline draft and valid rename, consecutive Delete/Undo, real Undo expiry per renderer; QA-owned MediaStore catalog")
        .put("limits", "Semantic/View actions, not physical keyboard/IME or full MediaPlayer playback; no Share, Trim, import, permissions or production store")
        .put("passed", false)
    val cases = JSONArray()
    result.put("cases", cases)
    val windowTransitions = JSONArray().also { result.put("window_transitions", it) }
    var host: NativeParityActivity? = null
    var board: TransactionBoard? = null
    var fixtures: TransactionFixtures? = null
    var primary: Throwable? = null
    val captures = linkedMapOf<String, MutableMap<String, File>>()
    fun closeBoard() {
        val retired = board ?: return
        board = null
        var failure: Throwable? = null
        try { nativeParityOnMain(instrumentation) { retired.close() } } catch (error: Throwable) { failure = error }
        try { retired.awaitClosed() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        failure?.let { throw it }
    }
    fun recordCase(renderer: String, name: String, body: (JSONObject) -> Unit) {
        val entry = JSONObject().put("renderer", renderer).put("name", name).put("passed", false)
        cases.put(entry)
        var failure: Throwable? = null
        try { body(entry); entry.put("passed", true) }
        catch (error: Throwable) { failure = error; entry.put("error", android.util.Log.getStackTraceString(error)); throw error }
        finally { transactionWrite(directory, result, failure) }
    }
    try {
        transactionWrite(directory, result, null) // Durable false before any route/operation is mounted.
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        for (compose in listOf(true, false)) {
            val renderer = if (compose) "compose" else "native"
            val ledger = TransactionFixtures(context, directory, renderer)
            fixtures = ledger
            ledger.admit()
            val records = ledger.create()
            val current = nativeParityOnMain(instrumentation) {
                TransactionBoard(activity, compose, records, windowTransitions) { board = it }
            }
            current.awaitReady(instrumentation, records.map { it.id }.toSet())
            fun capture(name: String, settle: Boolean = true) {
                val file = current.capture(instrumentation, directory, "$renderer-$name", settle)
                captures.getOrPut(name) { linkedMapOf() }[renderer] = file
            }
            recordCase(renderer, "menu-info") { entry ->
                current.openMenu(instrumentation, records[0].displayName)
                val expected = listOf(R.string.rename_recording, R.string.recording_info,
                    R.string.share_recording, R.string.trim_recording, R.string.delete_recording, R.string.multi_select)
                    .map(context::getString)
                check(current.clickLabels(instrumentation) == expected) { "Menu label/order/enabled mismatch" }
                capture("menu")
                current.click(instrumentation, context.getString(R.string.recording_info))
                current.awaitText(instrumentation, context.getString(R.string.recording_details_started))
                val labels = listOf(R.string.recording_details_started, R.string.recording_details_duration,
                    R.string.recording_details_size, R.string.recording_details_codec,
                    R.string.recording_details_mime, R.string.recording_details_storage,
                    R.string.recording_details_location).map(context::getString)
                val actual = current.textLabels(instrumentation)
                check(labels.all(actual::contains))
                check(records[0].displayName in actual)
                capture("info")
                current.click(instrumentation, context.getString(R.string.close))
                current.awaitActivity(instrumentation)
                ledger.requireUnchanged()
                entry.put("menu_labels", JSONArray(expected)).put("info_labels", JSONArray(labels))
            }
            recordCase(renderer, "rename-draft-and-commit") { entry ->
                current.openMenu(instrumentation, records[0].displayName)
                current.click(instrumentation, context.getString(R.string.rename_recording))
                current.awaitEditor(instrumentation)
                check(current.raw(instrumentation) == records[0].displayName.substringBeforeLast('.'))
                capture("rename-initial")
                // This is the existing invalid-draft case, also retaining the source raw-newline contract.
                current.setRaw(instrumentation, "bad\nname")
                current.click(instrumentation, context.getString(R.string.rename_recording))
                current.awaitText(instrumentation, context.getString(R.string.rename_recording_illegal_chars))
                check(current.raw(instrumentation) == "bad\nname")
                capture("rename-invalid-newline")
                current.click(instrumentation, context.getString(R.string.cancel))
                current.awaitActivity(instrumentation)
                ledger.requireUnchanged()
                current.openMenu(instrumentation, records[0].displayName)
                current.click(instrumentation, context.getString(R.string.rename_recording))
                current.awaitEditor(instrumentation)
                check(current.raw(instrumentation) == records[0].displayName.substringBeforeLast('.')) {
                    "Cancelled Rename draft leaked into reopened editor"
                }
                current.setRaw(instrumentation, "Library transaction renamed")
                current.click(instrumentation, context.getString(R.string.rename_recording))
                current.awaitActivity(instrumentation)
                val renamed = ledger.acceptRename(records[0], "Library transaction renamed.wav")
                current.awaitVisible(instrumentation, ledger.records().map { it.id }.toSet())
                capture("renamed")
                entry.put("renamed_id", renamed.id).put("pcm_and_object_identity_preserved", true)
            }
            recordCase(renderer, "consecutive-delete-aggregate-undo") { entry ->
                val before = ledger.records()
                val first = before[0]
                val second = before[1]
                current.delete(instrumentation, first.displayName)
                current.awaitText(instrumentation, context.getString(R.string.recording_deleted))
                val firstUndoObservedAt = SystemClock.uptimeMillis()
                // The second menu and delete must work while the first Undo notice is still present.
                current.openMenu(instrumentation, second.displayName)
                current.click(instrumentation, context.getString(R.string.delete_recording))
                val message = context.resources.getQuantityString(R.plurals.recordings_deleted, 2, 2)
                current.awaitText(instrumentation, message)
                val aggregateObservedAt = SystemClock.uptimeMillis()
                check(aggregateObservedAt - firstUndoObservedAt < current.undoTimeout) {
                    "The first real Undo window elapsed before the second deletion was admitted"
                }
                capture("aggregate-undo", settle = false)
                // Staged deletion has not changed either owned file or catalog row.
                ledger.requireUnchanged()
                current.click(instrumentation, context.getString(R.string.undo))
                current.awaitVisible(instrumentation, before.map { it.id }.toSet())
                ledger.requireUnchanged()
                capture("undo-restored")
                entry.put("first_notice_uptime_ms", firstUndoObservedAt)
                    .put("aggregate_notice_uptime_ms", aggregateObservedAt)
                    .put("actual_timeout_ms", current.undoTimeout).put("aggregate_message", message)
            }
            recordCase(renderer, "consecutive-delete-real-expiry") { entry ->
                val before = ledger.records()
                current.delete(instrumentation, before[0].displayName)
                current.awaitText(instrumentation, context.getString(R.string.recording_deleted))
                current.openMenu(instrumentation, before[1].displayName)
                val beforeSecondDelete = SystemClock.uptimeMillis()
                current.click(instrumentation, context.getString(R.string.delete_recording))
                val message = context.resources.getQuantityString(R.plurals.recordings_deleted, 2, 2)
                current.awaitText(instrumentation, message)
                ledger.requireUnchanged()
                val noticeObservedAt = SystemClock.uptimeMillis()
                current.await(instrumentation, "real Undo expiry", current.undoTimeout + 15_000L) {
                    !current.hasText(message) && current.visibleIds.isEmpty()
                }
                // listKnown waits for accepted background deletion; verify exact owned identities only.
                val deletionDeadline = SystemClock.uptimeMillis() + 15_000L
                while (!ledger.isDeleted(before)) {
                    check(SystemClock.uptimeMillis() < deletionDeadline) { "Accepted QA deletion did not settle" }
                    current.waitFrame(instrumentation)
                }
                ledger.acceptDeleted(before)
                val observedCompleteAt = SystemClock.uptimeMillis()
                check(observedCompleteAt - beforeSecondDelete >= current.undoTimeout) {
                    "Deletion completed before its actual accessibility-recommended deadline"
                }
                capture("expired-empty")
                entry.put("second_delete_before_uptime_ms", beforeSecondDelete)
                    .put("notice_observed_uptime_ms", noticeObservedAt)
                    .put("terminal_observed_uptime_ms", observedCompleteAt)
                    .put("actual_timeout_ms", current.undoTimeout)
                    .put("files_and_catalog_absent", true)
            }
            closeBoard()
            ledger.close()
            fixtures = null
            check(getRecorderPreferences(context).all == preferences)
            check(AppFeedbackCenter.events.value == feedback) { "Unexpected passive feedback; preserved" }
            check(recordingMutations.activeIds.value.isEmpty())
        }
        val comparisons = JSONArray()
        result.put("comparisons", comparisons)
        for ((name, files) in captures) {
            check(files.keys == setOf("compose", "native"))
            val source = checkNotNull(BitmapFactory.decodeFile(files.getValue("compose").absolutePath))
            try {
                val native = checkNotNull(BitmapFactory.decodeFile(files.getValue("native").absolutePath))
                try {
                    val diff = compareNativeParityPixels(source, native)
                    comparisons.put(JSONObject().put("name", name).put("difference", diff))
                    check(diff.getLong("different_pixels") == 0L) { "Library transaction raster mismatch: $name" }
                } finally { native.recycle() }
            } finally { source.recycle() }
        }
        check(cases.length() == 8 && captures.size == 8)
    } catch (error: Throwable) {
        primary = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
    } finally {
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
            }
        }
        cleanup(::closeBoard)
        cleanup { fixtures?.close(); fixtures = null }
        cleanup { nativeParityOnMain(instrumentation) { host?.finish() } }
        cleanup { check(getRecorderPreferences(context).all == preferences) { "QA preferences changed" } }
        cleanup { check(AppFeedbackCenter.events.value == feedback) { "QA feedback baseline changed; preserved" } }
        cleanup { check(recordingMutations.activeIds.value.isEmpty()) { "QA operation remains active" } }
        result.put("passed", primary == null).put("completed_flow_entries", cases.length())
        primary?.let { result.put("terminal_error", android.util.Log.getStackTraceString(it)) }
        var evidenceWritten = false
        try { transactionWrite(directory, result, null); evidenceWritten = true }
        catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
            result.put("passed", false).put("terminal_error", checkNotNull(primary).stackTraceToString())
            try { transactionWrite(directory, result, null); evidenceWritten = true }
            catch (secondary: Throwable) { if (primary !== secondary) primary!!.addSuppressed(secondary) }
        }
        report.append(if (evidenceWritten) "PARITY_EVIDENCE " else "PARITY_EVIDENCE_WRITE_FAILED ")
            .append(directory.name).append("/result.json\n")
    }
    primary?.let { throw it }
    report.append("PASS native_library_transactions flows=8 exact_raster_scenes=8\n")
}

private class TransactionLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    fun close() { registry.currentState = Lifecycle.State.DESTROYED }
}

@OptIn(ExperimentalComposeViewContextApi::class)
private class TransactionBoard(
    private val host: NativeParityActivity,
    private val compose: Boolean,
    private val records: List<RecordingEntity>,
    private val windowTransitions: JSONArray,
    onOwned: (TransactionBoard) -> Unit,
) : AutoCloseable {
    private val configuration = Configuration(host.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    }
    // Keep the actual Activity base for ordinary dialog window tokens while overriding resources.
    private val context = ContextThemeWrapper(host, 0).apply { applyOverrideConfiguration(configuration) }
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val colors = dynamicLightColorScheme(context)
    private val environment = ReverbViewEnvironment(density, createFontFamilyResolver(context),
        ReverbPalette.resolve(context, false))
    private val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
    private val lifecycle = TransactionLifecycle()
    private val clockHost = FrameLayout(context)
    private val reference = ComposeView(context)
    private val runtime = NativeAboutWindowRuntime()
    private val asyncFailure = AtomicReference<Throwable?>(null)
    private val scope = CoroutineScope(runtime.scope.coroutineContext + CoroutineExceptionHandler { _, error ->
        if (!asyncFailure.compareAndSet(null, error)) {
            asyncFailure.get()?.let { first -> if (first !== error) first.addSuppressed(error) }
        }
    })
    private val recomposer = Recomposer(scope.coroutineContext)
    private var recomposerJob: Job? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) runtime.setStarted(true)
        if (event == Lifecycle.Event.ON_STOP) runtime.setStarted(false)
    }
    private var nativeScope: CoroutineScope? = null
    private var native: ReverbLibraryScreenContent? = null
    private var closed = false
    private var pendingWindowTransition: NativeLibraryWindowTransition? = null
    var visibleIds: Set<String> = emptySet()
        private set
    private val accessibility = NativeActivityAccessibilityManager(context)
    val undoTimeout = recommendedFeedbackTimeoutMillis(accessibility, 4_500L, false, true, true)
    init {
        try {
            onOwned(this) // Caller owns disposal/join even when construction later fails.
            check(undoTimeout in 4_500L..15_000L) {
                "Actual accessibility timeout exceeds finite fixture allowance; expiry remains unqualified"
            }
            host.fixtureRoot.removeAllViews()
            host.fixtureRoot.background = ReverbNoiseDrawable(context.resources, environment.palette.surface)
            clockHost.isForceDarkAllowed = false
            clockHost.layoutDirection = View.LAYOUT_DIRECTION_LTR
            reference.layoutDirection = View.LAYOUT_DIRECTION_LTR
            host.fixtureRoot.addView(clockHost, FrameLayout.LayoutParams(-1, -1))
            host.lifecycle.addObserver(lifecycleObserver)
            runtime.setStarted(host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            val composition = ComposeViewContext(clockHost, recomposer)
            recomposerJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }
            reference.setContent {
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalResources provides context.resources, LocalDensity provides density,
                    LocalLayoutDirection provides LayoutDirection.Ltr, LocalLifecycleOwner provides lifecycle,
                    LocalOnBackPressedDispatcherOwner provides host,
                ) {
                    MaterialTheme(colorScheme = colors, typography = typography) {
                        if (compose) ReleasedLibraryFilesScreen(
                            modifier = Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing),
                            active = true, initialRecordings = records,
                            onVisibleRecordingsChanged = { visibleIds = it.map(RecordingEntity::id).toSet() },
                        )
                    }
                }
            }
            reference.createComposition(composition)
            clockHost.addView(reference, FrameLayout.LayoutParams(-1, -1))
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }
    fun awaitReady(i: Instrumentation, ids: Set<String>) {
        if (!compose) nativeParityOnMain(i) {
            checkAsync()
            val owned = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            nativeScope = owned
            val callbacks = NativeLibraryCallbacks(onVisibleRecordingsChanged = {
                visibleIds = it.map(RecordingEntity::id).toSet()
            })
            val input = NativeLibraryScreenInput(NativeLibraryInput(true, records, callbacks),
                false, true, LibraryScaffoldInsets(), {}, {}, {}, {})
            val content = ReverbLibraryScreenContent(context, environment, input, lifecycle,
                host.onBackPressedDispatcher, owned, checkNotNull(owned.coroutineContext[MonotonicFrameClock]),
                ValueAnimator::getDurationScale, accessibility, host.fixtureRoot, transactionNoNested, {}, {})
            native = content // Retain the owner before any attachment callback can fail.
            content.layoutDirection = View.LAYOUT_DIRECTION_LTR
            host.fixtureRoot.addView(content, FrameLayout.LayoutParams(-1, -1))
            clockHost.visibility = View.GONE
        }
        awaitVisible(i, ids)
    }
    fun awaitVisible(i: Instrumentation, ids: Set<String>) = await(i, "visible catalog") {
        visibleIds == ids && (compose || native?.currentState()?.let {
            it.hasLoaded && !it.loadFailed && !it.isRefreshing
        } == true)
    }
    fun awaitActivity(i: Instrumentation) = await(i, "Activity focus after owned popup") {
        focusRoot() === host.window.decorView && !hasEditor()
    }
    fun awaitEditor(i: Instrumentation) = await(i, "single Rename editor") { hasEditor() }
    fun awaitText(i: Instrumentation, text: String) = await(i, "text '$text'") { hasText(text) }
    fun await(i: Instrumentation, label: String, timeout: Long = 15_000L, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (true) {
            val observed = nativeParityOnMain(i) {
                checkAsync()
                val transition = pendingWindowTransition
                val root = if (transition != null) transition.focusedRoot() else { guard(); focusRoot() }
                val readyNow = root != null && run {
                    guard()
                    check(focusRoot() === root)
                    predicate()
                }
                if (readyNow) {
                    transition?.accepted(checkNotNull(root))
                    pendingWindowTransition = null
                }
                root to readyNow
            }
            if (observed.second) return
            check(SystemClock.uptimeMillis() < end) { "Transaction timeout: $label" }
            if (observed.first == null) Thread.sleep(16)
            else awaitNativeParityDraw(i, checkNotNull(observed.first))
        }
    }
    fun waitFrame(i: Instrumentation) {
        val root = nativeParityOnMain(i) { guard(); focusRoot() }
        awaitNativeParityDraw(i, root)
    }
    private fun guard() {
        checkAsync()
        check(!closed && !host.isFinishing && host.fixtureRoot.isShown)
        check(host.fixtureRoot.display?.displayId == 0)
        val root = focusRoot()
        check(root.context.packageName == host.packageName && root.isAttachedToWindow &&
            root.isShown && root.display?.displayId == 0 && root.hasWindowFocus()) {
            "Current physical focus is not this QA route; stop without reclaiming it"
        }
    }
    private fun focusRoot(): View = WindowInspector.getGlobalWindowViews().filter {
        it.isAttachedToWindow && it.isShown && it.display?.displayId == 0 && it.hasWindowFocus()
    }.single()
    private fun nodes(merged: Boolean = true): List<SemanticsNode> =
        transactionViews(focusRoot()).filterIsInstance<ViewRootForTest>().flatMap {
            it.semanticsOwner.getAllSemanticsNodes(merged)
        }
    private fun textOf(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    private fun nativeParagraphs() = transactionViews(focusRoot()).filterIsInstance<ReverbParagraphView>()
        .filter { it.isShown }
    private fun nativeAction(label: String): View {
        val actions = nativeParagraphs().filter { it.getText().toString() == label }.mapNotNull { leaf ->
            var current: View? = leaf
            while (current != null && !current.isClickable) current = current.parent as? View
            current
        }.distinct()
        return actions.single().also { check(it.isEnabled && it.isShown) }
    }
    fun click(i: Instrumentation, label: String) = nativeParityOnMain(i) {
        guard()
        val transitionEvidence = if (label in listOf(R.string.recording_info, R.string.rename_recording,
                R.string.close, R.string.cancel, R.string.delete_recording).map(context::getString)) {
            beginWindowTransition(label)
        } else null
        transitionEvidence?.put("action_submitted", JSONObject.NULL)
        if (compose) {
            val match = nodes().single { label in textOf(it) &&
                it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            check(match.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
        } else check(nativeAction(label).performClick())
        transitionEvidence?.put("action_submitted", true)
    }
    private fun beginWindowTransition(action: String): JSONObject {
        check(pendingWindowTransition == null) { "Previous owned UI transition has not reached readiness" }
        val evidence = JSONObject().put("renderer", if (compose) "compose" else "native")
            .put("action", action).put("action_submitted", false)
        windowTransitions.put(evidence)
        pendingWindowTransition = NativeLibraryWindowTransition(host, focusRoot(), evidence)
        return evidence
    }
    fun clickLabels(i: Instrumentation): List<String> = nativeParityOnMain(i) {
        guard()
        if (compose) nodes().filter { it.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
            it.config.getOrNull(SemanticsProperties.Disabled) == null }
            .sortedBy { it.boundsInRoot.top }.flatMap(::textOf)
        else nativeParagraphs().filter { leaf ->
            var v: View? = leaf
            while (v != null && !v.isClickable) v = v.parent as? View
            v?.let { it.isEnabled && it.isShown } == true
        }.sortedBy { val p = IntArray(2); it.getLocationOnScreen(p); p[1] }.map { it.getText().toString() }
    }
    fun textLabels(i: Instrumentation) = nativeParityOnMain(i) {
        guard()
        if (compose) nodes(false).flatMap(::textOf) else nativeParagraphs().map { it.getText().toString() }
    }
    fun hasText(text: String): Boolean = if (compose) nodes(false).any { text in textOf(it) }
        else nativeParagraphs().any { it.getText().toString() == text }
    fun openMenu(i: Instrumentation, title: String) {
        awaitActivity(i)
        nativeParityOnMain(i) {
            guard()
            val transitionEvidence = beginWindowTransition("open recording menu")
            transitionEvidence.put("action_submitted", JSONObject.NULL)
            if (compose) {
                val label = context.getString(R.string.open_options)
                val row = nodes().single { title in textOf(it) &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                    it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }
                check(row.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke())
            } else {
                val row = nativeParagraphs().filter { it.getText().toString() == title }.mapNotNull { leaf ->
                    var v: View? = leaf
                    while (v != null && !v.isLongClickable) v = v.parent as? View
                    v
                }.distinct().single()
                check(row.isEnabled && row.isShown && row.performLongClick())
            }
            transitionEvidence.put("action_submitted", true)
        }
        awaitText(i, context.getString(R.string.recording_info))
    }
    fun delete(i: Instrumentation, title: String) {
        openMenu(i, title)
        click(i, context.getString(R.string.delete_recording))
        awaitActivity(i)
    }
    private fun nativeEditor(): View = transactionViews(focusRoot()).filter { view ->
        view.isShown && view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES &&
            view.createAccessibilityNodeInfo()?.let { it.isEditable && it.isEnabled } == true
    }.single()
    private fun hasEditor(): Boolean = if (compose) nodes().count {
        it.config.getOrNull(SemanticsActions.SetText)?.action != null
    } == 1 else transactionViews(focusRoot()).count { view ->
        view.isShown && view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES &&
            view.createAccessibilityNodeInfo()?.let { it.isEditable && it.isEnabled } == true
    } == 1
    fun raw(i: Instrumentation): String = nativeParityOnMain(i) {
        guard()
        if (compose) nodes().single { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            .config.getOrNull(SemanticsProperties.EditableText)!!.text
        else nativeEditor().createAccessibilityNodeInfo().text.toString()
    }
    fun setRaw(i: Instrumentation, raw: String) = nativeParityOnMain(i) {
        guard()
        if (compose) {
            val editor = nodes().single { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            check(editor.config.getOrNull(SemanticsActions.SetText)!!.action!!.invoke(AnnotatedString(raw)))
        } else check(nativeEditor().performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, raw) }))
    }
    fun capture(i: Instrumentation, evidence: File, name: String, settle: Boolean): File {
        if (settle) {
            val duration = (900f * ValueAnimator.getDurationScale().coerceAtLeast(1f)).toLong()
            check(duration <= 15_000L)
            val until = SystemClock.uptimeMillis() + duration
            while (SystemClock.uptimeMillis() < until) {
                val root = nativeParityOnMain(i) { guard(); focusRoot() }
                awaitNativeParityDraw(i, root)
            }
        }
        val root = nativeParityOnMain(i) { guard(); focusRoot() }
        val bounds = nativeParityOnMain(i) {
            val p = IntArray(2); host.fixtureRoot.getLocationOnScreen(p)
            Rect(p[0], p[1], p[0] + host.fixtureRoot.width, p[1] + host.fixtureRoot.height)
        }
        var first: Bitmap? = null
        try {
            repeat(2) { pass ->
                awaitNativeParityDraw(i, root)
                nativeParityOnMain(i) { guard(); check(focusRoot() === root) }
                // Public UiAutomation screenshot is the default physical display. It captures owned
                // popup/sheet windows too; PixelCopy(Activity.window) would omit those windows.
                val screen = checkNotNull(i.uiAutomation.takeScreenshot())
                val cropped = try {
                    saveNativeParityBitmap(evidence, "$name-$pass-full.png", screen)
                    check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= screen.width && bounds.bottom <= screen.height)
                    val region = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                    if (region === screen) checkNotNull(region.copy(Bitmap.Config.ARGB_8888, false)) else region
                } finally { screen.recycle() }
                var retained = false
                try {
                    nativeParityOnMain(i) { guard(); check(focusRoot() === root) }
                    saveNativeParityBitmap(evidence, "$name-$pass.png", cropped)
                    if (pass == 0) { first = cropped; retained = true }
                    else check(compareNativeParityPixels(checkNotNull(first), cropped).getLong("different_pixels") == 0L) {
                        "Owned transaction scene is not stable: $name"
                    }
                } finally { if (!retained) cropped.recycle() }
            }
            return File(evidence, "$name-0.png")
        } finally { first?.recycle() }
    }
    fun checkAsync() { asyncFailure.get()?.let { throw it } }
    fun awaitClosed() {
        check(closed)
        runBlocking { withTimeout(5_000L) { checkNotNull(runtime.scope.coroutineContext[Job]).join() } }
        checkAsync()
    }
    override fun close() {
        if (closed) return
        closed = true
        var primary: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        } }
        release { native?.close() }
        release { nativeScope?.cancel() }
        release { reference.disposeComposition() }
        release { recomposer.cancel() }
        release { recomposerJob?.cancel() }
        release { host.lifecycle.removeObserver(lifecycleObserver) }
        release { runtime.close() }
        release { lifecycle.close() }
        release { host.fixtureRoot.removeAllViews() }
        release(::checkAsync)
        primary?.let { throw it }
    }
}

private val transactionNoNested = object : NativeVerticalNestedHost {
    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
    override suspend fun preFling(availableY: Float) = 0f
    override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
}
private fun transactionViews(root: View): List<View> = buildList {
    fun visit(view: View) { add(view); if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
    visit(root)
}
private fun transactionWrite(directory: File, result: JSONObject, primary: Throwable?) {
    val file = AtomicFile(File(directory, "result.json"))
    var output: java.io.FileOutputStream? = null
    try {
        val bytes = result.toString(2).toByteArray(Charsets.UTF_8)
        output = file.startWrite()
        output.write(bytes)
        file.finishWrite(output)
        // AtomicFile may log rather than throw on a filesystem failure; verify actual publication.
        check(File(directory, "result.json").readBytes().contentEquals(bytes)) { "Terminal evidence did not publish" }
    } catch (error: Throwable) {
        try { output?.let(file::failWrite) } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
        if (primary == null) throw error
        if (primary !== error) primary.addSuppressed(error)
    }
}
private fun transactionDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun requireTransactionIdentity(context: Context) {
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
    check(context.contentResolver.persistedUriPermissions.isEmpty() && getConfiguredExportTreeUri(context) == null)
}

/** Per-renderer authority ledger; never adopts an entry found by a scan. */
private class TransactionFixtures(
    private val context: Context,
    private val evidence: File,
    private val renderer: String,
) : AutoCloseable {
    private data class Owned(
        var recording: RecordingEntity,
        val wavSha: String,
        val pcmSha: String,
        val payloadOffset: Int,
        val manifest: JSONObject,
        var deleted: Boolean = false,
    )
    private val directory = getSavedRecordingsDirectory(context)
    private val owned = ArrayList<Owned>()
    private val manifest = JSONObject().put("renderer", renderer).put("package", context.packageName)
        .put("fixtures", JSONArray())
    private var creationAttempts = 0
    private var admitted = false
    private var closed = false
    fun admit() {
        requireTransactionIdentity(context)
        val appExternal = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)?.canonicalFile
        check(directory.canonicalFile.parentFile == appExternal ||
            directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        check(directory.mkdirs() || directory.isDirectory)
        check(directory.listFiles()?.isEmpty() == true) { "QA recording folder is not empty; preserve all entries" }
        check(known().isEmpty()) { "QA catalog is not empty; no reset" }
        check(runBlocking { withTimeout(15_000L) { RecordingRepository.refresh(context) } }.isEmpty())
        admitted = true
        manifest.put("baseline_empty", true)
        save()
    }
    fun create(): List<RecordingEntity> {
        check(admitted && owned.isEmpty() && !closed)
        for (index in 0..1) {
            val displayName = "Library transaction ${if (index == 0) "A" else "B"}.wav"
            val started = 1_792_497_600_000L - index * 60_000L
            // A provider insert may succeed before the factory throws; never call that empty.
            manifest.put("creation_attempts", ++creationAttempts)
            save()
            val target = createOutputTarget(context, displayName, "audio/wav", started, StagingOutputKind.COPY)
            val entry = JSONObject().put("id", target.id).put("uri", target.uri?.toString()).put("complete", false)
                .put("storage_type", target.storageType.name).put("directory_id", target.directoryId)
                .put("staging_name", target.stagingDisplayName).put("staging_creation_identity", target.stagingIdentity)
                .put("expected_final_name", displayName)
            manifest.getJSONArray("fixtures").put(entry)
            save() // Creation authority precedes every later assertion/write.
            check(target.storageType == RecordingStorageType.MEDIASTORE && target.staging)
            check(target.uri?.toString() == target.id && target.directoryId == MEDIA_STORE_DIRECTORY_ID)
            check(target.stagingIdentity.isNotBlank())
            val frames = 24_000 + index * 8_000
            val pcm = ByteArray(frames * 2)
            for (sample in 0 until frames) {
                val value = ((sample % 64) - 32) * 512
                pcm[sample * 2] = value.toByte()
                pcm[sample * 2 + 1] = (value shr 8).toByte()
            }
            val writer = WavAudioFileWriter(context, target, 8_000, 1)
            writer.use { it.write(pcm, 0, pcm.size) }
            val verified = verifyWavOutputTargetAndDigest(context, target, writer.totalFileBytesWritten,
                writer.expectedHeaderBytes, writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256)
            val wavSha = verified.digest.sha256.joinToString("") { "%02x".format(it.toInt() and 255) }
            entry.put("verified_staging_identity", verified.providerIdentity).put("verified_bytes", verified.digest.byteCount)
                .put("verified_wav_sha256", wavSha)
            save()
            val published = finalizeOutputTarget(context, target, verified)
            entry.put("published_id", published.id).put("published_uri", published.uri?.toString())
                .put("published_identity", published.publishedIdentity)
            save()
            check(published.storageType == RecordingStorageType.MEDIASTORE && !published.staging)
            check(published.id == target.id && published.uri == target.uri && published.directoryId == target.directoryId)
            check(published.displayName == displayName && published.publishedIdentity.isNotBlank())
            check(sameProviderObjectAcrossMutation(target.stagingIdentity, published.publishedIdentity))
            val raw = buildRecordingEntity(context, published, frames * 1_000L / 8_000L,
                "PCM", writer.totalFileBytesWritten)
            val authority = Owned(raw, wavSha, transactionDigest(pcm),
                writer.payloadOffsetBytes.toInt(), entry)
            owned += authority // Retain the factory-returned URI authority before catalog registration.
            verify(authority)
            entry.put("complete", true).put("id", raw.id).put("identity", raw.fileIdentity)
                .put("wav_sha256", authority.wavSha).put("pcm_sha256", authority.pcmSha)
                .put("payload_offset", authority.payloadOffset).put("bytes", raw.sizeBytes)
            save()
            authority.recording = runBlocking { withTimeout(15_000L) { RecordingRepository.register(context, raw) } }
        }
        requireUnchanged()
        return records()
    }
    fun records(): List<RecordingEntity> = owned.filterNot { it.deleted }.map { it.recording }
    private fun known() = runBlocking { withTimeout(15_000L) { RecordingRepository.listKnown(context) } }
    private fun verify(authority: Owned, recording: RecordingEntity = authority.recording) {
        check(recording.storageType == RecordingStorageType.MEDIASTORE && recording.directoryId == MEDIA_STORE_DIRECTORY_ID)
        check(recording.id == authority.recording.id)
        check(recordingDestructiveIdentityMatches(context, recording))
        val bytes = checkNotNull(openRecordingInputStream(context, recording)).use { it.readBytes() }
        check(bytes.size.toLong() == recording.sizeBytes && transactionDigest(bytes) == authority.wavSha)
        check(transactionDigest(bytes.copyOfRange(authority.payloadOffset, bytes.size)) == authority.pcmSha)
        check(recordingDestructiveIdentityMatches(context, recording)) { "Owned fixture changed during content read" }
    }
    fun requireUnchanged() {
        val expected = records()
        val actual = known()
        check(actual.map { it.id }.toSet() == expected.map { it.id }.toSet())
        for (authority in owned.filterNot { it.deleted }) {
            val catalog = actual.single { it.id == authority.recording.id }
            check(catalog.fileIdentity == authority.recording.fileIdentity && catalog.sizeBytes == authority.recording.sizeBytes)
            verify(authority, catalog)
        }
    }
    fun acceptRename(original: RecordingEntity, expectedName: String): RecordingEntity {
        val authority = owned.single { !it.deleted && it.recording.id == original.id }
        val current = known()
        val renamed = current.single { it.id == original.id && it.displayName == expectedName }
        check(sameProviderObjectAcrossMutation(original.fileIdentity, renamed.fileIdentity))
        verify(authority, renamed)
        authority.recording = renamed
        authority.manifest.put("rename_from", original.displayName).put("rename_from_identity", original.fileIdentity)
            .put("renamed_name", renamed.displayName).put("id", renamed.id)
            .put("identity", renamed.fileIdentity).put("same_object_pcm_preserved", true)
        save()
        requireUnchanged()
        return renamed
    }
    fun isDeleted(records: List<RecordingEntity>): Boolean {
        val current = known()
        return records.all { recording -> recordingAssetState(context, recording) == RecordingAssetState.MISSING &&
            current.none { it.id == recording.id } }
    }
    fun acceptDeleted(records: List<RecordingEntity>) {
        val current = known()
        for (recording in records) {
            val authority = owned.single { !it.deleted && it.recording.id == recording.id }
            check(recordingAssetState(context, recording) == RecordingAssetState.MISSING && current.none { it.id == recording.id })
            authority.deleted = true
            authority.manifest.put("deleted_by_real_undo_expiry", true)
        }
        save()
        check(current.map { it.id }.toSet() == this.records().map { it.id }.toSet())
        check(directory.listFiles()?.isEmpty() == true)
    }
    override fun close() {
        if (closed) return
        closed = true
        var primary: Throwable? = null
        fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        } }
        for (authority in owned.asReversed()) cleanup {
            if (!authority.deleted) {
                val recording = authority.recording
                val state = recordingAssetState(context, recording)
                if (state == RecordingAssetState.MISSING) {
                    // A delivered delete may have completed before an earlier assertion failed.
                    // Record the terminal absence; never replace the earlier failed test result.
                    check(known().none { it.id == recording.id })
                    authority.manifest.put("absent_at_cleanup", true)
                } else {
                    check(state == RecordingAssetState.PRESENT) { "Owned fixture availability is unknown; preserved" }
                    verify(authority)
                    check(runBlocking { withTimeout(15_000L) { RecordingRepository.delete(context, recording) } })
                    check(recordingAssetState(context, recording) == RecordingAssetState.MISSING)
                    check(known().none { it.id == recording.id })
                    authority.manifest.put("deleted_by_cleanup", true)
                }
                authority.deleted = true
            }
        }
        if (admitted) cleanup {
            check(known().isEmpty())
            check(directory.listFiles()?.isEmpty() == true) { "Uncertain/incomplete QA file remains; preserved" }
            check(creationAttempts == owned.size && manifest.getJSONArray("fixtures").length() == owned.size &&
                owned.all { it.deleted }) {
                "Uncertain/incomplete QA output remains; retained in creation ledger"
            }
            manifest.put("cleanup_verified_empty", true)
        }
        primary?.let { manifest.put("cleanup_error", android.util.Log.getStackTraceString(it)) }
        cleanup { save() }
        primary?.let { throw it }
    }
    private fun save() = File(evidence, "$renderer-fixtures.json").writeText(manifest.toString(2))
}
