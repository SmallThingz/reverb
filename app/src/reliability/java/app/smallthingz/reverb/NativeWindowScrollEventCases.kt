package app.smallthingz.reverb

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * QA-only counter/transport regression helper. Caller owns an attached isolated nativeparity Window
 * and calls on main. No permission/backend/input/state changes; the source test-only event gate and
 * send callback are used without enabling a system service. It tests queue time/order/lifetime,
 * not actual TalkBack delivery, native focus geometry, wall-clock latency or the full semantics tree.
 *
 * Integrated reliability helper; compilation and actual execution are still pending.
 */
internal fun verifyNativeWindowScrollEventCases(host: ViewGroup): List<String> {
    check(Looper.myLooper() == Looper.getMainLooper())
    check(host.isAttachedToWindow)
    check(host.context.packageName.endsWith(".nativeparity.reliability"))
    val fixture = FrameLayout(host.context)
    val horizontal = View(host.context)
    val vertical = View(host.context)
    fixture.addView(horizontal, FrameLayout.LayoutParams(20, 20))
    fixture.addView(vertical, FrameLayout.LayoutParams(20, 20))
    host.addView(fixture, ViewGroup.LayoutParams(100, 100))
    val clock = NativeScrollCaseClock()
    val scope = CoroutineScope(clock + SupervisorJob())
    val events = mutableListOf<NativeScrollCaseEvent>()
    var horizontalOffset = 0f
    var verticalOffset = 0f
    var horizontalMaximum = 100f
    var horizontalPresent = true
    var closed = 0
    var batches = 0
    val queue = NativeWindowScrollAccessibilityEvents(host, scope, {}, { batches++ }, { closed++ })
    queue.accessibilityForceEnabledForTesting = true
    queue.onSendAccessibilityEvent = { event ->
        events += NativeScrollCaseEvent(clock.now, event.eventType, event.contentChangeTypes,
            event.className?.toString(), event.isEnabled, event.scrollX, event.maxScrollX,
            event.scrollY, event.maxScrollY, event.text.toList(), event.contentDescription)
        true
    }
    val passed = mutableListOf<String>()
    var primaryFailure: Throwable? = null
    try {
        val h = queue.registerScroll(horizontal, { horizontalPresent }, { fixture },
            horizontal = { NativeAccessibilityScrollRange(horizontalOffset, horizontalMaximum) })
        val v = queue.registerScroll(vertical, { true }, { fixture },
            vertical = { NativeAccessibilityScrollRange(verticalOffset, 200f) })
        clock.runCurrent()
        check(events.isEmpty())

        horizontalOffset = 12f; h.scrollChanged()
        verticalOffset = 17f; v.scrollChanged()
        horizontalOffset = 18f; verticalOffset = 19f
        clock.runCurrent()
        check(events.size == 3 && batches == 1)
        check(events.first().type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        check(events.first().contentChanges == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        check(events.count { it.type == AccessibilityEvent.TYPE_VIEW_SCROLLED } == 2)
        check(events.any { it.type == AccessibilityEvent.TYPE_VIEW_SCROLLED && it.x == 18 && it.maxX == 100 })
        check(events.any { it.type == AccessibilityEvent.TYPE_VIEW_SCROLLED && it.y == 19 && it.maxY == 200 })
        check(events.all { it.time == 0L && it.className == "android.view.View" && it.enabled &&
            it.text.isEmpty() && it.description == null })
        passed += "one immediate shared batch; deduplicated subtree; latest ranges; exact generic payload"

        val firstCount = events.size
        horizontalOffset = 25f; h.scrollChanged()
        verticalOffset = 30f; v.scrollChanged()
        horizontalOffset = 31f; h.scrollChanged()
        clock.advanceBy(99)
        check(events.size == firstCount)
        clock.advanceBy(1)
        check(events.size == firstCount + 3 && events.drop(firstCount).all { it.time == 100L })
        check(events.drop(firstCount).any { it.x == 31 })
        passed += "horizontal and vertical changes share100ms batch gap without resetting it"

        val rangeChangeStart = events.size
        horizontalMaximum = 140f; h.layoutChanged()
        clock.advanceBy(100)
        check(events.size == rangeChangeStart + 1 &&
            events.last().type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        passed += "max-only layout change emits subtree without invented scroll event"

        horizontalOffset = 40f; h.scrollChanged(); h.close()
        val replacement = queue.registerScroll(horizontal, { horizontalPresent }, { fixture },
            horizontal = { NativeAccessibilityScrollRange(horizontalOffset, horizontalMaximum) })
        h.close(); h.scrollChanged()
        horizontalOffset = 41f; replacement.scrollChanged()
        val replacementStart = events.size
        clock.advanceBy(100)
        check(events.size == replacementStart + 2 && events.last().x == 41)
        passed += "retired token and old close cannot revive input or remove same-View replacement"

        horizontalOffset = 42f; replacement.scrollChanged()
        horizontalPresent = false
        val pendingHiddenStart = events.size
        clock.advanceBy(100)
        check(events.size == pendingHiddenStart + 2 && events.last().x == 42)
        passed += "already queued attached source remains dispatchable after semantic presence changes"

        horizontalPresent = true
        horizontalOffset = 43f; replacement.scrollChanged()
        fixture.removeView(horizontal)
        val detachedStart = events.size
        clock.advanceBy(100)
        check(events.size == detachedStart)
        fixture.addView(horizontal, FrameLayout.LayoutParams(20, 20))
        horizontalOffset = 44f; replacement.scrollChanged()
        clock.advanceBy(100)
        check(events.size == detachedStart + 2 && events.last().x == 44)
        passed += "detach suppresses queued source; reattach preserves live registration"

        horizontalOffset = 45f; replacement.scrollChanged()
        val disposalStart = events.size
        scope.cancel()
        clock.runCurrent()
        clock.advanceBy(100)
        check(closed == 1 && events.size == disposalStart)
        replacement.scrollChanged(); replacement.close(); v.close(); queue.close()
        check(closed == 1)
        passed += "parent scope cancellation disposes pending queue and registrations once"
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        listOf<() -> Unit>({ queue.close() }, { scope.cancel() }, { clock.runCurrent() },
            { host.removeView(fixture) }).forEach { cleanup ->
            try { cleanup() } catch (error: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
            }
        }
        cleanupFailure?.let { cleanup ->
            val primary = primaryFailure
            if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
        }
    }
    return passed
}

private data class NativeScrollCaseEvent(
    val time: Long, val type: Int, val contentChanges: Int, val className: String?,
    val enabled: Boolean, val x: Int, val maxX: Int, val y: Int, val maxY: Int,
    val text: List<CharSequence>, val description: CharSequence?,
)

/** Deterministic coroutine dispatcher/delay for the real queue; never sleeps or advances Android time. */
@OptIn(InternalCoroutinesApi::class)
private class NativeScrollCaseClock : CoroutineDispatcher(), Delay {
    var now: Long = 0L
        private set
    private var sequence = 0L
    private class Task(val at: Long, val order: Long, val run: Runnable) : Comparable<Task> {
        var cancelled = false
        override fun compareTo(other: Task): Int =
            if (at != other.at) at.compareTo(other.at) else order.compareTo(other.order)
    }
    private val tasks = PriorityQueue<Task>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.add(Task(now, sequence++, block))
    }
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = Task(now + timeMillis, sequence++, Runnable { continuation.resume(Unit) })
        tasks.add(task)
        continuation.invokeOnCancellation { task.cancelled = true }
    }
    fun runCurrent() = advanceBy(0L)
    fun advanceBy(amount: Long) {
        val target = now + amount
        while (tasks.peek()?.at?.let { it <= target } == true) {
            val task = tasks.remove()
            now = task.at
            if (!task.cancelled) task.run.run()
        }
        now = target
    }
}
