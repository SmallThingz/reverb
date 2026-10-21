// GENERATED from the release commit; edit only through generate_oracle.py.
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

internal data class ReleasedOnboardingStatus(
    val microphoneAllowed: Boolean,
    val storageAllowed: Boolean,
    val storagePermissionRequired: Boolean,
    val recoveryAllowed: Boolean,
    val recoveryPermissionRequired: Boolean,
    val notificationAllowed: Boolean,
    val notificationPermissionRequired: Boolean,
    val batteryOptimizationAllowed: Boolean,
)

@Composable
internal fun ReleasedOnboardingPage(
    page: Int,
    scrollState: ScrollState,
    status: ReleasedOnboardingStatus,
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
