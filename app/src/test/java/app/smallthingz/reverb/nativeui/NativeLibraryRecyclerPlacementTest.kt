package app.smallthingz.reverb.nativeui

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.Choreographer
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import app.smallthingz.reverb.LibraryListItem
import app.smallthingz.reverb.PendingSave
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
import app.smallthingz.reverb.SavePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLibraryRecyclerPlacementTest {
    @Test fun timeZoneChangeInvalidatesDayGroupsWhileAttachedAndAfterMissedBroadcast() {
        val original = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            Fixture().use { fixture ->
                val host = Robolectric.buildActivity(Activity::class.java).setup()
                var changes = 0
                fixture.view.onDateFormattingChanged = { changes++ }
                try {
                    host.get().setContentView(fixture.view)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(0, changes)
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
                    RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(1, changes)

                    host.get().setContentView(View(host.get()))
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
                    host.get().setContentView(fixture.view)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(2, changes)
                } finally { host.pause().stop().destroy() }
            }
        } finally { java.util.TimeZone.setDefault(original) }
    }

    @Test fun prependedPendingSaveAndRecordingKeepThePlacedViewportAtAbsoluteTop() = Fixture().use { fixture ->
        val before = fixture.place()
        assertEquals(Anchor(fixture.stableIdAt(0), 0, 0), before)

        fixture.prepend()
        val newFirst = fixture.stableIdAt(0)
        assertNotEquals(before.itemId, newFirst)
        assertEquals(Anchor(newFirst, 0, 0), fixture.place())
    }

    @Test fun prependedRowsKeepTheScrolledRecordingAndItsPixelOffset() = Fixture().use { fixture ->
        fixture.place()
        fixture.view.restorePosition(NativeListSavedPosition(12, 17))
        val before = fixture.place()
        assertEquals(Anchor(fixture.stableIdAt(12), 12, 17), before)

        fixture.prepend()
        assertEquals(before.copy(position = 16), fixture.place())
    }

    @Test fun catalogUpdateWhileRevealIsPendingPlacesTheRecordingAtItsNewIndex() = Fixture().use { fixture ->
        fixture.place()
        val targetId = fixture.recordings[13].recording.id
        val stableId = fixture.stableIdAt(14)
        assertTrue(fixture.view.revealRecording(targetId))
        assertEquals(targetId, fixture.view.pendingRecordingRevealId)

        fixture.prepend()
        assertEquals(targetId, fixture.view.pendingRecordingRevealId)
        assertEquals(Anchor(stableId, 18, 0), fixture.place())
        assertNull(fixture.view.pendingRecordingRevealId)
    }

    private data class Anchor(val itemId: Long, val position: Int, val offset: Int)

    private class Fixture : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val scope = CoroutineScope(Dispatchers.Main.immediate + AndroidUiFrameClock(Choreographer.getInstance()))
        val view = ReverbLibraryListView(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            scope, View(context), LibraryRecordingRowActions(null, null, null), object : LibraryInlineExpansionHost {
                override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease =
                    error("Placement fixture has no playback owner")
            }, onRetryFailedSaves = { error("Placement fixture has no save owner") })
        val recordings = List(30) { recording("initial-$it") }
        private val rows = listOf(LibraryListItem.Header("Initial date")) + recordings
        private val list = view.scrollView
        private val layout = list.layoutManager as LinearLayoutManager

        init { submit(rows) }

        fun prepend() {
            val pending = PendingSave(id = "pending", displayName = "Pending save",
                startedAtMillis = 1_700_000_000_000L, durationMillis = 1_000L, phase = SavePhase.QUEUED)
            submit(listOf(LibraryListItem.Header("Pending saves"), LibraryListItem.Pending(pending),
                LibraryListItem.Header("New date"), recording("new")) + rows)
        }

        private fun submit(items: List<LibraryListItem>) {
            view.submit(LibraryNativeListState(items, emptySet(), false, emptySet(), emptySet(), null, null, true))
        }

        fun stableIdAt(position: Int): Long = checkNotNull(list.adapter).getItemId(position)

        fun place(): Anchor {
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 500)
            val position = layout.findFirstVisibleItemPosition()
            val child = checkNotNull(layout.findViewByPosition(position))
            return Anchor(list.getChildViewHolder(child).itemId, position,
                list.paddingTop - layout.getDecoratedTop(child))
        }

        override fun close() {
            try { view.dispose() } finally { scope.cancel() }
        }

        private fun recording(label: String) = LibraryListItem.Recording(RecordingEntity(
            id = "/synthetic-library/$label.wav", displayName = "Library row $label", mimeType = "audio/wav",
            startedAtMillis = 1_700_000_000_000L, durationMillis = 1_000L, sizeBytes = 32_044L,
            codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/synthetic-library"))
    }
}
