package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ScrollView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.smallthingz.reverb.R
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class AboutContentAppearance(
    val palette: ReverbPalette,
    val headlineSmall: TextStyle,
    val titleSmall: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val density: Density,
    val fontResolver: FontFamily.Resolver,
)

/**
 * Concrete released About content only. The native dialog host must keep Gravity.TOP,
 * full-width/non-fitting decor, source entry/exit, and predictive Back ownership.
 *
 * [predictiveMotionTarget] excludes navigation-bar padding, matching the source graphicsLayer.
 * Entry/exit applies to this whole View, including navigation padding. requestDismiss is the
 * owner's idempotent exit request; this content never dismisses the Window prematurely.
 */
internal class ReverbAboutContentView(
    context: Context,
    initialAppearance: AboutContentAppearance,
    private val versionName: String,
    requestDismiss: () -> Unit,
) : ViewGroup(context) {
    private var appearance = initialAppearance
    private var navigation = Insets.NONE
    private var status = Insets.NONE
    private var linkError: String? = null
    private val surface = AboutSurface(context, initialAppearance)
    private val content = AboutColumn(context, initialAppearance, versionName, requestDismiss, ::openRepository)
    val predictiveMotionTarget: View get() = surface

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        surface.setContent(content)
        addView(surface)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nextNavigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val rawStatus = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nextStatus = Insets.of(
                max(0, rawStatus.left - nextNavigation.left),
                max(0, rawStatus.top - nextNavigation.top),
                max(0, rawStatus.right - nextNavigation.right),
                max(0, rawStatus.bottom - nextNavigation.bottom),
            )
            if (navigation != nextNavigation || status != nextStatus) {
                navigation = nextNavigation
                status = nextStatus
                surface.setStatusInsets(status)
                requestLayout()
            }
            insets
        }
    }

    fun setAppearance(value: AboutContentAppearance, requestDismiss: () -> Unit) {
        appearance = value
        surface.setAppearance(value)
        content.setAppearance(value, versionName, requestDismiss)
        content.setLinkError(linkError)
        requestLayout()
    }

    private fun openRepository() {
        linkError = try {
            context.startActivity(Intent(Intent.ACTION_VIEW, ABOUT_REPOSITORY_URL.toUri()))
            null
        } catch (_: RuntimeException) {
            context.resources.getString(R.string.no_app_available)
        }
        content.setLinkError(linkError)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = max(0, width - navigation.left - navigation.right)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val availableHeight = if (heightMode == MeasureSpec.UNSPECIFIED) 0
            else max(0, MeasureSpec.getSize(heightMeasureSpec) - navigation.top - navigation.bottom)
        surface.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(availableHeight,
                if (heightMode == MeasureSpec.UNSPECIFIED) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST))
        setMeasuredDimension(width, resolveSize(surface.measuredHeight + navigation.top + navigation.bottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        surface.layout(navigation.left, navigation.top,
            navigation.left + surface.measuredWidth, navigation.top + surface.measuredHeight)
    }
}

/** Same once-per-About-entry PackageManager read as remember{} in the released composable. */
internal fun createReverbAboutContent(
    context: Context,
    appearance: AboutContentAppearance,
    requestDismiss: () -> Unit,
): ReverbAboutContentView {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return ReverbAboutContentView(context, appearance, info.versionName.orEmpty(), requestDismiss)
}

private const val ABOUT_REPOSITORY_URL = "https://github.com/SmallThingz/reverb"
private const val ABOUT_REPOSITORY_LABEL = "SmallThingz/reverb"

private class AboutSurface(context: Context, private var appearance: AboutContentAppearance) : ViewGroup(context) {
    private val fill = Paint(ABOUT_PAINT_FLAGS)
    private val path = Path()
    private val bounds = RectF()
    private val radii = FloatArray(8)
    private val clipped = AboutSurfaceContentClip(context)
    private val noise = ReverbNoiseDrawable(resources)
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = false
        background = noise
        clipToPadding = false
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipped.addView(scroll, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(clipped)
        updateColor()
    }
    fun setContent(view: View) { scroll.addView(view, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    fun setStatusInsets(value: Insets) { clipped.setPadding(value.left, value.top, value.right, value.bottom); requestLayout() }
    fun setAppearance(value: AboutContentAppearance) { appearance = value; updateColor(); rebuildShape(); requestLayout() }
    private fun updateColor() { fill.color = aboutTonalColor(appearance.palette.surfaceContainerHigh, appearance.palette, 6f); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        clipped.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(clipped.measuredWidth, clipped.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { clipped.layout(0, 0, width, height) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rebuildShape() }

    private fun rebuildShape() {
        // CornerBasedShape rescales opposing radii if their sum exceeds a side.
        val radius = min(32f * appearance.density.density, min(width / 2f, height.toFloat()))
        radii.fill(0f)
        for (index in 4..7) radii[index] = radius
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        path.rewind()
        path.addRoundRect(bounds, radii, Path.Direction.CW)
        clipped.setShape(path)
        invalidate()
    }
    // Surface's shape fill is outside its child clipping layer; preserve edge coverage once.
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); canvas.drawPath(path, fill) }
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}

private class AboutSurfaceContentClip(context: Context) : android.widget.FrameLayout(context) {
    private val shape = Path()
    private var nativeClip = false
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { fillOutline(outline) }
        }
    }
    fun setShape(path: Path) {
        shape.set(path)
        val outline = Outline()
        fillOutline(outline)
        nativeClip = outline.canClip()
        clipToOutline = nativeClip
        invalidateOutline()
        invalidate()
    }
    @Suppress("DEPRECATION")
    private fun fillOutline(outline: Outline) {
        if (Build.VERSION.SDK_INT >= 30) outline.setPath(shape) else outline.setConvexPath(shape)
    }
    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated && nativeClip) super.dispatchDraw(canvas) else {
            val checkpoint = canvas.save()
            canvas.clipPath(shape)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }
}

