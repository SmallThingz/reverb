package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.Context
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
import org.junit.Assert.*
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

/** Real adapter, expanding shared row and RecyclerView placement; no storage/player effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLibraryExpansionPlacementTest {
    @Test fun lastInfoCardBecomesFullyVisibleWithoutRebuildingCatalog() = Fixture().use { f ->
        val before = f.lastRow()
        val collapsedHeight = before.height
        val bottomBefore = before.bottom
        f.expand(220)
        val after = f.lastRow()
        assertSame(before, after)
        assertTrue(after.height > collapsedHeight)
        assertTrue("Whole expanded row must fit", after.height <= f.viewport.height)
        assertTrue("Top visible", after.top >= f.viewport.paddingTop)
        assertTrue("Bottom visible", after.bottom <= f.viewport.height - f.viewport.paddingBottom)
        assertTrue("Expansion moved the bottom row up", after.top < bottomBefore - collapsedHeight)
        assertEquals("Expansion must not rebuild finished entries", 0, f.finishedEntries)
        assertEquals("Expansion must not diff the catalog", 0, f.catalogDiffs)
    }

    @Test fun oversizedInfoAnchorsItsStartAndUserScrollCanReachTheEnd() = Fixture().use { f ->
        f.expand(900)
        val row = f.lastRow()
        assertTrue(row.height > f.viewport.height)
        assertEquals(f.viewport.paddingTop, row.top)
        f.viewport.sourceOnUserScroll?.invoke()
        f.viewport.scrollBy(0, row.height)
        f.settle()
        assertTrue("Oversized tail remains reachable", f.lastRow().bottom <= f.viewport.height)
        val topAfterScroll = f.lastRow().top
        f.submitInfo()
        f.settle()
        assertEquals("Same publication must not fight the user", topAfterScroll, f.lastRow().top)
    }

    @Test fun dismissalAndAcceptedUserInputRetirePostedRevealsBeforePlacement() = Fixture().use { f ->
        f.expand(220)
        // Grow the real expansion again, then interrupt before the posted reveal drains.
        f.expansion.minimumHeight = 320
        f.expansion.requestLayout()
        f.place()
        f.viewport.sourceOnUserScroll?.invoke()
        f.viewport.scrollBy(0, -70)
        val interruptedTop = f.lastRow().top
        f.submitInfo()
        f.settle()
        assertEquals(interruptedTop, f.lastRow().top)
        f.submitInfo(false)
        f.settle()
        val dismissedTop = f.lastRow().top
        f.settle()
        assertEquals("No delayed reveal after dismissal", dismissedTop, f.lastRow().top)
    }

    private class Fixture : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup()
        private val context = activity.get()
        private val scale = object : MotionDurationScale { override val scaleFactor = 0f }
        private val scope = CoroutineScope(Dispatchers.Main.immediate +
            AndroidUiFrameClock(Choreographer.getInstance()) + scale)
        private val root = FrameLayout(context)
        val expansion = View(context)
        private val records = List(12) { index -> LibraryListItem.Recording(RecordingEntity(
            id = "/synthetic-expansion/$index.wav", displayName = "Row $index", mimeType = "audio/wav",
            startedAtMillis = 1_700_000_000_000L, durationMillis = 1_000L, sizeBytes = 32_044L,
            codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/synthetic-expansion")) }
        private val items: List<LibraryListItem> = listOf(LibraryListItem.Header("Today")) + records
        private val id = records.last().recording.id
        private val view = ReverbLibraryListView(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            scope, root, LibraryRecordingRowActions(null, null, null), object : LibraryInlineExpansionHost {
                override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease =
                    error("No playback in placement fixture")
                override fun createInfo(context: Context, recordingId: String) = object : LibraryInlineExpansionLease {
                    override val view: View get() = expansion
                    override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
                                      screenActive: Boolean, trimRequested: Boolean) = Unit
                    override fun setEnvironment(environment: ReverbViewEnvironment) = Unit
                    override fun setPresentationAttached(attached: Boolean) = Unit
                    override fun closePresentation() = Unit
                }
            }, onRetryFailedSaves = { error("No saves in placement fixture") })
        val viewport get() = view.scrollView
        var finishedEntries = 0
        var catalogDiffs = 0

        init {
            root.addView(view, FrameLayout.LayoutParams(400, 500))
            context.setContentView(root)
            submitInfo(false)
            settle()
            viewport.scrollToPosition(checkNotNull(viewport.adapter).itemCount - 1)
            settle()
            view.workObserver = object : LibraryListWorkObserver {
                override fun finishedEntryCreated() { finishedEntries++ }
                override fun catalogDiff() { catalogDiffs++ }
                override fun rowBound(key: String) = Unit
            }
        }

        fun submitInfo(open: Boolean = true) {
            view.submit(LibraryNativeListState(items, emptySet(), false, emptySet(), emptySet(),
                null, null, true, infoRecordingId = id.takeIf { open }))
        }
        fun expand(height: Int) { expansion.minimumHeight = height; submitInfo(); settle() }
        fun lastRow() = checkNotNull(view.attachedRecordingAnchor(id))
        fun place() {
            root.requestLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 500)
        }
        fun settle() {
            // Bounded frame advancement includes actual row animation/layout and posted reveal.
            repeat(8) { place(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        }
        override fun close() {
            try { view.dispose() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }
    }
}
