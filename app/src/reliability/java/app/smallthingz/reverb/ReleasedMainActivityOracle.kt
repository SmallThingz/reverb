package app.smallthingz.reverb

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent

import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

private const val URI_SCHEME_PACKAGE = "package"
private const val STATE_MICROPHONE_PERMISSION_REQUESTED = "microphone_permission_requested"
private const val STATE_STORAGE_PERMISSION_REQUESTED = "storage_permission_requested"
private const val STATE_RECOVERY_PERMISSION_REQUESTED = "recovery_permission_requested"
private const val STATE_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
private const val PANEL_COMMIT_PROGRESS = 0.12f
private const val PANEL_SETTLE_DURATION_MS = 220

private enum class MainPanelDragTarget { SETTINGS, LIBRARY }

internal fun releasedMainOraclePanelRevealProgress(dragDistancePx: Float, viewportHeightPx: Float): Float {
    if (viewportHeightPx <= 0f) return 0f
    return (dragDistancePx / viewportHeightPx).coerceIn(0f, 1f)
}

internal fun releasedMainOracleShouldCommitPanelReveal(progress: Float): Boolean =
    progress.coerceIn(0f, 1f) >= PANEL_COMMIT_PROGRESS

internal fun releasedMainOracleShouldComposeMainPanel(
    previouslyComposed: Boolean,
    visible: Boolean,
    progress: Float,
): Boolean = previouslyComposed || visible || progress > 0f

