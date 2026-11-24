package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import app.smallthingz.reverb.InlineFineSeekTarget
import app.smallthingz.reverb.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density
import smallthingz.ui.reverb.PlayerTrimButtons
import smallthingz.ui.reverb.PlayerTrimHeading

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerTrimButtonsTest {
    private fun state(revision: String = "recording-one") = LibraryPlayerUiState(revision, true, false,
        10_000, 1_000, true, false, 0, 8_000, InlineFineSeekTarget.TRIM_START, null,
        true, false, 0f, 1f)

    @Test fun appearanceSuppliesExpandedPlayerWaveformAndHeadingGeometry() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val frames = AndroidUiFrameClock(Choreographer.getInstance())
        val scope = CoroutineScope(Dispatchers.Main.immediate + frames)
        val palette = ReverbPalette.fallback(false)
        val defaults = NativeUiAppearance.Default
        val decorations = defaults.listDecorations
        val alternate = defaults.copy(listDecorations = decorations.copy(recordingPlayer = decorations.recordingPlayer.copy(
            sideInsetDp = 21f, waveformHeightDp = 96f, controlGapDp = 6f, headingGlyphDp = 22f, headingGlyphGapDp = 10f,
        )))
        fun place(view: View) {
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        try {
            for (appearance in listOf(defaults, alternate)) {
                val changed = appearance === alternate
                val environment = ReverbViewEnvironment.withTestAppearance(Density(1f), createFontFamilyResolver(context), palette,
                    appearance = appearance)
                val fineSeek = createReverbSpringFineSeekView(context, SpringFineSeekEnvironment(
                    palette, environment.textDensity, ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
                    frames, scope, root, { false }), appearance.springFineSeek)
                val player = ReverbLibraryPlayerView(context, environment, fineSeek, root, frames, scope) { 0f }
                try {
                    place(player)
                    val wave = player.waveformInput.view
                    val times = player.startLabel.parent as View
                    val inset = if (changed) 21 else 14
                    assertEquals(inset, wave.left)
                    assertEquals(360 - inset, wave.right)
                    assertEquals(if (changed) 96 else 128, wave.height)
                    assertEquals(if (changed) 6 else 2, times.top - wave.bottom)

                    val heading = PlayerTrimHeading(context, environment, reverbPlayerLabels.trimRecording)
                    heading.bind(environment, state())
                    place(heading)
                    val glyph = heading.getChildAt(0)
                    assertEquals(if (changed) 22 else 17, glyph.width)
                    assertEquals(if (changed) 10 else 7, heading.title.left - glyph.right)
                } finally { player.release() }
            }
        } finally { scope.cancel() }
    }

    @Test fun narrowLargeTextKeepsBothActionsReadableAndMirrorsTheirPlacement() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val frames = AndroidUiFrameClock(Choreographer.getInstance())
        val scope = CoroutineScope(Dispatchers.Main.immediate + frames)
        val environment = ReverbViewEnvironment(Density(1f, 2f), createFontFamilyResolver(activity), ReverbPalette.fallback(false))
        val row = PlayerTrimButtons(activity, environment, root, frames, scope, reverbPlayerLabels) { 0f }
        root.addView(row, FrameLayout.LayoutParams(-1, -2))
        fun buttons(view: View): List<NativeMaterialButtonView> = when (view) {
            is NativeMaterialButtonView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun place(direction: Int): List<Rect> {
            row.layoutDirection = direction
            root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 200, 800)
            return buttons(row).map { button ->
                assertTrue("action retains 48dp touch width", button.width >= 48)
                assertTrue("action retains 48dp touch height", button.height >= 48)
                assertTrue("action label keeps usable width", button.label.measuredWidth > 0)
                assertTrue("action label keeps usable height", button.label.measuredHeight > 0)
                val paragraph = requireNotNull(button.label.currentParagraph)
                assertFalse("action label is complete", paragraph.didExceedMaxLines)
                assertTrue(paragraph.height <= button.label.measuredHeight + 1f)
                Rect(0, 0, button.width, button.height).also { row.offsetDescendantRectToMyCoords(button, it) }
            }
        }
        try {
            row.bind(environment, state(), LibraryPlayerActions({ _, _ -> }, { _, _ -> }, {}, {},
                { _, _ -> false }, {}, {}))
            val ltr = place(View.LAYOUT_DIRECTION_LTR)
            assertEquals(2, ltr.size)
            assertFalse("actions cannot overlap", Rect.intersects(ltr[0], ltr[1]))
            ltr.forEach { assertTrue(it.left >= 0 && it.right <= row.width && it.top >= 0 && it.bottom <= row.height) }
            val rtl = place(View.LAYOUT_DIRECTION_RTL)
            ltr.zip(rtl).forEach { (before, after) ->
                assertEquals(row.width - before.right, after.left)
                assertEquals(row.width - before.left, after.right)
                assertEquals(before.top, after.top)
                assertEquals(before.bottom, after.bottom)
            }
        } finally {
            row.close()
            scope.cancel()
            owner.pause().stop().destroy()
        }
    }

    @Test fun livePositionBindingsKeepInputButRecordingRevisionAndBusyStateRevokeIt() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val frames = AndroidUiFrameClock(Choreographer.getInstance())
        val scope = CoroutineScope(Dispatchers.Main.immediate + frames)
        val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(activity), ReverbPalette.fallback(false))
        val row = PlayerTrimButtons(activity, environment, root, frames, scope, reverbPlayerLabels) { 0f }
        root.addView(row, FrameLayout.LayoutParams(-1, -2))
        val events = mutableListOf<String>()
        fun actions(tag: String) = LibraryPlayerActions({ _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> false },
            { events += "$tag:cancel" }, { events += "$tag:save" })
        fun buttons(view: View): List<NativeMaterialButtonView> = when (view) {
            is NativeMaterialButtonView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun place() {
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
        }
        fun key(button: View, action: Int): Boolean = KeyEvent(action, KeyEvent.KEYCODE_ENTER).let {
            if (action == KeyEvent.ACTION_DOWN) button.onKeyDown(it.keyCode, it) else button.onKeyUp(it.keyCode, it)
        }
        val geometryOwners = mutableListOf<AutoCloseable>()
        try {
            row.bind(environment, state(), actions("initial"))
            place()
            val initial = buttons(row)
            assertEquals(2, initial.size)
            val cancel = initial[0]
            val save = initial[1]
            assertEquals(activity.getString(R.string.cancel), requireNotNull(cancel.label.createAccessibilityNodeInfo()).text.toString())
            assertEquals(activity.getString(R.string.trim_save_copy), requireNotNull(save.label.createAccessibilityNodeInfo()).text.toString())
            val geometry = NativePlainTextViewGeometry(root, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })
            initial.forEach { button ->
                geometryOwners += button.mergedRowAccessibility.bindGeometry(geometry) { true }
                assertNotNull(button.createAccessibilityNodeInfo())
            }
            assertTrue(save.requestFocus())
            assertTrue(key(save, KeyEvent.ACTION_DOWN))
            repeat(20) { position -> row.bind(environment, state().copy(positionMillis = position), actions("latest")) }
            assertEquals(initial, buttons(row))
            assertTrue(save.isFocused)
            assertTrue(key(save, KeyEvent.ACTION_UP))
            assertEquals(listOf("latest:save"), events)
            assertTrue(key(save, KeyEvent.ACTION_DOWN))
            row.bind(environment, state("recording-two"), actions("replacement"))
            key(save, KeyEvent.ACTION_UP)
            assertEquals(listOf("latest:save"), events)
            assertTrue(key(save, KeyEvent.ACTION_DOWN))
            assertTrue(key(save, KeyEvent.ACTION_UP))
            assertEquals(listOf("latest:save", "replacement:save"), events)
            row.bind(environment, state("recording-two").copy(trimSaving = true), actions("busy"))
            assertFalse(save.performClick())
            assertFalse(cancel.performClick())
            assertFalse(save.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(activity.getString(R.string.trim_saving), requireNotNull(save.label.createAccessibilityNodeInfo()).text.toString())
            row.bind(environment, state("recording-two").copy(trimEndMillis = 0), actions("empty"))
            assertFalse(save.performClick())
            assertTrue(cancel.performClick())
            assertEquals("empty:cancel", events.last())
            row.bind(environment, state("recording-two"), actions("ready"))
            assertTrue(save.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals("ready:save", events.last())
            place()
            val saveBounds = Rect(0, 0, save.width, save.height).also {
                row.offsetDescendantRectToMyCoords(save, it)
            }
            val beforeTouch = events.size
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(100, 100 + action.toLong(), action,
                    saveBounds.exactCenterX(), saveBounds.exactCenterY(), 0)
                try { assertTrue("actual row dispatch reaches Save", row.dispatchTouchEvent(event)) }
                finally { event.recycle() }
            }
            assertEquals(beforeTouch + 1, events.size)
            assertEquals("ready:save", events.last())
            row.close()
            assertFalse(save.performClick())
            assertFalse(cancel.performClick())
        } finally {
            row.close()
            geometryOwners.forEach { it.close() }
            scope.cancel()
            owner.pause().stop().destroy()
        }
    }
}
