package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ServiceStateOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeRangeEndpointActionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun visibleCustomActionsWorkWhileWindowInputFocusIsReturning() = withEndpoint {
        assertFalse(root.hasWindowFocus())
        val info = checkNotNull(endpoint.createAccessibilityNodeInfo())
        assertTrue(info.isVisibleToUser)
        assertTrue(earlierId > 0 && laterId > 0 && earlierId != laterId)
        assertEquals(1, info.actionList.count { it.id == earlierId })
        assertEquals(1, info.actionList.count { it.id == laterId })

        assertCustomAction(earlierId)
        assertEquals(1f, state.startSeconds, 0f)
        assertEquals(2f, state.endSeconds, 0f)
        assertEquals(RangeEditTarget.END, state.lastTarget)
        assertCustomAction(laterId)
        assertEquals(3f, state.endSeconds, 0f)
        assertEquals(2.0, state.selectionDurationExactSeconds, 0.0)
        assertSame(snapshot, state.snapshot)
        assertFalse(state.isPlaying || state.isScrubbing || state.selectionDurationEditing)
        assertNull(state.playbackAnchor)
    }

    @Test fun missingHiddenClippedOrDisabledEndpointCannotUseAStaleAction() = withEndpoint {
        val visible = node
        for (unavailable in listOf(NativeSourceAccessibilityNode.absent(), visible.copy(hidden = true),
            visible.copy(visibleToUser = false))) {
            node = unavailable
            assertRejected()
        }
        node = visible
        root.visibility = View.INVISIBLE
        assertRejected()
        root.visibility = View.VISIBLE
        endpoint.isEnabled = false
        assertRejected()
    }

    @Test fun lifecycleAndCurrentGestureRetainAdmission() = withEndpoint {
        lifecycle.currentState = Lifecycle.State.STARTED
        assertRejected()
        lifecycle.currentState = Lifecycle.State.RESUMED
        interactive = false
        assertRejected()
        interactive = true

        state.beginSelectionDurationEdit()
        val wheel = state.selectionDurationInteraction
        assertRejected()
        assertSame(wheel, state.selectionDurationInteraction)
        state.endSelectionDurationEdit()
        assertCustomAction(earlierId)
        assertEquals(2f, state.endSeconds, 0f)
    }

    @Test fun detachedEndpointAndRetiredDelegateCannotEditTheLiveRange() = withEndpoint {
        val retired = checkNotNull(endpoint.accessibilityDelegate)
        root.removeView(endpoint)
        assertFalse(endpoint.isAttachedToWindow)
        assertRejected()
        actions.close()
        remountEndpoint()
        actions = bind()
        assertTrue(endpoint.isAttachedToWindow)
        assertFalse(retired.performAccessibilityAction(endpoint, earlierId, null))
        assertUnedited()
        assertCustomAction(earlierId)
        assertEquals(2f, state.endSeconds, 0f)
    }

    @Test fun focusSelectionPhysicalKeysAndStandardProgressKeepTheirWindowFocusGuard() = withEndpoint {
        endpoint.isFocusableInTouchMode = true
        assertTrue(endpoint.requestFocus())
        assertUnedited()
        endpoint.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        endpoint.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        assertUnedited()
        val progress = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2f) }
        assertFalse(endpoint.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, progress))
        assertUnedited()

        root.inputFocused = true
        endpoint.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        endpoint.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(2f, state.endSeconds, 0f)
    }

    private fun withEndpoint(check: Fixture.() -> Unit) {
        val directory = temporary.newFolder()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        val windowVisibilityOption = "robolectric.areWindowsMarkedVisible"
        val previousWindowVisibility = System.getProperty(windowVisibilityOption)
        ServiceStateOsShadow.fixtureRoot = directory.toPath().toAbsolutePath().normalize()
        try {
            // Robolectric's WindowManager otherwise omits ADD_FLAG_APP_VISIBLE at registration.
            // The native ViewRoot consumes this flag before its first visibility traversal.
            System.setProperty(windowVisibilityOption, "true")
            PersistentAudioChunkStore(directory, overwriteOldest = false, directorySync = {}).use { store ->
                val rate = 8_000
                val samples = ByteArray(rate * 4 * 2)
                store.configure(RetentionMode.SIZE, 1_048_576L, rate, 1, PcmSampleFormat.PCM_16)
                assertEquals(samples.size, store.append(samples, 0, samples.size))
                store.sealActiveChunk()
                ReverbService.TimelineSnapshot(checkNotNull(store.acquireRange(0.0, 4.0))).use { snapshot ->
                    assertEquals(4.0, snapshot.durationSeconds, 0.0)
                    val controller = Robolectric.buildActivity(Activity::class.java).setup()
                    try {
                        Fixture(controller.get(), snapshot).use(check)
                    } finally {
                        controller.pause().stop().destroy()
                    }
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
            if (previousWindowVisibility == null) System.clearProperty(windowVisibilityOption)
            else System.setProperty(windowVisibilityOption, previousWindowVisibility)
        }
    }

    // Only input focus is simulated. The actual endpoint View and delegate are attached to one window.
    private class WindowRoot(context: Context) : FrameLayout(context) {
        var inputFocused = false
        override fun hasWindowFocus(): Boolean = inputFocused
    }

    private class Fixture(activity: Activity, val snapshot: ReverbService.TimelineSnapshot) : AutoCloseable, LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val root = WindowRoot(activity)
        val endpoint = NativeRangeEndpointView(activity)
        private val platformControl = Button(activity)
        private val textWindow = PlainTextAccessibilityWindowScope(root)
        val state = RangeExportEditorState(4f).apply {
            attachSnapshot(this@Fixture.snapshot)
            setTarget(RangeEditTarget.START, 1f)
            setTarget(RangeEditTarget.END, 3f)
            selectTarget(RangeEditTarget.START)
        }
        var interactive = true
        var node = NativeSourceAccessibilityNode(true, false, true, Rect(0, 0, 56, 56))
        private var sourceReads = 0
        private val initialNativeVisibility: String
        lateinit var actions: NativeRangeEndpointActions
        val earlierId = smallthingz.ui.R.id.accessibility_custom_action_0
        val laterId = smallthingz.ui.R.id.accessibility_custom_action_1

        init {
            root.addView(endpoint, FrameLayout.LayoutParams(56, 56))
            root.addView(platformControl, FrameLayout.LayoutParams(56, 56).apply { leftMargin = 72 })
            activity.setContentView(root)
            initialNativeVisibility = nativeVisibility(endpoint)
            traverseWindow()
            assertNativeVisibility(platformControl)
            assertNativeVisibility(endpoint)
            assertTrue(root.isAttachedToWindow && root.isShown)
            assertNotNull(root.windowToken)
            assertSame(root.windowToken, endpoint.windowToken)
            actions = bind()
        }

        fun bind() = NativeRangeEndpointActions(endpoint, root, state, RangeEditTarget.END,
            lifecycle, { interactive }, textWindow, { sourceReads++; node })

        fun remountEndpoint() {
            root.addView(endpoint, FrameLayout.LayoutParams(56, 56))
            traverseWindow()
        }

        private fun traverseWindow() {
            // Let WindowManager deliver attachment and layout through two scheduled display frames.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        }

        private fun nativeVisibility(view: View): String {
            val bounds = Rect()
            val visible = view.getGlobalVisibleRect(bounds)
            return "${view.javaClass.simpleName} attached=${view.isAttachedToWindow} shown=${view.isShown} " +
                "enabled=${view.isEnabled} windowVisibility=${view.windowVisibility} " +
                "globalVisible=$visible bounds=$bounds"
        }

        private fun assertNativeVisibility(view: View) {
            val bounds = Rect()
            val details = "${nativeVisibility(view)}; before traversal $initialNativeVisibility"
            assertTrue(details, view.isAttachedToWindow && view.isShown && view.isEnabled)
            assertEquals(details, View.VISIBLE, view.windowVisibility)
            assertTrue(details, view.getGlobalVisibleRect(bounds) && !bounds.isEmpty)
        }

        fun assertCustomAction(action: Int) {
            assertNativeVisibility(endpoint)
            assertSame(root.windowToken, endpoint.windowToken)
            assertFalse(root.hasWindowFocus())
            assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState)
            assertTrue(interactive && state.durationActionAvailable && !state.selectionDurationInteraction.active)
            assertTrue(node.present && !node.hidden && node.visibleToUser)
            val readsBeforeDispatch = sourceReads
            val handled = endpoint.performAccessibilityAction(action, null)
            assertTrue("Custom action $action returned false after ${sourceReads - readsBeforeDispatch} " +
                "source-node reads; before traversal $initialNativeVisibility", handled)
        }

        fun assertRejected() {
            assertFalse(endpoint.performAccessibilityAction(earlierId, null))
            assertUnedited()
        }

        fun assertUnedited() {
            assertEquals(1f, state.startSeconds, 0f)
            assertEquals(3f, state.endSeconds, 0f)
            assertEquals(RangeEditTarget.START, state.lastTarget)
            assertSame(snapshot, state.snapshot)
            assertNull(state.playbackAnchor)
        }

        override fun close() {
            try { actions.close() } finally { state.close() }
        }
    }
}
