package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import smallthingz.ui.components.NativeFloatingPanelSurface
import smallthingz.ui.components.NativeSheetDragHandle
import smallthingz.ui.reverb.ReverbAboutDetails
import smallthingz.ui.reverb.ReverbAboutDialog
import smallthingz.ui.reverb.ReverbDesign

/** Exercises the current top-origin modal independently of the released appearance oracle. */
internal fun verifyNativeAboutCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val host = launchCurrentUiSmokeActivity(instrumentation)
    var current: ReverbAboutDialog? = null
    try {
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        val theme = ReverbDesign.theme(host, dark = true)
        var dismissals = 0
        val dialog = nativeParityOnMain(instrumentation) {
            lateinit var instance: ReverbAboutDialog
            instance = ReverbAboutDialog(host.fixtureRoot, theme,
                ReverbAboutDetails("Reverb", "Version fixture", "Repository", "SmallThingz/reverb", "Unavailable"),
                openRepository = { error("Drag opened the repository action") }, onDismiss = {
                    dismissals++
                    instance.dismiss()
                })
            current = instance
            instance.show()
            instance
        }
        val window = checkNotNull(dialog.window)
        val decor = window.decorView
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val surface = nativeParityOnMain(instrumentation) {
            descendants(decor).filterIsInstance<NativeFloatingPanelSurface>().single()
        }
        val deadline = SystemClock.uptimeMillis() + 6000L
        while (!nativeParityOnMain(instrumentation) {
            decor.hasWindowFocus() && surface.isLaidOut && surface.isShown &&
                generateSequence<View>(surface) { it.parent as? View }.all { it.alpha >= .99f }
        }) {
            check(SystemClock.uptimeMillis() < deadline) { "About window did not settle" }
            awaitNativeParityDraw(instrumentation, decor)
        }
        val geometry = nativeParityOnMain(instrumentation) {
            val handle = descendants(surface).filterIsInstance<NativeSheetDragHandle>().single()
            check(descendants(surface).none { it.contentDescription == context.getString(R.string.close) })
            val handleNode = checkNotNull(handle.createAccessibilityNodeInfo())
            try {
                check(handleNode.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS })
                val thickness = handle.getChildAt(0).height
                check(thickness > 0 && thickness < 4f * theme.density.density)
            } finally { handleNode.recycle() }
            val bounds = Rect().also { check(surface.getGlobalVisibleRect(it)) }
            check(bounds.height() >= 120f * theme.density.density)
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            val rawOrigin = IntArray(2).also(surface::getLocationOnScreen)
            val start = rawOrigin[1] - origin[1] + bounds.height() * .6f
            check(start > 80f * theme.density.density)
            (rawOrigin[0] - origin[0] + surface.width * .25f) to start
        }
        val directory = File(context.filesDir, "native-about-current-${System.currentTimeMillis()}")
        check(directory.mkdir())
        awaitNativeParityDraw(instrumentation, decor)
        nativeParityOnMain(instrumentation) { check(decor.isAttachedToWindow && decor.hasWindowFocus()) }
        val picture = checkNotNull(nativeParityUiAutomation(instrumentation).takeScreenshot())
        try {
            nativeParityOnMain(instrumentation) { check(decor.isAttachedToWindow && decor.hasWindowFocus()) }
            saveNativeParityBitmap(directory, "about-settled-full.png", picture)
        } finally { picture.recycle() }
        report.appendLine("ABOUT_CAPTURE ${directory.name}/about-settled-full.png")
        fun drag(terminal: Int) = nativeParityOnMain(instrumentation) {
            val (x, start) = geometry
            val end = start - 80f * theme.density.density
            val downTime = SystemClock.uptimeMillis()
            fun dispatch(action: Int, y: Float, eventTime: Long) {
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    val handled = window.callback.dispatchTouchEvent(event)
                    check(action == MotionEvent.ACTION_CANCEL || handled)
                }
                finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN, start, downTime)
            dispatch(MotionEvent.ACTION_MOVE, (start + end) / 2f, downTime + 24L)
            dispatch(MotionEvent.ACTION_MOVE, end, downTime + 48L)
            check((surface.parent as View).translationY < 0f) { "Panel body drag did not move the dialog" }
            dispatch(terminal, end, downTime + 72L)
        }
        drag(MotionEvent.ACTION_CANCEL)
        val cancelDeadline = SystemClock.uptimeMillis() + 6000L
        while (!nativeParityOnMain(instrumentation) { (surface.parent as View).translationY == 0f }) {
            check(SystemClock.uptimeMillis() < cancelDeadline) { "Cancelled About drag did not return to its starting position" }
            awaitNativeParityDraw(instrumentation, decor)
        }
        nativeParityOnMain(instrumentation) { check(dismissals == 0 && decor.isAttachedToWindow) }
        drag(MotionEvent.ACTION_UP)
        val closeDeadline = SystemClock.uptimeMillis() + 6000L
        while (!nativeParityOnMain(instrumentation) { dismissals == 1 && !decor.isAttachedToWindow }) {
            check(SystemClock.uptimeMillis() < closeDeadline) { "About drag did not dismiss exactly once" }
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        }
        report.appendLine("native_about_current passed: thin accessible handle, no Close icon, cancel restores the panel and release dismisses")
    } finally {
        nativeParityOnMain(instrumentation) { try { current?.dismiss() } finally { host.finish() } }
    }
}
