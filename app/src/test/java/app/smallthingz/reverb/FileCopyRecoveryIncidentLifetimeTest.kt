package app.smallthingz.reverb

import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FileCopyRecoveryIncidentLifetimeTest {
    private fun record() = FileCopyPublication(UUID.randomUUID().toString(), FileCopyPurpose.RENAME,
        "/owned/source.wav", "renamed.wav", "/owned/renamed.wav", "stat:1:2:3:4:0", 48L, "0".repeat(64))

    @Test fun retiredLifetimesReleaseTokensAndStaleSnapshotsCannotReinsertThem() {
        val owners = FileCopyRecoveryIncidentOwners()
        var reports = 0
        repeat(100) {
            val record = owners.bind(record())
            val snapshot = owners.bind(record.copy())
            val owner = checkNotNull(record.recoveryIncidentOwner)
            assertSame(owner, snapshot.recoveryIncidentOwner)
            repeat(3) { if (owner.claim()) reports++ }
            assertEquals(1, owners.size)
            assertFalse(owners.retire(record, withIncident = false))
            assertEquals(0, owners.size)
            assertFalse(checkNotNull(snapshot.recoveryIncidentOwner).claim())
            assertEquals(0, owners.size)
        }
        assertEquals(100, reports)
    }

    @Test fun incompletePreservationClaimsAtMostOneTerminalIncidentBeforeRetirement() {
        for (previouslyReported in listOf(false, true)) {
            val owners = FileCopyRecoveryIncidentOwners()
            val record = owners.bind(record())
            val owner = checkNotNull(record.recoveryIncidentOwner)
            var reports = if (previouslyReported && owner.claim()) 1 else 0
            if (owners.retire(record, withIncident = true)) reports++
            assertFalse(owner.claim()) // The same failure's outer recovery catch.
            assertFalse(owners.retire(record, withIncident = true))
            assertEquals(1, reports)
            assertEquals(0, owners.size)
        }
    }

    @Test fun phaseChangesKeepDeduplicationWithoutChangingWireOrValueIdentity() {
        val owners = FileCopyRecoveryIncidentOwners()
        val original = record()
        val raw = encodeFileCopyPublication(original)
        owners.bind(original)
        assertEquals(raw, encodeFileCopyPublication(original))
        assertEquals(original, decodeFileCopyPublication(raw))
        val changed = owners.bind(original.copy(phase = FileCopyPhase.CREATED, createdIdentity = "stat:1:5:6:7:0"))
        assertTrue(checkNotNull(original.recoveryIncidentOwner).claim())
        assertFalse(checkNotNull(changed.recoveryIncidentOwner).claim())
        assertEquals(1, owners.size)
        owners.retire(changed, withIncident = false)
        assertEquals(0, owners.size)
        assertFalse(checkNotNull(original.recoveryIncidentOwner).claim())
    }

    @Test fun racingReportAndTerminalPreservationClaimExactlyOneIncident() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val owners = FileCopyRecoveryIncidentOwners()
                val record = owners.bind(record())
                val owner = checkNotNull(record.recoveryIncidentOwner)
                val start = CyclicBarrier(2)
                val report = executor.submit<Boolean> { start.await(5, TimeUnit.SECONDS); owner.claim() }
                // Only journal ownership touches the registry; reporting touches the captured owner.
                val retire = executor.submit<Boolean> {
                    start.await(5, TimeUnit.SECONDS)
                    owners.retire(record, withIncident = true)
                }
                val claims = listOf(report.get(5, TimeUnit.SECONDS), retire.get(5, TimeUnit.SECONDS))
                assertEquals(1, claims.count { it })
                assertEquals(0, owners.size)
                assertFalse(owner.claim())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
