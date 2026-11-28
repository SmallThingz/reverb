package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.NativeMainSavedNavigation
import app.smallthingz.reverb.NativeMainScreenInput
import app.smallthingz.reverb.NativeMainScreenSavedState
import app.smallthingz.reverb.NativeMainScreenView
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.input.phase.nativePointerHitPath
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density

/** Exercise raw Android flags through Main's real hit paths, projection and Library binding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMainLibraryEdgeCancellationTest {
    @Test fun canceledEdgePointerUpKeepsLibraryAndOutsideToolbarPointer() = Fixture().use { f ->
        val end = f.crossThresholdWithOutsidePointer()
        val offset = f.offset
        f.touch(MotionEvent.ACTION_POINTER_UP, 7 to end, 19 to f.brand, flags = MotionEvent.FLAG_CANCELED)
        assertTrue("Canceled edge owner must not dismiss Library", f.libraryOpen)
        assertFalse(f.aboutOpen)
        assertEquals("Reserved edge drag must not scroll the list", offset, f.offset)
        f.touch(MotionEvent.ACTION_UP, 19 to f.brand)
        assertTrue("The independent toolbar finger must retain its click", f.aboutOpen)
        assertTrue(f.libraryOpen)
    }

    @Test fun ordinaryEdgePointerUpDismissesWithOutsidePointer() = Fixture().use { f ->
        val end = f.crossThresholdWithOutsidePointer()
        f.touch(MotionEvent.ACTION_POINTER_UP, 7 to end, 19 to f.brand)
        assertFalse("Normal edge release must still dismiss with an outside finger held", f.libraryOpen)
        assertFalse(f.aboutOpen)
    }

    @Test fun canceledOutsidePointerLeavesEdgeOwnerAbleToDismiss() = Fixture().use { f ->
        val end = f.crossThresholdWithOutsidePointer()
        f.touch(pointerUpSecond, 7 to end, 19 to f.brand, flags = MotionEvent.FLAG_CANCELED)
        assertTrue(f.libraryOpen)
        assertFalse("Canceled toolbar contact must not click", f.aboutOpen)
        f.touch(MotionEvent.ACTION_UP, 7 to end)
        assertFalse("A canceled outside contact must not cancel the edge owner", f.libraryOpen)
    }

    @Test fun canceledNonOwnerBodyPointerLeavesEdgeOwnerAbleToDismiss() = Fixture().use { f ->
        val end = f.crossThresholdWithBodyPointer()
        f.touch(pointerUpSecond, 7 to end, 19 to f.center, flags = MotionEvent.FLAG_CANCELED)
        assertTrue(f.libraryOpen)
        f.touch(MotionEvent.ACTION_UP, 7 to end)
        assertFalse("Canceling another body contact must preserve the original edge owner", f.libraryOpen)
    }

    @Test fun canceledEdgeOwnerCannotTransferDismissToRemainingBodyPointer() = Fixture().use { f ->
        val end = f.crossThresholdWithBodyPointer()
        val offset = f.offset
        f.touch(MotionEvent.ACTION_POINTER_UP, 7 to end, 19 to f.center, flags = MotionEvent.FLAG_CANCELED)
        assertTrue(f.libraryOpen)
        val secondEnd = f.center + Offset(0f, 180f)
        f.touch(MotionEvent.ACTION_MOVE, 19 to secondEnd)
        f.touch(MotionEvent.ACTION_UP, 19 to secondEnd)
        assertTrue("The canceled drag cannot resume on the remaining body contact", f.libraryOpen)
        assertFalse(f.aboutOpen)
        assertEquals("Keep the canceled stream reserved until all body contacts lift", offset, f.offset)
        f.touch(MotionEvent.ACTION_DOWN, 7 to f.edge)
        f.touch(MotionEvent.ACTION_MOVE, 7 to end)
        f.touch(MotionEvent.ACTION_UP, 7 to end)
        assertFalse("A fresh edge gesture must work after the canceled stream ends", f.libraryOpen)
    }

    private class Fixture : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val board = FrameLayout(context)
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        private val scope = CoroutineScope(Dispatchers.Main.immediate +
            AndroidUiFrameClock(Choreographer.getInstance()) +
            object : MotionDurationScale { override val scaleFactor = 0f })
        private val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context),
            ReverbPalette.fallback(false))
        private val root = NativeMainScreenView.create(context, environment, NativeMainScreenInput(false, false),
            lifecycle, OnBackPressedDispatcher(), scope, null, board,
            NativeWindowTextAccessibilityOwner(board, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }),
            NativeMainScreenSavedState(navigation = NativeMainSavedNavigation(showLibrary = true)), {}, {},
            registerDirectoryResult = { error("Edge fixture never enters Settings") }, releaseInputFocus = {})
        private val body: View
        private val brandView: View
        private val viewport: ReverbLibraryRecyclerView
        val edge: Offset
        val center: Offset
        val brand: Offset
        val libraryOpen get() = root.saveState().navigation.showLibrary
        val aboutOpen get() = root.saveState().navigation.showAboutDialog
        val offset get() = viewport.computeVerticalScrollOffset()
        private var downTime = SystemClock.uptimeMillis()
        private var eventTime = downTime

        init {
            // Same attached Main fixture as NativeMainLibraryScrollbarGestureTest: seed metadata,
            // keep the main clock paused, and do not advance the initial delayed catalog scan.
            NativeMainScreenView::class.java.getDeclaredField("librarySnapshot").apply { isAccessible = true }
                .set(root, List(40) { index ->
                    RecordingEntity("/synthetic-scroll/$index.wav", "Recording $index", "audio/wav",
                        1_700_000_000_000L, 1_000L, 32_044L, "PCM", RecordingStorageType.FILE, "/synthetic-scroll")
                })
            board.addView(root, FrameLayout.LayoutParams(-1, -1))
            context.setContentView(board)
            root.start()
            // Let the Activity's pending traversal place the real window before sampling points.
            // idle() drains due work without advancing the delayed catalog scan.
            shadowOf(Looper.getMainLooper()).idle()
            val library = field<ReverbLibraryScreenContent>(root, "library")
            body = library.bodyPointerView
            viewport = checkNotNull(library.listViewport)
            edge = point(body, 8f, 100f)
            center = point(body, body.width / 2f, 100f)
            val toolbar = field<ReverbAppTopBarView>(root, "libraryToolbar")
            brandView = field<View>(toolbar, "brand")
            brand = point(brandView, brandView.width / 2f, brandView.height / 2f)
            assertTrue("Library body must fit the threshold-crossing drag", body.height > 300)
            assertTrue("Edge press must route through Body", hitViews(edge).any { it === body })
            assertTrue("Center control must route through Body", hitViews(center).any { it === body })
            val outside = hitViews(brand)
            assertTrue("Outside contact must hit the real toolbar action", outside.any { it === brandView })
            assertFalse("Toolbar contact must not be projected into Body", outside.any { it === body })
        }

        fun crossThresholdWithOutsidePointer(): Offset = crossThreshold(brand, outsideBody = true)
        fun crossThresholdWithBodyPointer(): Offset = crossThreshold(center, outsideBody = false)

        private fun crossThreshold(second: Offset, outsideBody: Boolean): Offset {
            val end = edge + Offset(0f, 180f)
            touch(MotionEvent.ACTION_DOWN, 7 to edge)
            if (outsideBody) {
                val path = hitViews(second)
                assertTrue("Second DOWN must still hit the real toolbar action", path.any { it === brandView })
                assertFalse("Second DOWN must stay outside Body", path.any { it === body })
            }
            touch(pointerDownSecond, 7 to edge, 19 to second)
            touch(MotionEvent.ACTION_MOVE, 7 to end, 19 to second)
            assertTrue("Crossing the threshold must wait for release", libraryOpen)
            assertFalse(aboutOpen)
            return end
        }

        private fun point(view: View, x: Float, y: Float): Offset {
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            val location = IntArray(2).also(view::getLocationOnScreen)
            return Offset(x + location[0] - rootLocation[0], y + location[1] - rootLocation[1])
        }

        private fun hitViews(point: Offset): List<View> {
            val pointer = field<Any>(root, "pointer")
            val coordinates = field<NativeWindowPointerCoordinates>(pointer, "coordinates")
            val layout = field<NativeViewPointerHitLayout>(pointer, "layout")
            val probe = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, point.x, point.y, 0)
            return try {
                coordinates.begin(probe)
                nativePointerHitPath(layout, Offset(probe.rawX, probe.rawY), PointerType.Touch)
                    .filterIsInstance<NativeViewPhasePointerNode>().map { it.view }
            } finally { probe.recycle() }
        }

        fun touch(action: Int, vararg points: Pair<Int, Offset>, flags: Int = 0) {
            eventTime += 16L
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
                id = points[index].first // Non-contiguous Android IDs exercise the node mask, not indices.
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = points[index].second.x
                y = points[index].second.y
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(downTime, eventTime, action, points.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
            try {
                assertTrue("Main rejected ${MotionEvent.actionToString(action)} flags=$flags", root.dispatchTouchEvent(event))
            } finally { event.recycle() }
            shadowOf(Looper.getMainLooper()).idle()
        }

        override fun close() {
            try { root.close() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }
    }

    private companion object {
        val pointerDownSecond = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUpSecond = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        inline fun <reified T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