class ReleasedMainActivityOracle : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)
    private var microphonePermissionGranted by mutableStateOf(false)
    private var storagePermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private var mediaRecoveryAllowed by mutableStateOf(false)
    private var batteryOptimizationAllowed by mutableStateOf(false)
    private var showPermissionDenied by mutableStateOf(false)
    private var showOnboarding by mutableStateOf(false)
    private var onboardingConfiguration by mutableStateOf<OnboardingConfiguration?>(null)
    private var themeMode by mutableStateOf(AppThemeMode.SYSTEM)
    private lateinit var themePresentation: ThemePresentationOwner

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphonePermissionGranted = granted || hasMicrophonePermission()
            permissionsGranted = hasRequiredPermissions()
            showPermissionDenied = !microphonePermissionGranted && !showOnboarding
            if (microphonePermissionGranted && !showOnboarding) beginPermissionFlow()
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            storagePermissionGranted = granted || hasLegacyStoragePermission()
            permissionsGranted = hasRequiredPermissions()
            showPermissionDenied = !storagePermissionGranted && !showOnboarding
            if (storagePermissionGranted && !showOnboarding) beginPermissionFlow()
        }

    private val recoveryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            mediaRecoveryAllowed = granted || hasMediaRecoveryPermission()
            if (mediaRecoveryAllowed) {
                AppFeedbackCenter.post(getString(R.string.recording_recovery_enabled), FeedbackTone.SUCCESS)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionGranted = granted
        }

    private var microphonePermissionRequested = false
    private var storagePermissionRequested = false
    private var recoveryPermissionRequested = false
    private var notificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPhonePortraitOnly()
        // Never hold the startup surface for this animation. If the first app frame is ready
        // before the R finishes splitting, Android removes the splash immediately.
        installSplashScreen()
        val configuredThemeMode = applyConfiguredPlatformTheme()
        super.onCreate(savedInstanceState)
        microphonePermissionRequested =
            savedInstanceState?.getBoolean(STATE_MICROPHONE_PERMISSION_REQUESTED) ?: false
        storagePermissionRequested =
            savedInstanceState?.getBoolean(STATE_STORAGE_PERMISSION_REQUESTED) ?: false
        recoveryPermissionRequested =
            savedInstanceState?.getBoolean(STATE_RECOVERY_PERMISSION_REQUESTED) ?: false
        notificationPermissionRequested =
            savedInstanceState?.getBoolean(STATE_NOTIFICATION_PERMISSION_REQUESTED) ?: false
        microphonePermissionGranted = hasMicrophonePermission()
        storagePermissionGranted = hasLegacyStoragePermission()
        permissionsGranted = microphonePermissionGranted && storagePermissionGranted
        showOnboarding = onboardingPendingForUi(
            finishState = processOnboardingFinish.state.value,
            readDurablePending = { isOnboardingPending(this) },
        )
        if (showOnboarding) {
            notificationPermissionGranted = hasNotificationPermission()
            mediaRecoveryAllowed = hasMediaRecoveryPermission()
            batteryOptimizationAllowed = isIgnoringBatteryOptimizations(this)
        }
        themePresentation = ThemePresentationOwner(configuredThemeMode)
        themePresentation.observeCommitted(processCommittedTheme.state.value)
        themeMode = themePresentation.mode
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                processCommittedTheme.state.collect {
                    // A newer commit can supersede a payload already queued for this Activity.
                    themePresentation.observeCommitted(processCommittedTheme.state.value)
                    themeMode = themePresentation.mode
                }
            }
        }
        if (showOnboarding) {
            startOnboardingBufferHydration()
            observeOnboardingFinish()
        }
        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            val onboardingFinishState by processOnboardingFinish.state.collectAsState()
            ReverbTheme(darkTheme = themeMode.isDark(systemDarkTheme)) {
                if (showOnboarding) {
                    Box(Modifier.fillMaxSize()) {
                        val onboarding = onboardingConfiguration
                        if (onboarding == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            OnboardingScreen(
                                status = OnboardingStatus(
                                    microphoneAllowed = microphonePermissionGranted,
                                    storageAllowed = storagePermissionGranted,
                                    storagePermissionRequired = requiresLegacyStoragePermission(),
                                    recoveryAllowed = mediaRecoveryAllowed,
                                    recoveryPermissionRequired = mediaRecoveryPermission() != null,
                                    notificationAllowed = notificationPermissionGranted,
                                    notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                                    batteryOptimizationAllowed = batteryOptimizationAllowed,
                                ),
                                initialOneShotEnabled = onboarding.buffers.oneShotEnabled,
                                initialLoopingEnabled = onboarding.buffers.loopingEnabled,
                                initialAudio = onboarding.audio,
                                compressionAvailable = onboarding.compressionAvailable,
                                silenceValid = onboarding.silenceValid,
                                finishing = onboardingFinishState != OnboardingFinishState.IDLE,
                                onRequestMicrophone = {
                                    microphonePermissionRequested = true
                                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onRequestStorage = {
                                    if (requiresLegacyStoragePermission()) {
                                        storagePermissionRequested = true
                                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                },
                                onRequestRecovery = {
                                    mediaRecoveryPermission()?.let { permission ->
                                        recoveryPermissionRequested = true
                                        recoveryPermissionLauncher.launch(permission)
                                    }
                                },
                                onRequestNotifications = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionRequested = true
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onReviewBatteryOptimization = {
                                    if (!openBatteryOptimizationReview(this@ReleasedMainActivityOracle)) {
                                        AppFeedbackCenter.post(
                                            getString(R.string.no_app_available),
                                            FeedbackTone.ERROR,
                                        )
                                    }
                                },
                                onFinish = { oneShotEnabled, loopingEnabled, originalAudio, selectedAudio ->
                                    val appContext = applicationContext
                                    processOnboardingFinish.submit(
                                        block = {
                                            commitOnboardingAndReloadRuntime(
                                                commit = {
                                                    finishOnboarding(
                                                        appContext, oneShotEnabled, loopingEnabled,
                                                        originalAudio, selectedAudio,
                                                    )
                                                },
                                                reloadRuntime = { startCommittedSettingsRuntimeReload(appContext) },
                                                onRuntimeFailure = {
                                                    AppFeedbackCenter.post(
                                                        appContext.getString(R.string.settings_apply_failed),
                                                        FeedbackTone.ERROR,
                                                    )
                                                },
                                            )
                                        },
                                        onFailure = {
                                            AppFeedbackCenter.post(
                                                appContext.getString(R.string.recorder_state_persist_failed),
                                                FeedbackTone.ERROR,
                                            )
                                        },
                                    )
                                },
                            )
                        }
                        AppFeedbackHost(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .safeDrawingPadding()
                                .padding(horizontal = 18.dp)
                                .padding(bottom = 84.dp),
                        )
                    }
                } else {
                    if (showPermissionDenied) {
                        ReverbMessageSheet(
                            title = stringResource(R.string.permission_required),
                            message = when {
                                !hasMicrophonePermission() -> getString(R.string.permission_required_message)
                                !hasLegacyStoragePermission() -> getString(R.string.storage_permission_required_message)
                                else -> getString(R.string.permission_required_message)
                            },
                            onDismiss = { showPermissionDenied = false },
                            confirmLabel = stringResource(R.string.allow),
                            onConfirm = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts(URI_SCHEME_PACKAGE, packageName, null)
                                }
                                startActivity(intent)
                            },
                        )
                    }
                    MainScreen(
                        permissionsGranted = permissionsGranted,
                        showPermissionDenied = showPermissionDenied,
                        onReviewMicrophonePermission = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts(URI_SCHEME_PACKAGE, packageName, null)
                            }
                            startActivity(intent)
                        },
                        onThemeChanged = {
                            themePresentation.preview(it, processCommittedTheme.state.value)
                            themeMode = themePresentation.mode
                        },
                    )
                }
            }
        }
    }

    private fun observeOnboardingFinish() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                processOnboardingFinish.state.collect { state ->
                    if (
                        state == OnboardingFinishState.SUCCEEDED && showOnboarding &&
                        !isFinishing && !isDestroyed &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        onboardingConfiguration = null
                        showOnboarding = false
                        beginPermissionFlow()
                    }
                }
            }
        }
    }

    private fun startOnboardingBufferHydration() {
        lifecycleScope.launch {
            var retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
            var failureReported = false
            while (showOnboarding && onboardingConfiguration == null) {
                val loaded = try {
                    withContext(Dispatchers.IO) {
                        getOnboardingConfiguration(applicationContext)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (loaded != null) {
                    if (showOnboarding) onboardingConfiguration = loaded
                    return@launch
                }
                if (!failureReported) {
                    AppFeedbackCenter.post(
                        getString(R.string.recorder_state_persist_failed),
                        FeedbackTone.ERROR,
                    )
                    failureReported = true
                }
                delay(retryDelayMillis)
                retryDelayMillis = nextDurableUiRetryDelayMillis(retryDelayMillis)
            }
        }
    }

    override fun onDestroy() {
        if (::themePresentation.isInitialized) themePresentation.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_MICROPHONE_PERMISSION_REQUESTED, microphonePermissionRequested)
        outState.putBoolean(STATE_STORAGE_PERMISSION_REQUESTED, storagePermissionRequested)
        outState.putBoolean(STATE_RECOVERY_PERMISSION_REQUESTED, recoveryPermissionRequested)
        outState.putBoolean(STATE_NOTIFICATION_PERMISSION_REQUESTED, notificationPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        microphonePermissionGranted = hasMicrophonePermission()
        storagePermissionGranted = hasLegacyStoragePermission()
        permissionsGranted = microphonePermissionGranted && storagePermissionGranted
        if (showOnboarding) {
            notificationPermissionGranted = hasNotificationPermission()
            mediaRecoveryAllowed = hasMediaRecoveryPermission()
            batteryOptimizationAllowed = isIgnoringBatteryOptimizations(this)
        } else {
            beginPermissionFlow()
        }
    }

    private fun beginPermissionFlow() {
        if (!hasMicrophonePermission()) {
            permissionsGranted = false
            if (microphonePermissionRequested) {
                showPermissionDenied = true
                return
            }
            microphonePermissionRequested = true
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!hasLegacyStoragePermission()) {
            permissionsGranted = false
            if (storagePermissionRequested) {
                showPermissionDenied = true
                return
            }
            storagePermissionRequested = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        permissionsGranted = true
        showPermissionDenied = false
        maybeRequestNotificationPermission()
    }

    private fun hasRequiredPermissions(): Boolean =
        hasMicrophonePermission() && hasLegacyStoragePermission()

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requiresLegacyStoragePermission(): Boolean = requiresLegacyPublicStoragePermission()

    private fun hasLegacyStoragePermission(): Boolean =
        !requiresLegacyStoragePermission() ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun mediaRecoveryPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
        else -> null
    }

    private fun hasMediaRecoveryPermission(): Boolean =
        mediaRecoveryPermission()?.let { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun maybeRequestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionRequested) return false
        if (hasNotificationPermission()) {
            notificationPermissionGranted = true
            return false
        }
        notificationPermissionRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
    }

    private fun applyConfiguredPlatformTheme(): AppThemeMode {
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val mode = getConfiguredThemeMode(this)
        val dark = mode.isDark(systemDark)
        setTheme(if (dark) R.style.Theme_Reverb_Dark else R.style.Theme_Reverb_Light)
        return mode
    }

    private fun applyPhonePortraitOnly() {
        requestedOrientation =
            if (resources.configuration.smallestScreenWidthDp >= 600) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
    }
}

