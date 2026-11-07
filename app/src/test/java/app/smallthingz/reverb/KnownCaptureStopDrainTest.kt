package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class KnownCaptureStopDrainTest {
    private val root = File("/capture-stop-tests/one")
    private fun marker(armed: Boolean = true, pid: Int = 11) = ActiveRecordingSessionMarker(
        armed, pid, 100, 1_000, 200, 900, 3,
    )

    @Test fun identicalMarkerClocksDoNotAuthorizeAnotherPublicationEpoch() {
        val epoch = Any()
        val original = marker()
        val token = KnownCaptureStopToken(root, original, epoch)
        assertTrue(token.owns(root, epoch, original.copy()))
        assertFalse(token.owns(root, Any(), original.copy()))
        token.retirementAttempted = true
        assertFalse(token.owns(root, Any(), original.copy(armed = false)))
        assertFalse(token.owns(root, Any(), null))
    }

    @Test fun absentReservationCannotAcquireFreshMarkerEvenAfterAnAttempt() {
        val epoch = Any()
        val token = KnownCaptureStopToken(root, null, epoch)
        assertTrue(token.owns(root, epoch, null))
        assertFalse(token.armed)
        assertFalse(token.owns(root, epoch, marker()))
        token.retirementAttempted = true
        assertFalse(token.owns(root, epoch, marker()))
        assertFalse(token.owns(root, epoch, marker(armed = false)))
    }

    @Test fun armedReservationOwnsFalseOrAbsenceOnlyAfterItsRetirementAttempt() {
        val epoch = Any()
        val original = marker()
        val token = KnownCaptureStopToken(root, original, epoch)
        assertTrue(token.armed)
        assertFalse(token.owns(root, epoch, original.copy(armed = false)))
        assertFalse(token.owns(root, epoch, null))
        token.retirementAttempted = true
        assertTrue(token.owns(root, epoch, original.copy(armed = false)))
        assertTrue(token.owns(root, epoch, null))
        assertFalse(token.owns(root, epoch, original.copy(pid = 12, armed = false)))
        assertFalse(token.owns(root, epoch, original.copy(armedAtMillis = 1_001)))
    }

    @Test fun tokenNeverMutatesAnotherIncidentRoot() {
        val epoch = Any()
        val original = marker()
        val token = KnownCaptureStopToken(root, original, epoch)
        val otherRoot = File("/capture-stop-tests/two")
        assertFalse(token.owns(otherRoot, epoch, original))
        token.retirementAttempted = true
        assertFalse(token.owns(otherRoot, epoch, original.copy(armed = false)))
        assertFalse(token.owns(otherRoot, epoch, null))
    }

    private class Completion(var current: ActiveRecordingSessionMarker?) {
        val calls = mutableListOf<String>()
        var retry: () -> Unit = {}
        var recover: () -> Unit = {}
        var failDisarm = false
        var failRemove = false
        var failRestore = false
        var failConfirm = false
        fun complete(owns: (ActiveRecordingSessionMarker?) -> Boolean = { true }): KnownCaptureStopResult =
            completeKnownCaptureStop(
                retryPendingEvidence = { calls += "retry"; retry() },
                readMarker = { calls += "read"; current },
                isArmed = { it.armed },
                belongsToCurrentProcess = { it.pid == 11 },
                recoverPriorSession = { calls += "recover"; recover() },
                confirmDisarmed = { calls += "confirm"; if (failConfirm) throw IOException("confirm") },
                retainUncertainEvidence = { calls += "retain" },
                disarm = { calls += "disarm"; if (failDisarm) throw IOException("disarm") },
                remove = { calls += "remove"; if (failRemove) throw IOException("remove") },
                restore = { calls += "restore"; if (failRestore) throw IOException("restore") },
                ownsMarker = { calls += "owns"; owns(it) },
            )
    }

    @Test fun ownershipIsRecheckedAfterEvidenceRetryBeforeEveryMutationBranch() {
        for (replacement in listOf(marker(), marker(armed = false), null)) {
            val epoch = Any()
            var currentEpoch = epoch
            val token = KnownCaptureStopToken(root, marker(), epoch)
            val completion = Completion(marker())
            completion.retry = { currentEpoch = Any(); completion.current = replacement }
            assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN,
                completion.complete { token.owns(root, currentEpoch, it) })
            assertEquals(listOf("retry", "read", "owns"), completion.calls)
        }
    }

    @Test fun ownershipIsRecheckedAfterPriorSessionRecoveryBeforeEveryMutationBranch() {
        for (replacement in listOf(marker(), marker(armed = false), null)) {
            val epoch = Any()
            var currentEpoch = epoch
            val token = KnownCaptureStopToken(root, marker(pid = 10), epoch)
            val completion = Completion(marker(pid = 10))
            completion.recover = { currentEpoch = Any(); completion.current = replacement }
            assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN,
                completion.complete { token.owns(root, currentEpoch, it) })
            assertEquals(listOf("retry", "read", "recover", "read", "owns"), completion.calls)
        }
    }

    @Test fun failedDisarmUsesDeleteThenRestorationWithoutLosingFailureStatus() {
        val deleteSucceeds = Completion(marker()).apply { failDisarm = true }
        assertEquals(KnownCaptureStopResult.DURABLE, deleteSucceeds.complete())
        assertEquals(listOf("retry", "read", "owns", "disarm", "remove"), deleteSucceeds.calls)

        val restored = Completion(marker()).apply { failDisarm = true; failRemove = true }
        assertEquals(KnownCaptureStopResult.FAILED_REARMED, restored.complete())
        assertEquals(listOf("retry", "read", "owns", "disarm", "remove", "restore"), restored.calls)

        val uncertain = Completion(marker()).apply { failDisarm = true; failRemove = true; failRestore = true }
        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, uncertain.complete())
        assertEquals(listOf("retry", "read", "owns", "disarm", "remove", "restore", "retain"), uncertain.calls)
    }

    @Test fun successfulDisarmAndConfirmationRetainTheirDurabilityResults() {
        val armed = Completion(marker())
        assertEquals(KnownCaptureStopResult.DURABLE, armed.complete())
        assertEquals(listOf("retry", "read", "owns", "disarm"), armed.calls)
        for (fail in listOf(false, true)) {
            val absent = Completion(null).apply { failRemove = fail }
            assertEquals(if (fail) KnownCaptureStopResult.FAILED_UNCERTAIN else KnownCaptureStopResult.DURABLE,
                absent.complete())
            assertEquals(listOf("retry", "read", "owns", "remove"), absent.calls)
            val disarmed = Completion(marker(armed = false)).apply { failConfirm = fail }
            assertEquals(if (fail) KnownCaptureStopResult.FAILED_UNCERTAIN else KnownCaptureStopResult.DURABLE,
                disarmed.complete())
            assertEquals(listOf("retry", "read", "owns", "confirm"), disarmed.calls)
        }
    }
}
