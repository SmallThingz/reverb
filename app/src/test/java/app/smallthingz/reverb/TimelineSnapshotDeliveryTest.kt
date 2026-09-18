package app.smallthingz.reverb

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.assertEquals
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

    @Test
    fun captureSnapshotCleanup_attemptsEveryCloseAfterEarlierFailure() {
        val events = mutableListOf<String>()
        val first = Closeable {
            events += "first"
            throw IOException("release failed")
        }
        val second = Closeable { events += "second" }

        closeCaptureSnapshotsBestEffort(first, second)

        assertEquals(listOf("first", "second"), events)
    }
}
