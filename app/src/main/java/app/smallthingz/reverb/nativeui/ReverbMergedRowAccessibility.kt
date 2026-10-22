package app.smallthingz.reverb.nativeui

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.MainThread
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect as ComposeRect
import app.smallthingz.nativeui.unit.roundToIntRect
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal enum class NativeMergedRowRole { BUTTON, SWITCH, TAB }

internal interface NativeMergedRowAccessibilityHost {
    val mergedRowAccessibility: ReverbMergedRowAccessibility
    val mergesAccessibilityDescendants: Boolean get() = true
    val hasAccessibilitySemanticNode: Boolean get() = true
}

internal data class NativeMergedRowAccessibilityState(
    val enabled: Boolean,
    val checked: Boolean?,
    val clickLabel: CharSequence?,
    val orderedChildren: List<View>,
    val selected: Boolean? = null,
    val contentDescription: CharSequence? = null,
    val clickable: Boolean = true,
    val longClickable: Boolean = false,
    val longClickLabel: CharSequence? = null,
    val rolePresent: Boolean = true,
)

/** Existing row facts, read on demand; no copied text or second semantic/render tree. */
internal data class NativeMergedRowAccessibilitySnapshot(
    val present: Boolean,
    val hidden: Boolean,
    val visibleToUser: Boolean,
    val enabled: Boolean,
    val checked: Boolean?,
    val clickLabel: CharSequence?,
    /** Real semantic Views only, in source drawing order: marker/title/body/status as present. */
    val orderedChildren: List<View>,
    val originalChildCount: Int,
    /** Source adjusted touch bounds; not an integer native visible-rect approximation. */
    val parentBoundsInScreen: Rect,
    /** Source parent.boundsInRoot rounded before screen mapping; no sibling-occlusion subtraction. */
    val fakeRoleBoundsInScreen: Rect,
    val selected: Boolean?,
    val contentDescription: CharSequence?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val longClickLabel: CharSequence?,
    val rolePresent: Boolean,
)

/**
 * Source fake-node geometry: SemanticsOwner183–194 and native delegate578–609.
 * Round parent clipped root bounds BEFORE root-to-screen mapping, then floor/ceil screen extents.
 * The source fallback for an unplaced parent is a10x10 root-space rectangle.
 */
internal fun nativeMergedRoleBoundsInScreen(
    parentGeometry: PlainTextAccessibilityGeometry,
    parentPlaced: Boolean,
): Rect {
    val root = if (parentPlaced) parentGeometry.visibleBoundsInRoot
        else app.smallthingz.nativeui.geometry.Rect(0f, 0f, 10f, 10f)
    val rounded = root.roundToIntRect()
    val first = parentGeometry.rootToScreen(Offset(rounded.left.toFloat(), rounded.top.toFloat()))
    val second = parentGeometry.rootToScreen(Offset(rounded.right.toFloat(), rounded.bottom.toFloat()))
    return Rect(
        floor(min(first.x, second.x)).toInt(), floor(min(first.y, second.y)).toInt(),
        ceil(max(first.x, second.x)).toInt(), ceil(max(first.y, second.y)).toInt(),
    )
}

/**
 * Direct row delegate plus one non-drawing semantic child representing Compose's role-only node.
 *
 * A provider with mixed real/virtual children is NOT safe: Android16
 * AccessibilityInteractionController1665–1684 passes every child virtual ID back to one provider,
 * so real-child HOST_VIEW_ID values resolve to the row again. Keep a wholly native View hierarchy.
 *
 * Owner installs this delegate, appends roleView to the row, and measures/layouts that child to the
 * row's full bounds after ordinary children. It draws nothing, accepts no input and adds no layer,
 * outline, background or pixel offset. Full bounds retain native accessibility-focus geometry.
 * Remove old row node overrides that synthesize joined text, status descriptions or parent roles.
 *
 * invokeSourceClick calls the current bound action directly, NOT View.performClick: this delegate
 * emits the one source click event after the callback. Native Android owns all accessibility focus.
 */
