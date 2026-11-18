package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeControlRow
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.text.view.NativeMergedRowRole
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibility
import smallthingz.ui.text.view.PlainTextAccessibilitySnapshot
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import androidx.core.view.ViewCompat
import smallthingz.ui.animation.view.ViewColorTransition
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
    private var displayedColor=color
    private var publishedTint=color
    var useTextSurfaceContrast=false
        set(value) { if(field!=value){field=value;invalidate()} }
    private var colorTransition:ViewColorTransition?=null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
    fun bind(icon: ReverbIcon, color: Int) {
        if (currentIcon != icon) {
            currentIcon = icon
            glyph = ReverbIconDrawable(icon, resources.displayMetrics.density, color)
            publishedTint=color
            glyph.setBounds(0, 0, width, height)
        }
        glyph.layoutDirection = layoutDirection
        val motion=colorTransition ?: ViewColorTransition(this,intArrayOf(displayedColor),{applyTint()})
            .also {colorTransition=it}
        motion.retarget(color)
        applyTint()
        invalidate()
    }
    private fun publishTint() {
        val ink = if (!useTextSurfaceContrast || colorTransition == null) displayedColor else
            smallthingz.ui.controls.textSurfaceForeground(settingsTextSurface(this),displayedColor)
        if (publishedTint != ink) { publishedTint=ink;glyph.setTint(ink) }
    }
    private fun applyTint() {colorTransition?.let {displayedColor=it.color(0)};publishTint();invalidate()}
    fun setTintImmediately(color:Int) {
        colorTransition?.close();colorTransition=null;displayedColor=color;applyTint()
    }
    fun bindImmediately(icon:ReverbIcon,color:Int) {
        if(currentIcon!=icon) {
            currentIcon=icon;glyph=ReverbIconDrawable(icon,resources.displayMetrics.density,color);publishedTint=color
            glyph.setBounds(0,0,width,height)
        }
        glyph.layoutDirection=layoutDirection
        setTintImmediately(color)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { glyph.setBounds(0, 0, w, h) }
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        glyph.layoutDirection = layoutDirection
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        publishTint()
        glyph.draw(canvas)
    }

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
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
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
    val view = NativeGroupLayout(context, cornerRadiusDp = 14f, onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    internal val text = if (icon == null) ParagraphView(context, style, density, resolver, 0).apply {
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
        view.addRow(content, NativeGroupLayout.LayoutParams(-1, -1))
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        text?.isScreenReaderFocusable = false
    }
    fun bind(palette: ReverbPalette) {
        view.setPalette(palette.primaryContainer, android.graphics.Color.TRANSPARENT)
        text?.animateTextColor(palette.onPrimaryContainer)
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
) : NativeControlRow(context, accessibilityRole, interactions.inputRoot) {
    private val title = ParagraphView(context, typography.titleMedium, density, resolver, 0)
    private val body = ParagraphView(context, typography.bodyMedium, density, resolver, 0)
    private var textHeight = 0
    private var rowPadding = 0
    private var rowGap = 0
    private var stackedText = false
    private var stackedHeader = false
    private var headerHeight = 0
    private var bodyVisible = false
    var onActivate: (() -> Unit)? = null
    override val hasRowAction get() = onActivate != null
    override fun activateRow() { onActivate?.invoke() }
    override fun accessibilityChildren(): List<View> = buildList {
        marker.text?.let(::add)
        add(title)
        if (bodyVisible) add(body)
        if (!trailing.contentDescription.isNullOrEmpty()) add(trailing)
    }

    init {
        clipChildren = false
        clipToPadding = false
        title.isScreenReaderFocusable = false
        body.isScreenReaderFocusable = false
        listOf(marker.view, title, body, trailing).forEach(::addView)
        addRowAccessibilityRole()
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    protected fun bindCard(titleText: String, bodyText: String?, enabled: Boolean, palette: ReverbPalette,
        checked: Boolean? = null) {
        bindRow(enabled, palette.ink, checked)
        title.setText(titleText)
        title.animateTextColor(palette.ink)
        bodyVisible = !bodyText.isNullOrEmpty()
        body.visibility = if (bodyVisible) VISIBLE else GONE
        body.setText(bodyText.orEmpty())
        body.animateTextColor(palette.muted)
        marker.bind(palette)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        rowPadding = dp(16)
        rowGap = dp(14)
        val markerSize = dp(44)
        val trailWidth = dp(trailingWidthDp)
        val trailHeight = dp(trailingHeightDp)
        val titleWidth = title.maxIntrinsicTextWidth()
        var textWidth = (width - rowPadding * 2 - markerSize - trailWidth - rowGap * 2).coerceAtLeast(0)
        // Keep released geometry while it fits; narrow or enlarged text gets space, not a smaller font.
        if (titleWidth > textWidth) {
            rowPadding = dp(12)
            rowGap = dp(8)
            textWidth = (width - rowPadding * 2 - markerSize - trailWidth - rowGap * 2).coerceAtLeast(0)
        }
        stackedText = titleWidth > textWidth
        stackedHeader = stackedText && width - rowPadding * 2 < markerSize + trailWidth + rowGap
        headerHeight = if (stackedHeader) markerSize + rowGap + trailHeight else max(markerSize, trailHeight)
        if (stackedText) textWidth = (width - rowPadding * 2).coerceAtLeast(0)
        marker.view.measure(exact(markerSize), exact(markerSize))
        trailing.measure(exact(trailWidth), exact(trailHeight))
        title.measure(atMost(textWidth), unspecified())
        if (bodyVisible) body.measure(atMost(textWidth), unspecified())
        textHeight = title.measuredHeight + if (bodyVisible) dp(2) + body.measuredHeight else 0
        val desired = rowPadding * 2 + if (stackedText) headerHeight + rowGap + textHeight
            else max(headerHeight, textHeight)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        measureRowRole(measuredWidth, measuredHeight)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val markerY = if (stackedText) {
            rowPadding + if (stackedHeader) 0 else ((headerHeight - marker.view.measuredHeight) / 2f).roundToInt()
        } else ((height - marker.view.measuredHeight) / 2f).roundToInt()
        val trailingY = if (stackedText) {
            rowPadding + if (stackedHeader) marker.view.measuredHeight + rowGap
                else ((headerHeight - trailing.measuredHeight) / 2f).roundToInt()
        } else ((height - trailing.measuredHeight) / 2f).roundToInt()
        placeStart(marker.view, rowPadding, markerY)
        val textX = if (stackedText) rowPadding else rowPadding + marker.view.measuredWidth + rowGap
        val textY = if (stackedText) rowPadding + headerHeight + rowGap else ((height - textHeight) / 2f).roundToInt()
        placeStart(title, textX, textY)
        if (bodyVisible) placeStart(body, textX, textY + title.measuredHeight + dp(2))
        placeStart(trailing, width - rowPadding - trailing.measuredWidth, trailingY)
        layoutRowRole()
    }
    private fun placeStart(child: View, start: Int, top: Int) {
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }
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
    NativeMaterialSwitchView(context, initialPalette.switchPalette()), 52, 32, interactions, NativeMergedRowRole.SWITCH) {
    fun bind(title: String, body: String, checked: Boolean, visuallyEnabled: Boolean,
             interactionEnabled: Boolean, palette: ReverbPalette) {
        ViewCompat.setStateDescription(this, context.getString(
            if (checked) smallthingz.ui.R.string.state_on else smallthingz.ui.R.string.state_off))
        val toggle = trailing as NativeMaterialSwitchView
        toggle.setPalette(palette.switchPalette())
        toggle.setState(checked, visuallyEnabled)
        bindCard(title, body, visuallyEnabled && interactionEnabled, palette, checked)
    }
}

internal fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
internal fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
internal fun unspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
