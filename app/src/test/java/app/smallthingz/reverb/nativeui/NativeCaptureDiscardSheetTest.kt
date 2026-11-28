package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import app.smallthingz.reverb.R
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.reverb.ReverbEnvironment
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeCaptureDiscardSheetTest {
    private lateinit var previousFrameDelay: Duration
    private var previousVsyncPaused = false

    @Before fun finiteFrameClock() {
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        previousVsyncPaused = ShadowChoreographer.isPaused()
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }

    @After fun restoreFrameClock() {
        ShadowChoreographer.setFrameDelay(previousFrameDelay)
        ShadowChoreographer.setPaused(previousVsyncPaused)
    }

    @Test fun committedBackRetiresDiscardFooterBeforeExitCompletes() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val anchor = FrameLayout(activity).apply { setViewTreeLifecycleOwner(lifecycleOwner) }
        activity.setContentView(anchor)
        val environment = ReverbEnvironment(
            Density(1f, 1f), createFontFamilyResolver(activity), NativeColorScheme.fallback(false),
        )
        val main = shadowOf(Looper.getMainLooper())
        val clearLabel = activity.getString(R.string.clear_buffer)
        var sheet: CaptureTextSheet? = null
        var cancellations = 0
        var confirmations = 0

        fun advanceFrames(millis: Long) {
            // Native vsync is clock-driven: one large paused-clock jump can coalesce frames.
            // Keep the original time budget while delivering the actual animation frames.
            main.idle()
            var remaining = millis
            while (remaining > 0) {
                val step = minOf(16L, remaining)
                main.idleFor(Duration.ofMillis(step))
                remaining -= step
            }
        }

        fun show(title: Int): NativeActionSheet {
            val current = createDiscardBufferSheet(
                anchor, environment, activity.getString(title), activity.getString(R.string.clear_buffer_confirmation),
                onDismiss = { cancellations++; sheet?.close() },
                onConfirm = { confirmations++; sheet?.close() },
            )
            sheet = current
            current.show()
            advanceFrames(500)
            return ReflectionHelpers.getField(current, "dialog")
        }

        try {
            for ((index, title) in listOf(R.string.clear_one_shot_title, R.string.clear_loop_title).withIndex()) {
                val dialog = show(title)
                val confirm = descendants(dialog.contentView).filterIsInstance<NativeMaterialButtonView>()
                    .single { it.label.getText() == clearLabel }
                assertTrue(confirm.isAttachedToWindow && confirm.isShown && confirm.isEnabled)
                assertTrue(dialog.acceptsContentActions)

                // No animation frame runs between committed Back and these retained footer actions.
                dialog.onBackPressedDispatcher.onBackPressed()
                assertTrue(dialog.isShowing)
                assertFalse(dialog.acceptsContentActions)
                assertFalse(confirm.performClick())
                confirm.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                assertEquals(index, confirmations)
                assertEquals(index, cancellations)

                advanceFrames(500)
                assertEquals(index, confirmations)
                assertEquals(index + 1, cancellations)
                assertFalse(dialog.isShowing)

                val fresh = show(title)
                val activeConfirm = descendants(fresh.contentView).filterIsInstance<NativeMaterialButtonView>()
                    .single { it.label.getText() == clearLabel }
                assertTrue(activeConfirm.performClick())
                assertFalse(activeConfirm.performClick())
                assertEquals(index + 1, confirmations)
                assertEquals(index + 1, cancellations)
            }
        } finally {
            captureReleaseAll(listOf({ sheet?.close() }, { controller.pause().stop().destroy() }))
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
