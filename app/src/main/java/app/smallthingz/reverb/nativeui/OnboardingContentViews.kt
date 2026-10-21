package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import kotlin.math.max
import kotlin.math.roundToInt

/** Exact released roles supplied by the screen owner; no platform font/style substitution. */
internal data class OnboardingPageTypography(
    val headlineMedium: TextStyle,
    val titleMedium: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
)

internal class OnboardingIconView(
    context: Context,
    icon: ReverbIcon,
    color: Int,
) : View(context) {
    private var descriptionWindow: PlainTextAccessibilityWindowScope? = null
    private var descriptionAccessibility: PlainTextAccessibility? = null
    private var descriptionExposure: (() -> Boolean)? = null
    private var descriptionToken: Any? = null
    private var sourceDescriptionNode: (() -> NativeSourceAccessibilityNode)? = null
    private var currentIcon = icon
    private var glyph = ReverbIconDrawable(icon, resources.displayMetrics.density, color)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
    fun bind(icon: ReverbIcon, color: Int) {
        if (currentIcon != icon) {
            currentIcon = icon
            glyph = ReverbIconDrawable(icon, resources.displayMetrics.density, color)
            glyph.setBounds(0, 0, width, height)
        } else glyph.setTint(color)
        glyph.layoutDirection = layoutDirection
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { glyph.setBounds(0, 0, w, h) }
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        glyph.layoutDirection = layoutDirection
        invalidate()
    }
    override fun onDraw(canvas: Canvas) { glyph.draw(canvas) }

    fun bindDescriptionAccessibility(window: PlainTextAccessibilityWindowScope, exposed: () -> Boolean): AutoCloseable =
        bindDescriptionAccessibility(window, null, exposed)

    fun bindDescriptionAccessibility(window: PlainTextAccessibilityWindowScope,
                                     sourceNode: (() -> NativeSourceAccessibilityNode)?,
                                     exposed: () -> Boolean): AutoCloseable {
        val token = Any()
        descriptionToken = token
        descriptionExposure = exposed
        sourceDescriptionNode = sourceNode
        if (descriptionWindow !== window || descriptionAccessibility == null) {
            descriptionWindow = window
            descriptionAccessibility = PlainTextAccessibility(this, window,
                snapshot = { PlainTextAccessibilitySnapshot(contentDescription?.toString().orEmpty(), { null }, { null }) },
                exposed = { descriptionExposure?.invoke() == true }, descriptionOnly = true)
        }
        return AutoCloseable {
            if (descriptionToken === token) {
                descriptionToken = null
                descriptionWindow = null
                descriptionAccessibility = null
                descriptionExposure = null
                sourceDescriptionNode = null
            }
        }
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceDescriptionNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (contentDescription.isNullOrEmpty()) return
        // Source Image role remains generic under a merging row; its description is separate.
        info.className = "android.view.View"
        info.text = null
        info.isScreenReaderFocusable = false
        info.isFocusable = false
        info.isClickable = false
        info.isEnabled = true
        info.actionList.toList().forEach(info::removeAction)
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        descriptionAccessibility?.populate(info)
        sourceDescriptionNode?.invoke()?.populate(info)
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean =
        descriptionAccessibility?.performAction(action, args) ?: super.performAccessibilityAction(action, args)
    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!contentDescription.isNullOrEmpty()) {
            event.className = "android.view.View"
            event.isEnabled = true
            event.text.clear()
            event.contentDescription = null
        }
    }
}

/** The source OnboardingCard marker Surface:44dp,14dp corners, centered text or22dp icon. */
internal class OnboardingMarker(
    context: Context,
    style: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    icon: ReverbIcon? = null,
    marker: String? = null,
) {
    val view = ReverbGroupLayout(context, cornerRadiusDp = 14f)
    internal val text = if (icon == null) ReverbParagraphView(context, style, density, resolver, 0).apply {
        setText(marker.orEmpty())
    } else null
    private val glyph = icon?.let { OnboardingIconView(context, it, 0) }
    private val originalIcon = icon
    private val content = object : ViewGroup(context) {
        private val item: View = text ?: checkNotNull(glyph)
        init { clipChildren = false; clipToPadding = false; addView(item) }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val height = MeasureSpec.getSize(h)
            if (glyph != null) {
                val size = (22f * resources.displayMetrics.density).roundToInt()
                item.measure(exact(size.coerceAtMost(width)), exact(size.coerceAtMost(height)))
            } else {
                item.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            }
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val x = ((width - item.measuredWidth) / 2f).roundToInt()
            val y = ((height - item.measuredHeight) / 2f).roundToInt()
            item.layout(x, y, x + item.measuredWidth, y + item.measuredHeight)
        }
    }
    init {
        view.addRow(content, ReverbGroupLayout.LayoutParams(-1, -1))
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        text?.isScreenReaderFocusable = false
    }
    fun bind(palette: ReverbPalette) {
        view.setPalette(palette.primaryContainer, android.graphics.Color.TRANSPARENT)
        text?.setTextColor(palette.onPrimaryContainer)
        originalIcon?.let { glyph?.bind(it, palette.onPrimaryContainer) }
    }
}