private class AboutColumn(
    context: Context,
    private var appearance: AboutContentAppearance,
    versionName: String,
    requestDismiss: () -> Unit,
    openRepository: () -> Unit,
) : ViewGroup(context) {
    private val header = AboutBrandHeader(context, appearance, requestDismiss)
    private val name = aboutParagraph(context, appearance, appearance.headlineSmall, appearance.palette.ink)
    private val version = aboutParagraph(context, appearance, appearance.bodyMedium, appearance.palette.muted)
    private val repository = AboutRepositoryCard(context, appearance, openRepository)
    private val error = AboutErrorCard(context, appearance).apply { visibility = GONE }
    private var contentHeight = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(header); addView(name); addView(version); addView(repository); addView(error)
        updateLabels(versionName)
    }

    private fun updateLabels(versionName: String) {
        name.setText(context.resources.getString(R.string.app_name))
        version.setText(context.resources.getString(R.string.about_version, versionName))
    }

    fun setAppearance(value: AboutContentAppearance, versionName: String, requestDismiss: () -> Unit) {
        appearance = value
        header.bind(value, requestDismiss)
        name.setTypography(value.headlineSmall, value.density, value.fontResolver)
        name.setTextColor(value.palette.ink)
        version.setTypography(value.bodyMedium, value.density, value.fontResolver)
        version.setTextColor(value.palette.muted)
        repository.setAppearance(value)
        error.setAppearance(value)
        updateLabels(versionName)
        requestLayout()
    }

    fun setLinkError(message: String?) {
        error.visibility = if (message == null) GONE else VISIBLE
        message?.let(error::setText)
        requestLayout()
    }

    private fun dp(value: Float) = (value * appearance.density.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val childWidth = max(0, width - dp(24f) - dp(24f))
        val exactWidth = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val wrapWidth = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.AT_MOST)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        header.measure(exactWidth, unbounded)
        name.measure(wrapWidth, unbounded)
        version.measure(wrapWidth, unbounded)
        repository.measure(exactWidth, unbounded)
        contentHeight = dp(12f) + header.measuredHeight + dp(14f) + name.measuredHeight +
            dp(2f) + version.measuredHeight + dp(18f) + repository.measuredHeight + dp(26f)
        if (error.visibility != GONE) { error.measure(exactWidth, unbounded); contentHeight += dp(12f) + error.measuredHeight }
        setMeasuredDimension(width, contentHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = dp(12f)
        fun place(child: View) {
            val x = ((width - child.measuredWidth) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight
        }
        place(header); y += dp(14f)
        place(name); y += dp(2f)
        place(version); y += dp(18f)
        place(repository)
        if (error.visibility != GONE) { y += dp(12f); place(error) }
    }
}

