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
import app.smallthingz.reverb.InlinePlaybackRowOwner
import app.smallthingz.reverb.LibraryListItem
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
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
import smallthingz.ui.platform.AndroidUiFrameClock
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
        val defaultColumn = viewport.width - 3
        assertFalse(viewport.hasColorInColumn(defaultColumn, light))

        val rows = List(20, f::recording)
        f.submit(rows)
        f.place()
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewport.layoutDirection)
        assertTrue(viewport.canScrollVertically(1))
        assertTrue(viewport.childCount in 1 until rows.size)
        assertTrue(viewport.hasColorInColumn(defaultColumn, light))
        assertFalse(viewport.hasColorInColumn(2, light))

        val anchor = NativeListSavedPosition(6, 17)
        f.view.restorePosition(anchor)
        f.place()
        assertEquals(anchor, f.view.savePosition())
        val rowId = rows[anchor.firstVisibleItemIndex].recording.id
        val rowBounds = checkNotNull(f.view.attachedRecordingAnchor(rowId)).bounds()
        val expectedViewportBounds = Rect(16, 0, 384, 500)
        assertEquals(expectedViewportBounds, viewport.bounds())
        assertEquals(Rect(), viewport.padding())
        val adapter = checkNotNull(viewport.adapter)
        val layoutManager = checkNotNull(viewport.layoutManager)
        val onUserScroll = checkNotNull(viewport.sourceOnUserScroll)

        val appearance = f.light.appearance
        val inset = appearance.scrollIndicator.copy(rightInsetDp = 8f)
        val dark = ReverbViewEnvironment.withTestAppearance(f.light.textDensity, f.light.sharedFontResolver,
            ReverbPalette.fallback(true), appearance.copy(scrollIndicator = inset))
        f.view.setEnvironment(dark)
        f.place()
        assertSame(viewport, f.view.scrollView)
        assertSame(adapter, viewport.adapter)
        assertSame(layoutManager, viewport.layoutManager)
        assertSame(onUserScroll, viewport.sourceOnUserScroll)
        assertFalse(viewport.isVerticalScrollBarEnabled)
        assertEquals(expectedViewportBounds, viewport.bounds())
        assertEquals(Rect(), viewport.padding())
        assertEquals(anchor, f.view.savePosition())
        assertEquals(rowBounds, checkNotNull(f.view.attachedRecordingAnchor(rowId)).bounds())
        val reboundColumn = viewport.width - 9
        assertTrue(viewport.hasColorInColumn(reboundColumn, dark.palette.muted))
        assertFalse(viewport.hasColorInColumn(defaultColumn, dark.palette.muted))
        assertFalse(viewport.hasColorInColumn(defaultColumn, light))

        viewport.scrollBy(0, 31)
        assertNotEquals(anchor, f.view.savePosition())
        f.view.clearListComposition()
        f.place()
        assertSame(viewport, f.view.scrollView)
        assertEquals(0, adapter.itemCount)
        assertFalse(viewport.canScrollVertically(1))
        assertFalse(viewport.hasColorInColumn(reboundColumn, dark.palette.muted))
    }

    @Test fun inlineInfoCanCreateAndRemoveOverflowInTheSameRow() = Fixture().use { f ->
        val recording = f.recording(0)
        val items = listOf(recording)
        val id = recording.recording.id
        f.submit(items)
        f.settle()
        val viewport = f.view.scrollView
        val row = checkNotNull(f.view.attachedRecordingAnchor(id))
        val column = viewport.width - 3
        val color = f.light.palette.muted
        assertFalse(viewport.canScrollVertically(1))
        assertFalse(viewport.hasColorInColumn(column, color))

        f.submit(items, infoId = id)
        f.settle()
        assertSame(row, f.view.attachedRecordingAnchor(id))
        assertTrue(viewport.canScrollVertically(1))
        assertTrue(viewport.hasColorInColumn(column, color))
        checkNotNull(viewport.sourceOnUserScroll).invoke()
        viewport.scrollBy(0, viewport.height)
        assertTrue(viewport.canScrollVertically(-1))

        f.submit(items)
        f.settle()
        assertSame(viewport, f.view.scrollView)
        assertFalse(viewport.canScrollVertically(-1))
        assertFalse(viewport.canScrollVertically(1))
        assertFalse(viewport.hasColorInColumn(column, color))
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

        fun submit(items: List<LibraryListItem>, infoId: String? = null) {
            view.submit(LibraryNativeListState(items, emptySet(), false, emptySet(), emptySet(),
                null, null, true, infoRecordingId = infoId))
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
