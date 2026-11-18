package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.OnboardingNativeInteractionsFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeMaterialButtonViewTest {
    @Test fun realInputAndAccessibilityRespectAvailabilityAndTerminalCloseInBothSizingModes() {
        for (assigned in listOf(false, true)) {
            val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
            val activity = owner.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val resolver = createFontFamilyResolver(activity)
            var calls = 0
            val row = NativeMaterialButtonView(activity, true, TextStyle(fontSize = 16.sp), Density(1f),
                resolver, NativeMaterialButtonStyle(24, assignedSize = assigned),
                OnboardingNativeInteractionsFactory { root }) { calls++ }
            root.addView(row, FrameLayout.LayoutParams(220, if (assigned) 64 else -2))
            val geometry = row.mergedRowAccessibility.bindGeometry(NativePlainTextViewGeometry(root,
                NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })) { true }
            fun bind(enabled: Boolean) = row.bind("Continue", enabled, Color.BLUE, Color.WHITE)
            fun key(action: Int) = KeyEvent(action, KeyEvent.KEYCODE_ENTER).let {
                if (action == KeyEvent.ACTION_DOWN) row.onKeyDown(it.keyCode, it) else row.onKeyUp(it.keyCode, it)
            }
            try {
                bind(true)
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 360, 800)
                assertTrue(row.isAttachedToWindow)
                assertEquals(220, row.width)
                if (assigned) assertEquals(64, row.height) else assertTrue(row.height >= 40)
                assertTrue(row.label.measuredWidth > 0 && row.label.measuredHeight > 0)
                assertNotNull("mounted semantic positive control", row.createAccessibilityNodeInfo())
                var leadingAttaches = 0
                var leadingDetaches = 0
                val leading = object : View(activity) {
                    override fun onAttachedToWindow() { super.onAttachedToWindow(); leadingAttaches++ }
                    override fun onDetachedFromWindow() { leadingDetaches++; super.onDetachedFromWindow() }
                }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                fun semanticChildren(): List<View> = arrayListOf<View>().also(row::addChildrenForAccessibility)
                val ordinaryChildren = listOf(row.label, row.mergedRowAccessibility.roleView)
                row.setLeadingView(leading, exposeToAccessibility = false)
                val leadingParent = leading.parent
                assertNotNull(leadingParent)
                assertTrue(leading.isAttachedToWindow)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, leading.importantForAccessibility)
                assertEquals(ordinaryChildren, semanticChildren())
                row.setLeadingView(leading, exposeToAccessibility = true)
                assertEquals(listOf(leading) + ordinaryChildren, semanticChildren())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, leading.importantForAccessibility)
                assertSame(leadingParent, leading.parent)
                assertTrue(leading.isAttachedToWindow)
                row.setLeadingView(leading, exposeToAccessibility = false)
                assertEquals(ordinaryChildren, semanticChildren())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, leading.importantForAccessibility)
                assertSame(leadingParent, leading.parent)
                assertEquals("exposure rebinding keeps one attachment", 1, leadingAttaches)
                assertEquals("exposure rebinding never detaches content", 0, leadingDetaches)
                val replacement = View(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                row.setLeadingView(replacement, exposeToAccessibility = false)
                assertNull(leading.parent)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, leading.importantForAccessibility)
                assertTrue(replacement.isAttachedToWindow)
                row.setLeadingView(null)
                assertNull(replacement.parent)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, replacement.importantForAccessibility)
                assertEquals(ordinaryChildren, semanticChildren())
                row.setLeadingView(leading, exposeToAccessibility = false)
                assertTrue(row.performClick())
                assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(2, calls)
                assertTrue(key(KeyEvent.ACTION_DOWN))
                assertTrue(key(KeyEvent.ACTION_UP))
                assertEquals(3, calls)
                row.visibility = View.INVISIBLE
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(3, calls)
                row.visibility = View.VISIBLE
                assertTrue(key(KeyEvent.ACTION_DOWN))
                bind(false)
                bind(true)
                key(KeyEvent.ACTION_UP)
                assertEquals("disabled binding retires held input", 3, calls)
                bind(false)
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertFalse(requireNotNull(row.createAccessibilityNodeInfo()).isEnabled)
                bind(true)
                assertTrue(row.performClick())
                assertEquals(4, calls)
                assertTrue(key(KeyEvent.ACTION_DOWN))
                row.isEnabled = false
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                row.isEnabled = true
                key(KeyEvent.ACTION_UP)
                assertEquals("platform disable retires held input", 4, calls)
                row.close()
                assertNull(leading.parent)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, leading.importantForAccessibility)
                val lateLeading = View(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                row.setLeadingView(lateLeading, exposeToAccessibility = false)
                assertNull(lateLeading.parent)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, lateLeading.importantForAccessibility)
                val motion = NativeMaterialButtonView::class.java.getDeclaredField("colorMotion").apply {
                    isAccessible = true
                }
                assertNull("close releases the animation owner", motion.get(row))
                row.bind("Late replacement", true, Color.RED, Color.BLACK, onClick = { calls += 100 })
                row.bindTypography(TextStyle(fontSize = 32.sp), Density(2f), resolver)
                assertNull("late binding cannot recreate an animation owner", motion.get(row))
                assertFalse(row.isEnabled)
                assertFalse(row.performClick())
                assertFalse(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                root.removeView(row)
                root.addView(row)
                key(KeyEvent.ACTION_DOWN)
                key(KeyEvent.ACTION_UP)
                assertFalse(row.performClick())
                assertEquals(4, calls)
            } finally {
                row.close()
                geometry.close()
                owner.pause().stop().destroy()
            }
        }
    }
}
