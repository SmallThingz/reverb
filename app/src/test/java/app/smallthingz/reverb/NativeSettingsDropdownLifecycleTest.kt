package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import android.os.Message
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.NativeSettingsScreenInput
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.SettingsSectionKey
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi", application = Application::class,
    shadows = [SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSettingsDropdownLifecycleTest {
    private val frameInterval = Duration.ofMillis(16)
    private lateinit var previousFrameDelay: Duration
    private var previousVsyncPaused = false

    @Before fun controlVsync() {
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        previousVsyncPaused = ShadowChoreographer.isPaused()
        // PAUSED looper mode alone allows the shadow to advance time when a frame requests another.
        ShadowChoreographer.setFrameDelay(frameInterval)
        ShadowChoreographer.setPaused(true)
    }

    @After fun restoreVsync() {
        ShadowChoreographer.setFrameDelay(previousFrameDelay)
        ShadowChoreographer.setPaused(previousVsyncPaused)
    }

    @Test fun parentWindowBlurKeepsRealAudioPopupMountedUntilDispatchReturns() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val window = activity.get()
        val decor = window.window.decorView
        val mainLooper = shadowOf(Looper.getMainLooper())
        ShadowSystemClock.advanceBy(frameInterval)
        drainDueTasks(mainLooper)
        assertTrue("Activity decor must attach before window focus dispatch", decor.isAttachedToWindow)
        activity.windowFocusChanged(true)
        val context = object : ContextWrapper(window) {
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int) = false
        }
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        // The fixture supplies the durable snapshot through owner hydration, as in the retained shell test.
        val scope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        window.setContentView(FrameLayout(context))
        val host = window.findViewById<FrameLayout>(android.R.id.content)
        val frames = AndroidUiFrameClock(Choreographer.getInstance())
        val animations = CoroutineScope(Dispatchers.Main.immediate + frames)
        val owner = NativeSettingsOwner(context, host, lifecycleOwner.lifecycle, scope, true,
            isPanelActive = { true }, onThemePreview = {}, releaseInputFocus = {},
            launchDirectory = { error("Unexpected directory launch") }, onBack = { error("Unexpected navigation") })
        val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false))
        val screen = NativeSettingsScreenContent(context, owner, NativeSettingsScreenInput(active = true), environment,
            lifecycleOwner, OnBackPressedDispatcher(), host, frames, animations, NoNestedScroll,
            parentInterested = { false }, changedNodes = {})

        try {
            val appContent = host.getChildAt(0) as FrameLayout
            appContent.addView(screen, FrameLayout.LayoutParams(-1, -1))
            runBlocking {
                val values = resolveNativeSettingsRestoredDraft(SettingsUiDraft.normalized(SettingsSnapshot(
                    oneShotRetentionTime = 300, loopingRetentionTime = 600, sampleFormat = PcmSampleFormat.PCM_16,
                )), listOf(InputRouteMode.AUTO))
                assertEquals(SettingsDraftHydrationResult.APPLIED, owner.draft.hydrate(
                    readDurable = { SettingsDraftBaseline(values, null) }, restoreFields = { it }))
            }
            screen.start()
            ShadowSystemClock.advanceBy(frameInterval)
            drainDueTasks(mainLooper)
            assertTrue(screen.inputAllowed)
            val restricted = owner.batteryOptimizationRestricted || owner.resumeNotificationBlocked || owner.restartAccessMissing
            val recording = SettingsSectionKey.visible(restricted).indexOf(SettingsSectionKey.RECORDING)
            checkNotNull(screen.listViewport).scrollToPosition(recording)
            decor.measure(exact(420), exact(900))
            decor.layout(0, 0, 420, 900)

            val label = context.getString(R.string.sample_format_label)
            val trigger = descendants(screen).filterIsInstance<NativeDropdown<*>>().single {
                descendants(it).filterIsInstance<ParagraphView>().any { text -> text.getText() == label }
            }
            assertTrue(trigger.isAttachedToWindow && trigger.isShown && trigger.width > 0 && trigger.height > 0)
            assertEquals(1, host.childCount)
            assertTrue(trigger.performClick())
            assertEquals("The actual audio field must mount its popup in the Activity content host", 2, host.childCount)
            val popup = host.getChildAt(1) as ViewGroup
            assertEquals(label, popup.accessibilityPaneTitle)
            assertTrue(popup.hasWindowFocus())
            val beforeBlur = owner.snapshot
            val revisionBeforeBlur = owner.state.editRevision
            val wheel = popup.getChildAt(1)
            val session = field(wheel, "this\$0")
            val retirement = field(session, "retireAfterDetach") as Runnable
            val handler = checkNotNull(trigger.handler)
            assertFalse(handler.hasCallbacks(retirement))
            assertTrue(wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertEquals(beforeBlur, owner.snapshot) // The browsed choice is still pending on the paused looper.

            host.dispatchWindowFocusChanged(false)
            assertSame("Focus dispatch cannot remove a child from the host's traversal", popup, host.getChildAt(1))
            assertEquals("Blur must revoke the session before the queued removal", true, field(session, "retiring"))
            assertTrue("Blur must post the session's removal", handler.hasCallbacks(retirement))
            drainDueTasks(mainLooper)
            if (popup.isAttachedToWindow) {
                assertTrue("The retirement must still be queued", handler.hasCallbacks(retirement))
                val queueHead = field(Looper.myQueue(), "mMessages") as Message
                assertNull("A traversal barrier must head the queue if due retirement cannot run", queueHead.target)
                ShadowSystemClock.advanceBy(frameInterval)
                drainDueTasks(mainLooper)
            }
            assertFalse("The popup must detach after its queued retirement and any traversal frame", popup.isAttachedToWindow)
            assertFalse(handler.hasCallbacks(retirement))
            assertEquals(1, host.childCount)
            assertSame(appContent, host.getChildAt(0))
            repeat(25) {
                ShadowSystemClock.advanceBy(frameInterval)
                drainDueTasks(mainLooper)
            }
            assertEquals(beforeBlur, owner.snapshot)
            assertEquals(revisionBeforeBlur, owner.state.editRevision)
        } finally {
            try {
                screen.close()
            } finally {
                animations.cancel()
                scope.cancel()
                activity.pause().stop().destroy()
            }
        }
    }

    private fun field(owner: Any, name: String): Any =
        checkNotNull(owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner))

    private fun drainDueTasks(looper: ShadowLooper) {
        // Run actual posted messages without letting a self-reposting callback hang the test.
        repeat(64) {
            if (looper.isIdle) return
            looper.runOneTask()
        }
        assertTrue("Main looper still has due work after 64 messages", looper.isIdle)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private object NoNestedScroll : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }
}
