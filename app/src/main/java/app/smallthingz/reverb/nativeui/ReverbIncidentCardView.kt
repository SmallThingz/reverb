package app.smallthingz.reverb.nativeui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.IncidentPresentation
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingIncident
import kotlin.math.roundToInt

/** Exact released typography roles; the host supplies its current immutable font context. */
internal data class IncidentCardAppearance(
    val palette: ReverbPalette,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val density: Density,
    val fontResolver: FontFamily.Resolver,
)

internal data class IncidentCardActions(
    val toggleAcknowledged: (RecordingIncident) -> Unit,
    val delete: (RecordingIncident) -> Unit,
    val copy: (IncidentPresentation) -> Unit,
)

/** Referential identity survives same-key enrichment/rebinding, never a different incident. */
internal class IncidentCardMenuIdentity(val presentationKey: String)

internal data class IncidentCardMenuRequest(
    val identity: IncidentCardMenuIdentity,
    val anchor: View,
    val row: IncidentPresentation,
    val canDelete: Boolean,
    val copy: () -> Unit,
    val delete: () -> Unit,
)

/**
 * Owns only this card's popup. show creates on first use; update retains an existing
 * popup through dismissal/exit animation. release must never dismiss another owner.
 *
 * Copy is always present; Delete only when canDelete. The host retains the released
 * 18 dp popup / 12 dp item corners, labels, palette, copy feedback and exit behavior.
 */
internal interface IncidentCardMenuHost {
    fun show(request: IncidentCardMenuRequest)
    fun update(request: IncidentCardMenuRequest)
    fun release(identity: IncidentCardMenuIdentity)
}

/**
 * Native binding for the released IncidentCard only. No history loading, formatting,
 * storage, acknowledgement mutation, popup implementation, or list animation lives here.
 * Each incident remains a separate card; this must not join neighboring incident rows.
 */
