package app.smallthingz.reverb.nativeui

import androidx.compose.runtime.MonotonicFrameClock as OracleClock
import androidx.compose.runtime.PausableMonotonicFrameClock as OraclePausableClock
import androidx.compose.runtime.mutableFloatStateOf as oracleFloatState
import androidx.compose.runtime.mutableStateOf as oracleState
import smallthingz.ui.runtime.MonotonicFrameClock as NativeClock
import smallthingz.ui.runtime.PausableMonotonicFrameClock as NativePausableClock
import smallthingz.ui.runtime.mutableFloatStateOf as nativeFloatState
import smallthingz.ui.runtime.mutableStateOf as nativeState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeRuntimeOracleParityTest {
    private data class Payload(val value: Int)

    @Test fun structuralEqualityPreservesPreviousObjectAndDestructuredSetter() {
        val original = Payload(1)
        val equalReplacement = Payload(1)
        val oracle = oracleState(original)
        val native = nativeState(original)
        oracle.value = equalReplacement; native.value = equalReplacement
        assertSame(original, oracle.value)
        assertSame(original, native.value)
        val (oracleValue, oracleSet) = oracle
        val (nativeValue, nativeSet) = native
        assertSame(oracleValue, nativeValue)
        val changed = Payload(2)
        oracleSet(changed); nativeSet(changed)
        assertSame(oracle.value, native.value)
    }

    @Test fun primitiveFloatRetainsSignedZeroAndNaNAssignmentBehavior() {
        val oracle = oracleFloatState(0f)
        val native = nativeFloatState(0f)
        val values = floatArrayOf(-0.0f, 1f, -0.0f, 0f,
            Float.fromBits(0x7fc00001), Float.fromBits(0x7fc00002), Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        for (value in values) {
            oracle.floatValue = value; native.floatValue = value
            assertEquals(oracle.floatValue.toRawBits(), native.floatValue.toRawBits())
            assertEquals(oracle.component1().toRawBits(), native.component1().toRawBits())
        }
    }

    @Test fun pausedClockCancellationResumeAndFrameTimesMatch() = runBlocking {
        var oracleRequests = 0
        var nativeRequests = 0
        val oracle = OraclePausableClock(object : OracleClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(++oracleRequests * 16_666_667L)
        })
        val native = NativePausableClock(object : NativeClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(++nativeRequests * 16_666_667L)
        })
        val expected = ArrayList<Long>(); val actual = ArrayList<Long>()
        for (iteration in 0..7) {
            oracle.pause(); native.pause()
            val oldWaiter = async(start=CoroutineStart.UNDISPATCHED) { oracle.withFrameNanos { expected.add(it) } }
            val newWaiter = async(start=CoroutineStart.UNDISPATCHED) { native.withFrameNanos { actual.add(it) } }
            assertEquals(oracle.isPaused, native.isPaused)
            assertEquals(oracleRequests, nativeRequests)
            if (iteration % 2 == 0) { oldWaiter.cancelAndJoin(); newWaiter.cancelAndJoin() }
            oracle.resume(); native.resume()
            if (iteration % 2 != 0) { oldWaiter.await(); newWaiter.await() }
            oracle.withFrameNanos { expected.add(it) }; native.withFrameNanos { actual.add(it) }
        }
        assertEquals(expected, actual)
        assertEquals(oracleRequests, nativeRequests)
    }
}
