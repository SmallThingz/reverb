package app.smallthingz.reverb.nativeui

import android.app.Application
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.components.NativeSlider
import smallthingz.ui.components.NativeSliderValueMode
import smallthingz.ui.controls.ControlPalette

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeSliderContinuousContractTest {
    private fun slider() = NativeSlider(
        RuntimeEnvironment.getApplication(),
        ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
            0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt()),
        valueMode = NativeSliderValueMode.CONTINUOUS,
    ).apply { min = 0; max = 10; bindContinuousValue(5.25f) }

    @Test fun authoritativeFractionalRebindDuringHeldKeySupersedesUserCommit() {
        val slider = slider()
        val changes = mutableListOf<Float>()
        val finishes = mutableListOf<Pair<Float, Boolean>>()
        val commits = mutableListOf<Float>()
        slider.onContinuousValueChanged = { changes += it }
        slider.onContinuousInteractionFinished = { value, cancelled -> finishes += value to cancelled }
        slider.onContinuousValueCommitted = { commits += it }
        val down = KeyEvent(100L, 100L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        val up = KeyEvent(100L, 110L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0)

        assertTrue(slider.dispatchKeyEvent(down))
        assertEquals(5.35f, slider.continuousValue, 0.0001f)
        assertTrue(slider.isUserInteractionActive)
        slider.bindContinuousValue(5.75f) // Same integer mirror, different authoritative fraction.
        assertTrue(slider.dispatchKeyEvent(up))

        assertEquals(5.75f, slider.continuousValue, 0.0001f)
        assertEquals(listOf(5.35f), changes)
        assertEquals(listOf(5.75f to false), finishes)
        assertTrue(commits.isEmpty())
        assertFalse(slider.isUserInteractionActive)
    }

    @Test fun rejectedNonfiniteBindsPreserveHeldUserDraftAndCommit() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val slider = slider()
            val changes = mutableListOf<Float>()
            val finishes = mutableListOf<Pair<Float, Boolean>>()
            val commits = mutableListOf<Float>()
            val nativeChanges = mutableListOf<Pair<Int, Boolean>>()
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    nativeChanges += value to fromUser
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            slider.onContinuousValueChanged = { changes += it }
            slider.onContinuousInteractionFinished = { value, cancelled -> finishes += value to cancelled }
            slider.onContinuousValueCommitted = { commits += it }
            val down = KeyEvent(100L, 100L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            val up = KeyEvent(100L, 110L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0)

            assertTrue(slider.dispatchKeyEvent(down))
            val validDraft = slider.continuousValue
            val validMirror = slider.progress
            assertEquals(5.35f, validDraft, 0.0001f)
            assertEquals(5, validMirror)
            assertEquals(1, changes.size)
            val nativeBefore = nativeChanges.toList()
            assertTrue(slider.isUserInteractionActive)
            assertThrows(IllegalArgumentException::class.java) { slider.bindContinuousValue(invalid) }
            assertEquals(validDraft, slider.continuousValue, 0f)
            assertEquals(validMirror, slider.progress)
            assertTrue(slider.isUserInteractionActive)
            assertEquals(1, changes.size)
            assertEquals(nativeBefore, nativeChanges)
            assertTrue(finishes.isEmpty())
            assertTrue(commits.isEmpty())

            assertTrue(slider.dispatchKeyEvent(up))
            assertEquals(listOf(validDraft to false), finishes)
            assertEquals(listOf(validDraft), commits)
            assertFalse(slider.isUserInteractionActive)
        }
    }

    @Test fun accessibilityScrollUsesFractionalRangeAndStopsAtBounds() {
        val slider = slider()
        val commits = mutableListOf<Float>()
        slider.onContinuousValueCommitted = { commits += it }
        val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        assertTrue(slider.performAccessibilityAction(forward, null))
        assertEquals(5.75f, slider.continuousValue, 0.0001f)
        assertTrue(slider.performAccessibilityAction(backward, null))
        assertEquals(5.25f, slider.continuousValue, 0.0001f)
        slider.bindContinuousValue(10f)
        assertFalse(slider.performAccessibilityAction(forward, null))
        assertEquals(10f, slider.continuousValue, 0f)
        assertEquals(listOf(5.75f, 5.25f), commits)
    }
}
