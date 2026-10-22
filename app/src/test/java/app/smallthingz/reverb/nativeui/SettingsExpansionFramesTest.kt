package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.runtime.PausableMonotonicFrameClock
import app.smallthingz.nativeui.MotionDurationScale
import app.smallthingz.nativeui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SettingsExpansionFramesTest {
    @Test fun frameAdmissionUsesAttachmentAndInheritedDurationScale()=runBlocking {
        val clock=ManualFrames()
        val duration=object:MotionDurationScale {override val scaleFactor=2f}
        val scope=CoroutineScope(coroutineContext+clock+duration)
        var work=true
        var sampled:Pair<Long,Float>?=null
        val owner=SettingsExpansionFrames(scope,{work}) {time,scale->sampled=time to scale;work=false}
        try {
            owner.schedule();yield()
            assertEquals(0,clock.pendingCount)
            owner.attached();yield()
            assertEquals(1,clock.pendingCount)
            clock.frame(123L);yield()
            assertEquals(123L to 2f,sampled)
            assertEquals(0,clock.pendingCount)
        } finally {owner.close()}
    }

    @Test fun detachCancelsQueuedDeliveryButReattachKeepsTransitionTime()=runBlocking {
        val clock=ManualFrames()
        val scope=CoroutineScope(coroutineContext+clock)
        val motion=SettingsExpansionMotion(false)
        motion.visible(true);motion.measure(IntSize(300,112))
        val owner=SettingsExpansionFrames(scope,{motion.running},motion::frame)
        try {
            owner.attached();yield()
            clock.frame(1_000_000_000L);yield()
            clock.frame(1_100_000_000L);yield()
            val before=motion.measuredSize
            assertTrue(before.height in 1 until 112)
            owner.detached();yield()
            assertEquals(0,clock.pendingCount)
            assertEquals(before,motion.measuredSize)
            owner.attached();yield()
            clock.frame(1_500_000_000L);yield()
            assertTrue(motion.measuredSize.height>before.height)
            assertTrue(scope.isActive)
        } finally {owner.close()}
    }

    @Test fun windowPauseUsesSuppliedClockWithoutRestartingOwner()=runBlocking {
        val underlying=ManualFrames()
        val paused=PausableMonotonicFrameClock(underlying).apply {pause()}
        val scope=CoroutineScope(coroutineContext+paused)
        var calls=0
        val owner=SettingsExpansionFrames(scope,{calls==0}) {_,_->calls++}
        try {
            owner.attached();yield()
            assertEquals(0,underlying.pendingCount)
            paused.resume();yield()
            assertEquals(1,underlying.pendingCount)
            underlying.frame(99L);yield()
            assertEquals(1,calls)
        } finally {owner.close()}
    }

    @Test fun explicitCloseRetiresOnlyItsJobAndCannotReattach()=runBlocking {
        val clock=ManualFrames()
        val scope=CoroutineScope(coroutineContext+clock)
        var calls=0
        val owner=SettingsExpansionFrames(scope,{true}) {_,_->calls++}
        owner.attached();yield()
        assertEquals(1,clock.pendingCount)
        owner.close();yield()
        owner.attached();owner.schedule();yield()
        assertEquals(0,clock.pendingCount)
        assertEquals(0,calls)
        assertTrue(scope.isActive)
    }

    private class ManualFrames:MonotonicFrameClock {
        private class Request(val deliver:(Long)->Unit)
        private val requests=mutableListOf<Request>()
        val pendingCount get()=requests.size
        override suspend fun <R> withFrameNanos(onFrame:(Long)->R):R=suspendCancellableCoroutine {continuation->
            val request=Request {time->continuation.resumeWith(runCatching {onFrame(time)})}
            requests.add(request)
            continuation.invokeOnCancellation {requests.remove(request)}
        }
        fun frame(time:Long) {
            val ready=requests.toList()
            requests.clear()
            ready.forEach {it.deliver(time)}
        }
    }
}
