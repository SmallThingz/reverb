package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

    @Test
    @Config(qualifiers = "fr")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun standaloneRowNamesVisibleTextAndLocalizedStateUntilExplicitGeometryOwnsIt() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val title = TextView(activity).apply { text = "Mode automatique" }
        val summary = TextView(activity).apply { text = "Permission requise" }
        val calls = mutableListOf<Boolean>()
        val row = NativeToggleRow(activity, title, summary, NativeMaterialSwitchView(activity, palette),
            onChanged = { calls += it })
        val platformControl = Button(activity).apply { text = "Native visibility control" }
        root.addView(platformControl, FrameLayout.LayoutParams(-1, -2))
        root.addView(row, FrameLayout.LayoutParams(-1, -2))
        var lease: AutoCloseable? = null
        try {
            row.bindToggle(true, true, activity.resources.displayMetrics.density, Color.BLACK, palette)
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            val originalPlatformBounds = Rect()
            val originalRowBounds = Rect()
            val decor = activity.window.decorView
            println("TOGGLE_ORIGINAL_VIEWPORT decor=${decor.width}x${decor.height} " +
                "platformVisible=${platformControl.getGlobalVisibleRect(originalPlatformBounds)} " +
                "rowVisible=${row.getGlobalVisibleRect(originalRowBounds)}")
            platformControl.layoutParams = FrameLayout.LayoutParams(row.width, row.height)
            decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 360, 800)
            assertTrue("real window decor has a viewport", decor.width > 0 && decor.height > 0)
            val platformBounds = Rect()
            val rowBounds = Rect()
            assertTrue("ordinary native Button must be visible through real ancestors", platformControl.getGlobalVisibleRect(platformBounds))
            assertFalse(platformBounds.isEmpty)
            assertEquals("ordinary native Button has an admitted visible window", View.VISIBLE, platformControl.windowVisibility)
            assertTrue("toggle must be visible through the same real ancestors", row.getGlobalVisibleRect(rowBounds))
            assertFalse(rowBounds.isEmpty)
            assertEquals(View.VISIBLE, row.windowVisibility)
            val initial = requireNotNull(row.createAccessibilityNodeInfo())
            assertTrue(initial.isVisibleToUser)
            assertTrue(initial.isCheckable)
            assertTrue(initial.isChecked)
            assertEquals("Changer", androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(initial).roleDescription.toString())
            assertEquals("Mode automatique\nPermission requise", initial.contentDescription.toString())
            assertEquals("Activ\u00e9", initial.stateDescription.toString())
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(false), calls)
            val standaloneChildren = arrayListOf<View>()
            row.addChildrenForAccessibility(standaloneChildren)
            assertTrue("standalone Switch owns one node without fake children", standaloneChildren.isEmpty())
            summary.text = "Permission accordee"
            row.bindToggle(false, true, activity.resources.displayMetrics.density, Color.BLACK, palette)
            val updated = requireNotNull(row.createAccessibilityNodeInfo())
            assertFalse(updated.isChecked)
            assertEquals("D\u00e9sactiv\u00e9", updated.stateDescription.toString())
            assertEquals("Mode automatique\nPermission accordee", updated.contentDescription.toString())
            summary.visibility = View.GONE
            assertEquals("Mode automatique", requireNotNull(row.createAccessibilityNodeInfo()).contentDescription.toString())
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(false, true), calls)

            lease = row.mergedRowAccessibility.bindGeometry(NativePlainTextViewGeometry(root,
                NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })) { true }
            assertNotNull("first explicit geometry is positively admitted", row.createAccessibilityNodeInfo())
            lease.close()
            lease = null
            assertNull("retired explicit owner must not resurrect standalone semantics", row.createAccessibilityNodeInfo())
            assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(false, true), calls)
        } finally {
            lease?.close()
            row.close()
            owner.pause().stop().destroy()
        }
    }

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

    @Test fun equalBindingDoesNotRequestLayoutButDensityCheckedAndTextChangesDo() = fixture {
        row, _, summary, _, calls, root ->
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            assertFalse(row.isLayoutRequested)
        }
        val density = row.resources.displayMetrics.density
        layout()
        row.bindToggle(false, true, density, Color.BLACK, palette)
        assertFalse(row.isLayoutRequested)
        row.bindToggle(true, true, density, Color.BLACK, palette)
        assertTrue(row.isLayoutRequested)
        layout()
        row.bindToggle(true, true, density * 2, Color.BLACK, palette)
        assertTrue(row.isLayoutRequested)
        layout()
        summary.text = "A different, substantially longer description that wraps onto additional lines"
        assertTrue(row.isLayoutRequested)
        assertTrue(calls.isEmpty())
    }

    @Test fun platformDisableRejectsClickAndAccessibilityUntilReenabled() = fixture {
        row, _, _, visual, calls, _ ->
        val originalBounds = Rect(visual.left, visual.top, visual.right, visual.bottom)
        assertTrue(visual.isEnabled)
        assertTrue(requireNotNull(row.createAccessibilityNodeInfo()).isEnabled)
        assertTrue(row.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        row.isEnabled = false
        assertFalse(visual.isEnabled)
        assertFalse(row.performClick())
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        val disabled = requireNotNull(row.createAccessibilityNodeInfo())
        assertFalse(disabled.isEnabled)
        assertFalse(disabled.isChecked)
        assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertTrue(calls.isEmpty())
        row.isEnabled = true
        assertTrue(visual.isEnabled)
        assertEquals(originalBounds, Rect(visual.left, visual.top, visual.right, visual.bottom))
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

        row.bindToggle(true, false, row.resources.displayMetrics.density, Color.BLACK, palette)
        row.isEnabled = true
        assertFalse("Platform enable must not override the model for the mark", visual.isEnabled)
        val modelDisabled = requireNotNull(row.createAccessibilityNodeInfo())
        assertTrue(modelDisabled.isChecked)
        assertFalse(modelDisabled.isEnabled)
        assertFalse(row.performClick())
        assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))

        row.close()
        row.isEnabled = true
        assertFalse(visual.isEnabled)
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
