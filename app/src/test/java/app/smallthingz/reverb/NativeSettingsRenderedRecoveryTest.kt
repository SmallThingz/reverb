package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.NativeThemeMode
import smallthingz.ui.components.NativeThemeSelector
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.scroll.NativeWindowScrollAccessibilityEvents
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeActionDescriptionView
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** Real Settings owner, section factory, toolbar, accessibility leases and feedback host.
 * Native host layout/draw and exported node/action effects, not TalkBack/IME or device pixels.
 * Storage uses the same Context/JournalHostIoShadow seams as NativeSettingsPersistenceRecoveryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w199dp-h421dp-mdpi", application = Application::class,
    shadows = [JournalHostIoShadow::class, SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSettingsRenderedRecoveryTest {
    private var vsyncWasPaused = false
    private lateinit var previousFrameDelay: Duration

    @Before fun pausePlatformVsync() {
        vsyncWasPaused = ShadowChoreographer.isPaused()
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }

    @After fun restorePlatformVsync() {
        ShadowChoreographer.setFrameDelay(previousFrameDelay)
        ShadowChoreographer.setPaused(vsyncWasPaused)
    }

    @Test fun hydrationFailureRendersLoadingAndFeedbackThenReopenRejectsStaleChoice() = runBlocking {
        withFixture { f ->
            val first = f.context.arm(IOException("controlled hydration read"))
            f.screen.start()
            f.await { first.entered.isCompleted }
            f.render()
            f.assertLoading(retained = false)
            val revision = f.owner.state.editRevision
            assertFalse(f.done.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(revision, f.owner.state.editRevision)

            // The next real read is held too: a fast retry cannot hide the failed/loading state.
            val retry = f.context.arm()
            first.release()
            f.await { AppFeedbackCenter.events.value.any { it.message.contains("controlled hydration read") } }
            f.render()
            f.assertLoading(retained = false)
            f.assertErrorVisible("controlled hydration read")
            assertEquals(0, f.navigations)
            assertEquals(AppThemeMode.LIGHT, getConfiguredThemeMode(f.context))
            f.await { retry.entered.isCompleted } // Production bounded-backoff retry, no test-driven hydrate.
            retry.release()
            f.await { f.owner.state.interactionReady }
            f.showTheme()
            assertTrue(f.choice(NativeThemeMode.LIGHT).isChecked)
            assertFalse(descendants(f.screen).any { it is NativeCircularProgress })
            assertFalse(f.node(f.done).isEnabled)
            assertEquals(1, AppFeedbackCenter.events.value.count {
                it.message.contains("controlled hydration read")
            })

            // A retained clean panel must re-read durable state, not merely expose its old pixels.
            val retained = f.screen.sectionContent
            val oldDark = f.choice(NativeThemeMode.DARK)
            f.screen.bind(NativeSettingsScreenInput(active = false))
            assertTrue(f.preferences.edit().putInt(PrefKey.THEME_MODE, AppThemeMode.DARK.storageCode.toInt()).commit())
            val reopen = f.context.arm()
            f.screen.bind(NativeSettingsScreenInput(active = true))
            f.await { reopen.entered.isCompleted }
            f.render()
            assertSame(retained, f.screen.sectionContent)
            f.assertLoading(retained = true)
            val before = f.owner.snapshot
            assertFalse(oldDark.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(before, f.owner.snapshot)
            reopen.release()
            f.await { f.owner.state.interactionReady }
            f.showTheme()
            assertTrue(f.node(f.choice(NativeThemeMode.DARK)).isChecked)
            assertFalse(f.choice(NativeThemeMode.LIGHT).isChecked)
            assertEquals(AppThemeMode.DARK, f.owner.snapshot.themeMode)
            assertFalse(f.owner.state.hasUnsavedChanges)
        }
    }

    @Test fun accessibleDoneFailureKeepsRenderedSelectionAndRetryCommitsExactlyOnce() = runBlocking {
        withFixture { f ->
            f.open()
            val before = f.preferences.all.toMap()
            val silenceRevision = SilenceGatePreferences.revision.value
            val dark = f.choice(NativeThemeMode.DARK)
            assertTrue(dark.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            f.render()
            assertEquals(AppThemeMode.DARK, f.owner.snapshot.themeMode)
            assertTrue(f.node(dark).isChecked)
            assertTrue(f.owner.state.hasUnsavedChanges)
            assertTrue(f.node(f.done).isEnabled)
            f.assertNavigationLabel(R.string.undo)

            val failed = f.context.arm(IOException("controlled save preflight read"))
            assertTrue(f.done.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            f.await { failed.entered.isCompleted }
            f.render()
            assertTrue(f.owner.state.persisting)
            assertFalse(f.node(f.done).isEnabled)
            assertFalse(f.done.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(0, f.navigations)
            failed.release()
            f.await { !f.owner.state.persisting }
            f.render()
            f.assertErrorVisible("controlled save preflight read")
            assertEquals(before, f.preferences.all)
            assertEquals(silenceRevision, SilenceGatePreferences.revision.value)
            assertFalse(File(f.root, "retention-config.v1").exists())
            assertTrue(f.context.serviceActions.isEmpty())
            assertEquals(0, f.navigations)
            assertTrue(f.owner.state.hasUnsavedChanges)
            assertSame(dark, f.choice(NativeThemeMode.DARK))
            assertTrue(f.node(dark).isChecked)
            assertTrue(f.node(f.done).isEnabled)
            f.assertNavigationLabel(R.string.undo)

            // Done is the actual failed-save retry affordance; no direct owner.done invocation.
            val retry = f.context.arm()
            assertTrue(f.done.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            f.await { retry.entered.isCompleted }
            f.render()
            assertFalse(f.done.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            retry.release()
            f.await { !f.owner.state.persisting && f.navigations == 1 }
            f.render()
            assertEquals(AppThemeMode.DARK, getConfiguredThemeMode(f.context))
            assertTrue(File(f.root, "retention-config.v1").isFile)
            assertEquals(listOf(ReverbService.ACTION_APPLY_SETTINGS), f.context.serviceActions.toList())
            assertFalse(f.owner.state.hasUnsavedChanges)
            assertTrue(f.node(f.choice(NativeThemeMode.DARK)).isChecked)
            assertFalse(f.node(f.done).isEnabled)
            f.assertNavigationLabel(R.string.onboarding_back)
            assertEquals(1, AppFeedbackCenter.events.value.count {
                it.message.contains("controlled save preflight read")
            })
        }
    }

    @Test fun compactSettingsTitleExportsFullNameAtNormalAndLargeFont() = runBlocking {
        for (scale in listOf(1f, 2f)) withFixture(scale) { f ->
            f.open()
            val title = f.screen.topBar.title
            f.assertPlaced(title)
            val info = f.node(title)
            assertEquals(f.context.getString(R.string.settings_title), info.text.toString())
            assertTrue(info.isVisibleToUser)
            // Ellipsis in the measured visual title must not replace its accessible text.
            assertFalse(info.text.toString().contains('\u2026'))
        }
    }

    @Test fun setupFailureReleasesOwnersAndNextHostRendersSettings() = runBlocking {
        assertNull(ServiceStateOsShadow.fixtureRoot)
        val expected = AssertionError("controlled failure before window focus")
        var failedRoot: File? = null
        var failedScreen: View? = null
        var failedJob: Job? = null
        var observed: Throwable? = null
        try {
            withFixture(beforeWindowFocus = { f ->
                failedRoot = f.root
                failedScreen = f.screen
                failedJob = f.owner.scope.coroutineContext[Job]
                throw expected
            }) { error("Failed setup must not execute the test body") }
        } catch (failure: Throwable) { observed = failure }
        // Coroutine stack-trace recovery may copy an AssertionError and retain it as the cause.
        assertEquals(expected.message, checkNotNull(observed).message)
        assertTrue(generateSequence(observed) { it.cause }.any { it === expected })
        assertTrue("Setup cleanup must not hide a second failure", expected.suppressed.isEmpty())
        assertNull("A failed setup must not contaminate the next host", ServiceStateOsShadow.fixtureRoot)
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
        assertFalse(checkNotNull(failedRoot).exists())
        assertFalse(checkNotNull(failedScreen).isAttachedToWindow)
        assertTrue(checkNotNull(failedJob).isCompleted)

        var successfulRoot: File? = null
        withFixture { f ->
            successfulRoot = f.root
            assertTrue(f.screen.isAttachedToWindow && f.screen.hasWindowFocus())
            f.open() // Real hydration, section factory, native draw and exported choice state.
            assertTrue(f.screen.inputAllowed)
            f.assertPlaced(f.screen.topBar.title)
            assertTrue(f.node(f.choice(NativeThemeMode.LIGHT)).isChecked)
        }
        assertNull(ServiceStateOsShadow.fixtureRoot)
        assertEquals(0, JournalHostIoShadow.outstandingOwners)
        assertFalse(checkNotNull(successfulRoot).exists())
    }

    private suspend fun withFixture(
        fontScale: Float = 1f,
        beforeWindowFocus: (Fixture) -> Unit = {},
        block: suspend (Fixture) -> Unit,
    ) = coroutineScope {
        // Construction is inert; every acquired Android/global/IO resource is now inside finally.
        val fixture = Fixture(this, fontScale)
        var primary: Throwable? = null
        try {
            fixture.initialize(beforeWindowFocus)
            block(fixture)
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            try { withContext(NonCancellable) { fixture.close() } } catch (error: Throwable) {
                val first = primary
                if (first == null) throw error else first.addSuppressed(error)
            }
        }
    }

    private class ReadGate(private val failure: IOException?) {
        val entered = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        fun read() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Settings storage read ran on main" }
            entered.complete(Unit)
            check(released.await(5, TimeUnit.SECONDS)) { "Fixture failed to release Settings IO" }
            failure?.let { throw it }
        }
        fun release() = released.countDown()
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val pending = AtomicReference<ReadGate?>()
        private val gates = mutableListOf<ReadGate>()
        val serviceActions = Collections.synchronizedList(mutableListOf<String?>())
        fun arm(failure: IOException? = null): ReadGate = ReadGate(failure).also {
            check(pending.compareAndSet(null, it)) { "Unconsumed IO gate" }
            gates += it
        }
        fun releaseAll() = gates.forEach(ReadGate::release)
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File {
            pending.getAndSet(null)?.read()
            return root
        }
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int) = false
        override fun startService(service: Intent): ComponentName {
            serviceActions += service.action
            return checkNotNull(service.component)
        }
    }

    private class FrameClock : MonotonicFrameClock {
        private val waiting = mutableListOf<CancellableContinuation<Long>>()
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val time = suspendCancellableCoroutine<Long> { continuation ->
                waiting += continuation
                continuation.invokeOnCancellation { waiting.remove(continuation) }
            }
            return onFrame(time)
        }
        fun frame() {
            now += 16_000_000L
            val batch = waiting.toList()
            waiting.clear()
            batch.forEach { if (it.isActive) it.resume(now) }
        }
    }

    private class Fixture(private val parent: CoroutineScope, private val fontScale: Float) : LifecycleOwner {
        private lateinit var activity: ActivityController<Activity>
        override lateinit var lifecycle: LifecycleRegistry
            private set
        lateinit var root: File
            private set
        lateinit var context: FixtureContext
            private set
        lateinit var preferences: SharedPreferences
            private set
        private lateinit var board: FrameLayout
        private lateinit var job: Job
        private val clock = FrameClock()
        private lateinit var scope: CoroutineScope
        private lateinit var environment: ReverbViewEnvironment
        private var ownsIoFixture = false
        private var retired = false
        var navigations = 0
            private set
        lateinit var owner: NativeSettingsOwner
            private set
        private var accessibility: NativeSettingsAccessibilityBinding? = null
        lateinit var screen: NativeSettingsScreenContent
            private set
        private lateinit var textWindow: NativeWindowTextAccessibilityOwner
        private lateinit var scrollEvents: NativeWindowScrollAccessibilityEvents
        private lateinit var feedback: NativeAppFeedbackHostView
        val done get() = screen.topBar.done.visual

        fun initialize(beforeWindowFocus: (Fixture) -> Unit) {
            activity = Robolectric.buildActivity(Activity::class.java)
            activity.setup().visible()
            val decor = activity.get().window.decorView
            awaitWindowState("Activity decor must attach before window focus dispatch") {
                decor.isAttachedToWindow
            }
            lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            check(ServiceStateOsShadow.fixtureRoot == null) { "Another fixture retained the IO root" }
            root = Files.createTempDirectory("settings-rendered-recovery-").toFile()
            JournalHostIoShadow.reset()
            ServiceStateOsShadow.fixtureRoot = root.toPath()
            ownsIoFixture = true
            // This Robolectric host owns its process feedback bus. Remove leftovers before
            // mounting the actual queue-head renderer, rather than substituting a fake event.
            while (true) AppFeedbackCenter.events.value.firstOrNull()?.let { AppFeedbackCenter.acknowledge(it.id) } ?: break
            context = FixtureContext(activity.get(), root)
            preferences = getRecorderPreferences(context)
            check(preferences.edit().clear().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true)
                .putInt(PrefKey.THEME_MODE, AppThemeMode.LIGHT.storageCode.toInt()).commit())
            board = FrameLayout(context) // Real AttachInfo/window focus; no focus override.
            job = Job(parent.coroutineContext[Job])
            scope = CoroutineScope(parent.coroutineContext + job + clock + object : MotionDurationScale {
                override val scaleFactor = 0f // Settle decoration only; real durability/backoff still executes.
            })
            environment = ReverbViewEnvironment(Density(1f, fontScale),
                createFontFamilyResolver(context), ReverbPalette.fallback(false))
            owner = NativeSettingsOwner(context, board, lifecycle, scope, true,
                isPanelActive = { true }, onThemePreview = {}, releaseInputFocus = { board.clearFocus() },
                launchDirectory = { error("Unexpected picker launch") }, onBack = { navigations++ })
            screen = NativeSettingsScreenContent(context, owner, NativeSettingsScreenInput(active = true),
                environment, this, OnBackPressedDispatcher(), board, clock, scope, NoNestedScroll,
                parentInterested = { false }, changedNodes = { accessibility?.refresh() })
            textWindow = NativeWindowTextAccessibilityOwner(board,
                NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
            scrollEvents = textWindow.openScrollEvents(scope, { accessibility?.refresh() }, {})
            feedback = NativeAppFeedbackHostView(context, environment, board, this, scope,
                null, textWindow, NativeSourceAccessibilityNode::fromView)
            // Reuse the retained-shell fixture's isolation of the unrelated catalog scan. The
            // Settings hydration/transaction/rollback methods and callbacks are never replaced.
            val move = NativeSettingsOwner::class.java.getDeclaredField("moveAvailability")
                .apply { isAccessible = true }.get(owner)
            val scan: suspend (String, () -> Boolean) -> Boolean = { _, _ -> false }
            move.javaClass.getDeclaredField("scan").apply { isAccessible = true }.set(move, scan)
            activity.get().setContentView(board)
            board.addView(screen, FrameLayout.LayoutParams(-1, -1))
            board.addView(feedback, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            beforeWindowFocus(this)
            activity.windowFocusChanged(true)
            // ActivityController dispatches resize/focus then idles due work. API 35 can defer
            // focus until a future traversal; use the real frame/predicate sequence from
            // NativeCaptureRangeHandoffTest, not another immediate drain or fake focus bit.
            awaitWindowState("Settings must receive real window focus after attachment") {
                decor.hasWindowFocus() && board.hasWindowFocus() && screen.hasWindowFocus()
            }
            assertTrue("Settings must receive real window focus after attachment", screen.hasWindowFocus())
            accessibility = NativeSettingsAccessibilityBinding(screen, textWindow,
                NativeSourceAccessibilityNode::fromView, scrollEvents).also { it.refresh() }
            assertTrue(screen.isAttachedToWindow)
        }

        suspend fun open() {
            screen.start()
            await { owner.state.interactionReady }
            showTheme()
        }

        suspend fun await(complete: () -> Boolean) = withTimeout(5_000) {
            while (!complete()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(1)
            }
        }

        suspend fun render() {
            // Ordinary Settings has no About/Library-Undo suppression. Match Main's public
            // bind contract; true-to-true deliberately does not force admission to resample.
            feedback.bind(true, environment)
            repeat(3) {
                delay(1) // Drain owner/feedback coroutines on the test's main event loop.
                // The real feedback host posts its mount on Main. A pending ViewRoot traversal
                // can hold that post behind a sync barrier until the next PLATFORM frame.
                // The separate fixture animation clock below cannot service that traversal.
                ShadowSystemClock.advanceBy(Duration.ofMillis(16))
                drainDueTasks(shadowOf(Looper.getMainLooper()))
                val decor = activity.get().window.decorView
                decor.measure(exact(199), exact(421))
                decor.layout(0, 0, 199, 421)
                board.measure(exact(199), exact(421))
                board.layout(0, 0, 199, 421)
                accessibility?.refresh()
                clock.frame()
            }
            delay(1)
            val bitmap = Bitmap.createBitmap(199, 421, Bitmap.Config.ARGB_8888)
            try { board.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        }

        suspend fun showTheme() {
            val restricted = owner.batteryOptimizationRestricted || owner.resumeNotificationBlocked || owner.restartAccessMissing
            val index = SettingsSectionKey.visible(restricted).indexOf(SettingsSectionKey.THEME)
            val viewport = checkNotNull(screen.listViewport)
            (viewport.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(index, 0)
            render()
        }

        fun choice(mode: NativeThemeMode): NativeChip {
            val selector = descendants(screen).filterIsInstance<NativeThemeSelector>().single()
            val row = descendants(selector).filterIsInstance<NativeSegmentedChoice>().single()
            return checkNotNull(row.choiceView(mode.name)).also(::assertPlaced)
        }

        fun node(view: View): AccessibilityNodeInfo = checkNotNull(view.createAccessibilityNodeInfo())
        fun assertPlaced(view: View) {
            assertTrue(view.isAttachedToWindow && view.isShown)
            assertTrue(view.width > 0 && view.height > 0)
        }

        fun assertLoading(retained: Boolean) {
            assertFalse(screen.inputAllowed)
            val progress = descendants(screen).filterIsInstance<NativeCircularProgress>().single()
            assertPlaced(progress)
            val info = node(progress)
            assertEquals("android.widget.ProgressBar", info.className.toString())
            assertEquals(context.getString(smallthingz.ui.R.string.in_progress), info.stateDescription.toString())
            assertNull(info.rangeInfo)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, screen.topBar.importantForAccessibility)
            val focusable = arrayListOf<View>()
            screen.addFocusables(focusable, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
            assertTrue(focusable.isEmpty())
            if (retained) {
                assertEquals(.38f, checkNotNull(screen.sectionContent).alpha, 0f)
            } else {
                assertNull(screen.sectionContent)
                assertFalse(descendants(screen).any { it is NativeThemeSelector })
            }
        }

        suspend fun assertErrorVisible(fragment: String) {
            val event = AppFeedbackCenter.events.value.single { it.message.contains(fragment) }
            assertEquals(FeedbackTone.ERROR, event.tone)
            assertEquals("The injected error must be the actual delivery queue head", event.id,
                checkNotNull(AppFeedbackCenter.events.value.firstOrNull()).id)
            // Wait for the real collector -> Handler post -> layout chain, not just enqueue.
            // Bound the wait and retain state diagnostics if admission/mount never arrives.
            var matches = errorParagraphs(event.message)
            var turns = 0
            while (matches.isEmpty() && turns++ < 8) {
                render()
                matches = errorParagraphs(event.message)
            }
            assertEquals("Expected one real error paragraph; ${feedbackDiagnostic()}", 1, matches.size)
            val text = matches.single()
            assertPlaced(text)
            assertEquals(event.message, node(text).text.toString())
            assertTrue(node(text).isVisibleToUser)
            var view: View? = text
            while (view != null && view !== board) {
                assertTrue("Error card must have painted exposure", view.alpha > 0f)
                view = view.parent as? View
            }
        }

        private fun errorParagraphs(message: String) = descendants(feedback)
            .filterIsInstance<ParagraphView>().filter { it.getText() == message }.toList()

        /** Read-only application-owned state, emitted only in an assertion diagnostic. */
        private fun feedbackDiagnostic(): String {
            fun read(target: Any, name: String): Any? {
                var type: Class<*>? = target.javaClass
                while (type != null) {
                    try { return type.getDeclaredField(name).apply { isAccessible = true }.get(target) }
                    catch (_: NoSuchFieldException) { type = type.superclass }
                }
                return "<missing $name>"
            }
            val delivery = read(feedback, "owner")
            val allowed = delivery?.let { read(it, "allowed") } as? kotlinx.coroutines.flow.StateFlow<*>
            return "root(attached=${board.isAttachedToWindow},shown=${board.isShown},focus=${board.hasWindowFocus()}), " +
                "lifecycle=${lifecycle.currentState}, allowed=${allowed?.value}, " +
                "deliveryAllowed=${delivery?.let { read(it, "deliveryAllowed") }}, " +
                "visibleAllowed=${read(feedback, "visibleAllowed")}, pendingEvent=${read(feedback, "pendingEvent")}, " +
                "renderPosted=${read(feedback, "renderPosted")}, children=${feedback.childCount}, " +
                "queueHead=${AppFeedbackCenter.events.value.firstOrNull()}, " +
                "paragraphs=${descendants(feedback).filterIsInstance<ParagraphView>().map { it.getText() }.toList()}"
        }

        fun assertNavigationLabel(resource: Int) {
            // The source-compatible merged action exports its name on the Icon description
            // child; the parent owns the click and intentionally has no duplicate name.
            val children = arrayListOf<View>()
            screen.topBar.navigation.visual.addChildrenForAccessibility(children)
            val description = children.filterIsInstance<NativeActionDescriptionView>().single()
            assertEquals(context.getString(resource), node(description).contentDescription.toString())
            assertTrue(node(description).isVisibleToUser)
        }

        suspend fun close() {
            if (retired) return
            retired = true
            val failures = mutableListOf<Throwable>()
            fun retire(action: () -> Unit) { try { action() } catch (error: Throwable) { failures += error } }
            if (::context.isInitialized) retire(context::releaseAll)
            if (::feedback.isInitialized) retire(feedback::close)
            retire { accessibility?.close() }
            if (::scrollEvents.isInitialized) retire(scrollEvents::close)
            if (::screen.isInitialized) retire(screen::close)
            if (::owner.isInitialized) retire(owner::close) // Also covers a failed screen constructor.
            if (::lifecycle.isInitialized) retire { lifecycle.currentState = Lifecycle.State.DESTROYED }
            if (::job.isInitialized) {
                job.cancel()
                // Pump Main cleanup too; cancellation of a partially constructed host still
                // includes all child IO before removing the fixture's backing directory.
                try {
                    withTimeout(10_000) {
                        while (!job.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(1) }
                    }
                } catch (failure: Throwable) { failures += failure }
            }
            if (::activity.isInitialized) retire { activity.pause().stop().destroy() }
            retire {
                check(!::job.isInitialized || job.isCompleted) { "Fixture IO is still active; preserving root and descriptors" }
                check(!ownsIoFixture || ServiceStateOsShadow.fixtureRoot == root.toPath()) {
                    "Fixture IO root changed owner; preserving root and descriptors"
                }
                if (ownsIoFixture) {
                    retire { JournalHostIoShadow.finishFixture()?.let { throw it } }
                    ServiceStateOsShadow.fixtureRoot = null
                    ownsIoFixture = false
                    while (true) AppFeedbackCenter.events.value.firstOrNull()?.let { AppFeedbackCenter.acknowledge(it.id) } ?: break
                }
                if (::root.isInitialized) retire { check(root.deleteRecursively()) }
            }
            failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
        }
    }

    private object NoNestedScroll : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }

    private companion object {
        fun awaitWindowState(description: String, reached: () -> Boolean) {
            val looper = shadowOf(Looper.getMainLooper())
            repeat(8) {
                if (reached()) return
                ShadowSystemClock.advanceBy(Duration.ofMillis(16))
                drainDueTasks(looper)
            }
            assertTrue("$description after eight frame steps", reached())
        }

        fun drainDueTasks(looper: ShadowLooper) {
            repeat(64) {
                if (looper.isIdle) return
                looper.runOneTask()
            }
            assertTrue("Main looper still has due work after 64 messages", looper.isIdle)
        }

        fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
