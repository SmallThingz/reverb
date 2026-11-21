package app.smallthingz.reverb.nativeui

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeBufferSegmentStyle
import smallthingz.ui.components.NativeCaptureControlSurface
import smallthingz.ui.components.NativeCaptureControlsStyle
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.input.phase.NativeViewPointerHitLayout

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeCaptureControlSurfaceLayoutTest {
    @Test fun defaultRowsPreserveFractionalDensityAndConstrainedRtlPlacement() {
        val surface = CaptureSurface(RuntimeEnvironment.getApplication())
        surface.bind(1.1f)
        surface.place(350, 150)

        assertEquals(282, surface.width)
        assertEquals(123, surface.height)
        assertEquals(listOf(Rect(15, 15, 138, 44), Rect(145, 15, 267, 44)), surface.segments.map(View::bounds))
        assertEquals(listOf(Rect(9, 55, 68, 114), Rect(77, 55, 136, 114),
            Rect(145, 55, 204, 114), Rect(213, 55, 272, 114)), surface.actions.map(View::bounds))

        surface.layoutDirection = View.LAYOUT_DIRECTION_RTL
        surface.place(200, 150)

        assertEquals(200, surface.width)
        assertEquals(listOf(Rect(70, 15, 193, 44), Rect(7, 15, 63, 44)), surface.segments.map(View::bounds))
        assertEquals(listOf(Rect(132, 55, 191, 114), Rect(64, 55, 123, 114),
            Rect(9, 55, 55, 114), Rect(0, 55, 0, 114)), surface.actions.map(View::bounds))
    }

    @Test fun appearanceRebindRelayoutsTheSameChildrenAndPreservesTheirAction() {
        val surface = CaptureSurface(RuntimeEnvironment.getApplication())
        val segment = surface.segments.first()
        val action = surface.actions.first()
        var clicks = 0
        action.setOnClickListener { clicks++ }
        surface.bind(1.25f)
        surface.place(350, 150)
        assertTrue(action.performClick())

        val style = NativeCaptureControlsStyle.Default.copy(
            widthDp = 240, heightDp = 100, selectorWidthDp = 200, selectorHeightDp = 48,
            selectorSpacingDp = 4, actionHeightDp = 60, actionSpacingDp = 5, actionSizeDp = 40,
        )
        surface.bind(1.25f, style, NativeBufferSegmentStyle(preferredWidthDp = 96))
        surface.place(350, 150)

        assertEquals(300, surface.width)
        assertEquals(125, surface.height)
        assertSame(segment, surface.placedChild(0))
        assertSame(action, surface.placedChild(2))
        assertEquals(listOf(Rect(30, 16, 150, 45), Rect(155, 16, 270, 45)), surface.segments.map(View::bounds))
        assertEquals(listOf(Rect(6, 63, 56, 113), Rect(62, 63, 112, 113),
            Rect(118, 63, 168, 113), Rect(174, 63, 224, 113)), surface.actions.map(View::bounds))
        assertTrue(action.performClick())
        assertEquals(2, clicks)
    }

    private class CaptureSurface(context: Context) : NativeCaptureControlSurface(context) {
        val segments = List(2) {
            object : View(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(29, heightMeasureSpec))
                }
            }
        }
        val actions = List(4) { View(context) }
        private val palette = NativeColorScheme.fallback(false)

        init {
            layoutDirection = LAYOUT_DIRECTION_LTR
            addControlRows(segments, actions)
        }

        override fun childHitLayouts(): List<NativeViewPointerHitLayout> = emptyList()

        fun bind(
            density: Float,
            style: NativeCaptureControlsStyle = NativeCaptureControlsStyle.Default,
            segmentStyle: NativeBufferSegmentStyle = NativeBufferSegmentStyle(),
        ) = bindSurface(palette, density, style, segmentStyle)

        fun place(maxWidth: Int, maxHeight: Int) {
            measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            layout(0, 0, measuredWidth, measuredHeight)
        }

        fun placedChild(index: Int): View = content.getChildAt(index)
    }
}

private fun View.bounds() = Rect(left, top, right, bottom)
