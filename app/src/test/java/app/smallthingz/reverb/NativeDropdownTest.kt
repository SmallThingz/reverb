package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeDropdownColors
import smallthingz.ui.components.NativeDropdownContent
import smallthingz.ui.components.NativeDropdownIcon
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeDropdownTest {
    @Test fun selectedTextAndPopupHandoffTrackCurrentTypedBindingAcrossAvailabilityChanges() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val resolver = createFontFamilyResolver(activity)
        fun text() = ParagraphView(activity, TextStyle(fontSize = 20.sp), Density(1f, 2f), resolver, Color.BLACK)
        val title = text()
        val selected = text()
        val arrow = View(activity)
        val colors = NativeDropdownColors(Color.WHITE, Color.LTGRAY, Color.GRAY, Color.BLACK, Color.GRAY)
        val opens = mutableListOf<Pair<View, NativeDropdownContent<Int>>>()
        val bindings = mutableListOf<NativeDropdownContent<Int>>()
        val row = NativeDropdown(activity, title, selected, NativeDropdownIcon(arrow) {}, colors, { root },
            onBinding = { _, content -> bindings += content }, open = { anchor, content -> opens += anchor to content })
        root.addView(row, FrameLayout.LayoutParams(-1, -2))
        val initial = NativeDropdownContent("Sample rate", 48_000, listOf(44_100, 48_000)) { value: Int -> "$value Hz" }
        fun layout(direction: Int) {
            row.layoutDirection = direction
            root.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 240, 800)
        }
        try {
            row.bind(initial, false, colors)
            layout(View.LAYOUT_DIRECTION_LTR)
            assertTrue(row.isAttachedToWindow)
            assertEquals("Sample rate", requireNotNull(title.createAccessibilityNodeInfo()).text.toString())
            assertEquals("48000 Hz", requireNotNull(selected.createAccessibilityNodeInfo()).text.toString())
            assertTrue(row.performClick())
            assertEquals(1, opens.size)
            assertSame(row, opens.single().first)
            assertSame(initial, opens.single().second)
            assertFalse("arrow cannot independently open another popup", arrow.performClick())
            assertEquals(1, opens.size)
            val leftTitle = title.left
            val rightTitle = title.right
            val leftArrow = arrow.left
            val rightArrow = arrow.right
            assertTrue(title.measuredHeight > 0 && selected.measuredHeight > 0)
            assertTrue(title.right <= arrow.left)
            assertTrue(selected.bottom <= row.height)
            layout(View.LAYOUT_DIRECTION_RTL)
            assertEquals(row.width - rightTitle, title.left)
            assertEquals(row.width - leftTitle, title.right)
            assertEquals(row.width - rightArrow, arrow.left)
            assertEquals(row.width - leftArrow, arrow.right)
            assertTrue(arrow.right <= title.left)

            val unavailable = initial.copy(options = listOf(48_000))
            row.bind(unavailable, false, colors)
            assertEquals(View.GONE, row.visibility)
            assertFalse(row.performClick())
            assertEquals(1, opens.size)
            val next = initial.copy(selected = 44_100, label = "Updated sample rate")
            row.bind(next, false, colors)
            layout(View.LAYOUT_DIRECTION_RTL)
            assertEquals(View.VISIBLE, row.visibility)
            assertEquals("44100 Hz", requireNotNull(selected.createAccessibilityNodeInfo()).text.toString())
            assertEquals("Updated sample rate", requireNotNull(title.createAccessibilityNodeInfo()).text.toString())
            assertTrue(row.performClick())
            assertEquals(2, opens.size)
            assertSame(row, opens.last().first)
            assertSame(next, opens.last().second)
            assertEquals(listOf(initial, unavailable, next), bindings)
            val oldHeight = title.measuredHeight
            assertTrue("baseline native text metrics", oldHeight > 0)
            val oldCount = opens.size
            val newResolver = createFontFamilyResolver(activity)
            row.bindTypography(TextStyle(fontSize = 36.sp), TextStyle(fontSize = 30.sp), Density(1f, 2f), newResolver)
            row.bindDescription("Ouvrir les options")
            layout(View.LAYOUT_DIRECTION_RTL)
            assertTrue("retained title uses current typography", title.measuredHeight > oldHeight)
            val reference = ParagraphView(activity, TextStyle(fontSize = 36.sp), Density(1f, 2f), newResolver, Color.BLACK)
            reference.setText(title.getText())
            reference.measure(View.MeasureSpec.makeMeasureSpec(title.measuredWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals("retained metrics match a fresh current-style paragraph", reference.measuredHeight, title.measuredHeight)
            assertEquals("Ouvrir les options", arrow.contentDescription)
            assertEquals("configuration cannot select or open", oldCount, opens.size)
            assertEquals("44100 Hz", selected.getText())
            root.removeView(row)
            assertFalse(row.performClick())
            assertEquals(2, opens.size)
            row.close()
            row.bind(initial, false, colors)
            assertFalse(row.performClick())
        } finally {
            row.close()
            owner.pause().stop().destroy()
        }
    }
}
