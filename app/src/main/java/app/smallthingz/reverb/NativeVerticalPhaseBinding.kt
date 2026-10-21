package app.smallthingz.reverb

import android.view.MotionEvent
import android.view.View
import app.smallthingz.reverb.nativeui.NativeVerticalViewport

/**
 * Direct binding to one attached page viewport, never to the page's longer-lived ScrollState.
 * Input recognition stays in the shared source-derived router; the owner retains mutation/fling.
 * The host inserts observerNode and dragNode at their source modifier positions in its hit layout.
 */
internal class NativeVerticalPhaseBinding(
    private val viewport: NativeVerticalViewport,
    private val touchSlop: () -> Float,
    private val maximumVelocity: () -> Float,
    private val parentInterested: (NativeDragPointerChange) -> Boolean,
) : View.OnAttachStateChangeListener, AutoCloseable {
    private var channel: NativeVerticalDragChannel? = null
    private var router: NativeDragPointerRouter? = null

    fun interested(change: NativeDragPointerChange): Boolean = router?.interested(change) == true

    val dragNode: NativeViewPhasePointerNode = object : NativeViewPhasePointerNode {
        override val view: View get() = viewport
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            router?.let {
                it.enabled = viewport.interactionEnabled
                it.dispatch(event, pass)
            }
        }
        override fun cancel() { router?.reset() }
    }

    val observerNode: NativeViewPhasePointerNode = object : NativeViewPhasePointerNode {
        override val view: View get() = viewport
        private var rootEvent: MotionEvent? = null
        private var coordinates: NativePointerCoordinates? = null
        private var pointerMask = 0
        override fun bindMotion(
            event: MotionEvent?,
            coordinates: NativePointerCoordinates?,
            androidPointerMask: Int,
        ) {
            rootEvent = event
            this.coordinates = coordinates
            pointerMask = androidPointerMask
        }
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            if (pass != NativePointerPass.INITIAL) return
            // The edge-effect pointer observer sees every admitted event, even if already consumed.
            val local = requireNotNull(rootEvent).nativePointerSubset(pointerMask)
            try {
                local.transform(requireNotNull(coordinates).rootToLocal)
                viewport.observePointer(local)
            } finally {
                local.recycle()
            }
        }
        override fun cancel() = Unit // Drag cancel still uses the owner's zero-velocity release path.
    }

    init {
        viewport.addOnAttachStateChangeListener(this)
        if (viewport.isAttachedToWindow) onViewAttachedToWindow(viewport)
    }

    override fun onViewAttachedToWindow(view: View) {
        check(channel == null)
        val attachedChannel = NativeVerticalDragChannel(viewport.owner, viewport.inputScope)
        channel = attachedChannel
        router = NativeDragPointerRouter(
            NativeDragAxis.VERTICAL,
            touchSlop,
            maximumVelocity,
            { viewport.owner.shouldScrollImmediately },
            attachedChannel,
            parentInterested,
        ).also { it.enabled = viewport.interactionEnabled }
    }

    override fun onViewDetachedFromWindow(view: View) {
        // Node disposal cancels queued drag/fling before reset can enqueue another settle.
        channel?.close()
        router?.reset()
        channel = null
        router = null
    }

    override fun close() {
        viewport.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(viewport)
    }
}
