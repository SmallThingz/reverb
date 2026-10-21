package app.smallthingz.reverb

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.smallthingz.reverb.nativeui.*
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Isolated candidate for MainActivity's Activity boundary. Not declared in the manifest.
 * The released Activity remains the oracle/production entry until full route qualification.
 *
 * Reuses the ONE existing NativeOnboardingScreenOwner for all permission flags, hydration,
 * onboarding draft and process-owned finish work. Main owns its own navigation/settings/capture.
 */
internal class NativeReverbActivity : ComponentActivity() {
    private lateinit var owner: NativeOnboardingScreenOwner
    private lateinit var themePresentation: ThemePresentationOwner
    private lateinit var runtime: NativeAboutWindowRuntime
    private lateinit var content: NativeActivityContentView
    private var themeJob: Job? = null
    private var closed = false
    private var renderedDark: Boolean? = null
    private lateinit var environment: ReverbViewEnvironment
    private lateinit var directoryResultKey: String

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
        override fun onStop(owner: LifecycleOwner) { runtime.setStarted(false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        installSplashScreen() // No keep-on-screen gate: same released early removal.
        val configuredMode = getConfiguredThemeMode(this)
        setTheme(if (configuredMode.nativeIsDark(systemDark())) R.style.Theme_Reverb_Dark else R.style.Theme_Reverb_Light)
        super.onCreate(savedInstanceState)

        directoryResultKey = savedInstanceState?.getString(STATE_DIRECTORY_RESULT_KEY) ?: UUID.randomUUID().toString()
        themePresentation = ThemePresentationOwner(configuredMode).also {
            it.observeCommitted(processCommittedTheme.state.value)
        }
        val dark = themePresentation.mode.nativeIsDark(systemDark())
        renderedDark = dark
        environment = ReverbViewEnvironment(Density(this), createFontFamilyResolver(this), ReverbPalette.resolve(this, dark))
        runtime = NativeAboutWindowRuntime()
        lifecycle.addObserver(runtimeLifecycle)
        runtime.setStarted(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        owner = NativeOnboardingScreenOwner(this, savedInstanceState,
            NativeOnboardingPermissionLaunchers(microphone, storage, recovery, notifications)) { snapshot ->
            if (!closed && ::content.isInitialized) content.bind(snapshot, environment)
        }
        content = NativeActivityContentView(
            this, environment, owner, runtime.scope,
            NativeActivityAccessibilityManager(this),
            NativeMainScreenSavedState.fromBundle(savedInstanceState?.getBundle(STATE_MAIN)),
            savedInstanceState?.getBundle(STATE_ONBOARDING_VIEWPORT)?.let(::readOnboardingViewport),
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
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            },
        )
        setContentView(content)
        applyWindowPalette(dark)
        content.bind(owner.snapshot, environment)
        owner.attach()
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

    override fun onSaveInstanceState(outState: Bundle) {
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
        private const val STATE_ONBOARDING_VIEWPORT = "native_onboarding_viewport"
        private const val STATE_DIRECTORY_RESULT_KEY = "native_settings_directory_result_key"
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
