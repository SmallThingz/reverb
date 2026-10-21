package app.smallthingz.reverb

import android.Manifest
import android.animation.ValueAnimator
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Actual isolated owners, no production route, source-state hook, capture service or permission flow. */
internal fun verifyNativeLibraryParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    requireLibraryFixtureIdentity(context)
    val directory = File(context.filesDir, "native-library-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "eee4b6985bb0ada6b8127a49ea4925055afe46bb8ea75cb067780c02e4503489")
        .put("comparison", "Exact full-content ARGB and ABAB repeatability; no tolerance or masking")
        .put("scope", "Actual FilesScreen and native root/owner: empty, grouped populated, selection, paused expanded player; physical fixture viewport with safe-drawing insets consumed by parent")
        .put("limits", "Programmatic source actions and native View actions; no pointer, accessibility-service traversal, keyboard, popup, playback, trim, deletion or Main navigation qualification")
        .put("cases", cases).put("player_preparation", JSONArray()).put("passed", false)
    val fixtures = LibraryOwnedFixtures(context, directory)
    var host: NativeParityActivity? = null
    var board: LibraryParityBoard? = null
    var primary: Throwable? = null
    var completed = 0
    var mismatches = 0
    fun compare(activity: NativeParityActivity, current: LibraryParityBoard, name: String) {
        val entry = JSONObject().put("name", name).put("passed", false)
        cases.put(entry)
        val images = mutableListOf<Bitmap>()
        val focus = JSONArray().also { entry.put("focus", it) }
        var failure: Throwable? = null
        try {
            repeat(4) { pass ->
                images += captureNativeParityContent(instrumentation, activity) { current.show(pass % 2 == 0) }
                focus.put(nativeParityOnMain(instrumentation) { current.focusEvidence(pass % 2 == 0) })
                saveNativeParityBitmap(directory, "$name-${listOf("compose", "native", "compose-repeat", "native-repeat")[pass]}.png", images.last())
            }
            val difference = compareNativeParityPixels(images[0], images[1])
            val sourceStable = compareNativeParityPixels(images[0], images[2])
            val nativeStable = compareNativeParityPixels(images[1], images[3])
            val passed = difference.getLong("different_pixels") == 0L &&
                sourceStable.getLong("different_pixels") == 0L && nativeStable.getLong("different_pixels") == 0L
            entry.put("difference", difference).put("reference_stability", sourceStable)
                .put("native_stability", nativeStable)
                .put("state", nativeParityOnMain(instrumentation) { current.evidence() }).put("passed", passed)
            completed++
            if (!passed) mismatches++
            report.append(if (passed) "PASS " else "FAIL ").append("native_library_parity ")
                .append(name).append(" differing_pixels=").append(difference.getLong("different_pixels")).append('\n')
        } catch (error: Throwable) {
            failure = error
            entry.put("error", android.util.Log.getStackTraceString(error))
            throw error
        } finally {
            images.forEach(Bitmap::recycle)
            writeLibraryEvidence(directory, result, failure)
        }
    }
    try {
        fixtures.requireEmptyBaseline()
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        // Empty is measured before any synthetic catalog entry exists; no reset is used between scenes.
        for (dark in listOf(false, true)) {
            requireNativeParityHost(instrumentation, activity)
            val current = nativeParityOnMain(instrumentation) { LibraryParityBoard(activity, dark, emptyList()) }
            board = current
            current.awaitInitial(instrumentation)
            compare(activity, current, "empty-" + if (dark) "dark-rtl" else "light-ltr")
            nativeParityOnMain(instrumentation) { current.close(); board = null }
        }
        val records = fixtures.create()
        for (dark in listOf(false, true)) {
            requireNativeParityHost(instrumentation, activity)
            val suffix = if (dark) "dark-rtl" else "light-ltr"
            val current = nativeParityOnMain(instrumentation) { LibraryParityBoard(activity, dark, records) }
            board = current
            current.awaitInitial(instrumentation)
            compare(activity, current, "grouped-$suffix")
            current.selectFirst(instrumentation)
            compare(activity, current, "selected-$suffix")
            current.clearSelection(instrumentation)
            current.expandFirstPaused(instrumentation, result.getJSONArray("player_preparation"))
            compare(activity, current, "expanded-paused-$suffix")
            nativeParityOnMain(instrumentation) { current.assertNoPlayback(); current.close(); board = null }
        }
        check(completed == 8 && mismatches == 0) { "Library mismatch: $mismatches/$completed; ${directory.name}" }
    } catch (error: Throwable) {
        primary = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanup: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (cleanup == null) cleanup = error else if (cleanup !== error) cleanup!!.addSuppressed(error)
            }
        }
        release { nativeParityOnMain(instrumentation) { board?.close(); board = null } }
        release { nativeParityOnMain(instrumentation) { host?.finish() } }
        release { fixtures.close() }
        cleanup?.let {
            primary?.addSuppressed(it)
            result.put("cleanup_error", android.util.Log.getStackTraceString(it))
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", primary == null && cleanup == null && completed == 8 && mismatches == 0)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeLibraryEvidence(directory, result, primary ?: cleanup)
        if (primary == null) cleanup?.let { throw it }
    }
}

