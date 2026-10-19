package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIncidentHistoryCompatibilityTest {
    @Test
    fun deletedHistoryAtOldCapacity_doesNotBlockANewCaptureIncident() {
        var history = (1L..128L).map { it.toIncident().copy(acknowledgedAtMillis = Long.MIN_VALUE) }
        repeat(512) { index ->
            val incoming = (1_000L + index).toIncident()
            history = compactRecordingIncidentDeletions(history, incoming) { RecordingIncidentReplayOwners() }
            history = appendRecordingIncidentPreservingDeletions(history, incoming, MAX_VISIBLE_RECORDING_INCIDENTS)
            validateRecordingIncidentHistorySize(history)
            assertEquals(listOf(incoming), history)
            history = history.map { it.copy(acknowledgedAtMillis = Long.MIN_VALUE) }
        }
    }

    @Test
    fun fullPendingQueue_keepsDeletedIdentitiesWithoutConsumingVisibleHistory() {
        val deleted = (1L..128L).map { it.toIncident().copy(acknowledgedAtMillis = Long.MIN_VALUE) }
        val owners = RecordingIncidentReplayOwners(captureSessions = deleted)
        var history = deleted
        for (id in 1_000L..1_255L) {
            val incoming = id.toIncident()
            history = retainRecordingIncidentDeletions(history, owners, incoming)
            history = appendRecordingIncidentPreservingDeletions(history, incoming, MAX_VISIBLE_RECORDING_INCIDENTS)
            validateRecordingIncidentHistorySize(history)
        }
        assertEquals(deleted, history.filter { it.deleted })
        assertEquals(128, history.count { !it.deleted })
        assertEquals(1_128L, history.first { !it.deleted }.occurredAtMillis)
        assertEquals(history, decodeHistoryPayload(historyBytes(3, history)))
    }

    @Test
    fun delayedEvidenceAndLocalRetries_keepExactDeletedSessionUntilOwnerRetires() {
        val deleted = 10L.toIncident().copy(acknowledgedAtMillis = Long.MIN_VALUE)
        val late = deleted.copy(occurredAtMillis = 12L, acknowledgedAtMillis = 0L,
            exitReason = ApplicationExitInfo.REASON_CRASH)
        val unrelated = 100L.toIncident()
        val pending = RecordingIncidentReplayOwners(captureSessions = listOf(deleted))
        assertEquals(listOf(deleted), retainRecordingIncidentDeletions(listOf(deleted), pending, unrelated))
        val retry = RecordingIncidentReplayOwners(exactIncidents = listOf(late))
        assertEquals(listOf(deleted), retainRecordingIncidentDeletions(listOf(deleted), retry, unrelated))
        assertEquals(listOf(deleted), retainRecordingIncidentDeletions(
            listOf(deleted), RecordingIncidentReplayOwners(), late))
        assertTrue(mergeRecordingIncidentEvidence(deleted, late).deleted)
        assertTrue(retainRecordingIncidentDeletions(
            listOf(deleted), RecordingIncidentReplayOwners(), unrelated).isEmpty())
        assertTrue(retainRecordingIncidentDeletions(
            listOf(deleted), RecordingIncidentReplayOwners(captureSessions = listOf(
                deleted.copy(processStartedAtMillis = 50L))), unrelated).isEmpty())
    }

    @Test
    fun legacyDeletionRetainsPossibleTimestampReplayAndReleasesAfterOwner() {
        val legacy = RecordingIncident(occurredAtMillis = 100L, acknowledgedAtMillis = Long.MIN_VALUE)
        val incoming = 200L.toIncident()
        val owner = 50L.toIncident()
        assertEquals(listOf(legacy), retainRecordingIncidentDeletions(
            listOf(legacy), RecordingIncidentReplayOwners(captureSessions = listOf(owner)), incoming))
        assertTrue(retainRecordingIncidentDeletions(
            listOf(legacy), RecordingIncidentReplayOwners(captureSessions = listOf(150L.toIncident())), incoming).isEmpty())
        assertTrue(retainRecordingIncidentDeletions(
            listOf(legacy), RecordingIncidentReplayOwners(), incoming).isEmpty())
    }

    @Test
    fun appExitDeletionUsesItsRecoveryProcessAndHandledErrorsHaveNoReplay() {
        val deleted = RecordingIncident(occurredAtMillis = 100_000L, acknowledgedAtMillis = Long.MIN_VALUE,
            kind = RecordingIncidentKind.UNEXPECTED_ERROR, pid = 42, processStartedAtMillis = 90_000L,
            exitReason = ApplicationExitInfo.REASON_CRASH)
        val incoming = 200_000L.toIncident()
        val owner = RecordingIncidentReplayOwners(appProcessPid = 42, appProcessStartedAtMillis = 90_000L)
        assertEquals(listOf(deleted), retainRecordingIncidentDeletions(listOf(deleted), owner, incoming))
        assertTrue(retainRecordingIncidentDeletions(listOf(deleted),
            owner.copy(appProcessStartedAtMillis = 190_000L), incoming).isEmpty())
        val uncaught = deleted.copy(exitReason = ApplicationExitInfo.REASON_UNKNOWN,
            processStartedAtMillis = 90_002L, description = "Uncaught exception on main: failure")
        assertEquals(listOf(uncaught), retainRecordingIncidentDeletions(listOf(uncaught), owner, incoming))
        val handled = uncaught.copy(description = "Preview cleanup: failure")
        assertTrue(retainRecordingIncidentDeletions(listOf(handled), owner, incoming).isEmpty())
    }

    @Test
    fun unavailableReplayMetadataPreservesAllDeletionProofAndCanRecoverLater() {
        val deleted = (1L..128L).map { it.toIncident().copy(acknowledgedAtMillis = Long.MIN_VALUE) }
        val incoming = 1_000L.toIncident()
        val retained = compactRecordingIncidentDeletions(deleted, incoming) { throw IOException("owner read failed") }
        assertSame(deleted, retained)
        val appended = appendRecordingIncidentPreservingDeletions(retained, incoming, MAX_VISIBLE_RECORDING_INCIDENTS)
        validateRecordingIncidentHistorySize(appended)
        assertEquals(129, appended.size)
        assertEquals(listOf(incoming), compactRecordingIncidentDeletions(appended, 2_000L.toIncident()) {
            RecordingIncidentReplayOwners()
        })
    }

    @Test
    fun historyPayloadVersionsKeepLegacyBoundsAndCurrentDeletionReserve() {
        val visible = 10L.toIncident()
        assertEquals(listOf(RecordingIncident(10L)), decodeHistoryPayload(historyBytes(1, listOf(visible))))
        val deleted = visible.copy(acknowledgedAtMillis = Long.MIN_VALUE)
        assertEquals(listOf(deleted), decodeHistoryPayload(historyBytes(2, listOf(deleted))))
        assertEquals(listOf(deleted), decodeHistoryPayload(historyBytes(3, listOf(deleted))))
        for ((version, excessiveCount) in listOf(1 to 129, 2 to 129, 3 to (MAX_RECORDING_INCIDENT_HISTORY_RECORDS + 1))) {
            val header = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(0x52494831); output.writeByte(version); output.writeShort(excessiveCount)
                }
            }.toByteArray()
            val failure = org.junit.Assert.assertThrows(IOException::class.java) { decodeHistoryPayload(header) }
            assertTrue(failure.message.orEmpty().contains("exceeds"))
        }
        org.junit.Assert.assertThrows(IOException::class.java) {
            decodeHistoryPayload(historyBytes(3, (1L..129L).map { it.toIncident() }))
        }
        org.junit.Assert.assertThrows(IOException::class.java) {
            decodeHistoryPayload(historyBytes(3, listOf(deleted)) + byteArrayOf(1))
        }
    }

    private fun Long.toIncident() = RecordingIncident(
        occurredAtMillis = this, pid = 42, processStartedAtMillis = 1L, captureArmedAtMillis = this,
    )

    private fun decodeHistoryPayload(bytes: ByteArray): List<RecordingIncident> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            readIncidentPayloadExact(input, "test incident history", RecordingIncidentStore::readHistoryPayload)
        }

    private fun historyBytes(version: Int, incidents: List<RecordingIncident>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x52494831)
                output.writeByte(version)
                output.writeShort(incidents.size)
                incidents.forEach { incident ->
                    output.writeByte(incident.kind.storageCode.toInt())
                    output.writeLong(incident.occurredAtMillis)
                    if (version != 1) {
                        output.writeLong(incident.resumedAtMillis)
                        output.writeLong(incident.acknowledgedAtMillis)
                        output.writeInt(incident.exitReason)
                        output.writeInt(incident.exitStatus)
                        output.writeInt(incident.pid)
                        output.writeInt(incident.importance)
                        output.writeLong(incident.pssKb)
                        output.writeLong(incident.rssKb)
                        output.writeLong(incident.processStartedAtMillis)
                        output.writeLong(incident.captureArmedAtMillis)
                        output.writeUTF(incident.description.orEmpty())
                    }
                }
            }
        }.toByteArray()

    @Test
    fun productionEncoderKeepsPublishedV3LayoutAndEveryIncidentField() {
        val original = listOf(
            RecordingIncident(
                occurredAtMillis = 1_700_000_000_123L,
                resumedAtMillis = 1_700_000_002_456L,
                acknowledgedAtMillis = Long.MIN_VALUE,
                kind = RecordingIncidentKind.UNEXPECTED_ERROR,
                exitReason = ApplicationExitInfo.REASON_CRASH,
                exitStatus = 11,
                pid = 420,
                importance = 125,
                pssKb = 12_345L,
                rssKb = 67_890L,
                processStartedAtMillis = 1_699_999_999_000L,
                captureArmedAtMillis = 1_700_000_000_000L,
                description = "Capture: Ω failure",
            ),
            1_700_000_005_000L.toIncident(),
        )
        val encoded = encodeCurrent(original)
        assertArrayEquals(historyBytes(3, original), encoded)
        assertEquals(original, decodeHistoryPayload(encoded))
    }

    @Test
    fun publishedV3EmptyHistoryRemainsReadable() {
        // Literal header emitted by the prior v3 writer; this failed in the RC1 rebuild.
        val bytes = byteArrayOf(0x52, 0x49, 0x48, 0x31, 0x03, 0x00, 0x00)
        assertTrue(decodeHistoryPayload(bytes).isEmpty())
        assertArrayEquals(bytes, encodeCurrent(emptyList()))
    }

    @Test
    fun maximumPublishedV3DeletionReserveRoundTripsWithoutDroppingRecords() {
        val deletedCount = MAX_RECORDING_INCIDENT_HISTORY_RECORDS - MAX_VISIBLE_RECORDING_INCIDENTS
        val deleted = (1L..deletedCount.toLong()).map {
            it.toIncident().copy(acknowledgedAtMillis = Long.MIN_VALUE)
        }
        val visible = (1_000L until 1_000L + MAX_VISIBLE_RECORDING_INCIDENTS).map { it.toIncident() }
        val all = deleted + visible
        assertEquals(388, all.size)
        assertEquals(all, decodeHistoryPayload(historyBytes(3, all)))
        assertEquals(all, decodeHistoryPayload(encodeCurrent(all)))
    }

    @Test
    fun encoderRejectsCapacityOverflowBeforeEmittingAnyBytes() {
        val overflowLists = listOf(
            (1L..129L).map { it.toIncident() },
            (1L..MAX_RECORDING_INCIDENT_HISTORY_RECORDS + 1L).map {
                it.toIncident().copy(acknowledgedAtMillis = Long.MIN_VALUE)
            },
        )
        overflowLists.forEach { incidents ->
            val bytes = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                RecordingIncidentStore.writeHistoryPayload(DataOutputStream(bytes), incidents)
            }
            assertEquals(0, bytes.size())
        }
    }

    @Test
    fun malformedPublishedPayloadsFailClosedWithoutReturningPartialHistory() {
        val valid = historyBytes(3, listOf(100L.toIncident(), 200L.toIncident()))
        val invalid = listOf(
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            valid.copyOf().apply { this[0] = 0 },
            valid.copyOf().apply { this[4] = 4 },
            valid.copyOf().apply { this[7] = 99 },
            historyBytes(3, listOf(RecordingIncident(occurredAtMillis = 0L))),
        )
        invalid.forEach { bytes ->
            assertThrows(IOException::class.java) { decodeHistoryPayload(bytes) }
        }
    }

    @Test
    fun readingLegacyHistoryAndRewritingV3PreservesSupportedFields() {
        for (version in 1..2) {
            val original = listOf(5L.toIncident(), 6L.toIncident().copy(acknowledgedAtMillis = Long.MIN_VALUE))
            val decoded = decodeHistoryPayload(historyBytes(version, original))
            val rewritten = encodeCurrent(decoded)
            assertEquals(3, rewritten[4].toInt())
            assertEquals(decoded, decodeHistoryPayload(rewritten))
        }
    }

    private fun encodeCurrent(incidents: List<RecordingIncident>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                RecordingIncidentStore.writeHistoryPayload(output, incidents)
            }
        }.toByteArray()
}
