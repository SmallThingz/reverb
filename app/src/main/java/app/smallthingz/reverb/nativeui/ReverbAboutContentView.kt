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
import android.view.ViewConfiguration
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Size
import app.smallthingz.nativeui.geometry.Rect as ComposeRect
import app.smallthingz.reverb.NativePagerTouchRoot
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import app.smallthingz.reverb.NativePointerCoordinates
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import app.smallthingz.reverb.NativeVerticalPhaseBinding
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.nativeui.graphics.Color
import app.smallthingz.nativeui.graphics.compositeOver
import app.smallthingz.nativeui.graphics.toArgb
import app.smallthingz.nativeui.text.TextStyle
import app.smallthingz.nativeui.text.font.FontFamily
import app.smallthingz.nativeui.unit.Density
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
internal class ReverbAboutContentView private constructor(
    context: Context,
    initialAppearance: AboutContentAppearance,
    private val versionName: String,
    requestDismiss: () -> Unit,
    private val runtime: NativeAboutWindowRuntime,
    initialScrollPosition: Int,
    private val input: AboutInputGeometry,
) : NativePagerTouchRoot(context, input::rootToScreen, input::coordinates, input.rootHit, input::prepare) {
    constructor(context: Context, initialAppearance: AboutContentAppearance, versionName: String,
                requestDismiss: () -> Unit, runtime: NativeAboutWindowRuntime, initialScrollPosition: Int = 0) :
        this(context, initialAppearance, versionName, requestDismiss, runtime, initialScrollPosition,
            AboutInputGeometry(initialAppearance.density.density))
    private var appearance = initialAppearance
    private var navigation = Insets.NONE
    private var status = Insets.NONE
    private var linkError: String? = null
    private val surface = AboutSurface(context, initialAppearance, runtime, initialScrollPosition)
    private val content = AboutColumn(context, initialAppearance, versionName, requestDismiss, ::openRepository)
    val predictiveMotionTarget: View get() = surface
    private var accessibilityOwner: AboutContentAccessibilityOwner? = null

    fun scrollPosition(): Int = surface.viewport.saveScrollPosition()

    fun bindWindowAccessibility(windowRoot: View) {
        check(accessibilityOwner == null) { "About content already has its native Window cursor" }
        content.header.close.nativeInputRoot = windowRoot
        content.repository.row.nativeInputRoot = windowRoot
        accessibilityOwner = AboutContentAccessibilityOwner(windowRoot, this, surface,
            surface.clipHost, surface.viewport, content)
    }

    fun dispose() {
        input.close()
        accessibilityOwner?.close()
        accessibilityOwner = null
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        surface.setContent(content)
        addView(surface)
        input.bind(this, surface, content)
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
    runtime: NativeAboutWindowRuntime,
    initialScrollPosition: Int = 0,
): ReverbAboutContentView {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return ReverbAboutContentView(context, appearance, info.versionName.orEmpty(), requestDismiss, runtime, initialScrollPosition)
}

private const val ABOUT_REPOSITORY_URL = "https://github.com/SmallThingz/reverb"
private const val ABOUT_REPOSITORY_LABEL = "SmallThingz/reverb"

private class AboutSurface(context: Context, private var appearance: AboutContentAppearance, runtime: NativeAboutWindowRuntime, initialScrollPosition: Int) : ViewGroup(context) {
    private val fill = Paint(ABOUT_PAINT_FLAGS)
    private val path = Path()
    private val bounds = RectF()
    private val radii = FloatArray(8)
    private val clipped = AboutSurfaceContentClip(context)
    private val noise = ReverbNoiseDrawable(resources)
    val viewport = NativeVerticalViewport(context, NativeVerticalScrollState(initialScrollPosition), appearance.density,
        runtime.frames, runtime.scope, object : NativeVerticalNestedHost {
            // This dialog's scroll container has no scrollable ancestor; window motion is not nested scroll.
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override suspend fun preFling(availableY: Float) = 0f
            override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
        }).apply { background = noise }
    val clipHost: View get() = clipped
    fun containsShape(point: Offset): Boolean {
        val x = point.x; val y = point.y
        if (x < 0f || y < 0f || x >= width || y >= height) return false
        val radius = min(32f * appearance.density.density, min(width / 2f, height.toFloat()))
        if (y <= height - radius || x in radius..(width - radius)) return true
        val centerX = if (x < radius) radius else width - radius
        val dx = x - centerX; val dy = y - (height - radius)
        return dx * dx + dy * dy <= radius * radius
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipped.addView(viewport, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(clipped)
        updateColor()
    }
    fun setContent(view: View) { viewport.setContent(view) }
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
    val header = AboutBrandHeader(context, appearance, requestDismiss)
    val name = aboutParagraph(context, appearance, appearance.headlineSmall, appearance.palette.ink)
    val version = aboutParagraph(context, appearance, appearance.bodyMedium, appearance.palette.muted)
    val repository = AboutRepositoryCard(context, appearance, openRepository)
    val error = AboutErrorCard(context, appearance).apply { visibility = GONE }
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
    val mark = ReverbBrandMarkView(context, context.resources.getString(R.string.app_name))
    val close = AboutCloseButton(context, appearance, requestDismiss)
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
    var nativeInputRoot: View? = null
    private val key = Any()
    val visual = AboutCloseVisual(context, appearance.palette.muted)
    private val indication = ReverbRowIndication.install(visual, appearance.palette.ink, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(visual, object : NativeClickEnvironment {
        override val nativeInputRoot: View? get() = this@AboutCloseButton.nativeInputRoot
        override val minimumTouchWidthPx get() = 48f * appearance.density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent): Boolean {
            val w = visual.width.toFloat(); val h = visual.height.toFloat()
            val x = event.x; val y = event.y
            val radius = min(w, h) / 2f
            val dx = x - w / 2f; val dy = y - h / 2f
            if (dx * dx + dy * dy <= radius * radius) return true
            if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
            val extentX = max(0f, minimumTouchWidthPx - w) / 2f
            val extentY = max(0f, minimumTouchHeightPx - h) / 2f
            return x >= -extentX && x <= w + extentX && y >= -extentY && y <= h + extentY
        }
        override fun interestedParent(event: MotionEvent) = false // Shared touch phases supply actual vertical interest.
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, output -> output.set(x, y) })

    val clickEndpoint = ReverbClickPhaseEndpoint(controller)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visual.owner = this
        visual.glyph.contentDescription = context.resources.getString(R.string.close)
        visual.setOnClickListener { action() }
        addView(visual)
    }
    val minimumTouchTargetPx get() = 48f * appearance.density.density
    fun invokeSourceClick(): Boolean { action(); return true }
    fun visualTouch(event: MotionEvent) = controller.touch(event)
    fun visualKey(event: KeyEvent) = controller.key(event)
    fun visualHover(event: MotionEvent) = controller.hover(event)
    fun visualFocus(value: Boolean) = controller.focusChanged(value)
    fun bind(value: AboutContentAppearance, action: () -> Unit) {
        appearance = value; this.action = action; visual.setColor(value.palette.muted)
        indication.updateColor(value.palette.ink)
        visual.glyph.contentDescription = context.resources.getString(R.string.close)
        bindInput(); requestLayout()
    }
    private fun bindInput() = controller.bind(NativeClickBinding(key, true, { visual.performClick() }))
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
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val local = MotionEvent.obtain(event)
        return try { local.offsetLocation(-visual.left.toFloat(), -visual.top.toFloat()); controller.touch(local) }
        finally { local.recycle() }
    }

}

