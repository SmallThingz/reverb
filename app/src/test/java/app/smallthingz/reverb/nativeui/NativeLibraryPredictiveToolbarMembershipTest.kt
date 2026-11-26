package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLibraryPredictiveToolbarMembershipTest {
    @Test fun predictiveMembershipBindsAndRetiresBeforeProgressWithoutRescanningStableFrames() = Fixture().use { f ->
        assertEquals(1, checkNotNull(f.screen.currentState()).selectedCount)
        assertNull(f.normal())
        val initial = f.refreshes
        f.screen.selectionBackFrame(true, .25f, 1f)
        val first = checkNotNull(f.normal())
        assertEquals(initial + 1, f.refreshes)
        assertTrue("Preview-created toolbar must immediately acquire real accessibility leases", f.ownedToolbars().contains(first))
        val before = checkNotNull(f.screen.currentState())
        f.settleToolbar()
        f.progress(.2f)
        assertFalse("Progress must not relayout the predictive preview toolbar", f.toolbar.isLayoutRequested)
        assertTrue("Reproduce the progress-only fast path", libraryOnlySaveProgressChanged(before, checkNotNull(f.screen.currentState())))
        assertEquals(initial + 1, f.refreshes)
        assertTrue(f.ownedToolbars().contains(first))
        f.screen.selectionBackFrame(true, .6f, 1f)
        assertTrue("Real predictive frames still bind the toolbar", f.toolbar.isLayoutRequested)
        assertEquals("Animation-only frame must not rescan membership", initial + 1, f.refreshes)
        f.screen.selectionBackFrame(true, 0f, 1f)
        assertEquals(initial + 2, f.refreshes)
        assertNull(f.normal())
        assertFalse("Removed toolbar lease must retire immediately", f.ownedToolbars().contains(first))
        f.screen.selectionBackFrame(true, .4f, -1f)
        val second = checkNotNull(f.normal())
        assertNotSame(first, second)
        assertTrue(f.ownedToolbars().contains(second))
        assertEquals(initial + 3, f.refreshes)
        f.screen.selectionBackFrame(false, 0f, -1f)
        assertFalse(f.ownedToolbars().contains(second))
        assertEquals(initial + 4, f.refreshes)
        f.screen.selectionBackFrame(false, 0f, -1f)
        f.progress(.4f)
        assertEquals(initial + 4, f.refreshes)
        f.screen.owner.clearSelection()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, checkNotNull(f.screen.currentState()).selectedCount)
        assertTrue("Main's hidden normal top bar leaves no toolbar leases after selection exits", f.ownedToolbars().isEmpty())
        assertTrue(f.refreshes > initial + 4)
    }

    @Test fun progressSkipsOnlyToolbarLayoutWhileInputThemeAndPhaseChangesStillBind() = Fixture().use { f ->
        f.showNormalToolbar()
        val normal = checkNotNull(f.normal())
        val refreshes = f.refreshes
        f.settleToolbar()
        repeat(3) { index ->
            val before = checkNotNull(f.screen.currentState())
            val progress = (index + 1) / 4f
            f.progress(progress)
            val current = checkNotNull(f.screen.currentState())
            assertTrue(libraryOnlySaveProgressChanged(before, current))
            val pending = (current.list.items as LibraryListSnapshot).pending.single() as LibraryListItem.Pending
            assertEquals(progress, pending.save.progress!!, 0f)
            assertSame(normal, f.normal())
            assertEquals(refreshes, f.refreshes)
            assertFalse("Real Screen publication must not request unchanged toolbar layout", f.toolbar.isLayoutRequested)
        }
        f.changeInput()
        assertTrue("New input/insets must still bind", f.toolbar.isLayoutRequested)
        assertTrue(f.refreshes > refreshes)
        f.settleToolbar()
        f.changeTheme()
        assertTrue("Environment refresh must still bind even with retained input", f.toolbar.isLayoutRequested)
        f.settleToolbar()
        f.progress(.75f, SavePhase.FAILED)
        assertTrue("A phase/semantic change is not the progress-only fast path", f.toolbar.isLayoutRequested)
    }

    private class Fixture : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        // No RecyclerView attachment/layout fallback can accidentally repair the missing callback.
        private val root = FrameLayout(context).apply { suppressLayout(true) }
        private val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        private val frames = AndroidUiFrameClock(Choreographer.getInstance())
        // Existing Library fixture boundary: real Handler delivery, parked IO/animation coroutines.
        private val scope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        } + frames + object : MotionDurationScale { override val scaleFactor = 0f })
        private val record = RecordingEntity("/synthetic/one.wav", "One", "audio/wav", 1L,
            1000L, 32044L, "PCM", RecordingStorageType.FILE, "/synthetic")
        private val job = PendingSave("pending", "Saving", 1L, 1000L, SavePhase.SAVING, 0f)
        private var accessibility: NativeLibraryAccessibilityBinding? = null
        var refreshes = 0
        private val environment = ReverbViewEnvironment(
            Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false))
        private var input = NativeLibraryScreenInput(
            NativeLibraryInput(true, listOf(record), NativeLibraryCallbacks()),
            false, false, LibraryScaffoldInsets(), {}, {}, {}, {})
        val screen = ReverbLibraryScreenContent(context, environment, input,
            lifecycleOwner, OnBackPressedDispatcher(), scope, frames, { 0f }, null, root,
            object : NativeVerticalNestedHost {
                override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
                override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
                override suspend fun preFling(availableY: Float) = 0f
                override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
            }, { refreshes++; accessibility?.refresh() }, {})
        private val window = NativeWindowTextAccessibilityOwner(root,
            NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
        private val scrollEvents = window.openScrollEvents(scope, {}, {})
        init {
            root.addView(screen, FrameLayout.LayoutParams(-1, -1))
            context.setContentView(root)
            set("saveJobsReadiness", DurableSaveJobs.Readiness(loaded = true))
            screen.owner.toggleSelection(record)
            progress(0f)
            accessibility = NativeLibraryAccessibilityBinding(screen, window,
                { view -> NativeSourceAccessibilityNode.fromView(view) }, scrollEvents).also { it.refresh() }
        }
        fun progress(value: Float, phase: SavePhase = SavePhase.SAVING) {
            set("pendingSaves", listOf(job.copy(progress = value, phase = phase)))
            set("pendingItemsDirty", true)
            NativeLibraryController::class.java.getDeclaredMethod("invalidate")
                .apply { isAccessible = true }.invoke(screen.owner)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, checkNotNull(screen.listViewport).height)
        }
        private fun set(name: String, value: Any) = NativeLibraryController::class.java
            .getDeclaredField(name).apply { isAccessible = true }.set(screen.owner, value)
        val toolbar: ReverbLibraryToolbarView
            get() = read(screen, "toolbar") as ReverbLibraryToolbarView
        fun settleToolbar() {
            // Measure/layout the actual production toolbar only. The suppressed root keeps
            // unrelated RecyclerView work from consuming its requestLayout flag afterward.
            toolbar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(toolbar.contentHeightPx, View.MeasureSpec.EXACTLY))
            toolbar.layout(0, 0, toolbar.measuredWidth, toolbar.measuredHeight)
            assertFalse(toolbar.isLayoutRequested)
        }
        fun showNormalToolbar() {
            screen.owner.clearSelection()
            shadowOf(Looper.getMainLooper()).idle()
            input = input.copy(showNormalTopBar = true)
            screen.bind(input)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun changeInput() {
            input = input.copy(hasIncidents = true, scaffoldInsets = LibraryScaffoldInsets(left = 7, right = 11))
            screen.bind(input)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun changeTheme() {
            screen.bind(input, environment.withPalette(ReverbPalette.fallback(true)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun normal(): View? = read(toolbar, "normal") as View?
        fun ownedToolbars(): Set<View> {
            @Suppress("UNCHECKED_CAST")
            val map = read(checkNotNull(accessibility), "toolbarLeases") as Map<View, AutoCloseable>
            return map.keys.toSet()
        }
        override fun close() {
            try { accessibility?.close() } finally {
                try { scrollEvents.close() } finally {
                    try { screen.close() } finally {
                        try { scope.cancel() } finally { activity.pause().stop().destroy() }
                    }
                }
            }
        }
        private fun read(owner: Any, name: String): Any? = owner.javaClass
            .getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }
}
