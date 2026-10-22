/*
 * Source-derived from AndroidX UI 1.11.2 AndroidPopup.android.kt and Material3 1.4.0
 * AndroidMenu.android.kt. Copyright 2020-2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.unit.Density
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.smallthingz.reverb.NativePagerTouchRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Actual focusable sub-panel with the released Android Popup flags and geometry. */
internal class SettingsMenuWindow(
    private val anchor: View,
    private val menu: SettingsMenuContent,
    private val density: Density,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val input: SettingsMenuPointerBinding,
    private val onDismissRequest: () -> Unit,
    private val onTerminal: () -> Unit,
) : NativePagerTouchRoot(
    anchor.context,
    rootToScreen = input.geometry::rootToScreen,
    coordinates = input.geometry::coordinates,
    hitLayout = input.layout,
    prepareCoordinates = input.geometry::begin,
), AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val params = WindowManager.LayoutParams().apply {
        gravity = Gravity.START or Gravity.TOP
        flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            (((anchor.rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and
                WindowManager.LayoutParams.FLAG_SECURE)
        type = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
        token = anchor.applicationWindowToken
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        format = PixelFormat.TRANSLUCENT
        title = context.getString(app.smallthingz.nativeui.R.string.default_popup_window_title)
    }
    private val visibleFrame = Rect()
    private val location = IntArray(2)
    private val transition = SettingsMenuTransition()
    private val animationLayer = SettingsMenuAnimationLayer(menu)
    private var frameJob: Job? = null
    private var back: Any? = null
    private var added = false
    private var closed = false
    private var positioned = false
    private var menuMeasured = false
    private var previousAnchor: SettingsMenuBounds? = null
    private var previousMenuWidth = -1
    private var previousMenuHeight = -1
    private var previousWindowWidth = -1
    private var previousWindowHeight = -1
    private var anchorObserver: ViewTreeObserver? = null
    private val anchorLayout = ViewTreeObserver.OnGlobalLayoutListener {
        // Insets/window changes can change the usable frame without moving this anchor. This
        // callback is layout-driven; the steady frame loop below only polls cheap coordinates.
        updatePosition(force = true)
    }

    init {
        input.bindRoot(this)
        id = android.R.id.content
        setViewTreeLifecycleOwner(anchor.findViewTreeLifecycleOwner())
        setViewTreeViewModelStoreOwner(anchor.findViewTreeViewModelStoreOwner())
        setViewTreeSavedStateRegistryOwner(anchor.findViewTreeSavedStateRegistryOwner())
        isSaveEnabled = false // The screen owner saves each retained ScrollState exactly once.
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        // Compose enters an eligible menu item in keyboard mode; touch mode keeps root focus.
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        super.setLayoutDirection(anchor.layoutDirection)
        elevation = 8f * density.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, result: Outline) {
                result.setRect(0,0,view.width,view.height)
                result.alpha = 0f // Allocate surface shadow insets without a rectangular root shadow.
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        animationLayer.scaleX = transition.scale
        animationLayer.scaleY = transition.scale
        animationLayer.alpha = 0f
        addView(animationLayer)
    }

    fun show() {
        check(!added && !closed)
        transition.retarget(true)
        // Mark before addView, because its synchronous attach callback starts the frame owner.
        added = true
        try { manager.addView(this,params) }
        catch (failure: Throwable) {
            added = isAttachedToWindow || parent != null
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun expanded(expanded: Boolean) {
        if (closed) return
        transition.retarget(expanded)
        // AndroidMenu's condition is outside DropdownMenuContent/updateTransition. A close before
        // first entry settles removes the popup immediately, rather than inventing an exit tween.
        if (!transition.shouldExist) onTerminal()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anchorObserver = anchor.viewTreeObserver.also { it.addOnGlobalLayoutListener(anchorLayout) }
        if (Build.VERSION.SDK_INT >= 33) back = MenuBackApi33.register(this,onDismissRequest)
        frameJob = scope.launch {
            while (isActive && !closed) {
                frames.withFrameNanos { time ->
                    if (!closed) {
                        updatePosition()
                        transition.frame(time,ValueAnimator.getDurationScale())
                        animationLayer.scaleX = transition.scale
                        animationLayer.scaleY = transition.scale
                        animationLayer.alpha = if (positioned && anchor.isAttachedToWindow) transition.alpha else 0f
                        if (!transition.shouldExist) onTerminal()
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        frameJob?.cancel()
        frameJob = null
        anchorObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(anchorLayout)
        anchorObserver = null
        if (Build.VERSION.SDK_INT >= 33) MenuBackApi33.unregister(this,back)
        back = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        animationLayer.measure(atMost(visibleFrame.width()),atMost(visibleFrame.height()))
        setMeasuredDimension(animationLayer.measuredWidth,animationLayer.measuredHeight)
        menuMeasured = true
        // Compose's onSizeChanged publishes placement during measure, before ViewRoot's initial
        // relayout and renderer light-center setup. Waiting for onLayout leaves the first setup
        // at the default window origin even though the popup is drawn at its final placement.
        updatePosition()
    }

    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        animationLayer.layout(0,0,animationLayer.measuredWidth,animationLayer.measuredHeight)
        params.width = menu.measuredWidth
        params.height = menu.measuredHeight
        if (added && !closed) {
            updatePosition(force=true)
        }
        invalidateOutline()
    }

    private fun updatePosition(force: Boolean = false) {
        if (!added || closed || !anchor.isAttachedToWindow || !menuMeasured) return
        val menuWidth = menu.measuredWidth
        val menuHeight = menu.measuredHeight
        anchor.getLocationInWindow(location)
        val previous = previousAnchor
        val sameAnchor = previous != null && previous.left == location[0] && previous.top == location[1] &&
            previous.width == anchor.width && previous.height == anchor.height
        if (!force && sameAnchor && menuWidth == previousMenuWidth && menuHeight == previousMenuHeight) return
        val bounds = if (sameAnchor) requireNotNull(previous) else
            SettingsMenuBounds(location[0],location[1],location[0]+anchor.width,location[1]+anchor.height)
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        val windowWidth = visibleFrame.width()
        val windowHeight = visibleFrame.height()
        if (!force && bounds == previousAnchor && menuWidth == previousMenuWidth &&
            menuHeight == previousMenuHeight && windowWidth == previousWindowWidth &&
            windowHeight == previousWindowHeight) return
        if (windowWidth != previousWindowWidth || windowHeight != previousWindowHeight) requestLayout()
        previousAnchor = bounds
        previousMenuWidth = menuWidth
        previousMenuHeight = menuHeight
        previousWindowWidth = windowWidth
        previousWindowHeight = windowHeight
        val placement = settingsMenuPlacement(bounds,windowWidth,windowHeight,menuWidth,menuHeight,
            anchor.layoutDirection == LAYOUT_DIRECTION_RTL,(48f*density.density).roundToInt())
        params.x = placement.x
        params.y = placement.y
        animationLayer.pivotX = placement.pivotX * menuWidth
        animationLayer.pivotY = placement.pivotY * menuHeight
        if (Build.VERSION.SDK_INT >= 29)
            systemGestureExclusionRects = listOf(Rect(0,0,windowWidth,windowHeight))
        manager.updateViewLayout(this,params)
        positioned = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val state = keyDispatcherState ?: return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                state.startTracking(event,this)
                return true
            } else if (event.action == KeyEvent.ACTION_UP && state.isTracking(event) && !event.isCanceled) {
                onDismissRequest()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if ((event.action == MotionEvent.ACTION_DOWN &&
                (event.x < 0 || event.x >= width || event.y < 0 || event.y >= height)) ||
            event.action == MotionEvent.ACTION_OUTSIDE) {
            onDismissRequest()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    // Source Popup ignores ViewRootImpl's locale override and retains its parent's direction.
    override fun setLayoutDirection(layoutDirection: Int) = Unit

    override fun close() {
        if (closed) return
        closed = true
        frameJob?.cancel()
        frameJob = null
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        cleanup { input.close() }
        setViewTreeLifecycleOwner(null)
        if (added) {
            added = false
            cleanup { manager.removeViewImmediate(this) }
        }
        cleanup { menu.close() }
        failure?.let { throw it }
    }
}

/** Menu.kt applies motion outside Surface's separate shadow/background/content layers. */
private class SettingsMenuAnimationLayer(private val menu: SettingsMenuContent) : ViewGroup(menu.context) {
    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = false
        isFocusable = false
        isClickable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(menu)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        menu.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(menu.measuredWidth, menu.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        menu.layout(0, 0, menu.measuredWidth, menu.measuredHeight)
    }
}

@RequiresApi(33)
private object MenuBackApi33 {
    fun register(view: View,dismiss: () -> Unit): Any {
        val callback = OnBackInvokedCallback { dismiss() }
        view.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback,
        )
        return callback
    }
    fun unregister(view: View,callback: Any?) {
        if (callback is OnBackInvokedCallback)
            view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }
}
