package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class LibraryBodyMode { LOADING, FAILED, EMPTY }

/**
 * Only FilesScreen's existing three non-list branches. The real owner chooses the branch and owns
 * refresh. Empty uses the shared native vertical viewport so pull/scroll routing is not replaced
 * with a platform ScrollView. Pull-to-refresh ancestor and edge-dismiss remain the outer owner.
 */
internal class ReverbLibraryStatusView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private val frames: MonotonicFrameClock,
    private val parentScope: CoroutineScope,
    private val nested: NativeVerticalNestedHost,
    private val nativeInputRoot: View,
) : ViewGroup(context) {
    private var mode: LibraryBodyMode? = null
    private var retry: () -> Unit = {}
    private var body: LibraryStatusColumn? = null
    private var viewport: NativeVerticalViewport? = null
    private var emptyScrollState: NativeVerticalScrollState? = null
    val emptyViewport: NativeVerticalViewport? get() = viewport

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(next: LibraryBodyMode, onRetry: () -> Unit) {
        retry = onRetry
        if (mode != next) {
            removeAllViews()
            viewport = null
            emptyScrollState = null
            mode = next
            val column = LibraryStatusColumn(context, environment, next, nativeInputRoot) { retry() }
            body = column
            if (next == LibraryBodyMode.EMPTY) {
                val scroll = NativeVerticalScrollState()
                emptyScrollState = scroll
                viewport = NativeVerticalViewport(context, scroll, environment.textDensity, frames, parentScope, nested).also {
                    it.setContent(column)
                    addView(it)
                }
            } else addView(column)
        } else body?.bind(environment) { retry() }
        requestLayout()
    }

    fun setEnvironment(next: ReverbViewEnvironment) {
        val densityChanged = environment.textDensity !== next.textDensity
        environment = next
        body?.bind(next) { retry() }
        val oldViewport = viewport
        val state = emptyScrollState
        val column = body
        if (densityChanged && oldViewport != null && state != null && column != null) {
            oldViewport.removeView(column)
            removeView(oldViewport)
            viewport = NativeVerticalViewport(context, state, next.textDensity, frames, parentScope, nested).also {
                it.setContent(column)
                addView(it)
            }
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (childCount > 0) getChildAt(0).measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount > 0) getChildAt(0).layout(0, 0, width, height)
    }
}

private class LibraryStatusColumn(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private val mode: LibraryBodyMode,
    inputRoot: View,
    retry: () -> Unit,
) : ViewGroup(context) {
    private val loading = if (mode == LibraryBodyMode.LOADING)
        ReverbIncidentLoadingView(context, environment.palette.primary, strokeWidthDp = 4f) else null
    private val failure = if (mode == LibraryBodyMode.FAILED)
        environment.paragraph(context, context.getString(R.string.recordings_refresh_failed),
            ReverbTextRole.BODY_MEDIUM, environment.palette.muted) else null
    private val retryButton = if (mode == LibraryBodyMode.FAILED) ReverbLibraryTextAction(context, environment, inputRoot) else null
    private val empty = if (mode == LibraryBodyMode.EMPTY) LibraryEmptySymbol(context, environment) else null
    private var childrenHeight = 0
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        loading?.let(::addView)
        failure?.let(::addView)
        retryButton?.let(::addView)
        empty?.let(::addView)
        bind(environment, retry)
    }
    fun bind(value: ReverbViewEnvironment, retry: () -> Unit) {
        environment = value
        loading?.setColor(value.palette.primary)
        failure?.let { value.bindParagraphStyle(it, ReverbTextRole.BODY_MEDIUM, value.palette.muted) }
        retryButton?.bind(value, context.getString(R.string.library_retry), onClick = retry)
        empty?.bind(value)
        requestLayout()
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val boundedHeight = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val height = MeasureSpec.getSize(heightMeasureSpec)
        childrenHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val fixed = if (child === loading) dp(40f) else if (child === empty) dp(84f) else null
            val remaining = max(0, height - childrenHeight)
            val childWidth = if (fixed == null) MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
                else MeasureSpec.makeMeasureSpec(minOf(width, fixed), MeasureSpec.EXACTLY)
            val childHeight = if (fixed != null) MeasureSpec.makeMeasureSpec(if (boundedHeight) minOf(fixed, remaining) else fixed, MeasureSpec.EXACTLY)
                else if (boundedHeight) MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST)
                else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(childWidth, childHeight)
            childrenHeight += child.measuredHeight
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(childrenHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = ((height - childrenHeight) / 2f).roundToInt()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val x = ((width - child.measuredWidth) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight
        }
    }
}

private class LibraryEmptySymbol(context: Context, private var environment: ReverbViewEnvironment) : ViewGroup(context) {
    private val surface = ReverbGroupLayout(context, 28f)
    private val glyph = object : View(context) {
        var icon = ReverbIconDrawable(ReverbIcon.Library, environment.textDensity.density,
            reverbColorWithAlpha(environment.palette.muted, .72f))
        override fun onDraw(canvas: Canvas) { icon.draw(canvas) }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateBounds(w, h)
        fun updateBounds(w: Int, h: Int) {
            val size = (34f * environment.textDensity.density).roundToInt()
            val glyphWidth = minOf(size, w)
            val glyphHeight = minOf(size, h)
            val x = ((w - glyphWidth) / 2f).roundToInt()
            val y = ((h - glyphHeight) / 2f).roundToInt()
            icon.layoutDirection = layoutDirection
            icon.setBounds(x, y, x + glyphWidth, y + glyphHeight)
        }
        override fun onRtlPropertiesChanged(layoutDirection: Int) { super.onRtlPropertiesChanged(layoutDirection); icon.layoutDirection = layoutDirection }
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(glyph, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(surface)
        bind(environment)
    }
    fun bind(value: ReverbViewEnvironment) {
        if (environment.textDensity.density != value.textDensity.density) {
            glyph.icon = ReverbIconDrawable(ReverbIcon.Library, value.textDensity.density,
                reverbColorWithAlpha(value.palette.muted, .72f))
        }
        environment = value
        surface.setPalette(value.palette.surfaceContainerHigh, android.graphics.Color.TRANSPARENT)
        glyph.icon.setTint(reverbColorWithAlpha(value.palette.muted, .72f))
        glyph.updateBounds(glyph.width, glyph.height)
        glyph.invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        surface.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(surface.measuredWidth, surface.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { surface.layout(0, 0, width, height) }
}
