package app.smallthingz.reverb

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.AccessibilityManager
import androidx.core.view.WindowInsetsCompat
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope
import kotlin.math.min
import kotlin.math.roundToInt

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
) : ViewGroup(activity), AutoCloseable {
    private var environment = initialEnvironment
    private var snapshot = owner.snapshot
    private var main: NativeMainScreenView? = null
    private var onboarding: NativeOnboardingScreenView? = null
    private var loading: ReverbIncidentLoadingView? = null
    private var feedback: NativeAppFeedbackHostView? = null
    private var denied: ReverbPermissionDeniedDialog? = null
    private var remainingMainState = restoredMain
    private var remainingOnboardingState = restoredOnboarding
    private var closed = false
    private var rendering = false
    private var renderAgain = false
    private var windowInsets = WindowInsetsCompat.Builder().build()
    private val noise = ReverbNoiseDrawable(resources, initialEnvironment.palette.surface)
    private val hostClip = NativeTextSemanticsClipPolicy { view ->
        when {
            view.parent is ReverbGroupLayout -> NativeTextSemanticsClip.Rectangle(
                ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            else -> onboarding?.sourceWindowTextClip(view) ?: NativeTextSemanticsClip.Unclipped
        }
    }
    private val textGeometry = NativePlainTextViewGeometry(this, hostClip)
    private val windowText = NativeWindowTextAccessibilityOwner(this, hostClip)
    private val insets = NativeWindowInsetsBinding(this) { value ->
        windowInsets = value
        deliverInsets()
    }

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Released AndroidComposeView owns initial native window focus, not the first child editor.
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        background = noise
    }

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
        noise.setBackgroundColor(environment.palette.surface)
        if (snapshot.showingOnboarding) {
            retireMain()
            retireDenied()
            val page = snapshot.page
            if (page == null) {
                retireOnboarding()
                if (loading == null) loading = ReverbIncidentLoadingView(context, environment.palette.primary,
                    requireNotNull(scope.coroutineContext[MonotonicFrameClock]), scope,
                    { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f }, strokeWidthDp = 4f
                ).also { addView(it, 0) }
                loading?.setColor(environment.palette.primary)
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
                    addView(it, 0)
                }
                current.externalAccessibilityOcclusion = ::feedbackOcclusion
                current.setPalette(environment.palette)
                current.bind(page)
            }
            if (feedback == null) {
                feedback = NativeAppFeedbackHostView(context, environment, this, activity, scope,
                    accessibilityManager, windowText, ::feedbackSourceNode).also(::addView)
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
                addView(it)
                it.start()
            }
            current.bind(NativeMainScreenInput(snapshot.requiredPermissionsGranted, snapshot.showPermissionDenied), environment)
            if (snapshot.showPermissionDenied) {
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
            } else retireDenied()
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
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        main?.measure(activityExact(width), activityExact(height))
        onboarding?.measure(activityExact(width), activityExact(height))
        loading?.measure(activityExact(min(width, dp(40))), activityExact(min(height, dp(40))))
        feedback?.let {
            val safe = safeDrawing()
            val maxWidth = (width - safe.left - safe.right - dp(18) - dp(18)).coerceAtLeast(0)
            val maxHeight = (height - safe.top - safe.bottom - dp(84)).coerceAtLeast(0)
            it.measure(activityAtMost(maxWidth), activityAtMost(maxHeight))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        main?.layout(0, 0, width, height)
        onboarding?.layout(0, 0, width, height)
        loading?.let {
            val x = ((width - it.measuredWidth) * .5f).roundToInt()
            val y = ((height - it.measuredHeight) * .5f).roundToInt()
            it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
        }
        feedback?.let {
            // Modifier order: BottomCenter -> safeDrawingPadding -> horizontal18 -> bottom84.
            val safe = safeDrawing()
            val insideWidth = (width - safe.left - safe.right - dp(18) - dp(18)).coerceAtLeast(0)
            val x = safe.left + dp(18) + ((insideWidth - it.measuredWidth) * .5f).roundToInt()
            val y = height - safe.bottom - dp(84) - it.measuredHeight
            it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
        }
    }

    /** Passive feedback has one important Text node; its Surface only marks a traversal container. */
    private fun feedbackOcclusion(): List<Rect> {
        val current = feedback ?: return emptyList()
        val bounds = ArrayList<Rect>()
        fun visit(view: View) {
            if (view.visibility != VISIBLE) return
            if (view is ReverbParagraphView) {
                val facts = feedbackSourceNode(view)
                if (facts.present && !facts.hidden) {
                    val mapped = textGeometry.capture(view, Offset.Zero, view.originalAccessibilityBoundsInView())
                    val rectangle = mapped?.visibleBoundsInRoot?.intersect(
                        ComposeRect(0f, 0f, width.toFloat(), height.toFloat()))
                    if (rectangle != null && !rectangle.isEmpty) bounds += Rect(
                        rectangle.left.roundToInt(), rectangle.top.roundToInt(),
                        rectangle.right.roundToInt(), rectangle.bottom.roundToInt())
                }
            } else if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(current)
        return bounds // Onboarding is placed at0,0 with identical root size, without a root transform.
    }

    private fun feedbackSourceNode(view: View): NativeSourceAccessibilityNode {
        if (!view.isAttachedToWindow || !view.isLaidOut || view.visibility != VISIBLE)
            return NativeSourceAccessibilityNode.absent()
        val local = if (view is ReverbParagraphView) view.originalAccessibilityBoundsInView()
            else ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val mapped = textGeometry.capture(view, Offset.Zero, local) ?: return NativeSourceAccessibilityNode.absent()
        val visible = mapped.visibleBoundsInRoot.intersect(ComposeRect(0f, 0f, width.toFloat(), height.toFloat()))
        if (visible.isEmpty) return NativeSourceAccessibilityNode.absent()
        var hidden = false
        var current: View? = view
        while (current != null) {
            if (current.alpha <= 0f) hidden = true
            if (current === this) break
            current = current.parent as? View
        }
        val geometry = PlainTextAccessibilityGeometry(mapped.paragraphPositionInRoot, visible, mapped.rootToScreen)
        return NativeSourceAccessibilityNode(true, hidden, !hidden,
            nativeMergedRoleBoundsInScreen(geometry, true))
    }

    fun saveMain(): NativeMainScreenSavedState? = main?.saveState() ?: remainingMainState
    fun saveOnboarding(): NativeOnboardingViewportSavedState? =
        onboarding?.saveViewportState() ?: remainingOnboardingState

    private fun retireMain() { main?.let { main = null; removeView(it); it.close() } }
    private fun retireOnboarding() { onboarding?.let { onboarding = null; removeView(it); it.close() } }
    private fun retireLoading() { loading?.let { loading = null; removeView(it) } }
    private fun retireFeedback() { feedback?.let { feedback = null; removeView(it); it.close() } }
    private fun retireDenied() { denied?.let { denied = null; it.close() } }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        release(::retireDenied)
        release(::retireMain)
        release(::retireFeedback)
        release(::retireLoading)
        release(::retireOnboarding)
        release(insets::close)
        failure?.let { throw it }
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

private fun activityExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
private fun activityAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.AT_MOST)