/**
 * Native body of the one released OnboardingCard composable, shared by permissions and buffers.
 * Explicit placement retains Compose Center rounding and logical-start placement at odd pixel sizes.
 */
internal open class OnboardingCardView(
    context: Context,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    protected val marker: OnboardingMarker,
    protected val trailing: View,
    private val trailingWidthDp: Int,
    private val trailingHeightDp: Int,
    interactions: OnboardingNativeInteractionsFactory,
    accessibilityRole: NativeMergedRowRole = NativeMergedRowRole.BUTTON,
) : ViewGroup(context), NativeMergedRowAccessibilityHost, app.smallthingz.reverb.NativeClickPhaseHost {
    private val title = ReverbParagraphView(context, typography.titleMedium, density, resolver, 0)
    private val body = ReverbParagraphView(context, typography.bodyMedium, density, resolver, 0)
    private val input = interactions.row(this)
    override val nativeClickEndpoint get() = input.pointerEndpoint
    private var textHeight = 0
    private var bodyVisible = false
    private var canActivate = false
    var onActivate: (() -> Unit)? = null
    protected open val accessibilityClickLabel: CharSequence? get() = null
    protected open val accessibilityChecked: Boolean? get() = null
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(this, accessibilityRole,
        sourceState = {
            NativeMergedRowAccessibilityState(canActivate, accessibilityChecked, accessibilityClickLabel,
                buildList {
                    marker.text?.let(::add)
                    add(title)
                    if (bodyVisible) add(body)
                    if (!trailing.contentDescription.isNullOrEmpty()) add(trailing)
                })
        }, invokeSourceClick = {
            if (canActivate && isAttachedToWindow) { onActivate?.invoke(); true } else false
        })

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        title.isScreenReaderFocusable = false
        body.isScreenReaderFocusable = false
        listOf(marker.view, title, body, trailing).forEach(::addView)
        addView(mergedRowAccessibility.roleView)
        accessibilityDelegate = mergedRowAccessibility
        setOnClickListener { if (canActivate && isAttachedToWindow) onActivate?.invoke() }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)

    protected fun bindCard(titleText: String, bodyText: String?, enabled: Boolean, palette: ReverbPalette) {
        input.bind(enabled, palette.ink)
        canActivate = enabled
        isEnabled = enabled
        isFocusable = enabled
        title.setText(titleText)
        title.setTextColor(palette.ink)
        bodyVisible = !bodyText.isNullOrEmpty()
        body.visibility = if (bodyVisible) VISIBLE else GONE
        body.setText(bodyText.orEmpty())
        body.setTextColor(palette.muted)
        marker.bind(palette)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val padding = dp(16)
        val markerSize = dp(44)
        val trailWidth = dp(trailingWidthDp)
        val trailHeight = dp(trailingHeightDp)
        val textWidth = (width - padding * 2 - markerSize - trailWidth - dp(14) * 2).coerceAtLeast(0)
        marker.view.measure(exact(markerSize), exact(markerSize))
        trailing.measure(exact(trailWidth), exact(trailHeight))
        title.measure(atMost(textWidth), unspecified())
        if (bodyVisible) body.measure(atMost(textWidth), unspecified())
        textHeight = title.measuredHeight + if (bodyVisible) dp(2) + body.measuredHeight else 0
        val desired = padding * 2 + max(markerSize, max(trailHeight, textHeight))
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        mergedRowAccessibility.roleView.measure(exact(measuredWidth), exact(measuredHeight))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val padding = dp(16)
        placeStart(marker.view, padding, ((height - marker.view.measuredHeight) / 2f).roundToInt())
        val textX = padding + dp(44) + dp(14)
        val textY = ((height - textHeight) / 2f).roundToInt()
        placeStart(title, textX, textY)
        if (bodyVisible) placeStart(body, textX, textY + title.measuredHeight + dp(2))
        placeStart(trailing, width - padding - trailing.measuredWidth, ((height - trailing.measuredHeight) / 2f).roundToInt())
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
        input.measured(width, height)
    }
    private fun placeStart(child: View, start: Int, top: Int) {
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = input.touch(event)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = input.key(event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = input.key(event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = super.onHoverEvent(event)
        return input.hover(event) || handled
    }
    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        input.focusChanged(gain)
    }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); input.cancel() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); input.attached() }
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean = canActivate && isAttachedToWindow && super.performClick()
    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

