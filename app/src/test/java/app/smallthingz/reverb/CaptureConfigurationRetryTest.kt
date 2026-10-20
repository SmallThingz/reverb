package app.smallthingz.reverb

import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureConfigurationRetryTest {
    @Test
    fun initialAndReconfigurationFailuresRecoverOnlyOnAnExplicitOwnedTap() {
        for (initiallyReady in listOf(false, true)) {
            val fixture = RetryFixture(initiallyReady)
            fixture.focusReturns()
            assertEquals(0, fixture.attempts)
            assertEquals(CaptureResolvedBufferState(false, false, false), fixture.visibleBuffers())

            assertTrue(fixture.tap())
            assertEquals(0, fixture.attempts)
            assertTrue(fixture.admission.isActive())
            fixture.drain()

            assertEquals(1, fixture.attempts)
            assertEquals(1, fixture.starts)
            assertTrue(fixture.ready)
            assertTrue(fixture.durableIntent)
            assertFalse(fixture.blocked)
            assertFalse(fixture.admission.isActive())
            assertEquals(listOf(CaptureBlobTapAction.START), fixture.delivered.map { it.result })
            assertNull(fixture.delivered.single().errorMessage)
        }
    }

    @Test
    fun initializationAndOtherOwnersCannotAdmitAConfigurationRetry() {
        for (condition in listOf("initializing", "unadmitted", "listening", "destroying", "other work")) {
            val fixture = RetryFixture(false)
            when (condition) {
                "initializing" -> fixture.initializing = true
                "unadmitted" -> fixture.storesAdmitted = false
                "listening" -> fixture.listening = true
                "destroying" -> fixture.destroying = true
                "other work" -> fixture.otherWork = true
            }
            assertFalse(condition, fixture.tap())
            fixture.drain()
            assertEquals(condition, 0, fixture.attempts)
            assertEquals(condition, 0, fixture.starts)
            assertFalse(fixture.admission.isActive())
        }
    }

    @Test
    fun workAcceptedAfterTheTapWinsBeforeTheQueuedRetryBegins() {
        val fixture = RetryFixture(true)
        assertTrue(fixture.tap())
        fixture.otherWork = true
        fixture.drain()
        assertEquals(0, fixture.attempts)
        assertEquals(0, fixture.starts)
        assertTrue(fixture.blocked)
        assertEquals(CaptureBlobTapAction.NONE, fixture.delivered.single().result)
        assertFalse(fixture.admission.isActive())
    }

    @Test
    fun neededStopDoesNotDependOnReconfiguration() {
        val fixture = RetryFixture(true)
        fixture.blocked = false
        fixture.listening = true
        fixture.durableIntent = true
        fixture.otherWork = true
        assertTrue(fixture.tap())
        fixture.drain()
        assertEquals(0, fixture.attempts)
        assertEquals(CaptureBlobTapAction.STOP, fixture.delivered.single().result)
        assertFalse(fixture.durableIntent)
        assertFalse(fixture.listening)
    }

    @Test
    fun failureAfterConfigurationCannotPublishPartialReadiness() {
        val fixture = RetryFixture(true)
        fixture.failAfterConfiguration = true
        assertTrue(fixture.tap())
        fixture.drain()
        assertEquals(1, fixture.attempts)
        assertFalse(fixture.ready)
        assertEquals(CaptureResolvedBufferState(false, false, false), fixture.visibleBuffers())
        assertEquals(0, fixture.starts)
        assertFalse(fixture.durableIntent)
        assertEquals("Storage still unavailable", fixture.delivered.single().errorMessage)

        fixture.failAfterConfiguration = false
        assertTrue(fixture.tap())
        fixture.drain()
        assertEquals(2, fixture.attempts)
        assertEquals(1, fixture.starts)
        assertTrue(fixture.ready)
    }

    @Test
    fun repeatedFailingTapsHaveOneAttemptAndFeedbackPerAcceptedTicket() {
        val fixture = RetryFixture(false)
        fixture.failBeforeConfiguration = true
        repeat(3) {
            assertTrue(fixture.tap())
            assertFalse(fixture.tap()) // The first ticket owns this retry until its tail settles.
            fixture.drain()
            fixture.focusReturns()
            assertEquals(it + 1, fixture.attempts)
            assertEquals(it + 1, fixture.delivered.size)
            assertFalse(fixture.admission.isActive())
        }
        assertEquals(0, fixture.starts)
        assertTrue(fixture.blocked)
        assertFalse(fixture.ready)
        assertTrue(fixture.delivered.all { it.errorMessage == "Storage still unavailable" })
        assertTrue(fixture.processErrors.isEmpty())
        assertNull(fixture.errors.consumePending())
        fixture.drain()
        assertEquals(3, fixture.attempts)
    }

    @Test
    fun realDisabledBuffersStayDisabledWithoutChangingCaptureIntent() {
        for (priorIntent in listOf(false, true)) {
            val fixture = RetryFixture(false)
            fixture.durableIntent = priorIntent
            fixture.configuredOneShotEnabled = false
            fixture.configuredLoopingEnabled = false
            assertTrue(fixture.tap())
            fixture.drain()
            assertTrue(fixture.ready)
            assertEquals(CaptureResolvedBufferState(false, false, false), fixture.visibleBuffers())
            assertEquals(priorIntent, fixture.durableIntent)
            assertEquals(0, fixture.starts)
            assertEquals(CaptureBlobTapAction.NONE, fixture.delivered.single().result)
            // Resolved Off is the existing Settings route, not another storage retry.
            assertFalse(fixture.configurationUnavailable())
            assertFalse(fixture.tap())
            assertEquals(1, fixture.attempts)
        }
    }

    @Test
    fun acceptedRetryFailureSurvivesItsUiOwner() {
        val fixture = RetryFixture(false)
        fixture.failBeforeConfiguration = true
        assertTrue(fixture.tap())
        fixture.ui.retire()
        fixture.drain()
        assertTrue(fixture.delivered.isEmpty())
        assertEquals(listOf("Storage still unavailable"), fixture.processErrors)
        assertFalse(fixture.admission.isActive())
        assertEquals(1, fixture.attempts)
    }

    @Test
    fun retryingADisabledBufferDoesNotStartTheOtherEnabledBuffer() {
        val fixture = RetryFixture(false)
        fixture.configuredLoopingEnabled = false
        assertTrue(fixture.tap())
        fixture.drain()
        assertEquals(CaptureResolvedBufferState(true, false, false), fixture.visibleBuffers())
        assertEquals(CaptureBlobTapAction.NONE, fixture.delivered.single().result)
        assertEquals(0, fixture.starts)
        assertFalse(fixture.durableIntent)
        assertFalse(fixture.configurationUnavailable())
    }

    private class RetryFixture(var ready: Boolean) {
        val admission = CaptureCommandAdmission()
        val ui = CaptureCommandUiOwner<CaptureCommandOutcome<CaptureBlobTapAction>>()
        val errors = RecorderErrorState()
        val delivered = mutableListOf<CaptureCommandOutcome<CaptureBlobTapAction>>()
        val processErrors = mutableListOf<String>()
        private val audio = ArrayDeque<Runnable>()
        private val main = ArrayDeque<Runnable>()
        var mode: RetentionMode? = null
        var blocked = true
        var initializing = false
        var storesAdmitted = true
        var listening = false
        var destroying = false
        var otherWork = false
        var configuredOneShotEnabled = true
        var configuredLoopingEnabled = true
        var durableIntent = false
        var failBeforeConfiguration = false
        var failAfterConfiguration = false
        var attempts = 0
        var starts = 0

        fun configurationUnavailable() = !ready || mode == null

        fun mayRetry(ownsCommand: Boolean = false) = captureConfigurationRetryMayRun(
            configurationUnavailable = configurationUnavailable(),
            persistenceFailureBlocked = blocked,
            storesAdmitted = storesAdmitted,
            initializationPending = initializing,
            recorderListening = listening,
            serviceDestroying = destroying,
            otherWorkPending = otherWork || (!ownsCommand && admission.isActive()),
        )

        fun visibleBuffers() = captureResolvedBufferState(
            mode.takeIf { ready }, configuredOneShotEnabled, false, configuredLoopingEnabled,
        )

        fun tap(): Boolean {
            val visible = visibleBuffers()
            val action = captureBlobTapAction(
                ReverbService.BufferSlot.LOOPING, listening, ReverbService.BufferSlot.LOOPING,
                visible.oneShotEnabled, visible.oneShotFull, visible.loopingEnabled,
            )
            if (!captureBlobCommandMaySubmit(action, mayRetry())) return false
            if (!ready && !mayRetry()) return false
            return ui.submit(
                transfer = { feedback ->
                    admission.submit(
                        retainLifetime = { true },
                        enqueue = { audio.add(it) },
                        onIdle = {},
                    ) {
                        val outcome = errors.captureCommand {
                            if (!listening && configurationUnavailable()) {
                                if (!mayRetry(ownsCommand = true)) return@captureCommand CaptureBlobTapAction.NONE
                                if (!retryCaptureConfiguration(
                                        resetReadiness = { ready = false; mode = null },
                                        configure = {
                                            assertFalse(ready)
                                            attempts++
                                            if (failBeforeConfiguration) throw IOException("Storage still unavailable")
                                            mode = RetentionMode.TIME
                                            if (failAfterConfiguration) throw IOException("Storage still unavailable")
                                        },
                                        publishReadiness = { ready = true },
                                        onFailure = {
                                            blocked = true
                                            errors.reportTerminalCaptureFailure(requireNotNull(it.message), processErrors::add)
                                        },
                                    )
                                ) return@captureCommand CaptureBlobTapAction.NONE
                            }
                            val resolved = captureBlobTapAction(
                                ReverbService.BufferSlot.LOOPING, listening, ReverbService.BufferSlot.LOOPING,
                                configuredOneShotEnabled, false, configuredLoopingEnabled,
                            )
                            if (resolved == CaptureBlobTapAction.START) {
                                starts++
                                durableIntent = true
                                blocked = false
                                listening = true
                            } else if (resolved == CaptureBlobTapAction.STOP) {
                                durableIntent = false
                                listening = false
                            }
                            resolved
                        }
                        feedback.postIfCurrent(
                            outcome, { main.add(it) }, { !destroying },
                            onUndelivered = { it.errorMessage?.let(processErrors::add) },
                        )
                    }
                },
                onResult = { delivered += it; true },
            )
        }

        fun focusReturns() {
            assertFalse(shouldRetrySuspendedListeningWithForegroundUi(
                durableIntent, destroying, true, false, false, blocked, true,
            ))
        }

        fun drain() {
            while (audio.isNotEmpty()) audio.removeFirst().run()
            while (main.isNotEmpty()) main.removeFirst().run()
        }
    }
}
