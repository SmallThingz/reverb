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
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewStructure
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import android.view.translation.ViewTranslationRequest
import android.view.translation.ViewTranslationResponse
import androidx.annotation.RequiresApi
import java.util.function.Consumer
import android.widget.TextView
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.graphics.Color
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.ParagraphIntrinsics
import smallthingz.ui.text.AnnotatedString
import smallthingz.ui.text.MultiParagraph
import smallthingz.ui.text.MultiParagraphIntrinsics
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.resolveDefaults
import smallthingz.ui.text.style.TextDecoration
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.unit.Constraints
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.IntSize
import smallthingz.ui.unit.LayoutDirection
import smallthingz.ui.unit.constrain
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native text View using Android TextPaint and StaticLayout/BoringLayout.
 * Layout, accessibility geometry and drawing share one retained paragraph. EmojiCompat readiness
 * invalidates only attached consumers; there is no composition or snapshot state engine.
 *
 * Scope: plain String labels, minLines=1, no selection, inline content, annotated links, editing,
 * or autosizing. The host owns role/color resolution, shared FontFamily.Resolver, and configuration
 * updates. Supply the exact released TextStyle and Density; do not reconstruct them from native
 * TextAppearance. Density must be immutable: replace it on configuration changes, retaining its
 * conversion implementation rather than copying only its two scalar values. Refresh the resolver
 * after locale/accessibility font-weight configuration changes.
 *
 * A native host can bind one existing window's plain-text accessibility cursor/geometry ownership.
 * This enables source-derived selectionless traversal and character bounds, without adding keyboard
 * tab stops. It is not yet TalkBack-qualified. Unbound views retain basic native label semantics.
 * API31 translation has a separate display cache. Original text/layout stays authoritative for
 * accessibility, content capture and new translation requests; display measurement cannot overwrite
 * the original cache's constraints. Runtime translation/event equivalence is not yet qualified.
 * Parent grouping/heading/click semantics remain the containing View's responsibility.
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

    /** Two small direct caches, not a renderer/state tree. Source substitution owns its own policy. */
    private class ParagraphCache(
        var text: String,
        var style: TextStyle,
        var density: Density,
        var resolver: FontFamily.Resolver,
        var softWrap: Boolean,
        var maximumLines: Int,
        var overflow: TextOverflow,
    ) {
        var intrinsics: ParagraphIntrinsics? = null
        var paragraph: Paragraph? = null
        var previousConstraints: Constraints? = null
        var previousDirection: LayoutDirection? = null
        var layoutSize = IntSize.Zero
        var didOverflow = false
        var intrinsicHeightWidth = -1
        var intrinsicHeight = -1

        fun invalidateMetrics() {
            intrinsics = null
            paragraph = null
            previousConstraints = null
            previousDirection = null
            layoutSize = IntSize.Zero
            didOverflow = false
            intrinsicHeightWidth = -1
            intrinsicHeight = -1
        }
    }

    private val originalCache = ParagraphCache(
        label, textStyle, density, fontResolver, softWrap, maximumLines, overflow,
    )
    private var substitutionCache: ParagraphCache? = null
    private val substitution = ReverbTextSubstitutionState(label, ::invalidateTranslation)
    private val translationApi31 = if (Build.VERSION.SDK_INT >= 31) {
        ReverbTextTranslationApi31(this, substitution)
    } else null
    private val activeCache: ParagraphCache
        get() = if (substitution.isShowingSubstitution) substitutionCache ?: originalCache else originalCache

    private var measuredContentSize = IntSize.Zero
    private var clipParagraph = false
    private var firstBaseline = -1
    private var lastBaseline = -1
    private var observedIntrinsics: ParagraphIntrinsics? = null
    private var textAccessibility: PlainTextAccessibility? = null
    private var accessibilityWindow: PlainTextAccessibilityWindowScope? = null
    private var accessibilityGeometry: NativePlainTextViewGeometry? = null
    private var accessibilityExposed: (() -> Boolean)? = null
    private var accessibilityBindingToken: Any? = null

    private var fontObservation: AutoCloseable? = null
    private var reportedSubstitution: String? = null
    private var reportedShowingSubstitution = false
    private var translationSemanticsPosted = false
    private val translationSemanticsInvalidation = Runnable {
        translationSemanticsPosted = false
        if (isAttachedToWindow) {
            val changed = reportedSubstitution != substitution.substitution ||
                reportedShowingSubstitution != substitution.isShowingSubstitution
            reportedSubstitution = substitution.substitution
            reportedShowingSubstitution = substitution.isShowingSubstitution
            if (changed && accessibilityExposed?.invoke() != false &&
                context.getSystemService(AccessibilityManager::class.java)?.isEnabled == true) {
                // These are translation semantics properties, NOT replacement source text.
                // Pinned delegate:2890-2908 uses generic/undefined content change for this diff.
                // Forward directly: onInitializeAccessibilityEvent's text-change path is unsuitable.
                @Suppress("DEPRECATION")
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                event.className = View::class.java.name
                event.packageName = context.packageName
                event.isEnabled = true
                event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
                event.setSource(this)
                parent?.requestSendAccessibilityEvent(this, event)
            }
        }
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
        substitution.replaceOriginal(value) // same View identity, new source retires translation
        substitutionCache = null
        updateOriginalCache()
        queueTranslationSemanticsInvalidation()
        if (isAttachedToWindow) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun getText(): String = label

    /**
     * Bind to the containing native window, not a per-label cursor. Rebinding the same window keeps
     * this label's semantics identity while refreshing layout callbacks. A stale close handle cannot
     * remove a newer binding. The host closes its binding when its layout lifetime ends.
     *
     * Labels remain exposed beneath a merging parent in the source unmerged accessibility tree.
     * The owner sets their screenReaderFocusable flag separately; this method adds no keyboard stop.
     */
    private var sourceAccessibilityNode: (() -> NativeSourceAccessibilityNode)? = null

    internal fun originalAccessibilityBoundsInView() = Rect(
        paddingLeft.toFloat(), paddingTop.toFloat(),
        (paddingLeft + measuredContentSize.width).toFloat(),
        (paddingTop + measuredContentSize.height).toFloat(),
    )

    fun bindPlainTextAccessibility(
        window: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry,
        sourceNode: (() -> NativeSourceAccessibilityNode)? = null,
        exposed: () -> Boolean,
    ): AutoCloseable {
        val token = Any()
        accessibilityBindingToken = token
        accessibilityGeometry = geometry
        accessibilityExposed = exposed
        sourceAccessibilityNode = sourceNode
        if (accessibilityWindow !== window || textAccessibility == null) {
            accessibilityWindow = window
            textAccessibility = PlainTextAccessibility(
                this, window,
                snapshot = {
                    PlainTextAccessibilitySnapshot(
                        label, ::createOriginalSemanticsParagraph,
                        geometry = {
                            accessibilityGeometry?.capture(
                                this,
                                Offset(paddingLeft.toFloat() - scrollX, paddingTop.toFloat() - scrollY),
                                Rect(
                                    paddingLeft.toFloat(), paddingTop.toFloat(),
                                    (paddingLeft + measuredContentSize.width).toFloat(),
                                    (paddingTop + measuredContentSize.height).toFloat(),
                                ),
                            )
                        },
                    )
                },
                exposed = { accessibilityExposed?.invoke() == true },
            )
        }
        return AutoCloseable {
            if (accessibilityBindingToken === token) {
                accessibilityBindingToken = null
                accessibilityGeometry = null
                accessibilityExposed = null
                sourceAccessibilityNode = null
                accessibilityWindow = null
                textAccessibility = null
            }
        }
    }

    /** Same-label text updates keep identity; recycler reuse for another row explicitly replaces it. */
    fun replacePlainTextAccessibilityIdentity() {
        textAccessibility?.replaceIdentity()
    }

    /** Call from the host's configuration/theme binding, never from onDraw. */
    fun setTypography(style: TextStyle, textDensity: Density, sharedResolver: FontFamily.Resolver) {
        val densityChanged = density !== textDensity
        if (textStyle == style && !densityChanged && fontResolver === sharedResolver) return
        val layoutChanged = !textStyle.hasSameLayoutAffectingAttributes(style) ||
            fontResolver !== sharedResolver
        textStyle = style
        density = textDensity
        fontResolver = sharedResolver
        if (layoutChanged) {
            updateOriginalCache()
        } else {
            // Density belongs to whichever cache is actually measured; a display-only update
            // must not discard an inactive original cache's semantics layout/constraints.
            if (densityChanged) requestLayout()
            invalidate()
        }
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
        updateOriginalCache()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The source retains original/substitution caches across detach. Measurement checks font
        // staleness; destroying both caches here would lose original semantics while translated.
        reportedSubstitution = substitution.substitution
        reportedShowingSubstitution = substitution.isShowingSubstitution
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(translationSemanticsInvalidation)
        translationSemanticsPosted = false
        clearFontObservation()
        super.onDetachedFromWindow()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        // ensureIntrinsics updates only the selected cache on the next measure, like the source.
        requestLayout()
        invalidate()
    }

    private fun updateOriginalCache() {
        clearFontObservation()
        originalCache.apply {
            text = label
            style = textStyle
            resolver = fontResolver
            softWrap = this@ReverbParagraphView.softWrap
            maximumLines = this@ReverbParagraphView.maximumLines
            overflow = this@ReverbParagraphView.overflow
            invalidateMetrics()
        }
        requestLayout()
        invalidate()
    }

    private fun cacheForMeasure(): ParagraphCache = activeCache.also { cache ->
        // Only the actively measured source cache receives the current density.
        if (cache.density !== density) {
            cache.density = density
            cache.invalidateMetrics()
        }
    }

    /**
     * Source SetTextSubstitution updates only its separate cache. Identical responses still request
     * measure/draw/semantics, but preserve the existing cache; hide/show never discards original
     * constraints. Source style/resolver/wrap policy updates apply to the original cache; the
     * substitution receives current policy when its text is created/changed, exactly as the node.
     */
    private fun invalidateTranslation() {
        val translated = substitution.substitution
        if (translated == null) {
            substitutionCache = null
        } else if (substitutionCache?.text != translated) {
            val existing = substitutionCache
            if (existing == null) {
                substitutionCache = ParagraphCache(
                    translated, textStyle, originalCache.density, fontResolver,
                    softWrap, maximumLines, overflow,
                )
            } else {
                // Updating a substitution keeps that cache's density until its next measure.
                existing.text = translated
                existing.style = textStyle
                existing.resolver = fontResolver
                existing.softWrap = softWrap
                existing.maximumLines = maximumLines
                existing.overflow = overflow
                existing.invalidateMetrics()
            }
        }
        clearFontObservation()
        requestLayout()
        invalidate()
        queueTranslationSemanticsInvalidation()
    }

    private fun queueTranslationSemanticsInvalidation() {
        if (isAttachedToWindow && !translationSemanticsPosted) {
            translationSemanticsPosted = true
            postOnAnimation(translationSemanticsInvalidation)
        }
    }

    private fun paragraphDirection(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun ensureIntrinsics(cache: ParagraphCache, direction: LayoutDirection): ParagraphIntrinsics {
        val existing = cache.intrinsics
        if (existing != null && cache.previousDirection == direction && !existing.hasStaleResolvedFonts) return existing
        val created = ParagraphIntrinsics(
            text = cache.text,
            style = resolveDefaults(cache.style, direction),
            annotations = emptyList(),
            placeholders = emptyList(),
            density = cache.density,
            fontFamilyResolver = cache.resolver,
        )
        cache.intrinsics = created
        cache.paragraph = null
        cache.previousConstraints = null
        cache.previousDirection = direction
        cache.intrinsicHeightWidth = -1
        cache.intrinsicHeight = -1
        return created
    }

    private fun observeResolvedFonts(current: ParagraphIntrinsics) {
        if (!isAttachedToWindow || observedIntrinsics === current) return
        clearFontObservation()
        observedIntrinsics = current
        val invalidateFonts = {
            // Emoji readiness can arrive during measure; defer until that traversal finishes.
            post {
                if (isAttachedToWindow && observedIntrinsics === current) {
                    requestLayout()
                    invalidate()
                }
            }
            Unit
        }
        fontObservation = current.observeFontChanges(invalidateFonts)
        // Close the completion-before-registration race without readiness polling.
        if (current.hasStaleResolvedFonts) invalidateFonts()
    }

    private fun clearFontObservation() {
        observedIntrinsics = null
        fontObservation?.close()
        fontObservation = null
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
        val cache = cacheForMeasure()
        val currentIntrinsics = ensureIntrinsics(cache, direction)
        val previousConstraints = cache.previousConstraints
        val layoutChanged = needsNativeParagraphRelayout(
            previousConstraints, constraints, cache.paragraph?.height,
            cache.paragraph?.didExceedMaxLines == true,
        )
        val currentParagraph = if (!layoutChanged) {
            cache.paragraph!!
        } else {
            layoutParagraph(cache, currentIntrinsics, constraints).also {
                cache.paragraph = it
            }
        }
        if (layoutChanged || previousConstraints != constraints) {
            val width = if (layoutChanged) currentParagraph.width
                else min(currentParagraph.maxIntrinsicWidth, currentParagraph.width)
            cache.layoutSize = constraints.constrain(
                IntSize(ceilPixel(width), ceilPixel(currentParagraph.height)),
            )
            cache.didOverflow = cache.overflow != TextOverflow.Visible &&
                (cache.layoutSize.width < currentParagraph.width || cache.layoutSize.height < currentParagraph.height)
            cache.previousConstraints = constraints
        }
        if (layoutChanged) {
            // The released node owns one baseline cache, updated only for a changed layout, even
            // when switching between its original/substitution caches. Preserve that behavior.
            firstBaseline = currentParagraph.firstBaseline.roundToInt()
            lastBaseline = currentParagraph.lastBaseline.roundToInt()
        }
        measuredContentSize = cache.layoutSize
        clipParagraph = cache.didOverflow
        setMeasuredDimension(
            resolveSizeAndState(measuredContentSize.width + horizontalPadding, widthMeasureSpec, 0),
            resolveSizeAndState(measuredContentSize.height + verticalPadding, heightMeasureSpec, 0),
        )
        observeResolvedFonts(currentIntrinsics)
    }

    private fun layoutParagraph(cache: ParagraphCache, current: ParagraphIntrinsics, constraints: Constraints): Paragraph {
        val widthMatters = cache.softWrap || cache.overflow.isEllipsis()
        val availableWidth = if (widthMatters && constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val paragraphWidth = if (constraints.minWidth == availableWidth) availableWidth
            else ceilPixel(current.maxIntrinsicWidth).coerceIn(constraints.minWidth, availableWidth)
        return Paragraph(
            paragraphIntrinsics = current,
            constraints = Constraints.fitPrioritizingWidth(
                minWidth = 0, maxWidth = paragraphWidth,
                minHeight = 0, maxHeight = constraints.maxHeight,
            ),
            maxLines = if (!cache.softWrap && cache.overflow.isEllipsis()) 1 else cache.maximumLines,
            overflow = cache.overflow,
        )
    }

    /** Intrinsic queries are explicit because Android View has no Compose-style intrinsic protocol. */
    fun minIntrinsicTextWidth(): Int =
        ceilPixel(ensureIntrinsics(cacheForMeasure(), paragraphDirection()).minIntrinsicWidth)

    fun maxIntrinsicTextWidth(): Int =
        ceilPixel(ensureIntrinsics(cacheForMeasure(), paragraphDirection()).maxIntrinsicWidth)

    fun intrinsicTextHeight(widthPx: Int): Int {
        require(widthPx >= 0)
        val cache = cacheForMeasure()
        val current = ensureIntrinsics(cache, paragraphDirection())
        // Check density, direction and resolved-font freshness before reusing this cache's height.
        if (cache.intrinsicHeightWidth == widthPx) return cache.intrinsicHeight
        val height = ceilPixel(layoutParagraph(cache, current, Constraints(maxWidth = widthPx)).height)
        cache.intrinsicHeightWidth = widthPx
        cache.intrinsicHeight = height
        return height
    }

    override fun getBaseline(): Int = if (firstBaseline >= 0) paddingTop + firstBaseline else -1

    val lastTextBaseline: Int
        get() = if (lastBaseline >= 0) paddingTop + lastBaseline else -1

    /** Read-only geometry for the parity harness, not a second layout. */
    val currentParagraph: Paragraph?
        get() = activeCache.paragraph

    /**
     * Pinned ParagraphLayoutCache.slowCreateTextLayoutResultOrNull:366-402 creates a separate
     * original MultiParagraph for semantics, using the last ORIGINAL cache's max constraints and
     * maxLines (not the draw path's finalMaxLines). Do not use currentParagraph as an approximation.
     * Before the original layout has been measured, the source returns null rather than measuring
     * text speculatively. This function is called only for line/page traversal or character extras.
     */
    internal fun createOriginalSemanticsParagraph(): MultiParagraph? {
        originalCache.paragraph ?: return null
        originalCache.intrinsics ?: return null
        originalCache.previousDirection ?: return null
        val constraints = originalCache.previousConstraints ?: return null
        val semanticsStyle = textStyle.merge(color = textColor)
        return MultiParagraph(
            intrinsics = MultiParagraphIntrinsics(
                annotatedString = AnnotatedString(originalCache.text),
                // The source semantics path passes unresolved style directly; the draw path above
                // separately resolves defaults against layout direction. Do not conflate them.
                style = semanticsStyle,
                placeholders = emptyList(),
                density = originalCache.density,
                fontFamilyResolver = originalCache.resolver,
            ),
            constraints = Constraints(
                maxWidth = constraints.maxWidth,
                maxHeight = constraints.maxHeight,
            ),
            maxLines = originalCache.maximumLines,
            overflow = originalCache.overflow,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = activeCache.paragraph ?: return
        val saved = canvas.save()
        try {
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            if (clipParagraph) canvas.clipRect(0, 0, measuredContentSize.width, measuredContentSize.height)
            current.paint(canvas, textColor, textStyle.textDecoration ?: TextDecoration.None)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    override fun getAccessibilityClassName(): CharSequence = TextView::class.java.name

    override fun getParentForAccessibility(): android.view.ViewParent? {
        // A merged source row exposes its original Text directly. Marker/Surface/content Views
        // remain layout-only ancestors, even when a service requests non-important native Views.
        var ancestor = parent as? View
        while (ancestor != null) {
            if (ancestor is android.view.ViewGroup && ancestor is NativeMergedRowAccessibilityHost &&
                ancestor.hasAccessibilitySemanticNode) return ancestor
            if (ancestor.isImportantForAccessibility) break
            ancestor = ancestor.parent as? View
        }
        return super.getParentForAccessibility()
    }

    /** Structural parents use the same admission as this node's provider. */
    internal fun isAccessibilityNodePresent(): Boolean = sourceAccessibilityNode?.invoke()?.present != false

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (isAccessibilityNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = TextView::class.java.name
        info.text = label
        info.isEditable = false
        textAccessibility?.populate(info)
        sourceAccessibilityNode?.invoke()?.populate(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        textAccessibility?.performAction(action, arguments)
            ?: super.performAccessibilityAction(action, arguments)

    override fun addExtraDataToAccessibilityNodeInfo(
        info: AccessibilityNodeInfo,
        extraDataKey: String,
        arguments: Bundle?,
    ) {
        if (textAccessibility?.addExtraData(info, extraDataKey, arguments) != true) {
            super.addExtraDataToAccessibilityNodeInfo(info, extraDataKey, arguments)
        }
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

    @RequiresApi(31)
    override fun onCreateViewTranslationRequest(
        supportedFormats: IntArray,
        requestsCollector: Consumer<ViewTranslationRequest>,
    ) {
        // Like the released delegate, request the original plain String. Do not call super too:
        // the framework collector accepts one request for an ordinary native View.
        translationApi31?.createRequest(requestsCollector)
    }

    @RequiresApi(31)
    override fun onViewTranslationResponse(response: ViewTranslationResponse) {
        super.onViewTranslationResponse(response)
        translationApi31?.receiveResponse(response)
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

/** Pinned ParagraphLayoutCache.newLayoutWillBeDifferent after font/direction invalidation. */
internal fun needsNativeParagraphRelayout(
    previous: Constraints?,
    current: Constraints,
    previousParagraphHeight: Float?,
    previousDidExceedMaxLines: Boolean,
): Boolean {
    if (previous == null || previousParagraphHeight == null) return true
    if (current == previous) return false
    if (current.maxWidth != previous.maxWidth || current.minWidth != previous.minWidth) return true
    return current.maxHeight < previousParagraphHeight || previousDidExceedMaxLines
}
