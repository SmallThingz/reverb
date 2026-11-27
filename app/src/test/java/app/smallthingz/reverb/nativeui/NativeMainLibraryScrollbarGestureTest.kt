package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.IncidentHistoryAccess
import app.smallthingz.reverb.IncidentHistoryController
import app.smallthingz.reverb.IncidentPresentation
import app.smallthingz.reverb.NativeIncidentsScreenOwner
import app.smallthingz.reverb.NativeMainSavedNavigation
import app.smallthingz.reverb.NativeMainScreenInput
import app.smallthingz.reverb.NativeMainScreenSavedState
import app.smallthingz.reverb.NativeMainScreenView
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingIncident
import app.smallthingz.reverb.RecordingStorageType
import app.smallthingz.reverb.prepareIncidentHistory
import app.smallthingz.reverb.recordingIncidentPresentationKey
import app.smallthingz.reverb.toggleRecordingIncidentAcknowledgement
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import smallthingz.ui.reverb.ReverbIncidentCardView
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMainLibraryScrollbarGestureTest {
    @Test fun heldThumbExcludesSecondFingerEdgeDismissAndPullRefresh() = Fixture().use { f ->
        val thumb = f.wakeThumb()
        val edge = f.point(8f, 100f)
        val center = f.point(f.viewport.width / 2f, 100f)
        val initial = f.offset

        f.touch(MotionEvent.ACTION_DOWN, thumb)
        assertTrue(f.viewport.isScrollIndicatorDragging)
        f.touch(pointerDown, thumb, edge)
        f.touch(MotionEvent.ACTION_MOVE, thumb, edge + Offset(0f, 200f))
        f.touch(pointerUp, thumb, edge + Offset(0f, 200f))
        assertTrue(f.libraryOpen)

        f.touch(pointerDown, thumb, center)
        f.touch(MotionEvent.ACTION_MOVE, thumb, center + Offset(0f, 220f))
        assertEquals(initial, f.offset)
        assertEquals(0f, f.pull.distanceFraction, 0f)

        val movedThumb = thumb + Offset(0f, 160f)
        f.touch(MotionEvent.ACTION_MOVE, movedThumb, center + Offset(0f, 220f))
        f.place() // A distant thumb seek places its anchor in the next traversal.
        assertTrue(f.offset > initial)
        f.touch(MotionEvent.ACTION_POINTER_UP, movedThumb, center + Offset(0f, 220f))
        val afterThumb = f.offset
        // The remaining pointer's retained path must still end at Scaffold after thumb UP.
        f.touch(MotionEvent.ACTION_MOVE, center, firstId = 1)
        f.touch(MotionEvent.ACTION_UP, center, firstId = 1)
        assertEquals(afterThumb, f.offset)
        assertFalse(f.pull.isRefreshing)
        assertTrue(f.libraryOpen)
    }

    @Test fun aSecondFingerOnTheThumbCannotTakeOverAnExistingBodyReservation() {
        for (atEdge in listOf(true, false)) Fixture().use { f ->
            val thumb = f.wakeThumb()
            val body = f.point(if (atEdge) 8f else f.viewport.width / 2f, 170f)
            val initial = f.offset

            // Reserve the body with DOWN alone, before either edge or scroll has crossed slop.
            f.touch(MotionEvent.ACTION_DOWN, body)
            f.touch(pointerDown, body, thumb)
            assertFalse(f.viewport.isScrollIndicatorDragging)
            val draggedThumb = thumb + Offset(0f, 160f)
            f.touch(MotionEvent.ACTION_MOVE, body, draggedThumb)
            f.touch(pointerUp, body, draggedThumb)
            f.place()
            assertEquals(initial, f.offset)

            val movedBody = body + Offset(0f, if (atEdge) 140f else -140f)
            f.touch(MotionEvent.ACTION_MOVE, movedBody)
            f.touch(MotionEvent.ACTION_UP, movedBody)
            if (atEdge) {
                assertFalse(f.libraryOpen)
            } else {
                assertTrue(f.libraryOpen)
                assertTrue(f.offset > initial)
            }
        }
    }

    @Test fun incidentsKeepsTheCheckboxAndArbitratesThumbAndListInBothOrders() = Fixture(incidents = true).use { f ->
        val (checkbox, checkPress) = f.alignedIncidentCheckboxPress(f.wakeThumb().x)
        val checkboxRight = f.point(checkbox, checkbox.width.toFloat(), checkbox.height / 2f)
        assertEquals(f.point((f.viewport.width - f.viewport.paddingRight).toFloat(), 0f).x, checkboxRight.x, 1f)
        val expected = f.boundIncident(checkbox)
        assertFalse(expected.acknowledged)
        // A queued window traversal must not replace the sampled row between DOWN and UP.
        f.touch(MotionEvent.ACTION_DOWN, checkPress, idleAfter = false)
        assertFalse(f.viewport.isScrollIndicatorDragging)
        assertTrue("The actual DOWN must be admitted by the checkbox's click controller", f.hasCheckboxPress(checkbox))
        f.touch(MotionEvent.ACTION_UP, checkPress)
        f.assertAcknowledged(expected)

        // The acknowledgement can rebind and lay out the list. Sample the new frame for DOWN.
        f.place()
        val thumb = f.wakeThumb()
        val center = f.point(f.viewport.width / 2f, 200f)
        val movedCenter = center - Offset(0f, 100f)
        val initial = f.offset
        f.touch(MotionEvent.ACTION_DOWN, thumb)
        assertTrue(f.viewport.isScrollIndicatorDragging)
        f.touch(pointerDown, thumb, center)
        f.touch(MotionEvent.ACTION_MOVE, thumb, movedCenter)
        assertEquals(initial, f.offset)
        f.touch(pointerUp, thumb, movedCenter)
        f.touch(MotionEvent.ACTION_UP, thumb)

        f.place()
        val freshThumb = f.wakeThumb()
        val freshCenter = f.point(f.viewport.width / 2f, 200f)
        val beforeList = f.offset
        f.touch(MotionEvent.ACTION_DOWN, freshCenter)
        f.touch(pointerDown, freshCenter, freshThumb)
        assertFalse(f.viewport.isScrollIndicatorDragging)
        val movedThumb = freshThumb + Offset(0f, 160f)
        f.touch(MotionEvent.ACTION_MOVE, freshCenter, movedThumb)
        f.touch(pointerUp, freshCenter, movedThumb)
        f.place()
        assertEquals(beforeList, f.offset)
        val freshMovedCenter = freshCenter - Offset(0f, 100f)
        f.touch(MotionEvent.ACTION_MOVE, freshMovedCenter)
        assertTrue(f.offset > beforeList)
        f.touch(MotionEvent.ACTION_UP, freshMovedCenter)
    }

    private class Fixture(incidents: Boolean = false) : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val board = FrameLayout(context)
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        // Queue Main's first history read so the fixture can install its store before observation.
        private val dispatcher = if (incidents) Dispatchers.Main else Dispatchers.Main.immediate
        private val scope = CoroutineScope(dispatcher + AndroidUiFrameClock(Choreographer.getInstance()) +
            object : MotionDurationScale { override val scaleFactor = 0f })
        private val incidentAccess = if (incidents) FixtureIncidentHistory(context) else null
        private val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context),
            ReverbPalette.fallback(false))
        private val root = NativeMainScreenView.create(context, environment, NativeMainScreenInput(false, false),
            lifecycle, OnBackPressedDispatcher(), scope, null, board,
            NativeWindowTextAccessibilityOwner(board, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }),
            NativeMainScreenSavedState(navigation = NativeMainSavedNavigation(showLibrary = !incidents,
                showIncidents = incidents)), {}, {},
            registerDirectoryResult = { error("Library fixture never enters Settings") }, releaseInputFocus = {})
        val viewport: ReverbLibraryRecyclerView
        private val incidentHistory get() = field<NativeIncidentsScreenOwner>(root, "incidentsOwner").history
        val pull get() = field<NativeLibraryPullRefreshOwner>(field<ReverbLibraryScreenContent>(root, "library"), "pull")
        val libraryOpen get() = root.saveState().navigation.showLibrary
        val offset get() = viewport.computeVerticalScrollOffset()
        private var downTime = SystemClock.uptimeMillis()
        private var eventTime = downTime

        init {
            if (incidentAccess != null) {
                IncidentHistoryController::class.java.getDeclaredField("access").apply { isAccessible = true }
                    .set(incidentHistory, incidentAccess)
            } else {
                // Seed the catalog snapshot Main supplies on first open.
                NativeMainScreenView::class.java.getDeclaredField("librarySnapshot").apply { isAccessible = true }
                    .set(root, List(40) { index ->
                        RecordingEntity("/synthetic-scroll/$index.wav", "Recording $index", "audio/wav",
                            1_700_000_000_000L, 1_000L, 32_044L, "PCM", RecordingStorageType.FILE, "/synthetic-scroll")
                    })
            }
            board.addView(root, FrameLayout.LayoutParams(-1, -1))
            context.setContentView(board)
            root.start()
            viewport = if (incidents) {
                shadowOf(Looper.getMainLooper()).idle()
                check(incidentHistory.state.canMutate && incidentHistory.state.history.rows.isNotEmpty()) {
                    "The real Incidents controller must publish the fixture's history"
                }
                checkNotNull(field<NativeIncidentsScreenView>(root, "incidents").pointerContent?.pointerList)
            } else {
                checkNotNull(field<ReverbLibraryScreenContent>(root, "library").listViewport)
            }
            place()
        }

        fun place() {
            board.requestLayout()
            board.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            board.layout(0, 0, 400, 600)
        }

        fun wakeThumb(): Offset {
            assertTrue(viewport.canScrollVertically(1))
            viewport.scrollBy(0, 1)
            val bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
            try { viewport.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            val style = checkNotNull(environment.appearance.scrollIndicator)
            val x = viewport.width - viewport.paddingRight / 2f
            val y = style.minThumbHeightDp / 2f
            assertTrue("The right gutter must admit a press aligned with the painted thumb",
                viewport.canStartScrollIndicatorDrag(x, y))
            return point(x, y)
        }

        fun alignedIncidentCheckboxPress(thumbX: Float): Pair<View, Offset> {
            val listOrigin = point(0f, 0f)
            val pointer = field<Any>(root, "pointer")
            val geometry = field<NativeWindowPointerCoordinates>(pointer, "coordinates")
            val layout = field<NativeViewPointerHitLayout>(pointer, "layout")
            val aligned = descendants(viewport).filter { it.javaClass.simpleName == "IncidentCheckTarget" }
                .map { it to point(it, it.width - 3f, it.height / 2f) }
                .filter { (_, point) ->
                    viewport.canStartScrollIndicatorDrag(thumbX - listOrigin.x, point.y - listOrigin.y)
                }.toList()
            for ((checkbox, point) in aligned) {
                val probe = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, point.x, point.y, 0)
                val leaf = try {
                    geometry.begin(probe)
                    nativePointerHitPath(layout, Offset(probe.rawX, probe.rawY), PointerType.Touch)
                        .lastOrNull() as? NativeViewPhasePointerNode
                } finally { probe.recycle() }
                if (leaf?.view === checkbox) return checkbox to point
            }
            error("None of ${aligned.size} thumb-aligned checkbox edge points hit their checkbox in Main")
        }

        fun hasCheckboxPress(checkbox: View): Boolean =
            field<Any?>(field<Any>(checkbox, "input"), "pointer") != null

        fun boundIncident(checkbox: View): RecordingIncident {
            val card = generateSequence(checkbox) { it.parent as? View }
                .filterIsInstance<ReverbIncidentCardView>().first()
            return field<IncidentPresentation>(field<Any>(card, "binding"), "row").incident
        }

        fun assertAcknowledged(expected: RecordingIncident) {
            val key = recordingIncidentPresentationKey(expected)
            assertEquals(listOf(key), checkNotNull(incidentAccess).acknowledgements)
            assertEquals(listOf(key), incidentHistory.state.history.rows.filter { it.acknowledged }.map { it.key })
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }

        fun point(x: Float, y: Float): Offset = point(viewport, x, y)

        fun point(view: View, x: Float, y: Float): Offset {
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            val location = IntArray(2).also(view::getLocationOnScreen)
            return Offset(x + location[0] - rootLocation[0], y + location[1] - rootLocation[1])
        }

        fun touch(action: Int, first: Offset, second: Offset? = null, firstId: Int = 0, idleAfter: Boolean = true) {
            eventTime += 16L
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val points = listOfNotNull(first, second)
            val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
                id = if (index == 0) firstId else 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = points[index].x
                y = points[index].y
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(downTime, eventTime, action, points.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue("Main rejected ${MotionEvent.actionToString(action)} at $first " +
                    "with viewport ${viewport.width}x${viewport.height}", root.dispatchTouchEvent(event))
            } finally { event.recycle() }
            if (idleAfter) shadowOf(Looper.getMainLooper()).idle()
        }

        override fun close() {
            try { root.close() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }
    }

    private class FixtureIncidentHistory(private val context: Context) : IncidentHistoryAccess {
        override val revisions = MutableStateFlow(0L)
        val acknowledgements = mutableListOf<String>()
        private var incidents = List(25) { index ->
            RecordingIncident(occurredAtMillis = 1_700_000_000_000L + index * 1_000L, pid = index + 1)
        }

        override suspend fun readHistory(zone: ZoneId) =
            prepareIncidentHistory(incidents, zone = zone, resources = context.resources)

        override fun toggleAcknowledgedInBackground(incident: RecordingIncident) {
            val key = recordingIncidentPresentationKey(incident)
            acknowledgements += key
            incidents = incidents.map {
                if (recordingIncidentPresentationKey(it) == key) toggleRecordingIncidentAcknowledgement(it, 1L) else it
            }
            revisions.value++
        }

        override fun deleteInBackground(incidents: List<RecordingIncident>) = error("The gesture fixture never deletes")
    }

    private companion object {
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        inline fun <reified T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
