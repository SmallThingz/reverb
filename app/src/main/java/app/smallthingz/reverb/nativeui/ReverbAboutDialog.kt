/*
 * Native adaptation of AndroidX Compose UI/Animation behavior.
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0; see about/window/APACHE-2.0.txt and SOURCE.json.
 */

package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.graphics.Outline
import android.os.Build
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Native About Window proposal. Uses the released Compose Dialog's exact theme/window policy,
 * not AlertDialog's default sizing, title, buttons, background, animations or cancel behavior.
 *
 * The owner retains this object for one About entry and calls dismiss() when its route is removed.
 * Recreate on anchor/density changes, matching released Dialog's remember keys. No production caller
 * is integrated. Actual window/frame/input parity remains a fixture obligation.
 */
internal class ReverbAboutDialog(
    private val anchor: View,
    appearance: AboutContentAppearance,
    restoredScrollPosition: Int,
    onDismiss: () -> Unit,
) : ComponentDialog(ContextThemeWrapper(anchor.context, androidx.compose.ui.R.style.FloatingDialogWindowTheme)) {
    constructor(anchor: View, appearance: AboutContentAppearance, onDismiss: () -> Unit) :
        this(anchor, appearance, 0, onDismiss)

    private var currentOnDismiss = onDismiss
    private val visibilityMotion = ReverbAboutVisibilityMotion()
    private val predictiveMotion = ReverbAboutPredictiveMotion()
    private val runtime = NativeAboutWindowRuntime()
    private var predictiveSession = false
    private val choreographer = Choreographer.getInstance()
    private var callbackPosted = false
    private var disposed = false
    private var exitDelivered = false
    private var pressOutside = false
    private val hostLifecycle = anchor.findViewTreeLifecycleOwner()?.lifecycle
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> { runtime.setStarted(true); requestFrame() }
            Lifecycle.Event.ON_STOP -> {
                runtime.setStarted(false)
                choreographer.removeFrameCallback(frameCallback)
                callbackPosted = false
            }
            Lifecycle.Event.ON_DESTROY -> dismiss()
            else -> Unit
        }
    }
    private val content = createReverbAboutContent(context, appearance, ::requestDismiss, runtime, restoredScrollPosition).apply {
        alpha = 0f
    }
    private val root = AboutWindowRoot(context, requireNotNull(window), content) {
        visibilityMotion.setHeight(content.measuredHeight)
        applyMotion()
        requestFrame()
    }
    private val frameCallback = Choreographer.FrameCallback { frameNanos ->
        callbackPosted = false
        if (!disposed && root.isAttachedToWindow && hostStarted()) {
            val scale = ValueAnimator.getDurationScale()
            visibilityMotion.frame(frameNanos, scale)
            val predictiveCommitted = predictiveMotion.frame(frameNanos, scale)
            applyMotion()
            if (predictiveCommitted) {
                deliverPredictiveCommit()
            } else if (visibilityMotion.exiting && visibilityMotion.finished && !exitDelivered) {
                exitDelivered = true
                content.visibility = View.GONE
                currentOnDismiss()
            }
            requestFrame()
        }
    }
    private val predictiveCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (!isEnabled || disposed) return
            predictiveSession = true
            predictiveMotion.cancelled()
            predictiveMotion.started()
            applyMotion()
        }
        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (!isEnabled || disposed || !predictiveSession) return
            predictiveMotion.progressed(backEvent.progress)
            applyMotion()
        }
        override fun handleOnBackCancelled() {
            if (!isEnabled || disposed) return
            predictiveSession = false
            predictiveMotion.cancelled()
            applyMotion()
        }
        override fun handleOnBackPressed() {
            if (!isEnabled || disposed) return
            val complete = if (predictiveSession) predictiveMotion.committed() else predictiveMotion.ordinaryBack()
            predictiveSession = false
            if (complete) {
                applyMotion()
                deliverPredictiveCommit()
            } else requestFrame()
        }
    }

    init {
        val window = requireNotNull(window)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setGravity(Gravity.TOP)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.attributes = window.attributes.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsSides(0)
                setFitInsetsTypes(0)
            }
        }
        window.setSoftInputMode(if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setTitle("")
        inheritSecurePolicy()
        root.layoutDirection = anchor.layoutDirection
        content.bindWindowAccessibility(root)
        root.elevation = 8f * appearance.density.density
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, result: Outline) {
                result.setRect(0, 0, view.width, view.height)
                result.alpha = 0f // Space for source shadows without drawing a native light shadow.
            }
        }
        if (Build.VERSION.SDK_INT >= 29) root.isForceDarkAllowed = false
        (window.decorView as? ViewGroup)?.disableClippingUntil(root)
        setContentView(root)
        root.setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
        root.setViewTreeViewModelStoreOwner(anchor.findViewTreeViewModelStoreOwner())
        root.setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
        // DialogWrapper installs decor/content before DialogLayout.updateProperties sets width.
        // PhoneWindow.generateLayout resets a floating window to WRAP_CONTENT during decor creation.
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setCanceledOnTouchOutside(true)
        // The fallback remains enabled while an explicit exit disables predictive handling.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestDismiss()
        })
        onBackPressedDispatcher.addCallback(this, predictiveCallback)
        hostLifecycle?.addObserver(lifecycleObserver)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                runtime.setStarted(hostStarted())
                ViewCompat.requestApplyInsets(root)
                requestFrame()
            }
            override fun onViewDetachedFromWindow(view: View) {
                runtime.setStarted(false)
                choreographer.removeFrameCallback(frameCallback)
                callbackPosted = false
            }
        })
    }

    /** ScrollState.Saver contains only the current integer, including before the first measure. */
    fun scrollPosition(): Int = content.scrollPosition()

    fun updateAppearance(appearance: AboutContentAppearance, onDismiss: () -> Unit) {
        currentOnDismiss = onDismiss
        root.layoutDirection = anchor.layoutDirection
        inheritSecurePolicy()
        content.setAppearance(appearance, ::requestDismiss)
    }

    /** Close button, outside click and Escape own the source AnimatedVisibility exit. */
    fun requestDismiss() {
        if (disposed || visibilityMotion.exiting) return
        predictiveCallback.isEnabled = false
        predictiveSession = false
        predictiveMotion.disable()
        visibilityMotion.requestExit()
        applyMotion()
        requestFrame()
    }

    private fun deliverPredictiveCommit() {
        try {
            // Successful predictive completion invokes owner directly, without a second exit.
            currentOnDismiss()
        } finally {
            predictiveMotion.finishCommitCallback()
            if (!disposed) applyMotion()
        }
    }

    private fun inheritSecurePolicy() {
        val flags = (anchor.rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
        requireNotNull(window).setFlags(flags and WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun applyMotion() {
        content.translationX = visibilityMotion.translation.x.toFloat()
        content.translationY = visibilityMotion.translation.y.toFloat()
        content.alpha = visibilityMotion.alpha
        val progress = if (predictiveMotion.active) predictiveMotion.progress.coerceIn(0f, 1f) else 0f
        content.predictiveMotionTarget.translationY = -content.predictiveMotionTarget.height * progress
        content.predictiveMotionTarget.alpha = 1f - .22f * progress
    }

    private fun requestFrame() {
        if (disposed || callbackPosted || !root.isAttachedToWindow || !hostStarted()) return
        if (!visibilityMotion.needsFrame(predictiveMotion.hasContinuation, exitDelivered)) return
        callbackPosted = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun hostStarted(): Boolean = hostLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isTracking && !event.isCanceled && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            requestDismiss()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var result = super.onTouchEvent(event)
        if (!root.isInsideContent(event)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressOutside = true; result = true }
                MotionEvent.ACTION_UP -> if (pressOutside) {
                    requestDismiss(); result = true; pressOutside = false
                }
                MotionEvent.ACTION_CANCEL -> pressOutside = false
            }
        } else if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressOutside = false
        }
        return result
    }

    /** Released DialogWrapper prevents platform cancellation from removing its own Window. */
    override fun cancel() = Unit

    /** Owner disposal is immediate and must not invoke its own onDismiss callback again. */
    override fun dismiss() {
        disposed = true
        choreographer.removeFrameCallback(frameCallback)
        callbackPosted = false
        hostLifecycle?.removeObserver(lifecycleObserver)
        predictiveCallback.remove()
        content.dispose()
        runtime.close()
        super.dismiss()
    }
}

