package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.R
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native body for MainActivity.kt:667-722 and :1045-1050 at released 3e16f8d.
 * Host keeps the pager/navigation/permission/Finish state and the original click callbacks.
 */
internal data class OnboardingFooterState(
    /** predictiveTarget.sourcePage ?: pagerState.settledPage; selects dots and Back enablement. */
    val page: Int,
    /** predictiveTarget.sourcePage ?: minOf(page, pagerState.targetPage); selects label/readiness. */
    val footerPage: Int,
    /** Exact existing host expression; false changes colors, never Continue click admission. */
    val ready: Boolean,
    val finishing: Boolean,
) {
    init {
        require(page in 0..2 && footerPage in 0..2)
    }
}

/**
 * Pinned clickable + ripple + ButtonElevation boundary, implemented by
 * OnboardingNativeInteractionsFactory using the shared native input/ripple/elevation components.
 *
 * inputHost.performClick() delivers accepted pointer/key/accessibility clicks to the CURRENT callback.
 * indicationHost exclusively owns the returned foreground drawable and its callback; never install
 * it on inputHost too. FooterButton supplies clipping and draws that foreground after its label.
 * Pointer admission must honor the capsule and source minimum-target hit rules, not the View's
 * rectangular bounds. Do not let platform pressed state create a second interaction stream.
 * setShadowElevationPx must preserve M3's interaction ordering, tween and disabled snap behavior.
 */
internal fun interface OnboardingFooterInteractionsFactory {
    fun create(
        inputHost: View,
        indicationHost: View,
        filled: Boolean,
        setShadowElevationPx: (Float) -> Unit,
    ): OnboardingFooterInteractions
}

internal interface OnboardingFooterInteractions {
    val pointerEndpoint: NativeClickPhaseEndpoint
    val foreground: Drawable
    fun bind(enabled: Boolean, contentColor: Int)
    fun measured(width: Int, height: Int)
    fun touch(event: MotionEvent): Boolean
    fun key(event: KeyEvent): Boolean
    fun hover(event: MotionEvent): Boolean
    fun focusChanged(focused: Boolean)
    fun cancel()
    fun attached()
    fun detached()
}

private data class FooterActions(val back: () -> Unit, val continueOrFinish: () -> Unit)

/**
 * Excludes the outer screen's safe-area and 24dp horizontal / 20dp vertical padding.
 * Includes the 16dp gap above dots, the 8dp-high dot row, 14dp gap, and equal-weight buttons.
 * Requires the qualified released labelLarge TextStyle and an immutable exact Density adapter.
 */
