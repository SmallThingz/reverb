package app.smallthingz.reverb

import android.Manifest
import android.animation.ValueAnimator
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
        .put("cases", cases).put("passed", false)
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
        var failure: Throwable? = null
        try {
            repeat(4) { pass ->
                images += captureNativeParityContent(instrumentation, activity) { current.show(pass % 2 == 0) }
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
            current.expandFirstPaused(instrumentation)
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
private class LibraryOwnedFixtures(private val context: Context, private val evidence: File) : AutoCloseable {
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
    fun close() { registry.currentState = Lifecycle.State.DESTROYED }
}

@OptIn(ExperimentalComposeViewContextApi::class)
private class LibraryParityBoard(
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
            host.fixtureRoot.addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        referenceHost.visibility = if (compose) View.VISIBLE else View.GONE
        native?.visibility = if (compose) View.GONE else View.VISIBLE
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
    fun expandFirstPaused(instrumentation: Instrumentation) {
        for (compose in listOf(true, false)) {
            requireNativeParityHost(instrumentation, host)
            nativeParityOnMain(instrumentation) { show(compose) }
            awaitNativeParityDraw(instrumentation, if (compose) reference else checkNotNull(native))
            // Both routes receive their genuine action while RESUMED. Pause this fixture owner
            // synchronously before async preparation/recomposition can submit source auto-start.
            nativeParityOnMain(instrumentation) {
                invokeRecording(compose, longClick = false)
                if (compose) sourceLifecycle.pause() else nativeLifecycle.pause()
            }
            awaitCondition(instrumentation, "paused player preparation") {
                assertNoPlayback()
                if (compose) sourceExpanded && sourceNodes().any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.player_play)) == true &&
                        it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                } else native?.activePlayerLeases()?.singleOrNull()?.currentPresentation?.let {
                    it.state.prepared && !it.waveformLoading && !it.state.isPlaying
                } == true
            }
        }
        settle(instrumentation)
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
    private fun sourceNodes() = libraryViews(reference).filterIsInstance<ViewRootForTest>()
        .single().semanticsOwner.getAllSemanticsNodes(false)
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
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release { native?.close() }
        release { nativeScope?.cancel() }
        release { reference.disposeComposition() }
        release { nativeLifecycle.close() }
        release { sourceLifecycle.close() }
        release { host.fixtureRoot.removeAllViews() }
        failure?.let { throw it }
    }
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
