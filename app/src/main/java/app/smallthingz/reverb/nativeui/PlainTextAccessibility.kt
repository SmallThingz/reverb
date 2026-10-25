/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.smallthingz.reverb.nativeui

import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.MainThread
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.text.MultiParagraph
import kotlin.math.max
import kotlin.math.min

/**
 * Integrated native geometry contract; actual accessibility/device qualification remains pending.
 * The adapter must supply the same float clipping/coordinate contract
 * as source SemanticsNode.positionInRoot / boundsInRoot and AndroidComposeView.localToScreen.
 *
 * No getGlobalVisibleRect integer approximation is supplied. paragraphPositionInRoot includes the
 * original text layout's padding/placement, not a substituted/translated paragraph's placement.
 * visibleBoundsInRoot is the original text semantics node's clipped rect (not glyph ink bounds).
 * This immutable snapshot must represent one current layout; null is preferable to stale geometry.
 */
internal data class PlainTextAccessibilityGeometry(
    val paragraphPositionInRoot: Offset,
    val visibleBoundsInRoot: Rect,
    val rootToScreen: (Offset) -> Offset,
)

/**
 * Original semantics/layout remain authoritative even when translation displays a separate cache.
 * Layout and geometry are lazy: node text/character traversal must not perform paragraph relayout.
 */
internal data class PlainTextAccessibilitySnapshot(
    val originalText: String,
    val originalSemanticsLayout: () -> MultiParagraph?,
    val geometry: () -> PlainTextAccessibilityGeometry?,
)

/**
 * One direct owner per native parent window/root, analogous to the Compose host delegate's cursor.
 * Do NOT create one per label or a process-global cursor; no app state, focus tree or polling here.
 * Android itself continues to own native accessibility focus. The source resets the shared cursor
 * on a traversal target change, NOT merely when accessibility focus changes.
 */
@MainThread
internal class PlainTextAccessibilityWindowScope(private val root: View) {
    internal var cursor = -1
        private set
    private var previousTraversedIdentity: Any? = null
    private var pending: PendingTraversal? = null
    private val manager = root.context.getSystemService(AccessibilityManager::class.java)

    private data class PendingTraversal(
        val identity: Any, val action: Int, val granularity: Int,
        val from: Int, val to: Int, val time: Long,
    )

    internal fun traverse(
        label: PlainTextAccessibility,
        snapshot: PlainTextAccessibilitySnapshot,
        granularity: Int,
        forward: Boolean,
    ): Boolean {
        if (previousTraversedIdentity !== label.identity) {
            cursor = -1
            previousTraversedIdentity = label.identity
        }
        val text = snapshot.originalText
        if (text.isEmpty()) return false
        val iterator = iterator(snapshot, granularity) ?: return false
        val current = if (cursor == -1) (if (forward) 0 else text.length) else cursor
        val range = (if (forward) iterator.following(current) else iterator.preceding(current))
            ?: return false
        val start = range[0]
        val end = range[1]
        pending = PendingTraversal(
            label.identity,
            if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            granularity, start, end, SystemClock.uptimeMillis(),
        )
        // Plain Text is NOT selection-extendable. Ignore EXTEND_SELECTION exactly as the source's
        // isAccessibilitySelectionExtendable=false branch, including backward traversal.
        val position = if (forward) end else start
        setSelection(label, text, position, position)
        return true
    }

    internal fun setSelection(label: PlainTextAccessibility, text: String, start: Int, end: Int): Boolean {
        if (start == end && end == cursor) return false
        cursor = if (start >= 0 && start == end && end <= text.length) start else -1
        send(label.view, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            if (text.isNotEmpty()) {
                fromIndex = cursor
                toIndex = cursor
                itemCount = text.length
            }
            this.text.add(text)
        }
        val event = pending
        if (event != null && event.identity === label.identity) {
            if (SystemClock.uptimeMillis() - event.time <= 1000L) {
                send(label.view, AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY) {
                    fromIndex = event.from
                    toIndex = event.to
                    action = event.action
                    movementGranularity = event.granularity
                    this.text.add(text)
                }
            }
            pending = null
        }
        return true
    }

    internal fun selectionActionCompleted(label: PlainTextAccessibility) {
        // Pinned delegate:1466-1470 supplies CONTENT_CHANGE_TYPE_UNDEFINED (0) as eventType.
        // Preserve that envelope here rather than silently replacing it with a different event.
        send(label.view, AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) {}
    }