internal class OnboardingFooterView(
    context: Context,
    labelLarge: TextStyle,
    textDensity: Density,
    resolver: FontFamily.Resolver,
    interactions: OnboardingFooterInteractionsFactory,
) : ViewGroup(context) {
    /** Same native window cursor as page labels; keep the lease until this footer is disposed. */
    fun bindTextAccessibility(window: NativeWindowTextAccessibilityOwner,
                              sourceNode: ((View) -> NativeSourceAccessibilityNode)? = null,
                              exposed: (View) -> Boolean): AutoCloseable = window.bindFooter(this, sourceNode, exposed)

    internal fun clipsAccessibilityTextAt(view: View): Boolean =
        back.clipsAccessibilityTextAt(view) || next.clipsAccessibilityTextAt(view)

    internal fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
                                   geometry: NativePlainTextViewGeometry,
                                   sourceNode: ((View) -> NativeSourceAccessibilityNode)?,
                                   exposed: (View) -> Boolean): AutoCloseable {
        val first = back.bindAccessibility(cursor, geometry, sourceNode, exposed)
        val second = try { next.bindAccessibility(cursor, geometry, sourceNode, exposed) }
        catch (error: Throwable) {
            try { first.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
        return AutoCloseable { closeNativeFooterAccessibility(first, second) }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var state = OnboardingFooterState(0, 0, ready = false, finishing = false)
    private var palette: ReverbPalette? = null
    private var actions: FooterActions? = null
    private var buttonHeight = 0
    private var backWidth = 0
    private var continueWidth = 0
    private val back = FooterButton(context, false, labelLarge, textDensity, resolver, interactions) {
        actions?.back?.invoke()
    }
    private val next = FooterButton(context, true, labelLarge, textDensity, resolver, interactions) {
        actions?.continueOrFinish?.invoke()
    }

    init {
        setWillNotDraw(false)
        clipChildren = false // Filled Button's hover shadow may extend outside its layout bounds.
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(back)
        addView(next)
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    /**
     * Callbacks survive window reattachment. Close the lease when the owning screen retires.
     * Closing an old handle cannot retire replacement callbacks.
     * These are the original host lambdas: this footer never computes navigation or submits Finish.
     */
    fun connect(onBack: () -> Unit, onContinueOrFinish: () -> Unit): Closeable {
        cancelInput()
        val connected = FooterActions(onBack, onContinueOrFinish)
        actions = connected
        return Closeable {
            if (actions === connected) {
                actions = null
                cancelInput()
            }
        }
    }

    fun bind(value: OnboardingFooterState, colors: ReverbPalette) {
        val old = state
        state = value
        palette = colors
        back.bind(
            context.getString(R.string.onboarding_back),
            enabled = !value.finishing && value.page > 0,
            container = android.graphics.Color.TRANSPARENT,
            content = if (!value.finishing && value.page > 0) colors.primary
                else reverbColorWithAlpha(colors.onSurfaceVariant, 0.38f),
        )
        val enabled = !value.finishing
        next.bind(
            context.getString(if (value.footerPage < 2) R.string.onboarding_continue else R.string.onboarding_get_started),
            enabled = enabled,
            container = when {
                !enabled -> reverbColorWithAlpha(colors.onSurface, 0.1f)
                value.ready -> colors.primary
                else -> colors.field
            },
            content = when {
                !enabled -> reverbColorWithAlpha(colors.onSurfaceVariant, 0.38f)
                value.ready -> colors.onPrimary
                else -> colors.ink
            },
        )
        // Release dots and label/colors have no transition or width animation.
        if (old.page != value.page) requestLayout()
        invalidate()
    }

    fun bindTypography(labelLarge: TextStyle, textDensity: Density, resolver: FontFamily.Resolver) {
        back.bindTypography(labelLarge, textDensity, resolver)
        next.bindTypography(labelLarge, textDensity, resolver)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            "Released footer is fillMaxWidth; host must supply a bounded width"
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val remaining = (width - dp(10)).coerceAtLeast(0)
        // Source Row uses rounded weight unit and distributes negative remainder to first child.
        val unit = (remaining / 2f).roundToInt()
        backWidth = unit + (remaining - unit * 2).coerceIn(-1, 1)
        continueWidth = remaining - backWidth
        // IntrinsicSize.Min queries weighted children at the rounded common weight allocation.
        buttonHeight = max(dp(48), max(back.intrinsicHeight(unit), next.intrinsicHeight(unit)))
        back.measure(exact(backWidth), exact(buttonHeight))
        next.measure(exact(continueWidth), exact(buttonHeight))
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(dp(16) + dp(8) + dp(14) + buttonHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val y = dp(16) + dp(8) + dp(14)
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            back.layout(width - backWidth, y, width, y + buttonHeight)
            next.layout(0, y, continueWidth, y + buttonHeight)
        } else {
            back.layout(0, y, backWidth, y + buttonHeight)
            next.layout(width - continueWidth, y, width, y + buttonHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = palette ?: return
        val activeWidth = dp(18)
        val idleWidth = dp(6)
        val gap = dp(8)
        val dotHeight = dp(6)
        val total = activeWidth + idleWidth * 2 + gap * 2
        var x = (width - total) / 2f
        val y = dp(16) + ((dp(8) - dotHeight) / 2f).roundToInt()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (visualIndex in 0..2) {
            val index = if (rtl) 2 - visualIndex else visualIndex
            val dotWidth = if (state.page == index) activeWidth else idleWidth
            val start = x.roundToInt().toFloat()
            paint.color = if (state.page == index) colors.primary else colors.outlineVariant
            canvas.drawRoundRect(start, y.toFloat(), start + dotWidth, (y + dotHeight).toFloat(),
                dotHeight / 2f, dotHeight / 2f, paint)
            x += dotWidth + gap
        }
    }

    override fun onDetachedFromWindow() {
        cancelInput()
        super.onDetachedFromWindow()
    }

    private fun cancelInput() {
        back.cancelPendingInputEvents()
        next.cancelPendingInputEvents()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.EXACTLY)
}

/** One native button body; fill/shadow are outside the outline-clipped label/ripple layer. */
private class FooterButton(
    context: Context,
    private val filled: Boolean,
    style: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    factory: OnboardingFooterInteractionsFactory,
    private val onActivate: () -> Unit,
) : ViewGroup(context), NativeClickPhaseHost, NativeMergedRowAccessibilityHost {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = ReverbParagraphView(context, style, density, resolver, android.graphics.Color.TRANSPARENT)
    private val content = object : ViewGroup(context) {
        private val softwareClip = Path()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val radius = min(w, h) / 2f
            softwareClip.rewind()
            softwareClip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        }
        override fun draw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated) {
                super.draw(canvas)
            } else {
                // Pinned Compose ViewLayer clips its whole content on software Canvas too.
                val checkpoint = canvas.save()
                try {
                    canvas.clipPath(softwareClip)
                    super.draw(canvas)
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
            }
        }
        override fun onMeasure(w: Int, h: Int) {
            val horizontal = dp(if (filled) 24 else 12)
            val vertical = dp(8)
            val contentWidth = (MeasureSpec.getSize(w) - horizontal * 2).coerceAtLeast(0)
            val contentHeight = (MeasureSpec.getSize(h) - vertical * 2).coerceAtLeast(0)
            label.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST),
            )
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val x = ((width - label.measuredWidth) / 2f).roundToInt()
            val y = ((height - label.measuredHeight) / 2f).roundToInt()
            label.layout(x, y, x + label.measuredWidth, y + label.measuredHeight)
        }
    }
    private val interaction = factory.create(this, content, filled) { elevation = it }
    override val nativeClickEndpoint: NativeClickPhaseEndpoint get() = interaction.pointerEndpoint
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(this, NativeMergedRowRole.BUTTON,
        sourceState = { NativeMergedRowAccessibilityState(isEnabled, null, null, listOf(label)) },
        invokeSourceClick = {
            if (isEnabled && isAttachedToWindow) { onActivate(); true } else false
        })

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)

    fun clipsAccessibilityTextAt(view: View): Boolean = view === content

    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope,
                          geometry: NativePlainTextViewGeometry,
                          sourceNode: ((View) -> NativeSourceAccessibilityNode)?,
                          exposed: (View) -> Boolean): AutoCloseable {
        val role = mergedRowAccessibility.bindGeometry(geometry,
            sourceNode?.let { node -> { node(this) } }, sourceNode?.let { node -> { child -> node(child).present } }) { exposed(this) }
        val text = try { label.bindPlainTextAccessibility(cursor, geometry,
            sourceNode?.let { node -> { node(label) } }) { exposed(label) } }
        catch (error: Throwable) {
            try { role.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
        return AutoCloseable { closeNativeFooterAccessibility(role, text) }
    }
    private val capsuleOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) / 2f)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        outlineProvider = capsuleOutline
        content.outlineProvider = capsuleOutline
        content.clipToOutline = true
        content.foreground = interaction.foreground
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        label.isScreenReaderFocusable = false
        content.addView(label)
        addView(content)
        addView(mergedRowAccessibility.roleView)
        accessibilityDelegate = mergedRowAccessibility
        setOnClickListener { if (isEnabled && isAttachedToWindow) onActivate() }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(text: String, enabled: Boolean, container: Int, content: Int) {
        // Publish enabled state before cancellation/focus-loss interaction ends. The pinned
        // elevation effect sees the new enabled value when deciding whether a new target snaps.
        interaction.bind(enabled, content)
        if (isEnabled && !enabled) interaction.cancel()
        isEnabled = enabled
        isFocusable = enabled
        label.setText(text)
        label.setTextColor(content)
        paint.color = container
        invalidate()
    }

    fun bindTypography(style: TextStyle, density: Density, resolver: FontFamily.Resolver) {
        label.setTypography(style, density, resolver)
    }

    fun intrinsicHeight(width: Int): Int {
        val horizontal = dp(if (filled) 24 else 12)
        label.measure(
            MeasureSpec.makeMeasureSpec((width - horizontal * 2).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return max(dp(40), label.measuredHeight + dp(8) * 2)
    }

    override fun onMeasure(w: Int, h: Int) {
        content.measure(w, h)
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        mergedRowAccessibility.roleView.measure(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, width, height)
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
        invalidateOutline()
        content.invalidateOutline()
        interaction.measured(width, height)
    }
    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        super.onDraw(canvas)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = interaction.touch(event)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = interaction.key(event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = interaction.key(event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        // Keep native touch-exploration hover events. This outer host has no indication drawable;
        // the explicit interaction policy remains the sole ripple/elevation event stream.
        val nativeHandled = super.onHoverEvent(event)
        return interaction.hover(event) || nativeHandled
    }
    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        interaction.focusChanged(gain)
    }
    override fun onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents()
        interaction.cancel()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        interaction.attached()
    }
    override fun onDetachedFromWindow() {
        interaction.detached()
        super.onDetachedFromWindow()
    }
    override fun performClick(): Boolean = isEnabled && isAttachedToWindow && super.performClick()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private fun closeNativeFooterAccessibility(vararg leases: AutoCloseable) {
    var failure: Throwable? = null
    leases.forEach { lease ->
        try { lease.close() }
        catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
