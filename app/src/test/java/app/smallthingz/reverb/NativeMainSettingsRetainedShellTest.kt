package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density
import kotlin.coroutines.CoroutineContext

/** One Main lifetime, ordinary no-Save navigation; no heap/PSS or physical Service/IO claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class,
    shadows = [SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMainSettingsRetainedShellTest {
    @Test fun fixedCloseSequenceRetainsOneShellAndRetiresItsOwnersAtMainTeardown() = Fixture().use { f ->
        f.open()
        val shell = f.settings
        val owner = shell.owner
        val motion = f.motion
        val staleOpen = f.openAction
        val service = Robolectric.buildService(ReverbService::class.java).get() // No onCreate/start/bind.
        val binder = service.BackgroundRecorderBinder()
        val component = ComponentName(f.context, ReverbService::class.java)
        val staleConnection = f.context.connections.single()
        staleConnection.onServiceConnected(component, binder)
        assertSame("The callback must be demonstrably live before retirement", service, f.binding.service)
        var closedObservers: Int? = null
        var closes = 0
        for (batch in listOf(3, 10, 10)) repeat(batch) {
            assertSame(shell, f.settings)
            assertSame(owner, f.settings.owner)
            assertSame(motion, f.motion)
            assertTrue(shell.inputAllowed)
            assertTrue(f.main.admitsSurfaceInput(shell))
            assertEquals(1, f.context.connections.size)
            assertFalse(owner.state.persisting || owner.state.hasUnsavedChanges)
            assertEquals(1f, motion.panelProgress, 0f)
            assertEquals(0, f.pendingMotionFrames)
            val liveActivation = owner.state.activation
            f.back.onBackPressed() // Actual registered Main Settings Back callback.
            f.settle()
            closes++
            assertFalse(f.main.saveState().navigation.showSettings)
            assertSame(shell, f.settings)
            assertTrue(shell.isAttachedToWindow)
            assertEquals(.01f, shell.alpha, 0f)
            assertFalse(shell.inputAllowed)
            assertFalse(f.main.admitsSurfaceInput(shell))
            assertTrue(f.navigation.homeGesturesEnabled)
            assertEquals(0f, motion.panelProgress, 0f)
            assertEquals(0, f.pendingMotionFrames)
            assertNull(field<Job?>(motion, "animation"))
            assertTrue(f.context.connections.isEmpty())
            assertNull(f.binding.service)
            assertFalse(field<Boolean>(f.move, "active"))
            assertNull(field<Any?>(field<Any>(f.move, "queue"), "pending"))
            if (closedObservers == null) closedObservers = f.lifecycle.observerCount
            assertEquals(closedObservers, f.lifecycle.observerCount)
            assertEquals(1, f.registrations)
            assertEquals(0, f.unregistrations)
            val publications = f.publications
            val revision = owner.state.editRevision
            staleConnection.onServiceConnected(component, binder)
            owner.navigationAction(liveActivation)
            assertEquals(revision, owner.state.editRevision)
            assertFalse(f.main.saveState().navigation.showSettings)
            assertNull(f.binding.service)
            assertTrue(f.context.connections.isEmpty())
            assertEquals(publications, f.publications)
            if (closes < 23) {
                f.open()
                assertNotSame(liveActivation, owner.state.activation)
                val openedPublications = f.publications
                staleConnection.onServiceConnected(component, binder)
                owner.navigationAction(liveActivation)
                assertNull(f.binding.service)
                assertTrue(f.main.saveState().navigation.showSettings)
                assertEquals(openedPublications, f.publications)
            }
        }
        assertEquals(23, closes)
        assertEquals(23, f.context.binds)
        assertEquals(f.context.binds, f.context.unbinds)
        assertTrue("Actual move owner invoked the finite scan boundary", f.scans > 0)
        // A teardown-only reopen gives Main.close live ownership to retire, beyond the 23 closes.
        f.open(settleMotion = false)
        f.startMotionWithoutFrame()
        val teardownActivation = owner.state.activation
        val liveConnection = f.context.connections.single()
        liveConnection.onServiceConnected(component, binder)
        assertSame(service, f.binding.service)
        assertEquals(24, f.context.binds)
        assertEquals(23, f.context.unbinds)
        val outstanding = f.outstandingMotionRequest()
        assertTrue(checkNotNull(outstanding.job).isActive)
        assertTrue(checkNotNull(field<Job?>(motion, "animation")).isActive)
        assertTrue(motion.panelProgress < 1f)
        val observerTree = f.main.viewTreeObserver
        val preDraw = field<Any>(f.main, "settingsGeometry")
        val globalLayout = field<Any>(f.main, "settingsWindowLayout")
        assertTrue(hasListener(observerTree, "mOnPreDrawListeners", preDraw))
        assertTrue(hasListener(observerTree, "mOnGlobalLayoutListeners", globalLayout))
        val snapshot = owner.snapshot
        val published = f.publications
        f.retireMain() // Synchronous Main.close only; sample before draining cancellation work.
        // quickSaveEnabled keeps the notification wheel live. Its close notifies the still-live
        // owner once, after the Settings renderer is closed but before owner.close retires it.
        assertEquals("Exactly one synchronous wheel cleanup notification", published + 1, f.publications)
        assertTrue(field<Boolean>(shell, "closed"))
        assertTrue(field<Boolean>(owner, "closed"))
        assertSame(snapshot, owner.snapshot)
        val retiredPublications = f.publications
        val retiredSnapshot = owner.snapshot
        f.drainRetired()
        assertEquals("Cancellation drain must not notify", retiredPublications, f.publications)
        assertSame(retiredSnapshot, owner.snapshot)
        assertEquals(0, f.main.childCount)
        assertEquals(Lifecycle.State.RESUMED, f.lifecycle.currentState)
        assertFalse(hasListener(observerTree, "mOnPreDrawListeners", preDraw))
        assertFalse(hasListener(observerTree, "mOnGlobalLayoutListeners", globalLayout))
        assertEquals(0, f.lifecycle.observerCount)
        assertEquals(1, f.unregistrations)
        assertTrue(f.context.connections.isEmpty())
        assertTrue(field<Job>(f.main, "job").isCompleted)
        assertTrue(checkNotNull(owner.scope.coroutineContext[Job]).isCompleted)
        assertTrue(field<Boolean>(f.move, "closed"))
        assertEquals(24, f.context.unbinds)
        assertTrue(checkNotNull(outstanding.job).isCancelled)
        assertFalse(f.clock.pending.contains(outstanding))
        assertEquals(0, f.dispatcher.pending.size)
        assertEquals(0, f.clock.pending.size)
        fun assertRetiredAfter(callback: String) {
            assertEquals("$callback must not notify", retiredPublications, f.publications)
            assertSame("$callback must not replace the snapshot", retiredSnapshot, owner.snapshot)
            assertNull("$callback must not restore service ownership", f.binding.service)
            // Check immediately, without a drain that could hide illicit requeue.
            assertEquals("$callback must not enqueue work", 0, f.dispatcher.pending.size)
            assertEquals("$callback must not enqueue a frame", 0, f.clock.pending.size)
        }
        liveConnection.onServiceConnected(component, binder)
        assertNull("A stale connect must be rejected before disconnect can clear it", f.binding.service)
        assertRetiredAfter("Stale service connect")
        liveConnection.onServiceDisconnected(component)
        assertRetiredAfter("Stale service disconnect")
        owner.navigationAction(teardownActivation)
        assertRetiredAfter("Stale navigation action")
        owner.childInteractionChanged()
        assertRetiredAfter("Stale child interaction")
        staleOpen()
        assertRetiredAfter("Stale open action")
        outstanding.deliver(9_000_000_000L) // The once-live request, delivered exactly once after cancellation.
        assertRetiredAfter("Stale motion frame")
        // Observe before any drain: a new cancelled runnable must not hide an illicit requeue.
        assertEquals(0, f.dispatcher.pending.size)
        assertEquals(0, f.clock.pending.size)
        assertSame(snapshot, owner.snapshot)
        assertEquals(retiredPublications, f.publications)
        assertNull(f.binding.service)
        assertEquals(24, f.context.binds)
        assertEquals(24, f.context.unbinds)
        assertEquals(0, f.lifecycle.observerCount)
        assertEquals(0, f.clock.pending.size)
        assertEquals(0, f.dispatcher.pending.size)
        assertEquals(0, f.main.childCount)
    }

    private class CountingContext(base: Context) : ContextWrapper(base) {
        val connections = linkedSetOf<ServiceConnection>()
        var binds = 0
        var unbinds = 0
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(0, flags)
            assertEquals(ReverbService::class.java.name, service.component?.className)
            assertTrue(connections.add(connection)); binds++; return true
        }
        override fun unbindService(connection: ServiceConnection) {
            assertTrue(connections.remove(connection)); unbinds++
        }
    }

    private class Fixture : LifecycleOwner, AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val context = CountingContext(activity.get())
        private val board = object : FrameLayout(context) { override fun hasWindowFocus() = true }
        val dispatcher = ControlledDispatcher()
        val clock = ControlledClock()
        private val scope = CoroutineScope(dispatcher + Job() + clock + object : MotionDurationScale {
            override val scaleFactor = 1f
        })
        val back = OnBackPressedDispatcher { error("Ordinary Settings Back escaped Main") }
        var registrations = 0
        var unregistrations = 0
        var publications = 0
        var scans = 0
        private var retired = false
        private var instrumented = false
        private var frameMillis = 0L
        private val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context),
            ReverbPalette.fallback(false))
        val main = NativeMainScreenView.create(context, environment, NativeMainScreenInput(false, false),
            this, back, scope, null, board,
            NativeWindowTextAccessibilityOwner(board, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }),
            null, { error("No permission action") }, {}, {
                registrations++
                NativeDirectoryPickerRegistration({ error("No picker launch") }, { unregistrations++ })
            }, {})
        val settings get() = field<NativeSettingsScreenContent>(main, "settings")
        val motion get() = field<NativeMainPanelMotion>(main, "settingsMotion")
        val navigation get() = field<NativeMainNavigationState>(main, "navigation")
        val binding get() = field<NativeSettingsServiceBinding>(settings.owner, "serviceBinding")
        val move get() = field<SettingsMoveAvailabilityOwner>(settings.owner, "moveAvailability")
        val openAction get() = field<ReverbAppTopBarActions>(field<ReverbHomeScaffoldView>(main, "home"), "navigation").settings
        private val motionJob get() = checkNotNull(field<CoroutineScope>(motion, "scope").coroutineContext[Job])
        val pendingMotionFrames get() = clock.pending.count { belongsTo(it.job, motionJob) }
        init {
            activity.get().setContentView(board)
            board.addView(main, FrameLayout.LayoutParams(-1, -1))
            main.start()
            place()
            assertTrue(main.isAttachedToWindow)
        }
        fun open(settleMotion: Boolean = true) {
            openAction()
            if (!instrumented) {
                // Only the repository read boundary is replaced, never generation/admission/publication.
                val scan: suspend (String, () -> Boolean) -> Boolean = { _, mayStart ->
                    assertTrue(mayStart()); scans++; false
                }
                setField(move, "scan", scan)
                val original = field<() -> Unit>(settings.owner, "changed")
                setField(settings.owner, "changed", { publications++; original() })
                instrumented = true
            }
            // Same immutable hydration boundary as NativeSettingsPublicationOwnerTest.
            runBlocking {
                val values = resolveNativeSettingsRestoredDraft(SettingsUiDraft.normalized(SettingsSnapshot(
                    oneShotRetentionTime = 300, loopingRetentionTime = 600,
                    quickSaveEnabled = true, quickSaveSeconds = 30,
                )), listOf(InputRouteMode.AUTO))
                assertEquals(SettingsDraftHydrationResult.APPLIED, settings.owner.draft.hydrate(
                    readDurable = { SettingsDraftBaseline(values, null) }, restoreFields = { it }))
            }
            if (settleMotion) settle()
            assertTrue(settings.inputAllowed)
        }
        fun startMotionWithoutFrame() = dispatcher.drain { it?.isActive != true || belongsTo(it, motionJob) }
        fun outstandingMotionRequest(): ControlledClock.Request =
            clock.pending.filter { belongsTo(it.job, motionJob) }.single()
        fun settle() {
            // Finite frames for the existing 220ms motion, not a new elapsed-time deadline.
            repeat(5) {
                dispatcher.drain { it?.isActive != true || belongsTo(it, motionJob) }
                clock.frame(1_000_000_000L + frameMillis * 1_000_000L) { belongsTo(it, motionJob) }
                frameMillis += 110
                dispatcher.drain { it?.isActive != true || belongsTo(it, motionJob) }
                place()
            }
        }
        private fun place() {
            board.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            board.layout(0, 0, 400, 800)
        }
        fun drainRetired() = dispatcher.drain { it?.isActive != true }
        fun retireMain() {
            if (retired) return
            retired = true
            main.close() // The actual owner disposal; not called on any ordinary Back.
        }
        override fun close() {
            var failure: Throwable? = null
            fun clean(block: () -> Unit) { try { block() } catch (t: Throwable) {
                if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t)
            } }
            clean(::retireMain)
            clean { scope.cancel(); drainRetired() }
            clean { lifecycle.currentState = Lifecycle.State.DESTROYED }
            clean { activity.pause().stop().destroy() }
            failure?.let { throw it }
        }
    }

    /** Retain background tasks without starting storage; only real panel-motion jobs are advanced. */
    private class ControlledDispatcher : CoroutineDispatcher() {
        data class Task(val job: Job?, val action: Runnable)
        val pending = mutableListOf<Task>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending += Task(context[Job], block) }
        fun drain(admit: (Job?) -> Boolean) {
            while (true) {
                val index = pending.indexOfFirst { admit(it.job) }
                if (index < 0) return
                pending.removeAt(index).action.run()
            }
        }
    }
    private class ControlledClock : MonotonicFrameClock {
        data class Request(val job: Job?, val deliver: (Long) -> Unit)
        val pending = mutableListOf<Request>()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { c ->
            val request = Request(c.context[Job]) { time -> if (c.isActive) c.resumeWith(runCatching { onFrame(time) }) }
            pending += request
            c.invokeOnCancellation { pending.remove(request) }
        }
        fun frame(time: Long, admit: (Job?) -> Boolean) {
            val batch = pending.filter { admit(it.job) }
            pending.removeAll(batch.toSet())
            batch.forEach { request -> request.deliver(time) }
        }
    }
    companion object {
        private fun belongsTo(candidate: Job?, root: Job): Boolean = candidate === root ||
            root.children.any { belongsTo(candidate, it) }
        @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        // Pinned SDK35 ViewTreeObserver uses CopyOnWriteArray; inspect identity, never dispatch it.
        private fun hasListener(tree: ViewTreeObserver, name: String, listener: Any): Boolean {
            val list = field<Any?>(tree, name) ?: return false
            val values = field<List<*>?>(list, "mDataCopy") ?: field<List<*>>(list, "mData")
            return values.any { it === listener }
        }
        private fun setField(owner: Any, name: String, value: Any) {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }
    }
}