@MainThread
internal class ReverbMergedRowAccessibility(
    private val host: ViewGroup,
    private val role: NativeMergedRowRole,
    private val sourceState: () -> NativeMergedRowAccessibilityState,
    private val invokeSourceClick: () -> Boolean,
    private val invokeSourceLongClick: (() -> Boolean)? = null,
) : View.AccessibilityDelegate() {
    val roleView: View = RoleView()
    private var geometry: NativePlainTextViewGeometry? = null
    private var sourceExposure: (() -> Boolean)? = null
    private var geometryToken: Any? = null
    private var sourceNode: (() -> NativeSourceAccessibilityNode)? = null
    private var sourceChildPresent: ((View) -> Boolean)? = null

    fun sourceOrderedChildren(): List<View> = sourceState().orderedChildren

    fun bindGeometry(value: NativePlainTextViewGeometry, exposed: () -> Boolean): AutoCloseable =
        bindGeometry(value, null, null, exposed)

    fun bindGeometry(value: NativePlainTextViewGeometry,
                     node: (() -> NativeSourceAccessibilityNode)?,
                     childPresent: ((View) -> Boolean)?,
                     exposed: () -> Boolean): AutoCloseable {
        val token = Any()
        geometryToken = token
        geometry = value
        sourceExposure = exposed
        sourceNode = node
        sourceChildPresent = childPresent
        return AutoCloseable {
            if (geometryToken === token) {
                geometryToken = null
                geometry = null
                sourceExposure = null
                sourceNode = null
                sourceChildPresent = null
            }
        }
    }

    private fun snapshot(): NativeMergedRowAccessibilitySnapshot {
        val state = sourceState()
        val mapped = geometry?.capture(host, Offset.Zero,
            ComposeRect(0f, 0f, host.width.toFloat(), host.height.toFloat()))
        val node = sourceNode?.invoke()
        val hidden = sourceExposure?.invoke() != true || !host.isShown || hasTransparentAncestor(host)
        val present = mapped != null && host.isAttachedToWindow && !mapped.visibleBoundsInRoot.isEmpty
        val bounds = mapped?.let { nativeMergedRoleBoundsInScreen(it, host.isLaidOut) } ?: Rect()
        // Released onboarding rows are at least64dp high and fill the available width: their
        // source minimum48dp touch expansion is zero. Rows do not overlap. Under these explicit
        // page invariants the adjusted touch bounds equal the parent clipped rectangle.
        return NativeMergedRowAccessibilitySnapshot(node?.present ?: present, node?.hidden ?: hidden,
            node?.visibleToUser ?: (present && !hidden), state.enabled, state.checked, state.clickLabel,
            state.orderedChildren.filter { sourceChildPresent?.invoke(it) != false }, state.orderedChildren.size,
            node?.boundsInScreen ?: bounds, bounds, state.selected, state.contentDescription,
            state.clickable, state.longClickable, state.longClickLabel, state.rolePresent)
    }

    fun isNodePresent(): Boolean = host.isAttachedToWindow && snapshot().present

    /**
     * The actual ViewGroup must forward its public addChildrenForAccessibility here. Replacing
     * guessed leaf IDs after super.onInitializeAccessibilityNodeInfo is insufficient: with
     * FLAG_INCLUDE_NOT_IMPORTANT_VIEWS, Android initially advertises the marker/content wrappers,
     * and their same Text descendant would then also be advertised directly by this row.
     * Keep one semantic parent and one route to each original child, without changing View layout.
     */
    fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        val current = snapshot()
        if (!current.present) return
        outChildren.addAll(current.orderedChildren)
        if (current.rolePresent && current.originalChildCount > 0) outChildren.add(roleView)
    }

    override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(view, info)
        val current = snapshot()
        // Parent action node has no own Text/ContentDescription in the released rows.
        info.className = "android.view.View"
        info.text = null
        info.contentDescription = current.contentDescription
        val compat = AccessibilityNodeInfoCompat.wrap(info)
        compat.roleDescription = null
        compat.stateDescription = if (role == NativeMergedRowRole.SWITCH) host.context.getString(
            if (current.checked == true) app.smallthingz.nativeui.R.string.state_on
            else app.smallthingz.nativeui.R.string.state_off) else null
        info.isCheckable = role == NativeMergedRowRole.SWITCH
        info.isChecked = role == NativeMergedRowRole.SWITCH && current.checked == true
        info.isSelected = current.selected == true
        info.isEnabled = current.enabled
        // Pinned delegate825-839 suppresses only selected Tab advertisement. The source OnClick
        // still exists, owns focus, and remains invocable directly while enabled.
        info.isClickable = current.clickable && !(role == NativeMergedRowRole.TAB && current.selected == true)
        info.isLongClickable = current.longClickable
        info.isFocusable = current.enabled && current.clickable
        info.isFocused = current.enabled && host.isFocused
        info.isImportantForAccessibility = !current.hidden
        info.isVisibleToUser = current.present && current.visibleToUser
        info.isScreenReaderFocusable = !current.hidden && (current.clickable || current.originalChildCount == 0)
        info.maxTextLength = -1
        info.setBoundsInScreen(current.parentBoundsInScreen)
        info.movementGranularities = 0
        info.actionList.toList().forEach(info::removeAction)
        addAccessibilityFocusAction(info, host.isAccessibilityFocused)
        if (info.isFocusable) info.addAction(if (info.isFocused)
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
        if (current.enabled && info.isClickable) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_CLICK, current.clickLabel))
        if (current.enabled && current.longClickable) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_LONG_CLICK, current.longClickLabel))
        // ViewGroup's super call obtained the exact source children through the public override
        // above. Do not append them again or reintroduce their non-semantic layout wrappers.
        if (current.rolePresent && current.originalChildCount == 0) applyRole(info)
    }

    override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean {
        val current = snapshot()
        if (!host.isAttachedToWindow || !current.present) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> {
                if (!current.enabled || !current.clickable) false else {
                    val result = invokeSourceClick()
                    sendSourceEvent(host, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    result
                }
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK ->
                current.enabled && current.longClickable && invokeSourceLongClick?.invoke() == true
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS ->
                super.performAccessibilityAction(view, action, args)
            AccessibilityNodeInfo.ACTION_FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS ->
                current.enabled && super.performAccessibilityAction(view, action, args)
            else -> false
        }
    }

    private fun applyRole(info: AccessibilityNodeInfo) {
        when (role) {
            NativeMergedRowRole.BUTTON -> info.className = "android.widget.Button"
            NativeMergedRowRole.SWITCH -> AccessibilityNodeInfoCompat.wrap(info).roleDescription =
                host.context.getString(app.smallthingz.nativeui.R.string.switch_role)
            NativeMergedRowRole.TAB -> AccessibilityNodeInfoCompat.wrap(info).roleDescription =
                host.context.getString(app.smallthingz.nativeui.R.string.tab)
        }
    }

    private inner class RoleView : View(host.context) {
        init {
            setWillNotDraw(true)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = false
            isClickable = false
            isLongClickable = false
            isScreenReaderFocusable = false
        }
        override fun draw(canvas: Canvas) = Unit
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
        override fun dispatchHoverEvent(event: MotionEvent): Boolean = false
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = false

        override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? {
            val current = snapshot()
            return if (isAttachedToWindow && current.present && current.rolePresent && current.originalChildCount > 0)
                super.createAccessibilityNodeInfo() else null
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            val current = snapshot()
            info.className = "android.view.View"
            info.text = null
            info.contentDescription = null
            val compat = AccessibilityNodeInfoCompat.wrap(info)
            compat.stateDescription = null
            compat.roleDescription = null
            applyRole(info)
            info.isEnabled = true // Fake configuration contains Role only, never Disabled.
            info.isCheckable = false
            info.isChecked = false
            info.isSelected = false
            info.isClickable = false
            info.isLongClickable = false
            info.isFocusable = false
            info.isFocused = false
            info.isScreenReaderFocusable = false
            info.isImportantForAccessibility = !current.hidden
            info.isVisibleToUser = current.present && current.visibleToUser
            info.maxTextLength = -1
            info.movementGranularities = 0
            info.setBoundsInParent(Rect()) // Source provider writes only boundsInScreen.
            info.setBoundsInScreen(current.fakeRoleBoundsInScreen)
            info.drawingOrder = current.originalChildCount
            info.actionList.toList().forEach(info::removeAction)
            addAccessibilityFocusAction(info, isAccessibilityFocused)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val current = snapshot()
            if (!isAttachedToWindow || !current.present || !current.rolePresent || current.originalChildCount == 0) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS ->
                    super.performAccessibilityAction(action, arguments)
                else -> false
            }
        }

        override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
            super.onInitializeAccessibilityEvent(event)
            event.className = "android.view.View"
            event.isEnabled = true
            event.text.clear()
            event.contentDescription = null
        }
    }

    private companion object {
        fun hasTransparentAncestor(view: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current.alpha == 0f) return true
                current = current.parent as? View
            }
            return false
        }

        fun addAccessibilityFocusAction(info: AccessibilityNodeInfo, focused: Boolean) {
            info.isAccessibilityFocused = focused
            info.addAction(if (focused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }

        @Suppress("DEPRECATION")
        fun sendSourceEvent(view: View, type: Int) {
            val manager = view.context.getSystemService(AccessibilityManager::class.java)
            if (manager?.isEnabled != true || !view.isAttachedToWindow) return
            val event = AccessibilityEvent.obtain(type).apply {
                isEnabled = true
                className = "android.view.View"
                packageName = view.context.packageName
                setSource(view)
            }
            view.parent?.requestSendAccessibilityEvent(view, event)
        }
    }
}
