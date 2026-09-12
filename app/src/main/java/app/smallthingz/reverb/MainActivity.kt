package app.smallthingz.reverb

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private const val URI_SCHEME_PACKAGE = "package"
private const val STATE_MICROPHONE_PERMISSION_REQUESTED = "microphone_permission_requested"
private const val STATE_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private var batteryOptimizationAllowed by mutableStateOf(false)
    private var showPermissionDenied by mutableStateOf(false)
    private var showOnboarding by mutableStateOf(false)
    private var themeMode by mutableStateOf(AppThemeMode.SYSTEM)

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionsGranted = granted
            showPermissionDenied = !granted && !showOnboarding
            if (granted && !showOnboarding) {
                maybeRequestNotificationPermission()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionGranted = granted
        }

    private var microphonePermissionRequested = false
    private var notificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPhonePortraitOnly()
        super.onCreate(savedInstanceState)
        microphonePermissionRequested =
            savedInstanceState?.getBoolean(STATE_MICROPHONE_PERMISSION_REQUESTED) ?: false
        notificationPermissionRequested =
            savedInstanceState?.getBoolean(STATE_NOTIFICATION_PERMISSION_REQUESTED) ?: false
        permissionsGranted = hasRequiredPermissions()
        notificationPermissionGranted = hasNotificationPermission()
        batteryOptimizationAllowed = isIgnoringBatteryOptimizations(this)
        showOnboarding = isOnboardingPending(this)
        RecordingRepository.schedulePersistedPermissionCleanup(this)
        themeMode = getConfiguredThemeMode(this)
        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            ReverbTheme(darkTheme = themeMode.isDark(systemDarkTheme)) {
                if (showOnboarding) {
                    OnboardingScreen(
                        microphoneAllowed = permissionsGranted,
                        notificationAllowed = notificationPermissionGranted,
                        notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        batteryOptimizationAllowed = batteryOptimizationAllowed,
                        initialOneShotEnabled = isConfiguredOneShotBufferEnabled(this),
                        initialLoopingEnabled = isConfiguredLoopingBufferEnabled(this),
                        onRequestMicrophone = {
                            microphonePermissionRequested = true
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                        PermissionDeniedDialog(
                            onAllow = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts(URI_SCHEME_PACKAGE, packageName, null)
                                }
                                startActivity(intent)
                            },
                            onExit = { finish() },
                        )
                    }
                    MainScreen(
                        permissionsGranted = permissionsGranted,
                        showPermissionDenied = showPermissionDenied,
                        onThemeChanged = { themeMode = it },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_MICROPHONE_PERMISSION_REQUESTED, microphonePermissionRequested)
        outState.putBoolean(STATE_NOTIFICATION_PERMISSION_REQUESTED, notificationPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        permissionsGranted = hasRequiredPermissions()
        notificationPermissionGranted = hasNotificationPermission()
        batteryOptimizationAllowed = isIgnoringBatteryOptimizations(this)
        if (!showOnboarding) beginPermissionFlow()
    }

    private fun beginPermissionFlow() {
        if (hasRequiredPermissions()) {
            permissionsGranted = true
            showPermissionDenied = false
            maybeRequestNotificationPermission()
            return
        }
        permissionsGranted = false
        if (microphonePermissionRequested) {
            showPermissionDenied = true
            return
        }
        microphonePermissionRequested = true
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasRequiredPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
    notificationAllowed: Boolean,
    notificationPermissionRequired: Boolean,
    batteryOptimizationAllowed: Boolean,
    initialOneShotEnabled: Boolean,
    initialLoopingEnabled: Boolean,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onReviewBatteryOptimization: () -> Unit,
    onFinish: (oneShotEnabled: Boolean, loopingEnabled: Boolean) -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val validInitialOneShot = initialOneShotEnabled || !initialLoopingEnabled
    var oneShotEnabled by rememberSaveable { mutableStateOf(validInitialOneShot) }
    var loopingEnabled by rememberSaveable { mutableStateOf(initialLoopingEnabled) }

    Surface(Modifier.fillMaxSize()) {
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
                            marker = "1",
                            title = stringResource(R.string.onboarding_one_shot_title),
                            body = stringResource(R.string.onboarding_one_shot_body),
                            checked = oneShotEnabled,
                            enabled = !oneShotEnabled || loopingEnabled,
                            onCheckedChange = { oneShotEnabled = it },
                        )
                        Spacer(Modifier.height(12.dp))
                        OnboardingBufferCard(
                            marker = "↻",
                            title = stringResource(R.string.onboarding_looping_title),
                            body = stringResource(R.string.onboarding_looping_body),
                            checked = loopingEnabled,
                            enabled = !loopingEnabled || oneShotEnabled,
                            onCheckedChange = { loopingEnabled = it },
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
                    TextButton(onClick = { page-- }) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                }
                Button(
                    onClick = {
                        if (page < 2) page++ else onFinish(oneShotEnabled, loopingEnabled)
                    },
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
    marker: String,
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    OnboardingCard(
        marker = marker,
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
    marker: String,
    title: String,
    body: String,
    trailing: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    Text(
                        text = marker,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
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
private fun PermissionDeniedDialog(
    onAllow: () -> Unit,
    onExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.permission_required)) },
        text = { Text(stringResource(R.string.permission_required_message)) },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.exit))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    permissionsGranted: Boolean,
    showPermissionDenied: Boolean,
    onThemeChanged: (AppThemeMode) -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var libraryCount by rememberSaveable { mutableIntStateOf(0) }
    var librarySnapshot by remember { mutableStateOf<List<RecordingEntity>>(emptyList()) }
    val libraryRefreshGeneration = remember { intArrayOf(0) }
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val librarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val openPanelDistancePx = with(density) { 52.dp.toPx() }

    fun refreshLibrarySnapshot() {
        val generation = ++libraryRefreshGeneration[0]
        scope.launch {
            try {
                val known = RecordingRepository.listKnown(context)
                if (generation != libraryRefreshGeneration[0]) return@launch
                librarySnapshot = known
                libraryCount = known.size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
            try {
                val refreshed = RecordingRepository.refresh(context)
                if (generation != libraryRefreshGeneration[0]) return@launch
                librarySnapshot = refreshed
                libraryCount = refreshed.size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
        }
    }

    LaunchedEffect(Unit) { refreshLibrarySnapshot() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshLibrarySnapshot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = showSettings,
            transitionSpec = {
                if (targetState) {
                    slideInVertically(animationSpec = tween(220)) { -it } togetherWith
                        slideOutVertically(animationSpec = tween(180)) { it / 10 }
                } else {
                    slideInVertically(animationSpec = tween(180)) { it / 10 } togetherWith
                        slideOutVertically(animationSpec = tween(220)) { -it }
                }
            },
            label = "settingsSheet",
        ) { settingsVisible ->
            if (settingsVisible) {
                SettingsScreen(
                    onBack = { showSettings = false },
                    onThemeChanged = onThemeChanged,
                )
            } else {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(permissionsGranted, showLibrary, showAboutDialog, libraryCount) {
                            if (!permissionsGranted || showLibrary || showAboutDialog) return@pointerInput
                            var dragStartY = 0f
                            var downwardDrag = 0f
                            var upwardDrag = 0f
                            var triggered = false
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    dragStartY = offset.y
                                    downwardDrag = 0f
                                    upwardDrag = 0f
                                    triggered = false
                                },
                                onVerticalDrag = { _, amount ->
                                    if (!triggered) {
                                        val topRegionEnd = size.height * 0.48f
                                        val bottomRegionStart = size.height * 0.52f
                                        if (dragStartY <= topRegionEnd && amount > 0f) {
                                            downwardDrag += amount
                                            if (downwardDrag >= openPanelDistancePx) {
                                                triggered = true
                                                showSettings = true
                                            }
                                        } else if (dragStartY >= bottomRegionStart && amount < 0f) {
                                            upwardDrag -= amount
                                            if (libraryCount > 0 && upwardDrag >= openPanelDistancePx) {
                                                triggered = true
                                                showLibrary = true
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    topBar = {
                        AppTopBar(
                            onBrandClick = { showAboutDialog = true },
                            onSettingsClick = { showSettings = true },
                        )
                    },
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        if (permissionsGranted) {
                            CaptureScreen(
                                showLibraryButton = libraryCount > 0,
                                visualizerVisible = !showSettings && !showLibrary && !showAboutDialog,
                                onOpenLibrary = { showLibrary = true },
                                onRecordingSaved = { refreshLibrarySnapshot() },
                            )
                        } else if (!showPermissionDenied) {
                            Surface(Modifier.fillMaxSize()) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = stringResource(R.string.permission_required_message),
                                        modifier = Modifier.padding(24.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .padding(horizontal = 18.dp)
                .padding(bottom = if (showSettings) 20.dp else 104.dp),
        )
    }

    if (showLibrary && libraryCount > 0) {
        ModalBottomSheet(
            onDismissRequest = {
                showLibrary = false
                refreshLibrarySnapshot()
            },
            sheetState = librarySheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            FilesScreen(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f),
                initialRecordings = librarySnapshot,
                onRecordingCountChanged = { count ->
                    libraryCount = count
                    if (count == 0) {
                        librarySnapshot = emptyList()
                        showLibrary = false
                    }
                },
            )
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}