private class AboutCloseVisual(context: Context, color: Int) : ViewGroup(context) {
    lateinit var owner: AboutCloseButton
    val glyph = AboutCloseGlyph(context, color)
    private val clip = Path()
    var semanticRole: View? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true; isFocusable = true
        addView(glyph)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, min(width, height) / 2f)
            }
        }
    }
    fun setColor(value: Int) = glyph.setColor(value)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = (24f * resources.displayMetrics.density).roundToInt()
        glyph.measure(MeasureSpec.makeMeasureSpec(min(w, size), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(min(h, size), MeasureSpec.EXACTLY))
        semanticRole?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = ((width - glyph.measuredWidth) / 2f).roundToInt()
        val y = ((height - glyph.measuredHeight) / 2f).roundToInt()
        glyph.layout(x, y, x + glyph.measuredWidth, y + glyph.measuredHeight)
        semanticRole?.layout(0, 0, width, height)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.rewind(); clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), min(w,h)/2f, min(w,h)/2f, Path.Direction.CW)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas) else {
            val checkpoint = canvas.save()
            try { canvas.clipPath(clip); super.draw(canvas) } finally { canvas.restoreToCount(checkpoint) }
        }
    }
    override fun onTouchEvent(event: MotionEvent) = owner.visualTouch(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> owner.visualKey(event)
        else -> super.dispatchKeyEvent(event)
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val native = super.onHoverEvent(event)
        return owner.visualHover(event) || native
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gainFocus, direction, previous); owner.visualFocus(gainFocus)
    }
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

