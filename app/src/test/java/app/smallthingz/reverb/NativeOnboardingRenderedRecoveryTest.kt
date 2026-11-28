package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import smallthingz.ui.components.NativeCircularProgress
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.platform.AndroidUiDispatcher
import smallthingz.ui.text.view.ParagraphView

/** Real app owner/content/pager/footer/feedback. Only platform/storage boundaries are supplied.
 * One Robolectric sandbox owns the real process Finish coordinator through its terminal success;
 * this fixture never resets or replaces that coordinator or any production admission state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w199dp-h421dp-mdpi", application = Application::class,
    shadows = [JournalHostIoShadow::class, SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeOnboardingRenderedRecoveryTest {
    private var oldPaused = false
    private lateinit var oldDelay: Duration

    @Before fun pauseFrames() {
        oldPaused = ShadowChoreographer.isPaused()
        oldDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After fun restoreFrames() {
        ShadowChoreographer.setFrameDelay(oldDelay)
        ShadowChoreographer.setPaused(oldPaused)
    }

    @Test fun compactOwnerReadAndFinishRecoveryRendersHomeAndRetiresOnboarding() = runBlocking {
        // Inert construction: setup failures are covered by the same finally as test failures.
        val f = Fixture()
        var primary: Throwable? = null
        try {
            f.initialize()
            val failedRead = f.storage.armRead(IOException("onboarding controlled read"))
            f.owner.attach()
            f.await("initial durable read entered") { failedRead.entered.count == 0L }
            f.assertLoading()
            val retriedRead = f.storage.armRead()
            failedRead.release()
            f.awaitError("onboarding controlled read")
            f.await("real hydration backoff reached the second read") { retriedRead.entered.count == 0L }
            f.assertLoading()
            assertFalse(f.storage.preferences.getBoolean(PrefKey.ONBOARDING_SHOWN.name, false))
            retriedRead.release()
            f.awaitPage(0)
            f.awaitFeedbackRetired("onboarding controlled read")
            f.assertFooterInsideWindow()

            f.clickNext()
            f.awaitPage(1)
            f.assertFooterInsideWindow()
            val original = checkNotNull(f.owner.snapshot.page)
            assertTrue(original.oneShotEnabled && original.loopingEnabled)
            val row = descendants(f.onboarding()).filterIsInstance<OnboardingBufferRow>().single { candidate ->
                descendants(candidate).filterIsInstance<ParagraphView>().any {
                    it.getText() == f.activity.getString(R.string.onboarding_one_shot_title)
                }
            }
            row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
            f.await("buffer row is exposed by the real viewport") {
                row.createAccessibilityNodeInfo()?.isVisibleToUser == true
            }
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            f.await("real buffer callback updates only the draft") { f.owner.snapshot.page?.oneShotEnabled == false }
            assertTrue(withContext(Dispatchers.IO) { getConfiguredBufferAvailability(f.storage).oneShotEnabled })
            f.clickNext()
            f.awaitPage(2)
            // Back is the live footer callback, not an injected pager position.
            assertTrue(f.back().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            f.awaitPage(1)
            assertFalse(checkNotNull(f.owner.snapshot.page).oneShotEnabled)
            f.clickNext()
            f.awaitPage(2)

            val oldScreen = f.onboarding()
            val oldFinish = f.next()
            val failedCommit = f.storage.armFinish(IOException("onboarding controlled commit"))
            f.clickNext()
            f.await("actual preferences commit entered") { failedCommit.entered.count == 0L }
            f.await("Finish renders its pending state") { f.owner.snapshot.finishing && !oldFinish.isEnabled }
            assertFalse(oldFinish.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(f.back().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(f.storage.preferences.getBoolean(PrefKey.ONBOARDING_SHOWN.name, false))
            failedCommit.release()
            f.awaitError("onboarding controlled commit")
            f.awaitPage(2)
            assertFalse(f.owner.snapshot.finishing)
            assertSame(oldScreen, f.onboarding())
            assertSame(oldFinish, f.next())
            assertFalse(checkNotNull(f.owner.snapshot.page).oneShotEnabled)
            assertFalse(f.storage.preferences.getBoolean(PrefKey.ONBOARDING_SHOWN.name, false))
            assertTrue(withContext(Dispatchers.IO) { getConfiguredBufferAvailability(f.storage).oneShotEnabled })
            assertEquals(0, f.storage.successfulFinishes.get())
            f.awaitFeedbackRetired("onboarding controlled commit")
            f.assertFooterInsideWindow()

            val successfulCommit = f.storage.armFinish()
            f.clickNext()
            f.await("retry owns a second real commit") { successfulCommit.entered.count == 0L }
            assertEquals(2, f.storage.finishAttempts.get())
            successfulCommit.release()
            // The mounted Home blob uses RuntimeShader, unsupported by Canvas(Bitmap).
            // Keep the actual Home/window/settlement oracle; this is not a Home pixel test.
            f.await("successful Finish mounts and settles the actual Main view", softwareDraw = false) {
                f.homeMounted()
            }
            assertEquals(OnboardingFinishState.SUCCEEDED, processOnboardingFinish.state.value)
            assertTrue(f.storage.preferences.getBoolean(PrefKey.ONBOARDING_SHOWN.name, false))
            assertFalse(withContext(Dispatchers.IO) { getConfiguredBufferAvailability(f.storage).oneShotEnabled })
            assertEquals(1, f.storage.successfulFinishes.get())
            assertEquals(1, f.storage.serviceActions.count { it == ReverbService.ACTION_APPLY_SETTINGS })
            assertFalse(oldScreen.isAttachedToWindow)
            assertTrue(descendants(f.content).none { it is NativeOnboardingScreenView })
            val before = f.storage.preferences.all.toMap()
            // A retired view may return a platform handled bit; the decisive oracle is no effect.
            oldFinish.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            repeat(4) { f.frame() }
            assertEquals(2, f.storage.finishAttempts.get())
            assertEquals(1, f.storage.successfulFinishes.get())
            assertEquals(before, f.storage.preferences.all)
            assertFalse(f.owner.snapshot.showingOnboarding)
            assertEquals(0, f.unexpectedLaunches)
            assertTrue("Actual Home remains mounted after retired actions", f.homeMounted())
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try { withContext(NonCancellable) { f.close() } }
            catch (cleanup: Throwable) {
                val first = primary
                if (first == null) throw cleanup else first.addSuppressed(cleanup)
            }
        }
    }

    private class IoGate(private val failure: IOException?) {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun cross() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Durable IO ran on Main" }
            entered.countDown()
            check(released.await(15, TimeUnit.SECONDS)) { "Unreleased fixture IO gate" }
            failure?.let { throw it }
        }
        fun release() = released.countDown()
    }

    private class Storage(base: Context, val root: File) : ContextWrapper(base) {
        private val read = AtomicReference<IoGate?>()
        private val finish = AtomicReference<IoGate?>()
        private val gates = mutableListOf<IoGate>()
        val activeCalls = AtomicInteger()
        val finishAttempts = AtomicInteger()
        val successfulFinishes = AtomicInteger()
        val serviceActions = Collections.synchronizedList(mutableListOf<String?>())
        private val raw = base.getSharedPreferences(base.packageName, Context.MODE_PRIVATE)
        val preferences: SharedPreferences = object : SharedPreferences by raw {
            override fun edit(): SharedPreferences.Editor {
                val real = raw.edit()
                return object : SharedPreferences.Editor {
                    private var completesOnboarding = false
                    override fun putBoolean(key: String, value: Boolean) = apply {
                        if (key == PrefKey.ONBOARDING_SHOWN.name) completesOnboarding = value
                        real.putBoolean(key, value)
                    }
                    override fun putInt(key: String, value: Int) = apply { real.putInt(key, value) }
                    override fun putLong(key: String, value: Long) = apply { real.putLong(key, value) }
                    override fun putFloat(key: String, value: Float) = apply { real.putFloat(key, value) }
                    override fun putString(key: String, value: String?) = apply { real.putString(key, value) }
                    override fun putStringSet(key: String, values: MutableSet<String>?) = apply { real.putStringSet(key, values) }
                    override fun remove(key: String) = apply {
                        if (key == PrefKey.ONBOARDING_SHOWN.name) completesOnboarding = false
                        real.remove(key)
                    }
                    override fun clear() = apply { completesOnboarding = false; real.clear() }
                    override fun commit(): Boolean {
                        activeCalls.incrementAndGet()
                        try {
                            if (completesOnboarding) {
                                finishAttempts.incrementAndGet()
                                finish.getAndSet(null)?.cross()
                            }
                            return real.commit().also { if (it && completesOnboarding) successfulFinishes.incrementAndGet() }
                        } finally { activeCalls.decrementAndGet() }
                    }
                    override fun apply() { check(!completesOnboarding); real.apply() }
                }
            }
        }
        fun armRead(failure: IOException? = null) = arm(read, failure)
        fun armFinish(failure: IOException? = null) = arm(finish, failure)
        private fun arm(slot: AtomicReference<IoGate?>, failure: IOException?) = IoGate(failure).also {
            check(slot.compareAndSet(null, it)); gates += it
        }
        fun releaseAll() = gates.forEach(IoGate::release)
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == packageName) preferences else super.getSharedPreferences(name, mode)
        override fun getNoBackupFilesDir(): File {
            activeCalls.incrementAndGet()
            try { read.getAndSet(null)?.cross(); return root }
            finally { activeCalls.decrementAndGet() }
        }
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int) = false
        override fun startService(service: Intent): ComponentName {
            serviceActions += service.action
            return checkNotNull(service.component)
        }
    }

    private class Fixture {
        private lateinit var controller: ActivityController<OnboardingRecoveryHostActivity>
        val activity get() = controller.get()
        lateinit var storage: Storage
        lateinit var owner: NativeOnboardingScreenOwner
        lateinit var content: NativeActivityContentView
        private lateinit var runtime: NativeModalWindowRuntime
        private var dispatcherAtSetup = "uninitialized"
        private lateinit var lifecycleJob: Job
        private var ownsRoot = false
        private var oldOverlay: Boolean? = null
        private var power: PowerManager? = null
        private var oldBatteryExemption = false
        private val granted = mutableListOf<String>()
        var unexpectedLaunches = 0
            private set

        fun initialize() {
            check(ServiceStateOsShadow.fixtureRoot == null)
            check(processOnboardingFinish.state.value == OnboardingFinishState.IDLE)
            val app = RuntimeEnvironment.getApplication()
            JournalHostIoShadow.reset()
            val root = Files.createTempDirectory("onboarding-rendered-").toFile()
            try { storage = Storage(app, root) }
            catch (failure: Throwable) {
                try { check(root.deleteRecursively()) }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            ServiceStateOsShadow.fixtureRoot = storage.root.toPath()
            ownsRoot = true
            while (true) AppFeedbackCenter.events.value.firstOrNull()?.let { AppFeedbackCenter.acknowledge(it.id) } ?: break
            check(storage.preferences.edit().clear().commit())
            for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_AUDIO)) {
                if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    granted += permission
                    shadowOf(app).grantPermissions(permission)
                }
            }
            oldOverlay = Settings.canDrawOverlays(app)
            ShadowSettings.setCanDrawOverlays(true)
            val manager = checkNotNull(app.getSystemService(PowerManager::class.java))
            oldBatteryExemption = manager.isIgnoringBatteryOptimizations(app.packageName)
            power = manager
            shadowOf(manager).setIgnoringBatteryOptimizations(app.packageName, true)
            controller = Robolectric.buildActivity(OnboardingRecoveryHostActivity::class.java)
            controller.setup().visible()
            activity.storage = storage
            lifecycleJob = checkNotNull(activity.lifecycleScope.coroutineContext[Job])
            // Observe cached scheduling state before this fixture submits any runtime work.
            // Do not reset/re-arm it: the rendered test must exercise the production runtime.
            dispatcherAtSetup = dispatcherDiagnostic()
            runtime = NativeModalWindowRuntime(activity)
            runtime.setStarted(true)
            val environment = ReverbViewEnvironment(activity, false)
            assertEquals(2f, environment.textDensity.fontScale, 0f)
            val launcher = object : ActivityResultLauncher<String>() {
                override fun launch(input: String, options: ActivityOptionsCompat?) {
                    unexpectedLaunches++
                    error("Unexpected permission prompt: $input")
                }
                override fun unregister() = Unit
                override val contract: ActivityResultContract<String, *> = ActivityResultContracts.RequestPermission()
            }
            owner = NativeOnboardingScreenOwner(activity, null,
                NativeOnboardingPermissionLaunchers(launcher, launcher, launcher, launcher)) {
                if (::content.isInitialized) content.bind(it, environment)
            }
            content = NativeActivityContentView(activity, environment, owner, runtime.scope,
                NativeActivityAccessibilityManager(activity), null, null, {},
                { NativeDirectoryPickerRegistration({ error("Unexpected picker") }, {}) },
                { error("Unexpected settings navigation") })
            // Same public edge-to-edge contract as MainActivity.configureWindow().
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.setContentView(content)
            content.bind(owner.snapshot, environment)
            controller.visible()
            repeat(8) { platformFrame() }
            assertTrue(content.isAttachedToWindow)
            controller.windowFocusChanged(true)
            repeat(8) { platformFrame() }
            assertTrue("Real window focus must arrive after actual attachment", content.hasWindowFocus())
            layout()
        }

        fun onboarding() = descendants(content).filterIsInstance<NativeOnboardingScreenView>().single()
        private fun footer() = descendants(onboarding()).filterIsInstance<OnboardingFooterView>().single()
        fun next() = footer().getChildAt(1) as NativeMaterialButtonView
        fun back() = footer().getChildAt(0) as NativeMaterialButtonView
        fun clickNext() {
            assertTrue(checkNotNull(next().createAccessibilityNodeInfo()).isVisibleToUser)
            assertTrue(next().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        suspend fun awaitPage(page: Int) = await("settled exposed onboarding page $page") {
            descendants(content).filterIsInstance<NativeOnboardingScreenView>().singleOrNull()?.let { screen ->
                val saved = screen.saveViewportState()
                saved.page == page && saved.offsetFraction == 0f && !owner.snapshot.finishing &&
                    descendants(screen).filterIsInstance<OnboardingPageContentView>().count { it.acceptsPointerInput } == 1
            } == true
        }
        fun homeMounted(): Boolean = !owner.snapshot.showingOnboarding &&
            descendants(content).filterIsInstance<NativeMainScreenView>().singleOrNull()?.let {
                it.isAttachedToWindow && it.hasWindowFocus() && it.alpha == 1f && it.width > 0 && it.height > 0
            } == true

        suspend fun await(message: String, softwareDraw: Boolean = true, ready: () -> Boolean) {
            try {
                withTimeout(10_000) {
                    while (!ready()) frame()
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("$message; attached=${content.isAttachedToWindow}, " +
                    "focused=${content.hasWindowFocus()}, owner=${owner.snapshot}, " +
                    "feedback=${AppFeedbackCenter.events.value.map { it.message }}; " +
                    "${renderDiagnostic()}; ${ownershipDiagnostic()}", timeout)
            }
            assertTrue(message, ready())
            if (softwareDraw) draw()
        }
        suspend fun frame() { platformFrame(); layout(); delay(1) }
        private fun layout() {
            val decor = activity.window.decorView
            // Only constrain the window. Its normal parent/inset pass owns content placement;
            // independently stretching content can put the footer outside the real window clip.
            decor.measure(exact(199), exact(421)); decor.layout(0, 0, 199, 421)
        }
        fun draw() {
            check(owner.snapshot.showingOnboarding) { "Software draw is only for onboarding/loading/feedback" }
            check(content.width > 0 && content.height > 0)
            val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            try { content.draw(Canvas(image)) } finally { image.recycle() }
        }
        fun assertLoading() {
            assertNull(owner.snapshot.page)
            assertTrue(descendants(content).none { it is NativeOnboardingScreenView })
            val spinner = descendants(content).filterIsInstance<NativeCircularProgress>().single()
            assertTrue(spinner.isAttachedToWindow && spinner.width > 0 && spinner.height > 0)
            val node = checkNotNull(spinner.createAccessibilityNodeInfo())
            assertEquals("android.widget.ProgressBar", node.className.toString())
            assertTrue(node.isVisibleToUser)
            assertEquals(activity.getString(smallthingz.ui.R.string.in_progress), node.stateDescription.toString())
            assertNull(node.rangeInfo)
        }
        suspend fun awaitError(marker: String) {
            await("rendered feedback contains $marker") {
                descendants(content).filterIsInstance<ParagraphView>().any {
                    it.getText().contains(marker) && it.createAccessibilityNodeInfo()?.isVisibleToUser == true && painted(it)
                }
            }
            val paragraph = descendants(content).filterIsInstance<ParagraphView>().single { it.getText().contains(marker) }
            assertEquals(paragraph.getText(), checkNotNull(paragraph.createAccessibilityNodeInfo()).text.toString())
            val head = checkNotNull(AppFeedbackCenter.events.value.firstOrNull())
            assertEquals(paragraph.getText(), head.message)
            assertEquals(FeedbackTone.ERROR, head.tone)
        }
        suspend fun awaitFeedbackRetired(marker: String) {
            // Delivery acknowledgement and the Handler-posted removal are distinct turns.
            // A still-mounted feedback Text legitimately occludes footer source semantics.
            // Do not acknowledge the queue or alter exposure from the fixture.
            await("real delivery and rendered removal of $marker") {
                AppFeedbackCenter.events.value.isEmpty() &&
                    descendants(content).filterIsInstance<ParagraphView>().none { it.getText().contains(marker) }
            }
        }

        fun assertFooterInsideWindow() {
            for (button in listOf(back(), next())) {
                val bounds = Rect(0, 0, button.width, button.height)
                content.offsetDescendantRectToMyCoords(button, bounds)
                assertTrue("Footer target must fit compact font2 content: $bounds; ${footerDiagnostic(button)}",
                    bounds.left >= 0 && bounds.top >= 0 && bounds.right <= content.width &&
                        bounds.bottom <= content.height && !bounds.isEmpty)
                val buttonNode = checkNotNull(button.createAccessibilityNodeInfo()) { footerDiagnostic(button) }
                assertTrue("Footer action node must be visible; ${footerDiagnostic(button)}", buttonNode.isVisibleToUser)
                val exported = arrayListOf<View>()
                button.addChildrenForAccessibility(exported)
                assertTrue("Source-bound footer must export its label; ${footerDiagnostic(button)}",
                    exported.any { it === button.label })
                val labelNode = checkNotNull(button.label.createAccessibilityNodeInfo()) { footerDiagnostic(button) }
                assertEquals(button.label.getText(), labelNode.text.toString())
                assertTrue("Footer label must be visible after feedback retires; ${footerDiagnostic(button)}",
                    labelNode.isVisibleToUser)
            }
        }

        private fun footerDiagnostic(button: NativeMaterialButtonView): String {
            fun describe(view: View): String {
                val visible = Rect()
                val hasVisibleRect = view.getGlobalVisibleRect(visible)
                val screen = IntArray(2)
                view.getLocationOnScreen(screen)
                val node = view.createAccessibilityNodeInfo()
                val semanticBounds = Rect()
                node?.getBoundsInScreen(semanticBounds)
                return "${view.javaClass.simpleName}{local=${Rect(view.left, view.top, view.right, view.bottom)}, " +
                    "screen=${screen.toList()}, globalVisible=$hasVisibleRect:$visible, " +
                    "node=${node != null}, nodeVisible=${node?.isVisibleToUser}, nodeBounds=$semanticBounds, " +
                    "text=${node?.text}, description=${node?.contentDescription}}"
            }
            val exported = arrayListOf<View>()
            button.addChildrenForAccessibility(exported)
            return listOf(activity.window.decorView, content, button, button.label).joinToString("; ", transform = ::describe) +
                "; exported=${exported.map { it.javaClass.simpleName }}; " +
                "events=${AppFeedbackCenter.events.value.map { it.message }}"
        }

        private fun renderDiagnostic(): String {
            val host = descendants(content).filterIsInstance<NativeAppFeedbackHostView>().singleOrNull()
            val paragraphs = host?.let { descendants(it).filterIsInstance<ParagraphView>().map { text ->
                "${text.getText()}{size=${text.width}x${text.height}, shown=${text.isShown}, " +
                    "painted=${painted(text)}, nodeVisible=${text.createAccessibilityNodeInfo()?.isVisibleToUser}}"
            }.toList() }
            return "feedbackHost=" +
                "${host?.let { "${it.width}x${it.height}, attached=${it.isAttachedToWindow}" }}, paragraphs=$paragraphs"
        }

        /** Observe registrations as well as flags; a queue/flag alone does not prove a reset. */
        private fun dispatcherDiagnostic(): String = try {
            fun read(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(target)
            val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
            synchronized(checkNotNull(read(dispatcher, "lock"))) {
                val handler = read(dispatcher, "handler") as Handler
                val callback = checkNotNull(read(dispatcher, "dispatchCallback"))
                val queues = read(dispatcher.choreographer, "mCallbackQueues") as Array<*>
                var frameRegistered = false
                for (queue in queues) {
                    var record = read(checkNotNull(queue), "mHead")
                    while (record != null) {
                        if (read(record, "action") === callback) frameRegistered = true
                        record = read(record, "next")
                    }
                }
                "sameChoreographer=${dispatcher.choreographer === Choreographer.getInstance()}, " +
                    "sameLooper=${handler.looper === Looper.getMainLooper()}, " +
                    "trampolineScheduled=${read(dispatcher, "scheduledTrampolineDispatch")}, " +
                    "frameScheduled=${read(dispatcher, "scheduledFrameDispatch")}, " +
                    "trampolined=${(read(dispatcher, "toRunTrampolined") as Collection<*>).size}, " +
                    "frameWaiters=${(read(dispatcher, "toRunOnFrame") as Collection<*>).size}, " +
                    "spareFrames=${(read(dispatcher, "spareToRunOnFrame") as Collection<*>).size}, " +
                    "handlerRegistered=${handler.hasCallbacks(callback as Runnable)}, frameRegistered=$frameRegistered"
            }
        } catch (error: Exception) {
            "dispatcher diagnostic unavailable: $error"
        }

        private fun ownershipDiagnostic(): String {
            fun describe(job: Job) = "$job children=${job.children.toList()}"
            return "lifecycle=${if (::lifecycleJob.isInitialized) describe(lifecycleJob) else "uninitialized"}, " +
                "window=${if (::runtime.isInitialized) runtime.scope.coroutineContext[Job]?.let(::describe) else "uninitialized"}, " +
                "finish=${processOnboardingFinish.state.value}, " +
                "storageCalls=${if (::storage.isInitialized) storage.activeCalls.get() else 0}, " +
                "ownsRoot=$ownsRoot, fixtureRoot=${ServiceStateOsShadow.fixtureRoot}, " +
                "dispatcherAtSetup=[$dispatcherAtSetup], dispatcherNow=[${dispatcherDiagnostic()}]"
        }

        suspend fun close() {
            val errors = mutableListOf<Throwable>()
            fun release(action: () -> Unit) { try { action() } catch (t: Throwable) { errors += t } }
            if (::storage.isInitialized) release(storage::releaseAll)
            if (::owner.isInitialized) release(owner::close)
            if (::content.isInitialized) release(content::close)
            if (::runtime.isInitialized) release(runtime::close)
            if (::controller.isInitialized) release { controller.pause().stop().destroy() }
            try {
                withTimeout(10_000) {
                    while ((::lifecycleJob.isInitialized && !lifecycleJob.isCompleted) ||
                        (::runtime.isInitialized && runtime.scope.coroutineContext[Job]?.isCompleted != true) ||
                        processOnboardingFinish.state.value == OnboardingFinishState.RUNNING ||
                        (::storage.isInitialized && storage.activeCalls.get() != 0)) {
                        platformFrame(); delay(1)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                errors += AssertionError("Fixture owners did not retire; ${ownershipDiagnostic()}", timeout)
            } catch (t: Throwable) { errors += t }
            release {
                check(!::lifecycleJob.isInitialized || lifecycleJob.isCompleted) { ownershipDiagnostic() }
                check(!::runtime.isInitialized || runtime.scope.coroutineContext[Job]?.isCompleted == true) { ownershipDiagnostic() }
                check(processOnboardingFinish.state.value != OnboardingFinishState.RUNNING)
                check(!::storage.isInitialized || storage.activeCalls.get() == 0)
                check(!ownsRoot || ServiceStateOsShadow.fixtureRoot == storage.root.toPath())
                if (ownsRoot) {
                    JournalHostIoShadow.finishFixture()?.let(errors::add)
                    while (true) AppFeedbackCenter.events.value.firstOrNull()?.let {
                        AppFeedbackCenter.acknowledge(it.id)
                    } ?: break
                    ServiceStateOsShadow.fixtureRoot = null
                    ownsRoot = false
                    release { check(storage.root.deleteRecursively()) }
                }
                // Restore shared platform observations only after all owned work is terminal.
                release { oldOverlay?.let(ShadowSettings::setCanDrawOverlays) }
                release { power?.let {
                    shadowOf(it).setIgnoringBatteryOptimizations(RuntimeEnvironment.getApplication().packageName, oldBatteryExemption)
                } }
                release { shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(*granted.toTypedArray()) }
            }
            errors.firstOrNull()?.let { first -> errors.drop(1).forEach(first::addSuppressed); throw first }
        }
    }

    companion object {
        private fun exact(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
        private fun platformFrame() {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            val main = shadowOf(Looper.getMainLooper())
            repeat(128) { if (main.isIdle) return; main.runOneTask() }
            check(main.isIdle) { "Main did not quiesce after a finite platform frame" }
        }
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        private fun painted(view: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current.visibility != View.VISIBLE || current.alpha <= 0f) return false
                current = current.parent as? View
            }
            return view.width > 0 && view.height > 0
        }
    }
}

/** A real Activity/window/lifecycle with a test-owned storage/service boundary; no focus override. */
class OnboardingRecoveryHostActivity : ComponentActivity() {
    var storage: Context? = null
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { fontScale = 2f }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun getApplicationContext(): Context = storage ?: super.getApplicationContext()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        storage?.getSharedPreferences(name, mode) ?: super.getSharedPreferences(name, mode)
    override fun getNoBackupFilesDir(): File = storage?.noBackupFilesDir ?: super.getNoBackupFilesDir()
    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int) = false
    override fun startService(service: Intent): ComponentName? = storage?.startService(service)
        ?: super.startService(service)
}
