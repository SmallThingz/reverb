package app.smallthingz.reverb

import android.app.Application
import android.content.Intent
import android.content.ComponentName
import android.os.Looper
import android.os.Environment
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.content.Context
import java.time.ZoneId
import app.smallthingz.reverb.nativeui.NativeIncidentsScreenView
import kotlinx.coroutines.flow.MutableStateFlow
import smallthingz.ui.text.view.ParagraphView
import java.time.Duration
import java.util.concurrent.TimeUnit
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.NativeWindowTextAccessibilityOwner
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class,
    shadows = [SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugUiControlsTest {
    @Before fun provideHostStorageEnvironment() {
        // Match the qualified Settings host fixture: unsupported all-files access stays denied.
        SettingsPublicationEnvironmentShadow.queries = 0
        assertFalse(Environment.isExternalStorageManager())
        assertEquals(1, SettingsPublicationEnvironmentShadow.queries)
        // No recorder is running in this host. A flags=0 Settings bind legitimately returns false;
        // do not let Robolectric synthesize onServiceConnected(null, null).
        val application = RuntimeEnvironment.getApplication() as Application
        shadowOf(application).declareComponentUnbindable(ComponentName(application, ReverbService::class.java))
    }

    @Test fun settingsIdInvokesRealAccessibleGearAndReportsDispatchNotRenderSuccess() = Fixture().use { f ->
        val before = f.controls.snapshot()
        assertFalse(checkNotNull(before.navigation).showSettings)
        assertEquals(DebugUiStatus.READY, before.control(DebugUiControl.SETTINGS))
        val result = f.controls.activate(DebugUiControl.SETTINGS, checkNotNull(before.generation))
        assertEquals(DebugUiStatus.DISPATCHED, result.status)
        assertTrue(checkNotNull(result.snapshot.navigation).showSettings)
        // Hydration and animation may still be pending. Dispatch must not claim their completion.
        assertTrue(result.snapshot.controls.none { it.status == DebugUiStatus.READY })
    }

    @Test fun permissionBranchLibraryUsesRealActionWithoutGrantingCapturePermission() = Fixture().use { f ->
        val before = f.controls.snapshot()
        assertEquals(DebugUiStatus.READY, before.control(DebugUiControl.LIBRARY))
        val result = f.controls.activate(DebugUiControl.LIBRARY, checkNotNull(before.generation))
        assertEquals(DebugUiStatus.DISPATCHED, result.status)
        assertTrue(checkNotNull(result.snapshot.navigation).showLibrary)
        assertFalse(f.context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    @Test fun retainedHomeGearIsExcludedAndCurrentLibraryGearUsesLibraryHandler() =
        Fixture(NativeMainSavedNavigation(showLibrary = true)).use { f ->
            val before = f.controls.snapshot()
            assertEquals(DebugUiStatus.READY, before.control(DebugUiControl.SETTINGS))
            val result = f.controls.activate(DebugUiControl.SETTINGS, checkNotNull(before.generation))
            assertEquals(DebugUiStatus.DISPATCHED, result.status)
            val after = checkNotNull(result.snapshot.navigation)
            assertTrue(after.showSettings)
            assertFalse("Library's own Settings action closes Library; hidden Home must not win", after.showLibrary)
        }

    @Test fun pausedActivityStillReportsNavigationButCannotActAndOldGenerationExpires() = Fixture().use { f ->
        val token = checkNotNull(f.controls.snapshot().generation)
        f.activity.pause()
        val paused = f.controls.snapshot()
        assertEquals(DebugUiStatus.NOT_RESUMED, paused.status)
        assertFalse(checkNotNull(paused.navigation).showSettings)
        assertEquals(DebugUiStatus.NOT_RESUMED,
            f.controls.activate(DebugUiControl.SETTINGS, checkNotNull(paused.generation)).status)
        f.activity.resume()
        assertEquals(DebugUiStatus.STALE_OBSERVATION, f.controls.activate(DebugUiControl.SETTINGS, token).status)
        assertFalse(checkNotNull(f.controls.snapshot().navigation).showSettings)
    }

    @Test fun unfocusedWindowCannotInvokeVisibleGear() = Fixture().use { f ->
        f.activity.windowFocusChanged(false)
        val state = f.controls.snapshot()
        assertEquals(DebugUiStatus.WINDOW_UNFOCUSED, state.status)
        assertEquals(DebugUiStatus.WINDOW_UNFOCUSED,
            f.controls.activate(DebugUiControl.SETTINGS, checkNotNull(state.generation)).status)
        assertFalse(checkNotNull(state.navigation).showSettings)
    }

    @Test fun secondActionBeforeOpeningLayoutSettlesIsBlocked() = Fixture().use { f ->
        val token = checkNotNull(f.controls.snapshot().generation)
        val opened = f.controls.activate(DebugUiControl.SETTINGS, token)
        assertEquals(DebugUiStatus.DISPATCHED, opened.status)
        // Observe the real pending opening in this main turn. A looper drain can already finish
        // Choreographer's transition; it does not guarantee an intermediate animation frame.
        val pending = opened.snapshot
        assertTrue("Opening must initially await placement or motion: $pending",
            pending.status == DebugUiStatus.MOVING || pending.status == DebugUiStatus.NOT_LAID_OUT)
        assertTrue(checkNotNull(pending.navigation).showSettings)
        val blocked = f.controls.activate(DebugUiControl.BACK, checkNotNull(pending.generation))
        assertEquals(pending.status, blocked.status)
        assertEquals("Rejected Back must leave Settings open", pending.navigation, blocked.snapshot.navigation)

        // Only now drive the actual frame owner. The same action must become usable when settled.
        val settled = f.awaitReady(DebugUiControl.BACK)
        assertTrue(checkNotNull(settled.navigation).showSettings)
        val closed = f.controls.activate(DebugUiControl.BACK, checkNotNull(settled.generation))
        assertEquals(DebugUiStatus.DISPATCHED, closed.status)
        assertFalse(checkNotNull(closed.snapshot.navigation).showSettings)
    }

    @Test fun oldLibraryBackObservationCannotRetargetSettingsBackInSameActivity() =
        Fixture(NativeMainSavedNavigation(showLibrary = true)).use { f ->
            val library = f.awaitReady(DebugUiControl.BACK)
            val oldToken = checkNotNull(library.generation)
            assertEquals(DebugUiStatus.DISPATCHED, f.controls.activate(DebugUiControl.BACK, oldToken).status)
            val home = f.awaitReady(DebugUiControl.SETTINGS)
            assertEquals(DebugUiStatus.DISPATCHED,
                f.controls.activate(DebugUiControl.SETTINGS, checkNotNull(home.generation)).status)
            val settings = f.awaitReady(DebugUiControl.BACK)
            assertTrue(checkNotNull(settings.navigation).showSettings)
            assertEquals(DebugUiStatus.STALE_OBSERVATION, f.controls.activate(DebugUiControl.BACK, oldToken).status)
            assertTrue(checkNotNull(f.controls.snapshot().navigation).showSettings)
            assertEquals(DebugUiStatus.DISPATCHED,
                f.controls.activate(DebugUiControl.BACK, checkNotNull(settings.generation)).status)
            assertFalse(checkNotNull(f.controls.snapshot().navigation).showSettings)
        }

    @Test fun ancestorExposurePolicyBlocksVisibleGearUntilRestored() = Fixture().use { f ->
        val home = (0 until f.root.childCount).map(f.root::getChildAt).filterIsInstance<ReverbHomeScaffoldView>().single()
        val original = home.topBar.importantForAccessibility
        assertEquals(DebugUiStatus.READY, f.controls.snapshot().control(DebugUiControl.SETTINGS))
        home.topBar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val hidden = f.controls.snapshot()
        assertEquals(DebugUiStatus.SURFACE_BLOCKED, hidden.control(DebugUiControl.SETTINGS))
        assertEquals(DebugUiStatus.SURFACE_BLOCKED,
            f.controls.activate(DebugUiControl.SETTINGS, checkNotNull(hidden.generation)).status)
        assertFalse(checkNotNull(f.controls.snapshot().navigation).showSettings)
        home.topBar.importantForAccessibility = original
        val ready = f.controls.snapshot()
        assertEquals(DebugUiStatus.READY, ready.control(DebugUiControl.SETTINGS))
        assertEquals(DebugUiStatus.DISPATCHED,
            f.controls.activate(DebugUiControl.SETTINGS, checkNotNull(ready.generation)).status)
        assertTrue(checkNotNull(f.controls.snapshot().navigation).showSettings)
    }

    @Test fun libraryScrollAndIncidentSelectionCannotTargetHomeOrHiddenLists() = Fixture().use { f ->
        val before = f.controls.snapshot()
        for (control in listOf(DebugUiControl.LIBRARY_FORWARD, DebugUiControl.LIBRARY_BACKWARD,
            DebugUiControl.INCIDENTS_SELECT, DebugUiControl.INCIDENTS_SELECT_ALL)) {
            assertEquals(DebugUiStatus.NOT_PRESENT, before.control(control))
            assertEquals(DebugUiStatus.NOT_PRESENT,
                f.controls.activate(control, checkNotNull(before.generation)).status)
        }
        assertEquals(before.navigation, f.controls.snapshot().navigation)
    }

    @Test fun orderedCommandCoreInspectsAndActsOnExistingSessionWithoutLaunchingActivity() = Fixture().use { f ->
        val snapshot = dispatchDebugUiCommand(f.controls, Intent(DebugUiCommandReceiver.SNAPSHOT))
        val activity = f.context
        assertFalse(checkNotNull(snapshot.snapshot.navigation).showSettings)
        val result = dispatchDebugUiCommand(f.controls, Intent(DebugUiCommandReceiver.ACTIVATE)
            .putExtra(DebugUiCommandReceiver.CONTROL_ID, DebugUiControl.SETTINGS.id)
            .putExtra(DebugUiCommandReceiver.GENERATION, checkNotNull(snapshot.snapshot.generation)))
        assertEquals(DebugUiStatus.DISPATCHED, result.status)
        assertTrue(checkNotNull(result.snapshot.navigation).showSettings)
        assertTrue(activity === f.activity.get())
        assertFalse(activity.isFinishing)
        assertFalse(activity.isDestroyed)
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication() as Application).nextStartedActivity)
    }

    @Test fun coldPersistentRegistryReportsNoActivityWithoutLaunchingAnything() {
        val application = RuntimeEnvironment.getApplication() as Application
        val controls = DebugUiRuntime.controls(application)
        val first = dispatchDebugUiCommand(controls, Intent(DebugUiCommandReceiver.SNAPSHOT))
        assertEquals(DebugUiStatus.NO_ACTIVITY, first.status)
        assertEquals(null, first.snapshot.generation)
        assertTrue(controls === DebugUiRuntime.controls(application))
        assertEquals(null, shadowOf(application).nextStartedActivity)
        assertEquals(null, shadowOf(application).nextStartedService)
    }

    @Test fun unknownIdsAndAdditionalArgumentsCannotReachAnyHandler() = Fixture().use { f ->
        val before = f.controls.snapshot()
        val valid = Intent(DebugUiCommandReceiver.ACTIVATE)
            .putExtra(DebugUiCommandReceiver.CONTROL_ID, DebugUiControl.SETTINGS.id)
            .putExtra(DebugUiCommandReceiver.GENERATION, checkNotNull(before.generation))
        val unknown = Intent(valid).putExtra(DebugUiCommandReceiver.CONTROL_ID, "recorder.enable")
        val extra = Intent(valid).putExtra("method", "openSettings")
        for (request in listOf(unknown, extra, Intent("app.smallthingz.reverb.debug.ENABLE_LISTENING"))) {
            assertEquals(DebugUiStatus.INVALID_REQUEST, dispatchDebugUiCommand(f.controls, request).status)
        }
        assertEquals(before.navigation, f.controls.snapshot().navigation)
    }

    @Test fun debugTransportManifestHasOnlyExplicitProtectedReceiverAndPrivateInitializer() {
        val context = RuntimeEnvironment.getApplication() as Application
        val manager = context.packageManager
        val receiver = manager.getReceiverInfo(ComponentName(context, DebugUiCommandReceiver::class.java), 0)
        assertEquals("android.permission.DUMP", receiver.permission)
        assertTrue(receiver.exported)
        assertTrue(manager.queryBroadcastReceivers(Intent(DebugUiCommandReceiver.SNAPSHOT).setPackage(context.packageName), 0).isEmpty())
        assertFalse(manager.getProviderInfo(ComponentName(context, DebugUiInitializer::class.java), 0).exported)
    }

    @Test fun populatedLibraryCommandsScrollRealViewportForwardAndBackward() =
        Fixture(NativeMainSavedNavigation(showLibrary = true), libraryRows = 40, motionScale = 0f).use { f ->
            val before = f.controls.snapshot()
            assertEquals(DebugUiStatus.READY, before.control(DebugUiControl.LIBRARY_FORWARD))
            val initial = checkNotNull(before.libraryScrollOffset)
            assertEquals(0, initial)
            assertEquals(DebugUiStatus.NO_ACCESSIBLE_ACTION, before.control(DebugUiControl.LIBRARY_BACKWARD))
            val boundary = f.command(DebugUiControl.LIBRARY_BACKWARD)
            assertEquals(DebugUiStatus.NO_ACCESSIBLE_ACTION, boundary.status)
            assertEquals(initial, f.controls.snapshot().libraryScrollOffset)

            assertEquals(DebugUiStatus.DISPATCHED, f.command(DebugUiControl.LIBRARY_FORWARD).status)
            val forward = f.awaitFrames("accessible forward scroll changed the real offset") {
                it.status == DebugUiStatus.READY && (it.libraryScrollOffset ?: 0) > initial
            }
            val advanced = checkNotNull(forward.libraryScrollOffset)
            assertEquals(DebugUiStatus.READY, forward.control(DebugUiControl.LIBRARY_BACKWARD))
            assertEquals(DebugUiStatus.DISPATCHED, f.command(DebugUiControl.LIBRARY_BACKWARD).status)
            val backward = f.awaitFrames("accessible backward scroll reduced the real offset") {
                it.status == DebugUiStatus.READY && it.libraryScrollOffset != null && it.libraryScrollOffset < advanced
            }
            assertTrue(checkNotNull(backward.libraryScrollOffset) >= initial)
            assertTrue(checkNotNull(backward.navigation).showLibrary)
            assertEquals("The fixture catalog must remain populated during both actions", 40, f.libraryRecordingCount())
        }

    @Test fun populatedIncidentsCommandsEnterSelectionAndSelectEveryRowWithoutTogglingBack() =
        Fixture(NativeMainSavedNavigation(showIncidents = true), incidentRows = 25, motionScale = 0f).use { f ->
            val before = f.controls.snapshot()
            assertEquals(DebugUiStatus.READY, before.control(DebugUiControl.INCIDENTS_SELECT))
            assertEquals(false, before.incidentsSelecting)
            assertEquals(DebugUiStatus.NOT_PRESENT, before.control(DebugUiControl.INCIDENTS_SELECT_ALL))
            assertEquals(DebugUiStatus.DISPATCHED, f.command(DebugUiControl.INCIDENTS_SELECT).status)
            val selecting = f.awaitFrames("Incidents entered real selection mode") {
                it.incidentsSelecting == true && it.control(DebugUiControl.INCIDENTS_SELECT_ALL) == DebugUiStatus.READY
            }
            assertEquals(DebugUiStatus.NOT_PRESENT, selecting.control(DebugUiControl.INCIDENTS_SELECT))
            f.assertIncidentSelection(0)
            assertEquals(DebugUiStatus.DISPATCHED, f.command(DebugUiControl.INCIDENTS_SELECT_ALL).status)
            val selected = f.awaitFrames("real Select all changed the header to Deselect all") {
                it.status == DebugUiStatus.READY && it.incidentsSelecting == true &&
                    it.control(DebugUiControl.INCIDENTS_SELECT_ALL) == DebugUiStatus.NOT_PRESENT
            }
            f.assertIncidentSelection(25)
            val stale = dispatchDebugUiCommand(f.controls, Intent(DebugUiCommandReceiver.ACTIVATE)
                .putExtra(DebugUiCommandReceiver.CONTROL_ID, DebugUiControl.INCIDENTS_SELECT_ALL.id)
                .putExtra(DebugUiCommandReceiver.GENERATION, checkNotNull(selecting.generation)))
            assertEquals(DebugUiStatus.STALE_OBSERVATION, stale.status)
            val repeated = f.command(DebugUiControl.INCIDENTS_SELECT_ALL)
            assertEquals(DebugUiStatus.NOT_PRESENT, repeated.status)
            assertEquals(selected.navigation, repeated.snapshot.navigation)
            f.assertIncidentSelection(25)
            assertEquals(0, checkNotNull(f.incidentAccess).mutations)
        }

    @Test fun parserRejectsUnknownControlAndArbitraryVerbBeforeAnyUiWork() {
        assertThrows(IllegalArgumentException::class.java) { parseDebugUiCommands("activate:private.navigation") }
        assertThrows(IllegalStateException::class.java) { parseDebugUiCommands("eval:navigation.settings") }
        assertThrows(IllegalArgumentException::class.java) { parseDebugUiCommands(List(17) { "snapshot" }.joinToString(",")) }
        assertEquals(listOf(DebugUiCommand.Snapshot, DebugUiCommand.Await(DebugUiControl.SETTINGS),
            DebugUiCommand.Activate(DebugUiControl.SETTINGS)),
            parseDebugUiCommands("snapshot,await:navigation.settings,activate:navigation.settings"))
    }

    @Test fun partialSetupFailureReleasesOwnersBeforeAnotherRealHostRuns() {
        val injected = IllegalStateException("fixture fails after attachment, before focus")
        var failed: Fixture? = null
        val actual = assertThrows(IllegalStateException::class.java) {
            Fixture(beforeFocus = { fixture ->
                failed = fixture
                assertTrue("Fail only after the actual root attached", fixture.root.isAttachedToWindow)
                throw injected
            }).use { error("Setup failure must not enter the test body") }
        }
        assertSame("Cleanup must preserve the original setup failure", injected, actual)
        assertTrue("Cleanup must complete without suppressed failures", actual.suppressed.isEmpty())
        val retired = checkNotNull(failed)
        retired.assertRetired()
        retired.close() // Terminal cleanup is idempotent.
        Fixture().use { next ->
            val ready = next.awaitReady(DebugUiControl.SETTINGS)
            // A leaked callback would observe this new Activity and repopulate the retired registry.
            retired.assertRetired()
            assertEquals(DebugUiStatus.DISPATCHED,
                next.controls.activate(DebugUiControl.SETTINGS, checkNotNull(ready.generation)).status)
            assertTrue(checkNotNull(next.controls.snapshot().navigation).showSettings)
        }
        retired.assertRetired()
    }

    @Test fun recordingTitlesCannotResolveNavigationControls() = Fixture(
        restored = NativeMainSavedNavigation(showLibrary = true),
        libraryRows = 2,
        motionScale = 0f,
        libraryTitleResources = listOf(R.string.files_tab, R.string.open_settings),
    ).use { f ->
        fun visibleRows() = descendants(f.root)
            .filterIsInstance<smallthingz.ui.reverb.library.ReverbLibraryRecordingRow>()
            .count { it.getGlobalVisibleRect(android.graphics.Rect()) }
        val before = f.awaitFrames("the settled Library and both collision rows") { snapshot ->
            snapshot.status == DebugUiStatus.READY && visibleRows() == 2
        }
        assertEquals(DebugUiStatus.READY, before.status)
        assertTrue(checkNotNull(before.navigation).showLibrary)
        assertEquals("Both title collisions must be mounted and visible", 2,
            visibleRows())
        val result = f.controls.activate(DebugUiControl.LIBRARY, checkNotNull(before.generation))
        assertTrue("Files must not resolve to a recording leaf: ${result.status}", result.status in setOf(
            DebugUiStatus.NOT_PRESENT, DebugUiStatus.SURFACE_BLOCKED, DebugUiStatus.HIDDEN,
        ))
        assertEquals(before.navigation, result.snapshot.navigation)
        assertEquals(DebugUiStatus.READY, result.snapshot.control(DebugUiControl.SETTINGS))
        assertEquals(DebugUiStatus.READY, result.snapshot.control(DebugUiControl.BACK))
        val back = f.controls.activate(DebugUiControl.BACK, checkNotNull(result.snapshot.generation))
        assertEquals(DebugUiStatus.DISPATCHED, back.status)
        assertFalse(checkNotNull(back.snapshot.navigation).showLibrary)
    }

    internal class Fixture(
        restored: NativeMainSavedNavigation = NativeMainSavedNavigation(),
        libraryRows: Int = 0,
        incidentRows: Int = 0,
        motionScale: Float = 1f,
        libraryTitleResources: List<Int> = emptyList(),
        beforeFocus: (Fixture) -> Unit = {},
    ) : AutoCloseable {
        private val application = RuntimeEnvironment.getApplication() as Application
        private val registry = DebugUiActivities { it is ComponentActivity }
        private var registered = false
        private var retired = false
        lateinit var activity: ActivityController<ComponentActivity>
            private set
        val context: ComponentActivity get() = activity.get()
        private lateinit var board: FrameLayout
        private lateinit var ownerJob: Job
        private lateinit var scope: CoroutineScope
        var incidentAccess: FixtureIncidentHistory? = null
            private set
        lateinit var root: NativeMainScreenView
            private set
        val controls = DebugUiControls(registry)

        init {
            // Construction itself owns cleanup: callers' use blocks begin only after this returns.
            try {
                initialize(restored, libraryRows, incidentRows, motionScale, libraryTitleResources, beforeFocus)
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        private fun initialize(restored: NativeMainSavedNavigation, libraryRows: Int,
            incidentRows: Int, motionScale: Float, libraryTitleResources: List<Int>, beforeFocus: (Fixture) -> Unit) {
            application.registerActivityLifecycleCallbacks(registry)
            registered = true
            // Publish each acquired owner before the next fallible setup step.
            activity = Robolectric.buildActivity(ComponentActivity::class.java)
            activity.setup().visible()
            board = FrameLayout(context)
            // Queue the first Incidents read until its data-only fixture store is installed.
            val dispatcher = if (incidentRows > 0) Dispatchers.Main else Dispatchers.Main.immediate
            ownerJob = Job()
            scope = CoroutineScope(dispatcher + ownerJob + AndroidUiFrameClock(Choreographer.getInstance()) +
                object : MotionDurationScale { override val scaleFactor = motionScale })
            incidentAccess = if (incidentRows > 0) FixtureIncidentHistory(context, incidentRows) else null
            val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false))
            root = NativeMainScreenView.create(context, environment, NativeMainScreenInput(false, false),
                context, context.onBackPressedDispatcher, scope, null, board,
                NativeWindowTextAccessibilityOwner(board, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }),
                NativeMainScreenSavedState(navigation = restored), {}, {},
                registerDirectoryResult = { NativeDirectoryPickerRegistration(
                    { error("Harness fixture must not launch a directory picker") }, {}) }, releaseInputFocus = {})
            // Data-only fixture seams already exercised by NativeMainLibraryScrollbarGestureTest.
            // Never change navigation/admission/readiness flags or replace an action handler.
            if (libraryRows > 0) {
                NativeMainScreenView::class.java.getDeclaredField("librarySnapshot").apply { isAccessible = true }
                    .set(root, List(libraryRows) { index ->
                        val title = libraryTitleResources.getOrNull(index)?.let(context::getString) ?: "Recording $index"
                        RecordingEntity("/synthetic-scroll/$index.wav", title, "audio/wav",
                            1_700_000_000_000L, 1_000L, 32_044L, "PCM", RecordingStorageType.FILE, "/synthetic-scroll")
                    })
            }
            incidentAccess?.let { access ->
                val history = field<NativeIncidentsScreenOwner>(root, "incidentsOwner").history
                IncidentHistoryController::class.java.getDeclaredField("access").apply { isAccessible = true }
                    .set(history, access)
            }
            board.addView(root, FrameLayout.LayoutParams(-1, -1))
            context.setContentView(board)
            root.start()
            shadowOf(Looper.getMainLooper()).idle()
            beforeFocus(this)
            activity.windowFocusChanged(true)
        }
        fun command(control: DebugUiControl): DebugUiResult {
            val state = controls.snapshot()
            return dispatchDebugUiCommand(controls, Intent(DebugUiCommandReceiver.ACTIVATE)
                .putExtra(DebugUiCommandReceiver.CONTROL_ID, control.id)
                .putExtra(DebugUiCommandReceiver.GENERATION, checkNotNull(state.generation)))
        }

        // At most 96ms per operation, below the fixture's initial 250ms catalog scan. Zero duration
        // scale still uses real Choreographer/scroll-owner frames; no direct scroll or forced placement.
        fun awaitFrames(boundary: String, reached: (DebugUiSnapshot) -> Boolean): DebugUiSnapshot {
            var state = controls.snapshot()
            repeat(6) {
                if (reached(state)) return state
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                state = controls.snapshot()
            }
            assertTrue("Expected $boundary after six frames: $state", reached(state))
            return state
        }

        fun libraryRecordingCount(): Int = field<List<RecordingEntity>>(root, "librarySnapshot").size

        fun assertIncidentSelection(count: Int) {
            val panel = checkNotNull(field<NativeIncidentsScreenView>(root, "incidents").pointerContent)
            val expected = context.resources.getQuantityString(R.plurals.incidents_selected, count, count)
            assertTrue("Selection title must report all $count fixture rows",
                descendants(panel).filterIsInstance<ParagraphView>().any { it.getText() == expected })
            val checks = descendants(panel).filter { it.javaClass.simpleName == "IncidentCheckTarget" }
                .mapNotNull { view -> view.createAccessibilityNodeInfo()?.let { node ->
                    try { if (node.isVisibleToUser) node.isSelected else null }
                    finally { @Suppress("DEPRECATION") node.recycle() }
                } }.toList()
            assertTrue("The actual incident list must expose visible selection controls", checks.isNotEmpty())
            assertTrue("Visible incident selection must agree with the full-count header: $checks",
                checks.all { it == (count > 0) })
        }

        fun awaitReady(control: DebugUiControl): DebugUiSnapshot {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var state = controls.snapshot()
            while (state.controls.single { it.id == control.id }.status != DebugUiStatus.READY && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                Thread.sleep(5L)
                state = controls.snapshot()
            }
            assertEquals("Expected actual readiness for $control: $state", DebugUiStatus.READY,
                state.controls.single { it.id == control.id }.status)
            return state
        }
        fun assertRetired() {
            assertTrue(retired)
            assertFalse(registered)
            assertEquals(null, registry.current)
            assertFalse(registry.resumed)
            if (::root.isInitialized) assertFalse(root.isAttachedToWindow)
            if (::ownerJob.isInitialized) assertTrue("All owned coroutine work must terminate", ownerJob.isCompleted)
            if (::activity.isInitialized) assertTrue(context.isDestroyed)
        }

        override fun close() {
            if (retired) return
            retired = true
            var failure: Throwable? = null
            fun release(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error
                    else if (failure !== error) failure!!.addSuppressed(error)
                }
            }
            if (::root.isInitialized) release(root::close)
            if (::ownerJob.isInitialized) release { ownerJob.cancel() }
            if (::board.isInitialized) release { board.removeAllViews() }
            if (::activity.isInitialized) {
                // Honor actual lifecycle progress, including failure partway through setup().
                if (context.lifecycle.currentState == Lifecycle.State.RESUMED) release { activity.pause() }
                if (context.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) release { activity.stop() }
                if (!context.isDestroyed) release { activity.destroy() }
            }
            if (registered) release {
                application.unregisterActivityLifecycleCallbacks(registry)
                registered = false
            }
            if (::ownerJob.isInitialized) release {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!ownerJob.isCompleted && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(1L)
                }
                check(ownerJob.isCompleted) { "Fixture coroutine cleanup did not reach terminal" }
            }
            failure?.let { throw it }
        }
    }
    internal class FixtureIncidentHistory(private val context: Context, count: Int) : IncidentHistoryAccess {
        override val revisions = MutableStateFlow(0L)
        var mutations = 0
            private set
        private val incidents = List(count) { index ->
            RecordingIncident(occurredAtMillis = 1_700_000_000_000L + index * 1_000L, pid = index + 1)
        }
        override suspend fun readHistory(zone: ZoneId) =
            prepareIncidentHistory(incidents, zone = zone, resources = context.resources)
        override fun toggleAcknowledgedInBackground(incident: RecordingIncident) {
            mutations++
            error("Selection must not acknowledge an incident")
        }
        override fun deleteInBackground(incidents: List<RecordingIncident>) {
            mutations++
            error("Selection must not delete history")
        }
    }

    private companion object {
        inline fun <reified T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
    }
    private fun DebugUiSnapshot.control(id: DebugUiControl) = controls.single { it.id == id.id }.status
}
