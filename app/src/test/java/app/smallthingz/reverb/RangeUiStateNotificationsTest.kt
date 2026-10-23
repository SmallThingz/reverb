package app.smallthingz.reverb

import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.runtime.PausableMonotonicFrameClock
import smallthingz.ui.runtime.mutableFloatStateOf
import smallthingz.ui.runtime.mutableIntStateOf
import smallthingz.ui.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RangeUiStateNotificationsTest {
    @Test fun releasedNullPathReturnsExactStorageObjects() {
        val generic = mutableStateOf("original")
        val floating = mutableFloatStateOf(1f)
        val integer = mutableIntStateOf(1)
        assertSame(generic, rangeUiState(generic, null))
        assertSame(floating, rangeUiState(floating, null))
        assertSame(integer, rangeUiState(integer, null))
    }

    @Test fun notificationReadsAlreadyCommittedStorageAndPreservesIdentity() {
        val first = String(charArrayOf('a'))
        val second = String(charArrayOf('a'))
        // A custom delegate deliberately replaces equal values. The notification wrapper must
        // preserve that delegate's behavior, without imposing the default cell's equality rule.
        val storage = object : smallthingz.ui.runtime.MutableState<String> {
            override var value = first
        }
        var observed: String? = null
        val wrapper = rangeUiState(storage) { observed = storage.value }
        wrapper.value = second
        assertSame(second, storage.value)
        assertSame(second, observed)
        wrapper.component2()(first)
        assertSame(first, observed)
    }

    @Test fun primitiveAccessAndBoxedOrDestructuredWritesNotifyWithoutChangingValues() {
        val floatStorage = mutableFloatStateOf(1f)
        val intStorage = mutableIntStateOf(1)
        var floats = 0; var ints = 0
        val floating = rangeUiState(floatStorage) { floats++ }
        val integer = rangeUiState(intStorage) { ints++ }
        floating.floatValue = Float.NaN
        assertTrue(floatStorage.floatValue.isNaN())
        floating.value = 7f
        floating.component2()(-0f)
        integer.intValue = Int.MAX_VALUE
        integer.value = Int.MIN_VALUE
        integer.component2()(9)
        assertEquals(floatStorage.floatValue.toBits(), floating.floatValue.toBits())
        assertEquals(9, integer.intValue)
        assertEquals(3, floats); assertEquals(3, ints)
    }

    @Test fun manyImmediateWritesRenderLatestOnlyOnInheritedFrame() = runBlocking {
        val frames = ManualFrames()
        var value = 0; val rendered = mutableListOf<Int>()
        val owner = NativeRangeUiInvalidation(CoroutineScope(coroutineContext + frames)) { rendered += value }
        try {
            repeat(4) { value++; owner.request() }
            assertEquals(4, value)
            assertTrue(rendered.isEmpty())
            yield()
            assertEquals(1, frames.pendingCount)
            frames.frame(10L); yield()
            assertEquals(listOf(4), rendered)
            assertEquals(0, frames.pendingCount)
        } finally { owner.close() }
    }

    @Test fun pausedWindowRetainsPendingInvalidationUntilResume() = runBlocking {
        val frames = ManualFrames()
        val paused = PausableMonotonicFrameClock(frames).apply { pause() }
        var value = 1; val rendered = mutableListOf<Int>()
        val owner = NativeRangeUiInvalidation(CoroutineScope(coroutineContext + paused)) { rendered += value }
        try {
            owner.request(); yield()
            value = 2; owner.request(); yield()
            assertEquals(0, frames.pendingCount)
            assertTrue(rendered.isEmpty())
            paused.resume(); yield()
            frames.frame(20L); yield()
            assertEquals(listOf(2), rendered)
        } finally { owner.close() }
    }

    @Test fun mutationDuringBindSchedulesFollowingFrame() = runBlocking {
        val frames = ManualFrames()
        var calls = 0
        lateinit var owner: NativeRangeUiInvalidation
        owner = NativeRangeUiInvalidation(CoroutineScope(coroutineContext + frames)) {
            calls++
            if (calls == 1) owner.request()
        }
        try {
            owner.request(); yield(); frames.frame(30L); yield()
            assertEquals(1, calls)
            assertEquals(1, frames.pendingCount)
            frames.frame(40L); yield()
            assertEquals(2, calls)
            assertEquals(0, frames.pendingCount)
        } finally { owner.close() }
    }

    @Test fun disposalRetiresPendingBindWithoutCancellingHost() = runBlocking {
        val frames = ManualFrames()
        val scope = CoroutineScope(coroutineContext + frames)
        var calls = 0
        val owner = NativeRangeUiInvalidation(scope) { calls++ }
        owner.request(); yield()
        assertEquals(1, frames.pendingCount)
        owner.close(); yield()
        owner.request(); frames.frame(50L); yield()
        assertEquals(0, calls)
        assertEquals(0, frames.pendingCount)
        assertTrue(scope.isActive)
    }

    private class ManualFrames : MonotonicFrameClock {
        private class Request(val deliver: (Long) -> Unit)
        private val requests = mutableListOf<Request>()
        val pendingCount get() = requests.size
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            suspendCancellableCoroutine { continuation ->
                val request = Request { time -> continuation.resumeWith(runCatching { onFrame(time) }) }
                requests += request
                continuation.invokeOnCancellation { requests.remove(request) }
            }
        fun frame(time: Long) {
            val ready = requests.toList(); requests.clear()
            ready.forEach { it.deliver(time) }
        }
    }
}
