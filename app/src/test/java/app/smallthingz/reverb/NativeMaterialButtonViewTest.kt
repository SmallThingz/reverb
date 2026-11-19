package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeMaterialButtonViewTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun standaloneAccessibilityHonorsNativeClippingAndNeverReplacesRetiredSourceGeometry() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = FrameLayout(activity).apply { clipChildren = true }
        activity.setContentView(root)
        var calls = 0
        fun newButton() = NativeMaterialButtonView(activity, false, TextStyle(fontSize = 16.sp), Density(1f),
            createFontFamilyResolver(activity), NativeMaterialButtonStyle(12)) { calls++ }
        val button = newButton()
        val pristine = newButton()
        val platformButton = Button(activity).apply { text = "Platform control" }
        root.addView(platformButton, FrameLayout.LayoutParams(120, 56).apply { leftMargin = 150 })
        val width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        fun mount(view: View = button) {
            root.addView(view, FrameLayout.LayoutParams(120, 56))
            root.measure(width, height)
            root.layout(0, 0, 360, 600)
        }
        fun nativeVisibility(view: View): String {
            val bounds = Rect()
            val visible = view.getGlobalVisibleRect(bounds)
            val ancestors = generateSequence(view) { it.parent as? View }.joinToString(" <- ") {
                "${it.javaClass.simpleName}(${it.width}x${it.height}, visibility=${it.visibility})"
            }
            return "nativeVisible=$visible bounds=$bounds attached=${view.isAttachedToWindow} " +
                "shown=${view.isShown} windowVisibility=${view.windowVisibility} $ancestors"
        }
        fun click() = button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        val geometry = NativePlainTextViewGeometry(root, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
        try {
            button.bind("Save copy", true, Color.BLUE, Color.WHITE)
            mount()
            println("Standalone button nested-only mount: ${nativeVisibility(platformButton)}; ${nativeVisibility(button)}")
            val decor = activity.window.decorView
            decor.measure(width, height)
            decor.layout(0, 0, 360, 600)
            val platformBounds = Rect()
            val buttonBounds = Rect()
            assertTrue(nativeVisibility(platformButton), platformButton.getGlobalVisibleRect(platformBounds) && !platformBounds.isEmpty)
            assertTrue(nativeVisibility(button), button.getGlobalVisibleRect(buttonBounds) && !buttonBounds.isEmpty)
            assertEquals(nativeVisibility(platformButton), View.VISIBLE, platformButton.windowVisibility)
            assertEquals(nativeVisibility(button), View.VISIBLE, button.windowVisibility)
            assertTrue(button.isAttachedToWindow && button.isShown)
            val nativeNode = requireNotNull(button.createAccessibilityNodeInfo())
            assertEquals("android.widget.Button", nativeNode.className.toString())
            assertEquals("Save copy", nativeNode.contentDescription.toString())
            assertEquals(0, nativeNode.childCount)
            assertTrue(nativeNode.isVisibleToUser)
            assertTrue(click())
            assertEquals(1, calls)
            button.contentDescription = "Save recording copy"
            assertEquals("Save recording copy", requireNotNull(button.createAccessibilityNodeInfo()).contentDescription.toString())
            root.alpha = 0f
            assertNull(button.createAccessibilityNodeInfo())
            assertFalse(click())
            root.alpha = 1f
            button.translationX = 1000f
            assertNull(button.createAccessibilityNodeInfo())
            assertFalse(click())
            button.translationX = 0f
            assertNotNull(button.createAccessibilityNodeInfo())

            val older = button.mergedRowAccessibility.bindGeometry(geometry) { true }
            val hidden = button.mergedRowAccessibility.bindGeometry(geometry) { false }
            older.close()
            val hiddenNode = requireNotNull(button.createAccessibilityNodeInfo())
            assertFalse(hiddenNode.isVisibleToUser)
            assertNull(hiddenNode.contentDescription)
            hidden.close()
            assertNull(button.createAccessibilityNodeInfo())
            assertFalse(click())
            root.removeView(button)
            mount()
            assertNull(button.createAccessibilityNodeInfo())
            val absent = button.mergedRowAccessibility.bindGeometry(geometry,
                { NativeSourceAccessibilityNode.absent() }, null) { true }
            assertNull(button.createAccessibilityNodeInfo())
            assertFalse(click())
            absent.close()
            root.removeView(button)
            pristine.bind("Cancel", true, Color.BLUE, Color.WHITE)
            mount(pristine)
            assertNotNull(pristine.createAccessibilityNodeInfo())
            pristine.close()
            assertNull(pristine.createAccessibilityNodeInfo())
            assertFalse(pristine.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, calls)
        } finally {
            button.close()
            pristine.close()
            owner.pause().stop().destroy()
        }
    }

    @Test fun preferredWidthMatchesNaturalMeasurementAcrossLeadingContentAndLabelModes() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val resolver = createFontFamilyResolver(activity)
        fun button(style: NativeMaterialButtonStyle) = NativeMaterialButtonView(activity, true,
            TextStyle(fontSize = 16.sp), Density(1f), resolver, style) {}.apply {
            bind("A long, visible button label", true, Color.BLUE, Color.WHITE)
        }
        val normal = button(NativeMaterialButtonStyle(24, iconSizeDp = 19, iconGapDp = 7))
        val iconOnly = button(NativeMaterialButtonStyle(12, minimumWidthDp = 44, iconSizeDp = 26, showLabel = false))
        fun measuredWidth(row: NativeMaterialButtonView): Int {
            row.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return row.measuredWidth
        }
        try {
            val plainWidth = measuredWidth(normal)
            assertEquals(plainWidth, normal.preferredWidth(compact = false))
            normal.setLeadingIcon(ColorDrawable(Color.WHITE))
            assertEquals(plainWidth + 26, measuredWidth(normal))
            assertEquals(measuredWidth(normal), normal.preferredWidth(compact = false))
            normal.setLeadingIcon(null)
            normal.setLeadingView(View(activity))
            assertEquals(plainWidth + 26, measuredWidth(normal))
            assertEquals(measuredWidth(normal), normal.preferredWidth(compact = false))
            normal.compactPadding = true
            normal.requestLayout()
            assertEquals(plainWidth + 2, measuredWidth(normal))
            assertEquals(measuredWidth(normal), normal.preferredWidth(compact = true))

            assertEquals(44, measuredWidth(iconOnly))
            assertEquals(44, iconOnly.preferredWidth(compact = false))
            iconOnly.setLeadingIcon(ColorDrawable(Color.WHITE))
            assertEquals(50, measuredWidth(iconOnly))
            assertEquals(50, iconOnly.preferredWidth(compact = false))
        } finally {
            normal.close()
            iconOnly.close()
            owner.pause().stop().destroy()
        }
    }

    @Test fun realInputAndAccessibilityRespectAvailabilityAndTerminalCloseInBothSizingModes() {
        for (assigned in listOf(false, true)) {
            val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
            val activity = owner.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val resolver = createFontFamilyResolver(activity)
            var calls = 0
            val row = NativeMaterialButtonView(activity, true, TextStyle(fontSize = 16.sp), Density(1f),
                resolver, NativeMaterialButtonStyle(24, assignedSize = assigned)) { calls++ }
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
                fun touch(action: Int, x: Float, y: Float): Boolean {
                    val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
                    return try { row.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                if (assigned) assertFalse(touch(MotionEvent.ACTION_DOWN, 0f, 0f))
                assertTrue(touch(MotionEvent.ACTION_DOWN, row.width / 2f, row.height / 2f))
                assertTrue(touch(MotionEvent.ACTION_UP, row.width / 2f, row.height / 2f))
                assertEquals("the default library input dispatches one admitted click", 1, calls)
                calls = 0
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