private class AboutCloseGlyph(context: Context, color: Int) : View(context) {
    private val icon = ReverbIconDrawable(ReverbIcon.Close, resources.displayMetrics.density, color)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isScreenReaderFocusable = false }
    fun setColor(value: Int) { icon.setTint(value); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { icon.setBounds(0, 0, w, h) }
    override fun onDraw(canvas: Canvas) = icon.draw(canvas)
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

private class AboutRepositoryCard(context: Context, appearance: AboutContentAppearance, onClick: () -> Unit) : ViewGroup(context) {
    private val group = ReverbGroupLayout(context, 20f)
    val row = AboutRepositoryRow(context, appearance, onClick)
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
    var nativeInputRoot: View? = null
    private val key = Any()
    private val sourceClick = onClick
    fun invokeSourceClick(): Boolean { sourceClick(); return true }
    private val icon = AboutGithubGlyph(context, appearance.palette.ink)
    val title = aboutParagraph(context, appearance, appearance.titleSmall,
        reverbAboutRepositoryTitleColor(appearance.palette, appearance.titleSmall))
    val label = aboutParagraph(context, appearance, appearance.bodySmall, appearance.palette.muted)
    private val indication = ReverbRowIndication.install(this,
        reverbAboutRepositoryContentColor(appearance.palette), ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View? get() = this@AboutRepositoryRow.nativeInputRoot
        override val minimumTouchWidthPx get() = 48f * appearance.density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = reverbGroupGeometry(width.toFloat(), height.toFloat(), appearance.density.density, 20f)
            .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = false // Shared touch phases supply actual vertical interest.
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, output -> output.set(x, y) })
    val clickEndpoint = ReverbClickPhaseEndpoint(controller)
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
        title.setTypography(value.titleSmall, value.density, value.fontResolver)
        title.setTextColor(reverbAboutRepositoryTitleColor(value.palette, value.titleSmall))
        label.setTypography(value.bodySmall, value.density, value.fontResolver); label.setTextColor(value.palette.muted)
        title.setText(context.resources.getString(R.string.github_repo))
        indication.updateColor(reverbAboutRepositoryContentColor(value.palette)); requestLayout()
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
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); controller.attached(); controller.bind(NativeClickBinding(key, true, { performClick() }))
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> controller.key(event)
        else -> super.dispatchKeyEvent(event)
    }
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

/** A direct geometry owner for existing Views, not an independently measured/rendered layout tree. */
private class AboutInputGeometry(private val density: Float) : AutoCloseable {
    private lateinit var root: ReverbAboutContentView
    private val coordinates = NativeWindowPointerCoordinates { root }
    private var binding: NativeVerticalPhaseBinding? = null
    private var surfaces: List<NativeViewPointerHitLayout> = emptyList()
    val rootHit = object : NativeViewPointerHitLayout {
        override val placed get() = ::root.isInitialized && root.isAttachedToWindow && root.isLaidOut
        override val size get() = Size(root.width.toFloat(), root.height.toFloat())
        override val minimumTouchTarget = Size.Zero
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder get() = surfaces
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(root).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }

