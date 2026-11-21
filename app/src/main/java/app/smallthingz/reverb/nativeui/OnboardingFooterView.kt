package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
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
 * Uses the appearance and exact density of its window.
 */
internal class OnboardingFooterView(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
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

    private val recipe = environment.appearance.onboardingFooter
    private val indicator = recipe.createIndicator(this, 3)
    private var state = OnboardingFooterState(0, 0, ready = false, finishing = false)
    private var palette: ReverbPalette? = null
    private var boundConfiguration: Configuration? = null
    private var actions: FooterActions? = null
    private var buttonHeight = 0
    private var backWidth = 0
    private var continueWidth = 0
    private var stackedButtons = false
    private val inputPolicy = NativeButtonInputPolicy(inputRoot)
    private val back = NativeMaterialButtonView(context, environment.appearance.buttons.footerBack,
        environment.textDensity, environment.sharedFontResolver, inputPolicy) {
        actions?.back?.invoke()
    }
    private val next = NativeMaterialButtonView(context, environment.appearance.buttons.footerContinue,
        environment.textDensity, environment.sharedFontResolver, inputPolicy) {
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
        indicator.bind(value.page, colors)
        back.bind(
            context.getString(R.string.onboarding_back),
            enabled = !value.finishing && value.page > 0,
            palette = colors.buttonPalette,
        )
        next.bind(
            context.getString(when {
                value.footerPage < 2 -> R.string.onboarding_continue
                else -> R.string.onboarding_get_started
            }),
            enabled = !value.finishing,
            palette = colors.buttonPalette,
            emphasized = value.ready,
        )
        // The selected indicator width changes immediately.
        if (old.page != value.page) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            "Released footer is fillMaxWidth; host must supply a bounded width"
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val actionGap = dp(recipe.actionGapDp)
        val actionsTop = recipe.actionsTopPx(resources.displayMetrics.density)
        val remaining = (width - actionGap).coerceAtLeast(0)
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
            val backMinimum = max(dp(back.minimumTouchTargetDp), back.preferredWidth(true))
            val nextMinimum = max(dp(next.minimumTouchTargetDp), next.preferredWidth(true))
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
        val minimumButtonHeight = dp(max(back.minimumTouchTargetDp, next.minimumTouchTargetDp))
        buttonHeight = max(minimumButtonHeight, max(back.intrinsicHeight(if (compact) backWidth else unit),
            next.intrinsicHeight(if (compact) continueWidth else unit)))
        if (stackedButtons && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            actionsTop + buttonHeight * 2 + actionGap + dp(recipe.minimumPageSpaceDp) > MeasureSpec.getSize(heightMeasureSpec)) {
            // The screen supplies its total content budget. A stacked footer must leave at least
            // one minimum interactive row for the scrollable page, not merely fit its own buttons.
            stackedButtons = false
            backWidth = unit + (remaining - unit * 2).coerceIn(-1, 1)
            continueWidth = remaining - backWidth
            buttonHeight = max(minimumButtonHeight, max(back.intrinsicHeight(backWidth), next.intrinsicHeight(continueWidth)))
        }
        back.measure(exact(backWidth), exact(buttonHeight))
        next.measure(exact(continueWidth), exact(buttonHeight))
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(actionsTop + (if (stackedButtons) buttonHeight * 2 + actionGap else buttonHeight), heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val y = recipe.actionsTopPx(resources.displayMetrics.density)
        if (stackedButtons) {
            val actionGap = dp(recipe.actionGapDp)
            back.layout(0, y, width, y + buttonHeight)
            next.layout(0, y + buttonHeight + actionGap, width, y + buttonHeight * 2 + actionGap)
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
        indicator.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        cancelInput()
        super.onDetachedFromWindow()
    }

    private fun cancelInput() {
        back.cancelPendingInputEvents()
        next.cancelPendingInputEvents()
    }

    private fun dp(value: Int): Int = dp(value.toFloat())
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
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
