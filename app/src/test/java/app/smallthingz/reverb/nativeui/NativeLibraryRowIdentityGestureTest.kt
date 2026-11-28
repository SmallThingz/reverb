package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import app.smallthingz.reverb.LibraryListItem
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
import app.smallthingz.reverb.sameRecordingActionTarget
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLibraryRowIdentityGestureTest {
    @Test fun sameIdReplacementCancelsHeldTapAndFreshTapUsesReplacement() = Fixture().use { f ->
        val row = f.row()
        val body = f.target(leading = false)
        f.touch(body, MotionEvent.ACTION_DOWN)
        val replacement = f.current.copy(fileIdentity = "replacement", displayName = "Replacement.wav")
        assertFalse(sameRecordingActionTarget(f.current, replacement))
        f.submit(replacement)
        assertSame("Exercise a retained holder with the same catalog ID", row, f.row())
        assertSame(body, f.target(leading = false))
        f.touch(body, MotionEvent.ACTION_UP)
        assertTrue("A held tap cannot activate the replacement", f.actions.isEmpty())

        f.touch(body, MotionEvent.ACTION_DOWN)
        f.touch(body, MotionEvent.ACTION_UP)
        assertEquals(listOf("activate" to replacement), f.actions)
    }

    @Test fun sameIdReplacementCancelsHeldLeadingLongPressAndFreshPressUsesReplacement() = Fixture().use { f ->
        val row = f.row()
        val leading = f.target(leading = true)
        f.touch(leading, MotionEvent.ACTION_DOWN)
        val replacement = f.current.copy(fileIdentity = "replacement", displayName = "Replacement.wav")
        assertFalse(sameRecordingActionTarget(f.current, replacement))
        f.submit(replacement)
        assertSame(row, f.row())
        assertSame(leading, f.target(leading = true))
        f.longPressTimeout()
        f.touch(leading, MotionEvent.ACTION_UP)
        assertTrue("The retired long-press timer must not select the replacement", f.actions.isEmpty())

        f.touch(leading, MotionEvent.ACTION_DOWN)
        f.longPressTimeout()
        f.touch(leading, MotionEvent.ACTION_UP)
        assertEquals(listOf("select" to replacement), f.actions)
    }

    @Test fun sameAssetMetadataRebindPreservesHeldTapAndLeadingLongPress() {
        for (leading in listOf(false, true)) Fixture().use { f ->
            val row = f.row()
            val target = f.target(leading)
            f.touch(target, MotionEvent.ACTION_DOWN)
            val enriched = f.current.copy(displayName = "Enriched.wav", lastSeenAtMillis = 2L)
            assertTrue(sameRecordingActionTarget(f.current, enriched))
            f.submit(enriched)
            assertSame(row, f.row())
            assertSame(target, f.target(leading))
            if (leading) f.longPressTimeout()
            f.touch(target, MotionEvent.ACTION_UP)
            assertEquals(listOf((if (leading) "select" else "activate") to enriched), f.actions)
        }
    }

    private class Fixture : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val scope = CoroutineScope(Dispatchers.Main.immediate +
            AndroidUiFrameClock(Choreographer.getInstance()) +
            object : MotionDurationScale { override val scaleFactor = 0f })
        private val root = FrameLayout(context)
        var current = RecordingEntity(
            id = "/row-identity/recording.wav", displayName = "Recording.wav", mimeType = "audio/wav",
            startedAtMillis = 1_700_000_000_000L, durationMillis = 1_000L, sizeBytes = 32_044L,
            codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/row-identity",
            fileIdentity = "original", createdAtMillis = 1L, lastSeenAtMillis = 1L,
        )
            private set
        val actions = mutableListOf<Pair<String, RecordingEntity>>()
        private fun record(action: String, id: String) {
            assertEquals(current.id, id)
            actions += action to current
        }
        private val view = ReverbLibraryListView(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            scope, root, LibraryRecordingRowActions(
                activate = { record("activate", it) },
                longClick = { record("menu", it) },
                leadingLongClick = { record("select", it) },
                pointerLongClick = { id, _ -> record("pointer-menu", id) },
            ), object : LibraryInlineExpansionHost {
                override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease =
                    error("This fixture only observes row action delivery")
            }, onRetryFailedSaves = { error("No pending saves in this fixture") })
        private var downTime = 0L

        init {
            root.addView(view, FrameLayout.LayoutParams(400, 500))
            context.setContentView(root)
            submit(current)
            assertTrue(row().isAttachedToWindow)
        }

        fun submit(recording: RecordingEntity) {
            current = recording
            view.submit(LibraryNativeListState(
                items = listOf(LibraryListItem.Recording(recording)),
                selectedIds = emptySet(), selectionActive = false,
                activeMutationIds = emptySet(), localOperationBusyIds = emptySet(),
                expandedOwner = null, trimRequestRecordingId = null, screenActive = true,
            ))
            root.requestLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 500)
            assertTrue("The real holder must finish binding the submitted catalog before the gesture resumes",
                descendants(row()).filterIsInstance<ParagraphView>().any { it.getText() == recording.displayName })
        }

        fun row(): View = checkNotNull(view.attachedRecordingAnchor(current.id))
        fun target(leading: Boolean): View {
            val targets = descendants(row()).filter { it is NativeLibrarySummaryInput }.toList()
            assertEquals(2, targets.size)
            return targets[if (leading) 1 else 0]
        }

        fun touch(target: View, action: Int) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, target.width / 2f, target.height / 2f, 0)
            try {
                val handled = target.onTouchEvent(event)
                if (action == MotionEvent.ACTION_DOWN) assertTrue("Attached row must admit the press", handled)
            } finally {
                event.recycle()
            }
        }

        fun longPressTimeout() = shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))

        override fun close() {
            try { view.dispose() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
            }
        }
    }
}
