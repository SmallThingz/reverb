package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.smallthingz.reverb.NativeRangeExportPresentation
import app.smallthingz.reverb.RangeEditTarget
import app.smallthingz.reverb.RangeExportEditorState
import app.smallthingz.reverb.formatRangeTimeInput
import app.smallthingz.reverb.rangeBlobMorphStartScaleX
import app.smallthingz.reverb.rangeBlobMorphStartScaleY
import app.smallthingz.reverb.rangeBlobMorphTranslation
import kotlin.math.roundToInt

/**
 * Exact ordinary-View visual portion of RangeExportTimeline. The retained range input owner
 * attaches its source endpoint semantics/keys and pointer nodes to endpointStart/endpointEnd,
 * and its timeline scrub node to waveformInput. No competing Android recognizer lives here.
 * onWaveformPositioned belongs to the host's authoritative float root-coordinate owner.
 */
internal class ReverbRangeTimelineView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    private val onWaveformPositioned: (View) -> Unit,
) : ViewGroup(context) {
    private var environment = initialEnvironment
    private var editor: RangeExportEditorState? = null
    private var presentation: NativeRangeExportPresentation? = null
    private var data: ProgressiveWaveformData? = null
    private var renderedStart = Float.NaN
    private var renderedEnd = Float.NaN
    private var renderedDuration = Float.NaN
    private var renderedTarget: RangeEditTarget? = null
    private var stylesDirty = true
    val waveformInput: ViewGroup = object : ViewGroup(context) {
        init { clipChildren = false; clipToPadding = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
            waveform.measure(exact(width), exact(height)); setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            waveform.layout(0, 0, width, height)
            onWaveformPositioned(this)
        }
    }
    val waveform: ProgressiveWaveformView = ProgressiveWaveformView(context)
    private val chrome = Chrome(context)
    private val start = Endpoint(context)
    private val end = Endpoint(context)
    val endpointStart: app.smallthingz.reverb.NativeRangeEndpointView get() = start.target
    val endpointEnd: app.smallthingz.reverb.NativeRangeEndpointView get() = end.target
    private val startBubble = TimeBubble(context)
    private val endBubble = TimeBubble(context)
    private val first = environment.paragraph(context, formatRangeTimeInput(0.0), ReverbTextRole.LABEL_SMALL)
    private val last = environment.paragraph(context, "", ReverbTextRole.LABEL_SMALL)

    init {
        clipChildren = false; clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        waveformInput.addView(waveform)
        addView(waveformInput); addView(chrome)
        chrome.addView(start); chrome.addView(end)
        chrome.addView(startBubble); chrome.addView(endBubble)
        chrome.addView(first); chrome.addView(last)
        bindTextEnvironment()
    }

    fun bind(state: RangeExportEditorState, frame: NativeRangeExportPresentation) {
        editor = state; presentation = frame
        if (data !== frame.waveformData) { data = frame.waveformData; waveform.setWaveform(data) }
        waveform.setFrame(frame.waveformFrame)
        chrome.alpha = frame.chromeAlpha
        if (stylesDirty || renderedStart != state.startSeconds || renderedEnd != state.endSeconds ||
            renderedDuration != state.durationSeconds || renderedTarget != state.lastTarget) {
            start.marker.bind(environment, state.lastTarget == RangeEditTarget.START,
                state.lastTarget == RangeEditTarget.START, 1f)
            end.marker.bind(environment, state.lastTarget == RangeEditTarget.END,
                state.lastTarget == RangeEditTarget.END, 1f)
            startBubble.bind(state.targetSeconds(RangeEditTarget.START), state.lastTarget == RangeEditTarget.START)
            endBubble.bind(state.targetSeconds(RangeEditTarget.END), state.lastTarget == RangeEditTarget.END)
            last.setText(formatRangeTimeInput(state.durationSeconds.toDouble()))
            chrome.startActive = state.lastTarget == RangeEditTarget.START
            renderedStart = state.startSeconds; renderedEnd = state.endSeconds
            renderedDuration = state.durationSeconds; renderedTarget = state.lastTarget
            stylesDirty = false
            requestLayout()
        }
        // Draw-only wobble/morph frames do not remeasure labels or recreate their typography.
        updateMorph()
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        stylesDirty = true
        bindTextEnvironment()
        val state = editor; val frame = presentation
        if (state != null && frame != null) bind(state, frame)
        requestLayout()
    }

    private fun bindTextEnvironment() {
        waveform.setPalette(ProgressiveWaveformPalette(environment.palette.primary,
            environment.palette.onSurfaceVariant, environment.palette.tertiary))
        for (label in listOf(first, last)) {
            label.setTypography(ReverbParagraphStyles[ReverbTextRole.LABEL_SMALL].copy(fontFamily = FontFamily.Monospace),
                environment.textDensity, environment.sharedFontResolver)
            label.setTextColor(reverbColorWithAlpha(environment.palette.onSurfaceVariant, .66f))
        }
    }

    private fun updateMorph() {
        val frame = presentation ?: return
        val source = frame.sourceGeometry
        val target = frame.waveformTargetBoundsInRoot
        if (!frame.transitionStarted || source == null || target == null) {
            waveform.alpha = 0f
        } else {
            val morph = frame.waveformFrame.morphProgress
            val x = rangeBlobMorphStartScaleX(source.bodyDiameterPx, target.width)
            val y = rangeBlobMorphStartScaleY(source.bodyDiameterPx, target.height)
            waveform.alpha = 1f
            waveform.pivotX = waveform.width * .5f; waveform.pivotY = waveform.height * .5f
            waveform.scaleX = x + (1f - x) * morph
            waveform.scaleY = y + (1f - y) * morph
            waveform.translationX = rangeBlobMorphTranslation(morph, source.centerXInRootPx, target.center.x)
            waveform.translationY = rangeBlobMorphTranslation(morph, source.centerYInRootPx, target.center.y)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        waveformInput.measure(exact((width - dp(12f) * 2).coerceAtLeast(0)), exact(minOf(height, dp(146f))))
        chrome.measure(exact(width), exact(height))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        waveformInput.layout(dp(12f), dp(42f), dp(12f) + waveformInput.measuredWidth,
            dp(42f) + waveformInput.measuredHeight)
        chrome.layout(0, 0, width, height)
        updateMorph()
    }

    /** The source active bubble's zIndex30 also orders its inert Surface hit shield. */
    fun pointerChildrenInHitOrder(parent: ViewGroup): List<View>? {
        if (parent !== chrome) return null
        val active = if (chrome.startActive) startBubble else endBubble
        return listOf(active) + (chrome.childCount - 1 downTo 0)
            .map(chrome::getChildAt).filter { it !== active && it.visibility == VISIBLE }
    }

    /** Source xFor/secondsFor use raw dp floats; final offset placement rounds separately. */
    fun timelineWidthPixels(): Float = (width - 24f * density).coerceAtLeast(1f)
    fun markerCenterPixels(target: RangeEditTarget): Float {
        val state = checkNotNull(editor)
        return 12f * density + timelineWidthPixels() *
            (state.targetSeconds(target) / state.durationSeconds).coerceIn(0f, 1f)
    }
    fun secondsForWaveformX(localX: Float): Float {
        val state = checkNotNull(editor)
        // Source waveform-local x + inset, then secondsFor subtracts the same inset.
        return (localX / timelineWidthPixels() * state.durationSeconds).coerceIn(0f, state.durationSeconds)
    }

    private inner class Chrome(context: Context) : ViewGroup(context) {
        var startActive = true
            set(value) { if (field != value) { field = value; invalidate() } }
        init {
            clipChildren = false; clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setChildrenDrawingOrderEnabled(true)
        }
        // Surface elevation belongs inside each bubble; source zIndex30 raises only active label.
        override fun getChildDrawingOrder(count: Int, position: Int): Int {
            val active = if (startActive) 2 else 3
            return if (position == count - 1) active else if (position >= active) position + 1 else position
        }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
            for (endpoint in listOf(start, end)) endpoint.measure(exact(minOf(width, dp(44f))),
                exact(minOf(height, dp(146f))))
            for (bubble in listOf(startBubble, endBubble)) bubble.measure(exact(minOf(width, dp(84f))),
                exact(minOf(height, dp(28f))))
            for (label in listOf(first, last)) label.measure(atMost((width - dp(12f)).coerceAtLeast(0)), atMost(height))
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            if (editor == null) return
            fun place(target: RangeEditTarget, endpoint: View, bubble: View) {
                val center = markerCenterPixels(target)
                relative(endpoint, (center - 44f * density * .5f).roundToInt(), dp(42f))
                val bubbleLeft = (center - 84f * density * .5f)
                    .coerceIn(0f, (width - 84f * density).coerceAtLeast(0f)).roundToInt()
                relative(bubble, bubbleLeft, dp(3f))
            }
            place(RangeEditTarget.START, start, startBubble)
            place(RangeEditTarget.END, end, endBubble)
            relative(first, dp(12f), height - first.measuredHeight)
            relative(last, width - dp(12f) - last.measuredWidth, height - last.measuredHeight)
        }
        private fun relative(child: View, x: Int, y: Int) {
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - child.measuredWidth else x
            child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
        }
    }

    private inner class Endpoint(context: Context) : ViewGroup(context) {
        val marker = ReverbTimelineMarkerView(context, environment)
        val target = app.smallthingz.reverb.NativeRangeEndpointView(context)
        init {
            clipChildren = false; clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            target.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(marker); addView(target)
        }
        override fun addChildrenForAccessibility(children: ArrayList<View>) {
            // Never advertise a child identity which its source-presence check will reject.
            if (target.importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_YES &&
                target.sourceAccessibilityNode?.invoke()?.present != false) children.add(target)
        }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
            marker.measure(exact(width), exact(height)); target.measure(exact(width), exact(height))
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            marker.layout(0, 0, width, height); target.layout(0, 0, width, height)
        }
    }

    private inner class TimeBubble(context: Context) : ViewGroup(context) {
        private val surface = ReverbGroupLayout(context, 10f)
        private val text = environment.paragraph(context, "", ReverbTextRole.LABEL_MEDIUM)
        private val content = object : ViewGroup(context) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
                text.measure(atMost((width - dp(5f) * 2).coerceAtLeast(0)),
                    atMost((height - dp(2f) * 2).coerceAtLeast(0)))
                setMeasuredDimension(width, height)
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val x = ((width - text.measuredWidth) * .5f).roundToInt()
                val y = ((height - text.measuredHeight) * .5f).roundToInt()
                text.layout(x, y, x + text.measuredWidth, y + text.measuredHeight)
            }
        }
        init {
            clipChildren = false; clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            content.addView(text)
            surface.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height,
                        minOf(10f * density, minOf(view.width, view.height) * .5f))
                }
            }
            surface.addRow(content, ReverbGroupLayout.LayoutParams(-1, -1))
            addView(surface)
            text.setTextLayout(true, 1, androidx.compose.ui.text.style.TextOverflow.Clip)
        }
        fun bind(seconds: Float, active: Boolean) {
            val palette = environment.palette
            surface.setPalette(if (active) palette.primaryContainer else palette.surfaceContainerHighest, 0)
            surface.elevation = if (active) density else 0f
            text.setText(formatRangeTimeInput(seconds.toDouble()))
            text.setTypography(ReverbParagraphStyles[ReverbTextRole.LABEL_MEDIUM].copy(
                color = androidx.compose.ui.graphics.Color(if (active) palette.onPrimaryContainer else palette.onSurfaceVariant),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp, lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
            ), environment.textDensity, environment.sharedFontResolver)
            text.setTextColor(if (active) palette.onPrimaryContainer else palette.onSurfaceVariant)
        }
        override fun onMeasure(w: Int, h: Int) {
            surface.measure(w, h); setMeasuredDimension(surface.measuredWidth, surface.measuredHeight)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            surface.layout(0, 0, width, height)
            surface.invalidateOutline()
        }
    }

    private val density get() = environment.textDensity.density
    private fun dp(value: Float) = (value * density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.AT_MOST)
}
