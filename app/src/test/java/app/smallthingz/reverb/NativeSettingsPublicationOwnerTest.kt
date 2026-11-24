package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Environment
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment
import smallthingz.ui.components.NativeDurationWheelView
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.coroutines.CoroutineContext

/** Actual retained owner -> Recording section -> native wheel -> owner publication wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SettingsPublicationEnvironmentShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeSettingsPublicationOwnerTest {
    @Before fun providePlatformStoragePermissionBoundary() {
        SettingsPublicationEnvironmentShadow.queries = 0
        assertFalse(Environment.isExternalStorageManager())
        assertEquals(1, SettingsPublicationEnvironmentShadow.queries)
    }

    @Test fun discreteActionsAcknowledgeBeforeTheActualOwnerRebindsTheirWheel() = Fixture().use { f ->
        val wheel = f.wheel()
        repeat(6) { step ->
            f.published.clear()
            assertTrue(f.setProgress(wheel, 31 + step))
            assertEquals(31 + step, f.owner.snapshot.quickSaveSeconds)
            assertEquals(listOf(31 + step), f.published)
            assertSame(wheel, f.wheel())
        }
        f.published.clear()
        wheel.onInteractionStart?.invoke()
        wheel.onDurationChanged?.invoke(45)
        assertTrue(f.owner.notificationDurationInteraction.pending)
        wheel.onInteractionEnd?.invoke()
        assertFalse(f.owner.notificationDurationInteraction.pending)
        assertEquals(45, f.owner.snapshot.quickSaveSeconds)
        assertEquals(45, f.published.last())
        assertTrue(f.setProgress(wheel, 46))
    }

    @Test fun hideReopenRevokesHeldGestureAndRetiresServiceAndSectionOwners() = Fixture().use { f ->
        val wheel = f.wheel()
        repeat(5) {
            wheel.onInteractionStart?.invoke()
            wheel.onDurationChanged?.invoke(90)
            f.panelActive = false
            f.owner.setActive(false)
            assertEquals(0, f.context.connections.size)
            assertFalse(f.setProgress(wheel, 80))
            f.panelActive = true
            f.owner.setActive(true)
            f.hydrate()
            assertEquals(1, f.context.connections.size)
            // A terminal from the old interaction must not acquire the new activation's authority.
            wheel.onInteractionEnd?.invoke()
            assertFalse(f.owner.notificationDurationInteraction.pending)
            assertEquals(30, f.owner.snapshot.quickSaveSeconds)
            assertTrue(f.setProgress(wheel, 31))
        }
        f.retire()
        val value = f.owner.snapshot.quickSaveSeconds
        val publications = f.published.size
        assertFalse(f.setProgress(wheel, 99))
        wheel.onInteractionStart?.invoke()
        wheel.onDurationChanged?.invoke(99)
        wheel.onInteractionEnd?.invoke()
        f.owner.childInteractionChanged()
        assertEquals(value, f.owner.snapshot.quickSaveSeconds)
        assertEquals(publications, f.published.size)
        assertEquals(0, f.lifecycle.observerCount)
        assertEquals(0, f.context.connections.size)
        assertEquals(f.context.binds, f.context.unbinds)
    }

    @Test fun rendererFailureAfterCommitDoesNotLeaveOwnerPublicationGateHeld() = Fixture().use { f ->
        val expected = IllegalStateException("fixture renderer failure")
        f.renderFailure = expected
        assertSame(expected, assertThrows(IllegalStateException::class.java) { f.setProgress(f.wheel(), 31) })
        assertEquals(31, f.owner.snapshot.quickSaveSeconds)
        assertTrue(f.setProgress(f.wheel(), 32))
        assertEquals(32, f.published.last())
        assertEquals(0, NativeSettingsOwner::class.java.getDeclaredField("draftReconciliationDepth")
            .apply { isAccessible = true }.getInt(f.owner))
    }

    private class CountingContext(base: Context) : ContextWrapper(base) {
        val connections = mutableSetOf<ServiceConnection>()
        var binds = 0
        var unbinds = 0
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(0, flags) // Settings must never auto-create the recording Service.
            assertTrue(connections.add(connection))
            binds++
            return true
        }
        override fun unbindService(connection: ServiceConnection) {
            assertTrue(connections.remove(connection))
            unbinds++
        }
    }

    private class WindowRoot(context: Context) : FrameLayout(context) {
        override fun hasWindowFocus() = true // Only focus is simulated; attachment is the real window.
    }

    private class Fixture : LifecycleOwner, AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup()
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val context = CountingContext(activity.get())
        val root = WindowRoot(context).also { activity.get().setContentView(it) }
        // Hydration uses an explicit immutable fixture read below. Background storage scans and
        // hydration retries are parked; this is not IO/cancellation or physical Service coverage.
        private val scope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        private val animationScope = CoroutineScope(Dispatchers.Main.immediate + AndroidUiFrameClock(Choreographer.getInstance()))
        private val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false))
        var panelActive = true
        val published = mutableListOf<Int>()
        var renderFailure: RuntimeException? = null
        private var retired = false
        val owner = NativeSettingsOwner(context, root, lifecycle, scope, true,
            isPanelActive = { panelActive }, onThemePreview = {}, releaseInputFocus = {},
            launchDirectory = { error("Unexpected directory launch") }, onBack = {})
        private lateinit var section: NativeSettingsSection
        init {
            hydrate()
            val sections = NativeSettingsSections(context, owner, { environment }, lifecycle, { root },
                animationScope, { false }, { null }, { _, _ -> }, {})
            section = sections.create(SettingsSectionKey.RECORDING)
            root.addView(section.view)
            owner.start {
                section.bind()
                published.add(owner.snapshot.quickSaveSeconds)
                renderFailure?.let { renderFailure = null; throw it }
            }
            assertTrue(root.isAttachedToWindow)
            assertTrue(SettingsPublicationEnvironmentShadow.queries > 1) // Actual owner queried the platform boundary.
            assertNotNull(owner.notificationEditRevision())
            published.clear()
        }
        fun hydrate() = runBlocking {
            val values = resolveNativeSettingsRestoredDraft(SettingsUiDraft.normalized(SettingsSnapshot(
                oneShotRetentionTime = 300, loopingRetentionTime = 600,
                quickSaveEnabled = true, quickSaveSeconds = 30,
            )), listOf(InputRouteMode.AUTO))
            assertEquals(SettingsDraftHydrationResult.APPLIED, owner.draft.hydrate(
                readDurable = { SettingsDraftBaseline(values, null) }, restoreFields = { it }))
        }
        fun wheel(): NativeDurationWheelView = descendants(section.view).filterIsInstance<NativeDurationWheelView>().single()
        fun setProgress(wheel: NativeDurationWheelView, seconds: Int) = wheel.performAccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, seconds.toFloat()) })
        fun retire() {
            if (retired) return
            retired = true
            owner.close()
            section.close()
        }
        override fun close() {
            try { retire() } finally {
                scope.cancel()
                animationScope.cancel()
                activity.pause().stop().destroy()
            }
        }
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}

/** Only the unrelated all-files app-op is fixed. Keep normal Robolectric Environment behavior. */
@Implements(Environment::class)
class SettingsPublicationEnvironmentShadow : ShadowEnvironment() {
    companion object {
        var queries = 0
        @JvmStatic @Implementation fun isExternalStorageManager(): Boolean {
            queries++
            return false
        }
    }
}
