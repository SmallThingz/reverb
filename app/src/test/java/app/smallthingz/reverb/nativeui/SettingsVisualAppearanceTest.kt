package app.smallthingz.reverb.nativeui

import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.R
import app.smallthingz.reverb.SilenceCurveView
import app.smallthingz.reverb.SilenceGateConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.common.SegmentOption
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeMergedActionLayout
import smallthingz.ui.components.NativeQuickTileSectionLayout
import smallthingz.ui.reverb.settings.ReverbSettingsBackgroundWarningView
import smallthingz.ui.reverb.settings.ReverbSettingsStorageState
import smallthingz.ui.components.NativeArcValueDial
import smallthingz.ui.components.NativeSegmentedControl
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.reverb.settings.ReverbQuickTileEditor
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.floor
import kotlin.math.roundToInt

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
                val environment = ReverbViewEnvironment.withTestAppearance(context, false, style).forSettings()
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
                    val graph = descendants(section).filterIsInstance<SilenceCurveView>().single()
                    assertEquals(0, graph.top)
                    val discardedCaptions = setOf(context.getString(R.string.silence_calibration_heading),
                        context.getString(R.string.silence_calibration_hint))
                    assertFalse(descendants(section).filterIsInstance<ParagraphView>().any { it.getText() in discardedCaptions })
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

    @Test fun tileEditorPlacesTheSharedCapsuleAboveTheWheelAndBothActionsAtTheBottom() {
        val context = RuntimeEnvironment.getApplication()
        val environment = ReverbViewEnvironment(context, false)
        val options = listOf(SegmentOption(1, "One-shot"), SegmentOption(2, "Looping"))
        val wheel = View(context)
        val editor = object : ReverbQuickTileEditor<Int>(context, environment, options, 2,
            { null }, { error("Measurement requested a buffer change") }, { error("Unexpected Cancel") },
            { error("Unexpected Save") }) {
            init {
                replaceWheelView(wheel)
                bindInlineLayout(environment, options, 2, true, null, "Duration warning", null,
                    "Cancel", true, "Save", true)
            }
        }
        try {
            editor.measure(exact(360), unspecified())
            editor.layout(0, 0, editor.measuredWidth, editor.measuredHeight)
            val capsule = editor.getChildAt(0) as NativeSegmentedControl
            val actions = descendants(editor).filterIsInstance<SettingsCalibrationActionView>().toList()
            val cancel = actions.single { it.textView.getText() == "Cancel" }
            val save = actions.single { it.textView.getText() == "Save" }
            val inset = environment.appearance.quickTiles.editorInsetDp
            assertEquals(inset, capsule.top)
            assertTrue(capsule.bottom < wheel.top)
            fun editorBounds(view: View) = Rect(0, 0, view.width, view.height).apply {
                editor.offsetDescendantRectToMyCoords(view, this)
            }
            val cancelBounds = editorBounds(cancel)
            val saveBounds = editorBounds(save)
            assertTrue(wheel.bottom < minOf(cancelBounds.top, saveBounds.top))
            val footerBottomPadding = (NativeMergedActionLayout.ACTION_FOOTER_BOTTOM_PADDING_DP *
                environment.textDensity.density).roundToInt()
            assertEquals(editor.height - footerBottomPadding, maxOf(cancelBounds.bottom, saveBounds.bottom))
        } finally {
            editor.close()
        }
    }

    @Test fun sectionGuttersKeepDefaultsAndExplicitTileOverrideWithoutChangingStorageInnerPadding() {
        val context = RuntimeEnvironment.getApplication()
        val default = NativeUiAppearance.Default
        val alternate = default.copy(quickTiles = default.quickTiles.copy(sectionHorizontalInsetDp = 24))
        for (appearance in listOf(default, alternate)) {
            val environment = ReverbViewEnvironment.withTestAppearance(context, false, appearance).forSettings()
            val reject = { error("Section layout must not dispatch an action") }
            val storage = SettingsStorageSection(context,
                ReverbSettingsStorageState("Music/Reverb", false, false, false), environment,
                { null }, reject, reject, reject)
            val warning = ReverbSettingsBackgroundWarningView(context, environment, { null },
                true, false, false, reject, reject, reject)
            val tiles = object : NativeQuickTileSectionLayout(context, environment, "Tiles", "Loading",
                "Apply first", { null }, {}, reject), AutoCloseable {
                init {
                    bindListContent(listOf(View(context).apply { minimumHeight = 32 }),
                        false, null, "Retry", false)
                }
                override fun close() = closeRetry()
            }
            try {
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    for (section in listOf(storage, warning, tiles)) {
                        section.layoutDirection = direction
                        section.measure(exact(360), unspecified())
                        section.layout(0, 0, 360, section.measuredHeight)
                    }
                    val storageGroup = storage.getChildAt(1) as NativeGroupLayout
                    val warningGroup = warning.getChildAt(0) as NativeGroupLayout
                    val tileGroup = descendants(tiles).filterIsInstance<NativeGroupLayout>().single()
                    for (group in listOf(storageGroup, warningGroup)) {
                        assertEquals(16, group.left)
                        assertEquals(344, group.right)
                        assertEquals(328, group.width)
                    }
                    val tileInset = if (appearance === alternate) 24 else 16
                    assertEquals(tileInset, tileGroup.left)
                    assertEquals(360 - tileInset, tileGroup.right)
                    assertEquals(360 - 2 * tileInset, tileGroup.width)
                    val title = storage.getChildAt(0)
                    assertEquals(16, if (direction == View.LAYOUT_DIRECTION_RTL) storage.width - title.right else title.left)
                    val path = descendants(storageGroup).filterIsInstance<ParagraphView>()
                        .single { it.getText() == "Music/Reverb" }
                    val body = path.parent as ViewGroup
                    assertEquals("Inner path padding is a separate role", 16,
                        if (direction == View.LAYOUT_DIRECTION_RTL) body.width - path.right else path.left)
                }
            } finally {
                tiles.close()
                warning.close()
                storage.close()
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
