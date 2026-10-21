/*
 * Measurement policy adapted from AndroidX foundation 1.11.2 LayoutUtils.kt and
 * ParagraphLayoutCache.kt. Copyright 2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewStructure
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.graphics.CanvasHolder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrain
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Native text candidate; not yet wired into production screens or qualified for full text parity.
 *
 * An ordinary View using the existing pinned paragraph engine. There is no Composition,
 * LayoutNode/Modifier tree, recomposition scheduler, or font-readiness polling. SnapshotStateObserver
 * observes only the paragraph's asynchronously resolved-font/EmojiCompat readiness. A shared,
 * attached-consumer-only notification bridge makes that work without any Compose host; neither
 * mechanism is a second application state model.
 *
 * Scope: plain String labels, minLines=1, no selection, inline content, annotated links, editing,
 * or autosizing. The host owns role/color resolution, shared FontFamily.Resolver, and configuration
 * updates. Supply the exact released TextStyle and Density; do not reconstruct them from native
 * TextAppearance. Density must be immutable: replace it on configuration changes, retaining its
 * conversion implementation rather than copying only its two scalar values. Refresh the resolver
 * after locale/accessibility font-weight configuration changes.
 *
 * Remaining semantics qualification: native text/class/live-region semantics are exposed here.
 * Character-location extra-data, character/word traversal actions and Android translation
 * substitution are NOT implemented. Do not treat plain accessibility text as complete TalkBack
 * parity. Parent grouping/heading/click semantics remain the containing View's responsibility.
 * A host supporting TextOverflow.Visible must also disable inappropriate parent child clipping.
 */
