package app.smallthingz.reverb.nativeui

import androidx.compose.ui.unit.Constraints as OracleConstraints
import androidx.compose.ui.unit.IntSize as OracleIntSize
import androidx.compose.ui.unit.constrain as oracleConstrain
import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.unit.Constraints as NativeConstraints
import smallthingz.ui.unit.IntSize as NativeIntSize
import smallthingz.ui.unit.constrain as nativeConstrain

class NativeTextConstraintsFitParityTest {
    @Test fun widthPriorityKeepsPackedTextBoundsAndClampingAtEachCapacity() {
        val limits = intArrayOf(0, 1, 8190, 8191, 32766, 32767, 65534, 65535,
            262142, 262143, 300000, Int.MAX_VALUE)
        val ranges = limits.flatMap { maximum ->
            limits.filter { it <= maximum && it != Int.MAX_VALUE }.map { it to maximum }
        }
        val probes = intArrayOf(0, 12000, 40000, Int.MAX_VALUE)
        for ((minWidth, maxWidth) in ranges) for ((minHeight, maxHeight) in ranges) {
            val oracle = OracleConstraints.fitPrioritizingWidth(minWidth, maxWidth, minHeight, maxHeight)
            val native = NativeConstraints.fitPrioritizingWidth(minWidth, maxWidth, minHeight, maxHeight)
            val bounds = "$minWidth/$maxWidth, $minHeight/$maxHeight"
            assertEquals(bounds, oracle.minWidth, native.minWidth)
            assertEquals(bounds, oracle.maxWidth, native.maxWidth)
            assertEquals(bounds, oracle.minHeight, native.minHeight)
            assertEquals(bounds, oracle.maxHeight, native.maxHeight)
            assertEquals(bounds, oracle.hasBoundedWidth, native.hasBoundedWidth)
            assertEquals(bounds, oracle.hasBoundedHeight, native.hasBoundedHeight)
            for (probe in probes) {
                val expected = oracle.oracleConstrain(OracleIntSize(probe, probe))
                val actual = native.nativeConstrain(NativeIntSize(probe, probe))
                assertEquals(bounds, expected.width, actual.width)
                assertEquals(bounds, expected.height, actual.height)
            }
        }
    }
}