private class AboutBrandHeader(context: Context, private var appearance: AboutContentAppearance, requestDismiss: () -> Unit) : ViewGroup(context) {
    private val mark = ReverbBrandMarkView(context, context.resources.getString(R.string.app_name))
    private val close = AboutCloseButton(context, appearance, requestDismiss)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(close); addView(mark) }
    fun bind(value: AboutContentAppearance, requestDismiss: () -> Unit) {
        appearance = value; close.bind(value, requestDismiss); mark.refreshResourcePalette()
        mark.contentDescription = context.resources.getString(R.string.app_name)
        requestLayout()
    }
    private fun dp(value: Float) = (value * appearance.density.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        close.measure(MeasureSpec.makeMeasureSpec(min(width, dp(48f)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(48f), MeasureSpec.EXACTLY))
        mark.measure(MeasureSpec.makeMeasureSpec(min(width, dp(104f)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(104f), MeasureSpec.EXACTLY))
        setMeasuredDimension(width, max(close.measuredHeight, mark.measuredHeight))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val closeX = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else width - close.measuredWidth
        close.layout(closeX, 0, closeX + close.measuredWidth, close.measuredHeight)
        val markX = ((width - mark.measuredWidth) / 2f).roundToInt()
        mark.layout(markX, 0, markX + mark.measuredWidth, mark.measuredHeight)
    }
}

private class AboutCloseButton(context: Context, private var appearance: AboutContentAppearance, private var action: () -> Unit) : ViewGroup(context) {
    private val key = Any()
    private val visual = AboutCloseVisual(context, appearance.palette.muted)
    private val indication = ReverbRowIndication.install(visual, appearance.palette.ink, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val minimumTouchWidthPx get() = 48f * appearance.density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent): Boolean {
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) return event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height
            val x = event.x - visual.left - visual.width / 2f
            val y = event.y - visual.top - visual.height / 2f
            val radius = min(visual.width, visual.height) / 2f
            return x * x + y * y <= radius * radius
        }
        override fun interestedParent(event: MotionEvent) = true
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, output -> output.set(x - visual.left, y - visual.top) })

    init {
        isClickable = true; isFocusable = true
        contentDescription = context.resources.getString(R.string.close)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(visual)
        setOnClickListener { action() }
    }
    fun bind(value: AboutContentAppearance, action: () -> Unit) {
        appearance = value; this.action = action; visual.setColor(value.palette.muted)
        indication.updateColor(value.palette.ink)
        contentDescription = context.resources.getString(R.string.close)
        bindInput(); requestLayout()
    }
    private fun bindInput() = controller.bind(NativeClickBinding(key, true, { performClick() }))
    override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.attached(); bindInput() }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = (40f * appearance.density.density).roundToInt()
        visual.measure(MeasureSpec.makeMeasureSpec(min(w, size), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(min(h, size), MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - visual.measuredWidth) / 2f).roundToInt()
        val y = ((height - visual.measuredHeight) / 2f).roundToInt()
        visual.layout(x, y, x + visual.measuredWidth, y + visual.measuredHeight)
        indication.onMeasured(visual.width, visual.height, appearance.density.density)
    }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun dispatchKeyEvent(event: KeyEvent) = controller.key(event) || super.dispatchKeyEvent(event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = controller.hover(event)
        return super.onHoverEvent(event) || handled
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); controller.focusChanged(gainFocus)
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

private class AboutCloseVisual(context: Context, color: Int) : View(context) {
    private val icon = ReverbIconDrawable(ReverbIcon.Close, resources.displayMetrics.density, color)
    private val clip = Path()
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, width, height, min(width, height) / 2f) }
        }
    }
    fun setColor(value: Int) { icon.setTint(value); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = (24f * resources.displayMetrics.density).roundToInt()
        val x = ((w - size) / 2f).roundToInt(); val y = ((h - size) / 2f).roundToInt()
        icon.setBounds(x, y, x + size, y + size)
        clip.rewind(); clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), min(w, h) / 2f, min(w, h) / 2f, Path.Direction.CW)
        invalidateOutline()
    }
    override fun onDraw(canvas: Canvas) { icon.draw(canvas) }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas) else {
            val checkpoint = canvas.save(); canvas.clipPath(clip); super.draw(canvas); canvas.restoreToCount(checkpoint)
        }
    }
}

