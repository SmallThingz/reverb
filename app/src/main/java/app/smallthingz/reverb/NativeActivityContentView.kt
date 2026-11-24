package app.smallthingz.reverb

import smallthingz.ui.components.NativeWindowInsetsBinding
import smallthingz.ui.components.NativeStatusBarProtection

import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.NativeTextSemanticsTransformPolicy
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import smallthingz.ui.reverb.onboarding.ReverbOnboardingScreenLayout
import smallthingz.ui.geometry.Rect as ComposeRect
import smallthingz.ui.platform.AccessibilityManager
import androidx.core.view.WindowInsetsCompat
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope

/**
 * One actual Activity root and background. Branch changes dispose the outgoing native content
 * before mounting the incoming content. The window runtime/cursor survive those changes.
 * The app's existing owners retain all process, permission, Settings and recorder decisions.
 */
internal class NativeActivityContentView(
    private val activity: ComponentActivity,
    initialEnvironment: ReverbViewEnvironment,
    private val owner: NativeOnboardingScreenOwner,
    private val scope: CoroutineScope,
    private val accessibilityManager: AccessibilityManager,
    restoredMain: NativeMainScreenSavedState?,
    restoredOnboarding: NativeOnboardingViewportSavedState?,
    private val onThemeChanged: (AppThemeMode?) -> Unit,
    private val registerDirectoryResult: ((Uri?) -> Unit) -> NativeDirectoryPickerRegistration,
    private val openApplicationSettings: () -> Unit,
    private val statusBarProtection: NativeStatusBarProtection = createReverbStatusBarProtection(activity),
) : ViewGroup(activity), AutoCloseable {
    private var environment = initialEnvironment
    private var snapshot = owner.snapshot
    private var main: NativeMainScreenView? = null
    private var playbackRequest: RecordingPlaybackRequest? = null
    private var playbackRequestFinished: (() -> Unit)? = null
    private var onboarding: NativeOnboardingScreenView? = null
    private var loading: ReverbIncidentLoadingView? = null
    private var feedback: NativeAppFeedbackHostView? = null
    private var denied: ReverbPermissionDeniedDialog? = null
    private var restartInvitation: CaptureTextSheet? = null
    private var remainingMainState = restoredMain
    private var remainingOnboardingState = restoredOnboarding
    private var closed = false
    private var rendering = false
    private var sourceFocus: NativeSourceFocusPolicy? = null
    private var renderAgain = false
    private var windowInsets = WindowInsetsCompat.Builder().build()
    private val backdrop = initialEnvironment.appearance.backdrops.attachPage(this, initialEnvironment.palette) {
        main?.invalidateSettingsTextSurface()
    }
    private val hostClip = NativeTextSemanticsClipPolicy { view ->
        when {
            view.parent is NativeGroupLayout -> NativeTextSemanticsClip.Rectangle(
                ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            else -> onboarding?.sourceWindowTextClip(view) ?: NativeTextSemanticsClip.Unclipped
        }
    }
    private val hostTextTransforms = NativeTextSemanticsTransformPolicy { view, target ->
        onboarding?.sourceTextTransforms?.populate(view, target) == true
    }
    private val textGeometry = NativePlainTextViewGeometry(this, hostClip,
        transformPolicy = hostTextTransforms)
    private val windowText = NativeWindowTextAccessibilityOwner(this, hostClip,
        transformPolicy = hostTextTransforms)
    private val insets = NativeWindowInsetsBinding(this) { value ->
        windowInsets = value
        statusBarProtection.updateInsets(value)
        deliverInsets()
    }

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Resolve window focus through the released owner policy, preserving explicit child focus.
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        foreground = statusBarProtection
        sourceFocus = NativeSourceFocusPolicy(this,
            takeOwnerFocus = { super.requestFocus(FOCUS_DOWN, null) },
            clearOwnerFocus = { super.clearFocus() })
    }

    override fun focusableViewAvailable(view: View) {
        if (sourceFocus?.reportsAvailability(view) != false) super.focusableViewAvailable(view)
    }
    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean =
        sourceFocus?.requestFocus(direction, previouslyFocusedRect)
            ?: super.requestFocus(direction, previouslyFocusedRect)
    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        sourceFocus?.childFocused(focused)
    }
    override fun clearChildFocus(child: View) {
        super.clearChildFocus(child)
        sourceFocus?.childCleared(child)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (sourceFocus?.hasPendingInvalidation == true) false else super.dispatchKeyEvent(event)

    fun bind(value: NativeOnboardingScreenSnapshot, nextEnvironment: ReverbViewEnvironment) {
        if (closed) return
        snapshot = value
        environment = nextEnvironment
        if (rendering) { renderAgain = true; return }
        rendering = true
        try {
            do {
                renderAgain = false
                renderBranch()
            } while (renderAgain && !closed)
        } finally { rendering = false }
    }

    private fun renderBranch() {
        environment.appearance.backdrops.bindPage(backdrop, environment.palette)
        if (snapshot.showingOnboarding) {
            retireMain()
            retireDenied()
            retireRestartInvitation()
            val page = snapshot.page
            if (page == null) {
                retireOnboarding()
                if (loading == null) loading = ReverbOnboardingScreenLayout.createLoading(context, environment, scope)
                    .also { addView(it, 0) }
                loading?.let { ReverbOnboardingScreenLayout.bindLoading(it, environment) }
            } else {
                retireLoading()
                val current = onboarding ?: NativeOnboardingScreenView.create(
                    context, environment, page, activity, owner.actions, owner::finish, scope,
                    activity.onBackPressedDispatcher,
                    remainingOnboardingState ?: NativeOnboardingViewportSavedState(),
                    externalWindowText = windowText, observeWindowInsets = false,
                ).also {
                    onboarding = it
                    remainingOnboardingState = null
                    it.externalAccessibilityOcclusion = ::feedbackOcclusion
                    addView(it, 0)
                }
                current.setPalette(environment.palette)
                current.bind(page)
            }
            if (feedback == null) {
                feedback = NativeAppFeedbackHostView(context, environment, this, activity, scope,
                    accessibilityManager, windowText, { view ->
                        ReverbOnboardingScreenLayout.feedbackSourceNode(this, textGeometry, view)
                    }).also(::addView)
            }
            feedback?.bind(true, environment)
        } else {
            // The old onboarding queue/cursor leases retire before Main opens its scroll queue.
            retireFeedback()
            retireLoading()
            retireOnboarding()
            remainingOnboardingState = null
            val current = main ?: NativeMainScreenView.create(
                context = context,
                environment = environment,
                input = NativeMainScreenInput(snapshot.requiredPermissionsGranted, snapshot.showPermissionDenied),
                lifecycleOwner = activity,
                dispatcher = activity.onBackPressedDispatcher,
                parentScope = scope,
                accessibilityManager = accessibilityManager,
                windowRoot = this,
                windowText = windowText,
                restored = remainingMainState,
                onReviewPermission = openApplicationSettings,
                onThemeChanged = onThemeChanged,
                registerDirectoryResult = registerDirectoryResult,
                releaseInputFocus = { findFocus()?.clearFocus() },
            ).also {
                main = it // Install callback/result destination before source effects may run.
                remainingMainState = null
                it.setWindowInsets(windowInsets)
                addView(it)
                it.start()
            }
            current.bind(NativeMainScreenInput(snapshot.requiredPermissionsGranted, snapshot.showPermissionDenied), environment)
            deliverPlaybackRequest()
            if (snapshot.showPermissionDenied) {
                retireRestartInvitation()
                val microphoneAllowed = activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val storageAllowed = !requiresLegacyPublicStoragePermission() ||
                    activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val modal = denied ?: ReverbPermissionDeniedDialog(this, environment,
                    microphoneAllowed, storageAllowed, owner::dismissPermissionDenied, openApplicationSettings
                ).also { denied = it; it.show() }
                modal.bindEnvironment(environment)
                modal.bind(microphoneAllowed, storageAllowed, owner::dismissPermissionDenied, openApplicationSettings)
            } else {
                retireDenied()
                if (snapshot.showRestartAccessInvitation) {
                    val title = context.getString(R.string.onboarding_restart_resume_title)
                    val message = context.getString(R.string.onboarding_restart_resume_body)
                    val dismiss = { owner.respondToRestartInvitation(false) }
                    val modal = restartInvitation ?: CaptureTextSheet(this, environment, title, message, dismiss) { host ->
                        listOf(
                            LibrarySheetButton(context, host, environment, false, context.getString(R.string.restart_access_not_now), dismiss),
                            LibrarySheetButton(context, host, environment, true, context.getString(R.string.background_review), onClick = {
                                owner.respondToRestartInvitation(true)
                            }),
                        )
                    }.also { restartInvitation = it; it.show() }
                    modal.bind(title, message, environment, dismiss)
                } else retireRestartInvitation()
            }
        }
        deliverInsets()
        requestLayout()
    }

    private fun safeDrawing() = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or
        WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())

    private fun deliverInsets() {
        val safe = safeDrawing()
        onboarding?.setSafeDrawingInsets(Rect(safe.left, safe.top, safe.right, safe.bottom))
        main?.setWindowInsets(windowInsets)
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        sourceFocus?.beforeMeasure()
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        main?.measure(activityExact(width), activityExact(height))
        onboarding?.measure(activityExact(width), activityExact(height))
        loading?.let { ReverbOnboardingScreenLayout.measureLoading(it, width, height, environment.textDensity.density) }
        feedback?.let {
            ReverbOnboardingScreenLayout.measureFeedback(it, width, height, environment.textDensity.density, safeDrawing())
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        main?.layout(0, 0, width, height)
        onboarding?.layout(0, 0, width, height)
        loading?.let { ReverbOnboardingScreenLayout.layoutLoading(it, width, height) }
        feedback?.let {
            ReverbOnboardingScreenLayout.layoutFeedback(it, width, height, environment.textDensity.density, safeDrawing())
        }
    }

    private fun feedbackOcclusion(): List<Rect> = ReverbOnboardingScreenLayout.feedbackOcclusion(this, textGeometry, feedback)

    fun saveMain(): NativeMainScreenSavedState? = main?.saveState() ?: remainingMainState
    fun openSavedRecording(request: RecordingPlaybackRequest, finished: () -> Unit) {
        if (closed) return
        playbackRequest = request
        playbackRequestFinished = finished
        deliverPlaybackRequest()
    }

    private fun deliverPlaybackRequest() {
        val request = playbackRequest ?: return
        main?.openSavedRecording(request) {
            if (playbackRequest?.token == request.token) {
                playbackRequest = null
                val finished = playbackRequestFinished
                playbackRequestFinished = null
                finished?.invoke()
            }
        }
    }
    fun saveOnboarding(): NativeOnboardingViewportSavedState? =
        onboarding?.saveViewportState() ?: remainingOnboardingState

    private fun retireMain() { main?.let { main = null; removeView(it); it.close() } }
    private fun retireOnboarding() { onboarding?.let { onboarding = null; removeView(it); it.close() } }
    private fun retireLoading() { loading?.let { loading = null; removeView(it) } }
    private fun retireFeedback() { feedback?.let { feedback = null; removeView(it); it.close() } }
    private fun retireDenied() { denied?.let { denied = null; it.close() } }
    private fun retireRestartInvitation() { restartInvitation?.let { restartInvitation = null; it.close() } }

    override fun close() {
        if (closed) return
        closed = true
        playbackRequest = null
        playbackRequestFinished = null
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        release { sourceFocus?.close() }
        release(::retireDenied)
        release(::retireRestartInvitation)
        release(::retireMain)
        release(::retireFeedback)
        release(::retireLoading)
        release(::retireOnboarding)
        release(insets::close)
        release { foreground = null }
        failure?.let { throw it }
    }
}

private fun activityExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