private fun requireLibraryFixtureIdentity(context: Context) {
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    check(context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        "This fixture does not require or use broad media access"
    }
    check(context.contentResolver.persistedUriPermissions.isEmpty())
    check(getConfiguredExportTreeUri(context) == null)
}

/** Complete identity-bound outputs owned by this isolated app, using the ordinary default provider. */
internal class LibraryOwnedFixtures(private val context: Context, private val evidence: File) : AutoCloseable {
    private val directory = getSavedRecordingsDirectory(context)
    private val owned = mutableListOf<RecordingEntity>()
    private val ownedDigests = mutableMapOf<String, CopyDigest>()
    private var creationAttempts = 0
    private val manifest = JSONObject().put("package", context.packageName).put("fixtures", JSONArray())
    private var admitted = false
    fun requireEmptyBaseline() {
        requireLibraryFixtureIdentity(context)
        check(Build.VERSION.SDK_INT >= 29 && getConfiguredExportTreeUri(context) == null)
        val appExternal = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)?.canonicalFile
        val parent = directory.canonicalFile.parentFile
        check(parent == appExternal || directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        check(directory.mkdirs() || directory.isDirectory)
        check(directory.listFiles()?.isEmpty() == true) { "QA recording folder is not empty; preserve it" }
        val known = runBlocking { withTimeout(15_000L) { RecordingRepository.listKnown(context) } }
        check(known.isEmpty()) { "QA catalog is not empty; no reset is authorized" }
        val observed = runBlocking { withTimeout(15_000L) { RecordingRepository.refresh(context) } }
        check(observed.isEmpty()) { "QA accessible recording baseline is not empty; preserve every entry" }
        admitted = true
        manifest.put("baseline_empty", true)
        saveManifest()
    }
    fun create(): List<RecordingEntity> {
        check(admitted && owned.isEmpty())
        val baseTime = 1_792_497_600_000L
        val durationFrames = intArrayOf(24_000, 32_000, 16_000, 20_000)
        for (index in durationFrames.indices) {
            val displayName = "Library parity ${index + 1}.wav"
            val start = baseTime - if (index < 3) index * 60_000L else 86_400_000L
            // refresh() legitimately migrates legacy FILE outputs into MediaStore. Create the
            // fixture in its final provider from the start so its recorded URI remains authoritative.
            manifest.put("creation_attempts", ++creationAttempts)
            saveManifest() // The provider may create a row before its factory reports a failure.
            val target = createOutputTarget(context, displayName, "audio/wav", start)
            val entry = JSONObject().put("id", target.id).put("complete", false)
                .put("requested_display_name", displayName)
                .put("staging_display_name", target.stagingDisplayName)
                .put("staging_creation_identity", target.stagingIdentity)
            // Retain creation authority before any later assertion or operation can fail.
            manifest.getJSONArray("fixtures").put(entry); saveManifest()
            check(target.storageType == RecordingStorageType.MEDIASTORE && target.staging && target.uri != null)
            check(target.stagingIdentity.isNotBlank())
            val frames = durationFrames[index]
            val pcm = ByteArray(frames * 2)
            for (sample in 0 until frames) {
                val value = ((sample % 64) - 32) * 512
                pcm[sample * 2] = value.toByte()
                pcm[sample * 2 + 1] = (value shr 8).toByte()
            }
            val writer = WavAudioFileWriter(context, target, 8_000, 1)
            writer.use { it.write(pcm, 0, pcm.size) }
            val verified = verifyWavOutputTargetAndDigest(
                context, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
                writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
            )
            entry.put("verified_staging_identity", verified.providerIdentity)
                .put("verified_bytes", verified.digest.byteCount)
                .put("verified_wav_sha256", verified.digest.sha256.joinToString("") { "%02x".format(it.toInt() and 255) })
            saveManifest()
            val published = finalizeOutputTarget(context, target, verified)
            entry.put("published_id", published.id).put("published_identity", published.publishedIdentity)
            saveManifest()
            check(published.storageType == RecordingStorageType.MEDIASTORE && !published.staging)
            check(published.id == target.id && published.displayName == displayName)
            val identity = published.publishedIdentity
            check(identity.isNotBlank())
            val raw = buildRecordingEntity(context, published,
                frames * 1000L / 8_000L, "PCM", writer.totalFileBytesWritten)
            // Track before registration so a failed catalog write still leaves exact owned cleanup.
            owned += raw
            ownedDigests[raw.id] = verified.digest
            entry.put("complete", true).put("id", raw.id)
                .put("identity", identity).put("bytes", verified.digest.byteCount)
                .put("wav_sha256", verified.digest.sha256.joinToString("") { "%02x".format(it.toInt() and 255) })
                .put("pcm_sha256", libraryDigest(pcm))
                .put("sample_rate", 8_000).put("channels", 1).put("frames", frames)
                .put("signal", "signed16 little-endian; ((sampleIndex % 64)-32)*512")
            saveManifest()
            owned[owned.lastIndex] = runBlocking { withTimeout(15_000L) { RecordingRepository.register(context, raw) } }
        }
        val current = runBlocking { withTimeout(15_000L) { RecordingRepository.refresh(context) } }
        check(current.map { it.id }.toSet() == owned.map { it.id }.toSet())
        current.forEach { recording ->
            val expected = owned.single { it.id == recording.id }
            check(recording.fileIdentity == expected.fileIdentity && recording.sizeBytes == expected.sizeBytes)
        }
        manifest.put("catalog_ids", JSONArray(current.map { it.id })); saveManifest()
        return current
    }
    fun requireUnchanged() {
        check(admitted && creationAttempts == owned.size && owned.isNotEmpty())
        val current = runBlocking { RecordingRepository.listKnown(context) }
        check(current.map { it.id }.toSet() == owned.map { it.id }.toSet())
        owned.forEach { recording ->
            val catalog = current.single { it.id == recording.id }
            check(catalog.fileIdentity == recording.fileIdentity && catalog.displayName == recording.displayName &&
                catalog.sizeBytes == recording.sizeBytes && catalog.durationMillis == recording.durationMillis)
            val observed = recordingCatalogPostconditionObservation(context, recording)
            check(observed.identityMatches && observed.displayNameMatches)
            val digest = checkNotNull(sha256StableRecording(context, recording))
            val expected = ownedDigests.getValue(recording.id)
            check(digest.byteCount == expected.byteCount && digest.sha256.contentEquals(expected.sha256))
        }
    }
    override fun close() {
        var primary: Throwable? = null
        val deleted = mutableSetOf<String>()
        for (recording in owned.asReversed()) try {
            val observed = recordingCatalogPostconditionObservation(context, recording)
            check(observed.identityMatches && observed.displayNameMatches) { "Fixture identity changed; preserve it" }
            val digest = checkNotNull(sha256StableRecording(context, recording))
            val expected = ownedDigests.getValue(recording.id)
            check(digest.byteCount == expected.byteCount && digest.sha256.contentEquals(expected.sha256)) {
                "Fixture contents changed; preserve it"
            }
            check(runBlocking { withTimeout(15_000L) { RecordingRepository.delete(context, recording) } })
            check(recordingAssetState(context, recording) == RecordingAssetState.MISSING)
            deleted += recording.id
        } catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
        if (admitted) try {
            check(runBlocking { RecordingRepository.listKnown(context) }.isEmpty())
            check(directory.listFiles()?.isEmpty() == true) { "Incomplete or foreign fixture remains; preserved" }
            check(creationAttempts == owned.size && manifest.getJSONArray("fixtures").length() == owned.size &&
                deleted.size == owned.size) {
                "Incomplete or uncertain provider fixture remains; preserve its creation ledger"
            }
            manifest.put("cleanup_verified_empty", true)
        } catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
        primary?.let { manifest.put("cleanup_error", android.util.Log.getStackTraceString(it)) }
        try { saveManifest() } catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
        primary?.let { throw it }
    }
    private fun saveManifest() = File(evidence, "fixtures.json").writeText(manifest.toString(2))
}

