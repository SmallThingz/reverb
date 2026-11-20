package app.smallthingz.reverb.nativeui

import androidx.compose.ui.graphics.colorspace.connect as oracleConnect
import androidx.compose.ui.graphics.colorspace.RenderIntent as OracleRenderIntent
import smallthingz.ui.graphics.colorspace.connect as nativeConnect
import smallthingz.ui.graphics.colorspace.RenderIntent as NativeRenderIntent
import androidx.compose.ui.geometry.Offset as OracleOffset
import androidx.compose.ui.geometry.Rect as OracleRect
import androidx.compose.ui.geometry.Size as OracleSize
import androidx.compose.ui.graphics.Color as OracleColor
import app.smallthingz.reverb.populateOnboardingPreviewSemanticTransform
import androidx.compose.ui.graphics.Matrix as OracleMatrix
import androidx.compose.ui.graphics.colorspace.ColorSpaces as OracleColorSpaces
import androidx.compose.ui.graphics.lerp as oracleColorLerp
import androidx.compose.ui.graphics.toArgb as oracleArgb
import androidx.compose.ui.input.pointer.util.VelocityTracker as OracleVelocityTracker
import androidx.compose.ui.unit.Constraints as OracleConstraints
import androidx.compose.ui.unit.Velocity as OracleVelocity
import smallthingz.ui.geometry.Offset as NativeOffset
import smallthingz.ui.geometry.Rect as NativeRect
import smallthingz.ui.geometry.Size as NativeSize
import smallthingz.ui.graphics.Color as NativeColor
import smallthingz.ui.graphics.Matrix as NativeMatrix
import smallthingz.ui.graphics.colorspace.ColorSpaces as NativeColorSpaces
import smallthingz.ui.graphics.lerp as nativeColorLerp
import smallthingz.ui.graphics.toArgb as nativeArgb
import smallthingz.ui.input.pointer.util.VelocityTracker as NativeVelocityTracker
import smallthingz.ui.unit.Constraints as NativeConstraints
import smallthingz.ui.unit.Velocity as NativeVelocity
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** QA-only binary oracle. Production and the native-ui module have no Compose dependency. */
class NativePrimitiveOracleParityTest {
    @Test fun colorEncodingConversionsAndInterpolationMatchPinnedBinaryBits() {
        val random = Random(0x12013)
        val colors = intArrayOf(0, -1, 0xff000000.toInt(), 0xff2868bd.toInt(),
            0xffd09a32.toInt(), 0x80426a91.toInt()) + IntArray(128) { random.nextInt() }
        for (argb in colors) {
            val oracle = OracleColor(argb)
            val native = NativeColor(argb)
            assertEquals("argb=$argb", oracle.value, native.value)
            val oklabOracle = oracle.convert(OracleColorSpaces.Oklab)
            val oklabNative = native.convert(NativeColorSpaces.Oklab)
            assertEquals("oklab=$argb", oklabOracle.value, oklabNative.value)
            assertEquals("return=$argb", oklabOracle.oracleArgb(), oklabNative.nativeArgb())
            for (fraction in floatArrayOf(-0.2f, 0f, 0.1f, 0.5f, 0.9f, 1f, 1.2f)) {
                val other = colors[(argb and Int.MAX_VALUE) % colors.size]
                assertEquals("lerp=$argb/$other/$fraction",
                    oracleColorLerp(oracle, OracleColor(other), fraction).value,
                    nativeColorLerp(native, NativeColor(other), fraction).value)
            }
        }
        val edges = floatArrayOf(-1f, -0.0f, 0f, Float.MIN_VALUE, 0.0039215686f, 0.5f, 1f, 2f)
        for (value in edges) for (alpha in edges) {
            assertEquals(OracleColor(value, 1f-value, value*.3f, alpha).value,
                NativeColor(value, 1f-value, value*.3f, alpha).value)
            assertEquals(OracleColor(value, value*.25f, -value*.25f, alpha, OracleColorSpaces.Oklab).value,
                NativeColor(value, value*.25f, -value*.25f, alpha, NativeColorSpaces.Oklab).value)
        }
    }

