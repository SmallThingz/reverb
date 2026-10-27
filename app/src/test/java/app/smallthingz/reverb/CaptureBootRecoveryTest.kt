package app.smallthingz.reverb

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

class CaptureBootRecoveryTest {
    private val firstBootWall = 1_700_000_000_000L
    private val nextBootWall = firstBootWall + 2_000_000L
    private val marker = ActiveRecordingSessionMarker(
        armed = true,
        pid = 42,
        processStartElapsedRealtimeMillis = 60_000L,
        armedAtMillis = firstBootWall + 300_000L,
        armedElapsedRealtimeMillis = 300_000L,
        packageLastUpdateTimeMillis = firstBootWall,
        bootCount = 7,
    )

    @Test
    fun changedBootBeforeAndAfterOldArmUptime_recoversAtBootBoundary() {
        for (elapsed in listOf(120_000L, 1_200_000L)) {
            val fixture = RecoveryFixture()
            assertTrue(fixture.recover(elapsed = elapsed))
            val incident = fixture.history().single()
            assertEquals(nextBootWall, incident.occurredAtMillis)
            assertEquals("Device restarted while capture was running", incident.description)
            assertEquals(marker.armedAtMillis, incident.captureArmedAtMillis)
            assertFalse(recordingIncidentHasObservedExitEvidence(incident))
            assertEquals(listOf("history durable", "marker retired"), fixture.events)
            assertEquals(null, fixture.activeBytes)
            val resumed = completeRecordingIncidentDowntimes(
                fixture.history(), nextBootWall + elapsed + 1_000L,
            ).single()
            assertEquals(elapsed + 1_000L, requireNotNull(recordingIncidentDowntimeMillis(resumed)))
        }
    }

    @Test
    fun sameBootProcessReplacement_remainsEligibleForExistingExitRecovery() {
        val restored = decodeSession(encodeSession(marker))
        val fixture = RecoveryFixture()
        assertFalse(fixture.recover(bootCount = marker.bootCount))
        assertTrue(fixture.history().isEmpty())
        assertTrue(fixture.events.isEmpty())
        assertEquals(restored, decodeSession(requireNotNull(fixture.activeBytes)))
        val endedBy = marker.armedAtMillis + 100_000L
        val pending = decodePending(encodePending(
            appendPendingRecordingSession(emptyList(), restored, endedBy),
        )).single()
        assertEquals(marker, pending.marker)
        assertTrue(pendingCaptureExitTimestampMatches(pending, marker.pid, endedBy - 1L))
        assertFalse(pendingCaptureExitTimestampMatches(pending, marker.pid, endedBy + 1L))
    }

    @Test
    fun sameBootWallClockChangesNeverBecomeRebootEvidence() {
        for (wall in listOf(firstBootWall - 86_400_000L, firstBootWall + 86_400_000L)) {
            val fixture = RecoveryFixture()
            assertFalse(fixture.recover(bootCount = marker.bootCount, wall = wall))
            assertTrue(fixture.history().isEmpty())
            assertEquals(marker, decodeSession(requireNotNull(fixture.activeBytes)))
        }
    }

    @Test
    fun unavailableBootMetadataDoesNotBlockRecoveryOrInventAReboot() {
        val unavailable = readOptionalRecordingBootCount { throw SecurityException("Setting unavailable") }
        assertEquals(-1, unavailable)
        val delayed = RecoveryFixture()
        assertFalse(delayed.recover(bootCount = unavailable, elapsed = 1_200_000L))
        assertEquals(marker, decodeSession(requireNotNull(delayed.activeBytes)))
        val reset = RecoveryFixture()
        assertTrue(reset.recover(bootCount = unavailable, elapsed = 120_000L))
        assertEquals(nextBootWall, reset.history().single().occurredAtMillis)
    }

    @Test
    fun legacySessionV2KeepsUnknownBootAndExistingUptimeFallback() {
        val legacy = decodeSession(legacySessionBytes(marker))
        assertEquals(marker.copy(bootCount = -1), legacy)
        val delayed = RecoveryFixture(legacySessionBytes(marker))
        assertFalse(delayed.recover(elapsed = 1_200_000L))
        val reset = RecoveryFixture(legacySessionBytes(marker))
        assertTrue(reset.recover(elapsed = 120_000L))
        assertEquals(nextBootWall, reset.history().single().occurredAtMillis)
        assertEquals(legacy, decodeSession(encodeSession(legacy)))
    }

