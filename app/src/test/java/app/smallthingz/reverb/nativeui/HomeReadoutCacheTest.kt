package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.RetentionMode
import app.smallthingz.reverb.captureBufferReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class HomeReadoutCacheTest {
    @Test fun unchangedFlipFramesReuseTheSameReadoutUntilItsDisplayedInputsChange() {
        val cache = HomeReadoutCache()
        val first = cache.read(RetentionMode.SIZE, false, 61f, 1_048_576L, "Off")
        repeat(120) {
            assertSame(first, cache.read(RetentionMode.SIZE, false, 61f, 1_048_576L, "Off"))
        }
        val second = cache.read(RetentionMode.SIZE, false, 95f, 2_097_152L, "Off")
        assertNotSame(first, second)
        repeat(120) {
            assertSame(second, cache.read(RetentionMode.SIZE, false, 95f, 2_097_152L, "Off"))
        }
        assertEquals(captureBufferReadout(RetentionMode.SIZE, false, 95f, 2_097_152L, "Off"), second)
    }

    @Test fun everyReadoutInputAndEnvironmentInvalidationReformatsWithoutChangingOutput() {
        val cache = HomeReadoutCache()
        var previous = cache.read(null, false, 0f, 0L, "Off")
        fun changed(mode: RetentionMode?, disabled: Boolean, seconds: Float, bytes: Long, label: String) {
            val next = cache.read(mode, disabled, seconds, bytes, label)
            assertNotSame(previous, next)
            assertEquals(captureBufferReadout(mode, disabled, seconds, bytes, label), next)
            previous = next
        }
        changed(RetentionMode.TIME, false, 0f, 0L, "Off")
        changed(RetentionMode.TIME, false, 61f, 0L, "Off")
        changed(RetentionMode.TIME, false, 61f, 1_048_576L, "Off")
        changed(RetentionMode.SIZE, false, 61f, 1_048_576L, "Off")
        changed(RetentionMode.SIZE, true, 61f, 1_048_576L, "Off")
        changed(RetentionMode.SIZE, true, 61f, 1_048_576L, "Aus")
        cache.invalidate()
        changed(RetentionMode.SIZE, true, 61f, 1_048_576L, "Aus")
    }

    @Test fun cachedResultsEqualTheReleasedFormatterAcrossBoundaryAndNonFiniteInputs() {
        val cache = HomeReadoutCache()
        val times = floatArrayOf(-1f, -0f, 0f, .999f, 1f, 59.999f, 60f, 3599.999f,
            3600f, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN)
        val sizes = longArrayOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_048_575L, 1_048_576L,
            55_663_544L, Long.MAX_VALUE)
        for (mode in listOf(null, RetentionMode.TIME, RetentionMode.SIZE)) {
            for (disabled in listOf(false, true)) {
                for (seconds in times) for (bytes in sizes) {
                    val expected = captureBufferReadout(mode, disabled, seconds, bytes, "Off")
                    val actual = cache.read(mode, disabled, seconds, bytes, "Off")
                    assertEquals(expected, actual)
                    assertSame(actual, cache.read(mode, disabled, seconds, bytes, "Off"))
                }
            }
        }
    }
}
