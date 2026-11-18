package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeMaterialButtonInteractionsFactory
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import smallthingz.ui.animation.view.ViewColorTransition

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import app.smallthingz.reverb.R
import java.io.Closeable
import kotlin.math.max
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

private data class FooterActions(val back: () -> Unit, val continueOrFinish: () -> Unit)

/**
 * Excludes the outer screen's safe-area and 24dp horizontal / 20dp vertical padding.
 * Includes the 16dp gap above dots, the 8dp-high dot row, 14dp gap, and normally equal-weight buttons.
 * Requires the qualified released labelLarge TextStyle and an immutable exact Density adapter.
 */
internal class OnboardingFooterView(
    context: Context,
    labelLarge: TextStyle,
    textDensity: Density,
    resolver: FontFamily.Resolver,
    interactions: NativeMaterialButtonInteractionsFactory,
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
    private var dotColors:ViewColorTransition?=null
    private var boundConfiguration: Configuration? = null
    private var actions: FooterActions? = null
    private var buttonHeight = 0
    private var backWidth = 0
    private var continueWidth = 0
    private var stackedButtons = false
    private val back = NativeMaterialButtonView(context, false, reverbButtonTextStyle(labelLarge), textDensity, resolver,
        NativeMaterialButtonStyle(12, assignedSize = true), interactions) {
        actions?.back?.invoke()
    }
    private val next = NativeMaterialButtonView(context, true, reverbButtonTextStyle(labelLarge), textDensity, resolver,
        NativeMaterialButtonStyle(24, assignedSize = true), interactions) {
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
        // Pager frames keep these values unchanged. Callbacks read the current actions directly;
        // typography and attachment own their updates independently. Copy configuration so a
        // resource/locale change still refreshes labels when state and palette remain identical.
        val configuration = resources.configuration
        if (state == value && palette === colors && boundConfiguration == configuration) return
        if (boundConfiguration != configuration) boundConfiguration = Configuration(configuration)
        val old = state
        state = value
        palette = colors
        val targets=IntArray(3) {if(it==value.page)colors.primary else colors.outlineVariant}
        val dots=dotColors ?: ViewColorTransition(this,targets,{invalidate()}).also {dotColors=it}
        dots.retarget(*targets)
        back.bind(
            context.getString(R.string.onboarding_back),
            enabled = !value.finishing && value.page > 0,
            container = android.graphics.Color.TRANSPARENT,
            contentColor = if (!value.finishing && value.page > 0) colors.primary
                else reverbColorWithAlpha(colors.onSurfaceVariant, 0.38f),
        )
        val enabled = !value.finishing
        next.bind(
            context.getString(when {
                value.footerPage < 2 -> R.string.onboarding_continue
                else -> R.string.onboarding_get_started
            }),
            enabled = enabled,
            container = when {
                !enabled -> reverbColorWithAlpha(colors.onSurface, 0.1f)
                value.ready -> colors.primary
                else -> colors.field
            },
            contentColor = when {
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
        val inherited = reverbButtonTextStyle(labelLarge)
        back.bindTypography(inherited, textDensity, resolver)
        next.bindTypography(inherited, textDensity, resolver)
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
        // Equal weights remain exact when labels fit. Compact padding and measured widths avoid
        // splitting a short action into individual letters at narrow widths or enlarged font sizes.
        val compact = back.preferredWidth(false) > backWidth || next.preferredWidth(false) > continueWidth
        next.compactPadding = compact
        stackedButtons = false
        if (compact) {
            val backMinimum = max(dp(48), back.preferredWidth(true))
            val nextMinimum = max(dp(48), next.preferredWidth(true))
            if (backMinimum + nextMinimum <= remaining) {
                continueWidth = continueWidth.coerceIn(nextMinimum, remaining - backMinimum)
                backWidth = remaining - continueWidth
            } else {
                stackedButtons = true
                backWidth = width
                continueWidth = width
            }
        }
        // IntrinsicSize.Min queries weighted children at the rounded common weight allocation.
        buttonHeight = max(dp(48), max(back.intrinsicHeight(if (compact) backWidth else unit),
            next.intrinsicHeight(if (compact) continueWidth else unit)))
        if (stackedButtons && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            dp(16) + dp(8) + dp(14) + buttonHeight * 2 + dp(10) + dp(48) > MeasureSpec.getSize(heightMeasureSpec)) {
            // The screen supplies its total content budget. A stacked footer must leave at least
            // one minimum interactive row for the scrollable page, not merely fit its own buttons.
            stackedButtons = false
            backWidth = unit + (remaining - unit * 2).coerceIn(-1, 1)
            continueWidth = remaining - backWidth
            buttonHeight = max(dp(48), max(back.intrinsicHeight(backWidth), next.intrinsicHeight(continueWidth)))
        }
        back.measure(exact(backWidth), exact(buttonHeight))
        next.measure(exact(continueWidth), exact(buttonHeight))
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(dp(16) + dp(8) + dp(14) +
                (if (stackedButtons) buttonHeight * 2 + dp(10) else buttonHeight), heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val y = dp(16) + dp(8) + dp(14)
        if (stackedButtons) {
            back.layout(0, y, width, y + buttonHeight)
            next.layout(0, y + buttonHeight + dp(10), width, y + buttonHeight * 2 + dp(10))
        } else if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            back.layout(width - backWidth, y, width, y + buttonHeight)
            next.layout(0, y, continueWidth, y + buttonHeight)
        } else {
            back.layout(0, y, backWidth, y + buttonHeight)
            next.layout(width - continueWidth, y, width, y + buttonHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = dotColors ?: return
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
            paint.color = colors.color(index)
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

private fun NativeMaterialButtonView.bindAccessibility(
    cursor: PlainTextAccessibilityWindowScope,
    geometry: NativePlainTextViewGeometry,
    sourceNode: ((View) -> NativeSourceAccessibilityNode)?,
    exposed: (View) -> Boolean,
): AutoCloseable {
    val role = if (sourceNode != null) {
        mergedRowAccessibility.bindSourceGeometry(geometry, sourceNode) { exposed(this) }
    } else mergedRowAccessibility.bindGeometry(geometry) { exposed(this) }
    val text = try { label.bindPlainTextAccessibility(cursor, geometry,
        sourceNode?.let { node -> { node(label) } }) { exposed(label) } }
    catch (error: Throwable) {
        try { role.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }
    return AutoCloseable { closeNativeFooterAccessibility(role, text) }
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
