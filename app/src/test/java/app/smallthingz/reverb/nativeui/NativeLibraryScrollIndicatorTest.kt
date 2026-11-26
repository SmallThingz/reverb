package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import app.smallthingz.reverb.InlinePlaybackRowOwner
import app.smallthingz.reverb.LibraryListItem
import app.smallthingz.reverb.LibraryListSnapshot
import app.smallthingz.reverb.PendingSave
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
import app.smallthingz.reverb.SavePhase
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.scroll.NativeScrollIndicatorStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLibraryScrollIndicatorTest {
    @Test fun overflowAndThemeRebindUseTheRetainedPhysicalRightViewport() = Fixture().use { f ->
        val viewport = f.view.scrollView
        viewport.layoutDirection = View.LAYOUT_DIRECTION_RTL
        f.place()
        val light = f.light.palette.muted
        val appearance = f.light.appearance
        val defaultColumn = f.view.thumbColumn(checkNotNull(appearance.scrollIndicator))
        assertFalse(viewport.scrollIndicatorPresentationActive)
        assertFalse(f.view.hasColorInColumn(defaultColumn, light))

        val rows = List(20, f::recording)
        f.submit(rows)
        f.place()
        assertTrue(viewport.scrollIndicatorPresentationActive)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewport.layoutDirection)
        assertTrue(viewport.canScrollVertically(1))
        assertTrue(viewport.childCount in 1 until rows.size)
        viewport.scrollBy(0, 1)
        assertTrue(f.view.hasColorInColumn(defaultColumn, light))
        assertFalse(f.view.hasColorInColumn(f.view.width - 1 - defaultColumn, light))

        val anchor = NativeListSavedPosition(6, 17)
        f.view.restorePosition(anchor)
        f.place()
        assertEquals(anchor, f.view.savePosition())
        val rowId = rows[anchor.firstVisibleItemIndex].recording.id
        val rowBounds = checkNotNull(f.view.attachedRecordingAnchor(rowId)).bounds()
        val expectedViewportBounds = Rect(0, 0, 400, 500)
        val expectedRowInsets = Rect(16, 0, 16, 0)
        assertEquals(expectedViewportBounds, viewport.bounds())
        assertEquals(expectedRowInsets, viewport.padding())
        assertEquals(16, rowBounds.left)
        assertEquals(384, rowBounds.right)
        val adapter = checkNotNull(viewport.adapter)
        val layoutManager = checkNotNull(viewport.layoutManager)
        val onUserScroll = checkNotNull(viewport.sourceOnUserScroll)

        val inset = checkNotNull(appearance.scrollIndicator).copy(rightInsetDp = 8f)
        val dark = ReverbViewEnvironment.withTestAppearance(f.light.textDensity, f.light.sharedFontResolver,
            ReverbPalette.fallback(true), appearance.copy(scrollIndicator = inset))
        f.view.setEnvironment(dark)
        f.place()
        assertSame(viewport, f.view.scrollView)
        assertSame(adapter, viewport.adapter)
        assertSame(layoutManager, viewport.layoutManager)
        assertSame(onUserScroll, viewport.sourceOnUserScroll)
        assertTrue(viewport.isVerticalScrollBarEnabled)
        assertEquals(expectedViewportBounds, viewport.bounds())
        assertEquals(expectedRowInsets, viewport.padding())
        assertEquals(anchor, f.view.savePosition())
        assertEquals(rowBounds, checkNotNull(f.view.attachedRecordingAnchor(rowId)).bounds())
        val reboundColumn = f.view.thumbColumn(inset)
        viewport.scrollBy(0, 1)
        assertTrue(f.view.hasColorInColumn(reboundColumn, dark.palette.muted))
        assertFalse(f.view.hasColorInColumn(defaultColumn, dark.palette.muted))
        assertFalse(f.view.hasColorInColumn(defaultColumn, light))

        viewport.scrollBy(0, 31)
        assertNotEquals(anchor, f.view.savePosition())
        f.submit(rows, screenActive = false)
        assertFalse(viewport.scrollIndicatorPresentationActive)
        f.submit(rows)
        assertTrue(viewport.scrollIndicatorPresentationActive)
        f.view.clearListComposition()
        f.place()
        assertSame(viewport, f.view.scrollView)
        assertFalse(viewport.scrollIndicatorPresentationActive)
        assertEquals(0, adapter.itemCount)
        assertFalse(viewport.canScrollVertically(1))
        assertFalse(f.view.hasColorInColumn(reboundColumn, dark.palette.muted))
    }

    @Test fun thumbEndPlacementSurvivesAPendingSavePrefixAndLiftBeforeLayout() {
        for (prefixBeforeMove in listOf(false, true)) Fixture().use { f ->
            val viewport = f.view.scrollView
            val adapter = checkNotNull(viewport.adapter)
            val layout = viewport.layoutManager as LinearLayoutManager
            val finished = buildList<LibraryListItem> {
                repeat(6) { day ->
                    add(LibraryListItem.Header("Day $day"))
                    repeat(5) { add(f.recording(day * 5 + it)) }
                }
            }
            val original = LibraryListSnapshot.publish(emptyList(), emptyList(), finished)
            val pending = PendingSave(id = "pending", displayName = "Pending save",
                startedAtMillis = 1_700_000_000_000L, durationMillis = 1_000L, phase = SavePhase.QUEUED)
            val prefix = listOf(LibraryListItem.Header("Pending saves"), LibraryListItem.Pending(pending))
            val withPrefix = LibraryListSnapshot.publish(original, prefix, finished)
            assertSame(original.finishedIdentity, withPrefix.finishedIdentity)
            f.submit(original)
            f.place()
            val originalCount = adapter.itemCount
            val top = NativeListSavedPosition(0, 0)
            viewport.scrollBy(0, 1)
            viewport.scrollBy(0, -1)
            assertEquals(top, f.view.savePosition())
            val style = checkNotNull(f.light.appearance.scrollIndicator)
            assertTrue(f.view.hasColorInColumn(f.view.thumbColumn(style), f.light.palette.muted))
            val start = Offset(viewport.width - viewport.paddingRight / 2f, style.minThumbHeightDp / 2f)
            val end = Offset(start.x, viewport.height * 2f)
            viewport.thumbChange(start, previouslyPressed = false)
            assertTrue(viewport.isScrollIndicatorDragging)

            // Exercise an automatic top queued both after and before the user's jump.
            if (prefixBeforeMove) f.submit(withPrefix)
            viewport.thumbChange(end, previous = start)
            assertTrue(viewport.hasPendingScrollIndicatorPlacement)
            if (!prefixBeforeMove) f.submit(withPrefix)
            assertEquals(originalCount + prefix.size, adapter.itemCount)
            assertEquals(top, f.view.savePosition())
            viewport.thumbChange(end, pressed = false)
            assertFalse(viewport.isScrollIndicatorDragging)
            assertTrue(viewport.hasPendingScrollIndicatorPlacement)

            f.place()

            val last = checkNotNull(layout.findViewByPosition(adapter.itemCount - 1))
            assertEquals(viewport.height - viewport.paddingBottom, layout.getDecoratedBottom(last))
            assertFalse(viewport.canScrollVertically(1))
            assertFalse(viewport.hasPendingScrollIndicatorPlacement)
        }
    }

    @Test fun inlineInfoCanCreateAndRemoveOverflowInTheSameRow() = Fixture().use { f ->
        val recording = f.recording(0)
        val items = listOf(recording)
        val id = recording.recording.id
        f.submit(items)
        f.settle()
        val viewport = f.view.scrollView
        val row = checkNotNull(f.view.attachedRecordingAnchor(id))
        val column = f.view.thumbColumn(checkNotNull(f.light.appearance.scrollIndicator))
        val color = f.light.palette.muted
        assertFalse(viewport.canScrollVertically(1))
        assertFalse(f.view.hasColorInColumn(column, color))

        f.submit(items, infoId = id)
        f.settle()
        assertSame(row, f.view.attachedRecordingAnchor(id))
        assertTrue(viewport.canScrollVertically(1))
        viewport.scrollBy(0, 1)
        assertTrue(f.view.hasColorInColumn(column, color))
        checkNotNull(viewport.sourceOnUserScroll).invoke()
        viewport.scrollBy(0, viewport.height)
        assertTrue(viewport.canScrollVertically(-1))

        f.submit(items)
        f.settle()
        assertSame(viewport, f.view.scrollView)
        assertFalse(viewport.canScrollVertically(-1))
        assertFalse(viewport.canScrollVertically(1))
        assertFalse(f.view.hasColorInColumn(column, color))
    }

    private fun View.hasColorInColumn(x: Int, color: Int): Boolean {
        val bitmap = Bitmap.createBitmap(1, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap).apply { translate(-x.toFloat(), 0f) })
            return (0 until height).any { bitmap.getPixel(0, it) == color }
        } finally {
            bitmap.recycle()
        }
    }

    private fun View.bounds() = Rect(left, top, right, bottom)
    private fun View.padding() = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    private fun View.thumbColumn(style: NativeScrollIndicatorStyle) =
        (width - (style.rightInsetDp + style.widthDp / 2f) * resources.displayMetrics.density).toInt()

    private fun ReverbLibraryRecyclerView.thumbChange(position: Offset, previous: Offset = position,
        pressed: Boolean = true, previouslyPressed: Boolean = true) {
        val change = NativeDragPointerChange(0L, 0L, position, previous, pressed, previouslyPressed,
            PointerType.Touch, position)
        scrollIndicatorPointerNode.dispatch(NativeDragPointerEvent(listOf(change), Offset.Zero),
            NativePointerPass.INITIAL)
    }

    private class Fixture : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup()
        private val context = activity.get()
        private val scale = object : MotionDurationScale { override val scaleFactor = 0f }
        private val scope = CoroutineScope(Dispatchers.Main.immediate +
            AndroidUiFrameClock(Choreographer.getInstance()) + scale)
        private val root = FrameLayout(context)
        val light = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false))
        val view = ReverbLibraryListView(context, light, scope, root, LibraryRecordingRowActions(null, null, null),
            object : LibraryInlineExpansionHost {
                override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease =
                    error("The fixture has no playback owner")

                override fun createInfo(context: Context, recordingId: String) = object : LibraryInlineExpansionLease {
                    override val view = View(context).apply { minimumHeight = 900 }
                    override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
                        screenActive: Boolean, trimRequested: Boolean) = Unit
                    override fun setEnvironment(environment: ReverbViewEnvironment) = Unit
                    override fun setPresentationAttached(attached: Boolean) = Unit
                    override fun closePresentation() = Unit
                }
            }, onRetryFailedSaves = { error("The fixture has no save owner") })

        init {
            root.addView(view, FrameLayout.LayoutParams(400, 500))
            context.setContentView(root)
        }

        fun recording(index: Int) = LibraryListItem.Recording(RecordingEntity(
            id = "/synthetic-scroll-indicator/$index.wav", displayName = "Recording $index", mimeType = "audio/wav",
            startedAtMillis = 1_700_000_000_000L, durationMillis = 1_000L, sizeBytes = 32_044L,
            codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/synthetic-scroll-indicator"))

        fun submit(items: List<LibraryListItem>, infoId: String? = null, screenActive: Boolean = true) {
            view.submit(LibraryNativeListState(items, emptySet(), false, emptySet(), emptySet(),
                null, null, screenActive, infoRecordingId = infoId))
        }

        fun place() {
            root.requestLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 500)
        }

        fun settle() {
            repeat(8) {
                place()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            }
        }

        override fun close() {
            try { view.dispose() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }
    }
}