    fun bind(root: ReverbAboutContentView, surface: AboutSurface, content: AboutColumn) {
        check(!::root.isInitialized)
        this.root = root
        val viewConfiguration = ViewConfiguration.get(root.context)
        val vertical = NativeVerticalPhaseBinding(surface.viewport,
            { viewConfiguration.scaledTouchSlop.toFloat() },
            { viewConfiguration.scaledMaximumFlingVelocity.toFloat() }, { false })
        binding = vertical
        val close = content.header.close
        val repository = content.repository.row
        val closeNode = NativeClickPhaseNode(close.visual, close.clickEndpoint, vertical::interested)
        val repositoryNode = NativeClickPhaseNode(repository, repository.clickEndpoint, vertical::interested)
        val closeHit = Hit(close.visual, listOf(closeNode), emptyList()) { p ->
            val radius = min(close.visual.width, close.visual.height) / 2f
            val dx = p.x - close.visual.width / 2f; val dy = p.y - close.visual.height / 2f
            dx * dx + dy * dy <= radius * radius
        }
        val repositoryHit = Hit(repository, listOf(repositoryNode), emptyList()) { p ->
            reverbGroupGeometry(repository.width.toFloat(), repository.height.toFloat(), density, 20f)
                .contains(p.x, p.y)
        }
        val viewport = surface.viewport
        val inflation = (30f * density).roundToInt()
        val viewportHit = Hit(viewport, listOf(vertical.observerNode, vertical.dragNode),
            listOf(repositoryHit, closeHit)) { p ->
            p.x >= -inflation && p.x < viewport.width + inflation && p.y >= 0f && p.y < viewport.height
        }
        // Surface has an empty pointer-input node that blocks siblings behind it without consuming.
        val shield = object : NativeViewPhasePointerNode {
            override val view: View get() = surface
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() = Unit
        }
        surfaces = listOf(Hit(surface, listOf(shield), listOf(viewportHit), surface::containsShape))
    }

    private inner class Hit(
        private val view: View,
        override val nodes: List<NativeViewPhasePointerNode>,
        override val childrenInHitOrder: List<NativeViewPointerHitLayout>,
        private val clip: (Offset) -> Boolean,
    ) : NativeViewPointerHitLayout {
        override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density, 48f * density)
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset) = clip(local)
    }

    fun prepare(event: MotionEvent) = coordinates.begin(event)
    fun rootToScreen(point: Offset) = coordinates.rootToScreen(point)
    fun coordinates(view: View): NativePointerCoordinates = coordinates.coordinates(view)
    override fun close() { binding?.close(); binding = null; surfaces = emptyList() }
}

/** One native About-window cursor with direct existing-label leases. */
private class AboutContentAccessibilityOwner(
    windowRoot: View,
    private val root: ReverbAboutContentView,
    surface: View,
    clipHost: View,
    viewport: View,
    content: AboutColumn,
) : AutoCloseable {
    private val window = PlainTextAccessibilityWindowScope(windowRoot)
    private val leases = ArrayList<AutoCloseable>()
    private val geometry = NativePlainTextViewGeometry(windowRoot, NativeTextSemanticsClipPolicy { view ->
        when {
            view === clipHost || view === viewport ||
                (view.parent is ReverbGroupLayout && (view.parent as ReverbGroupLayout).getChildAt(0) === view) ->
                NativeTextSemanticsClip.Rectangle(ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            else -> NativeTextSemanticsClip.Unclipped
        }
    })

    init {
        fun exposed(view: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current.visibility != View.VISIBLE) return false
                if (current === windowRoot) return view.isAttachedToWindow
                current = current.parent as? View
            }
            return false
        }
        fun visit(view: View) {
            if (view is ReverbParagraphView) {
                view.isScreenReaderFocusable = view !== content.repository.row.title && view !== content.repository.row.label
                leases += view.bindPlainTextAccessibility(window, geometry) { exposed(view) }
                leases += bindAboutVisibility(view, { exposed(view) },
                    view !== content.repository.row.title && view !== content.repository.row.label)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(content)
        leases += bindAboutRepository(content.repository.row, geometry) { exposed(content.repository.row) }
        leases += bindAboutDescription(content.header.mark, window) { exposed(content.header.mark) }
        leases += bindAboutDescription(content.header.close.visual.glyph, window, speakingLeaf = false) {
            exposed(content.header.close.visual.glyph)
        }
        leases += bindAboutClose(content.header.close, geometry) { exposed(content.header.close.visual) }
    }

    override fun close() {
        leases.forEach(AutoCloseable::close)
        leases.clear()
    }
}

/** Retains native click/focus actions while adding the shared source description cursor actions. */
private fun bindAboutDescription(
    host: View,
    window: PlainTextAccessibilityWindowScope,
    speakingLeaf: Boolean = true,
    exposed: () -> Boolean,
): AutoCloseable {
    val description = PlainTextAccessibility(host, window,
        { PlainTextAccessibilitySnapshot(host.contentDescription?.toString().orEmpty(), { null }, { null }) },
        exposed, descriptionOnly = true)
    val delegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(view, info)
            val clickable = info.isClickable; val focusable = info.isFocusable; val focused = info.isFocused
            val actions = info.actionList.toList()
            description.populate(info)
            info.isVisibleToUser = info.isVisibleToUser && exposed() && !aboutHasTransparentAncestor(host)
            info.isImportantForAccessibility = exposed() && !aboutHasTransparentAncestor(host)
            info.isScreenReaderFocusable = speakingLeaf && exposed() && !aboutHasTransparentAncestor(host)
            info.isClickable = clickable; info.isFocusable = focusable; info.isFocused = focused
            actions.filter { it.id == AccessibilityNodeInfo.ACTION_CLICK ||
                it.id == AccessibilityNodeInfo.ACTION_FOCUS || it.id == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS }
                .forEach(info::addAction)
        }
        override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean =
            description.performAction(action, args) ?: super.performAccessibilityAction(view, action, args)
    }
    host.accessibilityDelegate = delegate
    return AutoCloseable { host.accessibilityDelegate = null } // Exclusive private About-View ownership; API28-safe.
}