    @Test
    fun optionalBootReadAcceptsZeroAndNormalizesMissingOrFailedReads() {
        assertEquals(0, readOptionalRecordingBootCount { 0 })
        assertEquals(123, readOptionalRecordingBootCount { 123 })
        assertEquals(-1, readOptionalRecordingBootCount { -1 })
        assertEquals(-1, readOptionalRecordingBootCount { -2 })
        assertEquals(-1, readOptionalRecordingBootCount { throw IllegalStateException("Provider unavailable") })
    }

    @Test
    fun sessionV3CodecRoundTripsKnownAndUnavailableBootMetadata() {
        for (bootCount in listOf(-1, 0, 7, Int.MAX_VALUE)) {
            val original = marker.copy(bootCount = bootCount)
            val encoded = encodeSession(original)
            assertEquals(3, encoded[4].toInt())
            assertEquals(original, decodeSession(encoded))
        }
    }

    @Test
    fun pendingV1AndV2StayReadableWithoutInventingBootOrDeathBounds() {
        for (version in listOf(1, 2)) {
            val owner = PendingRecordingSession(
                marker.copy(bootCount = -1), resumedAtMillis = nextBootWall,
                endedByMillis = if (version == 1) 0L else marker.armedAtMillis + 100_000L,
            )
            val decoded = decodePending(legacyPendingBytes(version, owner))
            assertEquals(listOf(owner), decoded)
            val rewritten = encodePending(decoded)
            assertEquals(3, rewritten[4].toInt())
            assertEquals(decoded, decodePending(rewritten))
            assertFalse(pendingCaptureExitTimestampMatches(
                decoded.single(), marker.pid, nextBootWall + 1L,
            ))
        }
    }

    @Test
    fun pendingV3CodecPreservesMixedBootMetadataResumeAndFixedDeathBounds() {
        val owners = listOf(
            PendingRecordingSession(marker, resumedAtMillis = nextBootWall, endedByMillis = nextBootWall - 1L),
            PendingRecordingSession(marker.copy(pid = 43, bootCount = -1)),
        )
        assertEquals(owners, decodePending(encodePending(owners)))
    }

    @Test
    fun sessionAndPendingCodecsStillRejectTruncationTrailingBytesAndInvalidBootCount() {
        val session = encodeSession(marker)
        for (invalid in listOf(
            session.copyOf(session.size - 1), session + byteArrayOf(0),
            encodeSession(marker.copy(bootCount = -2)),
            session.copyOf().also { it[4] = 4 },
        )) {
            assertThrows(IOException::class.java) { decodeSession(invalid) }
        }
        val owner = PendingRecordingSession(marker, endedByMillis = nextBootWall)
        val pending = encodePending(listOf(owner))
        for (invalid in listOf(
            pending.copyOf(pending.size - 1), pending + byteArrayOf(0),
            encodePending(listOf(owner.copy(marker = marker.copy(bootCount = -2)))),
            pending.copyOf().also { it[4] = 4 },
        )) {
            assertThrows(IOException::class.java) { decodePending(invalid) }
        }
    }

    @Test
    fun failedHistoryPublicationKeepsSerializedActiveOwnerUntilDurableRetry() {
        for (afterPublication in listOf(false, true)) {
            val fixture = RecoveryFixture()
            val originalMarker = requireNotNull(fixture.activeBytes).copyOf()
            if (afterPublication) fixture.failAfterPublication = true else fixture.failBeforePublication = true
            assertSame(fixture.historyFailure, assertThrows(IOException::class.java) { fixture.recover() })
            assertArrayEquals(originalMarker, fixture.activeBytes)
            assertTrue(fixture.events.isEmpty())
            assertTrue(decodeHistory(fixture.durableHistoryBytes).isEmpty())
            assertTrue(fixture.recover())
            assertEquals(1, fixture.history().size)
            assertEquals(nextBootWall, fixture.history().single().occurredAtMillis)
            assertEquals(null, fixture.activeBytes)
            assertEquals("marker retired", fixture.events.last())
        }
    }

    @Test
    fun failedMarkerRetirementReplaysSameDurableIncidentWithoutDuplicate() {
        val fixture = RecoveryFixture()
        val originalMarker = requireNotNull(fixture.activeBytes).copyOf()
        fixture.failRetirement = true
        assertSame(fixture.retirementFailure, assertThrows(IOException::class.java) { fixture.recover() })
        assertArrayEquals(originalMarker, fixture.activeBytes)
        assertEquals(1, decodeHistory(fixture.durableHistoryBytes).size)
        assertTrue(fixture.recover(elapsed = 1_500_000L))
        assertEquals(1, fixture.history().size)
        assertEquals(nextBootWall, fixture.history().single().occurredAtMillis)
        assertEquals(null, fixture.activeBytes)
    }

