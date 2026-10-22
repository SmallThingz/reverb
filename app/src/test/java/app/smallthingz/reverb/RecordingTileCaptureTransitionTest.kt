package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTileCaptureTransitionTest {
    private val source = ReverbService.BufferSlot.LOOPING
    private val target = ReverbService.BufferSlot.ONE_SHOT

    private fun snapshot(
        inputGeneration: Long,
        commandGeneration: Long = 2L,
        captureAllowed: Boolean = true,
        nativeInputRecording: Boolean = true,
        activeBuffer: ReverbService.BufferSlot = target,
    ): RecordingTileSnapshot {
        val state = recordingTileCaptureState(captureAllowed, nativeInputRecording, inputGeneration, commandGeneration)
        return recordingTileSnapshot(
            listeningIntentEnabled = captureAllowed,
            runtimeCaptureActive = state == RecordingTileCaptureState.RECORDING,
            activeBuffer = activeBuffer,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
            commandGeneration = commandGeneration,
            captureGenerationPending = state == RecordingTileCaptureState.GENERATION_PENDING,
        )
    }

    private class PublicationSequence(source: ReverbService.BufferSlot, target: ReverbService.BufferSlot) {
        private val owner = Any()
        var handoff: RecordingTileHandoff?
            private set

        init {
            RecordingQuickTileStateCache.beginRuntime(owner)
            assertTrue(RecordingQuickTileStateCache.publish(owner, RecordingTileSnapshot(
                true, source, true, false, true, commandGeneration = 1L,
            )))
            handoff = RecordingQuickTileStateCache.withCurrentSnapshot { _, generation ->
                RecordingTileHandoff(source, target, 1L, generation, 2L)
            }
        }

        fun publish(snapshot: RecordingTileSnapshot, currentCommand: Long = snapshot.commandGeneration,
                    destroying: Boolean = false): Boolean {
            if (!recordingTileSnapshotMayPublish(destroying, snapshot.commandGeneration, currentCommand)) return false
            assertTrue(RecordingQuickTileStateCache.publish(owner, snapshot))
            RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
                if (handoff?.shouldRetireFor(current, generation) == true) handoff = null
            }
            return true
        }

        fun sourceRefreshRequired(): Boolean = RecordingQuickTileStateCache.withCurrentSnapshot { current, generation ->
            handoff?.awaitsSourceRefresh(current, generation) == true
        }

        fun acknowledge(buffer: ReverbService.BufferSlot, rendered: RecordingTileSnapshot) {
            if (handoff?.acknowledgesSource(buffer, rendered) == true) handoff = null
        }
    }

    @Test
    fun immediateQuickSettingsPublicationPreservesHandoffForSourceAcknowledgement() {
        val sequence = PublicationSequence(source, target)
        // selectCaptureBuffer accepted generation 2; its queued adoption has not run yet.
        val pending = snapshot(inputGeneration = 1L)
        assertFalse(pending.listening)
        assertTrue(pending.captureGenerationPending)
        assertTrue(sequence.publish(pending))
        assertNotNull(sequence.handoff)
        assertFalse(sequence.sourceRefreshRequired())

        val adopted = snapshot(inputGeneration = 2L)
        assertTrue(sequence.publish(adopted))
        assertTrue(sequence.sourceRefreshRequired())
        val handoff = requireNotNull(sequence.handoff)
        assertTrue(handoff.suppressesRecordingState(target, adopted))
        assertFalse(handoff.acknowledgesSource(target, adopted))
        assertTrue(handoff.acknowledgesSource(source, adopted))
    }

    @Test
    fun sourceAlreadyRenderedInactiveDuringPendingDoesNotNeedAnotherAcknowledgement() {
        val sequence = PublicationSequence(source, target)
        val pending = snapshot(inputGeneration = 1L)
        sequence.publish(pending)
        sequence.acknowledge(target, pending)
        assertNotNull(sequence.handoff)
        sequence.acknowledge(source, pending)
        assertNull(sequence.handoff)
        val adopted = snapshot(inputGeneration = 2L)
        sequence.publish(adopted)
        assertNull(sequence.handoff)
        assertFalse(sequence.sourceRefreshRequired())
        assertEquals(RecordingTileUiState.RECORDING, recordingTileUiState(target, adopted))
    }

    @Test
    fun periodicPublicationsBetweenMainThreadSelectionAndQueuedAdoptionKeepTheSameMarker() {
        val sequence = PublicationSequence(source, target)
        val accepted = requireNotNull(sequence.handoff)
        repeat(4) { index ->
            assertTrue(sequence.publish(snapshot(inputGeneration = 1L).copy(loopingSeconds = index.toFloat())))
            assertTrue(sequence.handoff === accepted)
            assertFalse(sequence.sourceRefreshRequired())
        }
        val adopted = snapshot(inputGeneration = 2L)
        sequence.publish(adopted)
        assertTrue(sequence.handoff === accepted)
        assertTrue(accepted.suppressesRecordingState(target, adopted))
    }

    @Test
    fun stopFailureMissingInputAndNonRecordingMismatchRetirePendingHandoff() {
        for (terminal in listOf(
            snapshot(1L, captureAllowed = false),
            snapshot(1L, nativeInputRecording = false),
            snapshot(Long.MIN_VALUE),
            failClosedRecordingTileSnapshot(snapshot(1L)).copy(commandGeneration = 3L),
        )) {
            val sequence = PublicationSequence(source, target)
            sequence.publish(snapshot(1L))
            assertFalse(terminal.listening)
            assertFalse(terminal.captureGenerationPending)
            sequence.publish(terminal)
            assertNull(sequence.handoff)
        }
    }

    @Test
    fun newerCommandCannotBorrowOrAcknowledgeAnOlderHandoff() {
        val sequence = PublicationSequence(source, target)
        val old = requireNotNull(sequence.handoff)
        sequence.publish(snapshot(1L))
        val newerPending = snapshot(1L, commandGeneration = 3L, activeBuffer = source)
        sequence.publish(newerPending)
        assertNull(sequence.handoff)
        val newerLive = snapshot(3L, commandGeneration = 3L)
        assertFalse(old.suppressesRecordingState(target, newerLive))
        assertFalse(old.acknowledgesSource(source, newerLive))

        val replacement = RecordingTileHandoff(target, source, 2L, 10L, 3L)
        assertFalse(replacement.shouldRetireFor(newerPending, 11L))
        assertFalse(replacement.acknowledgesSource(target, snapshot(2L)))
    }

    @Test
    fun commandReplacementOrTeardownBetweenSamplingAndPublicationRejectsOldSample() {
        val sequence = PublicationSequence(source, target)
        val marker = requireNotNull(sequence.handoff)
        val beforeSelection = snapshot(1L, commandGeneration = 1L, activeBuffer = source)
        assertFalse(sequence.publish(beforeSelection, currentCommand = 2L))
        assertTrue(sequence.handoff === marker)
        assertFalse(sequence.publish(snapshot(1L), destroying = true))
        assertTrue(sequence.handoff === marker)
        sequence.publish(failClosedRecordingTileSnapshot().copy(commandGeneration = 2L))
        assertNull(sequence.handoff)
    }

    @Test
    fun stoppedAndFallbackSnapshotsNeverCarryPendingAdoptionAuthority() {
        val pending = snapshot(1L)
        assertFalse(stoppedRecordingTileSnapshot(pending, pending).captureGenerationPending)
        assertFalse(runtimeRecordingTileFallbackSnapshot(pending, target, true, true).captureGenerationPending)
        assertFalse(failClosedRecordingTileSnapshot(pending).captureGenerationPending)
        assertEquals(RecordingTileCaptureState.RECORDING,
            recordingTileCaptureState(true, true, 2L, 2L))
    }

    private fun beginObservedHandoff(controller: RecordingTileHandoffController): RecordingTileHandoff {
        controller.begin(source, target, cacheGeneration = 1L, commandGeneration = 2L)
        return requireNotNull(controller.pending)
    }

    @Test
    fun explicitlyRemovedSourceBeforeHandoffNeedsOneTargetRefreshAndNoRetry() {
        val controller = RecordingTileHandoffController()
        val sourceObservation = controller.beginObservation(source)
        assertTrue(controller.observePresent(sourceObservation))
        assertNull(controller.observeRemoved(sourceObservation)) // No pending handoff yet.
        controller.endObservation(sourceObservation)
        assertTrue(controller.sourceWasRemoved(source))
        assertFalse(controller.observePresent(sourceObservation)) // Destroyed callbacks are revoked.

        val handoff = beginObservedHandoff(controller)
        assertEquals(RecordingTileHandoffRefresh.TARGET_ONLY,
            controller.nextRefresh(handoff, snapshot(2L), 2L))
        assertEquals(0, handoff.sourceRefreshAttempts)
        assertNull(controller.pending)
        assertNull(controller.nextRefresh(handoff, snapshot(2L), 2L))
    }

    @Test
    fun removalBeforeCaptureAdoptionWaitsForCurrentTargetPublication() {
        val controller = RecordingTileHandoffController()
        val sourceObservation = controller.beginObservation(source)
        val handoff = beginObservedHandoff(controller)
        assertTrue(controller.observeRemoved(sourceObservation) === handoff)
        assertNull(controller.nextRefresh(handoff, snapshot(1L), 2L))
        assertTrue(controller.pending === handoff)
        assertEquals(0, handoff.sourceRefreshAttempts)
        assertEquals(RecordingTileHandoffRefresh.TARGET_ONLY,
            controller.nextRefresh(handoff, snapshot(2L), 3L))
    }

    @Test
    fun removalDuringSourceRetrySettlesMatchingHandoffAndQueuedRetryBecomesNoOp() {
        val controller = RecordingTileHandoffController()
        val sourceObservation = controller.beginObservation(source)
        controller.observePresent(sourceObservation)
        val handoff = beginObservedHandoff(controller)
        assertEquals(RecordingTileHandoffRefresh.SOURCE_RETRY,
            controller.nextRefresh(handoff, snapshot(2L), 2L))
        val queuedRetry = { controller.nextRefresh(handoff, snapshot(2L), 2L) }

        assertTrue(controller.observeRemoved(sourceObservation) === handoff)
        assertEquals(RecordingTileHandoffRefresh.TARGET_ONLY,
            controller.nextRefresh(handoff, snapshot(2L), 2L))
        assertNull(queuedRetry())
        assertEquals(1, handoff.sourceRefreshAttempts)
        assertNull(controller.pending)
    }

    @Test
    fun newIncarnationRevokesRemovedProofAndRejectsAllOlderLifecycleCallbacks() {
        val controller = RecordingTileHandoffController()
        val old = controller.beginObservation(source)
        val handoff = beginObservedHandoff(controller)
        controller.observeRemoved(old)
        val queuedRefresh = { controller.nextRefresh(handoff, snapshot(2L), 2L) }

        val replacement = controller.beginObservation(source)
        assertFalse(controller.sourceWasRemoved(source))
        assertNull(controller.observeRemoved(old))
        assertFalse(controller.observePresent(old))
        controller.endObservation(old)
        assertTrue(controller.isCurrent(replacement))
        assertNull(controller.acknowledgeSource(old, snapshot(2L)))
        assertEquals(RecordingTileHandoffRefresh.SOURCE_RETRY, queuedRefresh())
        assertTrue(requireNotNull(controller.pending).suppressesRecordingState(target, snapshot(2L)))
        assertTrue(controller.observePresent(replacement))
        assertEquals(target, controller.acknowledgeSource(replacement, snapshot(2L)))
        assertNull(controller.pending)
    }

    @Test
    fun reAddOrStartListeningBeforeQueuedRefreshRevokesSameIncarnationRemoval() {
        val controller = RecordingTileHandoffController()
        val sourceObservation = controller.beginObservation(source)
        val handoff = beginObservedHandoff(controller)
        controller.observeRemoved(sourceObservation)
        val queuedRefresh = { controller.nextRefresh(handoff, snapshot(2L), 2L) }

        // Both actual lifecycle callbacks call observePresent before any render.
        assertTrue(controller.observePresent(sourceObservation))
        assertFalse(controller.sourceWasRemoved(source))
        assertEquals(RecordingTileHandoffRefresh.SOURCE_RETRY, queuedRefresh())
        assertTrue(handoff.suppressesRecordingState(target, snapshot(2L)))
        assertEquals(target, controller.acknowledgeSource(sourceObservation, snapshot(2L)))
    }

    @Test
    fun unknownPresentAndDestroyedWithoutRemovalRetainTheSameBoundedFallback() {
        for (mode in 0..3) {
            val controller = RecordingTileHandoffController()
            if (mode != 0) {
                val observation = controller.beginObservation(source)
                if (mode >= 2) controller.observePresent(observation)
                if (mode == 3) controller.endObservation(observation)
            }
            val handoff = beginObservedHandoff(controller)
            val decisions = (1..50).map { controller.nextRefresh(handoff, snapshot(2L), 2L) }
            assertEquals(49, decisions.count { it == RecordingTileHandoffRefresh.SOURCE_RETRY })
            assertEquals(1, decisions.count { it == RecordingTileHandoffRefresh.FULL_REFRESH })
            assertFalse(decisions.contains(RecordingTileHandoffRefresh.TARGET_ONLY))
            // Each SOURCE_RETRY emits one request and one delayed callback; FULL_REFRESH emits two.
            assertEquals(51, decisions.sumOf { if (it == RecordingTileHandoffRefresh.SOURCE_RETRY) 1 else 2 })
            assertNull(controller.pending)
            assertNull(controller.nextRefresh(handoff, snapshot(2L), 2L))
        }
    }

    @Test
    fun targetRemovalCannotAcknowledgeSourceAndRestartedProcessHasNoAbsenceProof() {
        val controller = RecordingTileHandoffController()
        val targetObservation = controller.beginObservation(target)
        val handoff = beginObservedHandoff(controller)
        assertNull(controller.observeRemoved(targetObservation))
        assertEquals(RecordingTileHandoffRefresh.SOURCE_RETRY,
            controller.nextRefresh(handoff, snapshot(2L), 2L))
        val sourceObservation = controller.beginObservation(source)
        controller.observeRemoved(sourceObservation)
        assertTrue(controller.sourceWasRemoved(source))
        assertFalse(RecordingTileHandoffController().sourceWasRemoved(source))
    }

    @Test
    fun oldRetryAndRemovalCannotRetireANewerHandoffOrOverrideCurrentStop() {
        val controller = RecordingTileHandoffController()
        val oldSource = controller.beginObservation(source)
        val oldHandoff = beginObservedHandoff(controller)
        controller.begin(target, source, cacheGeneration = 2L, commandGeneration = 3L)
        val newHandoff = requireNotNull(controller.pending)
        val staleSource = controller.beginObservation(target)
        val newerSource = controller.beginObservation(target)
        assertNull(controller.observeRemoved(staleSource))
        controller.observeRemoved(oldSource) // This is now the target, not the source.
        val current = snapshot(3L, commandGeneration = 3L, activeBuffer = source)
        assertNull(controller.nextRefresh(oldHandoff, current, 3L))
        assertTrue(controller.pending === newHandoff)
        assertTrue(controller.observePresent(newerSource))
        assertEquals(RecordingTileHandoffRefresh.SOURCE_RETRY,
            controller.nextRefresh(newHandoff, current, 3L))
        assertNull(controller.nextRefresh(newHandoff, current.copy(listening = false, commandGeneration = 4L), 4L))
        assertNull(controller.pending)
    }
}
