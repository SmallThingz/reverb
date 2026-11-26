package app.smallthingz.reverb

import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource

import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.reverb.ReverbLibraryPlayerView as LibraryPlayerView
import smallthingz.ui.reverb.settings.ReverbAudioActions
import smallthingz.ui.reverb.settings.ReverbAudioChoices
import smallthingz.ui.reverb.settings.ReverbAudioOptions
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.util.closePreservingPrimaryFailure
import android.Manifest
import android.animation.ValueAnimator
import android.app.Application
import android.app.Instrumentation
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import smallthingz.ui.runtime.MonotonicFrameClock
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** QA-owned files only. Every storage mutation is the same visible action in the released screen. */
internal fun verifyNativeLibraryTransactions(instrumentation: Instrumentation, report: StringBuilder,
                                             menuOnly: Boolean = false, commonGrainInput: Boolean = false) {
    check(Build.VERSION.SDK_INT >= 31) // Exact current physical-phone fixture; no software fallback.
    val context = instrumentation.targetContext
    requireTransactionIdentity(context)
    check(!commonGrainInput || menuOnly)
    val selector = if (commonGrainInput) "native_library_menu_common_grain"
        else if (menuOnly) "native_library_menu" else "native_library_transactions"
    val directory = File(context.filesDir,
        (if (commonGrainInput) "native-library-menu-common-grain-"
            else if (menuOnly) "native-library-menu-" else "native-library-transactions-") + System.currentTimeMillis())
    check(directory.mkdir())
    val preferences = getRecorderPreferences(context).all.toMap()
    val feedback = AppFeedbackCenter.events.value.toList()
    check(recordingMutations.activeIds.value.isEmpty())
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "eee4b6985bb0ada6b8127a49ea4925055afe46bb8ea75cb067780c02e4503489")
        .put("scope", "One Light/LTR menu/Info, Rename raw-newline draft and valid rename, consecutive Delete/Undo, real Undo expiry per renderer; QA-owned MediaStore catalog")
        .put("limits", "Semantic/View actions, not physical keyboard/IME or full MediaPlayer playback; no Share, Trim, import, permissions or production store")
        .put("passed", false)
    result.put("selector", selector)
    if (menuOnly) result.put("scope", "One Light/LTR landscape/touch recording menu per renderer; same QA-owned MediaStore fixtures, no recording action; native typed audio-choice dropdown selection")
    if (commonGrainInput) result.put("reference_input_override",
        "Supplemental common-grain input only; original raw menu parity remains separate and unchanged")
    var commonGrain: NativeParityCommonGrainSession? = null
    val cases = JSONArray()
    result.put("cases", cases)
    val windowTransitions = JSONArray().also { result.put("window_transitions", it) }
    var host: NativeParityActivity? = null
    var board: TransactionBoard? = null
    var fixtures: TransactionFixtures? = null
    var primary: Throwable? = null
    val captures = linkedMapOf<String, MutableMap<String, File>>()
    val captureRegions = JSONObject().also { result.put("capture_regions", it) }
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
        catch (error: Throwable) {
            failure = error
            entry.put("failure_inventory", board?.failureInventory(instrumentation) ?: JSONObject.NULL)
            entry.put("error", android.util.Log.getStackTraceString(error))
            throw error
        }
        finally { transactionWrite(directory, result, failure) }
    }
    try {
        transactionWrite(directory, result, null) // Durable false before any route/operation is mounted.
        if (commonGrainInput) commonGrain = NativeParityCommonGrainSession(expectedReferenceLoads = 1)
        if (menuOnly) recoverEmptyLibraryFixtureJournal(context, result) { transactionWrite(directory, result, null) }
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        if (menuOnly) {
            requireNativeParityHost(instrumentation, activity)
            result.put("landscape_before_touch_mode", nativeParityOnMain(instrumentation) {
                transactionLandscapeMenuState(activity, activity.window.decorView, requireTouchMode = false)
            })
            requireNativeParityHost(instrumentation, activity)
            // Public test input setup, only while this physical-display QA Activity owns focus.
            // An unsupported/denied call fails this probe; there is no settings or permission fallback.
            instrumentation.setInTouchMode(true)
            awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
            requireNativeParityHost(instrumentation, activity)
            result.put("landscape_after_touch_mode", nativeParityOnMain(instrumentation) {
                transactionLandscapeMenuState(activity, activity.window.decorView)
            })
        }
        for (compose in listOf(true, false)) {
            val renderer = if (compose) "compose" else "native"
            if (menuOnly) {
                requireNativeParityHost(instrumentation, activity)
                result.put("${renderer}_landscape_before_fixtures", nativeParityOnMain(instrumentation) {
                    transactionLandscapeMenuState(activity, activity.window.decorView)
                })
            }
            val ledger = TransactionFixtures(context, directory, renderer)
            fixtures = ledger
            ledger.admit()
            val records = ledger.create()
            val current = nativeParityOnMain(instrumentation) {
                TransactionBoard(activity, compose, records, windowTransitions, commonGrain) { board = it }
            }
            current.awaitReady(instrumentation, records.map { it.id }.toSet())
            fun capture(name: String, settle: Boolean = true) {
                val scene = captureRegions.optJSONObject(name) ?: JSONObject().also { captureRegions.put(name, it) }
                val observation = JSONObject().also { scene.put(renderer, it) }
                val file = current.capture(instrumentation, directory, "$renderer-$name", settle,
                    observation, observeMenu = name == "menu", requireLandscapeTouch = menuOnly)
                captures.getOrPut(name) { linkedMapOf() }[renderer] = file
            }
            if (menuOnly) {
                recordCase(renderer, "menu") { entry ->
                    val timing = JSONObject().also { entry.put("menu_open_timing", it) }
                    current.openMenu(instrumentation, records[0].displayName, timing)
                    val expected = listOf(R.string.rename_recording, R.string.recording_info,
                        R.string.share_recording, R.string.trim_recording, R.string.delete_recording, R.string.multi_select)
                        .map(context::getString)
                    val actual = current.clickLabels(instrumentation)
                    entry.put("menu_labels", JSONArray(expected)).put("actual_menu_labels", JSONArray(actual))
                    check(actual == expected) { "Menu label/order/enabled mismatch" }
                    capture("menu")
                    ledger.requireUnchanged()
                }
                if (!compose) recordCase(renderer, "audio-choice") { entry ->
                    current.verifyAudioChoiceMenu(instrumentation, directory, entry)
                    ledger.requireUnchanged()
                }
                closeBoard()
                ledger.close()
                fixtures = null
                check(getRecorderPreferences(context).all == preferences)
                check(AppFeedbackCenter.events.value == feedback) { "Unexpected passive feedback; preserved" }
                check(recordingMutations.activeIds.value.isEmpty())
                continue
            }
            recordCase(renderer, "menu-info") { entry ->
                current.openMenu(instrumentation, records[0].displayName)
                val expected = listOf(R.string.rename_recording, R.string.recording_info,
                    R.string.share_recording, R.string.trim_recording, R.string.delete_recording, R.string.multi_select)
                    .map(context::getString)
                val actualMenu = current.clickLabels(instrumentation)
                entry.put("menu_labels", JSONArray(expected)).put("actual_menu_labels", JSONArray(actualMenu))
                check(actualMenu == expected) { "Menu label/order/enabled mismatch" }
                capture("menu")
                current.click(instrumentation, context.getString(R.string.recording_info))
                val labels = listOf(R.string.recording_details_started, R.string.recording_details_duration,
                    R.string.recording_details_size, R.string.recording_details_codec,
                    R.string.recording_details_mime, R.string.recording_details_storage,
                    R.string.recording_details_location).map { context.getString(it).removeSuffix(":") }
                // Both released Info rows and the native body render labels without their resource colon.
                entry.put("info_labels", JSONArray(labels))
                current.awaitText(instrumentation, labels.first())
                val actual = current.textLabels(instrumentation)
                entry.put("actual_info_labels", JSONArray(actual))
                check(labels.all(actual::contains))
                check(records[0].displayName in actual)
                capture("info")
                current.click(instrumentation, context.getString(R.string.close))
                current.awaitActivity(instrumentation)
                ledger.requireUnchanged()
            }
            recordCase(renderer, "rename-draft-and-commit") { entry ->
                current.openMenu(instrumentation, records[0].displayName)
                current.click(instrumentation, context.getString(R.string.rename_recording))
                current.awaitEditor(instrumentation)
                val initialRaw = current.raw(instrumentation)
                entry.put("initial_raw", initialRaw)
                check(initialRaw == records[0].displayName.substringBeforeLast('.'))
                capture("rename-initial")
                if (!compose) {
                    // Intentional inherited-bug correction, not a released-renderer parity claim:
                    // Android ACTION_SET_TEXT accepts CharSequence, including styled text.
                    val accessibilityDraft = android.text.SpannableString("Accessibility draft \uD83D\uDE03")
                    val argument = current.setNativeCharSequence(instrumentation, accessibilityDraft)
                    current.awaitRaw(instrumentation, accessibilityDraft.toString())
                    entry.put("native_char_sequence_action", argument
                        .put("accepted_raw", current.raw(instrumentation)))
                    // Retain an explicit ordinary String regression after the non-String value.
                    val ordinaryDraft = "Ordinary String draft"
                    current.setRaw(instrumentation, ordinaryDraft)
                    current.awaitRaw(instrumentation, ordinaryDraft)
                    entry.put("native_string_action_after_char_sequence", current.raw(instrumentation))
                }
                // Newlines are valid raw input and normalize to spaces on commit. Slash is illegal;
                // combine both to exercise the error state without committing an unintended rename.
                val invalidDraft = "bad/\nname"
                current.setRaw(instrumentation, invalidDraft)
                current.awaitRaw(instrumentation, invalidDraft)
                entry.put("invalid_raw_before_submit", current.raw(instrumentation))
                current.click(instrumentation, context.getString(R.string.rename_recording))
                current.awaitText(instrumentation, context.getString(R.string.rename_recording_illegal_chars))
                val invalidRaw = current.raw(instrumentation)
                entry.put("invalid_raw", invalidRaw)
                check(invalidRaw == invalidDraft)
                capture("rename-invalid-newline")
                current.click(instrumentation, context.getString(R.string.cancel))
                current.awaitActivity(instrumentation)
                ledger.requireUnchanged()
                current.openMenu(instrumentation, records[0].displayName)
                current.click(instrumentation, context.getString(R.string.rename_recording))
                current.awaitEditor(instrumentation)
                val reopenedRaw = current.raw(instrumentation)
                entry.put("reopened_raw", reopenedRaw)
                check(reopenedRaw == records[0].displayName.substringBeforeLast('.')) {
                    "Cancelled Rename draft leaked into reopened editor"
                }
                current.setRaw(instrumentation, "Library transaction renamed")
                current.awaitRaw(instrumentation, "Library transaction renamed")
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
        commonGrain?.verifyReferenceLoads()
        commonGrain?.verifyNativeReady()
        val comparisons = JSONArray()
        result.put("comparisons", comparisons)
        for ((name, files) in captures) {
            check(files.keys == setOf("compose", "native"))
            val regions = captureRegions.getJSONObject(name)
            check(regions.getJSONObject("compose").getJSONObject("region").toString() ==
                regions.getJSONObject("native").getJSONObject("region").toString()) {
                "Released/native app viewport geometry changed: $name"
            }
            val fullSource = checkNotNull(BitmapFactory.decodeFile(File(directory, "compose-$name-0.png").absolutePath))
            val fullDiff = try {
                val fullNative = checkNotNull(BitmapFactory.decodeFile(File(directory, "native-$name-0.png").absolutePath))
                try { compareNativeParityPixels(fullSource, fullNative) } finally { fullNative.recycle() }
            } finally { fullSource.recycle() }
            val source = checkNotNull(BitmapFactory.decodeFile(files.getValue("compose").absolutePath))
            try {
                val native = checkNotNull(BitmapFactory.decodeFile(files.getValue("native").absolutePath))
                try {
                    val diff = compareNativeParityPixels(source, native)
                    comparisons.put(JSONObject().put("name", name).put("difference", diff)
                        .put("full_fixture_difference", fullDiff)
                        .put("scope", "Exact app viewport from current system-bar/cutout insets; full raw differences retained separately")
                        .put("outside_viewport_difference_requires_review",
                            fullDiff.getLong("different_pixels") > diff.getLong("different_pixels")))
                    check(diff.getLong("different_pixels") == 0L) { "Library transaction raster mismatch: $name" }
                } finally { native.recycle() }
            } finally { source.recycle() }
        }
        check(cases.length() == (if (menuOnly) 3 else 8) && captures.size == (if (menuOnly) 1 else 8))
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
        cleanup { commonGrain?.let { result.put("common_grain", it.evidence()) } }
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
    report.append(if (menuOnly) "PASS $selector flows=3 exact_app_viewport_scenes=1 audio_choice_cases=1\n"
        else "PASS native_library_transactions flows=8 exact_app_viewport_scenes=8\n")
}

/** Current native menu checks using only media owned by the transaction fixture. */
internal fun verifyNativeLibraryMenuCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    requireTransactionIdentity(context, currentUi = true)
    check(Build.VERSION.SDK_INT >= 31)
    val selector = "native_library_menu_current"
    val directory = File(context.filesDir, "native-library-menu-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val preferences = getRecorderPreferences(context).all.toMap()
    val feedback = AppFeedbackCenter.events.value.toList()
    check(recordingMutations.activeIds.value.isEmpty())
    val transitions = JSONArray()
    val result = JSONObject().put("selector", selector).put("passed", false)
        .put("scope", "Current native recording menu and Settings typed audio choice on the QA window")
        .put("window_transitions", transitions)
    var host: NativeParityActivity? = null
    var board: TransactionBoard? = null
    var ledger: TransactionFixtures? = null
    var failure: Throwable? = null
    fun persist() = transactionWrite(directory, result, failure)
    try {
        persist()
        recoverEmptyLibraryFixtureJournal(context, result, ::persist)
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        requireNativeParityHost(instrumentation, activity)
        val owned = TransactionFixtures(context, directory, "native", currentUi = true,
            namePrefix = "Reverb QA menu ${java.util.UUID.randomUUID()}").also { ledger = it }
        owned.admit()
        val records = owned.create()
        val perf = ownerPerfCycle.get()
        val current = nativeParityOnMain(instrumentation) {
            TransactionBoard(activity, false, records, transitions, fitCurrentWindowInsets = perf == null) { board = it }
        }
        current.awaitReady(instrumentation, records.map { it.id }.toSet())
        val timing = JSONObject().also { result.put("menu_open_timing", it) }
        perf?.opening(activity)
        current.openMenu(instrumentation, records.first().displayName, timing)
        perf?.openSettled(current.performanceMenuWindow(instrumentation), timing)
        val expected = listOf(R.string.rename_recording, R.string.recording_info, R.string.share_recording,
            R.string.trim_recording, R.string.delete_recording, R.string.multi_select).map(context::getString)
        val actual = current.clickLabels(instrumentation)
        result.put("menu_labels", JSONArray(expected)).put("actual_menu_labels", JSONArray(actual))
        check(actual == expected) { "Menu label/order/enabled mismatch" }
        if (perf == null) {
            val capture = JSONObject().also { result.put("menu_capture", it) }
            current.capture(instrumentation, directory, "native-menu", true, capture, observeMenu = true)
            owned.requireUnchanged()
            val audioChoice = JSONObject().also { result.put("audio_choice", it) }
            current.verifyAudioChoiceMenu(instrumentation, directory, audioChoice, requireLandscapeTouch = false)
            val playerFirstDraw = JSONObject().also { result.put("playing_player_first_draw", it) }
            current.verifyPlayingPlayerFirstDraw(instrumentation, records.first(), playerFirstDraw)
        }
        owned.requireUnchanged()
    } catch (error: Throwable) {
        failure = error
        result.put("failure_inventory", board?.failureInventory(instrumentation) ?: JSONObject.NULL)
    } finally {
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        cleanup { nativeParityOnMain(instrumentation) { board?.close() } }
        cleanup { board?.awaitClosed() }
        cleanup { ledger?.close() }
        cleanup { nativeParityOnMain(instrumentation) { host?.finish() } }
        cleanup { check(getRecorderPreferences(context).all == preferences) }
        cleanup { check(AppFeedbackCenter.events.value == feedback) }
        cleanup { check(recordingMutations.activeIds.value.isEmpty()) }
        result.put("passed", failure == null)
        failure?.let { result.put("terminal_error", it.stackTraceToString()) }
        cleanup(::persist)
        report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    }
    failure?.let { throw it }
    val audioChoices = if (ownerPerfCycle.get() == null) 1 else 0
    report.appendLine("PASS $selector recording_menu_cases=1 audio_choice_cases=$audioChoices")
}

/* Current-only actual Rename sheet; existing transaction ledger owns all fixture media. */
internal fun verifyNativeRenameEditorCurrent(
    instrumentation: Instrumentation, report: StringBuilder, keyboardSessionNonce: String?,
) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    requireTransactionIdentity(context, currentUi = true)
    check(Build.VERSION.SDK_INT >= 31)
    checkNotNull(keyboardSessionNonce)
    val directory = File(context.filesDir, "native-rename-editor-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val result = JSONObject().put("selector", "native_rename_editor_current").put("passed", false)
        .put("expected_cases", 3).put("completed_cases", 0)
        .put("scope", "Actual native Rename sheet/owner and QA-owned MediaStore rename; visible real IME with stage-bound hardware keys")
        .put("not_qualified", JSONArray(listOf("soft-key composition", "other IMEs", "Enter/Done popup-dismiss handshake", "released pixel equality")))
    val cases = JSONArray().also { result.put("cases", it) }
    val preferences = getRecorderPreferences(context).all.toMap()
    val feedback = AppFeedbackCenter.events.value.toList()
    check(recordingMutations.activeIds.value.isEmpty())
    var host: NativeParityActivity? = null
    var board: TransactionBoard? = null
    var ledger: TransactionFixtures? = null
    var windows: NativeParityInteractiveWindows? = null
    var failure: Throwable? = null
    fun persist() = transactionWrite(directory, result, null)
    try {
        persist()
        recoverEmptyLibraryFixtureJournal(context, result, ::persist)
        val windowScope = NativeParityInteractiveWindows.open(instrumentation, result).also { windows = it }
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        windowScope.recordHost(instrumentation, activity)
        val uniquePrefix = "Reverb QA rename " + java.util.UUID.randomUUID().toString()
        result.put("owned_name_prefix", uniquePrefix)
        val owned = TransactionFixtures(context, directory, "native", currentUi = true,
            namePrefix = uniquePrefix).also { ledger = it }
        owned.admit()
        val records = owned.create()
        val initialStem = records[0].displayName.substringBeforeLast('.')
        check(initialStem == "$uniquePrefix A")
        val editedPrefix = "$uniquePrefix "
        val transitions = JSONArray().also { result.put("window_transitions", it) }
        val current = nativeParityOnMain(instrumentation) {
            TransactionBoard(activity, false, records, transitions) { board = it }
        }
        val expectedIds = records.map { it.id }.toSet()
        try {
            current.awaitReady(instrumentation, expectedIds)
            result.put("catalog_ready", current.catalogReadiness(instrumentation, expectedIds))
        } catch (error: Throwable) {
            try {
                result.put("catalog_readiness_failure", current.catalogReadiness(instrumentation, expectedIds))
                persist()
            } catch (evidence: Throwable) { if (evidence !== error) error.addSuppressed(evidence) }
            throw error
        }
        fun capture(name: String) {
            val observation = JSONObject()
            result.put(name, observation)
            current.capture(instrumentation, directory, name, false, observation,
                observeMenu = false, requireLandscapeTouch = false, requireStable = false)
            persist()
        }
        fun scenario(name: String, action: (JSONObject) -> Unit) {
            val entry = JSONObject().put("name", name).put("passed", false)
                .put("keyboard_requests", JSONArray())
            cases.put(entry); persist()
            var primary: Throwable? = null
            try { action(entry); entry.put("passed", true); result.put("completed_cases", cases.length()) }
            catch (error: Throwable) {
                primary = error
                entry.put("failure_inventory", current.failureInventory(instrumentation))
                try {
                    current.requireRenameWindow(instrumentation)
                    capture("rename-failure-$name")
                } catch (evidence: Throwable) {
                    entry.put("failure_capture_error", evidence.stackTraceToString())
                    if (evidence !== error) error.addSuppressed(evidence)
                }
                entry.put("error", error.stackTraceToString()); throw error
            } finally { transactionWrite(directory, result, primary) }
        }
        fun open() {
            current.openMenu(instrumentation, records[0].displayName)
            current.click(instrumentation, context.getString(R.string.rename_recording))
            current.awaitEditor(instrumentation)
        }
        fun key(entry: JSONObject, stage: String, code: String, expected: String) {
            awaitNativeParityKeyboardCommand(instrumentation, directory, keyboardSessionNonce,
                "native", stage, code,
                guardedSnapshot = {
                    current.renameKeyboardSnapshot(instrumentation, windowScope).also {
                        check(it.getJSONObject("observed_target").getBoolean("positive_ime"))
                    }
                },
                onRequest = { entry.getJSONArray("keyboard_requests").put(it); persist() },
                validateObservedAfter = {
                    current.awaitRaw(instrumentation, expected)
                    val after = current.renameKeyboardSnapshot(instrumentation, windowScope)
                    check(after.getJSONObject("observed_target").getBoolean("positive_ime"))
                    entry.put(stage, after)
                })
        }
        scenario("draft-ime") { entry ->
            open()
            check(current.raw(instrumentation) == records[0].displayName.substringBeforeLast('.'))
            current.selectRenameDraft(instrumentation, editedPrefix.length, initialStem.length, entry)
            current.awaitRenameIme(instrumentation, windowScope, entry)
            current.revealRenamePart(instrumentation, "title", entry)
            capture("rename-title-visible")
            current.revealRenamePart(instrumentation, "editor", entry)
            capture("rename-initial-focused")
            key(entry, "rename-draft-r", "KEYCODE_R", editedPrefix + "r")
            key(entry, "rename-draft-e", "KEYCODE_E", editedPrefix + "re")
            key(entry, "rename-draft-delete", "KEYCODE_DEL", editedPrefix + "r")
            capture("rename-edited-focused")
            owned.requireUnchanged()
        }
        scenario("rejected-cancel") { entry ->
            val invalid = uniquePrefix + " bad/\nname"
            current.setRaw(instrumentation, invalid)
            current.awaitRaw(instrumentation, invalid)
            current.revealRenamePart(instrumentation, "actions", entry)
            current.click(instrumentation, context.getString(R.string.rename_recording))
            current.awaitText(instrumentation, context.getString(R.string.rename_recording_illegal_chars))
            current.revealRenamePart(instrumentation, "editor", entry)
            check(current.raw(instrumentation) == invalid)
            capture("rename-rejected")
            owned.requireUnchanged()
            current.revealRenamePart(instrumentation, "actions", entry)
            current.click(instrumentation, context.getString(R.string.cancel))
            current.awaitActivity(instrumentation)
            owned.requireUnchanged()
            open()
            check(current.raw(instrumentation) == records[0].displayName.substringBeforeLast('.'))
            entry.put("cancelled_draft_discarded", true)
        }
        scenario("accepted-rename") { entry ->
            current.selectRenameDraft(instrumentation, initialStem.length, initialStem.length, entry)
            current.awaitRenameIme(instrumentation, windowScope, entry)
            key(entry, "rename-accepted-a", "KEYCODE_A", initialStem + "a")
            current.revealRenamePart(instrumentation, "actions", entry)
            capture("rename-before-commit")
            current.click(instrumentation, context.getString(R.string.rename_recording))
            current.awaitActivity(instrumentation)
            val expectedName = initialStem + "a.wav"
            current.awaitRenamed(instrumentation, records[0].id, expectedName)
            val renamed = owned.awaitAndAcceptRename(records[0], expectedName)
            current.awaitVisible(instrumentation, owned.records().map { it.id }.toSet())
            capture("rename-accepted")
            entry.put("renamed_id", renamed.id).put("pcm_and_object_identity_preserved", true)
        }
    } catch (error: Throwable) { failure = error }
    finally {
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        cleanup { nativeParityOnMain(instrumentation) { board?.close() } }
        cleanup { board?.awaitClosed() }
        cleanup { ledger?.close() }
        cleanup { nativeParityOnMain(instrumentation) { host?.finish() } }
        cleanup { windows?.close() }
        cleanup { check(getRecorderPreferences(context).all == preferences) }
        cleanup { check(AppFeedbackCenter.events.value == feedback) }
        cleanup { check(recordingMutations.activeIds.value.isEmpty()) }
        result.put("passed", failure == null && result.getInt("completed_cases") == 3)
        failure?.let { result.put("terminal_error", it.stackTraceToString()) }
        cleanup(::persist)
        report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    }
    failure?.let { throw it }
    check(result.getBoolean("passed"))
    report.appendLine("PASS native_rename_editor_current cases=3")
}

private class TransactionLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    fun close() { registry.currentState = Lifecycle.State.DESTROYED }
}

@OptIn(ExperimentalComposeViewContextApi::class)
internal class TransactionBoard(
    private val host: NativeParityActivity,
    private val compose: Boolean,
    private val records: List<RecordingEntity>,
    private val windowTransitions: JSONArray,
    private val commonGrain: NativeParityCommonGrainSession? = null,
    private val fitCurrentWindowInsets: Boolean = false,
    private val inlineCurrent: Boolean = false,
    onOwned: (TransactionBoard) -> Unit,
) : AutoCloseable {
    private val configuration = Configuration(host.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    }
    // Keep the actual Activity base for ordinary dialog window tokens while overriding resources.
    private val context = ContextThemeWrapper(host, 0).apply { applyOverrideConfiguration(configuration) }
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val colors = dynamicLightColorScheme(context)
    private val environment = nativeParityEnvironment(context, density,
        ReverbPalette.resolve(context, false))
    private val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
    private val lifecycle = TransactionLifecycle()
    private val clockHost = FrameLayout(context)
    private val reference = ComposeView(context)
    private val runtime = LegacyNativeAboutWindowRuntime()
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
    private var inlineRoot: LibraryNativeFocusRoot? = null
    private var nativeInput: NativeLibraryScreenInput? = null
    private var inlineText: NativeLibraryAccessibilityBinding? = null
    private var inlineScrollEvents: smallthingz.ui.scroll.NativeWindowScrollAccessibilityEvents? = null
    private var inlineSource: NativeMainAccessibility? = null
    private var inlineInsetsPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var inlineInsetsDispatches = 0
    private var inlineInsetsBinds = 0
    private var observedInlineInfoId: String? = null
    var inlineInfoDismissalTransitions = 0
        private set
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
                    LocalResources provides (commonGrain?.referenceResources(context.resources) ?: context.resources), LocalDensity provides density,
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
            val owned = CoroutineScope(scope.coroutineContext.withNativeParityClock() + SupervisorJob(scope.coroutineContext[Job]))
            nativeScope = owned
            val callbacks = NativeLibraryCallbacks(onVisibleRecordingsChanged = {
                visibleIds = it.map(RecordingEntity::id).toSet()
            })
            val initialInsets = if (fitCurrentWindowInsets) {
                residualVisualInsets(checkNotNull(ViewCompat.getRootWindowInsets(host.fixtureRoot)))
            } else Insets.NONE
            var input = NativeLibraryScreenInput(NativeLibraryInput(true, records, callbacks),
                false, true, initialInsets.libraryContent(), {}, {}, {}, {})
            nativeInput = input
            val phaseRoot = if (inlineCurrent) LibraryNativeFocusRoot(context).also {
                inlineRoot = it
                host.fixtureRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
            } else null
            val content = ReverbLibraryScreenContent(context, environment, input, lifecycle,
                host.onBackPressedDispatcher, owned, checkNotNull(owned.coroutineContext[MonotonicFrameClock]),
                ValueAnimator::getDurationScale, accessibility, phaseRoot ?: host.fixtureRoot,
                transactionNoNested, { phaseRoot?.refreshPointerNodes(); inlineText?.refresh() }, { state ->
                    if (inlineCurrent) {
                        val next = state.infoRecording?.id
                        if (observedInlineInfoId != null && next == null) inlineInfoDismissalTransitions++
                        observedInlineInfoId = next
                    }
                })
            native = content // Retain the owner before any attachment callback can fail.
            content.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val layout = FrameLayout.LayoutParams(-1, -1).apply { topMargin = initialInsets.top }
            fun applyFixtureInsets(): Boolean {
                var changed = false
                ViewCompat.getRootWindowInsets(host.fixtureRoot)?.let { rootInsets ->
                    val insets = residualVisualInsets(rootInsets)
                    if (layout.topMargin != insets.top) {
                        layout.topMargin = insets.top
                        content.layoutParams = layout
                        changed = true
                    }
                    val scaffold = insets.libraryContent()
                    if (input.scaffoldInsets != scaffold) {
                        input = input.copy(scaffoldInsets = scaffold)
                        nativeInput = input
                        content.bind(input)
                        if (inlineCurrent) inlineInsetsBinds++
                        changed = true
                    }
                }
                return changed
            }
            if (fitCurrentWindowInsets) ViewCompat.setOnApplyWindowInsetsListener(content) { _, _ ->
                if (inlineCurrent) inlineInsetsDispatches++
                applyFixtureInsets()
                WindowInsetsCompat.CONSUMED
            }
            if (fitCurrentWindowInsets && inlineCurrent) {
                // Like Main, re-evaluate remaining occlusion after pan/resize, even when the
                // raw window inset did not change. Never draw the obsolete viewport geometry.
                inlineInsetsPreDraw = ViewTreeObserver.OnPreDrawListener {
                    closed || !applyFixtureInsets()
                }.also { host.fixtureRoot.viewTreeObserver.addOnPreDrawListener(it) }
            }
            (phaseRoot ?: host.fixtureRoot).addView(content, layout)
            phaseRoot?.bindPointerContent(content, checkNotNull(owned.coroutineContext[MonotonicFrameClock]), owned)
            if (phaseRoot != null) {
                val clips = smallthingz.ui.text.view.NativeTextSemanticsClipPolicy(content::sourceClip)
                val windowText = NativeWindowTextAccessibilityOwner(host.window.decorView, clips)
                val source = NativeMainAccessibility(host.window.decorView, phaseRoot, { listOf(content) },
                    { false }, content::sourceClip, minimumTouch = { view ->
                        if (view is smallthingz.ui.input.phase.NativeClickPhaseHost &&
                            (view !is smallthingz.ui.text.view.NativeMergedRowAccessibilityHost || view.mergesAccessibilityDescendants))
                            48f * environment.textDensity.density else 0f
                    }, inactiveLayer = content::inlineInputExcluded)
                inlineSource = source
                phaseRoot.surfaceAdmission = { !source.inInactiveLayer(it) }
                val events = windowText.openScrollEvents(owned, { phaseRoot.invalidate() }, {})
                inlineScrollEvents = events
                inlineText = NativeLibraryAccessibilityBinding(content, windowText, source, events,
                    source::retainPresentSemanticChildren)
                content.publishInitialPresentation()
                inlineText?.refresh()
            }
            clockHost.visibility = View.GONE
        }
        awaitVisible(i, ids)
    }
    /** Standalone adapter; inline mode supplies Main's IME-aware residual bottom contract. */
    private fun residualVisualInsets(windowInsets: WindowInsetsCompat): Insets {
        val visual = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val decor = host.window.decorView
        val root = host.fixtureRoot
        val origin = IntArray(2).also(root::getLocationInWindow)
        val windowOrigin = IntArray(2).also(decor::getLocationInWindow)
        val x = origin[0] - windowOrigin[0]
        val y = origin[1] - windowOrigin[1]
        var bottom = (y + root.height - decor.height + visual.bottom).coerceAtLeast(0)
        val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        if (inlineCurrent && ime > 0) {
            check(Build.VERSION.SDK_INT >= 30)
            val protectedInset = maxOf(visual.bottom, ime)
            val window = host.windowManager.currentWindowMetrics.bounds
            val screen = IntArray(2).also(root::getLocationOnScreen)
            // The root may already have been resized or panned clear of part of the IME.
            bottom = (screen[1] + root.height - (window.bottom - protectedInset))
                .coerceIn(0, protectedInset)
        }
        return Insets.of(
            (visual.left - x).coerceAtLeast(0),
            (visual.top - y).coerceAtLeast(0),
            (x + root.width - decor.width + visual.right).coerceAtLeast(0),
            bottom,
        )
    }

    /** Observations only: this must not bind or scroll to make the visibility assertions pass. */
    private fun inlineInsetEvidence(): JSONObject {
        check(inlineCurrent)
        val root = host.fixtureRoot
        val content = checkNotNull(native)
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(root))
        val visual = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val residual = residualVisualInsets(insets)
        fun bounds(view: View): JSONArray {
            val xy = IntArray(2).also(view::getLocationOnScreen)
            return JSONArray(listOf(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height))
        }
        val window = host.windowManager.currentWindowMetrics.bounds
        val viewport = transactionViews(content).filterIsInstance<ReverbLibraryListView>()
            .singleOrNull()?.scrollView
        return JSONObject().put("adapter", "inline-only IME residual; window metrics and pre-draw pan/resize")
            .put("inset_dispatches", inlineInsetsDispatches).put("scaffold_rebinds", inlineInsetsBinds)
            .put("window_bounds", JSONArray(listOf(window.left, window.top, window.right, window.bottom)))
            .put("decor_bounds", bounds(host.window.decorView)).put("fixture_root_bounds", bounds(root))
            .put("phase_root_bounds", inlineRoot?.let(::bounds) ?: JSONObject.NULL)
            .put("content_bounds", bounds(content)).put("viewport_bounds", viewport?.let(::bounds) ?: JSONObject.NULL)
            .put("raw_visual_bottom", visual.bottom)
            .put("raw_ime_bottom", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            .put("ime_visible", insets.isVisible(WindowInsetsCompat.Type.ime()))
            .put("calculated_residual", JSONArray(listOf(residual.left, residual.top, residual.right, residual.bottom)))
            .put("bound_scaffold_bottom", checkNotNull(nativeInput).scaffoldInsets.bottom)
    }

    private fun Insets.libraryContent() = LibraryScaffoldInsets(left, right, bottom)

    fun catalogReadiness(i: Instrumentation, expected: Set<String>): JSONObject = nativeParityOnMain(i) {
        val state = native?.currentState()
        val readiness = DurableSaveJobs.readiness.value
        JSONObject().put("expected_ids", JSONArray(expected.toList())).put("visible_ids", JSONArray(visibleIds.toList()))
            .put("presentation_ids", state?.let { JSONArray(it.recordings.map(RecordingEntity::id)) } ?: JSONObject.NULL)
            .put("has_loaded", state?.hasLoaded ?: JSONObject.NULL)
            .put("load_failed", state?.loadFailed ?: JSONObject.NULL)
            .put("is_refreshing", state?.isRefreshing ?: JSONObject.NULL)
            .put("save_jobs_loaded", readiness.loaded)
            .put("save_recovery_failure", readiness.recoveryFailure ?: JSONObject.NULL)
            .put("pending_save_ids", JSONArray(DurableSaveJobs.state.value.map { it.id }))
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
                Triple(root, readyNow, transition != null)
            }
            if (observed.second) return
            check(SystemClock.uptimeMillis() < end) { "Transaction timeout: $label" }
            // An outgoing dialog may detach after this observation and never draw again.
            // Observe transition readiness on the next frame without requiring that retired root.
            if (observed.third) awaitTransitionFrame(i, end, label)
            else if (observed.first == null) Thread.sleep(16)
            else awaitNativeParityDraw(i, checkNotNull(observed.first))
        }
    }
    private fun awaitTransitionFrame(i: Instrumentation, deadline: Long, label: String) {
        val frame = CountDownLatch(1)
        val callback = Choreographer.FrameCallback { frame.countDown() }
        nativeParityOnMain(i) { Choreographer.getInstance().postFrameCallback(callback) }
        try {
            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            check(frame.await(remaining, TimeUnit.MILLISECONDS)) { "Transaction timeout: $label" }
        } finally {
            nativeParityOnMain(i) { Choreographer.getInstance().removeFrameCallback(callback) }
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
    private fun nativeParagraphs() = transactionViews(focusRoot()).filterIsInstance<ParagraphView>()
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
        val infoAction = action in listOf(R.string.recording_info, R.string.close).map(context::getString)
        val renameAction = action in listOf(R.string.rename_recording, R.string.cancel).map(context::getString)
        val tooltip = if (!inlineCurrent && (infoAction || renameAction)) NativeLibrarySheetTooltip(
            compose,
            context.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description),
            if (infoAction) NativeLibrarySheetScene.INFO else NativeLibrarySheetScene.RENAME,
            if (infoAction) listOf(R.string.recording_info, R.string.recording_details_started,
                R.string.recording_details_duration, R.string.recording_details_size,
                R.string.recording_details_codec, R.string.recording_details_mime,
                R.string.recording_details_storage, R.string.recording_details_location)
                .map { context.getString(it).removeSuffix(":") }
            else listOf(R.string.rename_recording, R.string.cancel).map(context::getString),
            if (infoAction) records.map { it.displayName } else emptyList(),
            context.getString(if (infoAction) R.string.close else R.string.cancel),
            if (renameAction) context.getString(R.string.rename_recording) else null,
        ) else null
        pendingWindowTransition = NativeLibraryWindowTransition(host, focusRoot(), evidence,
            observeSheetAuxiliaryOverlap = !inlineCurrent && action in listOf(R.string.recording_info, R.string.rename_recording)
                .map(context::getString),
            sheetTooltip = tooltip,
            allowInitialTooltipReadiness = !inlineCurrent && action == context.getString(R.string.rename_recording))
        return evidence
    }
    /** Actual Settings choice owner on this QA window; typed callbacks never write preferences. */
    fun verifyAudioChoiceMenu(i: Instrumentation, evidence: File, entry: JSONObject,
                              requireLandscapeTouch: Boolean = true) {
        check(!compose)
        data class Choice(val id: Int, val label: String)
        val options = listOf(Choice(1, "Same choice"), Choice(2, "Same choice"), Choice(3, "Other choice"))
        val title = "QA audio choice"
        val callbacks = JSONArray().also { entry.put("callback_order", it) }
        var selected: Choice? = null
        val channels = DropdownContent(title, options[1], options, Choice::label)
        val hidden = DropdownContent("Unused", 0, listOf(0), Int::toString)
        val initial = ReverbAudioOptions(channels, hidden, hidden, hidden, hidden)
        var owner: ReverbAudioChoices<Choice, Int, Int, Int>? = null
        var anchor: View? = null
        fun overlay(): View? = transactionViews(host.window.decorView).singleOrNull { it.accessibilityPaneTitle == title }
        var failure: Throwable? = null
        try {
            nativeParityOnMain(i) {
                guard()
                check(focusRoot() !== host.window.decorView)
                val transition = beginWindowTransition("audio-choice-dismiss-recording-menu")
                val root = focusRoot()
                check(root.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_ESCAPE)))
                check(root.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_ESCAPE)))
                transition.put("action_submitted", true)
            }
            awaitActivity(i)
            nativeParityOnMain(i) {
                guard()
                val unused: (Int) -> Unit = { error("Hidden audio choice activated") }
                val actions = ReverbAudioActions<Choice, Int, Int, Int>({ value ->
                    checkNotNull(overlay())
                    selected = value
                    callbacks.put("selected:${value.id}")
                    checkNotNull(owner).bind(initial.copy(channels = channels.copy(selected = value)), environment.theme)
                }, unused, unused, unused, unused)
                val choices = ReverbAudioChoices(context, environment.theme, initial, actions, { host.fixtureRoot },
                    host::hasWindowFocus)
                owner = choices
                val scale = environment.textDensity.density
                host.fixtureRoot.addView(choices.view, FrameLayout.LayoutParams((160 * scale).toInt(),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START).apply {
                    marginStart = (24 * scale).toInt()
                })
                anchor = transactionViews(choices.view).filterIsInstance<NativeDropdown<*>>()
                    .single { it.visibility == View.VISIBLE }
            }
            awaitNativeParityDraw(i, host.fixtureRoot)
            nativeParityOnMain(i) {
                guard()
                val field = checkNotNull(anchor)
                check(field.isShown && field.isLaidOut && focusRoot() === host.window.decorView)
                check(field.performClick())
                checkNotNull(overlay())
                callbacks.put("expanded:true")
            }
            fun choiceRows(): List<NativeDropdown<*>> = overlay()?.let(::transactionViews)
                ?.filterIsInstance<NativeDropdown<*>>().orEmpty()
            await(i, "Settings audio choice overlay") {
                val root = focusRoot()
                val currentOverlay = overlay()
                if (root !== host.window.decorView || currentOverlay == null || !currentOverlay.hasFocus()) false
                else {
                    val geometry = JSONArray().also { entry.put("choice_last_geometry", it) }
                    val ready = choiceRows().map { row ->
                        val full = Rect()
                        val visible = row.getGlobalVisibleRect(full)
                        val ancestors = JSONArray()
                        var settled = true
                        var ancestor: View? = row
                        while (ancestor != null) {
                            ancestors.put(JSONObject().put("identity", System.identityHashCode(ancestor))
                                .put("scale_x", ancestor.scaleX).put("scale_y", ancestor.scaleY)
                                .put("alpha", ancestor.alpha))
                            settled = settled && ancestor.scaleX == 1f && ancestor.scaleY == 1f && ancestor.alpha == 1f
                            ancestor = ancestor.parent as? View
                        }
                        geometry.put(JSONObject().put("identity", System.identityHashCode(row))
                            .put("width", row.width).put("height", row.height)
                            .put("visible", visible).put("bounds", full.flattenToString())
                            .put("ancestors", ancestors))
                        row.rootView === root && row.isShown && row.isEnabled && settled && visible &&
                            row.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES &&
                            row.width > 0 && row.height > 0 && full.width() == row.width && full.height() == row.height
                    }
                    ready.size == 3 && ready.all { it }
                }
            }
            val rows = nativeParityOnMain(i) {
                guard()
                val root = focusRoot()
                val metadata = JSONArray().also { entry.put("choice_metadata", it) }
                entry.put("overlay_identity", System.identityHashCode(checkNotNull(overlay())))
                    .put("same_activity_window", root === host.window.decorView)
                val anchorBounds = Rect()
                check(checkNotNull(anchor).getGlobalVisibleRect(anchorBounds))
                choiceRows().mapIndexed { index, row ->
                    check(row.rootView === root && row.isShown && row.isEnabled)
                    val full = Rect(); check(row.getGlobalVisibleRect(full))
                    check(full.width() == row.width && full.height() == row.height)
                    val node = checkNotNull(row.createAccessibilityNodeInfo())
                    try {
                        metadata.put(JSONObject().put("index", index).put("label", node.contentDescription?.toString())
                            .put("selected", node.isSelected).put("enabled", node.isEnabled)
                            .put("bounds", full.flattenToString()).put("identity", System.identityHashCode(row)))
                        check(node.contentDescription?.toString() == options[index].label)
                        check(node.isSelected == (index == 1) && node.isEnabled)
                        if (node.isSelected) check(full == anchorBounds)
                    } finally { node.recycle() }
                    row
                }.also { check(it.size == 3 && it.distinct().size == 3) }
            }
            val observation = JSONObject().also { entry.put("capture", it) }
            val image = capture(i, evidence, "native-audio-choice", true, observation,
                observeMenu = false, requireLandscapeTouch = requireLandscapeTouch)
            entry.put("capture_path", image.name)
            nativeParityOnMain(i) {
                guard()
                val row = rows[0]
                check(row.rootView === focusRoot() && row.isShown && row.isEnabled)
                val bounds = Rect(); check(row.getGlobalVisibleRect(bounds))
                check(bounds.width() == row.width && bounds.height() == row.height)
                check(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                check(selected === options[0])
                check(callbacks.optString(0) == "expanded:true" && callbacks.optString(1) == "selected:1")
            }
            await(i, "collapsed Settings audio choice overlay") { focusRoot() === host.window.decorView && overlay() == null }
            callbacks.put("expanded:false")
            check(callbacks.toString() == JSONArray(listOf("expanded:true", "selected:1", "expanded:false")).toString())
            entry.put("selected_id", selected?.id).put("typed_duplicate_identity", true)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            fun cleanup(action: () -> Unit) {
                try { nativeParityOnMain(i, action) } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                }
            }
            cleanup { owner?.close() }
            cleanup { owner?.view?.let(host.fixtureRoot::removeView) }
            if (failure != null && !entry.has("selected_id")) entry.put("choice_failure", failure!!.stackTraceToString())
            // The caller records the original exception; cleanup-only failure must also fail the case.
            failure?.let { throw it }
        }
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
        currentTextLabels()
    }
    private fun currentTextLabels() = if (compose) nodes(false).flatMap(::textOf)
        else nativeParagraphs().map { it.getText().toString() }
    /** Diagnostics only: retain the primary failure, and never read a foreign/unfocused window. */
    fun failureInventory(i: Instrumentation): JSONObject = try {
        nativeParityOnMain(i) {
            guard()
            val root = focusRoot()
            JSONObject().put("available", true).put("renderer", if (compose) "compose" else "native")
                .put("uptime_ms", SystemClock.uptimeMillis()).put("root_identity", System.identityHashCode(root))
                .put("root_class", root.javaClass.name).put("package", root.context.packageName)
                .put("display_id", root.display.displayId).put("window_focused", root.hasWindowFocus())
                .put("labels", JSONArray(currentTextLabels()))
                .put("view_state", transactionCaptureWindowState(root))
        }
    } catch (diagnosticError: Throwable) {
        JSONObject().put("available", false).put("reason", android.util.Log.getStackTraceString(diagnosticError))
    }
    fun hasText(text: String): Boolean = if (compose) nodes(false).any { text in textOf(it) }
        else nativeParagraphs().any { it.getText().toString() == text }
    fun performanceMenuWindow(i: Instrumentation): View = nativeParityOnMain(i) { guard(); focusRoot() }

    /** First production player binding from a supplied playing snapshot, without audible playback. */
    fun verifyPlayingPlayerFirstDraw(i: Instrumentation, recording: RecordingEntity, evidence: JSONObject) {
        check(!compose)
        awaitActivity(i)
        val pauseLabel = context.getString(R.string.player_pause)
        val attachedKey = "first_attached_pre_draw"
        val visibleKey = "first_visible_glyph_pre_draw"
        evidence.put("playback_source", "seeded_matching_playing_snapshot").put("passed", false)
        val (playback, original, observer) = nativeParityOnMain(i) {
            guard()
            check(checkNotNull(native).activePlayerLeases().isEmpty())
            @Suppress("UNCHECKED_CAST")
            val source = RecordingPlayback.javaClass.getDeclaredField("mutableState").run {
                isAccessible = true
                get(RecordingPlayback) as MutableStateFlow<RecordingPlaybackState>
            }
            val before = source.value
            check(!before.playing && !before.wantsPlayback && !RecordingPlayback.hasPendingStart())
            Triple(source, before, host.fixtureRoot.viewTreeObserver)
        }
        val seeded = original.copy(recording = recording, prepared = true, playing = true, wantsPlayback = true,
            positionMillis = 0, durationMillis = recording.durationMillis.toInt(), playbackAnchor = null)
        val visibleBounds = Rect()
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (evidence.has(visibleKey)) return@OnPreDrawListener true
            val lease = native?.activePlayerLeases()?.singleOrNull() ?: return@OnPreDrawListener true
            if (!lease.view.isAttachedToWindow || !lease.view.isShown) return@OnPreDrawListener true
            val player = transactionViews(lease.view).filterIsInstance<LibraryPlayerView<*>>().singleOrNull()
            val puck = player?.fineSeekView?.puckClickView as? ViewGroup
            val glyph = puck?.getChildAt(0)
            val visible = glyph?.getGlobalVisibleRect(visibleBounds) == true
            if (evidence.has(attachedKey) && !visible) return@OnPreDrawListener true
            // This field selects the Drawable used by the actual glyph's onDraw.
            val drawsPause = glyph?.let {
                runCatching { it.javaClass.getDeclaredField("playing").apply { isAccessible = true }.getBoolean(it) }
                    .getOrNull()
            }
            val presentation = lease.currentPresentation
            val observed = JSONObject().put("seed_still_current", RecordingPlayback.state.value === seeded)
                .put("state_playing", presentation?.state?.isPlaying ?: JSONObject.NULL)
                .put("binding_playing", presentation?.fineSeek?.isPlaying ?: JSONObject.NULL)
                .put("glyph_draws_pause", drawsPause ?: JSONObject.NULL)
                .put("glyph_label", glyph?.contentDescription?.toString() ?: JSONObject.NULL)
            if (!evidence.has(attachedKey)) evidence.put(attachedKey, observed)
            if (visible) evidence.put(visibleKey, observed)
            true
        }
        var failure: Throwable? = null
        var expanded = false
        try {
            nativeParityOnMain(i) {
                guard()
                check(playback.value === original)
                playback.value = seeded
                observer.addOnPreDrawListener(preDraw)
                expanded = nativeAction(recording.displayName).performClick()
                check(expanded)
            }
            await(i, "first visible playing player pre-draw") { evidence.has(visibleKey) }
            for (key in listOf(attachedKey, visibleKey)) {
                val frame = evidence.getJSONObject(key)
                check(frame.optBoolean("seed_still_current") && frame.optBoolean("state_playing") &&
                    frame.optBoolean("binding_playing") && frame.optBoolean("glyph_draws_pause") &&
                    frame.optString("glyph_label") == pauseLabel) { "Already-playing player rendered $key incorrectly: $frame" }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val terminal = closePreservingPrimaryFailure(failure) {
                nativeParityOnMain(i) {
                    try {
                        if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
                        if (expanded && host.hasWindowFocus()) check(nativeAction(recording.displayName).performClick())
                    } finally {
                        if (playback.value === seeded) playback.value = original
                    }
                    check(!RecordingPlayback.hasPendingStart()) { "First-draw fixture attempted real playback" }
                }
            }
            if (failure == null && terminal != null) throw terminal
        }
        evidence.put("passed", true)
    }

    fun openMenu(i: Instrumentation, title: String, timing: JSONObject? = null) {
        awaitActivity(i)
        val triggeredAt = nativeParityOnMain(i) {
            guard()
            val transitionEvidence = beginWindowTransition("open recording menu")
            transitionEvidence.put("action_submitted", JSONObject.NULL)
            val invokeLongClick: () -> Boolean = if (compose) {
                val label = context.getString(R.string.open_options)
                val row = nodes().single { title in textOf(it) &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                    it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }
                checkNotNull(row.config.getOrNull(SemanticsActions.OnLongClick)?.action)
            } else {
                val row = nativeParagraphs().filter { it.getText().toString() == title }.mapNotNull { leaf ->
                    var v: View? = leaf
                    while (v != null && !v.isLongClickable) v = v.parent as? View
                    v
                }.distinct().single()
                check(row.isEnabled && row.isShown)
                row::performLongClick
            }
            val start = SystemClock.uptimeMillis()
            check(invokeLongClick())
            val callbackReturnedAt = SystemClock.uptimeMillis()
            timing?.put("trigger", if (compose) "compose_semantics_long_click" else "view_perform_long_click")
                ?.put("includes_touch_dwell", false)
                ?.put("long_click_callback_ms", callbackReturnedAt - start)
            transitionEvidence.put("action_submitted", true)
            start
        }
        val menuLabel = context.getString(R.string.recording_info)
        await(i, "text '$menuLabel'") {
            hasText(menuLabel).also { ready ->
                if (ready) timing?.put("trigger_to_content_ready_ms", SystemClock.uptimeMillis() - triggeredAt)
            }
        }
        if (timing != null) {
            // A draw observed after readiness can follow the popup's first rendered frame.
            waitFrame(i)
            timing.put("trigger_to_observed_draw_boundary_ms", SystemClock.uptimeMillis() - triggeredAt)
            timing.put("configured_android_long_press_timeout_ms", ViewConfiguration.getLongPressTimeout())
        }
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
    fun awaitRenamed(i: Instrumentation, id: String, name: String) = await(i, "actual renamed catalog") {
        native?.currentState()?.recordings?.any { it.id == id && it.displayName == name } == true
    }
    fun requireRenameWindow(i: Instrumentation) = nativeParityOnMain(i) {
        guard(); check(!compose && hasEditor())
        check(currentTextLabels().contains(context.getString(R.string.rename_recording)))
    }
    /** Exercise the real viewport's bring-into-view owner, without changing focus or field state. */
    fun revealRenamePart(i: Instrumentation, part: String, evidence: JSONObject) {
        val views = nativeParityOnMain(i) {
            guard(); check(!compose)
            val descendants = transactionViews(focusRoot())
            val viewport = descendants.filterIsInstance<smallthingz.ui.scroll.NativeVerticalViewport>().single()
            val target: View = when (part) {
                "title" -> descendants.single {
                    it.javaClass.name == "smallthingz.ui.components.NativeSheetContent"
                }.let { content ->
                    content.javaClass.getDeclaredField("titleView").apply { isAccessible = true }.get(content) as View
                }
                "editor" -> descendants.filterIsInstance<android.widget.EditText>().single()
                "actions" -> descendants.filterIsInstance<app.smallthingz.reverb.nativeui.LibrarySheetActions>().single()
                else -> error("Unknown Rename part")
            }
            check(target.width > 0 && target.height > 0 && viewport.height > 0)
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), false)
            viewport to target
        }
        val (viewport, target) = views
        await(i, "visible Rename $part through actual viewport") {
            check(viewport.isAttachedToWindow && target.isAttachedToWindow)
            val viewportBounds = Rect().also { viewport.getGlobalVisibleRect(it) }
            val targetPosition = IntArray(2).also(target::getLocationOnScreen)
            val targetBounds = Rect(targetPosition[0], targetPosition[1],
                targetPosition[0] + target.width, targetPosition[1] + target.height)
            !viewport.state.isScrollInProgress && !targetBounds.isEmpty && viewportBounds.contains(targetBounds)
        }
        nativeParityOnMain(i) {
            guard()
            val editor = transactionViews(focusRoot()).filterIsInstance<android.widget.EditText>().single()
            check(editor.height > 0 && editor.isFocused && editor.hasWindowFocus())
            val observations = evidence.optJSONArray("viewport_reveals") ?: JSONArray().also {
                evidence.put("viewport_reveals", it)
            }
            observations.put(JSONObject().put("part", part).put("scroll", viewport.state.value)
                .put("maximum", viewport.state.maxValue).put("viewport_height", viewport.height)
                .put("content_height", viewport.getChildAt(0).height).put("editor_height", editor.height)
                .put("window", transactionCaptureWindowState(focusRoot())))
        }
    }
    fun selectRenameDraft(i: Instrumentation, start: Int, end: Int, evidence: JSONObject) {
        val readiness = JSONArray().also { evidence.put("selection_layout_readiness", it) }
        await(i, "laid-out Rename editor before single click") {
            val editor = transactionViews(focusRoot()).filterIsInstance<android.widget.EditText>()
                .filter { it.isShown && it.isEnabled }.singleOrNull()
            val visible = Rect()
            val hasVisible = editor?.getGlobalVisibleRect(visible) == true
            val xy = IntArray(2).also { editor?.getLocationOnScreen(it) }
            readiness.put(JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                .put("editor_identity", editor?.let(System::identityHashCode) ?: JSONObject.NULL)
                .put("attached", editor?.isAttachedToWindow ?: false)
                .put("shown", editor?.isShown ?: false).put("laid_out", editor?.isLaidOut ?: false)
                .put("layout_requested", editor?.isLayoutRequested ?: false)
                .put("window_focused", editor?.hasWindowFocus() ?: false)
                .put("bounds_screen", Rect(xy[0], xy[1], xy[0] + (editor?.width ?: 0),
                    xy[1] + (editor?.height ?: 0)).flattenToString())
                .put("visible_result", hasVisible).put("visible_global", visible.flattenToString()))
            editor != null && editor.isLaidOut && editor.width > 0 && editor.height > 0 && hasVisible
        }
        nativeParityOnMain(i) {
            guard(); check(!compose)
            val node = nativeEditor()
            val editor = transactionViews(focusRoot()).filterIsInstance<android.widget.EditText>()
                .filter { it.isShown && it.isEnabled }.single()
            val observation = JSONObject().put("before", transactionCaptureWindowState(focusRoot()))
            evidence.put("selection_request", observation)
            val clicked = node.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            observation.put("click_returned", clicked)
            val changed = node.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            })
            observation.put("selection_action_returned", changed)
                .put("expected_start", start).put("expected_end", end)
                .put("actual_start", editor.selectionStart).put("actual_end", editor.selectionEnd)
                .put("after", transactionCaptureWindowState(focusRoot()))
            check(clicked)
            // The actual semantic action legitimately returns false for an already-equal range.
            check(editor.selectionStart == start && editor.selectionEnd == end)
        }
    }
    fun awaitRenameIme(i: Instrumentation, scope: NativeParityInteractiveWindows, evidence: JSONObject) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        val observations = JSONArray().also { evidence.put("ime_readiness", it) }
        var identity: String? = null
        while (true) {
            val observed = renameKeyboardSnapshot(i, scope, requireEditorFocus = false)
            observations.put(observed)
            val target = observed.getString("target_identity")
            if (identity == null) identity = target else check(identity == target) { "Rename editor changed while awaiting IME" }
            if (observed.getJSONObject("observed_target").getBoolean("positive_ime")) return
            check(SystemClock.uptimeMillis() < deadline) { "Rename focus/IME did not become ready; no focus recovery" }
            Thread.sleep(40)
        }
    }
    fun renameKeyboardSnapshot(i: Instrumentation, scope: NativeParityInteractiveWindows,
                               requireEditorFocus: Boolean = true): JSONObject {
        val windows = scope.windows()
        try {
            val focused = windows.filter {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.displayId == 0 && it.isFocused
            }.single()
            val accessibleRoot = checkNotNull(focused.root)
            try { check(accessibleRoot.packageName?.toString() == host.packageName) }
            finally { accessibleRoot.recycle() }
            val imeWindows = windows.filter {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.displayId == 0
            }
            check(imeWindows.size <= 1)
            val ime = imeWindows.singleOrNull()?.let {
                JSONObject().put("window_id", it.id).put("title", it.title?.toString())
                    .put("bounds", Rect().also(it::getBoundsInScreen).let { b ->
                        JSONArray(listOf(b.left, b.top, b.right, b.bottom)) })
            }
            return nativeParityOnMain(i) {
                guard(); check(!compose)
                val root = focusRoot()
                val editor = transactionViews(root).filterIsInstance<android.widget.EditText>()
                    .filter { it.isShown && it.isEnabled }.single()
                if (requireEditorFocus) check(editor.isFocused && editor.hasWindowFocus())
                val raw = currentRaw()
                check(editor.text.toString() == raw)
                val position = IntArray(2).also(editor::getLocationOnScreen)
                val insets = checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(root))
                val accepting = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isAcceptingText
                val visible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                val title = (root.layoutParams as android.view.WindowManager.LayoutParams).title.toString()
                JSONObject().put("display_id", 0).put("window_title", title)
                    .put("decor_identity", System.identityHashCode(root))
                    .put("target_identity", "rename:${System.identityHashCode(editor)}:${focused.id}")
                    .put("scene_label", "Rename recording")
                    .put("target_bounds", JSONArray(listOf(position[0], position[1],
                        position[0] + editor.width, position[1] + editor.height)))
                    .put("observed_target", JSONObject().put("field_description", "Rename recording")
                        .put("raw_text", raw).put("owner_raw", raw)
                        .put("selection_start", editor.selectionStart).put("selection_end", editor.selectionEnd)
                        .put("editable_focused", editor.isFocused).put("editor_window_focused", editor.hasWindowFocus())
                        .put("focused_view_identity", root.findFocus()?.let(System::identityHashCode) ?: JSONObject.NULL)
                        .put("ime_visible", visible)
                        .put("ime_accepting_text", accepting).put("ime_window", ime ?: JSONObject.NULL)
                        .put("positive_ime", editor.isFocused && editor.hasWindowFocus() && visible && accepting && ime != null)
                        .put("application_window_id", focused.id))
                    .also { if (inlineCurrent) it.put("fixture_insets", inlineInsetEvidence()) }
            }
        } finally { windows.forEach { it.recycle() } }
    }

    fun raw(i: Instrumentation): String = nativeParityOnMain(i) {
        guard()
        currentRaw()
    }
    fun awaitRaw(i: Instrumentation, expected: String) = await(i, "exact Rename raw draft") {
        currentRaw() == expected
    }
    private fun currentRaw(): String =
        if (compose) nodes().single { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            .config.getOrNull(SemanticsProperties.EditableText)!!.text
        else nativeEditor().createAccessibilityNodeInfo().text.toString()
    fun setRaw(i: Instrumentation, raw: String) = nativeParityOnMain(i) {
        guard()
        if (compose) {
            val editor = nodes().single { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            check(editor.config.getOrNull(SemanticsActions.SetText)!!.action!!.invoke(AnnotatedString(raw)))
        } else check(nativeEditor().performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, raw) }))
    }
    fun setNativeCharSequence(i: Instrumentation, raw: CharSequence): JSONObject = nativeParityOnMain(i) {
        guard()
        check(!compose && raw is android.text.SpannableString)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, raw)
        }
        val delivered = checkNotNull(arguments.getCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
        check(delivered is android.text.SpannableString && delivered.toString() == raw.toString())
        check(nativeEditor().performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        JSONObject().put("argument_class", delivered.javaClass.name)
            .put("expected_raw", raw.toString()).put("action_returned", true)
    }
    fun capture(i: Instrumentation, evidence: File, name: String, settle: Boolean,
                observation: JSONObject, observeMenu: Boolean, requireLandscapeTouch: Boolean = false, requireStable: Boolean = true): File {
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
        val (viewport, region) = nativeParityOnMain(i) { transactionCaptureRegion(host, bounds) }
        if (fitCurrentWindowInsets) observation.put("toolbar_window_insets", nativeParityOnMain(i) {
            val content = checkNotNull(native)
            val decor = host.window.decorView
            val windowInsets = checkNotNull(ViewCompat.getRootWindowInsets(host.fixtureRoot))
            val platformTop = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            val origin = IntArray(2).also(content::getLocationInWindow)
            val windowOrigin = IntArray(2).also(decor::getLocationInWindow)
            val toolbarTop = origin[1] - windowOrigin[1]
            check(toolbarTop >= platformTop) { "Standalone Library toolbar crosses the platform safe top" }
            JSONObject().put("safe_top_px", platformTop).put("toolbar_top_px", toolbarTop)
        })
        observation.put("region", region)
        val frames = JSONArray().also { observation.put("frames", it) }
        fun currentState(): JSONObject = nativeParityOnMain(i) {
            guard()
            check(focusRoot() === root)
            check(transactionCaptureRegion(host, bounds).second.toString() == region.toString()) {
                "Owned transaction capture viewport changed: $name"
            }
            transactionCaptureWindowState(root).also {
                if (requireLandscapeTouch) it.put("landscape_touch_condition", transactionLandscapeMenuState(host, root))
                if (observeMenu) it.put("source_owned_layers", observeLibraryMenuLayers(root))
            }
        }
        var first: Bitmap? = null
        var firstFull: Bitmap? = null
        try {
            repeat(2) { pass ->
                val frame = JSONObject().put("pass", pass).also(frames::put)
                awaitNativeParityDraw(i, root)
                frame.put("before_capture", currentState())
                // Retain the full physical display and full fixture. The separately compared app
                // viewport comes only from current platform insets, never observed pixel differences.
                val screen = checkNotNull(nativeParityUiAutomation(i).takeScreenshot())
                val cropped = try {
                    frame.put("after_capture", currentState())
                    saveNativeParityBitmap(evidence, "$name-$pass-full.png", screen)
                    check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= screen.width && bounds.bottom <= screen.height)
                    val raw = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                    val fixture = if (raw === screen) checkNotNull(raw.copy(Bitmap.Config.ARGB_8888, false)) else raw
                    var retained = false
                    try {
                        saveNativeParityBitmap(evidence, "$name-$pass.png", fixture)
                        if (pass == 0) { firstFull = fixture; retained = true }
                        else frame.put("full_fixture_stability_observation",
                            compareNativeParityPixels(checkNotNull(firstFull), fixture))
                    } finally { if (!retained) fixture.recycle() }
                    val selected = Bitmap.createBitmap(screen, viewport.left, viewport.top, viewport.width(), viewport.height())
                    if (selected === screen) checkNotNull(selected.copy(Bitmap.Config.ARGB_8888, false)) else selected
                } finally { screen.recycle() }
                var retained = false
                try {
                    saveNativeParityBitmap(evidence, "$name-$pass-app-content.png", cropped)
                    if (pass == 0) { first = cropped; retained = true }
                    else {
                        val difference = compareNativeParityPixels(checkNotNull(first), cropped)
                        frame.put("app_viewport_stability", difference)
                        check(!requireStable || difference.getLong("different_pixels") == 0L) {
                            "Owned transaction app viewport is not stable: $name"
                        }
                    }
                } finally { if (!retained) cropped.recycle() }
            }
            return File(evidence, "$name-0-app-content.png")
        } finally { first?.recycle(); firstFull?.recycle() }
    }
    fun checkAsync() { asyncFailure.get()?.let { throw it } }
    /** Only the new current-inline fixture exposes its actual screen/root to bounded assertions. */
    fun inlineScreen(): ReverbLibraryScreenContent {
        guard(); check(inlineCurrent && !compose)
        return checkNotNull(native)
    }
    fun inlineInputRoot(): LibraryNativeFocusRoot { inlineScreen(); return checkNotNull(inlineRoot) }
    fun inlineRebind() { inlineScreen().bind(checkNotNull(nativeInput)) }
    /** QA-only observation of the retained production owner, without adding a production test API. */
    fun inlineRenameOwner(): NativeRecordingRenameOwner {
        val screen = inlineScreen()
        val lifetime = checkNotNull(screen.javaClass.getDeclaredField("renameHeader").run {
            isAccessible = true; get(screen)
        })
        val presentation = lifetime.javaClass.getDeclaredField("presentation").run {
            isAccessible = true; get(lifetime)
        } as NativeLibraryRenamePresentation
        return presentation.owner
    }
    fun inlineAccessibilityNode(view: View): smallthingz.ui.text.view.NativeSourceAccessibilityNode =
        checkNotNull(inlineSource)(view)
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
        release {
            inlineInsetsPreDraw?.let { host.fixtureRoot.viewTreeObserver.removeOnPreDrawListener(it) }
            inlineInsetsPreDraw = null
        }
        release {
            inlineRoot?.close()
        }
        release { inlineText?.close() }
        release { inlineScrollEvents?.close() }
        release {
            native?.let {
                if (fitCurrentWindowInsets) ViewCompat.setOnApplyWindowInsetsListener(it, null)
                it.close()
            }
        }
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

