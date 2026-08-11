package app.smallthingz.timetravel

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private const val TAB_CAPTURE = 0
private const val TAB_FILES = 1
private const val URI_SCHEME_PACKAGE = "package"
private const val STATE_MICROPHONE_PERMISSION_REQUESTED = "microphone_permission_requested"
private const val STATE_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)
    private var showPermissionDenied by mutableStateOf(false)
    private var themeMode by mutableStateOf(AppThemeMode.SYSTEM)

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionsGranted = true
                showPermissionDenied = false
                scheduleRecorderCapabilityCacheWarm(applicationContext)
                maybeRequestNotificationPermission()
            } else {
                showPermissionDenied = true
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> Unit }

    private var microphonePermissionRequested = false
    private var notificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPhonePortraitOnly()
        super.onCreate(savedInstanceState)
        microphonePermissionRequested =
            savedInstanceState?.getBoolean(STATE_MICROPHONE_PERMISSION_REQUESTED) ?: false
        notificationPermissionRequested =
            savedInstanceState?.getBoolean(STATE_NOTIFICATION_PERMISSION_REQUESTED) ?: false
        themeMode = getConfiguredThemeMode(this)
        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            TimeTravelTheme(darkTheme = themeMode.isDark(systemDarkTheme)) {
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
            scheduleRecorderCapabilityCacheWarm(applicationContext)
            maybeRequestNotificationPermission()
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

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionRequested) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
private fun MainScreen(
    permissionsGranted: Boolean,
    showPermissionDenied: Boolean,
    onThemeChanged: (AppThemeMode) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_CAPTURE) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var filesSelectionActive by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    if (showSettings) {
        BackHandler {
            showSettings = false
        }
        SettingsScreen(
            onBack = { showSettings = false },
            onThemeChanged = onThemeChanged,
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                AppTopBar(
                    selectionActive = selectedTab == TAB_FILES && filesSelectionActive,
                    onBrandClick = { showAboutDialog = true },
                    onSettingsClick = { showSettings = true },
                )
            },
            bottomBar = {
                if (permissionsGranted) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == TAB_CAPTURE,
                            onClick = { selectedTab = TAB_CAPTURE },
                            icon = {
                                Icon(
                                    painterResource(R.drawable.ic_tab_home),
                                    contentDescription = stringResource(R.string.capture_tab),
                                )
                            },
                        )
                        NavigationBarItem(
                            selected = selectedTab == TAB_FILES,
                            onClick = { selectedTab = TAB_FILES },
                            icon = {
                                Icon(
                                    painterResource(R.drawable.ic_tab_files),
                                    contentDescription = stringResource(R.string.files_tab),
                                )
                            },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                if (permissionsGranted) {
                    when (selectedTab) {
                        TAB_CAPTURE -> CaptureScreen()
                        TAB_FILES -> FilesScreen(onSelectionActiveChange = { filesSelectionActive = it })
                    }
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

        if (showAboutDialog) {
            AboutDialog(onDismiss = { showAboutDialog = false })
        }
    }
}
