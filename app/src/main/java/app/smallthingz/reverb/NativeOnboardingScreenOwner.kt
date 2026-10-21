package app.smallthingz.reverb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.smallthingz.reverb.nativeui.OnboardingPageActions
import app.smallthingz.reverb.nativeui.OnboardingPageContentState
import app.smallthingz.reverb.nativeui.OnboardingPermission
import app.smallthingz.reverb.nativeui.OnboardingPermissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Page entry NEVER requests a permission in this release. Requests while onboarding are explicit
 * row actions. The existing microphone -> legacy storage -> notifications sequence begins only
 * after Finish succeeds or on an ordinary non-onboarding onStart/result.
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

    init {
        if (showingOnboarding) refreshOptionalPermissionStatus()
    }

    /** Same callbacks used by every ordinary page; predictive-preview pages use empty callbacks. */
    val actions = OnboardingPageActions(
        requestPermission = ::requestPermission,
        oneShotChanged = { selected -> edit { oneShotEnabled = selected } },
        loopingChanged = { selected -> edit { loopingEnabled = selected } },
        compressionChanged = { selected -> edit { outputCompression = selected } },
        skipSilenceChanged = { selected -> edit { skipSilence = selected } },
    )

    val snapshot: NativeOnboardingScreenSnapshot
        get() {
            val loaded = configuration
            val current = draft
            val finishing = finishState != OnboardingFinishState.IDLE
            val page = if (showingOnboarding && loaded != null && current != null) {
                OnboardingPageContentState(
                    permissions = OnboardingPermissionStatus(
                        microphoneAllowed = microphoneAllowed,
                        storageRequired = requiresLegacyPublicStoragePermission(),
                        storageAllowed = storageAllowed,
                        recoveryRequired = mediaRecoveryPermission() != null,
                        recoveryAllowed = recoveryAllowed,
                        notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        notificationsAllowed = notificationAllowed,
                        batteryOptimizationAllowed = batteryAllowed,
                        interactionEnabled = !finishing,
                    ),
                    oneShotEnabled = current.oneShotEnabled,
                    loopingEnabled = current.loopingEnabled,
                    outputCompression = current.outputCompression,
                    skipSilence = current.skipSilence,
                    compressionAvailable = loaded.compressionAvailable,
                    silenceValid = loaded.silenceValid,
                    controlsEnabled = !finishing,
                    interactionEnabled = !finishing,
                )
            } else null
            return NativeOnboardingScreenSnapshot(showingOnboarding, page, finishing,
                requiredPermissionsGranted, showPermissionDenied)
        }

    fun attach() {
        check(!closed && !attached)
        attached = true
        if (showingOnboarding) {
            hydrateConfiguration()
            observeFinishSuccess()
        }
        // Equivalent to collectAsState in the lifetime of the released Activity content, without
        // adding a STARTED gate that would defer the source's finishing colors while paused.
        scope.launch {
            processOnboardingFinish.state.collect { state ->
                finishState = state
                publish()
            }
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
        microphoneAllowed = granted || hasMicrophonePermission()
        requiredPermissionsGranted = hasRequiredPermissions()
        showPermissionDenied = !microphoneAllowed && !showingOnboarding
        if (microphoneAllowed && !showingOnboarding) beginPermissionFlow()
        publish()
    }

    fun onStoragePermissionResult(granted: Boolean) {
        if (closed) return
        storageAllowed = granted || hasLegacyStoragePermission()
        requiredPermissionsGranted = hasRequiredPermissions()
        showPermissionDenied = !storageAllowed && !showingOnboarding
        if (storageAllowed && !showingOnboarding) beginPermissionFlow()
        publish()
    }

    fun onRecoveryPermissionResult(granted: Boolean) {
        if (closed) return
        recoveryAllowed = granted || hasMediaRecoveryPermission()
        if (recoveryAllowed) AppFeedbackCenter.post(
            activity.getString(R.string.recording_recovery_enabled), FeedbackTone.SUCCESS)
        publish()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (closed) return
        // Deliberately use the actual result, as the released callback does, rather than reread.
        notificationAllowed = granted
        publish()
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
        if (closed || !showingOnboarding) return
        val current = draft ?: return
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
        // Unconsumed restoration survives hydration/loading, like SaveableStateRegistry restored data.
        if (showingOnboarding) {
            (draft?.save() ?: restoredDraft)?.let { outState.putBundle(STATE_DRAFT, Bundle(it)) }
        } else outState.remove(STATE_DRAFT)
    }

    private fun requestPermission(permission: OnboardingPermission) {
        if (closed || !showingOnboarding || configuration == null) return
        // The exact row enabled/allowed logic stays in OnboardingPageContentView. There is no
        // extra RESUMED gate, in-flight launcher lock or page-settled requirement here.
        when (permission) {
            OnboardingPermission.MICROPHONE -> {
                microphoneRequested = true
                launchers.microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
            OnboardingPermission.STORAGE -> if (requiresLegacyPublicStoragePermission()) {
                storageRequested = true
                launchers.storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            OnboardingPermission.RECOVERY -> mediaRecoveryPermission()?.let { name ->
                recoveryRequested = true
                launchers.recovery.launch(name)
            }
            OnboardingPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationsRequested = true
                launchers.notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            OnboardingPermission.BATTERY -> if (!openBatteryOptimizationReview(activity)) {
                AppFeedbackCenter.post(activity.getString(R.string.no_app_available), FeedbackTone.ERROR)
            }
        }
    }

    private inline fun edit(update: NativeOnboardingDraft.() -> Unit) {
        if (closed || !showingOnboarding) return
        val current = draft ?: return
        // Source callbacks assign their Boolean directly; the row owns last-buffer/codec admission.
        current.update()
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
        scope.cancel() // Only UI hydration/observation. Never cancels an accepted process commit.
    }

    private companion object {
        // Preserve the released Activity's four exact requested-flag keys.
        const val STATE_MICROPHONE_REQUESTED = "microphone_permission_requested"
        const val STATE_STORAGE_REQUESTED = "storage_permission_requested"
        const val STATE_RECOVERY_REQUESTED = "recovery_permission_requested"
        const val STATE_NOTIFICATIONS_REQUESTED = "notification_permission_requested"
        const val STATE_DRAFT = "native_onboarding_draft"
    }
}
