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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private const val URI_SCHEME_PACKAGE = "package"
private const val STATE_MICROPHONE_PERMISSION_REQUESTED = "microphone_permission_requested"
private const val STATE_STORAGE_PERMISSION_REQUESTED = "storage_permission_requested"
private const val STATE_RECOVERY_PERMISSION_REQUESTED = "recovery_permission_requested"
private const val STATE_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
private const val PANEL_COMMIT_PROGRESS = 0.12f
private const val PANEL_SETTLE_DURATION_MS = 220

private enum class MainPanelDragTarget { SETTINGS, LIBRARY }

internal fun panelRevealProgress(dragDistancePx: Float, viewportHeightPx: Float): Float {
    if (viewportHeightPx <= 0f) return 0f
    return (dragDistancePx / viewportHeightPx).coerceIn(0f, 1f)
}

internal fun shouldCommitPanelReveal(progress: Float): Boolean =
    progress.coerceIn(0f, 1f) >= PANEL_COMMIT_PROGRESS

internal fun shouldComposeMainPanel(
    previouslyComposed: Boolean,
    visible: Boolean,
    progress: Float,
): Boolean = previouslyComposed || visible || progress > 0f

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private var mediaRecoveryAllowed by mutableStateOf(false)
    private var batteryOptimizationAllowed by mutableStateOf(false)
    private var showPermissionDenied by mutableStateOf(false)
    private var showOnboarding by mutableStateOf(false)
    private var themeMode by mutableStateOf(AppThemeMode.SYSTEM)

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionsGranted = hasRequiredPermissions()
            showPermissionDenied = !granted && !showOnboarding
            if (granted && !showOnboarding) beginPermissionFlow()
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionsGranted = hasRequiredPermissions()
            showPermissionDenied = !granted && !showOnboarding
            if (granted && !showOnboarding) beginPermissionFlow()
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
        permissionsGranted = hasRequiredPermissions()
        showOnboarding = isOnboardingPending(this)
        if (showOnboarding) {
            notificationPermissionGranted = hasNotificationPermission()
            mediaRecoveryAllowed = hasMediaRecoveryPermission()
            batteryOptimizationAllowed = isIgnoringBatteryOptimizations(this)
        }
        themeMode = configuredThemeMode
        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            ReverbTheme(darkTheme = themeMode.isDark(systemDarkTheme)) {
                if (showOnboarding) {
                    OnboardingScreen(
                        microphoneAllowed = hasMicrophonePermission(),
                        storageAllowed = hasLegacyStoragePermission(),
                        storagePermissionRequired = requiresLegacyStoragePermission(),
                        recoveryAllowed = mediaRecoveryAllowed,
                        recoveryPermissionRequired = mediaRecoveryPermission() != null,
                        notificationAllowed = notificationPermissionGranted,
                        notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        batteryOptimizationAllowed = batteryOptimizationAllowed,
                        initialOneShotEnabled = isConfiguredOneShotBufferEnabled(this),
                        initialLoopingEnabled = isConfiguredLoopingBufferEnabled(this),
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
                            if (!openBatteryOptimizationReview(this)) {
                                AppFeedbackCenter.post(
                                    getString(R.string.no_app_available),
                                    FeedbackTone.ERROR,
                                )
                            }
                        },
                        onFinish = { oneShotEnabled, loopingEnabled ->
                            if (finishOnboarding(this, oneShotEnabled, loopingEnabled)) {
                                showOnboarding = false
                                beginPermissionFlow()
                            } else {
                                AppFeedbackCenter.post(
                                    getString(R.string.recorder_state_persist_failed),
                                    FeedbackTone.ERROR,
                                )
                            }
                        },
                    )
                } else {
                    if (showPermissionDenied) {
                        PermissionDeniedSheet(
                            message = requiredPermissionMessage(),
                            onAllow = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts(URI_SCHEME_PACKAGE, packageName, null)
                                }
                                startActivity(intent)
                            },
                            onDismiss = { showPermissionDenied = false },
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
                        onThemeChanged = { themeMode = it },
                    )
                }
            }
        }
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
        permissionsGranted = hasRequiredPermissions()
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

    private fun requiredPermissionMessage(): String = when {
        !hasMicrophonePermission() -> getString(R.string.permission_required_message)
        !hasLegacyStoragePermission() -> getString(R.string.storage_permission_required_message)
        else -> getString(R.string.permission_required_message)
    }

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