private class LibraryFixtureLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    init { registry.currentState = Lifecycle.State.RESUMED }
    fun pause() { registry.currentState = Lifecycle.State.STARTED }
    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun close() { registry.currentState = Lifecycle.State.DESTROYED }
}

@OptIn(ExperimentalComposeViewContextApi::class)
internal class LibraryParityBoard(
    private val host: NativeParityActivity,
    dark: Boolean,
    private val records: List<RecordingEntity>,
) : AutoCloseable {
    private val configuration = Configuration(host.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    private val context = host.createConfigurationContext(configuration)
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    private val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
    private val environment = ReverbViewEnvironment(density, createFontFamilyResolver(context),
        ReverbPalette.resolve(context, dark))
    private val referenceHost = FrameLayout(context)
    private val reference = ComposeView(context)
    private val nativeHost = LibraryNativeFocusRoot(context)
    private val compositionContext: ComposeViewContext
    private val sourceLifecycle = LibraryFixtureLifecycle()
    private val nativeLifecycle = LibraryFixtureLifecycle()
    private var referenceScope: CoroutineScope? = null
    private var nativeScope: CoroutineScope? = null
    private var native: ReverbLibraryScreenContent? = null
    private var sourceSelected = false
    private var sourceExpanded = false
    private var sourceVisibleIds = emptyList<String>()
    private var closed = false
    private val noNested = object : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }
    init {
        try {
            host.fixtureRoot.removeAllViews()
            // The common backdrop is outside both implementations, just as Main owns Library background.
            host.fixtureRoot.background = ReverbNoiseDrawable(context.resources, environment.palette.surface)
            referenceHost.layoutDirection = if (dark) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            reference.layoutDirection = referenceHost.layoutDirection
            if (Build.VERSION.SDK_INT >= 29) referenceHost.isForceDarkAllowed = false
            host.fixtureRoot.addView(referenceHost, FrameLayout.LayoutParams(-1, -1))
            compositionContext = ComposeViewContext(referenceHost)
            reference.setContent {
                val scope = rememberCoroutineScope()
                SideEffect { referenceScope = scope }
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalResources provides context.resources, LocalDensity provides density,
                    LocalLayoutDirection provides if (dark) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalLifecycleOwner provides sourceLifecycle,
                    // The configuration Context is not the Activity dispatcher owner.
                    LocalOnBackPressedDispatcherOwner provides host,
                ) {
                    MaterialTheme(colorScheme = colors, typography = typography) {
                        ReleasedLibraryFilesScreen(
                            modifier = Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing),
                            active = true, initialRecordings = records,
                            onSelectionActiveChange = { sourceSelected = it },
                            onExpandedRecordingActiveChange = { sourceExpanded = it },
                            onVisibleRecordingsChanged = { sourceVisibleIds = it.map(RecordingEntity::id) },
                        )
                    }
                }
            }
            reference.createComposition(compositionContext)
            referenceHost.addView(reference, FrameLayout.LayoutParams(-1, -1))
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (error !== cleanup) error.addSuppressed(cleanup) }
            throw error
        }
    }
    fun show(compose: Boolean): View {
        check(!closed && host.hasWindowFocus())
        if (!compose && native == null) {
            val inherited = checkNotNull(referenceScope)
            val scope = CoroutineScope(inherited.coroutineContext + SupervisorJob(inherited.coroutineContext[Job]))
            nativeScope = scope
            val frames = checkNotNull(scope.coroutineContext[MonotonicFrameClock])
            val input = NativeLibraryScreenInput(NativeLibraryInput(true, records, NativeLibraryCallbacks()),
                false, true, LibraryScaffoldInsets(), {}, {}, {}, {})
            val content = ReverbLibraryScreenContent(context, environment, input, nativeLifecycle,
                host.onBackPressedDispatcher, scope, frames, ValueAnimator::getDurationScale,
                null, host.fixtureRoot, noNested, {}, {})
            native = content // Own it before attachment, whose callbacks can fail.
            content.layoutDirection = referenceHost.layoutDirection
            nativeHost.layoutDirection = referenceHost.layoutDirection
            nativeHost.addView(content, FrameLayout.LayoutParams(-1, -1))
            host.fixtureRoot.addView(nativeHost, FrameLayout.LayoutParams(-1, -1))
        }
        referenceHost.visibility = if (compose) View.VISIBLE else View.GONE
        nativeHost.visibility = if (compose) View.GONE else View.VISIBLE
        return if (compose) reference else checkNotNull(native)
    }
    fun awaitInitial(instrumentation: Instrumentation) {
        nativeParityOnMain(instrumentation) { show(true) }
        awaitCondition(instrumentation, "source catalog/empty readiness") {
            referenceScope != null && sourceVisibleIds.toSet() == records.map { it.id }.toSet() &&
                sourceNodes().none { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
        }
        nativeParityOnMain(instrumentation) { show(false) }
        awaitCondition(instrumentation, "native catalog readiness") {
            native?.currentState()?.let { it.hasLoaded && !it.isRefreshing && !it.loadFailed &&
                it.recordings.map(RecordingEntity::id).toSet() == records.map(RecordingEntity::id).toSet() } == true
        }
        settle(instrumentation)
    }
    fun selectFirst(instrumentation: Instrumentation) {
        for (compose in listOf(true, false)) {
            requireNativeParityHost(instrumentation, host)
            nativeParityOnMain(instrumentation) { show(compose) }
            awaitNativeParityDraw(instrumentation, if (compose) reference else checkNotNull(native))
            nativeParityOnMain(instrumentation) { invokeRecording(compose, longClick = true) }
            awaitCondition(instrumentation, "selected row") {
                if (compose) sourceSelected else native?.currentState()?.selectedCount == 1
            }
        }
        settle(instrumentation)
    }
    fun clearSelection(instrumentation: Instrumentation) {
        val label = context.getString(R.string.clear_selection)
        for (compose in listOf(true, false)) {
            requireNativeParityHost(instrumentation, host)
            nativeParityOnMain(instrumentation) { show(compose) }
            awaitNativeParityDraw(instrumentation, if (compose) reference else checkNotNull(native))
            nativeParityOnMain(instrumentation) { invokeDescription(compose, label) }
            awaitCondition(instrumentation, "selection cleared") {
                if (compose) !sourceSelected else native?.currentState()?.selectedCount == 0
            }
        }
    }
    fun expandFirstPaused(instrumentation: Instrumentation, preparation: JSONArray) {
        for (compose in listOf(true, false)) {
            val entry = JSONObject().put("renderer", if (compose) "compose" else "native")
                .put("action_submitted", false).put("ready", false)
            preparation.put(entry)
            requireNativeParityHost(instrumentation, host)
            nativeParityOnMain(instrumentation) { show(compose) }
            awaitNativeParityDraw(instrumentation, if (compose) reference else checkNotNull(native))
            // Both routes receive their genuine action while RESUMED. Pause this fixture owner
            // synchronously before async preparation/recomposition can submit source auto-start.
            nativeParityOnMain(instrumentation) {
                entry.put("action_submitted", JSONObject.NULL)
                invokeRecording(compose, longClick = false)
                entry.put("action_submitted", true)
                if (compose) sourceLifecycle.pause() else nativeLifecycle.pause()
            }
            awaitCondition(instrumentation, "paused player preparation") {
                assertNoPlayback()
                val playLabel = context.getString(R.string.player_play)
                // Released clickable Box owns OnClick; its child Icon owns the description.
                // Read their merged action node, as invokeDescription does. Unmerged geometry
                // and passive description observers elsewhere must remain unmerged.
                val playNodes = sourceNodes(merged = true).filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(playLabel) == true
                }
                val nativePresentation = native?.activePlayerLeases()?.singleOrNull()?.currentPresentation
                entry.put("source_expanded", sourceExpanded)
                    .put("source_lifecycle", sourceLifecycle.lifecycle.currentState.name)
                    .put("native_lifecycle", nativeLifecycle.lifecycle.currentState.name)
                    .put("source_play_nodes", JSONArray(playNodes.map { node ->
                        JSONObject().put("click_action", node.config.getOrNull(SemanticsActions.OnClick)?.action != null)
                            .put("enabled", node.config.getOrNull(SemanticsProperties.Disabled) == null)
                            .put("placed", node.layoutInfo.isPlaced).put("bounds", node.boundsInWindow.toString())
                    }))
                    .put("native_lease_count", native?.activePlayerLeases()?.size ?: 0)
                    .put("native_prepared", nativePresentation?.state?.prepared ?: JSONObject.NULL)
                    .put("native_waveform_loading", nativePresentation?.waveformLoading ?: JSONObject.NULL)
                    .put("native_playing", nativePresentation?.state?.isPlaying ?: JSONObject.NULL)
                val ready = if (compose) sourceExpanded && playNodes.any {
                    it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                } else nativePresentation?.let {
                    it.state.prepared && !it.waveformLoading && !it.state.isPlaying
                } == true
                entry.put("ready", ready)
                ready
            }
        }
        settle(instrumentation)
    }
    /** Bounded real-player route; the released renderer and fixture audio remain unchanged. */
    fun verifyPlayback(
        instrumentation: Instrumentation,
        compose: Boolean,
        directory: File,
        checkpoint: (String, JSONObject) -> Unit,
    ) {
        val renderer = if (compose) "compose" else "native"
        nativeParityOnMain(instrumentation) { show(compose) }
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        fun state() = nativeParityOnMain(instrumentation) { playbackState(compose) }
        fun record(name: String) = checkpoint(name, state())
        fun capture(name: String) {
            val bitmap = captureNativeParityContent(instrumentation, host) { show(compose) }
            try { saveNativeParityBitmap(directory, "$renderer-$name.png", bitmap) }
            finally { bitmap.recycle() }
        }
        record("prepared-first-attachment")
        capture("prepared-first-attachment")
        nativeParityOnMain(instrumentation) {
            playbackGuard()
            if (compose) sourceLifecycle.resume() else nativeLifecycle.resume()
        }
        awaitCondition(instrumentation, "initial expansion autoplay") { isPlaying(compose) }
        record("autoplay")
        nativeParityOnMain(instrumentation) {
            playbackGuard(); invokeDescription(compose, context.getString(R.string.player_pause))
        }
        awaitCondition(instrumentation, "pause before explicit play") { !isPlaying(compose) }
        val beforePlay = state()
        checkpoint("before-explicit-play", beforePlay)
        val beforePlayText = beforePlay.getJSONArray("transport_text").getString(0)
        nativeParityOnMain(instrumentation) {
            playbackGuard(); invokeDescription(compose, context.getString(R.string.player_play))
        }
        awaitCondition(instrumentation, "explicit play advances visible time") {
            isPlaying(compose) && transportTexts(compose).first() != beforePlayText
        }
        record("playing-progress")
        nativeParityOnMain(instrumentation) {
            playbackGuard(); check(isPlaying(compose))
            invokeDescription(compose, context.getString(R.string.player_pause))
        }
        awaitCondition(instrumentation, "explicit pause") { !isPlaying(compose) }
        record("paused")
        val pausedText = state().getJSONArray("transport_text").getString(0)
        val pauseUntil = SystemClock.uptimeMillis() + 180L
        while (SystemClock.uptimeMillis() < pauseUntil) {
            requireNativeParityHost(instrumentation, host)
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
            check(state().getJSONArray("transport_text").getString(0) == pausedText)
        }
        // The source waveform has no semantic node. Its bounds come from the current two
        // time labels and the released 128dp waveform / 2dp gap, checked against the concrete
        // native waveform View before dispatch. No screenshot-derived or stale screen position.
        nativeParityOnMain(instrumentation) {
            playbackGuard()
            check(!isPlaying(compose))
            val bounds = waveformBounds(compose)
            val other = waveformBounds(!compose)
            check(kotlin.math.abs(bounds.left - other.left) <= 1f &&
                kotlin.math.abs(bounds.top - other.top) <= 1f &&
                kotlin.math.abs(bounds.right - other.right) <= 1f &&
                kotlin.math.abs(bounds.bottom - other.bottom) <= 1f) {
                "Released/native logical waveform bounds differ: $bounds / $other"
            }
            val result = JSONObject().put("bounds", bounds.toString()).put("peer_bounds", other.toString())
                .put("down_handled", JSONObject.NULL).put("up_handled", JSONObject.NULL)
            // The checkpoint retains this object, so terminal failure serialization includes
            // any observed partial dispatch without claiming an uncertain action was absent.
            checkpoint("seek-input", result)
            val time = SystemClock.uptimeMillis()
            val origin = IntArray(2).also(host.fixtureRoot::getLocationOnScreen)
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                playbackGuard()
                check(waveformBounds(compose) == bounds) { "Waveform moved during the owned tap" }
                val x = bounds.left + bounds.width() * .75f
                val y = bounds.centerY()
                check(x >= 0f && y >= 0f && x < host.fixtureRoot.width && y < host.fixtureRoot.height)
                val properties = arrayOf(MotionEvent.PointerProperties().apply {
                    id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                })
                val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                    this.x = x + origin[0]; this.y = y + origin[1]
                    pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f
                })
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 1, properties,
                    coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                try {
                    val handled = host.fixtureRoot.dispatchTouchEvent(event)
                    result.put(if (action == MotionEvent.ACTION_DOWN) "down_handled" else "up_handled", handled)
                    check(handled) { "Owned waveform tap was not handled: $action" }
                } finally { event.recycle() }
            }
        }
        val expectedSeek = formatPlaybackTime((records.first().durationMillis * .75).toInt())
        awaitCondition(instrumentation, "paused interior waveform seek") {
            !isPlaying(compose) && transportTexts(compose).first() == expectedSeek
        }
        settle(instrumentation)
        record("seek-paused"); capture("seek-paused")
        val menuEvidence = JSONObject().put("action_submitted", false)
        checkpoint("menu-readiness", menuEvidence)
        val menuTransition = nativeParityOnMain(instrumentation) {
            val transition = NativeLibraryWindowTransition(host, playbackGuard(), menuEvidence)
            menuEvidence.put("action_submitted", JSONObject.NULL)
            openPlaybackMenu(compose)
            menuEvidence.put("action_submitted", true)
            transition
        }
        awaitPlaybackWindow(instrumentation, "Trim menu", menuTransition) {
            hasPlaybackText(compose, context.getString(R.string.trim_recording))
        }
        val trimEvidence = JSONObject().put("action_submitted", false)
        checkpoint("trim-controls-readiness", trimEvidence)
        val trimTransition = nativeParityOnMain(instrumentation) {
            val transition = NativeLibraryWindowTransition(host, playbackGuard(), trimEvidence)
            trimEvidence.put("action_submitted", JSONObject.NULL)
            clickPlaybackText(compose, context.getString(R.string.trim_recording))
            trimEvidence.put("action_submitted", true)
            transition
        }
        awaitPlaybackWindow(instrumentation, "inline Trim controls", trimTransition) {
            host.hasWindowFocus() && hasPlaybackText(compose, context.getString(R.string.trim_save_copy)) &&
                hasPlaybackText(compose, context.getString(R.string.cancel))
        }
        nativeParityOnMain(instrumentation) { check(!isPlaying(compose)) }
        settle(instrumentation)
        checkpoint("trim", nativeParityOnMain(instrumentation) {
            JSONObject().put("playing", isPlaying(compose)).put("labels", JSONArray(playbackTexts(compose)))
        })
        capture("trim")
        nativeParityOnMain(instrumentation) { clickPlaybackText(compose, context.getString(R.string.cancel)) }
        awaitCondition(instrumentation, "Trim Cancel leaves paused player") {
            !hasPlaybackText(compose, context.getString(R.string.trim_save_copy)) && !isPlaying(compose) &&
                transportTexts(compose).first() == formatPlaybackTime(0)
        }
        settle(instrumentation)
        record("trim-cancelled"); capture("trim-cancelled")
        nativeParityOnMain(instrumentation) {
            if (compose) sourceLifecycle.pause() else nativeLifecycle.pause()
            assertNoPlayback()
        }
    }

    private fun playbackGuard(): View {
        check(!closed && !host.isFinishing && host.fixtureRoot.isShown)
        val root = WindowInspector.getGlobalWindowViews().filter {
            it.isAttachedToWindow && it.isShown && it.display?.displayId == 0 && it.hasWindowFocus()
        }.single()
        check(root.context.packageName == host.packageName && host.fixtureRoot.display?.displayId == 0) {
            "Current focus is not this physical QA route"
        }
        return root
    }
    private fun playbackNodes(merged: Boolean = true) = libraryViews(playbackGuard())
        .filterIsInstance<ViewRootForTest>().flatMap { it.semanticsOwner.getAllSemanticsNodes(merged) }
    private fun playbackTexts(compose: Boolean): List<String> = if (compose) playbackNodes(false)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        else libraryViews(playbackGuard()).filterIsInstance<ReverbParagraphView>().filter { it.isShown }.map { it.getText() }
    private fun hasPlaybackText(compose: Boolean, text: String) = text in playbackTexts(compose)
    private fun clickPlaybackText(compose: Boolean, text: String) {
        if (compose) {
            val node = playbackNodes().single {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text } &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            check(node.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
        } else {
            val target = libraryViews(playbackGuard()).filterIsInstance<ReverbParagraphView>()
                .filter { it.isShown && it.getText() == text }.mapNotNull { leaf ->
                    var view: View? = leaf
                    while (view != null && !view.isClickable) view = view.parent as? View
                    view
                }.distinct().single()
            check(target.isEnabled && target.isShown && target.performClick())
        }
    }
    private fun openPlaybackMenu(compose: Boolean) {
        playbackGuard()
        val title = records.first().displayName
        if (compose) {
            val node = playbackNodes().single {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == title } &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                    it.config.getOrNull(SemanticsActions.OnLongClick)?.label == context.getString(R.string.open_options)
            }
            check(node.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke())
        } else {
            val row = libraryViews(checkNotNull(native)).filterIsInstance<ReverbParagraphView>()
                .filter { it.isShown && it.getText() == title }.mapNotNull { leaf ->
                    var view: View? = leaf
                    while (view != null && !view.isLongClickable) view = view.parent as? View
                    view
                }.distinct().single()
            check(row.isEnabled && row.performLongClick())
        }
    }
    private fun awaitPlaybackWindow(instrumentation: Instrumentation, label: String,
                                    transition: NativeLibraryWindowTransition, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (true) {
            val observed = nativeParityOnMain(instrumentation) {
                val root = transition.focusedRoot()
                val readyNow = root != null && run {
                    check(playbackGuard() === root) { "Playback focus changed during readiness observation" }
                    ready()
                }
                if (readyNow) transition.accepted(checkNotNull(root))
                root to readyNow
            }
            if (observed.second) return
            check(SystemClock.uptimeMillis() < deadline) { "Playback fixture timed out: $label" }
            if (observed.first == null) Thread.sleep(16)
            else awaitNativeParityDraw(instrumentation, checkNotNull(observed.first))
        }
    }
    private fun isPlaying(compose: Boolean): Boolean = if (compose) sourceNodes().any {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.player_pause)) == true
    } else checkNotNull(native?.activePlayerLeases()?.singleOrNull()?.currentPresentation).state.isPlaying
    private fun sourceTransportNodes() = sourceNodes().filter { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
        text != null && (0..(records.first().durationMillis / 1000).toInt()).any { text == formatPlaybackTime(it * 1000) }
    }.also { check(it.size == 2) { "Expected two unique source transport labels, found ${it.size}" } }
        .sortedBy { it.boundsInWindow.left }
    private fun nativePlayer() = libraryViews(checkNotNull(native)).filterIsInstance<ReverbLibraryPlayerView>().single()
    private fun transportTexts(compose: Boolean): List<String> = if (compose) sourceTransportNodes().map {
        it.config.getOrNull(SemanticsProperties.Text)!!.single().text
    } else nativePlayer().let { listOf(it.startLabel.getText(), it.endLabel.getText()) }
    private fun waveformBounds(compose: Boolean): RectF {
        val origin = IntArray(2).also(host.fixtureRoot::getLocationInWindow)
        return if (compose) {
            val labels = sourceTransportNodes()
            val first = labels[0].boundsInWindow
            val last = labels[1].boundsInWindow
            check(kotlin.math.abs(first.top - last.top) <= 1f)
            val bottom = first.top - 2f * density.density
            RectF(first.left - origin[0], bottom - 128f * density.density - origin[1],
                last.right - origin[0], bottom - origin[1])
        } else nativePlayer().waveformInput.view.let { view ->
            val at = IntArray(2).also(view::getLocationInWindow)
            RectF((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat(),
                (at[0] - origin[0] + view.width).toFloat(), (at[1] - origin[1] + view.height).toFloat())
        }.also { check(it.width() > 0 && it.height() > 0) }
    }
    private fun playbackState(compose: Boolean): JSONObject {
        playbackGuard()
        return JSONObject().put("playing", isPlaying(compose)).put("transport_text", JSONArray(transportTexts(compose)))
            .put("uptime_ms", SystemClock.uptimeMillis()).put("fixture_id", records.first().id)
            .put("native_position_ms", if (compose) JSONObject.NULL else
                native?.activePlayerLeases()?.singleOrNull()?.currentPresentation?.state?.positionMillis)
    }

    private fun invokeRecording(compose: Boolean, longClick: Boolean) {
        val title = records.first().displayName
        if (compose) {
            val match = sourceNodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(title) &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                    if (longClick) it.config.getOrNull(SemanticsActions.OnLongClick)?.action != null
                    else it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            check(if (longClick) match.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke()
                else match.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
        } else {
            val matches = libraryViews(checkNotNull(native)).filter {
                it.contentDescription?.toString() == title && it.isEnabled &&
                    if (longClick) it.isLongClickable else it.isClickable
            }
            check(matches.size == 1)
            check(if (longClick) matches.single().performLongClick() else matches.single().performClick())
        }
    }
    private fun invokeDescription(compose: Boolean, label: String) {
        if (compose) {
            val root = libraryViews(reference).filterIsInstance<ViewRootForTest>().single()
            val match = root.semanticsOwner.getAllSemanticsNodes(true).single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
            check(match.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
        } else {
            val described = libraryViews(checkNotNull(native)).filter { it.contentDescription?.toString() == label }
            check(described.size == 1)
            var target: View? = described.single()
            while (target != null && !target.isClickable) target = target.parent as? View
            val action = checkNotNull(target)
            check(action.isEnabled && action.performClick())
        }
    }
    private fun sourceNodes(merged: Boolean = false) = libraryViews(reference).filterIsInstance<ViewRootForTest>()
        .single().semanticsOwner.getAllSemanticsNodes(merged)
    fun assertNoPlayback() {
        check(sourceNodes().none {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.player_pause)) == true
        }) { "Source unexpectedly entered playback" }
        check(native?.activePlayerLeases()?.none { it.currentPresentation?.state?.isPlaying == true } != false) {
            "Native unexpectedly entered playback"
        }
    }
    private fun awaitCondition(instrumentation: Instrumentation, label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (true) {
            requireNativeParityHost(instrumentation, host)
            if (nativeParityOnMain(instrumentation, condition)) return
            check(SystemClock.uptimeMillis() < deadline) { "Library fixture timed out: $label" }
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        }
    }
    private fun settle(instrumentation: Instrumentation) {
        // Known finite source row(320ms) and waveform(430+330ms) settle allowance. ABAB below
        // independently fails unstable pixels; this delay is not evidence of continuous smoothness.
        val duration = (900f * ValueAnimator.getDurationScale().coerceAtLeast(1f)).toLong()
        check(duration <= 15_000L) { "Animation duration scale exceeds bounded fixture allowance" }
        val until = SystemClock.uptimeMillis() + duration
        while (SystemClock.uptimeMillis() < until) {
            requireNativeParityHost(instrumentation, host)
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        }
    }
    fun evidence(): JSONObject = JSONObject()
        .put("source_selected", sourceSelected).put("source_expanded", sourceExpanded)
        .put("source_lifecycle", sourceLifecycle.lifecycle.currentState.name)
        .put("native_lifecycle", nativeLifecycle.lifecycle.currentState.name)
        .put("native_loaded", native?.currentState()?.hasLoaded)
        .put("native_selected_count", native?.currentState()?.selectedCount)
        .put("native_expanded", native?.activePlayerLeases()?.size)
        .put("source_width", reference.width).put("source_height", reference.height)
        .put("native_width", native?.width).put("native_height", native?.height)
        .put("fixture_ids", JSONArray(records.map { it.id }))
    fun focusEvidence(compose: Boolean): JSONObject = JSONObject()
        .put("renderer", if (compose) "compose" else "native")
        .put("touch_mode", host.fixtureRoot.isInTouchMode)
        .put("source_focus_owner_installed", !compose)
        .put("focused_descriptions", JSONArray(if (compose) sourceNodes(merged = true)
            .filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        else nativeHost.findFocus()?.let { focused ->
            libraryViews(focused).mapNotNull { it.contentDescription?.toString() }
        }.orEmpty()))
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release { native?.close() }
        release { nativeHost.close() }
        release { nativeScope?.cancel() }
        release { reference.disposeComposition() }
        release { nativeLifecycle.close() }
        release { sourceLifecycle.close() }
        release { host.fixtureRoot.removeAllViews() }
        failure?.let { throw it }
    }
}

