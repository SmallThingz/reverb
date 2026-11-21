package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import smallthingz.ui.interaction.view.NativeClickBinding
import smallthingz.ui.interaction.view.NativeClickEnvironment
import smallthingz.ui.interaction.view.NativeClickIndication
import smallthingz.ui.interaction.view.NativePressToken
import smallthingz.ui.interaction.view.NativeStateToken
import smallthingz.ui.interaction.view.ViewClickController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeClickKeyboardCancellationTest {
    @Test
    fun canceledUpCancelsItsPressAndRetiresTheKeyWithoutActivating() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val host = View(activity)
        activity.setContentView(host)
        val loop = shadowOf(Looper.getMainLooper())
        val indication = RecordingIndication()
        val controller = ViewClickController(host, object : NativeClickEnvironment {
            override fun admitDown(event: MotionEvent) = false
            override fun interestedParent(event: MotionEvent) = false
            override fun longPressOwnershipChanged(owns: Boolean) = Unit
            override val minimumTouchWidthPx = 0f
            override val minimumTouchHeightPx = 0f
        }, indication)
        var clicks = 0
        var longClicks = 0
        controller.attached()
        controller.bind(NativeClickBinding("keyboard action", true, { clicks++ }, { longClicks++ }))
        try {
            assertTrue(host.isAttachedToWindow)
            val ordinaryDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(controller.key(ordinaryDown))
            loop.idle()
            val ordinaryPress = indication.presses.single()
            assertTrue(controller.key(KeyEvent.changeAction(ordinaryDown, KeyEvent.ACTION_UP)))
            loop.idle()
            assertEquals(1, clicks)
            assertEquals(listOf(ordinaryPress), indication.releases)
            assertTrue(indication.cancels.isEmpty())

            val time = SystemClock.uptimeMillis()
            val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)
            assertTrue(controller.key(down))
            assertFalse(controller.key(KeyEvent.changeTimeRepeat(down, time, 1)))
            loop.idle()
            assertEquals(2, indication.presses.size)
            val canceledPress = indication.presses.last()
            assertNotSame(ordinaryPress, canceledPress)
            val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
            val canceledUp = KeyEvent.changeFlags(up, KeyEvent.FLAG_CANCELED)
            assertTrue(canceledUp.isCanceled)
            assertTrue(controller.key(canceledUp))
            assertFalse(controller.key(up))
            loop.idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
            assertEquals(1, clicks)
            assertEquals(0, longClicks)
            assertEquals(listOf(ordinaryPress), indication.releases)
            assertEquals(listOf(canceledPress), indication.cancels)

            val nextDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(controller.key(nextDown))
            assertTrue(controller.key(KeyEvent.changeAction(nextDown, KeyEvent.ACTION_UP)))
            loop.idle()
            assertEquals(2, clicks)
            assertEquals(3, indication.presses.size)
            assertEquals(listOf(ordinaryPress, indication.presses.last()), indication.releases)
            assertEquals(listOf(canceledPress), indication.cancels)
        } finally {
            controller.detached()
            owner.pause().stop().destroy()
        }
    }

    private class RecordingIndication : NativeClickIndication {
        val presses = mutableListOf<NativePressToken>()
        val releases = mutableListOf<NativePressToken>()
        val cancels = mutableListOf<NativePressToken>()

        override fun press(token: NativePressToken) { presses += token }
        override fun release(token: NativePressToken) { releases += token }
        override fun cancel(token: NativePressToken) { cancels += token }
        override fun stateEnter(token: NativeStateToken) = Unit
        override fun stateExit(token: NativeStateToken) = Unit
    }
}