/** Platform-owned overlay regions; do not intersect source/native crops to hide a mismatch. */
/** Exact retained QA37 condition; callers establish current own-window focus before observation. */
private fun transactionLandscapeMenuState(host: NativeParityActivity, root: View,
                                          requireTouchMode: Boolean = true): JSONObject {
    val bounds = host.windowManager.currentWindowMetrics.bounds
    val orientation = host.resources.configuration.orientation
    val state = JSONObject().put("orientation", orientation)
        .put("window_bounds", bounds.flattenToString()).put("display_id", root.display?.displayId)
        .put("touch_mode", root.isInTouchMode).put("root_class", root.javaClass.name)
    check(orientation == Configuration.ORIENTATION_LANDSCAPE &&
        bounds.width() == 2400 && bounds.height() == 1080 && root.display?.displayId == 0) {
        "Original landscape menu condition is not present: $state"
    }
    check(!requireTouchMode || root.isInTouchMode) { "Original touch-mode menu condition is not present: $state" }
    return state
}

private fun transactionCaptureRegion(host: NativeParityActivity, fixture: Rect): Pair<Rect, JSONObject> {
    val decor = host.window.decorView
    val location = IntArray(2).also(decor::getLocationOnScreen)
    val insets = checkNotNull(decor.rootWindowInsets).getInsets(
        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
    val viewport = Rect(location[0] + insets.left, location[1] + insets.top,
        location[0] + decor.width - insets.right, location[1] + decor.height - insets.bottom)
    check(!viewport.isEmpty && fixture.contains(viewport)) {
        "Platform app viewport is outside the owned fixture"
    }
    return viewport to JSONObject().put("fixture_bounds", fixture.flattenToString())
        .put("decor_bounds", Rect(location[0], location[1], location[0] + decor.width,
            location[1] + decor.height).flattenToString())
        .put("system_bar_cutout_insets", JSONArray(listOf(insets.left, insets.top, insets.right, insets.bottom)))
        .put("compared_app_bounds", viewport.flattenToString())
}

private fun transactionCaptureWindowState(root: View): JSONObject {
    fun state(view: View): JSONObject {
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        return JSONObject().put("identity", System.identityHashCode(view)).put("class", view.javaClass.name)
            .put("width", view.width).put("height", view.height)
            .put("screen_location", JSONArray(screen.toList())).put("window_location", JSONArray(window.toList()))
            .put("focused", view.isFocused).put("window_focused", view.hasWindowFocus())
            .put("touch_mode", view.isInTouchMode).put("shown", view.isShown)
            .put("elevation", view.elevation.toDouble()).put("translation_z", view.translationZ.toDouble())
            .put("alpha", view.alpha.toDouble()).put("scale_x", view.scaleX.toDouble()).put("scale_y", view.scaleY.toDouble())
            .put("clip_to_outline", view.clipToOutline).put("force_dark_allowed", view.isForceDarkAllowed)
            .put("outline_provider", view.outlineProvider?.javaClass?.name ?: JSONObject.NULL).also {
                if (view is ViewGroup) it.put("clip_children", view.clipChildren).put("clip_to_padding", view.clipToPadding)
                    .put("descendant_focusability", view.descendantFocusability)
            }
    }
    val descendants = transactionViews(root)
    val lighting = JSONObject()
    for ((name, attribute) in listOf("ambient_shadow_alpha" to android.R.attr.ambientShadowAlpha,
        "spot_shadow_alpha" to android.R.attr.spotShadowAlpha, "window_elevation" to android.R.attr.windowElevation)) {
        val value = android.util.TypedValue()
        lighting.put(name, if (root.context.theme.resolveAttribute(attribute, value, true))
            JSONObject().put("type", value.type).put("data", value.data)
                .put("value", value.coerceToString()?.toString() ?: JSONObject.NULL) else JSONObject.NULL)
    }
    lighting.put("light_position_and_radius", "Unavailable through public Android SDK theme attributes")
    return JSONObject().put("uptime_ms", SystemClock.uptimeMillis()).put("root", state(root))
        .put("public_theme_lighting", lighting)
        .put("focused_view_identity", root.findFocus()?.let(System::identityHashCode) ?: JSONObject.NULL)
        .put("descendants", JSONArray(descendants.take(128).map(::state)))
        .put("descendant_count", descendants.size).put("descendant_observation_capped", descendants.size > 128)
        .put("window_layout", (root.layoutParams as? android.view.WindowManager.LayoutParams)?.let {
            JSONObject().put("type", it.type).put("flags", it.flags).put("x", it.x).put("y", it.y)
                .put("width", it.width).put("height", it.height)
                .put("surface_insets", "Unavailable through public Android SDK; no hidden API access")
                .put("manual_surface_insets", "Unavailable through public Android SDK")
                .put("preserve_previous_surface_insets", "Unavailable through public Android SDK")
        } ?: JSONObject.NULL)
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
        output = null // Publication committed; a readback failure must not roll it back.
        // AtomicFile may log rather than throw on a filesystem failure; verify actual publication.
        check(File(directory, "result.json").readBytes().contentEquals(bytes)) { "Terminal evidence did not publish" }
    } catch (error: Throwable) {
        try { output?.let(file::failWrite) } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
        if (primary == null) throw error
        if (primary !== error) primary.addSuppressed(error)
    }
}
private fun transactionDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .toHexString()
internal fun recoverEmptyLibraryFixtureJournal(context: Context, result: JSONObject, persist: () -> Unit) {
    // Plain Application intentionally skips ReverbApplication's startup recovery. Run the real
    // empty QA journal recovery; never synthesize readiness or adopt existing pending saves.
    requireQuietTransactionFixtureCatalog(context)
    val journal = durableSaveJournalRoot(context)
    val journalState = storagePathState(journal)
    check(journal.parentFile?.canonicalFile == context.noBackupFilesDir.canonicalFile)
    check(journalState == StoragePathState.MISSING ||
        (journalState == StoragePathState.PRESENT &&
            java.nio.file.Files.isDirectory(journal.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            journal.listFiles()?.isEmpty() == true)) { "Existing or unavailable QA save journal requires separate disposition" }
    check(DurableSaveJobs.state.value.isEmpty()) { "Existing QA save publications require separate disposition" }
    val recovery = JSONObject().put("journal", journal.absolutePath).put("journal_before", journalState.name)
        .put("loaded_before", DurableSaveJobs.readiness.value.loaded)
    result.put("save_recovery", recovery)
    persist()
    val coordinator = DurableSaveCoordinator.get(context)
    check(coordinator.pendingCount() == 0) { "Existing QA coordinator work requires separate disposition" }
    requireEmptyTransactionFixtureJournals(context)
    val handles = coordinator.recover()
    val readiness = DurableSaveJobs.readiness.value
    recovery.put("returned_handles", handles.size).put("pending_count", coordinator.pendingCount())
        .put("loaded_after", readiness.loaded).put("recovery_failure", readiness.recoveryFailure ?: JSONObject.NULL)
    persist()
    check(handles.isEmpty() && coordinator.pendingCount() == 0 && DurableSaveJobs.state.value.isEmpty())
    check(readiness.loaded && readiness.recoveryFailure == null) { "Empty QA save recovery did not finish" }
}

private fun requireTransactionIdentity(context: Context, currentUi: Boolean = false) {
    check(context.packageName == if (currentUi) "app.smallthingz.reverb.uitheme.reliability"
        else "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
    check(context.contentResolver.persistedUriPermissions.isEmpty() && getConfiguredExportTreeUri(context) == null)
}

private fun requireQuietTransactionFixtureCatalog(context: Context) {
    check(recordingMutations.activeIds.value.isEmpty()) { "QA recording mutation is still active" }
    requireEmptyTransactionFixtureJournals(context)
    requireEmptyTransactionFixtureCatalog(context)
}

@Suppress("DEPRECATION") // Android Q uses the URI parameter to include pending rows.
private fun requireEmptyTransactionFixtureMediaStore(context: Context) {
    check(usesMediaStoreDefaultStorage()) { "QA transaction fixtures require MediaStore" }
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val columns = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
    val paths = arrayOf("${Environment.DIRECTORY_MUSIC}/${APP_STORAGE_FOLDER_NAME}/")
    val resolver = context.contentResolver
    // A regular Library refresh can publish old pending staging even with empty journals.
    val rows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, paths)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(collection, columns, args, null)
    } else {
        resolver.query(MediaStore.setIncludePending(collection), columns, selection, paths, null)
    }
    checkNotNull(rows) { "QA MediaStore listing is unavailable" }.use {
        check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING) &&
            it.extras.getString(DocumentsContract.EXTRA_ERROR) == null) { "QA MediaStore listing is incomplete" }
        check(it.getColumnIndex(MediaStore.MediaColumns._ID) >= 0) { "QA MediaStore listing is unavailable" }
        check(!it.moveToFirst()) { "QA MediaStore directory is not empty; preserve all entries" }
    }
}

