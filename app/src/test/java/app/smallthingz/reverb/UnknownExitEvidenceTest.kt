package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownExitEvidenceTest {
    private val marker = ActiveRecordingSessionMarker(
        armed = true,
        pid = 42,
        processStartElapsedRealtimeMillis = 10L,
        armedAtMillis = 150L,
        armedElapsedRealtimeMillis = 60L,
        packageLastUpdateTimeMillis = 1L,
    )
    private val owner = PendingRecordingSession(marker, endedByMillis = 300L)
    private val provisional = RecordingIncident(
        occurredAtMillis = 300L,
        resumedAtMillis = 0L,
        pid = marker.pid,
        processStartedAtMillis = recordingSessionProcessStartedAtMillis(marker),
        captureArmedAtMillis = marker.armedAtMillis,
        description = "Recording process ended while capture was running; exit evidence pending",
    )

    private fun observedUnknown(status: Int = 0, description: String? = "Android exit detail") =
        provisional.copy(
            occurredAtMillis = 200L,
            exitReason = ApplicationExitInfo.REASON_UNKNOWN,
            exitStatus = status,
            importance = 125,
            pssKb = 1_024L,
            rssKb = 2_048L,
            description = description,
        )

    @Test
    fun observedUnknown_preservesEveryAndroidField_includingZeroStatus() {
        for (status in listOf(0, 7)) {
            val observed = observedUnknown(status)
            val merged = mergeRecordingIncidentEvidence(provisional, observed, incomingExitObserved = true)
            assertEquals(observed, merged)
            assertEquals(listOf(observed), decodeHistory(encodeHistory(listOf(merged))))
            val row = prepareIncidentHistory(listOf(merged)).rows.single()
            assertTrue(row.cause.contains("status $status"))
            assertTrue(row.runtime.contains("RSS"))
            assertTrue(row.runtime.contains("PSS"))
            assertEquals(observed.description, row.description)
        }
    }

    @Test
    fun durableUnknownEvidence_usesExplicitAbsentStatusSentinel_notZeroAsAbsence() {
        assertFalse(recordingIncidentHasObservedExitEvidence(provisional))
        assertTrue(recordingIncidentHasObservedExitEvidence(observedUnknown(status = 0)))
        assertTrue(recordingIncidentHasObservedExitEvidence(
            provisional.copy(exitReason = ApplicationExitInfo.REASON_CRASH),
        ))
        // Codec replay retains the status witness without a new history format.
        val replayed = decodeHistory(encodeHistory(listOf(observedUnknown(description = null)))).single()
        assertTrue(recordingIncidentHasObservedExitEvidence(replayed))
    }

    @Test
    fun observedNullDescription_staysResolvedAcrossCodecProvisionalAndResumeMerges() {
        val observed = observedUnknown(description = null)
        val merged = mergeRecordingIncidentEvidence(provisional, observed, incomingExitObserved = true)
        assertEquals(null, merged.description)
        val restored = decodeHistory(encodeHistory(listOf(merged))).single()
        val laterProvisional = provisional.copy(occurredAtMillis = 400L)
        val retried = mergeRecordingIncidentEvidence(restored, laterProvisional)
        assertEquals(observed, retried)
        val resumed = completeRecordingIncidentDowntimes(listOf(retried), 500L).single()
        assertEquals(observed.copy(resumedAtMillis = 500L), resumed)
        assertEquals(null, mergeRecordingIncidentEvidence(resumed, laterProvisional).description)
        assertEquals(null, prepareIncidentHistory(listOf(resumed)).rows.single().description)
    }

    @Test
    fun laterProvisionalCannotReplaceObservedUnknownDetailsAfterDiskReplay() {
        val observed = decodeHistory(encodeHistory(listOf(observedUnknown()))).single()
        assertEquals(observed, mergeRecordingIncidentEvidence(
            observed,
            provisional.copy(occurredAtMillis = 450L, description = "Service stopped"),
        ))
    }

    @Test
    fun enrichmentPreservesEarlierOutageResumeCheckedDeletionAndPresentationIdentity() {
        for (acknowledgement in listOf(0L, 400L, Long.MIN_VALUE)) {
            val stopped = provisional.copy(
                occurredAtMillis = 175L,
                resumedAtMillis = 350L,
                acknowledgedAtMillis = acknowledgement,
            )
            val merged = mergeRecordingIncidentEvidence(stopped, observedUnknown(), incomingExitObserved = true)
            assertEquals(observedUnknown().copy(
                occurredAtMillis = 175L,
                resumedAtMillis = 350L,
                acknowledgedAtMillis = acknowledgement,
            ), merged)
            assertTrue(recordingIncidentReferenceMatches(stopped, merged))
            assertEquals(recordingIncidentPresentationKey(stopped), recordingIncidentPresentationKey(merged))
        }
    }

    @Test
    fun knownReasonOutranksObservedUnknown_inEitherMergeOrder() {
        val known = observedUnknown().copy(
            exitReason = ApplicationExitInfo.REASON_CRASH,
            exitStatus = 9,
            description = "Known crash",
        )
        val unknown = observedUnknown()
        assertEquals(known, mergeRecordingIncidentEvidence(unknown, known))
        assertEquals(known, mergeRecordingIncidentEvidence(known, unknown, incomingExitObserved = true))
        assertEquals(known, mergeRecordingIncidentEvidence(provisional, known))
    }

    @Test
    fun unresolvedProvisionalRemainsUnknownWithoutFabricatedObservation() {
        val unresolved = mergeRecordingIncidentEvidence(provisional, provisional.copy(occurredAtMillis = 350L))
        assertEquals(provisional, unresolved)
        assertFalse(recordingIncidentHasObservedExitEvidence(unresolved))
        assertEquals("Later provisional detail", mergeRecordingIncidentEvidence(
            provisional.copy(description = null),
            provisional.copy(description = "Later provisional detail"),
        ).description)
    }

    @Test
    fun productionResolutionPublishesUnknownHistoryBeforeRetiringFixedOwner() {
        val fixture = RecoveryFixture()
        fixture.recover()
        assertEquals(listOf(observedUnknown()), fixture.history())
        assertTrue(fixture.pending().isEmpty())
        assertEquals(listOf("history durable", "owner retired"), fixture.events)
        assertEquals(3, fixture.pendingBytes[4].toInt()) // V3 also carries optional boot identity.
    }

    @Test
    fun failedHistoryWriteBeforePublication_keepsOwnerAndOriginalHistoryForRetry() {
        val fixture = RecoveryFixture()
        val originalPending = fixture.pendingBytes.copyOf()
        fixture.failBeforePublication = 1
        assertSame(fixture.historyFailure, assertThrows(IOException::class.java) { fixture.recover() })
        assertArrayEquals(originalPending, fixture.pendingBytes)
        assertEquals(listOf(provisional), fixture.history())
        assertEquals(0, fixture.retirements)
        fixture.recover()
        assertEquals(listOf(observedUnknown()), fixture.history())
        assertTrue(fixture.pending().isEmpty())
    }

    @Test
    fun failedHistoryBarrier_keepsOwnerUntilExactUnknownPayloadIsConfirmed() {
        val fixture = RecoveryFixture()
        val originalPending = fixture.pendingBytes.copyOf()
        fixture.failAfterPublication = 1
        assertSame(fixture.historyFailure, assertThrows(IOException::class.java) { fixture.recover() })
        assertArrayEquals(originalPending, fixture.pendingBytes)
        assertEquals(listOf(observedUnknown()), fixture.history()) // Visible, still unconfirmed.
        assertEquals(listOf(provisional), decodeHistory(fixture.durableHistoryBytes))
        assertEquals(0, fixture.retirements)
        fixture.recover()
        assertEquals(listOf(observedUnknown()), decodeHistory(fixture.durableHistoryBytes))
        assertTrue(fixture.pending().isEmpty())
        assertEquals(1, fixture.history().size)
        assertEquals("owner retired", fixture.events.last())
    }

    @Test
    fun failedOwnerRetirement_replaysUnknownEvidenceWithoutDuplicateOrStateLoss() {
        val fixture = RecoveryFixture()
        fixture.visibleHistoryBytes = encodeHistory(listOf(
            provisional.copy(resumedAtMillis = 350L, acknowledgedAtMillis = Long.MIN_VALUE),
        ))
        fixture.durableHistoryBytes = fixture.visibleHistoryBytes.copyOf()
        fixture.failRetirement = 1
        assertSame(fixture.retirementFailure, assertThrows(IOException::class.java) { fixture.recover() })
        assertEquals(listOf(owner), fixture.pending())
        assertTrue(fixture.history().single().deleted)
        fixture.recover()
        assertTrue(fixture.pending().isEmpty())
        assertEquals(listOf(observedUnknown().copy(
            resumedAtMillis = 350L,
            acknowledgedAtMillis = Long.MIN_VALUE,
        )), fixture.history())
    }

    @Test
    fun missingEvidenceAndLegacyUnknownBoundsRemainPendingWithoutWidening() {
        val legacyUnknown = PendingRecordingSession(marker.copy(pid = 43), resumedAtMillis = 700L)
        val pending = decodePending(encodePending(listOf(owner, legacyUnknown)))
        var remaining = pending
        var writes = 0
        resolvePendingCaptureExitEvidence(pending, { null }, { error("No observed exit") }, {
            writes++
            remaining = it
        })
        assertEquals(0, writes)
        assertEquals(pending, remaining)
        resolvePendingCaptureExitEvidence(
            pending,
            { candidate ->
                if (pendingCaptureExitTimestampMatches(candidate, 42, 200L)) observedUnknown() else null
            },
            { assertEquals(observedUnknown(), it) },
            { remaining = decodePending(encodePending(it)) },
        )
        assertEquals(listOf(legacyUnknown), remaining)
        assertEquals(0L, remaining.single().endedByMillis)
        assertFalse(pendingCaptureExitTimestampMatches(remaining.single(), 43, 500L))
    }

    private inner class RecoveryFixture {
        private val file = File("build/tmp/unknown-exit-evidence/history")
        private val publication = IncidentHistoryPublication()
        val historyFailure = IOException("history publication failed")
        val retirementFailure = IOException("pending retirement failed")
        var failBeforePublication = 0
        var failAfterPublication = 0
        var failRetirement = 0
        var pendingBytes = encodePending(listOf(owner))
        var visibleHistoryBytes = encodeHistory(listOf(provisional))
        var durableHistoryBytes = visibleHistoryBytes.copyOf()
        val events = mutableListOf<String>()
        var retirements = 0

        fun history() = decodeHistory(visibleHistoryBytes)
        fun pending() = decodePending(pendingBytes)

        private fun publish(target: File, incidents: List<RecordingIncident>, started: () -> Unit) {
            assertEquals(file, target)
            if (failBeforePublication > 0) {
                failBeforePublication--
                throw historyFailure
            }
            started()
            visibleHistoryBytes = encodeHistory(incidents)
            if (failAfterPublication > 0) {
                failAfterPublication--
                throw historyFailure
            }
            durableHistoryBytes = visibleHistoryBytes.copyOf()
            events += "history durable"
        }

        fun recover() {
            resolvePendingCaptureExitEvidence(
                pending = pending(),
                findIncident = {
                    if (pendingCaptureExitTimestampMatches(it, 42, 200L)) observedUnknown() else null
                },
                publishObservedIncident = { incident ->
                    // Same production history-read confirmation, identity merge, and exact
                    // payload publication coordinator as appendIncident/writeHistory.
                    publication.confirmPending(file, ::publish)
                    val existing = history()
                    val index = existing.indexOfFirst { recordingIncidentReferenceMatches(it, incident) }
                    val updated = existing.toMutableList()
                    if (index >= 0) {
                        updated[index] = mergeRecordingIncidentEvidence(
                            existing[index], incident, incomingExitObserved = true,
                        )
                    } else {
                        updated += incident
                    }
                    publication.write(file, updated, ::publish) {}
                },
                writeRemaining = {
                    // Simulate the durability acknowledgement boundary, not Android I/O.
                    assertArrayEquals(visibleHistoryBytes, durableHistoryBytes)
                    if (failRetirement > 0) {
                        failRetirement--
                        throw retirementFailure
                    }
                    pendingBytes = encodePending(it)
                    retirements++
                    events += "owner retired"
                },
            )
        }
    }

    private fun encodeHistory(incidents: List<RecordingIncident>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { RecordingIncidentStore.writeHistoryPayload(it, incidents) }
        }.toByteArray()

    private fun decodeHistory(bytes: ByteArray): List<RecordingIncident> =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            readIncidentPayloadExact(it, "unknown exit history", RecordingIncidentStore::readHistoryPayload)
        }

    private fun encodePending(pending: List<PendingRecordingSession>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { RecordingIncidentStore.writePendingSessionsPayload(it, pending) }
        }.toByteArray()

    private fun decodePending(bytes: ByteArray): List<PendingRecordingSession> =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            readIncidentPayloadExact(it, "unknown exit pending", RecordingIncidentStore::readPendingSessionsPayload)
        }
}