    @Test fun packedGeometryAndPerspectiveTransformsMatchPinnedBinaryBits() {
        val scalars = floatArrayOf(-123.5f, -0.0f, 0f, Float.MIN_VALUE, 0.25f, 17.3f, 1024f)
        for (x in scalars) for (y in scalars) {
            assertEquals(OracleOffset(x, y).packedValue, NativeOffset(x, y).packedValue)
            assertEquals(OracleSize(x, y).packedValue, NativeSize(x, y).packedValue)
        }
        for (angle in floatArrayOf(-179.9f, -90f, 0f, 13.37f, 90f, 180f)) {
            val oracle = OracleMatrix().apply { translate(14.7f, -29.2f, .5f); rotateZ(angle); scale(.83f, 1.07f) }
            oracle.values[3] = .00013f
            oracle.values[7] = -.00004f
            val native = NativeMatrix(oracle.values.copyOf())
            for (x in scalars) for (y in scalars) {
                assertEquals(oracle.map(OracleOffset(x,y)).packedValue, native.map(NativeOffset(x,y)).packedValue)
            }
            val expected = oracle.map(OracleRect(-4.2f, 2.1f, 321.3f, 89.2f))
            val actual = native.map(NativeRect(-4.2f, 2.1f, 321.3f, 89.2f))
            assertEquals(expected.left.toRawBits(), actual.left.toRawBits())
            assertEquals(expected.top.toRawBits(), actual.top.toRawBits())
            assertEquals(expected.right.toRawBits(), actual.right.toRawBits())
            assertEquals(expected.bottom.toRawBits(), actual.bottom.toRawBits())
        }
    }

    @Test fun onboardingSemanticTransformMatchesPinnedBinaryBits() {
        val translations = floatArrayOf(-45.79199981689453f, -0f, 0f, -Float.MIN_VALUE, 9.125f)
        val scales = floatArrayOf(-1f, -0f, 0f, 0.9820000529289246f, 1f, 1.075f)
        val native = NativeMatrix()
        val oracle = OracleMatrix()
        for (width in intArrayOf(0, 317, 954)) for (height in intArrayOf(0, 685, 1968)) {
            for (translationX in translations) for (translationY in translations) {
                for (scaleX in scales) for (scaleY in scales) {
                    populateOnboardingPreviewSemanticTransform(native,
                        width, height, translationX, translationY, scaleX, scaleY)
                    oracle.resetToPivotedTransform(pivotX = width / 2f, pivotY = height / 2f,
                        translationX = translationX, translationY = translationY,
                        scaleX = scaleX, scaleY = scaleY)
                    for (index in 0..15) {
                        assertEquals(oracle.values[index].toRawBits(), native.values[index].toRawBits())
                    }
                }
            }
        }
    }

