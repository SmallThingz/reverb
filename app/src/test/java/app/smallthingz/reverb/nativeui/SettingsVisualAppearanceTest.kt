package app.smallthingz.reverb.nativeui

import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.SilenceGateConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.components.NativeArcValueDial
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualAppearanceTest {
    @Test fun calibrationAndRetentionUseTheWindowRecipeWithTheirExistingEdits() {
        val context = RuntimeEnvironment.getApplication()
        val default = NativeUiAppearance.Default
        val alternate = default.copy(
            calibration = default.calibration.copy(dialWidthDp = 78f),
            editorVisuals = default.editorVisuals.copy(cursorWidthDp = 5f, selectionAlpha = 0x80),
        )
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                throw AssertionError("The unattached Settings fixture requested a frame")
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + clock)
        try {
            for ((style, expectedWidth) in listOf(default to 104, alternate to 78)) {
                val environment = ReverbViewEnvironment(context, false, style).forSettings()
                val initial = SilenceGateConfig(enabled = true)
                val edits = ArrayList<SilenceGateConfig>()
                val actions = object : SettingsSilenceActions {
                    override fun configChanged(config: SilenceGateConfig) { edits += config }
                    override fun calibrate() = error("Dial adjustment started sampling")
                    override fun beginHeld(): Boolean = error("Dial adjustment began a hold")
                    override fun completeTap() = error("Dial adjustment completed a tap")
                    override fun finishHeld() = error("Dial adjustment finished a hold")
                    override fun cancel() = error("Dial adjustment cancelled sampling")
                }
                val section = SettingsSilenceView(context, SettingsSilenceState(initial), environment, { null }, scope, actions)
                try {
                    section.measure(exact(480), unspecified())
                    section.layout(0, 0, 480, section.measuredHeight)
                    val dial = descendants(section).filterIsInstance<NativeArcValueDial>().single()
                    assertEquals(expectedWidth, dial.measuredWidth)
                    assertEquals(expectedWidth, dial.width)
                    val arguments = Bundle().apply {
                        putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 8f)
                    }
                    assertTrue(dial.performAccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
                    assertEquals(listOf(initial.copy(clearanceDb = 8f)), edits)
                } finally {
                    section.close()
                }

                val editor = SettingsRetentionInputView(context, environment)
                try {
                    val thickness = style.editorVisuals.cursorWidthDp * environment.textDensity.density
                    assertEquals(floor(thickness).coerceAtLeast(1f).toInt(), editor.textCursorDrawable!!.intrinsicWidth)
                    assertEquals(style.editorVisuals.selectionColor(environment.palette.primary), editor.highlightColor)
                } finally {
                    editor.releaseCallbacks()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