private class AboutRepositoryCard(context: Context, appearance: AboutContentAppearance, onClick: () -> Unit) : ViewGroup(context) {
    private val group = ReverbGroupLayout(context, 20f)
    private val row = AboutRepositoryRow(context, appearance, onClick)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        group.addRow(row, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(group); setAppearance(appearance)
    }
    fun setAppearance(value: AboutContentAppearance) {
        group.setPalette(aboutTonalColor(value.palette.surfaceContainerHighest, value.palette, 6f), 0)
        row.setAppearance(value)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        group.measure(widthMeasureSpec, heightMeasureSpec); setMeasuredDimension(group.measuredWidth, group.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { group.layout(0, 0, width, height) }
}

private class AboutRepositoryRow(context: Context, private var appearance: AboutContentAppearance, onClick: () -> Unit) : ViewGroup(context) {
    private val key = Any()
    private val icon = AboutGithubGlyph(context, appearance.palette.ink)
    private val title = aboutParagraph(context, appearance, appearance.titleSmall, appearance.palette.ink)
    private val label = aboutParagraph(context, appearance, appearance.bodySmall, appearance.palette.muted)
    private val indication = ReverbRowIndication.install(this, appearance.palette.ink, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val minimumTouchWidthPx get() = 48f * appearance.density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = reverbGroupGeometry(width.toFloat(), height.toFloat(), appearance.density.density, 20f)
            .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = true
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, output -> output.set(x, y) })
    init {
        isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        addView(icon); addView(title); addView(label)
        title.setText(context.resources.getString(R.string.github_repo)); label.setText(ABOUT_REPOSITORY_LABEL)
        setOnClickListener { onClick() }
    }
    fun setAppearance(value: AboutContentAppearance) {
        appearance = value
        icon.setColor(value.palette.ink)
        title.setTypography(value.titleSmall, value.density, value.fontResolver); title.setTextColor(value.palette.ink)
        label.setTypography(value.bodySmall, value.density, value.fontResolver); label.setTextColor(value.palette.muted)
        title.setText(context.resources.getString(R.string.github_repo))
        indication.updateColor(value.palette.ink); requestLayout()
    }
    private fun dp(value: Float) = (value * appearance.density.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val inner = max(0, w - dp(18f) - dp(18f))
        val glyph = min(dp(24f), inner)
        icon.measure(MeasureSpec.makeMeasureSpec(glyph, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(24f), MeasureSpec.EXACTLY))
        val textWidth = max(0, inner - icon.measuredWidth - dp(14f))
        val textSpec = MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.AT_MOST)
        title.measure(textSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        label.measure(textSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(w, max(dp(48f), max(icon.measuredHeight, title.measuredHeight + label.measuredHeight) + dp(15f) + dp(15f)))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val iconX = if (rtl) width - dp(18f) - icon.measuredWidth else dp(18f)
        val iconY = ((height - icon.measuredHeight) / 2f).roundToInt()
        icon.layout(iconX, iconY, iconX + icon.measuredWidth, iconY + icon.measuredHeight)
        val textStart = if (rtl) icon.left - dp(14f) else icon.right + dp(14f)
        val titleX = if (rtl) textStart - title.measuredWidth else textStart
        val labelX = if (rtl) textStart - label.measuredWidth else textStart
        val textY = ((height - title.measuredHeight - label.measuredHeight) / 2f).roundToInt()
        title.layout(titleX, textY, titleX + title.measuredWidth, textY + title.measuredHeight)
        label.layout(labelX, title.bottom, labelX + label.measuredWidth, title.bottom + label.measuredHeight)
        indication.onMeasured(width, height, appearance.density.density)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); controller.attached(); controller.bind(NativeClickBinding(key, true, { performClick() }))
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun dispatchKeyEvent(event: KeyEvent) = controller.key(event) || super.dispatchKeyEvent(event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = controller.hover(event)
        return super.onHoverEvent(event) || handled
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); controller.focusChanged(gainFocus)
    }
}

private class AboutGithubGlyph(context: Context, color: Int) : View(context) {
    // Exact released ic_github.xml path, using the shared mask renderer.
    private val icon = ReverbIconDrawable(ReverbIcon.GitHub, resources.displayMetrics.density, color)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setColor(value: Int) { icon.setTint(value); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { icon.setBounds(0, 0, w, h) }
    override fun onDraw(canvas: Canvas) { icon.draw(canvas) }
}

private class AboutErrorCard(context: Context, private var appearance: AboutContentAppearance) : ViewGroup(context) {
    private val group = ReverbGroupLayout(context, 18f)
    private val text = aboutParagraph(context, appearance, appearance.bodyMedium, appearance.palette.onErrorContainer).apply {
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val body = object : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val contentWidth = max(0, w - dp(16f) - dp(8f))
            text.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(w, text.measuredHeight + dp(8f) + dp(8f))
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) dp(8f) else dp(16f)
            text.layout(x, dp(8f), x + text.measuredWidth, dp(8f) + text.measuredHeight)
        }
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        body.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        body.addView(text)
        group.addRow(body, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(group); setAppearance(appearance)
    }
    private fun dp(value: Float) = (value * appearance.density.density).roundToInt()
    fun setText(value: String) = text.setText(value)
    fun setAppearance(value: AboutContentAppearance) {
        appearance = value
        group.setPalette(aboutTonalColor(value.palette.errorContainer, value.palette, 8f), 0)
        text.setTypography(value.bodyMedium, value.density, value.fontResolver)
        text.setTextColor(value.palette.onErrorContainer); requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        group.measure(widthMeasureSpec, heightMeasureSpec); setMeasuredDimension(group.measuredWidth, group.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { group.layout(0, 0, width, height) }
}

private const val ABOUT_PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
private fun aboutParagraph(context: Context, appearance: AboutContentAppearance, style: TextStyle, color: Int) =
    ReverbParagraphView(context, style, appearance.density, appearance.fontResolver, color)

private fun aboutTonalColor(color: Int, palette: ReverbPalette, elevationDp: Float): Int {
    if (color != palette.surface || elevationDp == 0f) return color
    val alpha = ((4.5f * ln(elevationDp + 1f)) + 2f) / 100f
    return Color(palette.surfaceTint).copy(alpha = alpha).compositeOver(Color(palette.surface)).toArgb()
}