    @Suppress("DEPRECATION")
    private fun iterator(
        snapshot: PlainTextAccessibilitySnapshot,
        granularity: Int,
    ): NativePlainTextSegments.TextSegmentIterator? {
        val text = snapshot.originalText
        val locale = root.context.resources.configuration.locale
        return when (granularity) {
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER ->
                NativePlainTextSegments.CharacterTextSegmentIterator.getInstance(locale).apply { initialize(text) }
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD ->
                NativePlainTextSegments.WordTextSegmentIterator.getInstance(locale).apply { initialize(text) }
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH ->
                NativePlainTextSegments.ParagraphTextSegmentIterator.getInstance().apply { initialize(text) }
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE -> {
                val paragraph = snapshot.originalSemanticsLayout() ?: return null
                NativePlainTextSegments.LineTextSegmentIterator.getInstance().apply { initialize(text, paragraph) }
            }
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE -> {
                val paragraph = snapshot.originalSemanticsLayout() ?: return null
                val geometry = snapshot.geometry() ?: return null
                NativePlainTextSegments.PageTextSegmentIterator.getInstance().apply {
                    initialize(text, paragraph, geometry)
                }
            }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private inline fun send(host: View, type: Int, configure: AccessibilityEvent.() -> Unit) {
        if (manager?.isEnabled != true || !host.isAttachedToWindow) return
        val event = AccessibilityEvent.obtain(type).apply {
            // Pinned delegate createEvent:1345-1353 always uses android.view.View, even for Text.
            isEnabled = true
            className = "android.view.View"
            packageName = root.context.packageName
            setSource(host)
            configure()
        }
        // Do not call host.onInitializeAccessibilityEvent again and duplicate its text payload.
        host.parent?.requestSendAccessibilityEvent(host, event)
    }
}

/**
 * Hook these three operations into an ordinary ReverbParagraphView. Do not replace the containing
 * row's role/click semantics or set isFocusable: native keyboard/tab stops remain unchanged.
 * A child inside a merging row remains exposed in the source unmerged accessibility tree; the
 * host marks it screenReaderFocusable=false without removing its text traversal operations.
 *
 * Caller supplies original semantics + lazy source MultiParagraph, not the displayed translation.
 * Pinned ParagraphLayoutCache.slowCreateTextLayoutResultOrNull rebuilds this layout on demand.
 * Coordinate data is REQUIRED for page traversal/character extras; no approximate fallback exists.
 */
@MainThread
internal class PlainTextAccessibility(
    internal val view: View,
    private val window: PlainTextAccessibilityWindowScope,
    private val snapshot: () -> PlainTextAccessibilitySnapshot,
    private val exposed: () -> Boolean,
    /** Source contentDescription-only nodes share the cursor, but expose no text layout/boxes. */
    private val descriptionOnly: Boolean = false,
) {
    internal var identity: Any = Any()
        private set

    /** Call only when this View represents a new semantics identity (e.g. recycler different key). */
    fun replaceIdentity() {
        identity = Any()
    }

    fun populate(info: AccessibilityNodeInfo) {
        if (!exposed()) return
        // Plain source text has no native View select/clear-selection/show-on-screen actions.
        // Preserve Android-owned accessibility focus, then add only the source cursor actions.
        info.actionList.toList().forEach(info::removeAction)
        info.addAction(if (view.isAccessibilityFocused)
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        info.isAccessibilityFocused = view.isAccessibilityFocused
        info.isFocusable = false
        info.isFocused = false
        info.isClickable = false
        info.isLongClickable = false
        info.isSelected = false
        info.isEnabled = true
        info.maxTextLength = -1
        info.movementGranularities = 0
        val text = snapshot().originalText
        if (descriptionOnly) info.contentDescription = text else {
            // Pinned getInfoText/trimToSize: cap the node payload without splitting a surrogate pair.
            // Traversal and selection still use the original untrimmed String, as the delegate does.
            val end = if (text.length <= 100000) text.length
                else if (Character.isHighSurrogate(text[99999]) && Character.isLowSurrogate(text[100000])) 99999
                else 100000
            info.text = SpannableString(if (end == text.length) text else text.substring(0, end))
        }
        info.isEditable = false
        if (text.isEmpty()) return
        info.setTextSelection(window.cursor, window.cursor)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
        // Static plain text supplies GetTextLayoutResult semantics even before first measurement.
        info.movementGranularities =
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
        if (!descriptionOnly) info.movementGranularities = info.movementGranularities or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE or AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE
        if (Build.VERSION.SDK_INT >= 26 && !descriptionOnly) {
            info.availableExtraData = (info.availableExtraData +
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY).distinct()
        }
    }

    /** null means unhandled: keep native focus/click/Back handling in the host/super implementation. */
    fun performAction(action: Int, arguments: Bundle?): Boolean? {
        if (action != AccessibilityNodeInfo.ACTION_SET_SELECTION &&
            action != AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY &&
            action != AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY) return null
        if (!view.isAttachedToWindow || !exposed()) return false
        val current = snapshot()
        if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
            val start = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1) ?: -1
            val end = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1) ?: -1
            val success = window.setSelection(this, current.originalText, start, end)
            if (success) window.selectionActionCompleted(this)
            return success
        }
        arguments ?: return false
        return window.traverse(
            this, current,
            arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT),
            action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
        )
    }

    /** True identifies this extra-data key, including malformed requests that produce no payload. */
    fun addExtraData(info: AccessibilityNodeInfo, key: String, arguments: Bundle?): Boolean {
        if (descriptionOnly || Build.VERSION.SDK_INT < 26 || key != AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) return false
        if (arguments == null || !view.isAttachedToWindow || !exposed()) return true
        val current = snapshot()
        val start = arguments.getInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, -1)
        val length = arguments.getInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1)
        if (length <= 0 || start < 0 || start >= current.originalText.length) return true
        val paragraph = current.originalSemanticsLayout() ?: return true
        val geometry = current.geometry() ?: return true
        // UTF-16 positions, not code points/grapheme indices. Requested tail beyond text is null.
        val boxes = arrayOfNulls<RectF>(length)
        for (index in boxes.indices) {
            val offset = start.toLong() + index
            if (offset >= current.originalText.length) continue
            val local = paragraph.getBoundingBox(offset.toInt())
            val inRoot = local.translate(geometry.paragraphPositionInRoot)
            val nodeBounds = geometry.visibleBoundsInRoot
            if (!inRoot.overlaps(nodeBounds)) continue
            val visible = inRoot.intersect(nodeBounds)
            val first = geometry.rootToScreen(Offset(visible.left, visible.top))
            val second = geometry.rootToScreen(Offset(visible.right, visible.bottom))
            // Deliberately the same two-corner mapping/order normalization as delegate:2127-2138.
            boxes[index] = RectF(min(first.x, second.x), min(first.y, second.y),
                max(first.x, second.x), max(first.y, second.y))
        }
        info.extras.putParcelableArray(key, boxes)
        return true
    }
}