    @Test fun onboardingNonfiniteTranslationRetainsSourcePropagation() {
        val translations = floatArrayOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NaN, -0f, 0f, 3.125f)
        val native = NativeMatrix()
        val oracle = OracleMatrix()
        for (translationX in translations) for (translationY in translations) {
            populateOnboardingPreviewSemanticTransform(native, 317, 685,
                translationX, translationY, -0f, .97f)
            oracle.resetToPivotedTransform(pivotX = 158.5f, pivotY = 342.5f,
                translationX = translationX, translationY = translationY, scaleX = -0f, scaleY = .97f)
            for (index in 0..15) {
                // JVMs may select different NaN payloads. Check all non-NaN coefficients bitwise.
                assertEquals(oracle.values[index].toBits(), native.values[index].toBits())
            }
        }
    }

    @Test fun constraintsPackingBoundariesKeepValuesAndRejections() {
        val limits = intArrayOf(0, 1, 1080, 8190, 8191, 8192, 16383, 16384, 32766, 32767,
            65534, 65535, 262142, 262143, Int.MAX_VALUE)
        val ranges = limits.map { 0 to it } + limits.filter { it != 0 && it != Int.MAX_VALUE }
            .flatMap { listOf(it to it, it to Int.MAX_VALUE) } + listOf(-1 to 1, 2 to 1)
        for ((minWidth, maxWidth) in ranges) for ((minHeight, maxHeight) in ranges) {
            val oracle = runCatching { OracleConstraints(minWidth, maxWidth, minHeight, maxHeight) }
            val native = runCatching { NativeConstraints(minWidth, maxWidth, minHeight, maxHeight) }
            val bounds = "$minWidth/$maxWidth, $minHeight/$maxHeight"
            assertEquals(bounds, oracle.isSuccess, native.isSuccess)
            if (oracle.isSuccess) {
                val expected = oracle.getOrThrow(); val actual = native.getOrThrow()
                assertEquals(bounds, expected.minWidth, actual.minWidth)
                assertEquals(bounds, expected.maxWidth, actual.maxWidth)
                assertEquals(bounds, expected.minHeight, actual.minHeight)
                assertEquals(bounds, expected.maxHeight, actual.maxHeight)
                assertEquals(bounds, expected.hasBoundedWidth, actual.hasBoundedWidth)
                assertEquals(bounds, expected.hasBoundedHeight, actual.hasBoundedHeight)
            } else {
                assertEquals(bounds, oracle.exceptionOrNull()!!::class.java, native.exceptionOrNull()!!::class.java)
            }
        }
    }

    @Test fun velocityHistoryClampingStopAndResetMatchPinnedBinaryBits() {
        val oracle = OracleVelocityTracker()
        val native = NativeVelocityTracker()
        val random = Random(0x51a3)
        var time = 1000L
        for (index in 0 until 160) {
            time += if (index % 31 == 0) 41L else (5L + index % 12)
            val x = random.nextFloat()*1080f
            val y = random.nextFloat()*2400f
            oracle.addPosition(time, OracleOffset(x,y)); native.addPosition(time, NativeOffset(x,y))
            for (limit in floatArrayOf(1f, 50f, 10000f, Float.MAX_VALUE)) {
                val expected = oracle.calculateVelocity(OracleVelocity(limit,limit))
                val actual = native.calculateVelocity(NativeVelocity(limit,limit))
                assertEquals("x=$index/$limit", expected.x.toRawBits(), actual.x.toRawBits())
                assertEquals("y=$index/$limit", expected.y.toRawBits(), actual.y.toRawBits())
            }
            if (index % 37 == 0) { oracle.resetTracking(); native.resetTracking() }
        }
    }

    @Test fun everyNamedColorPairAndIntentMatchesPinnedIndependentBinary() {
        val expectedSpaces = listOf(OracleColorSpaces.Srgb, OracleColor.Unspecified.colorSpace, OracleColorSpaces.Oklab)
        val actualSpaces = listOf(NativeColorSpaces.Srgb, NativeColor.Unspecified.colorSpace, NativeColorSpaces.Oklab)
        val packedIds = intArrayOf(0, 16, 19)
        val expectedIntents = listOf(OracleRenderIntent.Perceptual, OracleRenderIntent.Relative,
            OracleRenderIntent.Saturation, OracleRenderIntent.Absolute)
        val actualIntents = listOf(NativeRenderIntent.Perceptual, NativeRenderIntent.Relative,
            NativeRenderIntent.Saturation, NativeRenderIntent.Absolute)
        val random = Random(0x4e414d4544434f4cL)
        for (source in actualSpaces.indices) repeat(256) {
            val raw = (random.nextLong().toULong() and 0x3fUL.inv()) or packedIds[source].toULong()
            val expectedColor = OracleColor(raw)
            val actualColor = NativeColor(raw)
            for (target in actualSpaces.indices) {
                val expectedPacked = runCatching { expectedColor.convert(expectedSpaces[target]).value }
                val actualPacked = runCatching { actualColor.convert(actualSpaces[target]).value }
                assertEquals(expectedPacked.getOrNull(), actualPacked.getOrNull())
                assertEquals(expectedPacked.exceptionOrNull()?.javaClass, actualPacked.exceptionOrNull()?.javaClass)
                for (intent in actualIntents.indices) {
                    val expected = expectedSpaces[source].oracleConnect(expectedSpaces[target], expectedIntents[intent])
                        .transform(floatArrayOf(expectedColor.red, expectedColor.green, expectedColor.blue))
                    val actual = actualSpaces[source].nativeConnect(actualSpaces[target], actualIntents[intent])
                        .transform(floatArrayOf(actualColor.red, actualColor.green, actualColor.blue))
                    for (channel in 0..2) assertEquals("named=$source/$target/$intent/$channel",
                        expected[channel].toBits(), actual[channel].toBits())
                }
            }
        }
    }
}
