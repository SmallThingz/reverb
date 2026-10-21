package app.smallthingz.reverb

import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope

/** One attached horizontal modifier lifetime, separate from retained pager/page scroll state. */
internal class NativeOnboardingPagerPhaseBinding(
    private val viewport: View,
    private val pager: NativeOnboardingPager,
    private val scroll: NativeOnboardingPagerScrollOwner,
    private val attachedScope: () -> CoroutineScope,
    private val touchSlop: () -> Float,
    private val maximumVelocity: () -> Float,
    private val enabled: () -> Boolean,
) : View.OnAttachStateChangeListener, AutoCloseable {
    private var channel: NativePagerDragChannel? = null
    private var router: NativeDragPointerRouter? = null
    private val direction = NativePagerDirectionObserver(pager)

    fun interested(change: NativeDragPointerChange): Boolean = router?.let {
        it.enabled = enabled()
        it.interested(change)
    } == true

    // ScrollableArea's edge node precedes ScrollableNode; dragDirectionDetector is appended
    // afterwards by LazyLayoutPager. Only Initial observer nodes use the Android event here.
    val nodes: List<NativeViewPhasePointerNode> = listOf(
        object : NativeViewPhasePointerNode {
            override val view get() = viewport
            private var motion: MotionEvent? = null
            private var coordinates: NativePointerCoordinates? = null
            private var mask = 0
            override fun bindMotion(event: MotionEvent?, coordinates: NativePointerCoordinates?, androidPointerMask: Int) {
                motion = event
                this.coordinates = coordinates
                mask = androidPointerMask
            }
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                if (pass != NativePointerPass.INITIAL) return
                val local = requireNotNull(motion).nativePointerSubset(mask)
                try {
                    local.transform(requireNotNull(coordinates).rootToLocal)
                    scroll.overscroll.pointer(local)
                } finally { local.recycle() }
            }
            override fun cancel() = Unit
        },
        object : NativeViewPhasePointerNode {
            override val view get() = viewport
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                router?.let { it.enabled = enabled(); it.dispatch(event, pass) }
            }
            override fun cancel() { router?.reset() }
        },
        NativePagerDirectionPhaseNode(viewport, direction),
    )

    init {
        viewport.addOnAttachStateChangeListener(this)
        if (viewport.isAttachedToWindow) onViewAttachedToWindow(viewport)
    }

    override fun onViewAttachedToWindow(view: View) {
        check(channel == null)
        val owner = NativePagerDragChannel(
            pager, attachedScope(),
            deliverDelta = { scroll.dragBy(this, it) },
            settle = scroll::stopped,
        )
        channel = owner
        router = NativeDragPointerRouter(NativeDragAxis.HORIZONTAL, touchSlop, maximumVelocity,
            { scroll.shouldScrollImmediately }, owner).also { it.enabled = enabled() }
    }

    fun updateEnabled() { router?.enabled = enabled() }

    override fun onViewDetachedFromWindow(view: View) {
        channel?.close() // No canceled node may enqueue a new settle.
        router?.reset()
        direction.cancel()
        channel = null
        router = null
    }

    override fun close() {
        viewport.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(viewport)
    }
}
