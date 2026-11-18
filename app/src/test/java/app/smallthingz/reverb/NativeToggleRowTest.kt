package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import smallthingz.ui.components.NativeMaterialSwitchPalette
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeToggleRowTest {
    private val palette = NativeMaterialSwitchPalette(Color.WHITE, Color.BLACK, Color.BLUE,
        Color.WHITE, Color.GRAY, Color.LTGRAY)

    private fun fixture(body: (NativeToggleRow, TextView, TextView, NativeMaterialSwitchView,
        MutableList<Boolean>, FrameLayout) -> Unit) {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = FrameLayout(activity)
        val title = TextView(activity).apply { text = "Automatic start" }
        val summary = TextView(activity).apply { text = "Permission required" }
        val visual = NativeMaterialSwitchView(activity, palette)
        val calls = mutableListOf<Boolean>()
        val row = NativeToggleRow(activity, title, summary, visual, { root }, { calls += it })
        activity.setContentView(root)
        root.addView(row, FrameLayout.LayoutParams(-1, -2))
        row.bindToggle(false, true, activity.resources.displayMetrics.density, Color.BLACK, palette)
        val geometry = row.mergedRowAccessibility.bindGeometry(NativePlainTextViewGeometry(root,
            NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })) { true }
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 800)
        try {
            assertTrue("positive mounted geometry", row.isAttachedToWindow)
            assertNotNull("positive semantic node", row.createAccessibilityNodeInfo())
            body(row, title, summary, visual, calls, root)
        } finally {
            row.close()
            geometry.close()
            owner.pause().stop().destroy()
        }
    }

    @Test fun accessibilityHasOneCheckedAuthorityAndReadsCurrentBoundStateAndText() = fixture {
        row, title, summary, visual, calls, _ ->
        val children = arrayListOf<View>()
        row.addChildrenForAccessibility(children)
        assertEquals(listOf(title, summary, row.mergedRowAccessibility.roleView), children)
        assertFalse(visual.isClickable)
        assertFalse(visual.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, visual.importantForAccessibility)
        val initial = requireNotNull(row.createAccessibilityNodeInfo())
        assertTrue(initial.isCheckable)
        assertFalse(initial.isChecked)
        assertTrue(initial.isEnabled)
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(true), calls)
        assertFalse("controlled state waits for bind", requireNotNull(row.createAccessibilityNodeInfo()).isChecked)
        summary.text = "Permission granted"
        row.bindToggle(true, true, row.resources.displayMetrics.density, Color.BLACK, palette)
        assertTrue(requireNotNull(row.createAccessibilityNodeInfo()).isChecked)
        children.clear()
        row.addChildrenForAccessibility(children)
        assertEquals("Permission granted", (children[1] as TextView).createAccessibilityNodeInfo().text.toString())
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(true, false), calls)
        assertEquals(1, (listOf(row) + children).count { requireNotNull(it.createAccessibilityNodeInfo()).isCheckable })
    }

    @Test fun disableCancelsHeldKeyAndFreshEnabledInputStillActivatesOnce() = fixture {
        row, _, _, _, calls, _ ->
        assertTrue(row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        row.bindToggle(false, false, row.resources.displayMetrics.density, Color.BLACK, palette)
        row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(requireNotNull(row.createAccessibilityNodeInfo()).isEnabled)
        row.bindToggle(false, true, row.resources.displayMetrics.density, Color.BLACK, palette)
        row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertTrue(calls.isEmpty())
        assertTrue(row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf(true), calls)
    }

    @Test fun platformDisableRejectsClickAndAccessibilityUntilReenabled() = fixture {
        row, _, _, _, calls, _ ->
        assertTrue(requireNotNull(row.createAccessibilityNodeInfo()).isEnabled)
        assertTrue(row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        row.isEnabled = false
        assertFalse(row.performClick())
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        val disabled = requireNotNull(row.createAccessibilityNodeInfo())
        assertFalse(disabled.isEnabled)
        assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertTrue(calls.isEmpty())
        row.isEnabled = true
        row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertTrue("platform disable retires the held key", calls.isEmpty())
        assertTrue(requireNotNull(row.createAccessibilityNodeInfo()).isEnabled)
        assertTrue(row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf(true), calls)
        assertTrue(row.performClick())
        assertEquals(listOf(true, true), calls)
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(true, true, true), calls)
        row.close()
        row.isEnabled = true
        assertFalse(row.performClick())
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(true, true, true), calls)
    }

    @Test fun pointerCancelAndDetachedKeyCannotDeliverButReattachedFreshKeyCan() = fixture {
        row, _, _, _, calls, root ->
        fun touch(action: Int) = MotionEvent.obtain(1, 2, action, row.width / 2f, row.height / 2f, 0).let {
            try { row.onTouchEvent(it) } finally { it.recycle() }
        }
        assertTrue("pointer down admitted before cancellation", touch(MotionEvent.ACTION_DOWN))
        touch(MotionEvent.ACTION_CANCEL)
        touch(MotionEvent.ACTION_UP)
        assertTrue(calls.isEmpty())
        assertTrue(row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        root.removeView(row)
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertTrue(calls.isEmpty())
        root.addView(row)
        assertTrue(row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(row.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf(true), calls)
        row.close()
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(true), calls)
    }
}