internal class ReverbParagraphView(
    context: Context,
    initialStyle: TextStyle,
    initialDensity: Density,
    initialFontResolver: FontFamily.Resolver,
    initialTextColor: Int,
) : View(context) {
    private var textStyle = initialStyle
    private var density = initialDensity
    private var fontResolver = initialFontResolver
    private var textColor = Color(initialTextColor)
    private var label = ""
    private var softWrap = true
    private var maximumLines = Int.MAX_VALUE
    private var overflow = TextOverflow.Clip

    private var intrinsics: ParagraphIntrinsics? = null
    private var paragraph: Paragraph? = null
    private var previousConstraints: Constraints? = null
    private var previousDirection: LayoutDirection? = null
    private var measuredContentSize = IntSize.Zero
    private var clipParagraph = false
    private val canvasHolder = CanvasHolder()
    private var observedIntrinsics: ParagraphIntrinsics? = null

    private val onFontsChanged: (ReverbParagraphView) -> Unit = { owner ->
        if (owner.isAttachedToWindow && owner.observedIntrinsics != null) owner.invalidateTextMetrics()
    }
    private val fontObserver = SnapshotStateObserver { command ->
        // Apply delivery can occur while a paragraph is being measured. Defer its invalidation
        // until that traversal finishes instead of clearing the paragraph during onMeasure.
        post { command() }
    }

    init {
        setPadding(0, 0, 0, 0)
        minimumWidth = 0
        minimumHeight = 0
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setText(value: String) {
        if (label == value) return
        label = value
        invalidateTextMetrics()
        if (isAttachedToWindow) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun getText(): String = label

    /** Call from the host's configuration/theme binding, never from onDraw. */
    fun setTypography(style: TextStyle, textDensity: Density, sharedResolver: FontFamily.Resolver) {
        val densityChanged = density !== textDensity
        if (textStyle == style && !densityChanged && fontResolver === sharedResolver) return
        textStyle = style
        density = textDensity
        fontResolver = sharedResolver
        invalidateTextMetrics()
    }

    fun setTextColor(argb: Int) {
        val color = Color(argb)
        if (textColor == color) return
        textColor = color
        invalidate()
    }

    fun setTextLayout(softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
        require(maxLines >= 1) { "maxLines must be positive, matching BasicText" }
        if (this.softWrap == softWrap && maximumLines == maxLines && this.overflow == overflow) return
        this.softWrap = softWrap
        maximumLines = maxLines
        this.overflow = overflow
        paragraph = null
        previousConstraints = null
        requestLayout()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fontObserver.start()
        // Font/emoji state may have changed while this detached View had no observer.
        invalidateTextMetrics()
    }

    override fun onDetachedFromWindow() {
        clearFontObservation()
        fontObserver.stop()
        fontObserver.clear()
        super.onDetachedFromWindow()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        invalidateTextMetrics()
    }

    private fun invalidateTextMetrics() {
        clearFontObservation()
        intrinsics = null
        paragraph = null
        previousConstraints = null
        previousDirection = null
        requestLayout()
        invalidate()
    }

    private fun paragraphDirection(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun ensureIntrinsics(direction: LayoutDirection): ParagraphIntrinsics {
        val existing = intrinsics
        if (existing != null && previousDirection == direction && !existing.hasStaleResolvedFonts) return existing
        val created = ParagraphIntrinsics(
            text = label,
            style = resolveDefaults(textStyle, direction),
            annotations = emptyList(),
            placeholders = emptyList(),
            density = density,
            fontFamilyResolver = fontResolver,
        )
        intrinsics = created
        paragraph = null
        previousConstraints = null
        previousDirection = direction
        return created
    }

    private fun observeResolvedFonts(current: ParagraphIntrinsics) {
        if (!isAttachedToWindow || observedIntrinsics === current) return
        observedIntrinsics = current
        var alreadyStale = false
        ReverbFontSnapshotNotifications.observeReads(this) {
            fontObserver.observeReads(this, onFontsChanged) {
                alreadyStale = current.hasStaleResolvedFonts
            }
        }
        // Completion may precede registration of a newly observed state. The bridge initializes
        // those states, then this second read closes that hand-off without readiness polling.
        if (alreadyStale || current.hasStaleResolvedFonts) {
            post {
                if (isAttachedToWindow && observedIntrinsics === current) invalidateTextMetrics()
            }
        }
    }

    private fun clearFontObservation() {
        observedIntrinsics = null
        fontObserver.clear(this)
        ReverbFontSnapshotNotifications.clear(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val minWidth = contentMinimum(widthMeasureSpec, horizontalPadding)
        val maxWidth = contentMaximum(widthMeasureSpec, horizontalPadding)
        val minHeight = contentMinimum(heightMeasureSpec, verticalPadding)
        val maxHeight = contentMaximum(heightMeasureSpec, verticalPadding)
        val constraints = Constraints.fitPrioritizingWidth(
            minWidth = minWidth, maxWidth = maxWidth,
            minHeight = minHeight, maxHeight = maxHeight,
        )
        val direction = paragraphDirection()
        val currentIntrinsics = ensureIntrinsics(direction)
        val currentParagraph = if (paragraph != null && previousConstraints == constraints) {
            paragraph!!
        } else {
            layoutParagraph(currentIntrinsics, constraints).also {
                paragraph = it
                previousConstraints = constraints
            }
        }
        measuredContentSize = constraints.constrain(
            IntSize(ceilPixel(currentParagraph.width), ceilPixel(currentParagraph.height)),
        )
        clipParagraph = overflow != TextOverflow.Visible &&
            (measuredContentSize.width < currentParagraph.width || measuredContentSize.height < currentParagraph.height)
        setMeasuredDimension(
            resolveSizeAndState(measuredContentSize.width + horizontalPadding, widthMeasureSpec, 0),
            resolveSizeAndState(measuredContentSize.height + verticalPadding, heightMeasureSpec, 0),
        )
        observeResolvedFonts(currentIntrinsics)
    }

    private fun layoutParagraph(current: ParagraphIntrinsics, constraints: Constraints): Paragraph {
        val widthMatters = softWrap || overflow.isEllipsis()
        val availableWidth = if (widthMatters && constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val paragraphWidth = if (constraints.minWidth == availableWidth) availableWidth
            else ceilPixel(current.maxIntrinsicWidth).coerceIn(constraints.minWidth, availableWidth)
        return Paragraph(
            paragraphIntrinsics = current,
            constraints = Constraints.fitPrioritizingWidth(
                minWidth = 0, maxWidth = paragraphWidth,
                minHeight = 0, maxHeight = constraints.maxHeight,
            ),
            maxLines = if (!softWrap && overflow.isEllipsis()) 1 else maximumLines,
            overflow = overflow,
        )
    }

    /** Intrinsic queries are explicit because Android View has no Compose-style intrinsic protocol. */
    fun minIntrinsicTextWidth(): Int =
        ceilPixel(ensureIntrinsics(paragraphDirection()).minIntrinsicWidth)

    fun maxIntrinsicTextWidth(): Int =
        ceilPixel(ensureIntrinsics(paragraphDirection()).maxIntrinsicWidth)

    fun intrinsicTextHeight(widthPx: Int): Int {
        require(widthPx >= 0)
        val current = ensureIntrinsics(paragraphDirection())
        return ceilPixel(layoutParagraph(current, Constraints(maxWidth = widthPx)).height)
    }

    override fun getBaseline(): Int = paragraph?.let { paddingTop + it.firstBaseline.roundToInt() } ?: -1

    val lastTextBaseline: Int
        get() = paragraph?.let { paddingTop + it.lastBaseline.roundToInt() } ?: -1

    /** Read-only geometry for the parity harness, not a second layout. */
    val currentParagraph: Paragraph?
        get() = paragraph

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = paragraph ?: return
        val saved = canvas.save()
        try {
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            if (clipParagraph) canvas.clipRect(0, 0, measuredContentSize.width, measuredContentSize.height)
            val shadow = textStyle.shadow ?: Shadow.None
            val decoration = textStyle.textDecoration ?: TextDecoration.None
            val drawStyle = textStyle.drawStyle ?: Fill
            val brush = textStyle.brush
            canvasHolder.drawInto(canvas) {
                if (brush != null) {
                    current.paint(this, brush, textStyle.alpha, shadow, decoration, drawStyle)
                } else {
                    current.paint(this, textColor, shadow, decoration, drawStyle)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    override fun getAccessibilityClassName(): CharSequence = TextView::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = TextView::class.java.name
        info.text = label
        info.isEditable = false
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = TextView::class.java.name
        event.text.add(label)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        }
    }

    override fun onProvideStructure(structure: ViewStructure) {
        super.onProvideStructure(structure)
        structure.setText(label)
    }

    private fun contentMinimum(spec: Int, padding: Int): Int =
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) (MeasureSpec.getSize(spec) - padding).coerceAtLeast(0) else 0

    private fun contentMaximum(spec: Int, padding: Int): Int =
        if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) Constraints.Infinity
        else (MeasureSpec.getSize(spec) - padding).coerceAtLeast(0)

    private fun TextOverflow.isEllipsis(): Boolean =
        this == TextOverflow.Ellipsis || this == TextOverflow.StartEllipsis || this == TextOverflow.MiddleEllipsis

    private fun ceilPixel(value: Float): Int = ceil(value).roundToInt()
}
