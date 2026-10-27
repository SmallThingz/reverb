package app.smallthingz.reverb

import android.Manifest
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.smallthingz.reverb.nativeui.OnboardingPageActions
import app.smallthingz.reverb.nativeui.OnboardingPageContentState
import app.smallthingz.reverb.nativeui.OnboardingPermission
import app.smallthingz.reverb.nativeui.OnboardingPermissionStatus
import app.smallthingz.reverb.nativeui.SettingsSilenceActions
import app.smallthingz.reverb.nativeui.SettingsSilenceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Existing Activity registers these four RequestPermission contracts in the released order. */
internal data class NativeOnboardingPermissionLaunchers(
    val microphone: ActivityResultLauncher<String>,
    val storage: ActivityResultLauncher<String>,
    val recovery: ActivityResultLauncher<String>,
    val notifications: ActivityResultLauncher<String>,
)

/**
 * Direct screen publication, not another app-state framework. A null page while showing onboarding
 * is the released loading state. The native host owns the single pager/navigation/scroll engines.
 */
internal data class NativeOnboardingScreenSnapshot(
    val showingOnboarding: Boolean,
    val page: OnboardingPageContentState?,
    val finishing: Boolean,
    val requiredPermissionsGranted: Boolean,
    val showPermissionDenied: Boolean,
)

/**
 * Source-derived owner for released MainActivity:134–193,223–297,346–483 and OnboardingScreen:558–564.
 *
 * Construct after super.onCreate with the existing permission launchers, then call attach once at
 * the released onboarding setup point. Activity forwards onStart, the four result callbacks and
 * onSaveInstanceState; close at Activity disposal/onDestroy, NOT merely on onboarding completion:
 * the existing permission flow continues on Home using the same result/requested-flag owner.
 * No launcher registration order changes,
 * new permission contract, permission escalation, renderer, Composition or process-wide state.
 *
 * Permission prompts advance automatically on the first page. Only the atomic Finish transaction
 * publishes the buffer and audio choices.
 */