@Composable
private fun OnboardingScreen(
    microphoneAllowed: Boolean,
    storageAllowed: Boolean,
    storagePermissionRequired: Boolean,
    recoveryAllowed: Boolean,
    recoveryPermissionRequired: Boolean,
    notificationAllowed: Boolean,
    notificationPermissionRequired: Boolean,
    batteryOptimizationAllowed: Boolean,
    initialOneShotEnabled: Boolean,
    initialLoopingEnabled: Boolean,
    onRequestMicrophone: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestRecovery: () -> Unit,
    onRequestNotifications: () -> Unit,
    onReviewBatteryOptimization: () -> Unit,
    onFinish: (oneShotEnabled: Boolean, loopingEnabled: Boolean) -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val validInitialOneShot = initialOneShotEnabled || !initialLoopingEnabled
    var oneShotEnabled by rememberSaveable { mutableStateOf(validInitialOneShot) }
    var loopingEnabled by rememberSaveable { mutableStateOf(initialLoopingEnabled) }
    val backMotion = rememberPredictiveBackMotion(
        enabled = page > 0,
        onBack = { page-- },
    )
    val backProgress = if (backMotion.gestureActive) backMotion.progress.value.coerceIn(0f, 1f) else 0f
    val backDirection = predictiveBackHorizontalDirection(backMotion.swipeEdge)

    Box(Modifier.fillMaxSize()) {
        if (backMotion.gestureActive && page > 0) {
            OnboardingPage(
                page = page - 1,
                microphoneAllowed = microphoneAllowed,
                storageAllowed = storageAllowed,
                storagePermissionRequired = storagePermissionRequired,
                recoveryAllowed = recoveryAllowed,
                recoveryPermissionRequired = recoveryPermissionRequired,
                notificationAllowed = notificationAllowed,
                notificationPermissionRequired = notificationPermissionRequired,
                batteryOptimizationAllowed = batteryOptimizationAllowed,
                oneShotEnabled = oneShotEnabled,
                loopingEnabled = loopingEnabled,
                onRequestMicrophone = {},
                onRequestStorage = {},
                onRequestRecovery = {},
                onRequestNotifications = {},
                onReviewBatteryOptimization = {},
                onOneShotEnabledChange = {},
                onLoopingEnabledChange = {},
                onBackPage = {},
                onContinue = {},
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = -backDirection * size.width * 0.08f * (1f - backProgress)
                        val scale = 0.97f + 0.03f * backProgress
                        scaleX = scale
                        scaleY = scale
                        alpha = (backProgress * 1.45f).coerceIn(0f, 1f)
                    },
            )
        }

        OnboardingPage(
            page = page,
            microphoneAllowed = microphoneAllowed,
            storageAllowed = storageAllowed,
            storagePermissionRequired = storagePermissionRequired,
            recoveryAllowed = recoveryAllowed,
            recoveryPermissionRequired = recoveryPermissionRequired,
            notificationAllowed = notificationAllowed,
            notificationPermissionRequired = notificationPermissionRequired,
            batteryOptimizationAllowed = batteryOptimizationAllowed,
            oneShotEnabled = oneShotEnabled,
            loopingEnabled = loopingEnabled,
            onRequestMicrophone = onRequestMicrophone,
            onRequestStorage = onRequestStorage,
            onRequestRecovery = onRequestRecovery,
            onRequestNotifications = onRequestNotifications,
            onReviewBatteryOptimization = onReviewBatteryOptimization,
            onOneShotEnabledChange = { oneShotEnabled = it },
            onLoopingEnabledChange = { loopingEnabled = it },
            onBackPage = { page-- },
            onContinue = {
                if (page < 2) page++ else onFinish(oneShotEnabled, loopingEnabled)
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = backDirection * size.width * backProgress
                    alpha = 1f - 0.05f * backProgress
                },
        )
    }
}

