package app.smallthingz.reverb

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.core.net.toUri
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
    private var showPermissionDenied by mutableStateOf(false)
    private var showBatteryOptimizationPrompt by mutableStateOf(false)
    private var themeMode by mutableStateOf(AppThemeMode.SYSTEM)
    private var batteryOptimizationPromptPending = false

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionsGranted = true
                showPermissionDenied = false
                if (!maybeRequestNotificationPermission()) {
                    maybeShowBatteryOptimizationPrompt()
                }
            } else {
                showPermissionDenied = true
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            maybeShowBatteryOptimizationPrompt()
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
        batteryOptimizationPromptPending = isBatteryOptimizationStartupPromptPending(this)
        RecordingRepository.schedulePersistedPermissionCleanup(this)
        themeMode = getConfiguredThemeMode(this)
        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            ReverbTheme(darkTheme = themeMode.isDark(systemDarkTheme)) {
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
                } else if (permissionsGranted && showBatteryOptimizationPrompt) {
                    BatteryOptimizationPromptDialog(
                        onAllow = {
                            batteryOptimizationPromptPending = false
                            markBatteryOptimizationStartupPromptHandled(this)
                            showBatteryOptimizationPrompt = false
                            openBatteryOptimizationSettings()
                        },
                        onDismiss = {
                            batteryOptimizationPromptPending = false
                            markBatteryOptimizationStartupPromptHandled(this)
                            showBatteryOptimizationPrompt = false
                        },
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_MICROPHONE_PERMISSION_REQUESTED, microphonePermissionRequested)
        outState.putBoolean(STATE_NOTIFICATION_PERMISSION_REQUESTED, notificationPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (hasRequiredPermissions()) {
            permissionsGranted = true
            showPermissionDenied = false
            if (!maybeRequestNotificationPermission()) {
                maybeShowBatteryOptimizationPrompt()
            }
            return
        }
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

    private fun maybeRequestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionRequested) return false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        notificationPermissionRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
    }

    private fun maybeShowBatteryOptimizationPrompt() {
        if (!batteryOptimizationPromptPending || !permissionsGranted) return
        if (isIgnoringBatteryOptimizations(this)) {
            batteryOptimizationPromptPending = false
            markBatteryOptimizationStartupPromptHandled(this)
        } else {
            showBatteryOptimizationPrompt = true
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            },
            Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").apply {
                data = "package:$packageName".toUri()
                putExtra("package_name", packageName)
                putExtra("packageName", packageName)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts(URI_SCHEME_PACKAGE, packageName, null)
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        )
        val launched = intents.any { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!launched) {
            AppFeedbackCenter.post(getString(R.string.no_app_available), FeedbackTone.ERROR)
        }
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

@Composable
private fun BatteryOptimizationPromptDialog(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.battery_optimization_prompt_title)) },
        text = { Text(stringResource(R.string.battery_optimization_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.battery_optimization_prompt_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.not_now))
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
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val librarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val openPanelDistancePx = with(density) { 52.dp.toPx() }

    fun refreshLibrarySnapshot() {
        scope.launch {
            try {
                val known = RecordingRepository.listKnown(context)
                librarySnapshot = known
                libraryCount = known.size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
            try {
                val refreshed = RecordingRepository.refresh(context)
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
