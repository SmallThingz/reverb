package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbRecordingGroupGeometryTest {
    @Test fun recordingBorderRetainsRawDensityInsteadOfSettingsCeil() {
        val recording = reverbRecordingGroupGeometry(300f, 180f, 2.625f, ReverbRecordingGroupEdges(true, true))
        val settings = reverbGroupGeometry(300f, 180f, 2.625f)
        assertEquals(2.625f, recording.strokeWidth, 0f)
        assertEquals(3f, settings.strokeWidth, 0f)
        assertEquals(1.3125f, recording.strokeInset, 0f)
        assertEquals(40.6875f, recording.strokeRadius, 0f)
    }

    @Test fun sectionEdgesRoundOnlyItsOuterRows() {
        val first = reverbRecordingGroupGeometry(300f, 180f, 2.625f, ReverbRecordingGroupEdges(true, false))
        val middle = reverbRecordingGroupGeometry(300f, 180f, 2.625f, ReverbRecordingGroupEdges(false, false))
        val last = reverbRecordingGroupGeometry(300f, 180f, 2.625f, ReverbRecordingGroupEdges(false, true))
        assertEquals(42f, first.topRadius, 0f)
        assertEquals(0f, first.bottomRadius, 0f)
        assertEquals(0f, middle.topRadius, 0f)
        assertEquals(0f, middle.bottomRadius, 0f)
        assertEquals(0f, last.topRadius, 0f)
        assertEquals(42f, last.bottomRadius, 0f)
    }

    @Test fun narrowFirstRowNeedsPathHitTestingButItsOutlineHasItsOwnRadius() {
        val first = reverbRecordingGroupGeometry(20f, 100f, 1f, ReverbRecordingGroupEdges(true, false))
        assertEquals(16f, first.topRadius, 0f)
        assertFalse(first.cornersFit)
        assertEquals(9.5f, first.strokeRadius, 0f)
        val singleton = reverbRecordingGroupGeometry(20f, 100f, 1f, ReverbRecordingGroupEdges(true, true))
        assertEquals(10f, singleton.topRadius, 0f)
        assertEquals(10f, singleton.bottomRadius, 0f)
        assertTrue(singleton.cornersFit)
    }

    @Test fun shortSingletonUsesSourceVerticalCornerNormalization() {
        val geometry = reverbRecordingGroupGeometry(300f, 20f, 2f, ReverbRecordingGroupEdges(true, true))
        assertEquals(10f, geometry.topRadius, 0f)
        assertEquals(10f, geometry.bottomRadius, 0f)
        assertEquals(9f, geometry.strokeRadius, 0f)
        assertTrue(geometry.cornersFit)
    }
}
