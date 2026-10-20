package app.smallthingz.reverb

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCommandSettlementLifetimeTest {
    @Test
    fun failedSwitchSettlesAfterMainObservedItsStillActiveCleanupTicket() {
        val queues = SettlementQueues()
        queues.runFailureThroughEarlyMainStop()

        assertTrue(queues.admission.isActive())
        assertTrue(queues.listeningIntentEnabled)
        assertFalse(queues.inputHeld)
        assertEquals(1, queues.deferredStopRequests)
        assertEquals(emptyList<Int>(), queues.stopAttempts)

        queues.finishTicketAndSettle()

        assertFalse(queues.admission.isActive())
        assertTrue(queues.listeningIntentEnabled)
        assertEquals(listOf(7), queues.stopAttempts)
        assertEquals(1, queues.foregroundRemovals)
    }

    @Test
    fun foregroundRestrictionWithPreservedIntentAlsoSettlesAtLastTicket() {
        val queues = SettlementQueues()
        queues.runFailureThroughEarlyMainStop()
        queues.persistenceFailureBlocked = false
        queues.foregroundStartBlocked = true

        queues.finishTicketAndSettle()

        assertTrue(queues.listeningIntentEnabled)
        assertEquals(listOf(7), queues.stopAttempts)
        assertEquals(1, queues.foregroundRemovals)
    }

    @Test
    fun lastTicketRetainsHealthyPendingAndEveryOtherServiceOwner() {
        for (owner in Owner.values()) {
            val queues = SettlementQueues()
            queues.runFailureThroughEarlyMainStop()
            queues.installOwner(owner)

            queues.finishTicketAndSettle()

            assertFalse(queues.admission.isActive())
            assertEquals(owner != Owner.STOP_TAIL, queues.listeningIntentEnabled)
            assertEquals(owner.name, emptyList<Int>(), queues.stopAttempts)
            assertEquals(owner.name, 0, queues.foregroundRemovals)
        }
    }

    @Test
    fun aNewerCommandTicketRetainsLifetimeUntilItsOwnCleanupPasses() {
        val queues = SettlementQueues()
        queues.runFailureThroughEarlyMainStop()
        assertTrue(queues.admission.submit(
            retainLifetime = { true },
            enqueue = { queues.audio.add(it) },
            onIdle = { queues.main.add(Runnable { queues.settleLastTicket() }) },
        ) {
            queues.installOwner(Owner.HEALTHY_CAPTURE)
        })

        // The first completion precedes the new command, but may not schedule idle settlement.
        queues.audio.removeFirst().run()
        assertTrue(queues.admission.isActive())
        assertTrue(queues.main.isEmpty())
        queues.audio.removeFirst().run()
        queues.audio.removeFirst().run()
        queues.main.removeFirst().run()

        assertFalse(queues.admission.isActive())
        assertEquals(emptyList<Int>(), queues.stopAttempts)
        assertEquals(0, queues.foregroundRemovals)
    }

    @Test
    fun refusedOldStartIdCannotRemoveNewerStartedWorksForeground() {
        val queues = SettlementQueues()
        queues.runFailureThroughEarlyMainStop()
        queues.platformLatestStartId = 8

        queues.finishTicketAndSettle()

        assertTrue(queues.listeningIntentEnabled)
        assertEquals(listOf(7), queues.stopAttempts)
        assertEquals(0, queues.foregroundRemovals)

        // Delivery of that later command keepalive retries with the latest owned start ID.
        queues.commandStartId = 8
        queues.settleLastTicket()
        assertEquals(listOf(7, 8), queues.stopAttempts)
        assertEquals(1, queues.foregroundRemovals)
    }

    private enum class Owner {
        HEALTHY_CAPTURE,
        PENDING_CAPTURE,
        STOP_TAIL,
        EXPORT,
        CLEAR,
        SETTINGS,
        RETENTION,
        DESTROYING,
    }

    private class SettlementQueues {
        val audio = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val admission = CaptureCommandAdmission()
        var listeningIntentEnabled = true
        var recorderState = ReverbService.STATE_LISTENING
        var captureInitializationPending = false
        var foregroundStartBlocked = false
        var persistenceFailureBlocked = false
        var exportActive = false
        var bufferClearActive = false
        var settingsRuntimeActive = false
        var retentionActive = false
        var serviceDestroying = false
        var inputHeld = true
        var deferredStopRequests = 0
        var commandStartId = 7
        var platformLatestStartId = 7
        val stopAttempts = mutableListOf<Int>()
        var foregroundRemovals = 0

        fun runFailureThroughEarlyMainStop() {
            assertTrue(admission.submit(
                retainLifetime = { true },
                enqueue = { audio.add(it) },
                onIdle = { main.add(Runnable { settleLastTicket() }) },
            ) {
                // Postcondition of the production switch's failed commit + uncertain rollback:
                // retain retry intent, pause input, and enqueue its cleanup before ticket completion.
                recorderState = ReverbService.STATE_PAUSED
                persistenceFailureBlocked = true
                audio.add(Runnable {
                    inputHeld = false
                    main.add(Runnable {
                        // requestServiceStopWhenExportIdle returns at this exact early boundary.
                        if (admission.isActive()) deferredStopRequests++ else settleLastTicket()
                    })
                })
            })
            audio.removeFirst().run() // Accepted command; queues cleanup then ticket completion.
            assertEquals(2, audio.size)
            audio.removeFirst().run() // Cleanup; queues the earlier main stop request.
            assertTrue(admission.isActive())
            main.removeFirst().run() // Main wins before the audio ticket completion.
            assertEquals(1, deferredStopRequests)
            assertTrue(main.isEmpty())
        }

        fun finishTicketAndSettle() {
            audio.removeFirst().run()
            assertFalse(admission.isActive())
            main.removeFirst().run()
            assertTrue(audio.isEmpty())
            assertTrue(main.isEmpty())
        }

        fun settleLastTicket() {
            if (admission.isActive()) return
            if (!quickTileStartedLifetimeMayStop(
                    serviceDestroying = serviceDestroying,
                    listeningIntentEnabled = listeningIntentEnabled,
                    recorderState = recorderState,
                    captureInitializationPending = captureInitializationPending,
                    foregroundStartBlocked = foregroundStartBlocked,
                    foregroundServiceTimedOut = false,
                    persistenceFailureBlocked = persistenceFailureBlocked,
                    exportActive = exportActive,
                    bufferClearActive = bufferClearActive,
                    settingsRuntimeActive = settingsRuntimeActive,
                    retentionActive = retentionActive,
                )
            ) return
            releaseStartedServiceIfCurrent(
                startId = commandStartId,
                stopSelfResult = {
                    stopAttempts += it
                    it == platformLatestStartId
                },
                stopForeground = { foregroundRemovals++ },
            )
        }

        fun installOwner(owner: Owner) {
            when (owner) {
                Owner.HEALTHY_CAPTURE -> {
                    recorderState = ReverbService.STATE_LISTENING
                    persistenceFailureBlocked = false
                    inputHeld = true
                }
                Owner.PENDING_CAPTURE -> {
                    recorderState = ReverbService.STATE_READY
                    captureInitializationPending = true
                    persistenceFailureBlocked = false
                }
                Owner.STOP_TAIL -> {
                    // Retain a still-listening native owner even before its disabled intent settles.
                    listeningIntentEnabled = false
                    recorderState = ReverbService.STATE_LISTENING
                    inputHeld = true
                }
                Owner.EXPORT -> exportActive = true
                Owner.CLEAR -> bufferClearActive = true
                Owner.SETTINGS -> settingsRuntimeActive = true
                Owner.RETENTION -> retentionActive = true
                Owner.DESTROYING -> serviceDestroying = true
            }
        }
    }
}
