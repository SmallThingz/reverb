package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.RangeWaveformPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveWaveformContourCacheTest {
    @Test
    fun settledPlayheadAndSelectionTracesReuseBitIdenticalContour() {
        val data = waveform()
        val initial = ProgressiveWaveformFrame(1f, 1f, 1f, 42f, 0f, 0f, false)
        for (trim in listOf(false, true)) {
            val cache = ProgressiveWaveformContourCache()
            val reference = ProgressiveWaveformGeometry()
            var rebuilt = 0
            var referencePoints = 0
            repeat(120) { position ->
                val frame = initial.copy(
                    startFraction = if (trim) position / 240f else 0f,
                    endFraction = if (trim) .8f else position / 120f,
                )
                val input = Input(data, frame.visibleCoarseFraction, frame.visibleDetailFraction,
                    frame.morphProgress, frame.wobblePhaseSeconds)
                if (update(cache, input)) rebuilt++
                referencePoints += assertExactContour(reference, cache, input)
            }
            assertEquals(61_440, referencePoints)
            assertEquals(1, rebuilt)
        }
    }

    @Test
    fun everyGeometryInputInvalidatesWithoutChangingContourBits() {
        val initial = Input(waveform(), coarse = .7f, detail = .4f, morph = .9f, phase = 3f)
        val changes = listOf(
            initial.copy(data = waveform(.2f)), // equal lengths, different immutable snapshot
            initial.copy(coarse = .8f),
            initial.copy(detail = .6f),
            initial.copy(morph = .86f),
            initial.copy(phase = 7f),
            initial.copy(width = 641f),
            initial.copy(height = 129f),
            initial.copy(width = 0f),
            initial.copy(height = 0f),
        )
        for (changed in changes) {
            val cache = ProgressiveWaveformContourCache()
            val reference = ProgressiveWaveformGeometry()
            assertTrue(update(cache, initial))
            assertExactContour(reference, cache, initial)
            assertTrue(update(cache, changed))
            assertExactContour(reference, cache, changed)
            assertFalse(update(cache, changed))
            assertExactContour(reference, cache, changed)
            assertTrue(update(cache, initial))
            assertExactContour(reference, cache, initial)
        }
    }

    @Test
    fun openingMorphKeepsAllChangingContourFrames() {
        val data = waveform()
        val cache = ProgressiveWaveformContourCache()
        val reference = ProgressiveWaveformGeometry()
        var points = 0
        // The View draws the initial zero-progress blob as an oval, before consulting the contour.
        for (frame in 1..60) {
            val input = Input(data, morph = frame / 60f, phase = frame / 60f)
            assertTrue(update(cache, input))
            points += assertExactContour(reference, cache, input)
        }
        assertEquals(9_216, points)
    }

    @Test
    fun retirementAndPreparationCannotReuseAnOldPath() {
        val input = Input(waveform())
        val cache = ProgressiveWaveformContourCache()
        val reference = ProgressiveWaveformGeometry()
        assertTrue(update(cache, input))
        assertFalse(update(cache, input))
        cache.clear()
        assertEquals(0, cache.pointCount)
        assertTrue(update(cache, input))
        assertExactContour(reference, cache, input)
        cache.prepare(32)
        assertEquals(0, cache.pointCount)
        assertTrue(update(cache, input))
        assertExactContour(reference, cache, input)
    }

    @Test
    fun cachedSnapshotDoesNotObserveProducerArrayMutation() {
        val coarse = FloatArray(96) { .25f }
        val detail = FloatArray(512) { .5f }
        val data = ProgressiveWaveformData(coarse, coarse.size, detail, detail.size, RangeWaveformPass.DETAIL)
        val input = Input(data)
        val cache = ProgressiveWaveformContourCache()
        val reference = ProgressiveWaveformGeometry()
        assertTrue(update(cache, input))
        coarse.fill(Float.NaN)
        detail.fill(Float.NaN)
        assertFalse(update(cache, input))
        assertExactContour(reference, cache, input)
    }

    @Test
    fun rawFloatKeysDoNotConflateSignedZero() {
        val input = Input(waveform(), phase = 0f)
        val cache = ProgressiveWaveformContourCache()
        val reference = ProgressiveWaveformGeometry()
        assertTrue(update(cache, input))
        val negativeZero = input.copy(phase = -0f)
        assertTrue(update(cache, negativeZero))
        assertExactContour(reference, cache, negativeZero)
        assertFalse(update(cache, negativeZero))
    }

    private data class Input(
        val data: ProgressiveWaveformData,
        val coarse: Float = 1f,
        val detail: Float = 1f,
        val morph: Float = 1f,
        val phase: Float = 42f,
        val width: Float = 640f,
        val height: Float = 128f,
    )

    private fun waveform(offset: Float = 0f) = ProgressiveWaveformData(
        FloatArray(96) { (it % 13) / 16f + offset }, 96,
        FloatArray(512) { (it % 17) / 20f + offset }, 512,
        RangeWaveformPass.DETAIL,
    )

    private fun update(cache: ProgressiveWaveformContourCache, input: Input) = with(input) {
        cache.update(data, coarse, detail, morph, phase, width, height)
    }

    private fun assertExactContour(
        reference: ProgressiveWaveformGeometry,
        cached: ProgressiveWaveformContourCache,
        input: Input,
    ): Int = with(input) {
        val count = reference.update(data, coarse, detail, morph, phase, width, height)
        assertEquals(count, cached.pointCount)
        for (point in 0 until count) {
            assertEquals("Contour point $point", reference.amplitudeAt(point).toRawBits(),
                cached.amplitudeAt(point).toRawBits())
        }
        count
    }
}
