package app.smallthingz.reverb.nativeui

import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.NativeRangeExportPresentation
import app.smallthingz.reverb.RangeEditTarget
import app.smallthingz.reverb.RangeExportEditorState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeRangeTimelineAppearanceTest {
    @Test fun appearanceMovesWaveformHitTargetsAndBubblesWhileTimeUsesFloatGeometry() {
        val context = RuntimeEnvironment.getApplication()
        val resolver = createFontFamilyResolver(context)
        val defaults = NativeUiAppearance.Default
        val surfaces = defaults.groupSurfaces
        val alternate = defaults.copy(groupSurfaces = surfaces.copy(
            rangeTimeline = surfaces.rangeTimeline.copy(sideInsetDp = 13f, waveformTopDp = 33f,
                waveformHeightDp = 120f, endpointWidthDp = 37f),
            rangeTimeBubble = surfaces.rangeTimeBubble.copy(widthDp = 96f, heightDp = 32f, topDp = 5f),
        ))
        val editor = RangeExportEditorState(120f)
        editor.setTarget(RangeEditTarget.START, 30f)
        val empty = FloatArray(0)
        val presentation = NativeRangeExportPresentation(null, null, false, true, 0f, false, false, 120.0, 1f,
            ProgressiveWaveformData(empty, 0, empty, 0, detailAvailable = false),
            ProgressiveWaveformFrame(1f, 0f, 0f, 0f, .25f, 1f, false))
        try {
            fun timeline(appearance: NativeUiAppearance): ReverbRangeTimelineView {
                val environment = ReverbViewEnvironment(Density(1.3f), resolver, ReverbPalette.fallback(false),
                    appearance = appearance)
                return ReverbRangeTimelineView(context, environment) {}.also {
                    it.bind(editor, presentation)
                    it.measure(exact(300), exact(250))
                    it.layout(0, 0, 300, 250)
                }
            }
            val original = timeline(defaults)
            val changed = timeline(alternate)
            assertEquals(Rect(16, 55, 284, 245), original.waveformInput.bounds())
            assertEquals(Rect(17, 43, 283, 199), changed.waveformInput.bounds())
            assertEquals(Rect(54, 55, 111, 245), (original.endpointStart.parent as View).bounds())
            assertEquals(Rect(59, 43, 107, 199), (changed.endpointStart.parent as View).bounds())
            assertEquals(Rect(191, 4, 300, 40), endBubble(original).bounds())
            assertEquals(Rect(175, 7, 300, 49), endBubble(changed).bounds())
            assertEquals(268.8f, original.timelineWidthPixels(), .001f)
            assertEquals(266.2f, changed.timelineWidthPixels(), .001f)
            assertEquals(82.8f, original.markerCenterPixels(RangeEditTarget.START), .001f)
            assertEquals(83.45f, changed.markerCenterPixels(RangeEditTarget.START), .001f)
            assertEquals(30f, original.secondsForWaveformX(67.2f), .001f)
            assertEquals(30f, changed.secondsForWaveformX(66.55f), .001f)
        } finally { editor.close() }
    }

    private fun endBubble(timeline: ReverbRangeTimelineView): View = (timeline.getChildAt(1) as ViewGroup).getChildAt(3)
    private fun View.bounds() = Rect(left, top, right, bottom)
    private fun exact(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
}