@Composable
private fun OnboardingPage(
    page: Int,
    microphoneAllowed: Boolean,
    storageAllowed: Boolean,
    storagePermissionRequired: Boolean,
    recoveryAllowed: Boolean,
    recoveryPermissionRequired: Boolean,
    notificationAllowed: Boolean,
    notificationPermissionRequired: Boolean,
    batteryOptimizationAllowed: Boolean,
    oneShotEnabled: Boolean,
    loopingEnabled: Boolean,
    onRequestMicrophone: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestRecovery: () -> Unit,
    onRequestNotifications: () -> Unit,
    onReviewBatteryOptimization: () -> Unit,
    onOneShotEnabledChange: (Boolean) -> Unit,
    onLoopingEnabledChange: (Boolean) -> Unit,
    onBackPage: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = Color.Transparent) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                when (page) {
                    0 -> {
                        Text(
                            text = stringResource(R.string.onboarding_permissions_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_permissions_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        OnboardingPermissionCard(
                            marker = "●",
                            title = stringResource(R.string.onboarding_microphone_title),
                            body = stringResource(R.string.onboarding_microphone_body),
                            allowed = microphoneAllowed,
                            canRequest = true,
                            onAllow = onRequestMicrophone,
                        )
                        if (storagePermissionRequired) {
                            Spacer(Modifier.height(12.dp))
                            OnboardingPermissionCard(
                                marker = "S",
                                title = stringResource(R.string.storage_settings_title),
                                body = stringResource(R.string.onboarding_storage_body),
                                allowed = storageAllowed,
                                canRequest = true,
                                onAllow = onRequestStorage,
                            )
                        }
                        if (recoveryPermissionRequired) {
                            Spacer(Modifier.height(12.dp))
                            OnboardingPermissionCard(
                                marker = "R",
                                title = stringResource(R.string.recording_recovery_title),
                                body = stringResource(R.string.recording_recovery_body),
                                allowed = recoveryAllowed,
                                canRequest = true,
                                onAllow = onRequestRecovery,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OnboardingPermissionCard(
                            marker = "N",
                            title = stringResource(R.string.onboarding_notifications_title),
                            body = stringResource(R.string.onboarding_notifications_body),
                            allowed = notificationAllowed,
                            canRequest = notificationPermissionRequired,
                            onAllow = onRequestNotifications,
                        )
                    }
                    1 -> {
                        Text(
                            text = stringResource(R.string.onboarding_background_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_background_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        BackgroundOptimizationWarning(
                            restricted = !batteryOptimizationAllowed,
                            onReview = onReviewBatteryOptimization,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.onboarding_background_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.onboarding_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(24.dp))
                        OnboardingBufferCard(
                            icon = AppIcons.oneShot,
                            title = stringResource(R.string.onboarding_one_shot_title),
                            body = stringResource(R.string.onboarding_one_shot_body),
                            checked = oneShotEnabled,
                            enabled = !oneShotEnabled || loopingEnabled,
                            onCheckedChange = onOneShotEnabledChange,
                        )
                        Spacer(Modifier.height(12.dp))
                        OnboardingBufferCard(
                            icon = AppIcons.looping,
                            title = stringResource(R.string.onboarding_looping_title),
                            body = stringResource(R.string.onboarding_looping_body),
                            checked = loopingEnabled,
                            enabled = !loopingEnabled || oneShotEnabled,
                            onCheckedChange = onLoopingEnabledChange,
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = stringResource(R.string.onboarding_order),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OnboardingProgressDot(active = page == 0)
                Spacer(Modifier.size(8.dp))
                OnboardingProgressDot(active = page == 1)
                Spacer(Modifier.size(8.dp))
                OnboardingProgressDot(active = page == 2)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page > 0) {
                    TextButton(onClick = onBackPage) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (page < 2) {
                            stringResource(R.string.onboarding_continue)
                        } else {
                            stringResource(R.string.onboarding_get_started)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPermissionCard(
    marker: String,
    title: String,
    body: String,
    allowed: Boolean,
    canRequest: Boolean,
    onAllow: () -> Unit,
) {
    OnboardingCard(
        marker = marker,
        title = title,
        body = body,
        trailing = {
            Button(
                onClick = onAllow,
                enabled = canRequest && !allowed,
            ) {
                Text(
                    if (allowed || !canRequest) {
                        stringResource(R.string.onboarding_allowed)
                    } else {
                        stringResource(R.string.allow)
                    },
                )
            }
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
    onCheckedChange: (Boolean) -> Unit,
) {
    OnboardingCard(
        marker = null,
        markerIcon = icon,
        title = title,
        body = body,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun OnboardingCard(
    marker: String?,
    markerIcon: ImageVector? = null,
    title: String,
    body: String,
    trailing: @Composable () -> Unit,
) {
    val chrome = appChrome()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = chrome.field,
        border = androidx.compose.foundation.BorderStroke(1.dp, chrome.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                        androidx.compose.material3.Icon(
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
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
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

@Composable
private fun PermissionDeniedSheet(
    message: String,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    ReverbActionSheet(
        title = stringResource(R.string.permission_required),
        onDismiss = onDismiss,
        content = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAllow) { Text(stringResource(R.string.allow)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    permissionsGranted: Boolean,
    showPermissionDenied: Boolean,
    onReviewMicrophonePermission: () -> Unit,
    onThemeChanged: (AppThemeMode) -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsBufferTargetCode by rememberSaveable { mutableIntStateOf(-1) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var librarySelectionActive by remember { mutableStateOf(false) }
    var libraryExpandedRecordingActive by remember { mutableStateOf(false) }
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
    val libraryBackMotion = rememberPredictiveBackMotion(
        enabled = showLibrary && !showSettings && !librarySelectionActive &&
            !libraryExpandedRecordingActive && !showAboutDialog,
        onBack = { showLibrary = false },
    )
    val settingsSettledProgress by animateFloatAsState(
        targetValue = if (settingsDragging) settingsDragProgress else if (showSettings) 1f else 0f,
        animationSpec = if (settingsDragging) snap() else tween(PANEL_SETTLE_DURATION_MS),
        label = "settings-panel-progress",
    )
    val librarySettledProgress by animateFloatAsState(
        targetValue = if (libraryDragging) libraryDragProgress else if (showLibrary) 1f else 0f,
        animationSpec = if (libraryDragging) snap() else tween(PANEL_SETTLE_DURATION_MS),
        label = "library-panel-progress",
    )
    val settingsPanelProgress = if (settingsBackMotion.gestureActive) {
        predictiveBackOpenProgress(settingsBackMotion.progress.value)
    } else {
        settingsSettledProgress
    }
    val libraryPanelProgress = if (libraryBackMotion.gestureActive) {
        predictiveBackOpenProgress(libraryBackMotion.progress.value)
    } else {
        librarySettledProgress
    }
    var librarySnapshot by remember { mutableStateOf<List<RecordingEntity>>(emptyList()) }
    val libraryRefreshGeneration = remember { intArrayOf(0) }
    val settingsCompositionRetained = remember { booleanArrayOf(false) }
    val libraryCompositionRetained = remember { booleanArrayOf(false) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val noiseBrush = rememberAppNoiseBrush()

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
            shouldComposeMainPanel(
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
                    if (showSettings || showLibrary || showAboutDialog) hideFromAccessibility()
                }
                .pointerInput(showSettings, showLibrary, showAboutDialog) {
                    if (showSettings || showLibrary || showAboutDialog) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            settingsDragProgress = 0f
                            libraryDragProgress = 0f
                            panelDragTarget = when {
                                offset.y <= size.height * 0.48f -> {
                                    settingsBufferTargetCode = -1
                                    MainPanelDragTarget.SETTINGS
                                }
                                offset.y >= size.height * 0.52f -> MainPanelDragTarget.LIBRARY
                                else -> null
                            }
                        },
                        onVerticalDrag = { change, amount ->
                            when (panelDragTarget) {
                                MainPanelDragTarget.SETTINGS -> {
                                    val distance = settingsDragProgress * size.height + amount
                                    settingsDragProgress = panelRevealProgress(distance, size.height.toFloat())
                                    if (settingsDragProgress > 0f) change.consume()
                                }
                                MainPanelDragTarget.LIBRARY -> {
                                    val distance = libraryDragProgress * size.height - amount
                                    libraryDragProgress = panelRevealProgress(distance, size.height.toFloat())
                                    if (libraryDragProgress > 0f) change.consume()
                                }
                                null -> Unit
                            }
                        },
                        onDragEnd = {
                            when (panelDragTarget) {
                                MainPanelDragTarget.SETTINGS -> {
                                    showSettings = shouldCommitPanelReveal(settingsDragProgress)
                                    if (!showSettings) settingsBufferTargetCode = -1
                                }
                                MainPanelDragTarget.LIBRARY -> {
                                    showLibrary = shouldCommitPanelReveal(libraryDragProgress)
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
                    onSettingsClick = {
                        settingsBufferTargetCode = -1
                        showSettings = true
                    },
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                if (permissionsGranted) {
                    CaptureScreen(
                        visualizerVisible = !showSettings && !showLibrary && !showAboutDialog,
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
                .padding(horizontal = 18.dp)
                .padding(bottom = if (showSettings) 20.dp else 104.dp),
        )

        if (
            shouldComposeMainPanel(
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
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .appNoise(noiseBrush),
                    )
                    FilesScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = libraryTopPadding),
                        active = showLibrary && !showAboutDialog,
                        initialRecordings = librarySnapshot,
                        onSelectionActiveChange = { librarySelectionActive = it },
                        onExpandedRecordingActiveChange = { libraryExpandedRecordingActive = it },
                        showNormalTopBar = false,
                        onVisibleRecordingsChanged = { visible ->
                            if (librarySnapshot != visible) {
                                ++libraryRefreshGeneration[0]
                                librarySnapshot = visible
                            }
                        },
                        onParentRefreshRequested = { refreshLibrarySnapshot() },
                        onBrandClick = { showAboutDialog = true },
                        onSettingsClick = {
                            closeLibrary()
                            settingsBufferTargetCode = -1
                            showSettings = true
                        },
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
                    onBrandClick = { showAboutDialog = true },
                    onSettingsClick = {
                        closeLibrary()
                        settingsBufferTargetCode = -1
                        showSettings = true
                    },
                )
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}
