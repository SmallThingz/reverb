package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingTileDeliveryTest {
    private val running = RecordingTileSnapshot(
        listening = true,
        activeBuffer = ReverbService.BufferSlot.LOOPING,
        oneShotEnabled = true,
        oneShotFull = false,
        loopingEnabled = true,
        loopingSeconds = 10f,
        commandGeneration = 1L,
    )

    @Test
    fun queuedRenderCannotReactivateRecordingAfterStopOrTeardown() {
        var latest = running
        var delivered: RecordingTileSnapshot? = null
        var reads = 0
        val render = recordingTileRefreshTask({ reads++; latest }) { delivered = it }
        assertNull(delivered)
        assertEquals(0, reads)
        latest = running.copy(listening = false, oneShotEnabled = false, loopingEnabled = false, commandGeneration = 2L)
        render.run()
        assertEquals(1, reads)
        assertSame(latest, delivered)
        assertFalse(requireNotNull(delivered).listening)
    }

    @Test
    fun queuedRendersUseNewestHandoffDurationsAndSettings() {
        var latest = running
        val observed = mutableListOf<RecordingTileSnapshot>()
        val first = recordingTileRefreshTask({ latest }, observed::add)
        latest = running.copy(activeBuffer = ReverbService.BufferSlot.ONE_SHOT, commandGeneration = 2L)
        val second = recordingTileRefreshTask({ latest }, observed::add)
        latest = latest.copy(oneShotSeconds = 33f, loopingSeconds = 25f, oneShotFull = true, commandGeneration = 3L)
        second.run()
        first.run()
        assertEquals(2, observed.size)
        observed.forEach { assertSame(latest, it) }
    }
}
