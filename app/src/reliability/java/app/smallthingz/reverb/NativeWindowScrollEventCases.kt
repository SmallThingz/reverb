package app.smallthingz.reverb

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import app.smallthingz.nativeui.unit.Density
import app.smallthingz.reverb.nativeui.NativePlainTextViewGeometry
import app.smallthingz.reverb.nativeui.NativeTextSemanticsClip
import app.smallthingz.reverb.nativeui.NativeTextSemanticsClipPolicy
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
    val pagerAttachmentCases = verifyNativePagerScrollAttachmentCases(host)
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
    val passed = pagerAttachmentCases.toMutableList()
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

/** Same external window-root construction order as NativeActivityContentView during hydration. */
private fun verifyNativePagerScrollAttachmentCases(host: ViewGroup): List<String> {
    val subtree = FrameLayout(host.context)
    val reparented = FrameLayout(host.context)
    val viewport = FrameLayout(host.context)
    subtree.addView(viewport, FrameLayout.LayoutParams(100, 100))
    val clock = NativeScrollCaseClock()
    val scope = CoroutineScope(clock + SupervisorJob())
    var geometryChanges = 0
    val queue = NativeWindowScrollAccessibilityEvents(host, scope, { geometryChanges++ }, {}, {})
    queue.accessibilityForceEnabledForTesting = true
    val sent = mutableListOf<Int>()
    queue.onSendAccessibilityEvent = { event -> sent += event.eventType; true }
    var registrations = 0
    var retirements = 0
    val tracked = NativeWindowHorizontalAccessibilityEvents { source, present, range ->
        val actual = queue.register(source, present, range) // Keep the real ancestry check.
        registrations++
        object : NativeHorizontalAccessibilityEventRegistration {
            override fun scrollChanged() = actual.scrollChanged()
            override fun layoutChanged() = actual.layoutChanged()
            override fun close() { retirements++; actual.close() }
        }
    }
    val pager = NativeOnboardingPager(scope, Density(1f)).also { it.updateLayout(100) }
    val geometry = NativePlainTextViewGeometry(host,
        NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
    var sourcePresent = true
    var sourceReads = 0
    fun create(source: ViewGroup) = NativeOnboardingPagerAccessibility(source, pager, scope,
        geometry, { true }, {
            sourceReads++
            NativeOnboardingPagerNode(sourcePresent, false, sourcePresent, emptyList())
        }, tracked)
    var delegate: NativeOnboardingPagerAccessibility? = null
    var other: NativeOnboardingPagerAccessibility? = null
    var primaryFailure: Throwable? = null
    val passed = mutableListOf<String>()
    try {
        val created = create(viewport) // Used to throw before Activity.addView established ancestry.
        delegate = created
        check(registrations == 0 && !viewport.isAttachedToWindow)
        val foreign = FrameLayout(host.context)
        val rejected = runCatching {
            queue.register(foreign, { true }, { NativeAccessibilityScrollRange(0f, 100f) })
        }.exceptionOrNull()
        check(rejected is IllegalArgumentException &&
            rejected.message == "Scroller must belong to this native window root")
        host.addView(subtree, ViewGroup.LayoutParams(100, 100))
        check(viewport.isAttachedToWindow && registrations == 1)
        created.sourceStateChanged()
        check(sourceReads == 0 && geometryChanges == 0) // Initial offset only seeds observation.
        pager.dispatchRawDelta(1f)
        created.sourceStateChanged()
        check(sourceReads == 1 && geometryChanges == 1)
        created.sourceStateChanged()
        check(sourceReads == 1 && geometryChanges == 1) // Unchanged offset does no geometry work.
        sourcePresent = false
        pager.dispatchRawDelta(1f)
        created.sourceStateChanged()
        check(sourceReads == 2 && geometryChanges == 1) // Absent source never reaches invalidation.
        sourcePresent = true
        passed += "pager changed offset captures source once; initial, unchanged and absent admission stay distinct"
        created.sourceLayoutChanged() // Retire while this notification is still queued.
        host.removeView(subtree)
        check(retirements == 1)
        clock.runCurrent()
        check(sent.isEmpty())
        passed += "pager defers external-window registration until attach; foreign ancestry still rejects"

        pager.requestScrollToPage(1)
        pager.updateLayout(100)
        created.sourceStateChanged() // Detached state changes cannot enqueue or seed reattachment.
        check(sourceReads == 2 && geometryChanges == 1)
        host.addView(reparented, ViewGroup.LayoutParams(100, 100))
        reparented.addView(subtree, FrameLayout.LayoutParams(100, 100))
        check(registrations == 2)
        created.sourceStateChanged()
        check(sourceReads == 2 && geometryChanges == 1) // Reattachment seeds a fresh observation.
        clock.advanceBy(100)
        check(sent.isEmpty())
        created.sourceLayoutChanged()
        clock.runCurrent()
        check(sent == listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        created.sourceLayoutChanged()
        created.close(); created.close()
        check(retirements == 2)
        clock.advanceBy(100)
        check(sent.size == 1)
        passed += "pager detach retires queued event; same-window reparent seeds range and owns one fresh registration"

        val closedBeforeAttach = FrameLayout(host.context)
        val retiredBeforeAttach = create(closedBeforeAttach)
        other = retiredBeforeAttach
        retiredBeforeAttach.close()
        subtree.addView(closedBeforeAttach, FrameLayout.LayoutParams(20, 20))
        check(registrations == 2)
        passed += "pager disposed before attachment cannot register later"
        val existingHost = create(closedBeforeAttach) // Already attached construction works too.
        other = existingHost
        check(registrations == 3)
        existingHost.close(); existingHost.close()
        subtree.removeView(closedBeforeAttach)
        reparented.removeView(subtree)
        check(retirements == 3)
        passed += "already-attached pager registers once; close and later detach retire it once"
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        listOf<() -> Unit>({ other?.close() }, { delegate?.close() },
            { (subtree.parent as? ViewGroup)?.removeView(subtree) },
            { host.removeView(reparented) }, { queue.close() }, { scope.cancel() },
            { clock.runCurrent() }).forEach { cleanup ->
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