@MainThread
internal class NativeOnboardingScreenOwner(
    private val activity: ComponentActivity,
    savedInstanceState: Bundle?,
    private val launchers: NativeOnboardingPermissionLaunchers,
    publish: (NativeOnboardingScreenSnapshot) -> Unit,
) : AutoCloseable {
    private val appContext = activity.applicationContext
    private val ownerJob = SupervisorJob(activity.lifecycleScope.coroutineContext[Job])
    private val scope = CoroutineScope(activity.lifecycleScope.coroutineContext + ownerJob)
    private var publishTarget: ((NativeOnboardingScreenSnapshot) -> Unit)? = publish
    private var attached = false
    private var closed = false

    private var microphoneRequested = savedInstanceState?.getBoolean(STATE_MICROPHONE_REQUESTED) ?: false
    private var storageRequested = savedInstanceState?.getBoolean(STATE_STORAGE_REQUESTED) ?: false
    private var recoveryRequested = savedInstanceState?.getBoolean(STATE_RECOVERY_REQUESTED) ?: false
    private var notificationsRequested = savedInstanceState?.getBoolean(STATE_NOTIFICATIONS_REQUESTED) ?: false
    private val restoredDraft = savedInstanceState?.getBundle(STATE_DRAFT)?.let(::Bundle)
    private val permissionSequence = OnboardingPermissionRequestSequence(
        attempted = if (savedInstanceState?.containsKey(STATE_PERMISSION_ATTEMPTS) == true) {
            OnboardingPermissionRequestSequence.attemptedFromMask(savedInstanceState.getInt(STATE_PERMISSION_ATTEMPTS))
        } else buildSet {
            if (microphoneRequested) add(OnboardingPermission.MICROPHONE)
            if (storageRequested) add(OnboardingPermission.STORAGE)
            if (recoveryRequested) add(OnboardingPermission.RECOVERY)
            if (notificationsRequested) add(OnboardingPermission.NOTIFICATIONS)
        },
        pending = savedInstanceState?.getString(STATE_PERMISSION_PENDING)?.let { name ->
            OnboardingPermission.entries.firstOrNull { it.name == name }
        },
        batteryLeftForeground = savedInstanceState?.getBoolean(STATE_BATTERY_LEFT_FOREGROUND) ?: false,
    )
    private var visiblePage = -1
    private var inputRefreshJob: Job? = null
    private var inputRefreshPending = false
    private var permissionPromptJob: Job? = null

    private var microphoneAllowed = hasMicrophonePermission()
    private var storageAllowed = hasLegacyStoragePermission()
    private var requiredPermissionsGranted = microphoneAllowed && storageAllowed
    private var notificationAllowed = false
    private var recoveryAllowed = false
    private var batteryAllowed = false
    private var showPermissionDenied = false
    private var showingOnboarding = onboardingPendingForUi(
        finishState = processOnboardingFinish.state.value,
        readDurablePending = { isOnboardingPending(activity) },
    )
    private var configuration: OnboardingConfiguration? = null
    private var draft: NativeOnboardingDraft? = null
    private var finishState = processOnboardingFinish.state.value
    private var editRevision = 0L
    private var histogramRaw: String? = null
    private var histogram: SilenceCalibrationHistogram? = null
    private val calibrationBindingDelegate = lazy(LazyThreadSafetyMode.NONE) { NativeSettingsCalibrationBinding(
        activity, scope, activity.window.decorView, activity.lifecycle,
        readOwner = { config, input ->
            val current = draft
            if (!closed && showingOnboarding && visiblePage == 2 &&
                finishState == OnboardingFinishState.IDLE && current != null &&
                config.enabled && current.silenceConfig.copy(enabled = current.skipSilence) == config &&
                configuration?.input == input) editRevision to 0 else null
        },
        onCalibrated = { config, measured ->
            draft?.let { current ->
                current.silenceConfig = config
                current.silenceHistogram = encodeSilenceCalibrationHistogram(measured)
                configuration = configuration?.copy(silenceValid = true)
                editRevision++
                publish()
            }
        },
        onChanged = ::publish,
    ) }
    private val calibrationBinding by calibrationBindingDelegate
    private val calibration get() = calibrationBinding.controller
    private val silenceActions = object : SettingsSilenceActions {
        override fun configChanged(config: SilenceGateConfig) {
            if (visiblePage != 2) return
            edit {
                silenceConfig = config
                skipSilence = config.enabled
                configuration = configuration?.copy(silenceValid = true)
            }
        }
        override fun calibrate() = beginCalibration(held = false)
        override fun beginHeld(): Boolean {
            beginCalibration(held = true)
            return calibration.running && calibration.holding
        }
        override fun completeTap() = calibration.completeTap()
        override fun finishHeld() = calibration.finishHeld()
        override fun cancel() = calibration.cancel()
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE -> permissionSequence.leftForeground()
            Lifecycle.Event.ON_RESUME -> {
                permissionSequence.returnedToForeground()
                if (showingOnboarding) {
                    microphoneAllowed = hasMicrophonePermission()
                    storageAllowed = hasLegacyStoragePermission()
                    requiredPermissionsGranted = microphoneAllowed && storageAllowed
                    refreshOptionalPermissionStatus()
                    refreshAudioInput()
                    publish()
                    schedulePermissionPrompt()
                }
            }
            else -> Unit
        }
    }

    init {
        if (showingOnboarding) refreshOptionalPermissionStatus()
    }

    /** Same callbacks used by every ordinary page; predictive-preview pages use empty callbacks. */
    val actions = OnboardingPageActions(
        requestPermission = ::requestPermission,
        oneShotChanged = { selected -> edit { oneShotEnabled = selected } },
        loopingChanged = { selected -> edit { loopingEnabled = selected } },
        compressionChanged = { selected ->
            if (!selected || configuration?.compressionAvailable == true) edit { outputCompression = selected }
        },
        skipSilenceChanged = { selected -> edit { skipSilence = selected } },
        pageChanged = ::pageChanged,
        compressionLevelChanged = { level ->
            if (visiblePage == 2 && validFlacCompression(level)) edit { compressionLevel = level }
        },
        silenceActions = silenceActions,
    )

    val snapshot: NativeOnboardingScreenSnapshot
        get() {
            val loaded = configuration
            val current = draft
            val finishing = finishState != OnboardingFinishState.IDLE
            val page = if (showingOnboarding && loaded != null && current != null) {
                OnboardingPageContentState(
                    permissions = permissionStatus(!finishing),
                    oneShotEnabled = current.oneShotEnabled,
                    loopingEnabled = current.loopingEnabled,
                    outputCompression = current.outputCompression,
                    skipSilence = current.skipSilence,
                    compressionAvailable = loaded.compressionAvailable,
                    silenceValid = loaded.silenceValid,
                    controlsEnabled = !finishing,
                    interactionEnabled = !finishing,
                    compressionLevel = current.compressionLevel,
                    silenceState = silenceState(current, loaded, !finishing),
                )
            } else null
            return NativeOnboardingScreenSnapshot(showingOnboarding, page, finishing,
                requiredPermissionsGranted, showPermissionDenied)
        }

    fun attach() {
        check(!closed && !attached)
        attached = true
        activity.lifecycle.addObserver(lifecycleObserver)
        if (showingOnboarding) {
            hydrateConfiguration()
            observeFinishSuccess()
        }
        // Equivalent to collectAsState in the lifetime of the released Activity content, without
        // adding a STARTED gate that would defer the source's finishing colors while paused.
        scope.launch {
            processOnboardingFinish.state.collect { state ->
                val previouslyFinishing = finishState != OnboardingFinishState.IDLE
                finishState = state
                if (previouslyFinishing && state == OnboardingFinishState.IDLE) refreshAudioInput()
                publish()
            }
        }
        scope.launch {
            SilenceGatePreferences.revision.drop(1).collect { refreshAudioInput() }
        }
        publish()
    }

    /** Forward immediately after Activity.super.onStart, exactly as the released Activity does. */
    fun onStart() {
        if (closed) return
        microphoneAllowed = hasMicrophonePermission()
        storageAllowed = hasLegacyStoragePermission()
        requiredPermissionsGranted = microphoneAllowed && storageAllowed
        if (showingOnboarding) refreshOptionalPermissionStatus() else beginPermissionFlow()
        publish()
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        if (closed) return
        permissionSequence.completed(OnboardingPermission.MICROPHONE)
        microphoneAllowed = granted || hasMicrophonePermission()
        requiredPermissionsGranted = hasRequiredPermissions()
        showPermissionDenied = !microphoneAllowed && !showingOnboarding
        if (microphoneAllowed && !showingOnboarding) beginPermissionFlow()
        publish()
        schedulePermissionPrompt()
    }

    fun onStoragePermissionResult(granted: Boolean) {
        if (closed) return
        permissionSequence.completed(OnboardingPermission.STORAGE)
        storageAllowed = granted || hasLegacyStoragePermission()
        requiredPermissionsGranted = hasRequiredPermissions()
        showPermissionDenied = !storageAllowed && !showingOnboarding
        if (storageAllowed && !showingOnboarding) beginPermissionFlow()
        publish()
        schedulePermissionPrompt()
    }

    fun onRecoveryPermissionResult(granted: Boolean) {
        if (closed) return
        permissionSequence.completed(OnboardingPermission.RECOVERY)
        recoveryAllowed = granted || hasMediaRecoveryPermission()
        if (recoveryAllowed) AppFeedbackCenter.post(
            activity.getString(R.string.recording_recovery_enabled), FeedbackTone.SUCCESS)
        publish()
        schedulePermissionPrompt()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (closed) return
        permissionSequence.completed(OnboardingPermission.NOTIFICATIONS)
        // Deliberately use the actual result, as the released callback does, rather than reread.
        notificationAllowed = granted
        publish()
        schedulePermissionPrompt()
    }

    fun dismissPermissionDenied() {
        if (closed) return
        showPermissionDenied = false
        publish()
    }

    /**
     * Called only after the renderer's existing navigation.mayFinish test on settled page2.
     * Permission readiness changes highlighting only: do not impose another permission gate here.
     * Accepted durable work stays owned by processOnboardingFinish after this owner is disposed.
     */
    fun finish() {
        if (closed || !showingOnboarding || visiblePage != 2) return
        val current = draft ?: return
        if (calibration.running) calibration.cancel()
        val oneShot = current.oneShotEnabled
        val looping = current.loopingEnabled
        val originalAudio = current.originalAudio
        val selectedAudio = current.selectedAudio
        val application = appContext
        processOnboardingFinish.submit(
            block = {
                commitOnboardingAndReloadRuntime(
                    commit = { finishOnboarding(application, oneShot, looping, originalAudio, selectedAudio) },
                    reloadRuntime = { startCommittedSettingsRuntimeReload(application) },
                    onRuntimeFailure = {
                        AppFeedbackCenter.post(application.getString(R.string.settings_apply_failed), FeedbackTone.ERROR)
                    },
                )
            },
            onFailure = {
                AppFeedbackCenter.post(application.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
            },
        )
    }

    fun saveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_MICROPHONE_REQUESTED, microphoneRequested)
        outState.putBoolean(STATE_STORAGE_REQUESTED, storageRequested)
        outState.putBoolean(STATE_RECOVERY_REQUESTED, recoveryRequested)
        outState.putBoolean(STATE_NOTIFICATIONS_REQUESTED, notificationsRequested)
        outState.putInt(STATE_PERMISSION_ATTEMPTS, permissionSequence.attemptedMask)
        outState.putString(STATE_PERMISSION_PENDING, permissionSequence.pending?.name)
        outState.putBoolean(STATE_BATTERY_LEFT_FOREGROUND, permissionSequence.batteryLeftForeground)
        // Unconsumed restoration survives hydration/loading, like SaveableStateRegistry restored data.
        if (showingOnboarding) {
            (draft?.save() ?: restoredDraft)?.let { outState.putBundle(STATE_DRAFT, Bundle(it)) }
        } else outState.remove(STATE_DRAFT)
    }

    private fun permissionStatus(enabled: Boolean = finishState == OnboardingFinishState.IDLE) =
        OnboardingPermissionStatus(
            microphoneAllowed, requiresLegacyPublicStoragePermission(), storageAllowed,
            mediaRecoveryPermission() != null, recoveryAllowed,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU, notificationAllowed, batteryAllowed, enabled,
        )

    private fun requestPermission(permission: OnboardingPermission) {
        if (closed || !showingOnboarding || visiblePage != 0 || configuration == null) return
        if (permissionSequence.claimManual(permission, permissionStatus(),
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) launchPermission(permission)
    }

    private fun schedulePermissionPrompt() {
        if (closed || !attached || !showingOnboarding || visiblePage != 0 || configuration == null ||
            permissionPromptJob != null) return
        permissionPromptJob = scope.launch {
            // Leave renderer/result callbacks before launching another system Activity.
            yield()
            permissionPromptJob = null
            if (closed || !showingOnboarding || visiblePage != 0) return@launch
            permissionSequence.claimNext(permissionStatus(),
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))?.let(::launchPermission)
        }
    }

    private fun launchPermission(permission: OnboardingPermission) {
        try {
            when (permission) {
                OnboardingPermission.MICROPHONE -> {
                    microphoneRequested = true
                    launchers.microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
                OnboardingPermission.STORAGE -> {
                    storageRequested = true
                    launchers.storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                OnboardingPermission.RECOVERY -> {
                    recoveryRequested = true
                    val name = mediaRecoveryPermission()
                    if (name == null) permissionSequence.completed(permission) else launchers.recovery.launch(name)
                }
                OnboardingPermission.NOTIFICATIONS -> {
                    notificationsRequested = true
                    launchers.notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                OnboardingPermission.BATTERY -> if (!openBatteryOptimizationReview(activity)) {
                    permissionSequence.completed(permission)
                    AppFeedbackCenter.post(activity.getString(R.string.no_app_available), FeedbackTone.ERROR)
                }
            }
        } catch (failure: Exception) {
            permissionSequence.completed(permission)
            val expected = failure is ActivityNotFoundException || failure is SecurityException ||
                (failure is IllegalStateException && (activity.isFinishing || activity.isDestroyed ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))
            if (!expected) RecordingIncidentStore.recordUnexpectedErrorInBackground(
                appContext, "onboarding permission request", failure)
            AppFeedbackCenter.post(activity.getString(R.string.no_app_available), FeedbackTone.ERROR)
        }
        if (permissionSequence.pending == null) schedulePermissionPrompt()
    }

    private fun pageChanged(page: Int) {
        if (closed || visiblePage == page) return
        visiblePage = page
        if (page != 2 && calibration.running) calibration.cancel()
        schedulePermissionPrompt()
    }

    private fun beginCalibration(held: Boolean) {
        val current = draft ?: return
        val input = configuration?.input ?: return
        val config = current.silenceConfig.copy(enabled = current.skipSilence)
        if (held) calibration.beginHeld(config, input) else calibration.beginTimed(config, input)
    }

    private fun silenceState(current: NativeOnboardingDraft, loaded: OnboardingConfiguration,
        enabled: Boolean): SettingsSilenceState {
        if (histogramRaw != current.silenceHistogram) {
            histogramRaw = current.silenceHistogram
            histogram = decodeSilenceCalibrationHistogram(histogramRaw)
        }
        val input = loaded.input
        return SettingsSilenceState(
            config = current.silenceConfig.copy(enabled = current.skipSilence),
            enabled = enabled,
            maximumHz = minOf(SILENCE_MAX_HZ, input?.sampleRate?.div(2) ?: SILENCE_MAX_HZ),
            sampledPeaks = input?.let { histogram?.peaksFor(it) },
            calibrationAllowed = input != null,
            calibrating = calibration.running,
            holdingCalibration = calibration.holding,
            calibrationProgress = calibration.progress,
            calibrationError = calibration.error,
        )
    }

    /** Event-driven IO refresh preserves untouched choices without reading preferences on UI frames. */
    private fun refreshAudioInput() {
        if (closed || !showingOnboarding || configuration == null) return
        inputRefreshPending = true
        if (inputRefreshJob?.isActive == true) return
        inputRefreshJob = scope.launch {
            try {
                do {
                    inputRefreshPending = false
                    try {
                        val fresh = withContext(Dispatchers.IO) { getOnboardingConfiguration(appContext) }
                        val loaded = configuration
                        val current = draft
                        if (!closed && showingOnboarding && loaded != null && current != null) {
                            val before = current.selectedAudio
                            current.refreshUntouchedAudio(fresh.audio)
                            configuration = loaded.copy(input = fresh.input, audio = fresh.audio,
                                compressionAvailable = fresh.compressionAvailable, silenceValid = fresh.silenceValid)
                            if (loaded.input != fresh.input || before != current.selectedAudio) {
                                editRevision++
                                calibration.cancel(clearMeasurement = true)
                            }
                            publish()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (!closed && showingOnboarding) {
                            AppFeedbackCenter.post(activity.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
                        }
                    }
                } while (inputRefreshPending && !closed && showingOnboarding)
            } finally {
                inputRefreshJob = null
            }
        }
    }

    private inline fun edit(update: NativeOnboardingDraft.() -> Unit) {
        if (closed || !showingOnboarding || finishState != OnboardingFinishState.IDLE) return
        val current = draft ?: return
        if (calibration.running) calibration.cancel()
        current.update()
        editRevision++
        publish()
    }

    private fun hydrateConfiguration() {
        scope.launch {
            var retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
            var failureReported = false
            while (showingOnboarding && configuration == null) {
                val loaded = try {
                    withContext(Dispatchers.IO) { getOnboardingConfiguration(appContext) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (loaded != null) {
                    if (showingOnboarding) {
                        configuration = loaded
                        draft = NativeOnboardingDraft.restore(loaded, restoredDraft)
                        publish()
                        schedulePermissionPrompt()
                    }
                    return@launch
                }
                if (!failureReported) {
                    AppFeedbackCenter.post(activity.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
                    failureReported = true
                }
                delay(retryDelayMillis)
                retryDelayMillis = nextDurableUiRetryDelayMillis(retryDelayMillis)
            }
        }
    }

    private fun observeFinishSuccess() {
        scope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                processOnboardingFinish.state.collect { state ->
                    if (state == OnboardingFinishState.SUCCEEDED && showingOnboarding &&
                        !activity.isFinishing && !activity.isDestroyed &&
                        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        configuration = null
                        draft = null
                        showingOnboarding = false
                        histogramRaw = null
                        histogram = null
                        if (calibrationBindingDelegate.isInitialized()) calibrationBinding.close()
                        beginPermissionFlow()
                        publish()
                    }
                }
            }
        }
    }

    private fun beginPermissionFlow() {
        if (!hasMicrophonePermission()) {
            requiredPermissionsGranted = false
            if (microphoneRequested) {
                showPermissionDenied = true
                return
            }
            microphoneRequested = true
            launchers.microphone.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!hasLegacyStoragePermission()) {
            requiredPermissionsGranted = false
            if (storageRequested) {
                showPermissionDenied = true
                return
            }
            storageRequested = true
            launchers.storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        requiredPermissionsGranted = true
        showPermissionDenied = false
        maybeRequestNotificationPermission()
    }

    private fun maybeRequestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsRequested) return false
        if (hasNotificationPermission()) {
            notificationAllowed = true
            return false
        }
        notificationsRequested = true
        launchers.notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
    }

    private fun refreshOptionalPermissionStatus() {
        notificationAllowed = hasNotificationPermission()
        recoveryAllowed = hasMediaRecoveryPermission()
        batteryAllowed = isIgnoringBatteryOptimizations(activity)
    }

    private fun hasRequiredPermissions(): Boolean = hasMicrophonePermission() && hasLegacyStoragePermission()
    private fun hasMicrophonePermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasLegacyStoragePermission(): Boolean =
        !requiresLegacyPublicStoragePermission() ||
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun mediaRecoveryPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
        else -> null
    }
    private fun hasMediaRecoveryPermission(): Boolean =
        mediaRecoveryPermission()?.let { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true
    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun publish() {
        if (attached && !closed) publishTarget?.invoke(snapshot)
    }

    override fun close() {
        if (closed) return
        closed = true
        publishTarget = null
        if (calibrationBindingDelegate.isInitialized()) calibrationBinding.close()
        activity.lifecycle.removeObserver(lifecycleObserver)
        scope.cancel() // Only UI hydration/observation. Never cancels an accepted process commit.
    }

    private companion object {
        // Preserve the released Activity's four exact requested-flag keys.
        const val STATE_MICROPHONE_REQUESTED = "microphone_permission_requested"
        const val STATE_STORAGE_REQUESTED = "storage_permission_requested"
        const val STATE_RECOVERY_REQUESTED = "recovery_permission_requested"
        const val STATE_NOTIFICATIONS_REQUESTED = "notification_permission_requested"
        const val STATE_DRAFT = "native_onboarding_draft"
        const val STATE_PERMISSION_ATTEMPTS = "native_onboarding_permission_attempts"
        const val STATE_PERMISSION_PENDING = "native_onboarding_permission_pending"
        const val STATE_BATTERY_LEFT_FOREGROUND = "native_onboarding_battery_left_foreground"
    }
}
