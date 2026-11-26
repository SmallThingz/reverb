package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun periodicWakeBurstQueuesOneRenderOfTheLatestState() {
        var latest = running
        val pending = ArrayDeque<Runnable>()
        val delivered = mutableListOf<RecordingTileSnapshot>()
        val refresh = PeriodicRecordingTileRefresh(pending::add,
            recordingTileRefreshTask({ latest }, delivered::add))

        repeat(4) { tick ->
            latest = running.copy(loopingSeconds = 11f + tick)
            refresh.schedule()
        }
        latest = latest.copy(listening = false, commandGeneration = 2L)

        assertEquals(1, pending.size)
        assertTrue(delivered.isEmpty())
        pending.removeFirst().run()
        assertSame(latest, delivered.single())
        assertTrue(pending.isEmpty())
    }

    @Test
    fun periodicPublicationDuringRenderSchedulesAnotherWake() {
        val pending = ArrayDeque<Runnable>()
        val delivered = mutableListOf<RecordingTileSnapshot>()
        val next = running.copy(oneShotSeconds = 3f)
        var latest = running
        lateinit var refresh: PeriodicRecordingTileRefresh
        refresh = PeriodicRecordingTileRefresh(pending::add, recordingTileRefreshTask({ latest }) { snapshot ->
            delivered.add(snapshot)
            if (snapshot === running) {
                latest = next
                refresh.schedule()
            }
        })

        refresh.schedule()
        pending.removeFirst().run()
        assertEquals(listOf(running), delivered)
        pending.removeFirst().run()
        assertEquals(listOf(running, next), delivered)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun rejectedPeriodicEnqueueDoesNotKeepTheWakeClaimed() {
        val pending = ArrayDeque<Runnable>()
        var acceptsPosts = false
        var deliveries = 0
        val refresh = PeriodicRecordingTileRefresh({ acceptsPosts && pending.add(it) }, Runnable { deliveries++ })

        refresh.schedule()
        assertTrue(pending.isEmpty())
        acceptsPosts = true
        refresh.schedule()
        pending.removeFirst().run()
        assertEquals(1, deliveries)
    }

    @Test
    fun delayedStoppedOrUnrelatedWakeUpKeepsTheNewLiveHandoff() {
        for (oldSnapshot in listOf(running.copy(listening = false), running.copy(activeBuffer = null))) {
            val oldOwner = Any()
            RecordingQuickTileStateCache.beginRuntime(oldOwner)
            assertTrue(RecordingQuickTileStateCache.publish(oldOwner, oldSnapshot))
            lateinit var handoff: RecordingTileHandoff
            var retire = true
            var refreshSource = false
            var delivered: RecordingTileSnapshot? = null
            // The accepted old publication is delayed before dispatch. The wake-up must not
            // carry its snapshot into bookkeeping for the handoff installed below.
            val delayedDispatch = Runnable {
                RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
                    retire = handoff.shouldRetireFor(current, generation)
                    refreshSource = handoff.awaitsSourceRefresh(current, generation)
                    delivered = current
                }
            }

            val liveOwner = Any()
            RecordingQuickTileStateCache.beginRuntime(liveOwner)
            assertTrue(RecordingQuickTileStateCache.publish(liveOwner, running))
            handoff = RecordingQuickTileStateCache.withCurrentSnapshot { _, generation ->
                RecordingTileHandoff(ReverbService.BufferSlot.LOOPING, ReverbService.BufferSlot.ONE_SHOT, 1L, generation, 2L)
            }
            val target = running.copy(activeBuffer = ReverbService.BufferSlot.ONE_SHOT, commandGeneration = 2L)
            assertTrue(RecordingQuickTileStateCache.publish(liveOwner, target))
            delayedDispatch.run()

            assertFalse(retire)
            assertTrue(refreshSource)
            assertSame(target, delivered)
        }
    }

    @Test
    fun stoppedCacheFromBeforeMarkerInstallationCannotRetireTheMarker() {
        val owner = Any()
        RecordingQuickTileStateCache.beginRuntime(owner)
        assertTrue(RecordingQuickTileStateCache.publish(owner, running.copy(listening = false)))
        val handoff = RecordingQuickTileStateCache.withCurrentSnapshot { _, generation ->
            RecordingTileHandoff(ReverbService.BufferSlot.LOOPING, ReverbService.BufferSlot.ONE_SHOT, 1L, generation, 2L)
        }
        RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
            assertFalse(handoff.shouldRetireFor(current, generation))
            assertFalse(handoff.awaitsSourceRefresh(current, generation))
        }
        assertTrue(RecordingQuickTileStateCache.publish(owner,
            running.copy(activeBuffer = ReverbService.BufferSlot.ONE_SHOT, commandGeneration = 2L)))
        RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
            assertFalse(handoff.shouldRetireFor(current, generation))
            assertTrue(handoff.awaitsSourceRefresh(current, generation))
        }
    }

    @Test
    fun currentStopOrUnrelatedDestinationRetiresTheHandoff() {
        for (terminal in listOf(running.copy(listening = false, commandGeneration = 2L),
            running.copy(activeBuffer = null, commandGeneration = 2L))) {
            val owner = Any()
            RecordingQuickTileStateCache.beginRuntime(owner)
            assertTrue(RecordingQuickTileStateCache.publish(owner, running))
            val handoff = RecordingQuickTileStateCache.withCurrentSnapshot { _, generation ->
                RecordingTileHandoff(ReverbService.BufferSlot.LOOPING, ReverbService.BufferSlot.ONE_SHOT, 1L, generation, 2L)
            }
            assertTrue(RecordingQuickTileStateCache.publish(owner,
                running.copy(activeBuffer = ReverbService.BufferSlot.ONE_SHOT, commandGeneration = 2L)))
            assertTrue(RecordingQuickTileStateCache.publish(owner, terminal))
            RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
                assertTrue(handoff.shouldRetireFor(current, generation))
                assertFalse(handoff.awaitsSourceRefresh(current, generation))
            }
        }
    }

    @Test
    fun sourceRefreshRequiresATargetPublicationAfterTheMarker() {
        val owner = Any()
        RecordingQuickTileStateCache.beginRuntime(owner)
        val target = running.copy(activeBuffer = ReverbService.BufferSlot.ONE_SHOT, commandGeneration = 2L)
        assertTrue(RecordingQuickTileStateCache.publish(owner, target))
        val handoff = RecordingQuickTileStateCache.withCurrentSnapshot { _, generation ->
            RecordingTileHandoff(ReverbService.BufferSlot.LOOPING, ReverbService.BufferSlot.ONE_SHOT, 1L, generation, 2L)
        }
        RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
            assertFalse(handoff.shouldRetireFor(current, generation))
            assertFalse(handoff.awaitsSourceRefresh(current, generation))
        }
        assertTrue(RecordingQuickTileStateCache.publish(owner, running.copy(commandGeneration = 2L)))
        RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
            assertFalse(handoff.shouldRetireFor(current, generation))
            assertFalse(handoff.awaitsSourceRefresh(current, generation))
        }
        assertTrue(RecordingQuickTileStateCache.publish(owner, target))
        RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
            assertFalse(handoff.shouldRetireFor(current, generation))
            assertTrue(handoff.awaitsSourceRefresh(current, generation))
        }
    }
}
