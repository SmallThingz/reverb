package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DebugUiBoundsTest {
    @Test fun oversizedGroupRejectsBeforeFetchingAnyChildren() {
        val context = RuntimeEnvironment.getApplication() as Application
        for (count in listOf(DEBUG_UI_VIEW_LIMIT, Int.MAX_VALUE)) {
            val root = DeclaredChildren(context, count)
            assertNull(debugUiBoundedViews(root))
            assertEquals(0, root.childLookups)
            assertEquals(1, root.countReads)
        }
    }

    @Test fun exactCapIncludingRootIsAdmitted() {
        val context = RuntimeEnvironment.getApplication() as Application
        val root = DeclaredChildren(context, DEBUG_UI_VIEW_LIMIT - 1)
        val result = debugUiBoundedViews(root)
        assertNotNull(result)
        assertEquals(DEBUG_UI_VIEW_LIMIT, checkNotNull(result).size)
        assertTrue(result.first() === root)
        assertEquals(DEBUG_UI_VIEW_LIMIT - 1, root.childLookups)
    }

    @Test fun queuedSiblingConsumesBudgetBeforeWideChildIsFetched() {
        val context = RuntimeEnvironment.getApplication() as Application
        val branch = DeclaredChildren(context, DEBUG_UI_VIEW_LIMIT - 2)
        val sibling = View(context)
        val root = DeclaredChildren(context, 2) { if (it == 0) branch else sibling }
        // root + branch + queued sibling leaves only LIMIT - 3 entries for branch's children.
        assertNull(debugUiBoundedViews(root))
        assertEquals(2, root.childLookups)
        assertEquals(0, branch.childLookups)
    }

    @Test fun exactSharedCapWithQueuedSiblingIsAdmitted() {
        val context = RuntimeEnvironment.getApplication() as Application
        val branch = DeclaredChildren(context, DEBUG_UI_VIEW_LIMIT - 3)
        val sibling = View(context)
        val root = DeclaredChildren(context, 2) { if (it == 0) branch else sibling }
        val result = checkNotNull(debugUiBoundedViews(root))
        assertEquals(DEBUG_UI_VIEW_LIMIT, result.size)
        assertEquals(2, root.childLookups)
        assertEquals(DEBUG_UI_VIEW_LIMIT - 3, branch.childLookups)
        assertTrue(result[2] === sibling)
    }

    @Test fun snapshotReportsTreeLimitWithoutFetchingOversizedDescendants() = ActivityFixture().use { f ->
        val wide = DeclaredChildren(f.context, 0)
        f.context.setContentView(wide)
        wide.declaredCount = DEBUG_UI_VIEW_LIMIT
        wide.childLookups = 0
        try {
            val result = dispatchDebugUiCommand(f.controls, Intent(DebugUiCommandReceiver.SNAPSHOT))
            assertEquals(DebugUiStatus.TREE_LIMIT, result.status)
            assertEquals(DebugUiStatus.TREE_LIMIT, result.snapshot.status)
            assertEquals(0, wide.childLookups)
        } finally {
            // Restore the synthetic declaration before ordinary Activity teardown traversals.
            wide.declaredCount = 0
        }
    }

    @Test fun malformedRequestsNeverDiscoverUiOrReviseObservationAndValidSnapshotDoes() = ActivityFixture().use { f ->
        val guard = object : FrameLayout(f.context) {
            var failOnRead = false
            var reads = 0
            override fun getChildCount(): Int {
                reads++
                check(!failOnRead) { "Malformed request attempted UI discovery" }
                return super.getChildCount()
            }
        }
        var clicks = 0
        guard.addView(View(f.context).apply { setOnClickListener { clicks++ } })
        f.context.setContentView(guard)
        val token = f.registry.generation
        fun valid() = Intent(DebugUiCommandReceiver.ACTIVATE)
            .putExtra(DebugUiCommandReceiver.CONTROL_ID, DebugUiControl.SETTINGS.id)
            .putExtra(DebugUiCommandReceiver.GENERATION, "observed-token")
        val malformed = listOf(
            Intent("unknown.action"),
            Intent(DebugUiCommandReceiver.SNAPSHOT).putExtra("unexpected", true),
            Intent(DebugUiCommandReceiver.ACTIVATE),
            valid().putExtra("unexpected", true),
            valid().apply { removeExtra(DebugUiCommandReceiver.CONTROL_ID) },
            valid().putExtra(DebugUiCommandReceiver.CONTROL_ID, 7),
            valid().putExtra(DebugUiCommandReceiver.GENERATION, 7L),
            valid().putExtra(DebugUiCommandReceiver.CONTROL_ID, "x".repeat(65)),
            valid().putExtra(DebugUiCommandReceiver.GENERATION, ""),
            valid().putExtra(DebugUiCommandReceiver.GENERATION, "x".repeat(129)),
            valid().putExtra(DebugUiCommandReceiver.CONTROL_ID, "unknown.control"),
        )
        guard.reads = 0
        guard.failOnRead = true
        try {
            for (intent in malformed) {
                val result = dispatchDebugUiCommand(f.controls, intent)
                assertEquals(DebugUiStatus.INVALID_REQUEST, result.status)
                assertEquals(DebugUiStatus.INVALID_REQUEST, result.snapshot.status)
                assertNull(result.snapshot.generation)
                assertNull(result.snapshot.navigation)
                assertTrue(result.snapshot.controls.isEmpty())
                assertEquals(token, f.registry.generation)
                assertEquals(0, guard.reads)
                assertEquals(0, clicks)
            }
        } finally { guard.failOnRead = false }
        val live = dispatchDebugUiCommand(f.controls, Intent(DebugUiCommandReceiver.SNAPSHOT))
        assertEquals(DebugUiStatus.NO_MAIN_ROOT, live.status)
        assertTrue("A valid SNAPSHOT must still discover the actual Activity hierarchy", guard.reads > 0)
        assertEquals(0, clicks)
    }

    @Test fun partialSetupFailureUnregistersBeforeNextBoundsHost() {
        val injected = IllegalStateException("fixture setup failure")
        var failed: ActivityFixture? = null
        val actual = assertThrows(IllegalStateException::class.java) {
            ActivityFixture(afterSetup = { fixture ->
                failed = fixture
                assertTrue(fixture.registry.resumed)
                throw injected
            }).use { error("Failed initialization must not enter the test body") }
        }
        assertSame(injected, actual)
        assertTrue("Cleanup must not add a failure", actual.suppressed.isEmpty())
        val retired = checkNotNull(failed)
        retired.assertRetired()
        retired.close() // Cleanup is terminal, not repeated against the retired Activity.
        ActivityFixture().use { next ->
            // A leaked registration would now observe the new Activity.
            retired.assertRetired()
            assertSame(next.context, next.registry.current)
            val result = dispatchDebugUiCommand(next.controls, Intent(DebugUiCommandReceiver.SNAPSHOT))
            assertEquals(DebugUiStatus.NO_MAIN_ROOT, result.status)
        }
        retired.assertRetired()
    }

    /** Synthetic widths permit checking the bound without allocating the rejected children. */
    private class DeclaredChildren(
        context: Context,
        var declaredCount: Int,
        private val child: (Int) -> View = { View(context) },
    ) : ViewGroup(context) {
        var childLookups = 0
        var countReads = 0
        override fun getChildCount(): Int { countReads++; return declaredCount }
        override fun getChildAt(index: Int): View { childLookups++; return child(index) }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit
    }

    private class ActivityFixture(afterSetup: (ActivityFixture) -> Unit = {}) : AutoCloseable {
        private val application = RuntimeEnvironment.getApplication() as Application
        val registry = DebugUiActivities { it.javaClass == Activity::class.java }
        private var registered = false
        private var retired = false
        private lateinit var activity: ActivityController<Activity>
        val context: Activity get() = activity.get()
        val controls = DebugUiControls(registry)
        init {
            try {
                application.registerActivityLifecycleCallbacks(registry)
                registered = true
                activity = Robolectric.buildActivity(Activity::class.java)
                activity.setup()
                afterSetup(this)
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        fun assertRetired() {
            assertTrue(retired)
            assertFalse(registered)
            assertNull(registry.current)
            assertFalse(registry.resumed)
            if (::activity.isInitialized) assertTrue(context.isDestroyed)
        }

        override fun close() {
            if (retired) return
            retired = true
            var failure: Throwable? = null
            // Robolectric close() follows the actual reached lifecycle stage, even if setup failed.
            try { if (::activity.isInitialized) activity.close() }
            catch (error: Throwable) { failure = error }
            finally {
                if (registered) try {
                    application.unregisterActivityLifecycleCallbacks(registry)
                    registered = false
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                    else if (failure !== error) failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
}
