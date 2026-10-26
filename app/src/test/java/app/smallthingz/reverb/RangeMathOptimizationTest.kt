package app.smallthingz.reverb

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeMathOptimizationTest {
    @Test
    fun collapsedTrimAtMaximumDuration_keepsPositiveGapForEitherBoundary() {
        val duration = Int.MAX_VALUE
        val collapsed = InlineFineSeekValues(duration, duration, duration)
        val expected = InlineFineSeekValues(duration, duration - 50, duration)
        assertEquals(expected, adjustInlineTrimTarget(collapsed, duration, InlineFineSeekTarget.TRIM_START, duration))
        assertEquals(expected, adjustInlineTrimTarget(collapsed, duration, InlineFineSeekTarget.TRIM_END, duration))
    }

    @Test
    fun trimExtremeAndMalformedValues_matchWideArithmeticReference() {
        val durations = intArrayOf(Int.MIN_VALUE, -1, 0, 1, 25, 49, 50, 51, 1_000, Int.MAX_VALUE - 50, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        val values = intArrayOf(Int.MIN_VALUE, -1, 0, 1, 49, 50, 51, Int.MAX_VALUE - 49, Int.MAX_VALUE)
        for (duration in durations) for (start in values) for (end in values) for (request in values) {
            val original = InlineFineSeekValues(Int.MAX_VALUE, start, end)
            for (target in arrayOf(InlineFineSeekTarget.TRIM_START, InlineFineSeekTarget.TRIM_END)) {
                val actual = adjustInlineTrimTarget(original, duration, target, request)
                assertEquals(wideTrim(original, duration, target, request), actual)
                assertTrue(actual.startMillis >= 0)
                assertTrue(actual.endMillis <= duration.coerceAtLeast(1))
                assertTrue(actual.endMillis.toLong() - actual.startMillis >= minOf(50, duration.coerceAtLeast(1)))
            }
        }
    }

    @Test
    fun endpointBounds_matchPointerOracleBitsAcrossFloatExponentAndSubnormalEdges() {
        val bitPatterns = linkedSetOf(1, 2, 3, 0x007fffff, 0x00800000, 0x7f7fffff, 0.05f.toRawBits())
        for (exponent in 1..254) for (delta in -2..2) {
            val bits = (exponent shl 23) + delta
            if (bits in 1 until 0x7f800000) bitPatterns += bits
        }
        val random = Random(26026)
        repeat(10_000) { bitPatterns += random.nextInt(1, 0x7f800000) }
        for (bits in bitPatterns) {
            val duration = Float.fromBits(bits)
            for (target in RangeEditTarget.entries) {
                val expected = legacyEndpointLimits(duration, target)
                val actual = requireNotNull(rangeEndpointLimits(duration, target))
                assertEquals("lower ${bits.toString(16)} $target", expected.start.toRawBits(), actual.start.toRawBits())
                assertEquals("upper ${bits.toString(16)} $target", expected.endInclusive.toRawBits(), actual.endInclusive.toRawBits())
            }
        }
        for (duration in floatArrayOf(-0f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (target in RangeEditTarget.entries) assertNull(rangeEndpointLimits(duration, target))
        }
    }

    @Test
    fun wheelValues_matchLegacySortedDistinctForAllSmallRingsAndBoundedHugeInputs() {
        for (maximum in -2..80) for (step in 1..20) for (current in -2..100) {
            for (includeMaximum in booleanArrayOf(false, true)) {
                assertArrayEquals(legacyWheel(step, maximum, current, includeMaximum),
                    rangeDurationWheelValues(step, maximum, current, includeMaximum))
            }
        }
        val random = Random(26026)
        repeat(2_000) {
            val maximum = random.nextInt(Int.MAX_VALUE)
            val step = random.nextInt(maxOf(1, maximum / 4096 + 1), Int.MAX_VALUE)
            val current = random.nextInt()
            val includeMaximum = random.nextBoolean()
            assertArrayEquals(legacyWheel(step, maximum, current, includeMaximum),
                rangeDurationWheelValues(step, maximum, current, includeMaximum))
        }
        for (step in intArrayOf(Int.MAX_VALUE / 2, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            for (current in intArrayOf(0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
                assertArrayEquals(legacyWheel(step, Int.MAX_VALUE, current, true),
                    rangeDurationWheelValues(step, Int.MAX_VALUE, current, true))
            }
        }
    }

    @Test
    fun wheelOffProfileMaximumAndCurrent_remainOrderedAndUnique() {
        assertArrayEquals(intArrayOf(0, 1, 2), rangeDurationWheelValues(5, 1, 2))
        assertArrayEquals(intArrayOf(0, 4, 5), rangeDurationWheelValues(5, 5, 4))
        assertArrayEquals(intArrayOf(0, 1, 5), rangeDurationWheelValues(1, 1, 5))
        assertArrayEquals(intArrayOf(0, 5, 7), rangeDurationWheelValues(5, 7, 7))
        assertArrayEquals(intArrayOf(0, 5, 7), rangeDurationWheelValues(5, 7, 7, false))
    }

    @Test
    fun wheelInvalidStepAndOverflowingCapacity_preserveRejectionWithoutLargeAllocation() {
        for (step in intArrayOf(Int.MIN_VALUE, -1, 0)) {
            assertThrows(IllegalArgumentException::class.java) { legacyWheel(step, 59, 0, true) }
            assertThrows(IllegalArgumentException::class.java) { rangeDurationWheelValues(step, 59, 0) }
        }
        for (maximum in intArrayOf(Int.MAX_VALUE - 2, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { legacyWheel(1, maximum, 0, true) }
            assertThrows(IllegalArgumentException::class.java) { rangeDurationWheelValues(1, maximum, 0) }
        }
    }

    @Test
    fun quantizerFailure_doesNotCommitRemainderAndSuccessfulCallRunsOnce() {
        val position = FineSeekPosition<String>()
        val failure = IllegalStateException("quantizer failure")
        var calls = 0
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            position.advance("start", 10.0, 0.25, 0.0, 20.0) { calls++; throw failure }
        })
        assertEquals(1, calls)
        assertEquals(10.25, position.advance("start", 10.0, 0.25, 0.0, 20.0) { calls++; it }, 0.0)
        assertEquals(2, calls)
        assertEquals(10.5, position.project("start", 10.25, 0.25, 0.0, 20.0), 0.0)
    }

    private fun wideTrim(values: InlineFineSeekValues, durationMillis: Int, target: InlineFineSeekTarget, request: Int): InlineFineSeekValues {
        val duration = durationMillis.toLong().coerceAtLeast(1L)
        val gap = minOf(50L, duration)
        var start = values.startMillis.toLong().coerceIn(0L, duration)
        var end = values.endMillis.toLong().coerceIn(start, duration)
        if (end - start < gap) {
            end = minOf(start + gap, duration)
            start = maxOf(0L, end - gap)
        }
        if (target == InlineFineSeekTarget.TRIM_START) {
            start = request.toLong().coerceIn(0L, duration - gap)
            end = maxOf(end, start + gap)
        } else {
            end = request.toLong().coerceIn(gap, duration)
            start = minOf(start, end - gap)
        }
        return InlineFineSeekValues(values.cursorMillis.coerceIn(0, duration.toInt()), start.toInt(), end.toInt())
    }

    // Preserve the old bounds derivation and full wheel algorithm as independent oracles.
    private fun legacyEndpointLimits(duration: Float, target: RangeEditTarget): ClosedFloatingPointRange<Float> {
        val fullRange = RangeEditValues(0f, duration)
        fun bound(requested: Float): Float = editTargetValue(adjustRangeEditTarget(fullRange, target, requested, duration).values, target)
        return bound(0f)..bound(duration)
    }

    private fun legacyWheel(step: Int, maxInclusive: Int, currentValue: Int, includeMaximum: Boolean): IntArray {
        require(step > 0)
        val max = maxInclusive.coerceAtLeast(0)
        val current = currentValue.coerceAtLeast(0)
        val values = ArrayList<Int>(max / step + 3)
        var value = 0
        while (value <= max) {
            values += value
            if (value > Int.MAX_VALUE - step) break
            value += step
        }
        if (includeMaximum && values.lastOrNull() != max) values += max
        if (current !in values) values += current
        values.sort()
        return values.distinct().toIntArray()
    }
}
