package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.NativeIncidentsScreenView
import app.smallthingz.reverb.nativeui.NativeWindowTextAccessibilityOwner
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.ReverbIncidentsContentView
import smallthingz.ui.reverb.ReverbPalette
import smallthingz.ui.reverb.ReverbSelectionTopBarLayout
import smallthingz.ui.reverb.ReverbToolbarActionView
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density

/** Real Main admission, source-bound header and incident controller; only the store is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeMainIncidentsMotionAdmissionTest {
    @Test fun predictiveCancelKeepsHeaderEffectsBlockedThroughReturnAndRestoresDeletion() = Fixture(true).use { f ->
        val panel = f.panel
        assertTrue(f.main.admitsSurfaceInput(panel))
        f.dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        f.idle()
        // A gesture owns admission even before its first nonzero visual displacement.
        f.assertHeaderBlocked(panel)
        f.dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .02f, BackEventCompat.EDGE_LEFT))
        f.idle()
        assertTrue(f.owner.openProgress in 0f..1f && f.owner.openProgress < 1f)
        f.assertHeaderBlocked(panel)

        f.dispatcher.dispatchOnBackCancelled()
        f.idle()
        f.frames(3)
        assertTrue(f.owner.openProgress < 1f)
        f.assertHeaderBlocked(panel)
        f.frames(60)
        assertEquals(1f, f.owner.openProgress, 0f)
        assertSame(panel, f.panel)
        assertTrue(f.main.admitsSurfaceInput(panel))

        f.click(f.select(panel))
        f.click(f.select(panel))
        f.click(f.destructive(panel))
        assertEquals(listOf(listOf(f.store.incident)), f.store.deleted)
        assertFalse(panel.selectionActive)
        f.click(f.destructive(panel))
        assertNotNull(f.clearSheet(panel))
        assertEquals("Opening Clear does not submit another deletion", 1, f.store.deleted.size)
    }

    @Test fun entryAndExitBlockHeaderEffectsWhileAccessibleBackCanClose() = Fixture(false).use { f ->
        f.open()
        f.frames(12)
        val panel = f.panel
        assertTrue(f.owner.openProgress > 0f && f.owner.openProgress < 1f)
        f.assertHeaderBlocked(panel)
        val back = f.back(panel).accessibilityAction
        val node = back.createAccessibilityNodeInfo()
        try {
            assertNotNull(node)
            assertTrue(node.isVisibleToUser)
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        } finally {
            @Suppress("DEPRECATION")
            node?.recycle()
        }
        f.click(f.back(panel))
        assertFalse(f.main.saveState().navigation.showIncidents)
        f.assertHeaderBlocked(panel)
        f.frames(60)
        assertNull(f.screen.pointerContent)
        assertTrue(f.store.deleted.isEmpty())
        f.open()
        f.frames(60)
        assertTrue(f.main.admitsSurfaceInput(f.panel))
        f.click(f.select(f.panel))
        assertTrue(f.panel.selectionActive)
        f.click(f.back(f.panel))
        assertFalse(f.panel.selectionActive)
        assertTrue("Selection Back keeps Incidents open", f.main.saveState().navigation.showIncidents)
    }

    private class Store(private val context: Context) : IncidentHistoryAccess {
        override val revisions = MutableStateFlow(0L)
        val incident = RecordingIncident(occurredAtMillis = 1_700_000_000_000L, pid = 42)
        val deleted = mutableListOf<List<RecordingIncident>>()
        override suspend fun readHistory(zone: ZoneId) =
            prepareIncidentHistory(listOf(incident), zone = zone, resources = context.resources)
        override fun toggleAcknowledgedInBackground(incident: RecordingIncident) = error("Unexpected acknowledgement")
        override fun deleteInBackground(incidents: List<RecordingIncident>) { deleted += incidents.toList() }
    }

    private class Fixture(initiallyOpen: Boolean) : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val board = FrameLayout(context)
        val dispatcher = OnBackPressedDispatcher { error("Unexpected fallback Back") }
        private val clock = FrameClock()
        // Defer the first history read until the replacement store is installed.
        private val scope = CoroutineScope(Dispatchers.Main + clock +
            object : MotionDurationScale { override val scaleFactor = 1f })
        val store = Store(context)
        private val text = NativeWindowTextAccessibilityOwner(board,
            NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
        val main = NativeMainScreenView.create(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            NativeMainScreenInput(false, false), this, dispatcher, scope, null, board, text,
            NativeMainScreenSavedState(navigation = NativeMainSavedNavigation(showIncidents = initiallyOpen)),
            { error("Unexpected permission action") }, {},
            registerDirectoryResult = { error("Unexpected Settings") }, releaseInputFocus = {})
        val owner = field(main, "incidentsOwner") as NativeIncidentsScreenOwner
        val screen = field(main, "incidents") as NativeIncidentsScreenView
        val panel get() = checkNotNull(screen.pointerContent)

        init {
            IncidentHistoryController::class.java.getDeclaredField("access").apply { isAccessible = true }
                .set(owner.history, store)
            context.setContentView(board)
            board.addView(main, FrameLayout.LayoutParams(-1, -1))
            activity.windowFocusChanged(true)
            main.start()
            idle()
            assertTrue(owner.state.canMutate)
            assertEquals(1, owner.state.history.rows.size)
        }

        fun idle() {
            shadowOf(Looper.getMainLooper()).idle()
            board.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            board.layout(0, 0, 360, 600)
        }
        fun frames(count: Int) = repeat(count) { clock.frame(); idle() }
        fun open() {
            val action: NativeMainNavigationState.() -> Unit = { openIncidents() }
            NativeMainScreenView::class.java.getDeclaredMethod("navigate", Function1::class.java)
                .apply { isAccessible = true }.invoke(main, action)
            idle()
        }
        private fun bar(panel: ReverbIncidentsContentView) =
            (panel.getChildAt(0) as ViewGroup).getChildAt(0) as ReverbSelectionTopBarLayout
        fun back(panel: ReverbIncidentsContentView) = bar(panel).getChildAt(0) as ReverbToolbarActionView
        private fun actions(panel: ReverbIncidentsContentView) =
            (bar(panel).getChildAt(2) as NativeGroupLayout).pointerContent
        fun select(panel: ReverbIncidentsContentView) = actions(panel).getChildAt(1) as ReverbToolbarActionView
        fun destructive(panel: ReverbIncidentsContentView) = actions(panel).getChildAt(0) as ReverbToolbarActionView
        fun clearSheet(panel: ReverbIncidentsContentView) = field(panel, "clearSheet")
        fun click(action: ReverbToolbarActionView) {
            assertTrue(action.accessibilityAction.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            idle()
        }
        fun assertHeaderBlocked(panel: ReverbIncidentsContentView) {
            click(select(panel))
            assertFalse("Motion must not enter selection", panel.selectionActive)
            click(destructive(panel))
            assertNull("Motion must not open Clear", clearSheet(panel))
            assertTrue(store.deleted.isEmpty())
            assertFalse(main.admitsSurfaceInput(panel))
        }
        override fun close() {
            try { main.close() } finally {
                try { scope.cancel() } finally {
                    lifecycle.currentState = Lifecycle.State.DESTROYED
                    activity.pause().stop().destroy()
                }
            }
        }
    }

    private class FrameClock : MonotonicFrameClock {
        private val pending = linkedSetOf<(Long) -> Unit>()
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            suspendCancellableCoroutine { continuation ->
                val callback: (Long) -> Unit = { time ->
                    if (continuation.isActive) continuation.resumeWith(runCatching { onFrame(time) })
                }
                pending += callback
                continuation.invokeOnCancellation { pending -= callback }
            }
        fun frame() {
            nanos += 16_000_000L
            val callbacks = pending.toList()
            pending.clear()
            callbacks.forEach { it(nanos) }
        }
    }

    private companion object {
        fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }
}
