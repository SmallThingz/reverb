/*
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Source-derived from foundation 1.11.2 ScrollNode / VerticalScrollableClipShape.
 */
package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.NativeScrollAccessibilityRegistration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import app.smallthingz.nativeui.animation.splineBasedDecay
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.unit.Density
import app.smallthingz.nativeui.unit.dp
import app.smallthingz.nativeui.geometry.Rect as ComposeRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Existing ancestor dispatch, not a second nested-scrolling implementation. Physical Y units. */
internal interface NativeVerticalNestedHost {
    fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float
    fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float
    suspend fun preFling(availableY: Float): Float
    suspend fun postFling(consumedY: Float, availableY: Float): Float
}

/**
 * Ordinary single-content ViewGroup for the released onboarding verticalScroll.
 *
 * The shared root pointer dispatcher owns all Initial/Main/Final routing; this View neither
 * intercepts touch nor creates a drag detector. It observes the same local pointer stream for edge
 * displacement, and exposes [owner] and the actual attached [inputScope] to that dispatcher.
 *
 * Layout/configuration density is immutable. Retain [state] per page, recreate this host on the
 * same source configuration/disposal boundary, and restore the saved integer into the state before
 * measurement. A predictive preview has a separate host/edge effect and shares only the state.
 */
internal class NativeVerticalViewport(
    context: Context,
    val state: NativeVerticalScrollState,
    density: Density,
    frames: MonotonicFrameClock,
    private val parentScope: CoroutineScope,
    nested: NativeVerticalNestedHost,
    glowColor: Int = 0xff666666.toInt(),
) : ViewGroup(context), NativeBringIntoViewParent {
    private val crossAxisInflation = with(density) { 30.dp.roundToPx() }
    private val overscroll = NativeVerticalOverscroll(context, density, glowColor) { invalidate() }
    private var positionBinding: AutoCloseable? = null
    private var attachedScope: CoroutineScope? = null
    private var measuring = false
    private var localScrollRange = 0
    private var reportedScrollPosition = state.value
    private var focusedDescendant: View? = null
    private var windowScrollEvents: NativeScrollAccessibilityRegistration? = null
    private var pendingScrollEvent = false
    private var pendingLayoutEvent = false
    var sourceAccessibilityNode: (() -> NativeSourceAccessibilityNode)? = null
    var sourceAccessibilityChildren: (() -> List<View>)? = null
    override val bringIntoViewView: View get() = this

    /** New input admission only. It does not invent cancellation of an already-running fling. */
    var interactionEnabled = true
        set(value) {
            if (field == value) return
            field = value
            windowScrollEvents?.layoutChanged()
        }

    private val engine = NativeVerticalScrollEngine(state, object : NativeVerticalScrollHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) =
            nested.preScroll(availableY, source)
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) =
            nested.postScroll(consumedY, availableY, source)
        override fun positionChanged(value: Int) = Unit // Shared-state binding updates every drawn host.
    })

    val owner = NativeVerticalScrollOwner(
        engine, splineBasedDecay(density), frames,
        object : NativeVerticalFlingHost {
            override suspend fun preFling(availableY: Float) = nested.preFling(availableY)
            override suspend fun postFling(consumedY: Float, availableY: Float) =
                nested.postFling(consumedY, availableY)
            override fun isAttached() = isAttachedToWindow
        },
        overscroll,
    )

    private val contentInView = NativeContentInViewOwner(
        owner, frames, { inputScope }, ::focusedBounds, nativeBringIntoViewSpec(context),
    )

    /** This is the source node's lifetime, not the containing screen's longer-lived scope. */
    val inputScope: CoroutineScope
        get() = checkNotNull(attachedScope) { "The viewport input node is not attached" }

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isSaveEnabled = false // The page owner saves the retained state's integer exactly once.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRect(-crossAxisInflation, 0, view.width + crossAxisInflation, view.height)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun setContent(content: View) {
        if (childCount == 1 && getChildAt(0) === content) return
        removeAllViews()
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Existing root dispatch calls this even when another node consumed the event. */
    fun observePointer(event: MotionEvent) {
        overscroll.pointer(event)
    }

    fun saveScrollPosition(): Int = state.save()

    /**
     * Semantics/character geometry clips to actual layer size, not the 30 dp drawing expansion.
     * Text geometry walks child.left/top and parent.scrollX/Y; this host always keeps scrollY=0.
     */
    fun semanticsViewport(out: Rect) {
        out.set(0, 0, width, height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        check(heightMode != MeasureSpec.UNSPECIFIED) {
            "A vertically scrollable component requires a finite maximum height"
        }
        val maximumHeight = MeasureSpec.getSize(heightMeasureSpec)
        val minimumHeight = if (heightMode == MeasureSpec.EXACTLY) maximumHeight else 0
        val content = if (childCount == 0) null else getChildAt(0)
        measuring = true
        try {
            if (content != null) {
                // ScrollNode retains bounded width and minHeight while removing maxHeight.
                // Android MeasureSpec cannot represent that min+unbounded pair. Ordinary Views
                // first report natural height; only a short child needs the exact-minimum pass.
                content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (content.measuredHeight < minimumHeight) {
                    content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minimumHeight, MeasureSpec.EXACTLY))
                }
            }
            val childWidth = content?.measuredWidth ?: 0
            val childHeight = content?.measuredHeight ?: minimumHeight
            val measuredWidth = resolveSize(childWidth, widthMeasureSpec)
            val measuredHeight = childHeight.coerceAtMost(maximumHeight)
            localScrollRange = (childHeight - measuredHeight).coerceAtLeast(0)
            // Source publishes bounds before returning its measure result, not after placement.
            val previousMaximum = state.maxValue
            state.measured(measuredHeight, childHeight)
            if (previousMaximum != state.maxValue) pendingLayoutEvent = true
            contentInView.measured(measuredHeight)
            setMeasuredDimension(measuredWidth, measuredHeight)
        } finally {
            measuring = false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = if (childCount == 0) null else getChildAt(0)
        if (child != null) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - child.measuredWidth else 0
            val y = -state.value.coerceIn(0, localScrollRange)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        if (changed) pendingLayoutEvent = true
        notifyPlacedAccessibility()
    }

    /** One actual window queue; no per-viewport timer or duplicate stock scroll event. */
    fun bindWindowScrollEvents(registration: NativeScrollAccessibilityRegistration): AutoCloseable {
        check(windowScrollEvents == null)
        windowScrollEvents = registration
        // First range observation seeds source state; it is not a synthetic scroll.
        pendingScrollEvent = false
        return AutoCloseable {
            if (windowScrollEvents === registration) {
                windowScrollEvents = null
                pendingScrollEvent = false
                pendingLayoutEvent = false
            }
        }
    }

    private fun notifyPlacedAccessibility() {
        if (pendingLayoutEvent) windowScrollEvents?.layoutChanged()
        if (pendingScrollEvent) windowScrollEvents?.scrollChanged()
        pendingLayoutEvent = false
        pendingScrollEvent = false
    }

    private fun positionChanged() {
        val previous = reportedScrollPosition
        reportedScrollPosition = state.value
        if (previous != reportedScrollPosition) pendingScrollEvent = true
        if (measuring || isInLayout || !isLaidOut || isLayoutRequested) {
            invalidate()
            return
        }
        if (childCount != 0) {
            val child = getChildAt(0)
            val y = -state.value.coerceIn(0, localScrollRange)
            // Like placeRelativeWithLayer, move the content without remeasuring every descendant.
            child.offsetTopAndBottom(y - child.top)
        }
        notifyPlacedAccessibility()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            super.draw(canvas) // Source-shaped hardware outline clips the whole draw subtree.
        } else {
            val save = canvas.save()
            try {
                canvas.clipRect(-crossAxisInflation, 0, width + crossAxisInflation, height)
                super.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Released AndroidOverscroll updates containerSize at draw, not measure/size callbacks.
        overscroll.measured(width, height)
        overscroll.draw(canvas) { super.dispatchDraw(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        if (event.keyCode != KeyEvent.KEYCODE_PAGE_DOWN && event.keyCode != KeyEvent.KEYCODE_PAGE_UP) return false
        val scope = attachedScope ?: return false
        return owner.pageKey(
            pageDown = event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN,
            keyDown = event.action == KeyEvent.ACTION_DOWN,
            ctrlPressed = event.isCtrlPressed,
            enabled = interactionEnabled,
            nodeScope = scope,
        )
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = false
    override fun onTouchEvent(event: MotionEvent) = false

    override fun getAccessibilityClassName(): CharSequence =
        if (interactionEnabled) "android.widget.ScrollView" else "android.view.View"

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceAccessibilityNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        val source = sourceAccessibilityChildren
        if (source == null) super.addChildrenForAccessibility(outChildren) else outChildren.addAll(source())
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.actionList.toList().forEach(info::removeAction)
        info.addAction(if (isAccessibilityFocused)
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
        else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        info.isAccessibilityFocused = isAccessibilityFocused
        info.isFocusable = false
        info.isFocused = false
        info.isScreenReaderFocusable = false
        info.isClickable = false
        info.isLongClickable = false
        info.isEnabled = true
        info.className = accessibilityClassName
        info.isScrollable = interactionEnabled && state.maxValue > 0
        if (interactionEnabled) {
            if (state.canScrollForward) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
            }
            if (state.canScrollBackward) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
            }
        }
        sourceAccessibilityNode?.invoke()?.populate(info)
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = "android.view.View"
        event.isEnabled = true
        event.isScrollable = false
        event.text.clear()
        event.contentDescription = null
        event.scrollX = 0
        event.maxScrollX = 0
        event.scrollY = if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) state.value else 0
        event.maxScrollY = if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) state.maxValue else 0
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val direction = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, android.R.id.accessibilityActionScrollDown ->
                NativeVerticalAccessibilityDirection.FORWARD
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, android.R.id.accessibilityActionScrollUp ->
                NativeVerticalAccessibilityDirection.BACKWARD
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS ->
                return super.performAccessibilityAction(action, arguments)
            else -> return false
        }
        val scope = attachedScope ?: return false
        val amount = nativeVerticalAccessibilityDelta(direction, height.toFloat(), state.value,
            state.maxValue, interactionEnabled) ?: return false
        scope.launch { owner.semanticsScrollBy(amount) }
        return true
    }

    override fun canScrollVertically(direction: Int): Boolean =
        if (direction < 0) state.canScrollBackward else if (direction > 0) state.canScrollForward else false

    /** AndroidViewHolder ignores immediate and returns true even if no local movement is needed. */
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        val requested = ComposeRect(rectangle.left.toFloat(), rectangle.top.toFloat(),
            rectangle.right.toFloat(), rectangle.bottom.toFloat())
        attachedScope?.launch { bringDescendantIntoView(child) { requested } }
        return true
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        if (focusedDescendant === focused) return
        focusedDescendant = focused
        // Source FocusableNode launches for a newly focused descendant. Loss of focus alone does
        // not cancel its accepted request; a newer request is arbitrated by the source queue.
        attachedScope?.launch {
            bringDescendantIntoView(focused) {
                if (focused.isAttachedToWindow) ComposeRect(0f, 0f, focused.width.toFloat(), focused.height.toFloat())
                else null
            }
        }
    }

    override fun clearChildFocus(child: View) {
        super.clearChildFocus(child)
        // Losing focus does not cancel an accepted request; it only admits a future focus entry.
        focusedDescendant = null
    }

    private fun focusedBounds(): ComposeRect? {
        val focused = findFocus()?.takeIf { it !== this } ?: return null
        return nativeBringIntoViewLocalRect(this, focused,
            ComposeRect(0f, 0f, focused.width.toFloat(), focused.height.toFloat()))
    }

    override suspend fun bringDescendantIntoView(child: View, bounds: () -> ComposeRect?) {
        fun localRect(): ComposeRect? {
            if (!isAttachedToWindow || !isLaidOut) return null
            return bounds()?.let { nativeBringIntoViewLocalRect(this, child, it) }
        }
        val parentBounds = { localRect()?.let(contentInView::calculateRectForParent) }
        coroutineScope {
            // Keep two child coroutines: a parent's normal CancellationException must not cancel
            // the independently accepted local responder, exactly like BringIntoViewResponderNode.
            launch { contentInView.bringChildIntoView(::localRect) }
            launch {
                var ancestor = parent
                while (ancestor is View && ancestor !is NativeBringIntoViewParent) ancestor = ancestor.parent
                if (ancestor is NativeBringIntoViewParent) {
                    ancestor.bringDescendantIntoView(this@NativeVerticalViewport, parentBounds)
                } else {
                    parentBounds()?.let { requestRectangleOnScreen(it.toNativeBringIntoViewRect(), false) }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        positionBinding = state.observePosition(::positionChanged)
        positionChanged()
    }

    override fun onDetachedFromWindow() {
        focusedDescendant = null
        positionBinding?.close()
        positionBinding = null
        attachedScope?.cancel()
        attachedScope = null
        overscroll.dispose()
        super.onDetachedFromWindow()
    }

    override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