/** Material3 Surface(onClick) has no explicit Role and retains its two unmerged text children. */
private fun bindAboutRepository(
    host: AboutRepositoryRow,
    geometry: NativePlainTextViewGeometry,
    exposed: () -> Boolean,
): AutoCloseable {
    val manager = host.context.getSystemService(AccessibilityManager::class.java)
    val delegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(view, info)
            info.className = "android.view.View"
            info.text = null; info.contentDescription = null
            info.isClickable = true; info.isLongClickable = false
            info.isFocusable = true; info.isFocused = host.isFocused
            info.isEnabled = true; info.isSelected = false
            info.isCheckable = false; info.isChecked = false
            info.isScreenReaderFocusable = exposed() && !aboutHasTransparentAncestor(host)
            info.isVisibleToUser = info.isVisibleToUser && exposed() && !aboutHasTransparentAncestor(host)
            info.isImportantForAccessibility = exposed() && !aboutHasTransparentAncestor(host)
            info.maxTextLength = -1; info.movementGranularities = 0
            geometry.capture(host, Offset.Zero,
                ComposeRect(0f, 0f, host.width.toFloat(), host.height.toFloat()))?.let {
                info.setBoundsInScreen(nativeMergedRoleBoundsInScreen(it, host.isLaidOut))
            }
            info.actionList.toList().forEach(info::removeAction)
            info.addAction(if (host.isAccessibilityFocused)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            info.addAction(if (host.isFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.removeChild(host.title); info.removeChild(host.label)
            info.addChild(host.title); info.addChild(host.label)
        }
        override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean {
            if (!exposed()) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    val result = host.invokeSourceClick()
                    if (manager?.isEnabled == true && host.isAttachedToWindow) {
                        @Suppress("DEPRECATION")
                        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
                            className = "android.view.View"; isEnabled = true
                            packageName = host.context.packageName; setSource(host)
                        }
                        host.parent?.requestSendAccessibilityEvent(host, event)
                    }
                    result
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS ->
                    super.performAccessibilityAction(view, action, args)
                else -> false
            }
        }
    }
    host.accessibilityDelegate = delegate
    return AutoCloseable { host.accessibilityDelegate = null } // Exclusive private About-View ownership; API28-safe.
}

private fun aboutHasTransparentAncestor(view: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current.alpha == 0f) return true
        current = current.parent as? View
    }
    return false
}