/** The same focus-owner hooks as NativeActivityContentView, without an Activity or data owner. */
private class LibraryNativeFocusRoot(context: Context) : FrameLayout(context), AutoCloseable {
    private var focus: NativeSourceFocusPolicy? = null
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        focus = NativeSourceFocusPolicy(this,
            takeOwnerFocus = { super.requestFocus(FOCUS_DOWN, null) },
            clearOwnerFocus = { super.clearFocus() })
    }
    override fun focusableViewAvailable(view: View) {
        if (focus?.reportsAvailability(view) != false) super.focusableViewAvailable(view)
    }
    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean =
        focus?.requestFocus(direction, previouslyFocusedRect) ?: super.requestFocus(direction, previouslyFocusedRect)
    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        focus?.childFocused(focused)
    }
    override fun clearChildFocus(child: View) {
        super.clearChildFocus(child)
        focus?.childCleared(child)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (focus?.hasPendingInvalidation == true) false else super.dispatchKeyEvent(event)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        focus?.beforeMeasure()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun close() { focus?.close() }
}

private fun libraryViews(root: View): List<View> = buildList {
    fun visit(view: View) { add(view); if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
    visit(root)
}
private fun libraryDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun writeLibraryEvidence(directory: File, result: JSONObject, primary: Throwable?) {
    try { File(directory, "result.json").writeText(result.toString(2)) }
    catch (error: Throwable) { if (primary == null) throw error; if (primary !== error) primary.addSuppressed(error) }
}
