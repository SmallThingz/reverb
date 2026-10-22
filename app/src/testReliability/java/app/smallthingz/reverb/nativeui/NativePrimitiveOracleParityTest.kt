package app.smallthingz.reverb.nativeui

import androidx.compose.ui.geometry.Offset as OracleOffset
import androidx.compose.ui.geometry.Rect as OracleRect
import androidx.compose.ui.geometry.Size as OracleSize
import androidx.compose.ui.graphics.Color as OracleColor
import androidx.compose.ui.graphics.Matrix as OracleMatrix
import androidx.compose.ui.graphics.colorspace.ColorSpaces as OracleColorSpaces
import androidx.compose.ui.graphics.lerp as oracleColorLerp
import androidx.compose.ui.graphics.toArgb as oracleArgb
import androidx.compose.ui.input.pointer.util.VelocityTracker as OracleVelocityTracker
import androidx.compose.ui.unit.Constraints as OracleConstraints
import androidx.compose.ui.unit.Velocity as OracleVelocity
import app.smallthingz.nativeui.geometry.Offset as NativeOffset
import app.smallthingz.nativeui.geometry.Rect as NativeRect
import app.smallthingz.nativeui.geometry.Size as NativeSize
import app.smallthingz.nativeui.graphics.Color as NativeColor
import app.smallthingz.nativeui.graphics.Matrix as NativeMatrix
import app.smallthingz.nativeui.graphics.colorspace.ColorSpaces as NativeColorSpaces
import app.smallthingz.nativeui.graphics.lerp as nativeColorLerp
import app.smallthingz.nativeui.graphics.toArgb as nativeArgb
import app.smallthingz.nativeui.input.pointer.util.VelocityTracker as NativeVelocityTracker
import app.smallthingz.nativeui.unit.Constraints as NativeConstraints
import app.smallthingz.nativeui.unit.Velocity as NativeVelocity
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
            val native = NativeMatrix().apply { translate(14.7f, -29.2f, .5f); rotateZ(angle); scale(.83f, 1.07f) }
            oracle.values[3] = .00013f; native.values[3] = .00013f
            oracle.values[7] = -.00004f; native.values[7] = -.00004f
            for (x in scalars) for (y in scalars) {
                assertEquals(oracle.map(OracleOffset(x,y)).packedValue, native.map(NativeOffset(x,y)).packedValue)
            }
            val expected = oracle.map(OracleRect(-4.2f, 2.1f, 321.3f, 89.2f))
            val actual = native.map(NativeRect(-4.2f, 2.1f, 321.3f, 89.2f))
            assertEquals(expected.left.toRawBits(), actual.left.toRawBits())
            assertEquals(expected.top.toRawBits(), actual.top.toRawBits())
            assertEquals(expected.right.toRawBits(), actual.right.toRawBits())
            assertEquals(expected.bottom.toRawBits(), actual.bottom.toRawBits())
            oracle.invert(); native.invert()
            for (index in 0..15) assertEquals(oracle.values[index].toRawBits(), native.values[index].toRawBits())
        }
    }

    @Test fun constraintsPackingBoundariesKeepValuesAndRejections() {
        val limits = intArrayOf(0, 1, 1080, 8191, 8192, 16383, 16384, 32767, 65535, 262142, Int.MAX_VALUE)
        for (width in limits) for (height in limits) {
            val oracle = runCatching { OracleConstraints(0, width, 0, height) }
            val native = runCatching { NativeConstraints(0, width, 0, height) }
            assertEquals("$width/$height", oracle.isSuccess, native.isSuccess)
            if (oracle.isSuccess) {
                val expected = oracle.getOrThrow(); val actual = native.getOrThrow()
                assertEquals(expected.minWidth, actual.minWidth)
                assertEquals(expected.maxWidth, actual.maxWidth)
                assertEquals(expected.minHeight, actual.minHeight)
                assertEquals(expected.maxHeight, actual.maxHeight)
                assertEquals(expected.hasBoundedWidth, actual.hasBoundedWidth)
                assertEquals(expected.hasBoundedHeight, actual.hasBoundedHeight)
            } else assertEquals(oracle.exceptionOrNull()!!::class.java, native.exceptionOrNull()!!::class.java)
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
}
