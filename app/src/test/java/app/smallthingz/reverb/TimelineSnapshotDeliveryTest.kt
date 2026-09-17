package app.smallthingz.reverb

import java.io.Closeable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotDeliveryTest {
    @Test
    fun successfulLiveDeliveryTransfersSnapshotOwnership() {
        var closed = false
        var delivered: Closeable? = null
        val snapshot = Closeable { closed = true }

        deliverTimelineSnapshotAtServiceBoundary(false, snapshot) { delivered = it }

        assertSame(snapshot, delivered)
        assertFalse(closed)
        delivered?.close()
        assertTrue(closed)
    }

    @Test
    fun failedLiveDeliveryClosesSnapshotBeforePropagatingCallbackFailure() {
        var closed = false
        val snapshot = Closeable { closed = true }
        var threw = false

        try {
            deliverTimelineSnapshotAtServiceBoundary(false, snapshot) {
                throw IllegalStateException("callback failed")
            }
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertTrue(closed)
    }
}
