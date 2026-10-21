package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.RangeWaveformPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProgressiveWaveformGeometryTest {
    @Test
    fun zeroMorphIsCircleCrossSectionRegardlessOfPhaseAndSamples() {
        val data = waveform(
            coarse = floatArrayOf(1f, 0f, 1f, 0f, 1f),
            detail = floatArrayOf(0f, 1f, 0f, 1f, 0f),
        )
        val geometry = ProgressiveWaveformGeometry()
        assertEquals(5, update(geometry, data, morph = 0f, phase = 17f))
        val expected = floatArrayOf(0f, 43.30127f, 50f, 43.30127f, 0f)
        for (point in expected.indices) {
            assertEquals(expected[point], geometry.amplitudeAt(point), 0.00001f)
        }
    }

    @Test
    fun fullyBuiltSettledDetailOwnsEnvelopeOverCoarseAndWobble() {
        val data = waveform(
            coarse = FloatArray(5) { 1f },
            detail = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
        )
        val geometry = ProgressiveWaveformGeometry()
        assertEquals(5, update(geometry, data, phase = 123f))
        // The released envelope occupies 3.5% minimum to 47.5% maximum of total height.
        val expected = floatArrayOf(3.5f, 14.5f, 25.5f, 36.5f, 47.5f)
        for (point in expected.indices) {
            assertEquals(expected[point], geometry.amplitudeAt(point), 0.00001f)
        }
    }

    @Test
    fun unpublishedSuffixCannotAffectContourEvenWhenItContainsNaN() {
        val complete = floatArrayOf(0.1f, 0.8f, 0.3f, 0.6f, 0.5f)
        val poisoned = complete.copyOf().apply {
            for (index in 2 until size) this[index] = Float.NaN
        }
        val first = ProgressiveWaveformGeometry()
        val second = ProgressiveWaveformGeometry()
        val a = waveform(detail = complete, detailBuilt = 2)
        val b = waveform(detail = poisoned, detailBuilt = 2)
        val count = update(first, a)
        assertEquals(count, update(second, b))
        for (point in 0 until count) {
            assertFalse(second.amplitudeAt(point).isNaN())
            assertEquals(first.amplitudeAt(point), second.amplitudeAt(point), 0f)
        }
    }

    @Test
    fun sourceMutationAfterPublicationCannotChangeAnExistingSnapshot() {
        val coarse = FloatArray(5) { 0.2f }
        val detail = FloatArray(5) { 0.5f }
        val snapshot = waveform(coarse, detail)
        coarse.fill(Float.NaN)
        detail.fill(Float.NaN)
        val geometry = ProgressiveWaveformGeometry()
        assertEquals(5, update(geometry, snapshot))
        for (point in 0 until 5) {
            assertEquals(25.5f, geometry.amplitudeAt(point), 0.00001f)
        }
    }

    @Test
    fun reusedGeometryResizesForNewSnapshotAndRejectsUndrawableInputs() {
        val geometry = ProgressiveWaveformGeometry()
        assertEquals(5, update(geometry, waveform()))
        assertEquals(3, update(geometry, waveform(detail = floatArrayOf(0f, 0.5f, 1f))))
        assertEquals(3.5f, geometry.amplitudeAt(0), 0.00001f)
        assertEquals(47.5f, geometry.amplitudeAt(2), 0.00001f)
        assertEquals(0, update(geometry, waveform(detail = floatArrayOf(1f))))
        assertEquals(0, update(geometry, waveform(), width = 0f))
        assertEquals(5, update(geometry, waveform()))
    }

    private fun waveform(
        coarse: FloatArray = FloatArray(5) { 0.25f },
        detail: FloatArray = FloatArray(5) { 0.5f },
        detailBuilt: Int = detail.size,
    ) = ProgressiveWaveformData(
        coarseSamples = coarse,
        coarseBuiltCount = coarse.size,
        detailSamples = detail,
        detailBuiltCount = detailBuilt,
        pass = RangeWaveformPass.DETAIL,
    )

    private fun update(
        geometry: ProgressiveWaveformGeometry,
        data: ProgressiveWaveformData,
        morph: Float = 1f,
        phase: Float = 0f,
        width: Float = 100f,
    ) = geometry.update(
        data = data,
        visibleCoarseFraction = 1f,
        visibleDetailFraction = 1f,
        morphProgress = morph,
        phase = phase,
        width = width,
        height = 100f,
    )
}
