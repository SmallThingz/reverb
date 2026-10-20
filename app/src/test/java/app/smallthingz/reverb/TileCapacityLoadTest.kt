package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCapacityLoadTest {
    @Test fun bufferRoundTripsUseOneSnapshotWithoutRemovingTheWheelOrReloadingLimits() {
        val load = TileCapacityLoad()
        val target = QuickSettingsTileEditorTarget()
        val snapshot = TileCapacities(3_600, loopingSeconds = 300.0, oneShotSeconds = 90.0)
        val request = load.begin(7L)!!
        assertTrue(load.finish(request, 7L, snapshot))

        repeat(3) {
            assertTrue(target.selectBuffer(ReverbService.BufferSlot.ONE_SHOT))
            assertEquals(TileCapacity(3_600, 90.0), load.ready(7L)!!.forBuffer(target.buffer))
            assertSame(snapshot, load.visible)
            assertTrue(target.selectBuffer(ReverbService.BufferSlot.LOOPING))
            assertEquals(TileCapacity(3_600, 300.0), load.ready(7L)!!.forBuffer(target.buffer))
            assertSame(snapshot, load.visible)
        }
        assertTrue(load.isCurrent(request, 7L))
    }

    @Test fun changedSettingsRetainGeometryButCannotAuthorizeSaveBeforeTheirReadCompletes() {
        val load = TileCapacityLoad()
        val previous = TileCapacities(3_600, 300.0, 90.0)
        val current = TileCapacities(1_800, 60.0, null)
        val oldRequest = load.begin(7L)!!
        assertTrue(load.finish(oldRequest, 7L, previous))
        assertNull(load.ready(8L)) // New parent revision rejects input even before its effect runs.

        val newRequest = load.begin(8L)!!
        assertSame(previous, load.visible)
        assertNull(load.ready(7L))
        assertNull(load.ready(8L))
        assertFalse(load.finish(oldRequest, 8L, previous))
        assertTrue(load.finish(newRequest, 8L, current))
        assertSame(current, load.ready(8L))
        assertFalse(load.finish(oldRequest, 7L, previous))
        assertSame(current, load.visible)
        assertNull(load.ready(8L)!!.forBuffer(ReverbService.BufferSlot.ONE_SHOT).retainedSeconds)
    }

    @Test fun delayedReadCannotReplaceNewerReadOfTheSameSettingsRevision() {
        val load = TileCapacityLoad()
        val first = load.begin(7L)!!
        val second = load.begin(7L)!!
        val current = TileCapacities(1_800, 60.0, 45.0)
        assertTrue(load.finish(second, 7L, current))
        assertFalse(load.finish(first, 7L, TileCapacities(3_600, 300.0, 90.0)))
        assertSame(current, load.ready(7L))
    }

    @Test fun failedRefreshOrLostOwnerKeepsOnlyTheVisualSnapshot() {
        val load = TileCapacityLoad()
        val snapshot = TileCapacities(3_600, 300.0, 90.0)
        val first = load.begin(7L)!!
        assertTrue(load.finish(first, 7L, snapshot))
        val refresh = load.begin(7L)!!
        // A failed IO read never calls finish: the wheel remains mounted but cannot save.
        assertSame(snapshot, load.visible)
        assertNull(load.ready(7L))
        assertNull(load.begin(null))
        assertFalse(load.finish(refresh, 7L, snapshot))
        assertSame(snapshot, load.visible)
        assertNull(load.ready(null))
        assertNull(load.ready(7L))
    }
}
