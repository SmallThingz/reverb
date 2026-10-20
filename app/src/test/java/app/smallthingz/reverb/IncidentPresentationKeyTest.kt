package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentPresentationKeyTest {
    private val session = RecordingIncident(
        occurredAtMillis = 10_000L,
        resumedAtMillis = 0L,
        pid = 42,
        processStartedAtMillis = 5_000L,
        captureArmedAtMillis = 9_000L,
        description = "Exit evidence pending",
    )

    private fun prepare(incidents: List<RecordingIncident>): IncidentHistoryPresentation =
        prepareIncidentHistory(incidents, Locale.US, ZoneId.of("UTC"))

    private fun roundTrip(incidents: List<RecordingIncident>): List<RecordingIncident> {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { RecordingIncidentStore.writeHistoryPayload(it, incidents) }
        }.toByteArray()
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            readIncidentPayloadExact(input, "incident history", RecordingIncidentStore::readHistoryPayload)
        }
    }

    @Test
    fun sameTimestampDistinctCaptureIdentities_haveDistinctProductionKeysAfterPersistence() {
        val incidents = listOf(
            session,
            session.copy(pid = 43),
            session.copy(captureArmedAtMillis = 8_000L),
            session.copy(processStartedAtMillis = 6_000L),
        )
        incidents.forEachIndexed { index, incident ->
            incidents.drop(index + 1).forEach { other ->
                assertFalse(recordingIncidentReferenceMatches(incident, other))
            }
        }

        val rows = prepare(roundTrip(incidents)).rows

        assertEquals(incidents.asReversed(), rows.map { it.incident })
        assertEquals(incidents.size, rows.map { it.key }.toSet().size)
        assertEquals(rows.map { recordingIncidentPresentationKey(it.incident) }, rows.map { it.key })
    }

    @Test
    fun captureEvidenceEnrichment_preservesKeyWhenOccurrenceMoves() {
        val enriched = mergeRecordingIncidentEvidence(
            session,
            session.copy(
                occurredAtMillis = 9_500L,
                resumedAtMillis = 11_000L,
                exitReason = ApplicationExitInfo.REASON_CRASH,
                exitStatus = 1,
                importance = 100,
                pssKb = 512L,
                rssKb = 1_024L,
                description = "Crash evidence",
            ),
        )

        assertTrue(recordingIncidentReferenceMatches(session, enriched))
        assertNotEquals(session.occurredAtMillis, enriched.occurredAtMillis)
        assertEquals(prepare(listOf(session)).rows.single().key, prepare(listOf(enriched)).rows.single().key)
    }

    @Test
    fun acknowledgementAndDetails_doNotChangeAnyIncidentKey() {
        val incidents = listOf(
            session,
            session.copy(kind = RecordingIncidentKind.UNEXPECTED_ERROR, captureArmedAtMillis = -1L),
            RecordingIncident(occurredAtMillis = 10_000L),
        )
        incidents.forEach { incident ->
            val changed = incident.copy(
                acknowledgedAtMillis = 20_000L,
                resumedAtMillis = 30_000L,
                exitReason = ApplicationExitInfo.REASON_CRASH_NATIVE,
                exitStatus = 6,
                importance = 200,
                pssKb = 256L,
                rssKb = 512L,
                description = "Updated evidence",
            )
            assertEquals(recordingIncidentPresentationKey(incident), recordingIncidentPresentationKey(changed))
        }
    }

    @Test
    fun sameTimestampAppErrors_distinguishPidAndKnownProcessLifetime() {
        val error = session.copy(kind = RecordingIncidentKind.UNEXPECTED_ERROR, captureArmedAtMillis = -1L)
        val incidents = listOf(error, error.copy(pid = 43), error.copy(processStartedAtMillis = 6_000L))

        assertFalse(recordingIncidentReferenceMatches(error, incidents[1]))
        assertFalse(recordingIncidentReferenceMatches(error, incidents[2]))
        assertEquals(incidents.size, prepare(roundTrip(incidents)).rows.map { it.key }.toSet().size)
    }

    @Test
    fun legacyRecords_distinguishKindAndOccurrence_andKeepTheirPresentation() {
        val shutdown = RecordingIncident(occurredAtMillis = 10_000L)
        val incidents = listOf(
            shutdown,
            shutdown.copy(occurredAtMillis = 11_000L),
            shutdown.copy(kind = RecordingIncidentKind.UNEXPECTED_ERROR),
        )
        val before = prepare(incidents)
        val after = prepare(roundTrip(incidents))

        assertEquals(incidents.size, after.rows.map { it.key }.toSet().size)
        assertEquals(before.rows, after.rows)
        assertEquals(incidents.asReversed(), after.rows.map { it.incident })
        assertTrue(after.hasAlert)
    }

    @Test
    fun learningUnknownProcessStart_explicitlyChangesLegacyKey_withoutChangingReferenceRules() {
        for (incident in listOf(
            session.copy(processStartedAtMillis = -1L),
            session.copy(kind = RecordingIncidentKind.UNEXPECTED_ERROR, captureArmedAtMillis = -1L,
                processStartedAtMillis = -1L),
        )) {
            val enriched = incident.copy(processStartedAtMillis = 5_000L)

            assertTrue(recordingIncidentReferenceMatches(incident, enriched))
            assertNotEquals(recordingIncidentPresentationKey(incident), recordingIncidentPresentationKey(enriched))
        }
    }

    @Test
    fun unrelatedRowsAndInputOrder_doNotChangeExistingKeys() {
        val key = prepare(listOf(session)).rows.single().key
        val sameArmOtherLifetime = session.copy(processStartedAtMillis = 6_000L)
        val later = session.copy(occurredAtMillis = 20_000L, captureArmedAtMillis = 19_000L)

        for (incidents in listOf(
            listOf(session, sameArmOtherLifetime, later),
            listOf(later, sameArmOtherLifetime, session),
        )) {
            assertEquals(key, prepare(incidents).rows.single { it.incident == session }.key)
        }
    }
}
