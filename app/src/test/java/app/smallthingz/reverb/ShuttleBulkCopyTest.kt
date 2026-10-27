package app.smallthingz.reverb

import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class ShuttleBulkCopyTest {
    @Test fun exactBaselineBytesAcrossOverlapTailAndOddLengthCases() {
        val random = java.util.Random(0x51a77eL)
        fun checkCase(currentSize: Int, previousSize: Int?, overlap: Int) {
            val current = ByteArray(currentSize).also { random.nextBytes(it) }
            val previous = previousSize?.let { ByteArray(it).also { bytes -> random.nextBytes(bytes) } }
            val currentBefore = current.copyOf()
            val previousBefore = previous?.copyOf()
            val expected = baselineCrossfade(previous, current, overlap)
            val actual = crossfadeShuttlePcm16(previous, current, overlap)
            assertArrayEquals(expected.output, actual.output)
            assertArrayEquals(expected.tail, actual.tail)
            assertArrayEquals(currentBefore, current)
            if (previous != null) assertArrayEquals(previousBefore, previous)
            if (currentSize < 2 && previous != null) assertSame(previous, actual.tail)
        }
        for (size in 0..24) for (previous in listOf(null, 0, 1, 2, 3, 8, 25, 100)) {
            for (overlap in listOf(Int.MIN_VALUE, -1, 0, 1, 2, 3, 6, 12, 25, Int.MAX_VALUE)) {
                checkCase(size, previous, overlap)
            }
        }
        repeat(200) { checkCase(random.nextInt(12001), random.nextInt(4097), random.nextInt(2049)) }
        checkCase(4608, 1152, 576)
        checkCase(4609, null, 576)
        checkCase(4608, 1, 576)
    }

    @Test fun stereoCrossfadeMatchesOriginalMonoOraclePerChannel() {
        val random = java.util.Random(0x57e2e0L)
        fun stereo(left: ByteArray, right: ByteArray): ByteArray {
            require(left.size == right.size && left.size % 2 == 0)
            return ByteArray(left.size * 2).also { output ->
                for (offset in left.indices step 2) {
                    output[offset * 2] = left[offset]
                    output[offset * 2 + 1] = left[offset + 1]
                    output[offset * 2 + 2] = right[offset]
                    output[offset * 2 + 3] = right[offset + 1]
                }
            }
        }
        fun samples(frames: Int) = ByteArray(frames * 2).also(random::nextBytes)
        for (frames in 0..12) for (previousFrames in listOf(null, 0, 1, 2, 9)) {
            for (overlap in listOf(0, 1, 2, 3, 20, Int.MAX_VALUE)) {
                val left = samples(frames)
                val right = samples(frames)
                val previousLeft = previousFrames?.let(::samples)
                val previousRight = previousFrames?.let(::samples)
                val previous = previousLeft?.let { stereo(it, requireNotNull(previousRight)) }
                val expectedLeft = baselineCrossfade(previousLeft, left, overlap)
                val expectedRight = baselineCrossfade(previousRight, right, overlap)
                val actual = crossfadeShuttlePcm16(previous, stereo(left, right), overlap, channelCount = 2)
                assertArrayEquals(stereo(expectedLeft.output, expectedRight.output), actual.output)
                assertArrayEquals(stereo(expectedLeft.tail, expectedRight.tail), actual.tail)
                if (frames == 0 && previous != null) assertSame(previous, actual.tail)
            }
        }
    }
}

// Exact 7d894ce baseline function and PCM helpers, retained as a byte-level regression oracle.
private fun baselineCrossfade(
    previousTail: ByteArray?,
    current: ByteArray,
    overlapFrames: Int,
): ShuttleCrossfadeResult {
    val currentFrames = current.size / 2
    if (currentFrames <= 0) return ShuttleCrossfadeResult(ByteArray(0), previousTail ?: ByteArray(0))
    val requestedOverlap = overlapFrames.coerceAtLeast(0)
    val tailFrames = minOf(requestedOverlap, currentFrames)
    val bodyFrames = currentFrames - tailFrames
    val previousFrames = (previousTail?.size ?: 0) / 2
    val mixedFrames = minOf(previousFrames, bodyFrames, requestedOverlap)
    val output = ByteArray(bodyFrames * 2)

    var outFrame = 0
    if (mixedFrames > 0 && previousTail != null) {
        val previousStart = previousFrames - mixedFrames
        repeat(mixedFrames) { index ->
            val t = (index + 1).toFloat() / (mixedFrames + 1).toFloat()
            val blend = t * t * (3f - 2f * t)
            val previous = previousTail.readPcm16Mono(previousStart + index)
            val next = current.readPcm16Mono(index)
            val mixed = (previous * (1f - blend) + next * blend)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.writePcm16Mono(outFrame++, mixed)
        }
    }
    val currentBodyStart = mixedFrames
    for (frame in currentBodyStart until bodyFrames) {
        var value = current.readPcm16Mono(frame)
        if (previousFrames == 0 && requestedOverlap > 0 && frame < requestedOverlap) {
            val t = (frame + 1).toFloat() / (requestedOverlap + 1).toFloat()
            val fade = t * t * (3f - 2f * t)
            value = (value * fade).roundToInt()
        }
        output.writePcm16Mono(outFrame++, value)
    }
    val tail = if (tailFrames > 0) {
        current.copyOfRange(bodyFrames * 2, currentFrames * 2)
    } else {
        ByteArray(0)
    }
    return ShuttleCrossfadeResult(output, tail)
}

private fun ByteArray.readPcm16Mono(frame: Int): Int {
    val index = frame * 2
    return ((this[index + 1].toInt() shl 8) or (this[index].toInt() and 0xff)).toShort().toInt()
}

private fun ByteArray.writePcm16Mono(frame: Int, value: Int) {
    val index = frame * 2
    this[index] = (value and 0xff).toByte()
    this[index + 1] = ((value ushr 8) and 0xff).toByte()
}