internal class ReverbIncidentCardView(
    context: Context,
    initialAppearance: IncidentCardAppearance,
    private val menuHost: IncidentCardMenuHost,
) : FrameLayout(context), NativePointerLayerShape {
    private var appearance = initialAppearance
    private var binding: Binding? = null
    private var menuCreated = false
    private var menuLifetime = Any()
    private val surface = ReverbGroupLayout(context, cornerRadiusDp = 18f)
    private val body = IncidentBody(context, initialAppearance,
        onPrimaryAction = { performPrimaryAction(); true },
        onOpenMenu = { openMenu(); true })
    private val bodyIndication = ReverbRowIndication.install(this, initialAppearance.palette.ink) {
        ValueAnimator.getDurationScale()
    }
    private val checkIndication = ReverbRowIndication.install(body.checkTarget, initialAppearance.palette.ink) {
        ValueAnimator.getDurationScale()
    }
    /** Current scroll recognizer interest, supplied by the containing list for this down. */
    var parentInterestedInDown: (MotionEvent) -> Boolean = { false }
    var pointerInputRoot: View? = null
    private val bodyInput = createInput(body, this, bodyIndication, checkbox = false)
    private val checkInput = createInput(body.checkTarget, body.checkTarget, checkIndication, checkbox = true)
    private val outerClip = Path()
    private val outerBounds = RectF()

    override fun containsPointerLayer(x: Float, y: Float): Boolean = reverbGroupGeometry(
        width.toFloat(), height.toFloat(), appearance.density.density, cornerRadiusDp = 18f,
    ).contains(x, y)

    private class Binding(
        val row: IncidentPresentation,
        val canMutate: Boolean,
        val actions: IncidentCardActions,
        val menuIdentity: IncidentCardMenuIdentity,
    )

    init {
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, outerRadius())
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        surface.addRow(body, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.setOnClickListener { performPrimaryAction() }
        body.setOnLongClickListener { openMenu(); true }
        body.checkTarget.setOnClickListener { performPrimaryAction() }
        body.checkTarget.setOnLongClickListener { openMenu(); true }
        body.input = bodyInput
        body.checkTarget.input = checkInput
        body.checkTarget.geometryChanged = {
            checkIndication.onMeasured(body.checkTarget.width, body.checkTarget.height, appearance.density.density)
        }
    }

    /** Main-thread binding of an already-prepared revision. No timestamps are formatted here. */
    fun bind(row: IncidentPresentation, canMutate: Boolean, actions: IncidentCardActions) {
        val previous = binding
        val identity = if (previous?.row?.key == row.key) {
            previous.menuIdentity
        } else {
            if (previous != null) cancelInputOwnership()
            releaseMenu()
            IncidentCardMenuIdentity(row.key)
        }
        binding = Binding(row, canMutate, actions, identity)
        bindInputs()
        body.bind(row, canMutate)
        applyAppearance()
        visibility = VISIBLE
        if (menuCreated) menuHost.update(menuRequest(identity))
    }

    /** Call on theme/configuration/font-resolver updates, not during drawing. */
    fun setAppearance(value: IncidentCardAppearance) {
        appearance = value
        body.setAppearance(value)
        rebuildOuterClip()
        bodyIndication.updateColor(value.palette.ink)
        checkIndication.updateColor(value.palette.ink)
        bodyIndication.onMeasured(width, height, value.density.density)
        checkIndication.onMeasured(body.checkTarget.width, body.checkTarget.height, value.density.density)
        applyAppearance()
    }

    /** Explicit recycler/disposal boundary; stale popup callbacks become inert. */
    fun unbind() {
        cancelInputOwnership()
        releaseMenu()
        binding = null
        body.clear()
        visibility = INVISIBLE
    }

    private fun applyAppearance() {
        val current = binding ?: return
        val colors = appearance.palette
        val unacknowledged = !current.row.incident.acknowledged
        val border = if (unacknowledged) replaceAlpha(colors.error, 0.45f) else colors.outlineVariant
        val fill = if (unacknowledged) replaceAlpha(colors.errorContainer, 0.42f) else colors.surfaceContainerHigh
        surface.setPalette(fill, border)
        // Source border.copy(alpha = .55f) REPLACES .45; it does not multiply it.
        body.setColors(colors.ink, colors.muted, replaceAlpha(border, 0.55f),
            if (unacknowledged) colors.error else colors.muted)
    }

    private fun performPrimaryAction() {
        val current = binding ?: return
        if (current.canMutate) current.actions.toggleAcknowledged(current.row.incident) else openMenu()
    }

    private fun openMenu() {
        val current = binding ?: return
        menuCreated = true
        menuHost.show(menuRequest(current.menuIdentity))
    }

    private fun menuRequest(identity: IncidentCardMenuIdentity): IncidentCardMenuRequest {
        val current = checkNotNull(binding)
        check(current.menuIdentity === identity)
        val lifetime = menuLifetime
        return IncidentCardMenuRequest(identity, this, current.row, current.canMutate,
            copy = {
                binding?.takeIf { menuCreated && menuLifetime === lifetime &&
                    isAttachedToWindow && it.menuIdentity === identity }
                    ?.let { it.actions.copy(it.row) }
            },
            delete = {
                binding?.takeIf { menuCreated && menuLifetime === lifetime &&
                    isAttachedToWindow && it.menuIdentity === identity && it.canMutate }
                    ?.let { it.actions.delete(it.row.incident) }
            })
    }

    private fun releaseMenu() {
        val current = binding
        val created = menuCreated
        menuCreated = false
        menuLifetime = Any()
        if (created && current != null) menuHost.release(current.menuIdentity)
    }

    private fun cancelInputOwnership() {
        cancelPendingInputEvents()
        bodyInput.bind(null)
        checkInput.bind(null)
    }

    private fun bindInputs() {
        val current = binding ?: return
        bodyInput.bind(NativeClickBinding(current.row.key, true,
            onClick = { body.performClick() }, onLongClick = ::openMenu))
        checkInput.bind(NativeClickBinding(current.row.key, true,
            onClick = { body.checkTarget.performClick() }, onLongClick = ::openMenu))
        bodyInput.focusChanged(body.isFocused)
        checkInput.focusChanged(body.checkTarget.isFocused)
    }

    private fun createInput(target: View, drawHost: View, indication: ReverbRowIndication,
        checkbox: Boolean): ReverbClickController {
        val environment = object : NativeClickEnvironment {
            override val nativeInputRoot get() = pointerInputRoot
            override val minimumTouchWidthPx get() = 48f * appearance.density.density
            override val minimumTouchHeightPx get() = minimumTouchWidthPx
            override fun admitDown(event: MotionEvent): Boolean {
                if (checkbox) return body.checkTarget.acceptsPointer(event)
                return reverbGroupGeometry(target.width.toFloat(), target.height.toFloat(),
                    appearance.density.density, cornerRadiusDp = 18f).acceptsPointer(
                    event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
            }
            override fun interestedParent(event: MotionEvent) = parentInterestedInDown(event)
            override fun longPressOwnershipChanged(owns: Boolean) {
                target.parent?.requestDisallowInterceptTouchEvent(owns)
            }
        }
        return ReverbClickController(target, environment, ReverbRowIndicationSink(indication) { x, y, out ->
            // Body, group content and surface have no local transforms; item animation transforms
            // the shared outer card. Preserve the original hotspot through their layout offsets.
            var px = x
            var py = y
            var child = target
            while (child !== drawHost) {
                val parent = child.parent as View
                px += child.left - parent.scrollX
                py += child.top - parent.scrollY
                child = parent
            }
            out.set(px, py)
        })
    }

    private fun outerRadius(): Float =
        minOf(18f * resources.displayMetrics.density, minOf(width, height) / 2f)

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        rebuildOuterClip()
        bodyIndication.onMeasured(width, height, appearance.density.density)
    }

    private fun rebuildOuterClip() {
        outerBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        outerClip.rewind()
        outerClip.addRoundRect(outerBounds, outerRadius(), outerRadius(), Path.Direction.CW)
        invalidateOutline()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            super.draw(canvas)
        } else {
            val saved = canvas.save()
            try { canvas.clipPath(outerClip); super.draw(canvas) }
            finally { canvas.restoreToCount(saved) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Source combinedClickable wraps Surface; the single outer foreground covers its border.
        bindInputs()
    }

    override fun onDetachedFromWindow() {
        // A detached row owns no popup/input indication. The binding can be retained for
        // reattachment, but callbacks from its retired popup must be disposed by the host.
        cancelInputOwnership()
        releaseMenu()
        super.onDetachedFromWindow()
    }
}

private class IncidentBody(
    context: Context,
    initial: IncidentCardAppearance,
    onPrimaryAction: () -> Boolean,
    onOpenMenu: () -> Boolean,
) : ViewGroup(context), NativeClickPhaseHost, NativeMergedRowAccessibilityHost {
    private var endpoint: NativeClickPhaseEndpoint? = null
    override val nativeClickEndpoint get() = checkNotNull(endpoint)
    var input: ReverbClickController? = null
        set(value) { field = value; endpoint = value?.let(::ReverbClickPhaseEndpoint) }
    private var appearance = initial
    private var clickLabel: String? = null
    private val date = label(initial.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    private val summary = label(initial.bodyMedium)
    private val cause = label(initial.bodyMedium)
    private val runtime = label(initial.bodySmall)
    private val age = label(initial.bodySmall)
    private val description = label(initial.bodySmall)
    private val divider = IncidentDivider(context)
    val checkTarget = IncidentCheckTarget(context, initial.density.density, onPrimaryAction, onOpenMenu)
    private val details = listOf<View>(divider, cause, runtime, age, description)
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(this, NativeMergedRowRole.BUTTON, {
        NativeMergedRowAccessibilityState(true, null, clickLabel,
            listOf(date, summary, checkTarget, cause, runtime, age, description).filter { it.visibility == VISIBLE },
            longClickable = true, rolePresent = false)
    }, onPrimaryAction, onOpenMenu)

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        accessibilityDelegate = mergedRowAccessibility
        listOf(date, summary, cause, runtime, age, description, divider, checkTarget).forEach { addView(it) }
        clear()
    }

    private fun label(style: TextStyle) = ReverbParagraphView(context, style, appearance.density,
        appearance.fontResolver, appearance.palette.ink).apply {
        // Source keeps the original unmerged Text children under its role-less merging card.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, false)
    }

    fun bind(row: IncidentPresentation, canMutate: Boolean) {
        date.setText(row.date)
        summary.setText(row.stopSummary)
        cause.setText(row.cause)
        runtime.setText(row.runtime)
        age.setText(row.age)
        description.setText(row.description ?: "")
        divider.visibility = if (row.hasDetails) VISIBLE else GONE
        cause.visibility = if (row.cause.isNotEmpty()) VISIBLE else GONE
        runtime.visibility = if (row.runtime.isNotEmpty()) VISIBLE else GONE
        age.visibility = if (row.age.isNotEmpty()) VISIBLE else GONE
        // Empty but non-null descriptions still consume the source Text's line height.
        description.visibility = if (row.description != null) VISIBLE else GONE
        clickLabel = if (canMutate) null else context.getString(R.string.open_options)
        checkTarget.bind(row.incident.acknowledged, canMutate)
        requestLayout()
    }

    fun clear() {
        clickLabel = null
        listOf(date, summary, cause, runtime, age, description).forEach { it.setText("") }
        details.forEach { it.visibility = GONE }
        checkTarget.clear()
        requestLayout()
    }

    fun setAppearance(value: IncidentCardAppearance) {
        appearance = value
        date.setTypography(value.bodyLarge.copy(fontWeight = FontWeight.SemiBold), value.density, value.fontResolver)
        summary.setTypography(value.bodyMedium, value.density, value.fontResolver)
        cause.setTypography(value.bodyMedium, value.density, value.fontResolver)
        listOf(runtime, age, description).forEach { it.setTypography(value.bodySmall, value.density, value.fontResolver) }
        checkTarget.setDensity(value.density.density)
        requestLayout()
    }

    fun setColors(ink: Int, muted: Int, line: Int, icon: Int) {
        date.setTextColor(ink)
        summary.setTextColor(muted)
        cause.setTextColor(ink)
        listOf(runtime, age, description).forEach { it.setTextColor(muted) }
        divider.setColor(line)
        checkTarget.setTint(icon)
    }

    private fun dp(value: Int): Int = (value * appearance.density.density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val paddingX = dp(16)
        val paddingY = dp(14)
        val innerWidth = (width - paddingX * 2).coerceAtLeast(0)
        val checkWidth = minOf(dp(46), innerWidth)
        checkTarget.measure(MeasureSpec.makeMeasureSpec(checkWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(46), MeasureSpec.EXACTLY))
        val textWidth = (innerWidth - checkWidth - dp(10)).coerceAtLeast(0)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        date.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.AT_MOST), unbounded)
        summary.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.AT_MOST), unbounded)
        var height = maxOf(date.measuredHeight + summary.measuredHeight, checkTarget.measuredHeight)
        details.forEach {
            if (it.visibility == GONE) return@forEach
            val childWidth = if (it === divider) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
            it.measure(MeasureSpec.makeMeasureSpec(innerWidth, childWidth), unbounded)
            height += dp(7) + it.measuredHeight
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height + paddingY * 2, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val paddingX = dp(16)
        var y = dp(14)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val checkX = if (rtl) paddingX else width - paddingX - checkTarget.measuredWidth
        checkTarget.layout(checkX, y, checkX + checkTarget.measuredWidth, y + checkTarget.measuredHeight)
        fun layoutText(view: View, textY: Int) {
            val x = if (rtl) width - paddingX - view.measuredWidth else paddingX
            view.layout(x, textY, x + view.measuredWidth, textY + view.measuredHeight)
        }
        layoutText(date, y)
        layoutText(summary, y + date.measuredHeight)
        y += maxOf(date.measuredHeight + summary.measuredHeight, checkTarget.measuredHeight)
        details.forEach {
            if (it.visibility == GONE) return@forEach
            y += dp(7)
            layoutText(it, y)
            y += it.measuredHeight
        }
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        requestLayout()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = input?.touch(event) == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (isIncidentActivationKey(keyCode)) input?.key(event) == true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isIncidentActivationKey(keyCode)) input?.key(event) == true else super.onKeyUp(keyCode, event)

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val accessibilityHandled = super.onHoverEvent(event)
        return input?.hover(event) == true || accessibilityHandled
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        input?.focusChanged(gainFocus)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input?.attached()
        input?.focusChanged(isFocused)
    }

    override fun onDetachedFromWindow() {
        input?.detached()
        super.onDetachedFromWindow()
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun addChildrenForAccessibility(children: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(children)
}

private class IncidentCheckTarget(
    context: Context,
    initialDensity: Float,
    onPrimaryAction: () -> Boolean,
    onOpenMenu: () -> Boolean,
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost {
    private var endpoint: NativeClickPhaseEndpoint? = null
    override val nativeClickEndpoint get() = checkNotNull(endpoint)
    var input: ReverbClickController? = null
        set(value) { field = value; endpoint = value?.let(::ReverbClickPhaseEndpoint) }
    var geometryChanged: () -> Unit = {}
    private var density = initialDensity
    private var checked = false
    private var tint = 0
    private val glyph = OnboardingIconView(context, ReverbIcon.Unchecked, tint)
    private var clickLabel: String? = null
    private var hitGeometry = reverbGroupGeometry(0f, 0f, density, cornerRadiusDp = 23f)
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(this, NativeMergedRowRole.BUTTON, {
        NativeMergedRowAccessibilityState(true, null, clickLabel,
            if (glyph.contentDescription != null) listOf(glyph) else emptyList(),
            longClickable = true, rolePresent = false)
    }, onPrimaryAction, onOpenMenu)

    override fun containsPointerLayer(x: Float, y: Float): Boolean = hitGeometry.contains(x, y)

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        accessibilityDelegate = mergedRowAccessibility
        addView(glyph)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, minOf(view.width, view.height) / 2f)
            }
        }
    }

    fun bind(value: Boolean, canMutate: Boolean) {
        checked = value
        glyph.contentDescription = if (canMutate) context.getString(
            if (checked) R.string.incident_mark_unchecked else R.string.incident_mark_checked) else null
        glyph.bind(if (checked) ReverbIcon.Checked else ReverbIcon.Unchecked, tint)
        glyph.importantForAccessibility = if (canMutate) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setScreenReaderFocusable(glyph, false)
        clickLabel = if (canMutate) null else context.getString(R.string.open_options)
        invalidate()
    }

    fun clear() { glyph.contentDescription = null; clickLabel = null }

    fun setDensity(value: Float) {
        if (density == value) return
        density = value
        updateGeometry()
        invalidate()
    }

    fun setTint(value: Int) {
        tint = value
        glyph.bind(if (checked) ReverbIcon.Checked else ReverbIcon.Unchecked, value)
        invalidate()
    }

    fun acceptsPointer(event: MotionEvent, x: Float = event.x, y: Float = event.y): Boolean =
        hitGeometry.acceptsPointer(x, y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, 48f * density)

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        updateGeometry()
        geometryChanged()
    }

    private fun updateGeometry() {
        hitGeometry = reverbGroupGeometry(width.toFloat(), height.toFloat(), density, cornerRadiusDp = 23f)
        invalidateOutline()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !acceptsPointer(event)) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = input?.touch(event) == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (isIncidentActivationKey(keyCode)) input?.key(event) == true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isIncidentActivationKey(keyCode)) input?.key(event) == true else super.onKeyUp(keyCode, event)

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val accessibilityHandled = super.onHoverEvent(event)
        return input?.hover(event) == true || accessibilityHandled
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        input?.focusChanged(gainFocus)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input?.attached()
        input?.focusChanged(isFocused)
    }

    override fun onDetachedFromWindow() {
        input?.detached()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = (22f * density).roundToInt()
        glyph.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = ((width - glyph.measuredWidth) / 2f).roundToInt()
        val y = ((height - glyph.measuredHeight) / 2f).roundToInt()
        glyph.layout(x, y, x + glyph.measuredWidth, y + glyph.measuredHeight)
    }

    override fun getAccessibilityClassName(): CharSequence = View::class.java.name

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun addChildrenForAccessibility(children: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(children)
}

private class IncidentDivider(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setColor(color: Int) { if (paint.color != color) { paint.color = color; invalidate() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resources.displayMetrics.density.roundToInt())
    }
    override fun onDraw(canvas: Canvas) {
        val thickness = resources.displayMetrics.density
        paint.strokeWidth = thickness
        canvas.drawLine(0f, thickness / 2f, width.toFloat(), thickness / 2f, paint)
    }
}

private fun replaceAlpha(color: Int, alpha: Float): Int =
    (color and 0x00ffffff) or (((alpha * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 24)

private fun isIncidentActivationKey(key: Int): Boolean = when (key) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
    else -> false
}