/** Per-renderer authority ledger; never adopts an entry found by a scan. */
internal class TransactionFixtures(
    private val context: Context,
    private val evidence: File,
    private val renderer: String,
    private val currentUi: Boolean = false,
    private val namePrefix: String = "Library transaction",
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
        requireTransactionIdentity(context, currentUi)
        requireEmptyBaseline()
        manifest.put("baseline_empty", true)
        save()
        admitted = true
    }
    private fun requireEmptyDirectory() {
        val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.canonicalFile
        check(directory.canonicalFile.parentFile == appExternal ||
            directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        val state = storageDirectoryState(directory)
        check(state == StoragePathState.MISSING ||
            (state == StoragePathState.PRESENT && directory.listFiles()?.isEmpty() == true)) {
            "QA recording folder is not empty or unavailable; preserve all entries"
        }
    }
    private fun requireEmptyBaseline() {
        requireQuietTransactionFixtureCatalog(context)
        requireEmptyDirectory()
        requireEmptyTransactionFixtureMediaStore(context)
    }
    fun create(count: Int = 2): List<RecordingEntity> {
        check(admitted && owned.isEmpty() && !closed)
        require(count in 2..12)
        requireTransactionIdentity(context, currentUi)
        requireEmptyBaseline()
        for (index in 0 until count) {
            val displayName = "$namePrefix ${'A' + index}.wav"
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
            val wavSha = verified.digest.sha256.toHexString()
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
        val pcmDigest = MessageDigest.getInstance("SHA-256")
        pcmDigest.update(bytes, authority.payloadOffset, bytes.size - authority.payloadOffset)
        check(pcmDigest.digest().toHexString() == authority.pcmSha)
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
    fun awaitAndAcceptRename(original: RecordingEntity, expectedName: String): RecordingEntity {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (known().none { it.id == original.id && it.displayName == expectedName }) {
            check(SystemClock.uptimeMillis() < deadline) { "Owned Rename catalog did not settle" }
            Thread.sleep(40)
        }
        return acceptRename(original, expectedName)
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
    fun isDeleted(records: List<RecordingEntity>): Boolean = records.all { recording ->
        recordingAssetState(context, recording) == RecordingAssetState.MISSING &&
            transactionFixtureCatalogIsEmpty(context, recording.id)
    }
    fun acceptDeleted(records: List<RecordingEntity>) {
        val current = known()
        for (recording in records) {
            val authority = owned.single { !it.deleted && it.recording.id == recording.id }
            check(recordingAssetState(context, recording) == RecordingAssetState.MISSING)
            requireEmptyTransactionFixtureCatalog(context, recording.id)
            authority.deleted = true
            authority.manifest.put("deleted_by_real_undo_expiry", true)
        }
        save()
        check(current.map { it.id }.toSet() == this.records().map { it.id }.toSet())
        requireEmptyDirectory()
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
                requireEmptyTransactionFixtureJournals(context)
                val recording = authority.recording
                val state = recordingAssetState(context, recording)
                if (state == RecordingAssetState.MISSING) {
                    // A delivered delete may have completed before an earlier assertion failed.
                    // Record the terminal absence; never replace the earlier failed test result.
                    requireEmptyTransactionFixtureCatalog(context, recording.id)
                    authority.manifest.put("absent_at_cleanup", true)
                } else {
                    check(state == RecordingAssetState.PRESENT) { "Owned fixture availability is unknown; preserved" }
                    verify(authority)
                    check(deleteOwnedTransactionFixture(context, recording)) {
                        "Exact QA fixture cleanup refused; preserve ${recording.id}"
                    }
                    check(recordingAssetState(context, recording) == RecordingAssetState.MISSING)
                    requireEmptyTransactionFixtureCatalog(context, recording.id)
                    authority.manifest.put("deleted_by_cleanup", true)
                }
                authority.deleted = true
            }
        }
        if (admitted && primary == null) cleanup {
            check(creationAttempts == owned.size && manifest.getJSONArray("fixtures").length() == owned.size &&
                owned.all { it.deleted }) {
                "Uncertain/incomplete QA output remains; retained in creation ledger"
            }
            requireEmptyBaseline()
            manifest.put("cleanup_verified_empty", true)
        }
        primary?.let { manifest.put("cleanup_error", android.util.Log.getStackTraceString(it)) }
        cleanup { save() }
        primary?.let { throw it }
    }
    private fun save() = File(evidence, "$renderer-fixtures.json").writeText(manifest.toString(2))
}
