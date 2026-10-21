// GENERATED from v0.1.2 MainActivity; declaration/reference renaming only.
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

private const val RELEASED_SCREEN_PAGE_COUNT = 3

@Composable
internal fun ReleasedOnboardingScreen(
    status: ReleasedOnboardingStatus,
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
    val pagerState = rememberPagerState(pageCount = { RELEASED_SCREEN_PAGE_COUNT })
    val pageScrollStates = mutableListOf<ScrollState>()
    repeat(RELEASED_SCREEN_PAGE_COUNT) { pageScrollStates += rememberScrollState() }
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
                    ReleasedOnboardingPage(
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
                    ReleasedOnboardingPage(
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
                repeat(RELEASED_SCREEN_PAGE_COUNT) { index ->
                    if (index > 0) Spacer(Modifier.size(8.dp))
                    ReleasedScreenProgressDot(active = page == index)
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
                        if (currentPage < RELEASED_SCREEN_PAGE_COUNT - 1) {
                            navigateTo(currentPage + 1)
                        } else if (navigation.mayFinish(
                                onLastPage = pagerState.settledPage == RELEASED_SCREEN_PAGE_COUNT - 1,
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
                        if (footerPage < RELEASED_SCREEN_PAGE_COUNT - 1) R.string.onboarding_continue
                        else R.string.onboarding_get_started,
                    ))
                }
            }
        }
    }
}

@Composable
private fun ReleasedScreenProgressDot(active: Boolean) {
    Surface(
        modifier = Modifier.size(width = if (active) 18.dp else 6.dp, height = 6.dp),
        shape = RoundedCornerShape(99.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ) {}
}