private fun ViewGroup.disableClippingUntil(stop: ViewGroup) {
    clipChildren = false
    if (this === stop) return
    for (index in 0 until childCount) {
        (getChildAt(index) as? ViewGroup)?.disableClippingUntil(stop)
    }
}

/** Source AndroidDialog.DialogLayout measure/inset/outside-hit policy for the non-fitting variant. */
private class AboutWindowRoot(
    context: android.content.Context,
    private val dialogWindow: Window,
    private val content: View,
    private val onContentMeasured: () -> Unit,
) : ViewGroup(context) {
    init {
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Released AndroidComposeView owns initial native window focus before child controls.
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            insetForChild(insets) { l, t, r, b -> insets.inset(l, t, r, b) }
        }
        ViewCompat.setWindowInsetsAnimationCallback(this,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat) =
                    insetForChild(bounds) { l, t, r, b -> bounds.inset(Insets.of(l, t, r, b)) }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>) =
                    insetForChild(insets) { l, t, r, b -> insets.inset(l, t, r, b) }
            })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val targetHeight = if (heightMode == MeasureSpec.AT_MOST &&
            dialogWindow.attributes.height == LayoutParams.WRAP_CONTENT) height + 1 else height
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val childWidth = if (widthMode == MeasureSpec.UNSPECIFIED) widthMeasureSpec
            else MeasureSpec.makeMeasureSpec(max(0, width - horizontalPadding), MeasureSpec.AT_MOST)
        val childHeight = if (heightMode == MeasureSpec.UNSPECIFIED) heightMeasureSpec
            else MeasureSpec.makeMeasureSpec(max(0, targetHeight - verticalPadding), MeasureSpec.AT_MOST)
        if (content.visibility == GONE) {
            setMeasuredDimension(if (widthMode == MeasureSpec.EXACTLY) width else horizontalPadding,
                if (heightMode == MeasureSpec.EXACTLY) height else verticalPadding)
            return
        }
        content.measure(childWidth, childHeight)
        setMeasuredDimension(
            when (widthMode) {
                MeasureSpec.EXACTLY -> width
                MeasureSpec.AT_MOST -> minOf(width, content.measuredWidth + horizontalPadding)
                else -> content.measuredWidth + horizontalPadding
            },
            when (heightMode) {
                MeasureSpec.EXACTLY -> height
                MeasureSpec.AT_MOST -> minOf(height, content.measuredHeight + verticalPadding)
                else -> content.measuredHeight + verticalPadding
            })
        if (content.measuredHeight + verticalPadding > height && dialogWindow.attributes.height == LayoutParams.WRAP_CONTENT) {
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            dialogWindow.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        onContentMeasured()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (content.visibility == GONE) return
        val l = paddingLeft + (width - content.measuredWidth - paddingLeft - paddingRight) / 2
        val t = paddingTop + (height - content.measuredHeight - paddingTop - paddingBottom) / 2
        content.layout(l, t, l + content.measuredWidth, t + content.measuredHeight)
    }

    private inline fun <T> insetForChild(unchanged: T, block: (Int, Int, Int, Int) -> T): T {
        val l = max(0, content.left); val t = max(0, content.top)
        val r = max(0, width - content.right); val b = max(0, height - content.bottom)
        return if (l == 0 && t == 0 && r == 0 && b == 0) unchanged else block(l, t, r, b)
    }

    fun isInsideContent(event: MotionEvent): Boolean {
        if (!event.x.isFinite() || !event.y.isFinite() || content.visibility == GONE) return false
        val l = left + content.left; val t = top + content.top
        return event.x.roundToInt() in l..(l + content.width) && event.y.roundToInt() in t..(t + content.height)
    }
}