    @Test
    fun disarmedSessionDoesNotProduceARebootIncident() {
        val fixture = RecoveryFixture(encodeSession(marker.copy(armed = false)))
        assertFalse(fixture.recover())
        assertTrue(fixture.history().isEmpty())
        assertTrue(fixture.events.isEmpty())
    }

    private inner class RecoveryFixture(initialMarker: ByteArray = encodeSession(marker)) {
        private val file = File("build/tmp/capture-boot-recovery/history")
        private val publication = IncidentHistoryPublication()
        var activeBytes: ByteArray? = initialMarker
        var visibleHistoryBytes = encodeHistory(emptyList())
        var durableHistoryBytes = visibleHistoryBytes.copyOf()
        var failBeforePublication = false
        var failAfterPublication = false
        var failRetirement = false
        val historyFailure = IOException("History publication failed")
        val retirementFailure = IOException("Marker retirement failed")
        val events = mutableListOf<String>()

        fun history() = decodeHistory(visibleHistoryBytes)

        private fun publish(target: File, incidents: List<RecordingIncident>, started: () -> Unit) {
            assertEquals(file, target)
            if (failBeforePublication) {
                failBeforePublication = false
                throw historyFailure
            }
            started()
            visibleHistoryBytes = encodeHistory(incidents)
            if (failAfterPublication) {
                failAfterPublication = false
                throw historyFailure
            }
            durableHistoryBytes = visibleHistoryBytes.copyOf()
            events += "history durable"
        }

        fun recover(
            bootCount: Int = 8,
            elapsed: Long = 1_200_000L,
            wall: Long = nextBootWall + elapsed,
        ): Boolean = RecordingIncidentStore.recoverDeviceRestartedSession(
            marker = decodeSession(requireNotNull(activeBytes)),
            currentBootCount = bootCount,
            currentWallClockMillis = wall,
            currentElapsedRealtimeMillis = elapsed,
            publishIncident = { incident ->
                publication.confirmPending(file, ::publish)
                val existing = history()
                val index = existing.indexOfFirst { recordingIncidentReferenceMatches(it, incident) }
                val updated = existing.toMutableList()
                if (index >= 0) updated[index] = mergeRecordingIncidentEvidence(existing[index], incident)
                else updated += incident
                publication.write(file, updated, ::publish) {}
            },
            retireMarker = {
                assertArrayEquals(visibleHistoryBytes, durableHistoryBytes)
                if (failRetirement) {
                    failRetirement = false
                    throw retirementFailure
                }
                activeBytes = null
                events += "marker retired"
            },
        )
    }

    private fun encodeSession(marker: ActiveRecordingSessionMarker): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { RecordingIncidentStore.writeSessionPayload(it, marker) }
        }.toByteArray()

    private fun decodeSession(bytes: ByteArray): ActiveRecordingSessionMarker =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            readIncidentPayloadExact(it, "test recording session", RecordingIncidentStore::readSessionPayload)
        }

    private fun encodePending(owners: List<PendingRecordingSession>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { RecordingIncidentStore.writePendingSessionsPayload(it, owners) }
        }.toByteArray()

    private fun decodePending(bytes: ByteArray): List<PendingRecordingSession> =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            readIncidentPayloadExact(it, "test pending sessions", RecordingIncidentStore::readPendingSessionsPayload)
        }

    private fun encodeHistory(incidents: List<RecordingIncident>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { RecordingIncidentStore.writeHistoryPayload(it, incidents) }
        }.toByteArray()

    private fun decodeHistory(bytes: ByteArray): List<RecordingIncident> =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            readIncidentPayloadExact(it, "test history", RecordingIncidentStore::readHistoryPayload)
        }

    private fun legacySessionBytes(marker: ActiveRecordingSessionMarker): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(0x52495331)
                it.writeByte(2)
                writeLegacyMarker(it, marker)
            }
        }.toByteArray()

    private fun legacyPendingBytes(version: Int, owner: PendingRecordingSession): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(0x52495031)
                it.writeByte(version)
                it.writeShort(1)
                writeLegacyMarker(it, owner.marker)
                it.writeLong(owner.resumedAtMillis)
                if (version >= 2) it.writeLong(owner.endedByMillis)
            }
        }.toByteArray()

    private fun writeLegacyMarker(output: DataOutputStream, marker: ActiveRecordingSessionMarker) {
        output.writeBoolean(marker.armed)
        output.writeInt(marker.pid)
        output.writeLong(marker.processStartElapsedRealtimeMillis)
        output.writeLong(marker.armedAtMillis)
        output.writeLong(marker.armedElapsedRealtimeMillis)
        output.writeLong(marker.packageLastUpdateTimeMillis)
    }
}
