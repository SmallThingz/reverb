package app.smallthingz.reverb

import android.app.Instrumentation
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Reliability-only content/value restoration. No actual NativeReverbActivity launch, request,
 * Finish, persistence callback, repository mount, settings apply, or ActivityResult delivery.
 *
 * Parcel creates genuinely separated primitive/Bundle payloads. Content replacement uses real
 * native save/restore owners; it does not pretend to be process death or an Activity route test.
 */
internal fun verifyNativeContentRestoration(instrumentation: Instrumentation, report: StringBuilder) {
    val host = launchNativeParityActivity(instrumentation)
    val result = JSONObject().put("passed", false)
    val cases = JSONArray()
    result.put("cases", cases)
    val directory = File(host.filesDir, "native-content-restoration-${System.currentTimeMillis()}")
    var lane: RestorationLane? = null
    var failure: Throwable? = null
    var preferencesBefore: Map<String, *>? = null
    try {
        check(directory.mkdirs())
        preferencesBefore = getRecorderPreferences(host).all.toMap()
        requireNativeParityHost(instrumentation, host)
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        lane = nativeParityOnMain(instrumentation) { RestorationLane(host) }
        val active = checkNotNull(lane)
        nativeParityOnMain(instrumentation) {
            cases.put(verifyNativeRestoredValues())
            cases.put(active.verifyUnconsumedActivityContent())
        }
        for (page in 0..2) {
            val name = "onboarding-page$page"
            var saved: NativeOnboardingViewportSavedState? = null
            var draft: Bundle? = null
            for (generation in 0..1) {
                requireNativeParityHost(instrumentation, host)
                nativeParityOnMain(instrumentation) {
                    active.showOnboarding(page, saved, draft)
                }
                awaitNativeParityDraw(instrumentation, host.fixtureRoot)
                requireNativeParityHost(instrumentation, host)
                val state = nativeParityOnMain(instrumentation) { active.onboardingState() }
                if (generation == 0) {
                    saved = state.first
                    draft = state.second
                    check(saved!!.page == page && saved!!.verticalPositions[page] > 0) {
                        "The finite small viewport did not exercise nonzero page scroll"
                    }
                } else {
                    check(state.first == saved) { "Onboarding page/offset/scroll changed after replacement: $saved -> ${state.first}" }
                    check(restorationBundleJson(state.second).toString() == restorationBundleJson(checkNotNull(draft)).toString())
                }
                val bitmap = captureNativeParityContent(instrumentation, host) { active.content }
                try { saveNativeParityBitmap(directory, "$name-$generation.png", bitmap) }
                finally { bitmap.recycle() }
                nativeParityOnMain(instrumentation) { active.clearContent() }
            }
            val before = android.graphics.BitmapFactory.decodeFile(File(directory, "$name-0.png").path)
            val after = android.graphics.BitmapFactory.decodeFile(File(directory, "$name-1.png").path)
            checkNotNull(before); checkNotNull(after)
            val comparison = try { compareNativeParityPixels(before, after) }
                finally { before.recycle(); after.recycle() }
            check(comparison.getLong("different_pixels") == 0L) { "$name changed raster after recreation: $comparison" }
            cases.put(JSONObject().put("name", name).put("viewport", restorationViewportJson(checkNotNull(saved)))
                .put("pixels", comparison).put("passed", true))
        }
        val expected = SettingsListSavedPosition(2, 11)
        repeat(2) { generation ->
            requireNativeParityHost(instrumentation, host)
            nativeParityOnMain(instrumentation) {
                active.showSettingsList(expected)
                check(active.settingsPosition() == expected) {
                    "A pending Settings anchor was discarded before its first layout"
                }
            }
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
            requireNativeParityHost(instrumentation, host)
            nativeParityOnMain(instrumentation) {
                check(active.settingsPosition() == expected) { "Settings anchor changed on layout/replacement" }
                active.clearContent()
            }
            cases.put(JSONObject().put("name", "settings-anchor-$generation")
                .put("first_item", expected.firstVisibleItemIndex)
                .put("offset", expected.firstVisibleItemScrollOffset).put("passed", true))
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        cleanup { nativeParityOnMain(instrumentation) { lane?.close() } }
        cleanup { preferencesBefore?.let { before ->
            check(getRecorderPreferences(host).all == before) {
                "Content restoration changed the isolated QA recorder preferences"
            }
        } }
        cleanup { nativeParityOnMain(instrumentation) { host.finish() } }
        result.put("passed", failure == null)
        failure?.let { result.put("failure", it.stackTraceToString()) }
        cleanup { File(directory, "result.json").writeText(result.toString(2)) }
        report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    }
    failure?.let { throw it }
    report.appendLine("Native content restoration: ${cases.length()} bounded cases passed; $directory")
}

private class RestorationLane(private val host: NativeParityActivity) : AutoCloseable {
    private val runtime = NativeAboutWindowRuntime()
    private val lifecycle = LifecycleEventObserver { _, _ ->
        runtime.setStarted(host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    private val environment = ReverbViewEnvironment(
        Density(host.resources.displayMetrics.density, host.resources.configuration.fontScale),
        createFontFamilyResolver(host), ReverbPalette.resolve(host,
            host.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES))
    private var onboarding: NativeOnboardingScreenView? = null
    private var settings: SettingsSectionListView? = null
    private var currentDraft: NativeOnboardingDraft? = null
    private var closed = false
    var content: View = host.fixtureRoot
        private set
    init {
        host.lifecycle.addObserver(lifecycle)
        runtime.setStarted(host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    /** No bind/attach/start: unconsumed values must survive before a branch is ever mounted. */
    fun verifyUnconsumedActivityContent(): JSONObject {
        val seed = restorationMainSeed()
        val viewport = NativeOnboardingViewportSavedState(2, 0.25f, listOf(17, 23, 31))
        var encodedMain = seed.toBundle()
        var encodedViewport = restorationViewportBundle(viewport)
        val flags = Bundle().apply {
            putBoolean("microphone_permission_requested", true)
            putBoolean("storage_permission_requested", false)
            putBoolean("recovery_permission_requested", true)
            putBoolean("notification_permission_requested", false)
        }
        repeat(2) {
            val owner = NativeOnboardingScreenOwner(host, restorationParcel(flags),
                NativeOnboardingPermissionLaunchers(ForbiddenPermissionLauncher(), ForbiddenPermissionLauncher(),
                    ForbiddenPermissionLauncher(), ForbiddenPermissionLauncher()), {})
            var view: NativeActivityContentView? = null
            var primary: Throwable? = null
            try {
                view = NativeActivityContentView(host, environment, owner, runtime.scope,
                    NativeActivityAccessibilityManager(host),
                    NativeMainScreenSavedState.fromBundle(restorationParcel(encodedMain)),
                    restorationReadViewport(restorationParcel(encodedViewport)),
                    onThemeChanged = { error("Unmounted content changed theme") },
                    registerDirectoryResult = { error("Unmounted content registered a directory callback") },
                    openApplicationSettings = { error("Unmounted content opened external settings") })
                check(view.saveMain() == seed)
                check(view.saveOnboarding() == viewport)
                encodedMain = checkNotNull(view.saveMain()).toBundle()
                encodedViewport = restorationViewportBundle(checkNotNull(view.saveOnboarding()))
                val savedFlags = Bundle().also(owner::saveInstanceState)
                for (key in flags.keySet()) check(savedFlags.getBoolean(key) == flags.getBoolean(key))
            } catch (error: Throwable) { primary = error }
            finally {
                try { view?.close() } catch (error: Throwable) {
                    if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
                }
                try { owner.close() } catch (error: Throwable) {
                    if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
                }
            }
            primary?.let { throw it }
        }
        check(NativeMainScreenSavedState.fromBundle(restorationParcel(encodedMain)) == seed)
        return JSONObject().put("name", "unmounted-activity-content-two-generations")
            .put("main", restorationBundleJson(encodedMain))
            .put("onboarding", restorationBundleJson(encodedViewport)).put("passed", true)
    }

    fun showOnboarding(page: Int, saved: NativeOnboardingViewportSavedState?, savedDraft: Bundle?) {
        check(!closed)
        clearContent()
        val fresh = OnboardingConfiguration(ConfiguredBufferAvailability(true, true),
            OnboardingAudioSelection(false, false), compressionAvailable = true, silenceValid = true)
        // The replacement deliberately has a different fresh audio baseline: restored original
        // values and user choices must win, as they do in the real NativeOnboardingDraft.
        val draft = NativeOnboardingDraft.restore(if (savedDraft == null) fresh else fresh.copy(
            audio = OnboardingAudioSelection(true, true)), savedDraft?.let(::restorationParcel))
        if (savedDraft == null) {
            draft.oneShotEnabled = false; draft.loopingEnabled = true
            draft.outputCompression = true; draft.skipSilence = true
        }
        currentDraft = draft
        check(!draft.oneShotEnabled && draft.loopingEnabled && draft.outputCompression && draft.skipSilence)
        check(draft.originalAudio == OnboardingAudioSelection(false, false))
        val permissions = OnboardingPermissionStatus(false, false, true, true, false,
            true, false, false, true)
        fun state() = OnboardingPageContentState(permissions, draft.oneShotEnabled,
            draft.loopingEnabled, draft.outputCompression, draft.skipSilence,
            true, true, true, true)
        val actions = OnboardingPageActions(
            requestPermission = { error("Restoration fixture must not request permissions") },
            oneShotChanged = { draft.oneShotEnabled = it; onboarding?.bind(state()) },
            loopingChanged = { draft.loopingEnabled = it; onboarding?.bind(state()) },
            compressionChanged = { draft.outputCompression = it; onboarding?.bind(state()) },
            skipSilenceChanged = { draft.skipSilence = it; onboarding?.bind(state()) })
        val value = NativeOnboardingScreenView.create(host, environment, state(), host, actions,
            onFinish = { error("Restoration fixture must not Finish onboarding") }, parentScope = runtime.scope,
            dispatcher = host.onBackPressedDispatcher,
            restored = saved?.let { restorationReadViewport(restorationParcel(restorationViewportBundle(it))) }
                ?: NativeOnboardingViewportSavedState(page, 0f, listOf(17, 23, 31)),
            observeWindowInsets = false)
        onboarding = value
        install(value, dp(280))
    }

    fun onboardingState(): Pair<NativeOnboardingViewportSavedState, Bundle> =
        checkNotNull(onboarding).saveViewportState() to restorationParcel(checkNotNull(currentDraft).save())

    fun showSettingsList(position: SettingsListSavedPosition) {
        clearContent()
        val value = SettingsSectionListView(host, SettingsSectionFactory {
            object : NativeSettingsSection {
                override val view = View(host).apply { minimumHeight = dp(120) }
                override fun bind() = Unit
                override fun close() = Unit
            }
        }, environment, restricted = true, restored = position)
        settings = value
        install(value, dp(180))
    }
    fun settingsPosition() = checkNotNull(settings).savePosition()

    private fun install(value: View, preferredHeight: Int) {
        check(host.fixtureRoot.width > 0 && host.fixtureRoot.height > 0)
        content = value
        host.fixtureRoot.addView(value, FrameLayout.LayoutParams(-1,
            minOf(host.fixtureRoot.height, preferredHeight)))
    }

    fun clearContent() {
        val oldOnboarding = onboarding; onboarding = null
        val oldSettings = settings; settings = null
        currentDraft = null
        val oldContent = content; content = host.fixtureRoot
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        release { oldOnboarding?.close() }
        release { oldSettings?.close() }
        release { if (oldContent !== host.fixtureRoot) (oldContent.parent as? ViewGroup)?.removeView(oldContent) }
        failure?.let { throw it }
    }
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        release(::clearContent)
        release { host.lifecycle.removeObserver(lifecycle) }
        release(runtime::close)
        failure?.let { throw it }
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

private class ForbiddenPermissionLauncher : ActivityResultLauncher<String>() {
    override fun launch(input: String, options: ActivityOptionsCompat?) =
        error("Restoration qualification attempted a forbidden permission launch: $input")
    override fun unregister() = Unit
    override val contract: ActivityResultContract<String, Boolean>
        get() = ActivityResultContracts.RequestPermission()
}

private fun restorationParcel(input: Bundle): Bundle {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(input)
        parcel.setDataPosition(0)
        checkNotNull(parcel.readBundle(NativeMainScreenSavedState::class.java.classLoader))
    } finally { parcel.recycle() }
}

private fun restorationViewportBundle(value: NativeOnboardingViewportSavedState) = Bundle().apply {
    putInt("page", value.page); putFloat("offset", value.offsetFraction)
    putIntArray("vertical", value.verticalPositions.toIntArray())
}
private fun restorationReadViewport(value: Bundle) = NativeOnboardingViewportSavedState(
    value.getInt("page"), value.getFloat("offset"), checkNotNull(value.getIntArray("vertical")).toList())
private fun restorationViewportJson(value: NativeOnboardingViewportSavedState) =
    restorationBundleJson(restorationViewportBundle(value))

@Suppress("DEPRECATION")
private fun restorationBundleJson(bundle: Bundle): JSONObject = JSONObject().also { output ->
    for (key in bundle.keySet().sorted()) {
        val value = bundle.get(key)
        output.put(key, when (value) {
            is Bundle -> restorationBundleJson(value)
            is IntArray -> JSONArray(value.toList())
            is List<*> -> JSONArray(value)
            null -> JSONObject.NULL
            else -> value
        })
    }
}

private fun restorationMainSeed(): NativeMainScreenSavedState {
    val snapshot = SettingsSnapshot(themeMode = AppThemeMode.DARK, retentionMode = RetentionMode.TIME,
        oneShotRetentionTime = 300, oneShotRetentionSizeBytes = 9_007_199_254_740_993L,
        loopingRetentionTime = 600, loopingRetentionSizeBytes = 2_000_003L,
        format = ExportFormat.WAV, codec = ExportCodec.PCM_16, sampleFormat = PcmSampleFormat.PCM_16,
        source = AudioSourceMode.MIC, channelMode = ChannelMode.MONO, route = InputRouteMode.AUTO,
        sampleRate = 48_000)
    val draft = SettingsUiDraft.normalized(snapshot).copy(oneShotTimeText = "5,000",
        oneShotSizeText = "-", loopingTimeText = "unfinished", loopingSizeText = "1.23456789")
    val picker = SettingsDirectoryPickerState(true).also { check(it.beginRequest()) }
    val tile = QuickSettingsTileEditorTarget().apply {
        check(selectBuffer(ReverbService.BufferSlot.ONE_SHOT)); seconds = 60
    }
    return NativeMainScreenSavedState(
        navigation = NativeMainSavedNavigation(true, ReverbService.BufferSlot.LOOPING.storageCode.toInt(),
            true, true, true),
        selectedBufferCode = ReverbService.BufferSlot.LOOPING.storageCode.toInt(),
        settingsDraft = draft.save(), directoryPicker = picker.save(), tileEditor = tile.saveDraft(),
        settingsPosition = SettingsListSavedPosition(2, 11),
        libraryPosition = NativeLibrarySavedScrollPosition(NativeListSavedPosition(4, 19), 23),
        incidentsPosition = NativeListSavedPosition(3, 7), aboutScrollPosition = 29)
}

/** A fresh controller must retain draft values without restoring readiness or transaction owners. */
private fun verifyNativeRestoredValues(): JSONObject {
    val seed = restorationMainSeed()
    val restored = checkNotNull(NativeMainScreenSavedState.fromBundle(restorationParcel(seed.toBundle())))
    check(restored == seed)
    check(NativeMainScreenSavedState.fromBundle(restorationParcel(NativeMainScreenSavedState().toBundle())) ==
        NativeMainScreenSavedState()) // Missing is distinct from zero-valued optional scroll state.
    val navigation = NativeMainNavigationState(restored.navigation)
    check(navigation.save() == seed.navigation)
    check(!navigation.librarySelectionActive && !navigation.libraryUndoVisible &&
        !navigation.libraryExpandedRecordingActive && navigation.dragTarget == null)
    navigation.closeAbout(); navigation.closeIncidents(); navigation.closeLibrary()
    check(navigation.settingsBackEnabled && !navigation.homeGesturesEnabled)
    navigation.closeSettings()
    // Closing the restored Incidents flag does not complete its retained exit motion.
    check(!navigation.homeGesturesEnabled && navigation.incidentsOverlayVisible &&
        navigation.settingsBufferTargetCode == -1)
    navigation.bindIncidentsOverlayVisibility(false)
    check(navigation.homeGesturesEnabled && navigation.settingsBufferTargetCode == -1)

    val themes = ArrayList<AppThemeMode?>()
    val controller = SettingsDraftController(true, checkNotNull(restored.settingsDraft),
        restored.directoryPicker, SettingsCommitBarrier(), { true }, {}, themes::add, { _, _ -> })
    try {
        check(!controller.state.hydrated && !controller.state.interactionReady && !controller.state.persisting)
        check(controller.saveDraft() == seed.settingsDraft)
        check(controller.saveDirectoryPicker() == seed.directoryPicker)
        val expected = checkNotNull(SettingsUiDraft.restore(checkNotNull(seed.settingsDraft)))
        val baseline = expected.snapshot.copy(themeMode = AppThemeMode.LIGHT)
        // This local barrier has no accepted work and this read supplies a value only. No IO,
        // repository, service, preference write or production hydration hook is involved.
        runBlocking {
            check(controller.hydrate({ SettingsDraftBaseline(SettingsUiDraft.normalized(baseline), null) },
                { it }) == SettingsDraftHydrationResult.APPLIED)
        }
        check(controller.state.displayed == expected)
        check(controller.state.interactionReady && controller.state.hasUnsavedChanges && !controller.state.persisting)
        check(themes.last() == AppThemeMode.DARK)
        check(controller.state.displayed.snapshot.oneShotRetentionSizeBytes == 9_007_199_254_740_993L)
        val next = SettingsDraftController(true, controller.saveDraft(), controller.saveDirectoryPicker(),
            SettingsCommitBarrier(), { true }, {}, {}, { _, _ -> })
        try {
            check(!next.state.hydrated && !next.state.interactionReady)
            check(next.saveDraft() == seed.settingsDraft)
        } finally { next.dispose() }
        controller.setActive(false)
        check(controller.saveDraft().isEmpty()) // Closing Settings discards its draft in release.
    } finally { controller.dispose() }

    val tiles = QuickSettingsTilesEditorState.restore(checkNotNull(restored.tileEditor))
    check(tiles.target == null && !tiles.mutationInProgress && tiles.save(true) == seed.tileEditor)
    check(!tiles.restorePendingDraft(false) && tiles.target == null)
    check(tiles.restorePendingDraft(true))
    check(tiles.target?.buffer == ReverbService.BufferSlot.ONE_SHOT && tiles.target?.seconds == 60)
    check(tiles.target?.busy == false && tiles.deleting == null && tiles.pinning == null)
    tiles.closeEditor()
    return JSONObject().put("name", "parcel-native-values-and-fresh-owner-admission").put("passed", true)
}
