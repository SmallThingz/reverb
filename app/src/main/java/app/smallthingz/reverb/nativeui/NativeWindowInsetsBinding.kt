/*
 * InsetsListener adapted from foundation-layout 1.11.2 WindowInsets.android.kt.
 * Copyright 2021 The Android Open Source Project. Apache License 2.0.
 */
package app.smallthingz.reverb.nativeui

import android.os.Build
import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * One actual native window root's dispatched inset stream. The caller derives its source policy
 * (safeDrawing versus top-bar systemBars); no raw global-inset read or duplicate padding occurs.
 * Consuming matches AndroidComposeView's default consumeWindowInsets=true.
 */
internal class NativeWindowInsetsBinding(
    private val root: View,
    private val consume: Boolean = true,
    private val changed: (WindowInsetsCompat) -> Unit,
) : WindowInsetsAnimationCompat.Callback(
    if (consume) DISPATCH_MODE_STOP else DISPATCH_MODE_CONTINUE_ON_SUBTREE
), OnApplyWindowInsetsListener, View.OnAttachStateChangeListener, Runnable, AutoCloseable {
    private var prepared = false
    private var running = false
    private var saved: WindowInsetsCompat? = null
    private var closed = false

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root, this)
        ViewCompat.setWindowInsetsAnimationCallback(root, this)
        root.addOnAttachStateChangeListener(this)
        if (root.isAttachedToWindow) root.requestApplyInsets()
    }
    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
        prepared = true
        running = true
        super.onPrepare(animation)
    }
    override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat):
        WindowInsetsAnimationCompat.BoundsCompat {
        prepared = false
        return super.onStart(animation, bounds)
    }
    override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>):
        WindowInsetsCompat {
        if (!closed) changed(insets)
        return if (consume) WindowInsetsCompat.CONSUMED else insets
    }
    override fun onEnd(animation: WindowInsetsAnimationCompat) {
        prepared = false
        running = false
        val last = saved
        if (!closed && animation.durationMillis > 0L && last != null) changed(last)
        saved = null
        super.onEnd(animation)
    }
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        if (!closed) {
            saved = insets
            if (prepared) {
                if (Build.VERSION.SDK_INT == 30) view.post(this)
            } else if (!running) changed(insets)
        }
        return if (consume) WindowInsetsCompat.CONSUMED else insets
    }
    override fun run() {
        if (!closed && prepared) {
            prepared = false
            running = false
            saved?.let(changed)
            saved = null
        }
    }
    override fun onViewAttachedToWindow(view: View) { view.requestApplyInsets() }
    override fun onViewDetachedFromWindow(view: View) = Unit

    override fun close() {
        if (closed) return
        closed = true
        root.removeCallbacks(this)
        root.removeOnAttachStateChangeListener(this)
        ViewCompat.setOnApplyWindowInsetsListener(root, null)
        ViewCompat.setWindowInsetsAnimationCallback(root, null)
        saved = null
    }
}