private fun AppThemeMode.isDark(systemDarkTheme: Boolean): Boolean = when (this) {
    AppThemeMode.SYSTEM -> systemDarkTheme
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

private data class OnboardingStatus(
    val microphoneAllowed: Boolean,
    val storageAllowed: Boolean,
    val storagePermissionRequired: Boolean,
    val recoveryAllowed: Boolean,
    val recoveryPermissionRequired: Boolean,
    val notificationAllowed: Boolean,
    val notificationPermissionRequired: Boolean,
    val batteryOptimizationAllowed: Boolean,
)

private const val ONBOARDING_PAGE_COUNT = 3

@Composable
private fun OnboardingScreen(
    status: OnboardingStatus,
    initialOneShotEnabled: Boolean,
    initialLoopingEnabled: Boolean,
    initialAudio: OnboardingAudioSelection,
    compressionAvailable: Boolean,
    silenceValid: Boolean,
    finishing: Boolean,
    onRequestMicrophone: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestRecovery: () -> Unit,
    onRequestNotifications: () -> Unit,
    onReviewBatteryOptimization: () -> Unit,
    onFinish: (Boolean, Boolean, OnboardingAudioSelection, OnboardingAudioSelection) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
    val pageScrollStates = mutableListOf<ScrollState>()
    repeat(ONBOARDING_PAGE_COUNT) { pageScrollStates += rememberScrollState() }
    val scope = rememberCoroutineScope()
    val navigation = remember { OnboardingNavigationState() }
    fun navigateTo(target: Int, animate: Boolean = true) {
        if (!navigation.mayNavigate()) return
        val owner = navigation.begin()
        val job = scope.launch {
            if (animate) pagerState.animateScrollToPage(target)
            else pagerState.scrollToPage(target)
        }
        job.invokeOnCompletion { navigation.complete(owner) }
        navigation.attachCancellation(owner) { job.cancel() }
    }
    // The footer belongs to the settled page: another tap during a swipe cannot skip
    // a page or turn an in-flight Continue into Finish.
    val predictiveTarget = navigation.predictiveBackTarget
    val page = predictiveTarget?.sourcePage ?: pagerState.settledPage
    val validInitialOneShot = initialOneShotEnabled || !initialLoopingEnabled
    var oneShotEnabled by rememberSaveable { mutableStateOf(validInitialOneShot) }
    var loopingEnabled by rememberSaveable { mutableStateOf(initialLoopingEnabled) }
    val originalCompression by rememberSaveable { mutableStateOf(initialAudio.outputCompressionEnabled) }
    val originalSkipSilence by rememberSaveable { mutableStateOf(initialAudio.skipSilenceEnabled) }
    var outputCompression by rememberSaveable { mutableStateOf(initialAudio.outputCompressionEnabled) }
    var skipSilence by rememberSaveable { mutableStateOf(initialAudio.skipSilenceEnabled) }
    val backMotion = rememberPredictiveBackMotion(
        enabled = (page > 0 || predictiveTarget != null) && !finishing,
        onGestureStarted = {
            // Pin before cancelling a button animation: cancellation may settle the pager elsewhere.
            val target = navigation.beginPredictiveBack(pagerState.settledPage)
            pagerState.requestScrollToPage(target.sourcePage)
            PredictiveBackGestureCallbacks(
                onBack = {
                    if (navigation.ownsPredictiveBack(target)) {
                        pagerState.requestScrollToPage(target.destinationPage)
                    }
                },
                onFinished = { navigation.endPredictiveBack(target) },
            )
        },
        onBack = { navigateTo((pagerState.settledPage - 1).coerceAtLeast(0), animate = false) },
    )
    val backDirection = predictiveBackHorizontalDirection(backMotion.swipeEdge)
    val chrome = appChrome()
    val footerPage = predictiveTarget?.sourcePage ?: minOf(page, pagerState.targetPage)
    val ready = footerPage != 0 || (
        status.microphoneAllowed && status.storageAllowed &&
            (!status.notificationPermissionRequired || status.notificationAllowed) &&
            (!status.recoveryPermissionRequired || status.recoveryAllowed)
        )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Box(Modifier.weight(1f)) {
                if (backMotion.gestureActive && predictiveTarget != null && predictiveTarget.sourcePage > 0) {
                    OnboardingPage(
                        page = predictiveTarget.destinationPage,
                        scrollState = pageScrollStates[predictiveTarget.destinationPage],
                        status = status,
                        oneShotEnabled = oneShotEnabled,
                        loopingEnabled = loopingEnabled,
                        outputCompression = outputCompression,
                        skipSilence = skipSilence,
                        compressionAvailable = compressionAvailable,
                        silenceValid = silenceValid,
                        controlsEnabled = !finishing,
                        interactionEnabled = false,
                        onRequestMicrophone = {},
                        onRequestStorage = {},
                        onRequestRecovery = {},
                        onRequestNotifications = {},
                        onReviewBatteryOptimization = {},
                        onOneShotEnabledChange = {},
                        onLoopingEnabledChange = {},
                        onOutputCompressionChange = {},
                        onSkipSilenceChange = {},
                        modifier = Modifier.fillMaxSize().semantics { hideFromAccessibility() }
                            .graphicsLayer {
                                val backProgress = if (backMotion.gestureActive) backMotion.progress.value.coerceIn(0f, 1f) else 0f
                                translationX = -backDirection * size.width * 0.08f * (1f - backProgress)
                                val scale = 0.97f + 0.03f * backProgress
                                scaleX = scale
                                scaleY = scale
                                alpha = (backProgress * 1.45f).coerceIn(0f, 1f)
                            },
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !finishing && !backMotion.gestureActive,
                    key = { it },
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val backProgress = if (backMotion.gestureActive) backMotion.progress.value.coerceIn(0f, 1f) else 0f
                        translationX = backDirection * size.width * backProgress
                        alpha = 1f - 0.05f * backProgress
                    },
                ) { visiblePage ->
                    OnboardingPage(
                        page = visiblePage,
                        scrollState = pageScrollStates[visiblePage],
                        status = status,
                        oneShotEnabled = oneShotEnabled,
                        loopingEnabled = loopingEnabled,
                        outputCompression = outputCompression,
                        skipSilence = skipSilence,
                        compressionAvailable = compressionAvailable,
                        silenceValid = silenceValid,
                        controlsEnabled = !finishing,
                        interactionEnabled = !finishing,
                        onRequestMicrophone = onRequestMicrophone,
                        onRequestStorage = onRequestStorage,
                        onRequestRecovery = onRequestRecovery,
                        onRequestNotifications = onRequestNotifications,
                        onReviewBatteryOptimization = onReviewBatteryOptimization,
                        onOneShotEnabledChange = { oneShotEnabled = it },
                        onLoopingEnabledChange = { loopingEnabled = it },
                        onOutputCompressionChange = { outputCompression = it },
                        onSkipSilenceChange = { skipSilence = it },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(ONBOARDING_PAGE_COUNT) { index ->
                    if (index > 0) Spacer(Modifier.size(8.dp))
                    OnboardingProgressDot(active = page == index)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { navigateTo((pagerState.settledPage - 1).coerceAtLeast(0)) },
                    enabled = !finishing && page > 0,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.onboarding_back))
                }
                Button(
                    onClick = {
                        val currentPage = minOf(pagerState.settledPage, pagerState.targetPage)
                        if (currentPage < ONBOARDING_PAGE_COUNT - 1) {
                            navigateTo(currentPage + 1)
                        } else if (navigation.mayFinish(
                                onLastPage = pagerState.settledPage == ONBOARDING_PAGE_COUNT - 1,
                                scrolling = pagerState.isScrollInProgress,
                                predictiveBack = backMotion.gestureActive,
                            )
                        ) {
                            onFinish(
                                oneShotEnabled,
                                loopingEnabled,
                                OnboardingAudioSelection(originalCompression, originalSkipSilence),
                                OnboardingAudioSelection(outputCompression, skipSilence),
                            )
                        }
                    },
                    enabled = !finishing,
                    colors = if (ready) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                        containerColor = chrome.field,
                        contentColor = chrome.ink,
                    ),
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(
                        if (footerPage < ONBOARDING_PAGE_COUNT - 1) R.string.onboarding_continue
                        else R.string.onboarding_get_started,
                    ))
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(
    page: Int,
    scrollState: ScrollState,
    status: OnboardingStatus,
    oneShotEnabled: Boolean,
    loopingEnabled: Boolean,
    outputCompression: Boolean,
    skipSilence: Boolean,
    compressionAvailable: Boolean,
    silenceValid: Boolean,
    controlsEnabled: Boolean,
    interactionEnabled: Boolean,
    onRequestMicrophone: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestRecovery: () -> Unit,
    onRequestNotifications: () -> Unit,
    onReviewBatteryOptimization: () -> Unit,
    onOneShotEnabledChange: (Boolean) -> Unit,
    onLoopingEnabledChange: (Boolean) -> Unit,
    onOutputCompressionChange: (Boolean) -> Unit,
    onSkipSilenceChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(scrollState, enabled = interactionEnabled)) {
        Icon(
            imageVector = when (page) {
                0 -> AppIcons.backgroundWarning
                1 -> AppIcons.oneShot
                else -> AppIcons.capture
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(when (page) {
                0 -> R.string.onboarding_permissions_title
                1 -> R.string.onboarding_title
                else -> R.string.recording_settings_title
            }),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(24.dp))
        when (page) {
            0 -> {
                Text(
                    text = stringResource(R.string.onboarding_permissions_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OnboardingGroup {
                    OnboardingPermissionCard(
                        marker = "●",
                        title = stringResource(R.string.onboarding_microphone_title),
                        allowed = status.microphoneAllowed,
                        canRequest = interactionEnabled,
                        onAllow = onRequestMicrophone,
                    )
                    if (status.storagePermissionRequired) {
                        OnboardingPermissionCard(
                            marker = "S",
                            title = stringResource(R.string.storage_settings_title),
                            body = stringResource(R.string.onboarding_storage_body),
                            allowed = status.storageAllowed,
                            canRequest = interactionEnabled,
                            onAllow = onRequestStorage,
                            modifier = Modifier.settingsAudioDivider(top = true),
                        )
                    }
                    if (status.recoveryPermissionRequired) {
                        OnboardingPermissionCard(
                            marker = "R",
                            title = stringResource(R.string.recording_recovery_title),
                            body = stringResource(R.string.recording_recovery_body),
                            allowed = status.recoveryAllowed,
                            canRequest = interactionEnabled,
                            onAllow = onRequestRecovery,
                            modifier = Modifier.settingsAudioDivider(top = true),
                        )
                    }
                    OnboardingPermissionCard(
                        marker = "N",
                        title = stringResource(R.string.onboarding_notifications_title),
                        body = stringResource(R.string.onboarding_notifications_body),
                        allowed = status.notificationAllowed,
                        canRequest = interactionEnabled && status.notificationPermissionRequired,
                        onAllow = onRequestNotifications,
                        modifier = Modifier.settingsAudioDivider(top = true),
                    )
                    OnboardingPermissionCard(
                        marker = "B",
                        title = stringResource(R.string.onboarding_background_title),
                        body = stringResource(R.string.onboarding_background_body),
                        allowed = status.batteryOptimizationAllowed,
                        canRequest = interactionEnabled,
                        onAllow = onReviewBatteryOptimization,
                        modifier = Modifier.settingsAudioDivider(top = true),
                    )
                }
            }
            1 -> {
                OnboardingGroup {
                    OnboardingBufferCard(
                        icon = AppIcons.oneShot,
                        title = stringResource(R.string.onboarding_one_shot_title),
                        body = stringResource(R.string.onboarding_one_shot_body),
                        checked = oneShotEnabled,
                        enabled = controlsEnabled && (!oneShotEnabled || loopingEnabled),
                        interactionEnabled = interactionEnabled,
                        onCheckedChange = onOneShotEnabledChange,
                    )
                    OnboardingBufferCard(
                        icon = AppIcons.looping,
                        title = stringResource(R.string.onboarding_looping_title),
                        body = stringResource(R.string.onboarding_looping_body),
                        checked = loopingEnabled,
                        enabled = controlsEnabled && (!loopingEnabled || oneShotEnabled),
                        interactionEnabled = interactionEnabled,
                        onCheckedChange = onLoopingEnabledChange,
                        modifier = Modifier.settingsAudioDivider(top = true),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.onboarding_order),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                OnboardingGroup {
                    OnboardingAudioToggle(
                        title = stringResource(R.string.output_compression),
                        checked = outputCompression,
                        enabled = controlsEnabled && (compressionAvailable || outputCompression),
                        interactionEnabled = interactionEnabled,
                        onCheckedChange = onOutputCompressionChange,
                    )
                    if (!compressionAvailable) {
                        Text(
                            text = stringResource(R.string.flac_pcm_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    OnboardingAudioToggle(
                        title = stringResource(R.string.silence_title),
                        checked = skipSilence,
                        enabled = controlsEnabled,
                        interactionEnabled = interactionEnabled,
                        onCheckedChange = onSkipSilenceChange,
                        modifier = Modifier.settingsAudioDivider(top = true),
                    )
                    if (!silenceValid) {
                        Text(
                            text = stringResource(R.string.silence_invalid_settings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingGroup(content: @Composable () -> Unit) {
    val chrome = appChrome()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = chrome.field,
        border = androidx.compose.foundation.BorderStroke(1.dp, chrome.border),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun OnboardingPermissionCard(
    marker: String,
    title: String,
    body: String? = null,
    allowed: Boolean,
    canRequest: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionLabel = stringResource(R.string.allow)
    OnboardingCard(
        marker = marker,
        title = title,
        body = body,
        rowModifier = modifier.clickable(
            enabled = canRequest && !allowed,
            role = Role.Button,
            onClickLabel = actionLabel,
            onClick = onAllow,
        ),
        trailing = {
            Icon(
                imageVector = if (allowed) AppIcons.checked else AppIcons.unchecked,
                contentDescription = stringResource(if (allowed) R.string.onboarding_allowed else R.string.allow),
                tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

@Composable
private fun OnboardingBufferCard(
    icon: ImageVector,
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    interactionEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingCard(
        marker = null,
        markerIcon = icon,
        title = title,
        body = body,
        rowModifier = modifier.toggleable(
            value = checked,
            enabled = enabled && interactionEnabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun OnboardingAudioToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    interactionEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = appChrome()
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled && interactionEnabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) chrome.ink else chrome.muted,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun OnboardingCard(
    marker: String?,
    markerIcon: ImageVector? = null,
    title: String,
    body: String?,
    rowModifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val chrome = appChrome()
    Row(
        modifier = Modifier.fillMaxWidth().then(rowModifier).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (markerIcon != null) {
                    Icon(
                        imageVector = markerIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        text = marker.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = chrome.ink)
            if (!body.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = body, style = MaterialTheme.typography.bodyMedium, color = chrome.muted)
            }
        }
        trailing()
    }
}

@Composable
private fun OnboardingProgressDot(active: Boolean) {
    Surface(
        modifier = Modifier.size(width = if (active) 18.dp else 6.dp, height = 6.dp),
        shape = RoundedCornerShape(99.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    permissionsGranted: Boolean,
    showPermissionDenied: Boolean,
    onReviewMicrophonePermission: () -> Unit,
    onThemeChanged: (AppThemeMode?) -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsBufferTargetCode by rememberSaveable { mutableIntStateOf(-1) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showIncidents by rememberSaveable { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var librarySelectionActive by remember { mutableStateOf(false) }
    var libraryExpandedRecordingActive by remember { mutableStateOf(false) }
    var libraryUndoVisible by remember { mutableStateOf(false) }
    var panelDragTarget by remember { mutableStateOf<MainPanelDragTarget?>(null) }
    var settingsDragProgress by remember { mutableFloatStateOf(0f) }
    var libraryDragProgress by remember { mutableFloatStateOf(0f) }
    val settingsDragging = panelDragTarget == MainPanelDragTarget.SETTINGS
    val libraryDragging = panelDragTarget == MainPanelDragTarget.LIBRARY
    val settingsBackMotion = rememberPredictiveBackMotion(
        enabled = showSettings && !showAboutDialog,
        onBack = {
            showSettings = false
            settingsBufferTargetCode = -1
        },
    )
    val incidentsMotion = rememberIncidentsPanelMotion(showIncidents, PANEL_SETTLE_DURATION_MS)
    val incidentsBackMotion = rememberIncidentsPredictiveBackMotion(
        enabled = showIncidents && !showAboutDialog,
        onStarted = incidentsMotion::beginBack,
        onCancelled = { incidentsMotion.settleTo(showIncidents) },
        onDisabled = { interrupted -> incidentsMotion.resumeAfterBack(interrupted, showIncidents) },
        onBack = {
            incidentsMotion.snapTo(0f)
            showIncidents = false
        },
    )
    val incidentsOverlayVisible = showIncidents || incidentsMotion.hasVisibleProgress
    val libraryBackMotion = rememberPredictiveBackMotion(
        enabled = showLibrary && !showSettings && !incidentsOverlayVisible && !librarySelectionActive &&
            !libraryExpandedRecordingActive && !showAboutDialog,
        onBack = { showLibrary = false },
    )
    val settingsPredictiveDismissCommitted = settingsBackMotion.commitCompleted && !showSettings
    val libraryPredictiveDismissCommitted = libraryBackMotion.commitCompleted && !showLibrary
    val settingsSettledProgress by animateFloatAsState(
        targetValue = if (settingsDragging) settingsDragProgress else if (showSettings) 1f else 0f,
        animationSpec = if (settingsDragging || settingsPredictiveDismissCommitted) {
            snap()
        } else {
            tween(PANEL_SETTLE_DURATION_MS)
        },
        label = "settings-panel-progress",
    )
    val librarySettledProgress by animateFloatAsState(
        targetValue = if (libraryDragging) libraryDragProgress else if (showLibrary) 1f else 0f,
        animationSpec = if (libraryDragging || libraryPredictiveDismissCommitted) {
            snap()
        } else {
            tween(PANEL_SETTLE_DURATION_MS)
        },
        label = "library-panel-progress",
    )
    val settingsPanelProgress = predictiveBackPanelOpenProgress(
        settledProgress = settingsSettledProgress,
        gestureActive = settingsBackMotion.gestureActive,
        gestureProgress = settingsBackMotion.progress.value,
        commitCompleted = settingsBackMotion.commitCompleted,
        surfaceVisible = showSettings,
    )
    val libraryPanelProgress = predictiveBackPanelOpenProgress(
        settledProgress = librarySettledProgress,
        gestureActive = libraryBackMotion.gestureActive,
        gestureProgress = libraryBackMotion.progress.value,
        commitCompleted = libraryBackMotion.commitCompleted,
        surfaceVisible = showLibrary,
    )
    var librarySnapshot by remember { mutableStateOf<List<RecordingEntity>>(emptyList()) }
    val libraryRefreshGeneration = remember { intArrayOf(0) }
    val settingsCompositionRetained = remember { booleanArrayOf(false) }
    val libraryCompositionRetained = remember { booleanArrayOf(false) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val noiseBrush = rememberAppNoiseBrush()
    var incidentHistory by remember { mutableStateOf(IncidentHistoryUiState()) }
    val hasIncidentAlert = incidentHistory.history.hasAlert

    suspend fun loadIncidents(): Boolean {
        val loaded = try {
            withContext(Dispatchers.IO) {
                prepareIncidentHistory(RecordingIncidentStore.readIncidents(context))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            incidentHistory = incidentHistory.loadFailed()
            return false
        }
        incidentHistory = incidentHistory.loadSucceeded(loaded)
        return true
    }

    fun toggleIncidentAcknowledged(incident: RecordingIncident) {
        if (!incidentHistory.canMutate) return
        // The store owns this accepted user mutation past Activity/Compose lifetime. Its durable
        // revision stream remains the sole source of UI convergence for rapid toggles.
        RecordingIncidentStore.toggleIncidentAcknowledgedInBackground(context, incident)
    }

    fun openIncidents() {
        showIncidents = true
    }

    LaunchedEffect(Unit) {
        // StateFlow emits immediately for the initial load, then refreshes again whenever
        // recovery, resume-time completion, or checked-state changes durable history. Latest-only
        // collection cancels an obsolete read/retry when a newer durable revision arrives.
        RecordingIncidentStore.historyRevision.collectLatest {
            incidentHistory = incidentHistory.loadStarted()
            var retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
            while (!loadIncidents()) {
                delay(retryDelayMillis)
                retryDelayMillis = nextDurableUiRetryDelayMillis(retryDelayMillis)
            }
        }
    }

    fun refreshLibrarySnapshot() {
        val generation = ++libraryRefreshGeneration[0]
        scope.launch {
            try {
                val known = RecordingRepository.listKnown(context)
                if (generation != libraryRefreshGeneration[0]) return@launch
                librarySnapshot = known
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
            try {
                val refreshed = RecordingRepository.refresh(context)
                if (generation != libraryRefreshGeneration[0]) return@launch
                librarySnapshot = refreshed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
        }
    }

    fun closeLibrary() {
        showLibrary = false
    }

    val libraryTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val libraryContentTopPadding = libraryTopPadding + AppTopBarContentHeight

    Box(Modifier.fillMaxSize()) {
        if (
            releasedMainOracleShouldComposeMainPanel(
                previouslyComposed = settingsCompositionRetained[0],
                visible = showSettings || settingsDragging,
                progress = settingsPanelProgress,
            )
        ) {
            settingsCompositionRetained[0] = true
            SettingsScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .graphicsLayer { alpha = if (settingsPanelProgress > 0f || showSettings) 1f else 0.01f }
                    .semantics { if (!showSettings) hideFromAccessibility() },
                active = showSettings,
                isPanelActive = { showSettings && !showAboutDialog },
                onBack = {
                    showSettings = false
                    settingsBufferTargetCode = -1
                },
                onThemeChanged = onThemeChanged,
                focusRetentionBuffer = ReverbService.BufferSlot.fromStorageCode(settingsBufferTargetCode),
            )
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .graphicsLayer { translationY = settingsPanelProgress * size.height }
                .background(MaterialTheme.colorScheme.surface)
                .appNoise(noiseBrush)
                .semantics {
                    if (showSettings || showLibrary || incidentsOverlayVisible || showAboutDialog) hideFromAccessibility()
                }
                .pointerInput(showSettings, showLibrary, incidentsOverlayVisible, showAboutDialog) {
                    if (showSettings || showLibrary || incidentsOverlayVisible || showAboutDialog) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            settingsDragProgress = 0f
                            libraryDragProgress = 0f
                            panelDragTarget = when {
                                offset.y <= size.height * 0.48f -> {
                                    settingsBufferTargetCode = -1
                                    settingsBackMotion.beginForwardMotion()
                                    MainPanelDragTarget.SETTINGS
                                }
                                offset.y >= size.height * 0.52f -> {
                                    libraryBackMotion.beginForwardMotion()
                                    MainPanelDragTarget.LIBRARY
                                }
                                else -> null
                            }
                        },
                        onVerticalDrag = { change, amount ->
                            when (panelDragTarget) {
                                MainPanelDragTarget.SETTINGS -> {
                                    val distance = settingsDragProgress * size.height + amount
                                    settingsDragProgress = releasedMainOraclePanelRevealProgress(distance, size.height.toFloat())
                                    if (settingsDragProgress > 0f) change.consume()
                                }
                                MainPanelDragTarget.LIBRARY -> {
                                    val distance = libraryDragProgress * size.height - amount
                                    libraryDragProgress = releasedMainOraclePanelRevealProgress(distance, size.height.toFloat())
                                    if (libraryDragProgress > 0f) change.consume()
                                }
                                null -> Unit
                            }
                        },
                        onDragEnd = {
                            when (panelDragTarget) {
                                MainPanelDragTarget.SETTINGS -> {
                                    showSettings = releasedMainOracleShouldCommitPanelReveal(settingsDragProgress)
                                    if (!showSettings) settingsBufferTargetCode = -1
                                }
                                MainPanelDragTarget.LIBRARY -> {
                                    showLibrary = releasedMainOracleShouldCommitPanelReveal(libraryDragProgress)
                                }
                                null -> Unit
                            }
                            panelDragTarget = null
                        },
                        onDragCancel = {
                            panelDragTarget = null
                        },
                    )
                },
            containerColor = Color.Transparent,
            topBar = {
                AppTopBar(
                    onBrandClick = { showAboutDialog = true },
                    onIncidentsClick = ::openIncidents,
                    onSettingsClick = {
                        settingsBufferTargetCode = -1
                        showSettings = true
                    },
                    hasIncidents = hasIncidentAlert,
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                if (permissionsGranted) {
                    CaptureScreen(
                        visualizerVisible = !showSettings && !showLibrary && !incidentsOverlayVisible && !showAboutDialog,
                        isSaveFeedbackVisible = {
                            // Read live navigation state at delivery, including before recomposition.
                            !showSettings && !showLibrary && !showIncidents && !showAboutDialog &&
                                panelDragTarget == null && settingsSettledProgress == 0f &&
                                librarySettledProgress == 0f && !incidentsMotion.hasVisibleProgress
                        },
                        onOpenLibrary = { showLibrary = true },
                        onRecordingSaved = { refreshLibrarySnapshot() },
                        onOpenBufferSettings = { bufferSlot ->
                            settingsBufferTargetCode = bufferSlot.storageCode.toInt()
                            showSettings = true
                        },
                    )
                } else if (!showPermissionDenied) {
                    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                        Box(Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.permission_required_message),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = onReviewMicrophonePermission) {
                                    Text(stringResource(R.string.allow))
                                }
                            }
                            Surface(
                                onClick = { showLibrary = true },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 22.dp)
                                    .size(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = AppIcons.library,
                                        contentDescription = stringResource(R.string.files_tab),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(25.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AppFeedbackHost(
            deliveryAllowed = !showAboutDialog &&
                !(showLibrary && !incidentsOverlayVisible && libraryUndoVisible),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(when {
                    incidentsOverlayVisible -> 7f
                    showLibrary || libraryPanelProgress > 0f -> 5f
                    else -> 2f
                })
                .padding(horizontal = 18.dp)
                .padding(bottom = when {
                    incidentsOverlayVisible || showLibrary || libraryPanelProgress > 0f ->
                        20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    showSettings -> 20.dp
                    else -> 104.dp
                }),
        )

        if (
            releasedMainOracleShouldComposeMainPanel(
                previouslyComposed = libraryCompositionRetained[0],
                visible = showLibrary || libraryDragging,
                progress = libraryPanelProgress,
            )
        ) {
            libraryCompositionRetained[0] = true
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (showLibrary || libraryPanelProgress > 0f) 3f else -2f)
                    .graphicsLayer { alpha = if (libraryPanelProgress > 0f || showLibrary) 1f else 0.01f }
                    .semantics { if (!showLibrary) hideFromAccessibility() },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = libraryContentTopPadding)
                        .graphicsLayer { alpha = libraryPanelProgress }
                        .background(BottomSheetDefaults.ScrimColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = (1f - libraryPanelProgress) * size.height },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = libraryContentTopPadding)
                            .background(MaterialTheme.colorScheme.surface)
                            .appNoise(noiseBrush),
                    )
                    FilesScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = libraryTopPadding),
                        active = showLibrary && !incidentsOverlayVisible && !showAboutDialog,
                        initialRecordings = librarySnapshot,
                        onSelectionActiveChange = { librarySelectionActive = it },
                        onExpandedRecordingActiveChange = { libraryExpandedRecordingActive = it },
                        onUndoVisibleChange = { libraryUndoVisible = it },
                        showNormalTopBar = false,
                        onVisibleRecordingsChanged = { visible ->
                            if (librarySnapshot != visible) {
                                ++libraryRefreshGeneration[0]
                                librarySnapshot = visible
                            }
                        },
                        onParentRefreshRequested = { refreshLibrarySnapshot() },
                        onBrandClick = { showAboutDialog = true },
                        onIncidentsClick = {
                            closeLibrary()
                            openIncidents()
                        },
                        onSettingsClick = {
                            closeLibrary()
                            settingsBufferTargetCode = -1
                            showSettings = true
                        },
                        hasIncidents = hasIncidentAlert,
                        onDismissLibrary = ::closeLibrary,
                    )
                }
            }
        }

        if (!showSettings && libraryPanelProgress > 0f && !librarySelectionActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(4f),
            ) {
                AppTopBar(
                    onBackClick = ::closeLibrary,
                    onBrandClick = { showAboutDialog = true },
                    onIncidentsClick = {
                        closeLibrary()
                        openIncidents()
                    },
                    onSettingsClick = {
                        closeLibrary()
                        settingsBufferTargetCode = -1
                        showSettings = true
                    },
                    hasIncidents = hasIncidentAlert,
                )
            }
        }

        if (incidentsOverlayVisible) {
            IncidentsScreen(
                state = incidentHistory,
                onBack = { showIncidents = false },
                onToggleAcknowledged = ::toggleIncidentAcknowledged,
                onDelete = {
                    if (incidentHistory.canMutate) RecordingIncidentStore.deleteIncidentInBackground(context, it)
                },
                openProgress = {
                    incidentsPanelOpenProgress(
                        settledProgress = incidentsMotion.progress,
                        gestureStartOpenProgress = incidentsBackMotion.startOpenProgress,
                        gestureActive = incidentsBackMotion.gestureActive,
                        gestureProgress = incidentsBackMotion.progress.value,
                        commitCompleted = incidentsBackMotion.commitCompleted,
                        surfaceVisible = showIncidents,
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(6f),
            )
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}