/** Transparency changes node visibility/importance, not its retained text/cursor semantics. */
private fun bindAboutVisibility(host: View, present: () -> Boolean, speakingLeaf: Boolean): AutoCloseable {
    host.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(view, info)
            val visible = present() && !aboutHasTransparentAncestor(host)
            info.isVisibleToUser = info.isVisibleToUser && visible
            info.isImportantForAccessibility = visible
            info.isScreenReaderFocusable = speakingLeaf && visible
        }
        override fun onInitializeAccessibilityEvent(view: View, event: AccessibilityEvent) {
            super.onInitializeAccessibilityEvent(view, event)
            event.className = "android.view.View"; event.isEnabled = true
            event.text.clear(); event.contentDescription = null
        }
    }
    return AutoCloseable { host.accessibilityDelegate = null }
}

/** Inner40dp action, described24dp child, then role-only child; outer48dp is layout only. */
private fun bindAboutClose(
    owner: AboutCloseButton,
    geometry: NativePlainTextViewGeometry,
    present: () -> Boolean,
): AutoCloseable {
    val host = owner.visual
    fun bounds(touch: Boolean): Rect {
        val x = if (touch) max(0f, owner.minimumTouchTargetPx - host.width) / 2f else 0f
        val y = if (touch) max(0f, owner.minimumTouchTargetPx - host.height) / 2f else 0f
        return geometry.capture(host, Offset.Zero,
            ComposeRect(-x, -y, host.width + x, host.height + y))?.let {
                nativeMergedRoleBoundsInScreen(it, host.isLaidOut)
            } ?: Rect()
    }
    fun visible() = present() && !aboutHasTransparentAncestor(host)
    val role = object : View(host.context) {
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; setWillNotDraw(true) }
        override fun draw(canvas: Canvas) = Unit
        override fun dispatchTouchEvent(event: MotionEvent) = false
        override fun dispatchHoverEvent(event: MotionEvent) = false
        override fun dispatchKeyEvent(event: KeyEvent) = false
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
            info.text = null; info.contentDescription = null
            info.isClickable = false; info.isLongClickable = false
            info.isFocusable = false; info.isFocused = false
            info.isEnabled = true; info.isScreenReaderFocusable = false
            val visualBounds = bounds(false)
            info.isVisibleToUser = visible() && !visualBounds.isEmpty
            info.isImportantForAccessibility = visible()
            info.setBoundsInParent(Rect()); info.setBoundsInScreen(visualBounds)
            info.drawingOrder = 1
            info.actionList.toList().forEach(info::removeAction)
            info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }
        override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean = when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> super.performAccessibilityAction(action, args)
            else -> false
        }
    }
    host.semanticRole = role
    host.addView(role)
    val manager = host.context.getSystemService(AccessibilityManager::class.java)
    host.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(view, info)
            info.className = "android.view.View"
            info.text = null; info.contentDescription = null
            info.isEnabled = true; info.isClickable = true; info.isLongClickable = false
            info.isFocusable = true; info.isFocused = host.isFocused
            val touchBounds = bounds(true)
            info.isVisibleToUser = visible() && !touchBounds.isEmpty
            info.isImportantForAccessibility = visible()
            info.isScreenReaderFocusable = visible()
            info.maxTextLength = -1; info.movementGranularities = 0
            info.setBoundsInScreen(touchBounds)
            info.actionList.toList().forEach(info::removeAction)
            info.addAction(if (host.isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            info.addAction(if (host.isFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.removeChild(host.glyph); info.removeChild(role)
            info.addChild(host.glyph); info.addChild(role)
        }
        override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean {
            if (!present()) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    val result = owner.invokeSourceClick()
                    if (manager?.isEnabled == true && host.isAttachedToWindow) {
                        @Suppress("DEPRECATION")
                        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
                            className = "android.view.View"; isEnabled = true
                            packageName = host.context.packageName; setSource(host)
                        }
                        host.parent?.requestSendAccessibilityEvent(host, event)
                    }
                    result
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS ->
                    super.performAccessibilityAction(view, action, args)
                else -> false
            }
        }
    }
    return AutoCloseable {
        host.accessibilityDelegate = null
        host.removeView(role)
        host.semanticRole = null
    }
}
