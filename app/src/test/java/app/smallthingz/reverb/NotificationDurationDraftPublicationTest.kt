package app.smallthingz.reverb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real controller/binding integration; the Android owner's publication gate is modeled explicitly. */
class NotificationDurationDraftPublicationTest {
    private class Host(private val deferOwnPublication: Boolean = true) {
        val interaction = NotificationDurationInteractionState()
        val binding = NotificationDurationBinding(interaction)
        val renders = mutableListOf<Int>()
        private var reconciliationDepth = 0
        var draftCallbackFailure: RuntimeException? = null
        var resetDisplaySeconds: Int? = null
        val controller: SettingsDraftController = SettingsDraftController(
            initiallyActive = true,
            barrier = SettingsCommitBarrier(),
            isPanelActive = { true },
            onChanged = {
                draftCallbackFailure?.let { failure ->
                    draftCallbackFailure = null
                    throw failure
                }
            },
            onThemePreview = {},
            onChildEditorReadiness = { _, _ ->
                if (reconciliationDepth == 0) publish()
            },
        )

        suspend fun hydrate() {
            controller.hydrate(
                readDurable = {
                    SettingsDraftBaseline(SettingsUiDraft.normalized(SettingsSnapshot(
                        oneShotRetentionTime = 300, loopingRetentionTime = 600,
                        quickSaveEnabled = true, quickSaveSeconds = 30,
                    )), null)
                },
                restoreFields = { it },
            )
            renders.clear()
        }

        fun owner(): Long? = controller.state.let {
            it.editRevision.takeIf { _ -> it.active && it.interactionReady && !it.persisting }
        }

        fun publish() {
            val state = controller.state
            val seconds = state.displayed.snapshot.quickSaveSeconds
            binding.update(seconds, 300, owner() != null, state.editRevision)
            renders += seconds
        }

        // Models only NativeSettingsOwner's reconciliation-depth gate. Draft/revision mutation,
        // callback order and binding acknowledgement are the production controller and binding.
        fun change(seconds: Int): NotificationDurationCommit {
            if (deferOwnPublication) reconciliationDepth++
            try {
                val displayed = controller.state.displayed
                controller.edited(displayed.copy(snapshot = displayed.snapshot.copy(quickSaveSeconds = seconds)))
                return NotificationDurationCommit(seconds, requireNotNull(owner()))
            } finally {
                if (deferOwnPublication) reconciliationDepth--
            }
        }

        fun discrete(seconds: Int): Int? = try {
            binding.commitInput(seconds, owner(), ::change)
        } finally { publish() }

        fun terminal(): Int? = try {
            binding.finishInteraction(owner(), ::change) { canonical ->
                assertTrue(interaction.pending)
                resetDisplaySeconds = canonical
            }
        } finally { publish() }
    }

    @Test fun synchronousControllerPublicationChangesDraftButLosesOwnReceipt() = runBlocking {
        val host = Host(deferOwnPublication = false)
        host.hydrate()

        // Regression witness: this was the native owner's publication order. The edit succeeds,
        // but NativeNotificationDurationWheel maps this null receipt to an unhandled action.
        assertNull(host.discrete(31))
        assertEquals(31, host.controller.state.displayed.snapshot.quickSaveSeconds)
        assertEquals(31, host.binding.seconds)
        assertTrue(host.controller.state.hasUnsavedChanges)
    }

    @Test fun deferredControllerPublicationAcknowledgesRepeatedDiscreteEdits() = runBlocking {
        val host = Host()
        host.hydrate()
        val oldOwner = host.owner()

        assertEquals(31, host.discrete(31))
        assertEquals(32, host.discrete(32))
        assertEquals(listOf(31, 32), host.renders)
        assertEquals(32, host.controller.state.displayed.snapshot.quickSaveSeconds)
        assertEquals(32, host.binding.seconds)
        assertNull(host.binding.commitInput(90, oldOwner, host::change))
        assertEquals(32, host.controller.state.displayed.snapshot.quickSaveSeconds)
    }

    @Test fun terminalPublishesAfterReceiptAndReleasesOnlyItsInteraction() = runBlocking {
        val host = Host()
        host.hydrate()
        host.binding.beginInteraction(host.owner())
        host.binding.select(45)
        assertTrue(host.interaction.pending)

        assertEquals(45, host.terminal())
        assertFalse(host.interaction.pending)
        assertEquals(45, host.resetDisplaySeconds)
        assertEquals(listOf(45), host.renders)
        assertEquals(46, host.discrete(46))

        host.binding.beginInteraction(host.owner())
        host.binding.select(90)
        host.binding.invalidateInteraction() // Lifecycle invalidation before native terminal.
        assertNull(host.terminal())
        assertFalse(host.interaction.pending)
        assertEquals(46, host.controller.state.displayed.snapshot.quickSaveSeconds)
        assertEquals(46, host.binding.seconds)
        assertEquals(46, host.resetDisplaySeconds)
    }

    @Test fun mutationFailureStillPublishesAndDoesNotLeaveTheGateHeld() = runBlocking {
        val host = Host()
        host.hydrate()
        val expected = IllegalStateException("Failure after the controller accepted the draft")
        host.draftCallbackFailure = expected

        assertSame(expected, assertThrows(IllegalStateException::class.java) { host.discrete(31) })
        assertEquals(31, host.controller.state.displayed.snapshot.quickSaveSeconds)
        assertEquals(31, host.binding.seconds)
        assertEquals(listOf(31), host.renders)
        assertEquals(32, host.discrete(32))
    }

    @Test fun unrelatedControllerPublicationStillInvalidatesAnOwnReceipt() = runBlocking {
        val host = Host()
        host.hydrate()

        assertNull(host.binding.commitInput(31, host.owner()) { seconds ->
            val receipt = host.change(seconds)
            val displayed = host.controller.state.displayed
            host.controller.edited(displayed.copy(snapshot = displayed.snapshot.copy(quickSaveSeconds = 70)))
            receipt
        })
        assertEquals(70, host.controller.state.displayed.snapshot.quickSaveSeconds)
        assertEquals(70, host.binding.seconds)
        assertEquals(71, host.discrete(71))
    }

    @Test fun terminalMutationFailureReleasesItsTokenAndPublishesAcceptedDraft() = runBlocking {
        val host = Host()
        host.hydrate()
        val expected = IllegalStateException("Failure after the controller accepted the draft")
        host.binding.beginInteraction(host.owner())
        host.binding.select(45)
        host.draftCallbackFailure = expected

        assertSame(expected, assertThrows(IllegalStateException::class.java) { host.terminal() })
        assertFalse(host.interaction.pending)
        assertEquals(30, host.resetDisplaySeconds)
        assertEquals(45, host.controller.state.displayed.snapshot.quickSaveSeconds)
        assertEquals(45, host.binding.seconds)
        assertEquals(listOf(45), host.renders)
        assertEquals(46, host.discrete(46))
    }
}
