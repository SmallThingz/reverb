package app.smallthingz.nativeui.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PausableNativeClockTest {
    private class ImmediateClock : MonotonicFrameClock {
        var requests = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requests++
            return onFrame(requests.toLong())
        }
    }

    @Test fun pausedAndCancelledRequestNeverReachesClock() = runBlocking {
        val wrapped = ImmediateClock()
        val clock = PausableMonotonicFrameClock(wrapped)
        clock.pause()
        val request = async(start = CoroutineStart.UNDISPATCHED) { clock.withFrameNanos { it } }
        assertFalse(request.isCompleted)
        assertEquals(0, wrapped.requests)
        request.cancelAndJoin()
        clock.resume()
        assertEquals(0, wrapped.requests)
        assertEquals(1L, clock.withFrameNanos { it })
    }

    @Test fun resumedWaitersAndFutureRequestsUseWrappedTimestampExactlyOnce() = runBlocking {
        val wrapped = ImmediateClock()
        val clock = PausableMonotonicFrameClock(wrapped)
        clock.pause()
        val first = async(start = CoroutineStart.UNDISPATCHED) { clock.withFrameNanos { it } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { clock.withFrameNanos { it } }
        assertEquals(0, wrapped.requests)
        clock.resume()
        clock.resume()
        assertEquals(setOf(1L, 2L), setOf(first.await(), second.await()))
        assertEquals(3L, clock.withFrameNanos { it })
        assertEquals(3, wrapped.requests)
    }
}
