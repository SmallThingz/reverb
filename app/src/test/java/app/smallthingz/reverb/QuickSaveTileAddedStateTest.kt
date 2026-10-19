package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class QuickSaveTileAddedStateTest {
    private fun option(revision: Char = 'a', slot: Int = 0) = QuickSaveTileOption(slot, revision.toString().repeat(32))
    private fun observed(option: QuickSaveTileOption, state: QuickSaveTileAddedState = QuickSaveTileAddedState.ADDED) =
        mapOf(option.slot to QuickSaveTileAddedObservation(option.revision, option.revision, state))
    private fun history(option: QuickSaveTileOption, state: QuickSaveTileAddedState = QuickSaveTileAddedState.ADDED) =
        QuickSaveTileAddedHistory(observed(option, state), setOf(option.slot))

    @Test fun legacyAndMismatchedMetadataNeverClaimAbsence() {
        val tile = option()
        assertEquals(QuickSaveTileAddedState.UNKNOWN,
            quickSaveTileAddedState(tile, missingQuickSaveTileAddedHistory(true).observations))
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(tile, observed(option('b'), QuickSaveTileAddedState.NOT_ADDED)))
        val reconciled = reconcileQuickSaveTileAddedStates(listOf(tile), observed(option('b')))
        assertEquals(tile.revision, reconciled[0]?.lifetimeId)
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(tile, reconciled))
    }

    @Test fun newlyCreatedDefinitionAndObservedStateSurviveStorage() {
        val tile = option()
        val created = quickSaveTileAddedStatesAfterEdit(emptyList(), listOf(tile), missingQuickSaveTileAddedHistory(false))
        assertEquals(QuickSaveTileAddedState.NOT_ADDED, quickSaveTileAddedState(tile, created.observations))
        for (state in QuickSaveTileAddedState.entries) {
            val states = history(tile, state)
            assertEquals(states, decodeQuickSaveTileAddedStates(encodeQuickSaveTileAddedStates(states)))
        }
    }

    @Test fun editingKeepsInstallationLifetimeAndDoesNotChangeDefinitionEquality() {
        val tile = option()
        val edited = tile.copy(revision = "b".repeat(32), amount = 3)
        val states = quickSaveTileAddedStatesAfterEdit(listOf(tile), listOf(edited), history(tile)).observations
        assertEquals(tile.revision, states[0]?.lifetimeId)
        assertEquals(QuickSaveTileAddedState.ADDED, quickSaveTileAddedState(edited, states))
        assertEquals(tile, option())
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(tile, states))
        val owners = QuickSaveTileObservationLifetimes()
        val session = owners.capture(0)
        owners.bind(session, observed(tile))
        owners.definitionsCommitted(listOf(tile), listOf(edited))
        assertEquals(edited, owners.resolve(session, listOf(edited), states))
    }

    @Test fun oldServiceCannotBindOrPublishAfterSlotIsDeletedAndReused() {
        val tile = option()
        val replacement = option('b')
        val owners = QuickSaveTileObservationLifetimes()
        val pendingInitialization = owners.capture(0)
        val active = owners.capture(0)
        owners.bind(active, observed(tile))
        owners.definitionsCommitted(listOf(tile), emptyList())
        owners.definitionsCommitted(emptyList(), listOf(replacement))
        val states = quickSaveTileAddedStatesAfterEdit(emptyList(), listOf(replacement), history(tile)).observations
        owners.bind(pendingInitialization, states)
        assertNull(owners.resolve(active, listOf(replacement), states))
        assertNull(owners.resolve(pendingInitialization, listOf(replacement), states))
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(replacement, states))
        val fresh = owners.capture(0)
        owners.bind(fresh, states)
        assertEquals(replacement, owners.resolve(fresh, listOf(replacement), states))
    }

    @Test fun absenceAtServiceCreationNeverRebindsToLaterDefinition() {
        val owners = QuickSaveTileObservationLifetimes()
        val session = owners.capture(0)
        owners.bind(session, emptyMap())
        val tile = option()
        owners.definitionsCommitted(emptyList(), listOf(tile))
        assertNull(owners.resolve(session, listOf(tile), observed(tile)))
    }

    @Test fun pendingAddAcknowledgmentCannotOverrideNewerLifecycleObservationOrEdit() {
        val tile = option()
        val owners = QuickSaveTileObservationLifetimes()
        val request = owners.captureAddRequest(tile)
        assertTrue(owners.addRequestIsCurrent(request, listOf(tile)))
        owners.observationQueued(owners.capture(0)) // Removal (including a failed durable write) wins immediately.
        assertFalse(owners.addRequestIsCurrent(request, listOf(tile)))
        val newer = owners.captureAddRequest(tile)
        assertTrue(owners.addRequestIsCurrent(newer, listOf(tile)))
        assertFalse(owners.addRequestIsCurrent(newer, listOf(option('b'))))
        assertFalse(owners.addRequestIsCurrent(newer, emptyList()))
    }

    @Test fun oldServiceEventDoesNotInvalidateNewLifetimesAddRequest() {
        val owners = QuickSaveTileObservationLifetimes()
        val tile = option()
        val old = owners.capture(0)
        owners.definitionsCommitted(listOf(tile), emptyList())
        val replacement = option('b')
        owners.definitionsCommitted(emptyList(), listOf(replacement))
        val request = owners.captureAddRequest(replacement)
        owners.observationQueued(old)
        assertTrue(owners.addRequestIsCurrent(request, listOf(replacement)))
    }

    @Test fun malformedDuplicateAndOversizedObservationsFailClosed() {
        val valid = encodeQuickSaveTileAddedStates(history(option()))
        for (raw in listOf("", "2", "$valid\n", "$valid\n${valid.substringAfter('\n')}",
            valid.replace("ADDED", "MISSING"), valid.replace("0|", "8|"),
            valid.replace("a".repeat(32), "short"), "1\n" + "x".repeat(1025),
            "$valid\n${valid.substringAfter('\n').replace("0|", "1|")}",
            "1\n0|USED\n0|USED", "1\n8|USED", "1\n0|MISSING")) {
            assertThrows(IllegalArgumentException::class.java) { decodeQuickSaveTileAddedStates(raw) }
        }
    }

    @Test fun deletedSlotRemainsUnknownAfterColdRestartAndReuse() {
        val tile = option()
        val deleted = quickSaveTileAddedStatesAfterEdit(listOf(tile), emptyList(), history(tile))
        assertTrue(deleted.observations.isEmpty())
        assertEquals(setOf(0), deleted.usedSlots)
        // Reconstruct solely from durable wire, with no prior owner or in-memory definitions.
        val restarted = decodeQuickSaveTileAddedStates(encodeQuickSaveTileAddedStates(deleted))
        val replacement = option('b')
        val reused = quickSaveTileAddedStatesAfterEdit(emptyList(), listOf(replacement), restarted)
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(replacement, reused.observations))
        assertEquals(replacement.revision, reused.observations[0]?.lifetimeId)
        val other = option('c', 1)
        val virgin = quickSaveTileAddedStatesAfterEdit(listOf(replacement), listOf(replacement, other), reused)
        assertEquals(QuickSaveTileAddedState.NOT_ADDED, quickSaveTileAddedState(other, virgin.observations))
    }

    @Test fun missingLegacyHistoryCannotInventVirginSlotsAfterPriorDeletions() {
        val migrated = missingQuickSaveTileAddedHistory(definitionsPreviouslyStored = true)
        val tile = option()
        val created = quickSaveTileAddedStatesAfterEdit(emptyList(), listOf(tile), migrated)
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(tile, created.observations))
        assertEquals((0 until QUICK_SAVE_TILE_COUNT).toSet(), created.usedSlots)
        assertEquals(created, decodeQuickSaveTileAddedStates(encodeQuickSaveTileAddedStates(created)))
    }

    @Test fun repeatedSlotReuseKeepsOnlyEightBoundedHistoryEntries() {
        var stored = missingQuickSaveTileAddedHistory(false)
        for (round in 1..100) {
            val tile = QuickSaveTileOption(round % QUICK_SAVE_TILE_COUNT, round.toString(16).padStart(32, '0'))
            stored = quickSaveTileAddedStatesAfterEdit(emptyList(), listOf(tile), stored)
            stored = quickSaveTileAddedStatesAfterEdit(listOf(tile), emptyList(), stored)
            stored = decodeQuickSaveTileAddedStates(encodeQuickSaveTileAddedStates(stored))
        }
        assertEquals(QUICK_SAVE_TILE_COUNT, stored.usedSlots.size)
        assertTrue(stored.observations.isEmpty())
        assertTrue(encodeQuickSaveTileAddedStates(stored).length < 1024)
    }

    @Test fun repairedEditedDefinitionKeepsOnlyProvenLifetimeAndAcceptsFreshOwnerObservation() {
        val tile = option()
        val owners = QuickSaveTileObservationLifetimes()
        val owner = owners.capture(0)
        owners.bind(owner, observed(tile))
        val edited = option('b')
        val editedHistory = quickSaveTileAddedStatesAfterEdit(listOf(tile), listOf(edited), history(tile))
        owners.definitionsCommitted(listOf(tile), listOf(edited))
        val repaired = QuickSaveTileAddedHistory(
            reconcileQuickSaveTileAddedStates(listOf(edited), emptyMap(), editedHistory.observations),
            editedHistory.usedSlots,
        )
        val reloaded = decodeQuickSaveTileAddedStates(encodeQuickSaveTileAddedStates(repaired))
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(edited, reloaded.observations))
        assertEquals(tile.revision, reloaded.observations[0]?.lifetimeId)
        assertEquals(edited, owners.resolve(owner, listOf(edited), reloaded.observations))
        val observedAgain = reloaded.observations.mapValues { (_, value) -> value.copy(state = QuickSaveTileAddedState.ADDED) }
        assertEquals(QuickSaveTileAddedState.ADDED, quickSaveTileAddedState(edited, observedAgain))

        owners.definitionsCommitted(listOf(edited), emptyList())
        val replacement = option('c')
        owners.definitionsCommitted(emptyList(), listOf(replacement))
        val staleContinuity = reconcileQuickSaveTileAddedStates(listOf(replacement), emptyMap(), editedHistory.observations)
        assertEquals(replacement.revision, staleContinuity[0]?.lifetimeId)
        assertEquals(QuickSaveTileAddedState.UNKNOWN, quickSaveTileAddedState(replacement, staleContinuity))
        assertNull(owners.resolve(owner, listOf(replacement), staleContinuity))
    }
}