internal class OnboardingBufferRow(
    context: Context,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    icon: ReverbIcon,
    initialPalette: ReverbPalette,
    interactions: OnboardingNativeInteractionsFactory,
) : OnboardingCardView(context, typography, density, resolver,
    OnboardingMarker(context, typography.titleMedium, density, resolver, icon = icon),
    ReverbMaterialSwitchView(context, initialPalette), 52, 32, interactions, NativeMergedRowRole.SWITCH) {
    private var checked = false
    override val accessibilityChecked: Boolean get() = checked
    init { trailing.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    fun bind(title: String, body: String, checked: Boolean, visuallyEnabled: Boolean,
             interactionEnabled: Boolean, palette: ReverbPalette) {
        this.checked = checked
        ViewCompat.setStateDescription(this, context.getString(
            if (checked) androidx.compose.ui.R.string.state_on else androidx.compose.ui.R.string.state_off))
        val toggle = trailing as ReverbMaterialSwitchView
        toggle.setPalette(palette)
        toggle.setState(checked, visuallyEnabled)
        bindCard(title, body, visuallyEnabled && interactionEnabled, palette)
    }
}

/** Source OnboardingAudioToggle:64dp minimum,14/8dp padding,12dp gap,bodyLarge and52×32dp Switch. */
internal class OnboardingAudioRow(
    context: Context,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    palette: ReverbPalette,
    interactions: OnboardingNativeInteractionsFactory,
) : ViewGroup(context), NativeMergedRowAccessibilityHost, app.smallthingz.reverb.NativeClickPhaseHost {
    private val title = ReverbParagraphView(context, typography.bodyLarge, density, resolver, 0)
    private val toggle = ReverbMaterialSwitchView(context, palette)
    private val input = interactions.row(this)
    override val nativeClickEndpoint get() = input.pointerEndpoint
    private var checked = false
    private var canActivate = false
    var onActivate: (() -> Unit)? = null
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(this, NativeMergedRowRole.SWITCH,
        sourceState = { NativeMergedRowAccessibilityState(canActivate, checked, null, listOf(title)) },
        invokeSourceClick = { if (canActivate && isAttachedToWindow) { onActivate?.invoke(); true } else false })
    init {
        clipChildren = false; clipToPadding = false
        isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        title.isScreenReaderFocusable = false
        toggle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(title); addView(toggle); addView(mergedRowAccessibility.roleView)
        accessibilityDelegate = mergedRowAccessibility
        setOnClickListener { if (canActivate && isAttachedToWindow) onActivate?.invoke() }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)

    fun bind(text: String, checked: Boolean, visuallyEnabled: Boolean, interactionEnabled: Boolean, palette: ReverbPalette) {
        this.checked = checked
        ViewCompat.setStateDescription(this, context.getString(
            if (checked) androidx.compose.ui.R.string.state_on else androidx.compose.ui.R.string.state_off))
        canActivate = visuallyEnabled && interactionEnabled
        input.bind(canActivate, palette.ink)
        isEnabled = canActivate
        isFocusable = canActivate
        title.setText(text)
        title.setTextColor(if (visuallyEnabled) palette.ink else palette.muted)
        toggle.setPalette(palette)
        toggle.setState(checked, visuallyEnabled)
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        toggle.measure(exact(dp(52)), exact(dp(32)))
        title.measure(atMost((width - dp(14) * 2 - dp(12) - toggle.measuredWidth).coerceAtLeast(0)), unspecified())
        val desired = max(dp(64), dp(8) * 2 + max(title.measuredHeight, toggle.measuredHeight))
        setMeasuredDimension(resolveSize(width, w), resolveSize(desired, h))
        mergedRowAccessibility.roleView.measure(exact(measuredWidth), exact(measuredHeight))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        fun place(child: View, start: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(title, dp(14))
        place(toggle, width - dp(14) - toggle.measuredWidth)
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
        input.measured(width, height)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = input.touch(event)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = input.key(event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = input.key(event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = super.onHoverEvent(event)
        return input.hover(event) || handled
    }
    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous); input.focusChanged(gain)
    }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); input.cancel() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); input.attached() }
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean = canActivate && isAttachedToWindow && super.performClick()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

internal fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
internal fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
internal fun unspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
