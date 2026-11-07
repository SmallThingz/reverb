package app.smallthingz.reverb

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.ActivityResultLauncher
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.smallthingz.reverb.nativeui.*
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Native View entry under the unchanged public MainActivity component.
 * The exact released Compose implementation is retained in reliability sources.
 *
 * Reuses the ONE existing NativeOnboardingScreenOwner for all permission flags, hydration,
 * onboarding draft and process-owned finish work. Main owns its own navigation/settings/capture.
 */
class MainActivity : ComponentActivity() {
    private lateinit var owner: NativeOnboardingScreenOwner
    private lateinit var themePresentation: ThemePresentationOwner
    private lateinit var runtime: NativeAboutWindowRuntime
    private lateinit var content: NativeActivityContentView
    private var themeJob: Job? = null
    private var closed = false
    private var renderedDark: Boolean? = null
    private lateinit var environment: ReverbViewEnvironment
    private lateinit var directoryResultKey: String
    private var deletionConsentToken: String? = null
    private var deletionConsentLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var playbackRequest: RecordingPlaybackRequest? = null

    // Preserve original registerForActivityResult field order exactly.
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        owner.onMicrophonePermissionResult(it)
    }
    private val storage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        owner.onStoragePermissionResult(it)
    }
    private val recovery = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        owner.onRecoveryPermissionResult(it)
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        owner.onNotificationPermissionResult(it)
    }

    private val runtimeLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { runtime.setStarted(true) }
        override fun onResume(owner: LifecycleOwner) { deliverPlaybackRequest() }
        override fun onStop(owner: LifecycleOwner) { runtime.setStarted(false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ReverbNoiseTexture.prepare() // UI-only warmup; never waits or extends the platform splash.
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        installSplashScreen() // No keep-on-screen gate: same released early removal.
        val configuredMode = getConfiguredThemeMode(this)
        setTheme(if (configuredMode.nativeIsDark(systemDark())) R.style.Theme_Reverb_Dark else R.style.Theme_Reverb_Light)
        super.onCreate(savedInstanceState)
        ViewModelProvider(this)[RecordingRenameConsentHostViewModel::class.java]
        playbackRequest = if (savedInstanceState?.containsKey(STATE_PLAYBACK_REQUEST) == true)
            RecordingPlaybackRequest.fromBundle(savedInstanceState.getBundle(STATE_PLAYBACK_REQUEST))
        else RecordingPlaybackRequest.consume(intent)
        deletionConsentToken = savedInstanceState?.getString(STATE_DELETION_CONSENT_TOKEN)
            ?.takeIf(RecordingDeletionConsent::owns)
        deletionConsentToken?.let(::registerDeletionConsent)
        val windowBackground = TypedValue()
        if (theme.resolveAttribute(android.R.attr.colorBackground, windowBackground, true)) {
            val color = if (windowBackground.resourceId != 0) getColor(windowBackground.resourceId) else windowBackground.data
            window.setBackgroundDrawable(ReverbNoiseDrawable(resources, color))
        }

        val restoredUi = restoreReleased012SavedUi(
            registry = savedStateRegistry,
            nativeMain = NativeMainScreenSavedState.fromBundle(savedInstanceState?.getBundle(STATE_MAIN)),
            nativeMainPresent = savedInstanceState?.containsKey(STATE_MAIN) == true,
            nativeDirectoryResultKey = savedInstanceState?.getString(STATE_DIRECTORY_RESULT_KEY),
            nativeDirectoryKeyPresent = savedInstanceState?.containsKey(STATE_DIRECTORY_RESULT_KEY) == true,
            nativeOnboardingDraftPresent = savedInstanceState?.containsKey(STATE_ONBOARDING_DRAFT) == true,
            nativeOnboardingViewport = savedInstanceState?.getBundle(STATE_ONBOARDING_VIEWPORT)?.let(::readOnboardingViewport),
            nativeOnboardingViewportPresent = savedInstanceState?.containsKey(STATE_ONBOARDING_VIEWPORT) == true,
        )
        val restoredOwnerState = restoredUi.onboardingDraft?.let { values ->
            (savedInstanceState?.let(::Bundle) ?: Bundle()).apply {
                putBundle(STATE_ONBOARDING_DRAFT, Bundle().apply { values.forEach { (key, value) -> putBoolean(key, value) } })
            }
        } ?: savedInstanceState
        directoryResultKey = restoredUi.directoryResultKey ?: UUID.randomUUID().toString()
        themePresentation = ThemePresentationOwner(configuredMode).also {
            it.observeCommitted(processCommittedTheme.state.value)
        }
        val dark = themePresentation.mode.nativeIsDark(systemDark())
        renderedDark = dark
        environment = ReverbViewEnvironment(Density(this), createFontFamilyResolver(this), ReverbPalette.resolve(this, dark))
        runtime = NativeAboutWindowRuntime(this)
        lifecycle.addObserver(runtimeLifecycle)
        runtime.setStarted(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        owner = NativeOnboardingScreenOwner(this, restoredOwnerState,
            NativeOnboardingPermissionLaunchers(microphone, storage, recovery, notifications)) { snapshot ->
            if (!closed && ::content.isInitialized) content.bind(snapshot, environment)
        }
        content = NativeActivityContentView(
            this, environment, owner, runtime.scope,
            NativeActivityAccessibilityManager(this),
            restoredUi.main,
            restoredUi.onboardingViewport,
            onThemeChanged = { selected ->
                themePresentation.preview(selected, processCommittedTheme.state.value)
                publishTheme()
            },
            registerDirectoryResult = { receive ->
                // Called by Main only when its retained Settings subtree first exists. Registering
                // without a LifecycleOwner matches rememberLauncherForActivityResult's effect:
                // an already-pending result reaches the already-created Settings owner immediately.
                val launcher = activityResultRegistry.register(directoryResultKey,
                    ActivityResultContracts.OpenDocumentTree(), receive)
                NativeDirectoryPickerRegistration(launcher::launch, launcher::unregister)
            },
            openApplicationSettings = {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    })
                } catch (_: ActivityNotFoundException) {
                    AppFeedbackCenter.post(getString(R.string.no_app_available), FeedbackTone.ERROR)
                } catch (_: SecurityException) {
                    AppFeedbackCenter.post(getString(R.string.no_app_available), FeedbackTone.ERROR)
                }
            },
        )
        setContentView(content)
        applyWindowPalette(dark)
        content.bind(owner.snapshot, environment)
        owner.attach()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                RecordingDeletionConsent.changes.collect {
                    if (!closed && !isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        deletionConsentToken == null
                    ) {
                        val request = RecordingDeletionConsent.claim(this@MainActivity) ?: return@collect
                        deletionConsentToken = request.token
                        try {
                            registerDeletionConsent(request.token)
                            deletionConsentLauncher?.launch(IntentSenderRequest.Builder(request.sender).build())
                        } catch (error: android.content.IntentSender.SendIntentException) {
                            retireDeletionConsentLauncher()
                            RecordingDeletionConsent.finish(this@MainActivity, request.token, false, launchFailed = true, launchFailure = error)
                        } catch (error: ActivityNotFoundException) {
                            retireDeletionConsentLauncher()
                            RecordingDeletionConsent.finish(this@MainActivity, request.token, false, launchFailed = true, launchFailure = error)
                        } catch (error: SecurityException) {
                            retireDeletionConsentLauncher()
                            RecordingDeletionConsent.finish(this@MainActivity, request.token, false, launchFailed = true, launchFailure = error)
                        }
                    }
                }
            }
        }
        themeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                processCommittedTheme.state.collect {
                    // Reread latest: a newer commit may supersede the queued publication.
                    themePresentation.observeCommitted(processCommittedTheme.state.value)
                    publishTheme()
                }
            }
        }
    }

    private fun systemDark() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun registerDeletionConsent(token: String) {
        // The registry key and callback both carry the request token. A process-death or
        // recreation result can never be attached to a newer queued recording.
        val launcher = activityResultRegistry.register("recording_delete_$token",
            ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (deletionConsentToken == token) {
                retireDeletionConsentLauncher()
            }
            @Suppress("DEPRECATION")
            val launchError = result.data?.getSerializableExtra(
                ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) as? Exception
            RecordingDeletionConsent.finish(this, token, result.resultCode == RESULT_OK && !closed,
                launchFailed = launchError != null, launchFailure = launchError)
        }
        if (deletionConsentToken == token) deletionConsentLauncher = launcher else launcher.unregister()
    }

    private fun retireDeletionConsentLauncher() {
        deletionConsentToken = null
        deletionConsentLauncher?.unregister()
        deletionConsentLauncher = null
    }

    private fun publishTheme() {
        if (closed) return
        val dark = themePresentation.mode.nativeIsDark(systemDark())
        if (renderedDark != dark) {
            renderedDark = dark
            // A palette preview retains the current Density/resolver and every live native owner.
            environment = ReverbViewEnvironment(environment.textDensity, environment.sharedFontResolver,
                ReverbPalette.resolve(this, dark))
            content.bind(owner.snapshot, environment)
        }
        applyWindowPalette(dark)
    }

    @Suppress("DEPRECATION")
    private fun applyWindowPalette(dark: Boolean) {
        // Exactly ReverbThemeBase's own-window SideEffect; no system/night/accessibility setting.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, content).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onStart() {
        super.onStart()
        owner.onStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        RecordingPlaybackRequest.consume(intent)?.let {
            playbackRequest = it
            deliverPlaybackRequest()
        }
    }

    private fun deliverPlaybackRequest() {
        if (closed || !::content.isInitialized || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = playbackRequest ?: return
        content.openSavedRecording(request) {
            if (playbackRequest?.token == request.token) playbackRequest = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(STATE_PLAYBACK_REQUEST, playbackRequest?.toBundle())
        outState.putString(STATE_DELETION_CONSENT_TOKEN, deletionConsentToken)
        owner.saveInstanceState(outState) // Original four requested-flag keys retained by owner.
        outState.putString(STATE_DIRECTORY_RESULT_KEY, directoryResultKey)
        content.saveMain()?.let { outState.putBundle(STATE_MAIN, it.toBundle()) }
        content.saveOnboarding()?.let { value ->
            outState.putBundle(STATE_ONBOARDING_VIEWPORT, Bundle().apply {
                putInt("page", value.page)
                putFloat("offset", value.offsetFraction)
                putIntArray("vertical", value.verticalPositions.toIntArray())
            })
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        deletionConsentLauncher?.unregister()
        deletionConsentLauncher = null
        if (!isChangingConfigurations) {
            deletionConsentToken?.let { RecordingDeletionConsent.finish(this, it, false) }
            deletionConsentToken = null
        }
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        if (::themePresentation.isInitialized) release(themePresentation::close)
        release { themeJob?.cancel() }
        if (::content.isInitialized) release(content::close)
        if (::owner.isInitialized) release(owner::close)
        release { lifecycle.removeObserver(runtimeLifecycle) }
        if (::runtime.isInitialized) release(runtime::close)
        release { super.onDestroy() }
        failure?.let { throw it }
    }

    companion object {
        private const val STATE_MAIN = "native_main_saveable"
        private const val STATE_ONBOARDING_DRAFT = "native_onboarding_draft"
        private const val STATE_ONBOARDING_VIEWPORT = "native_onboarding_viewport"
        private const val STATE_DIRECTORY_RESULT_KEY = "native_settings_directory_result_key"
        private const val STATE_DELETION_CONSENT_TOKEN = "native_recording_deletion_consent_token"
        private const val STATE_PLAYBACK_REQUEST = "native_saved_recording_playback_request"
    }
}

private fun AppThemeMode.nativeIsDark(systemDark: Boolean) = when (this) {
    AppThemeMode.SYSTEM -> systemDark
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

private fun readOnboardingViewport(bundle: Bundle): NativeOnboardingViewportSavedState =
    NativeOnboardingViewportSavedState(bundle.getInt("page"), bundle.getFloat("offset"),
        bundle.getIntArray("vertical")?.toList() ?: listOf(0, 0, 0))
