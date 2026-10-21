package app.smallthingz.reverb

import android.view.View
import app.smallthingz.reverb.nativeui.NativeVerticalViewport

/** Actual attached source node lifetime; retained page scroll state owns neither queue nor timer. */
internal class NativeVerticalScrollAccessibilityBinding(
    private val viewport: NativeVerticalViewport,
    private val window: NativeWindowScrollAccessibilityEvents,
    private val sourcePresent: () -> Boolean,
) : View.OnAttachStateChangeListener, AutoCloseable {
    private var registration: NativeScrollAccessibilityRegistration? = null
    private var viewportLease: AutoCloseable? = null

    init {
        viewport.addOnAttachStateChangeListener(this)
        if (viewport.isAttachedToWindow) onViewAttachedToWindow(viewport)
    }

    override fun onViewAttachedToWindow(view: View) {
        check(registration == null)
        val registered = window.registerScroll(
            viewport, sourcePresent, { viewport },
            vertical = { NativeAccessibilityScrollRange(viewport.state.value.toFloat(), viewport.state.maxValue.toFloat()) },
        )
        try {
            viewportLease = viewport.bindWindowScrollEvents(registered)
            registration = registered
        } catch (failure: Throwable) {
            registered.close()
            throw failure
        }
    }

    override fun onViewDetachedFromWindow(view: View) {
        try { viewportLease?.close() }
        finally {
            viewportLease = null
            registration?.close()
            registration = null
        }
    }

    override fun close() {
        viewport.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(viewport)
    }
}
