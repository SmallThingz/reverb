package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import androidx.activity.BackEventCompat
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeFloatingDialog
import smallthingz.ui.components.NativeFloatingPredictiveMotion
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
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
        // Current-source cancellation contract, separate from the historical snap oracle.
        val cancellation = JSONObject().put("passed", false)
            .put("scope", "Actual About dispatcher and presented surface; local synthetic Back events")
        val samples = JSONArray().also { cancellation.put("translation_y", it) }
        fun saveCancellation() = writeQaEvidenceAtomically(File(directory, "predictive-cancellation.json")) {
            it.write(cancellation.toString(2).toByteArray(Charsets.UTF_8))
        }
        try {
            val scale = nativeParityOnMain(instrumentation) {
                check(decor.isAttachedToWindow && decor.hasWindowFocus())
                val dispatcher = checkNotNull(decor.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .6f, BackEventCompat.EDGE_LEFT))
                val release = surface.translationY
                check(kotlin.math.abs(release + surface.height * .6f) < .01f)
                dispatcher.dispatchOnBackCancelled()
                check(surface.translationY == release) { "Predictive cancel snapped away from release pose" }
                cancellation.put("release_y", release.toDouble()).put("release_pose_preserved", true)
                readCachedAnimatorDurationScale(context)
            }
            cancellation.put("duration_scale", scale.toDouble())
            var intermediate = false
            val returnDeadline = SystemClock.uptimeMillis() + 6000L
            while (true) {
                awaitNativeParityDraw(instrumentation, decor)
                val returned = nativeParityOnMain(instrumentation) {
                    check(dismissals == 0 && decor.isAttachedToWindow && decor.hasWindowFocus())
                    val y = surface.translationY
                    samples.put(y.toDouble())
                    if (y < 0f && y > cancellation.getDouble("release_y")) intermediate = true
                    y == 0f
                }
                if (returned) break
                check(SystemClock.uptimeMillis() < returnDeadline) { "Predictive cancel failed to return" }
            }
            cancellation.put("intermediate_observed", intermediate)
            check(scale == 0f || intermediate) { "No intermediate return frame observed at nonzero scale" }
            repeat(2) { awaitNativeParityDraw(instrumentation, decor) }
            nativeParityOnMain(instrumentation) {
                val floating = ReverbAboutDialog::class.java.getDeclaredField("dialog").run {
                    isAccessible = true; get(dialog) as NativeFloatingDialog
                }
                val motion = NativeFloatingDialog::class.java.getDeclaredField("predictiveMotion").run {
                    isAccessible = true; get(floating) as NativeFloatingPredictiveMotion
                }
                val posted = NativeFloatingDialog::class.java.getDeclaredField("callbackPosted").run {
                    isAccessible = true; getBoolean(floating)
                }
                check(!motion.active && !motion.hasContinuation && !motion.commitCompleted && !posted)
                check(dismissals == 0 && surface.translationY == 0f)
                cancellation.put("no_back", true).put("idle_callback_retired", true).put("passed", true)
            }
        } catch (failure: Throwable) {
            cancellation.put("error", failure.stackTraceToString())
            try { saveCancellation() } catch (write: Throwable) { if (write !== failure) failure.addSuppressed(write) }
            throw failure
        }
        saveCancellation()
        report.appendLine("ABOUT_PREDICTIVE_CANCEL ${directory.name}/predictive-cancellation.json")
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
