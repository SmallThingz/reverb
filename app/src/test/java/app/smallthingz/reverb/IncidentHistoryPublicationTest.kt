package app.smallthingz.reverb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentHistoryPublicationTest {
    @Test
    fun visibleCheckWithFailedBarrier_recoversWithoutReapplyingToggle() {
        Fixture().use { fixture ->
            fixture.failAfterPublication = 1
            val failed = assertThrows(IOException::class.java) { fixture.toggle() }
            assertEquals("directory barrier failed", failed.message)
            assertTrue(fixture.readVisible().single().acknowledged)
            assertEquals(1, fixture.revisions)

            assertTrue(fixture.readAuthoritative().single().acknowledged)
            assertEquals(2, fixture.revisions)
            assertTrue(fixture.readAuthoritative().single().acknowledged)
            assertEquals(2, fixture.revisions)
            assertEquals(1, fixture.toggles)
        }
    }

    @Test
    fun repeatedConfirmationFailure_retainsExactPayloadWithoutRevisionStorm() {
        Fixture().use { fixture ->
            fixture.failAfterPublication = 3
            assertThrows(IOException::class.java) { fixture.toggle() }
            repeat(2) {
                assertThrows(IOException::class.java) { fixture.readAuthoritative() }
                assertEquals(1, fixture.revisions)
                assertTrue(fixture.readVisible().single().acknowledged)
            }
            assertTrue(fixture.readAuthoritative().single().acknowledged)
            assertEquals(2, fixture.revisions)
            assertEquals(1, fixture.toggles)
        }
    }

    @Test
    fun repeatedPrepublicationRecoveryFailure_onlyWakesHistoryOnce() {
        Fixture().use { fixture ->
            fixture.failBeforePublication = 10
            repeat(10) {
                assertThrows(IOException::class.java) { fixture.toggle() }
                assertEquals(1, fixture.revisions)
                assertFalse(fixture.readVisible().single().acknowledged)
            }
            assertFalse(fixture.readAuthoritative().single().acknowledged)
            assertEquals(2, fixture.revisions)
            fixture.failBeforePublication = 1
            assertThrows(IOException::class.java) { fixture.toggle() }
            assertEquals(3, fixture.revisions)
        }
    }

    @Test
    fun internalConfirmationDoesNotEndEpisodeBeforeWholeRecoveryReadSucceeds() {
        Fixture().use { fixture ->
            fixture.failAfterPublication = 1
            assertThrows(IOException::class.java) { fixture.toggle() }
            fixture.publication.confirmPending(fixture.file, fixture::publish)
            assertEquals(1, fixture.revisions)
            // A later step of the same recovery still fails. An internal confirmation must
            // not rearm the wake-up budget and cancel the collector's existing backoff.
            fixture.failBeforePublication = 5
            repeat(5) {
                assertThrows(IOException::class.java) { fixture.toggle() }
                assertEquals(1, fixture.revisions)
            }
            assertTrue(fixture.readAuthoritative().single().acknowledged)
            assertEquals(2, fixture.revisions)
        }
    }

    @Test
    fun nextAcceptedToggleWaitsForPriorPublicationAndThenInvertsExactlyOnce() {
        Fixture().use { fixture ->
            fixture.failAfterPublication = 1
            assertThrows(IOException::class.java) { fixture.toggle() }
            fixture.toggle()
            assertFalse(fixture.readAuthoritative().single().acknowledged)
            assertEquals(2, fixture.toggles)
            assertEquals(3, fixture.revisions) // first wake, second mutation, completed recovery
        }
    }

    @Test
    fun newMutationCannotReadOrOverwritePriorTargetWhileConfirmationStillFails() {
        Fixture().use { fixture ->
            fixture.failAfterPublication = 2
            assertThrows(IOException::class.java) { fixture.toggle() }
            assertThrows(IOException::class.java) { fixture.toggle() }
            assertEquals(1, fixture.toggles)
            assertEquals(1, fixture.revisions)
            assertTrue(fixture.readAuthoritative().single().acknowledged)
            assertEquals(1, fixture.toggles)
        }
    }

    @Test
    fun uncertainTombstoneBecomesAuthoritativeWithoutResurrectingDeletedCard() {
        Fixture().use { fixture ->
            val tombstone = fixture.readVisible().single().copy(acknowledgedAtMillis = Long.MIN_VALUE)
            fixture.failAfterPublication = 2
            assertThrows(IOException::class.java) { fixture.write(listOf(tombstone)) }
            assertTrue(fixture.readVisible().single().deleted)
            assertThrows(IOException::class.java) { fixture.readAuthoritative() }
            assertEquals(1, fixture.revisions)
            assertEquals(listOf(tombstone), fixture.readAuthoritative())
            assertEquals(2, fixture.revisions)
            assertTrue(fixture.readAuthoritative().filterNot { it.deleted }.isEmpty())
        }
    }

    @Test
    fun pendingPayloadIsAnImmutableSnapshotAndKeepsItsOwnFileIdentity() {
        Fixture().use { fixture ->
            val desired = fixture.readVisible().map { it.copy(acknowledgedAtMillis = 50L) }.toMutableList()
            fixture.failAfterPublication = 1
            assertThrows(IOException::class.java) { fixture.write(desired) }
            desired.clear()
            val otherFile = File(fixture.directory, "other-history")
            fixture.publication.write(otherFile, emptyList(), fixture::publish, fixture::signal)
            assertTrue(fixture.readVisible().single().acknowledged)
            assertEquals(emptyList<RecordingIncident>(), fixture.decode(otherFile))
        }
    }

    @Test
    fun prepublicationFailurePreservesPrimaryAndDoesNotAcquireReplayAuthority() {
        Fixture().use { fixture ->
            val primary = IOException("payload failed")
            val rollback = IOException("rollback failed")
            primary.addSuppressed(rollback)
            assertSame(primary, assertThrows(IOException::class.java) {
                fixture.publication.write(fixture.file, emptyList(), { _, _, _ -> throw primary }, fixture::signal)
            })
            assertEquals(listOf(rollback), primary.suppressed.toList())
            var attemptedReplay = false
            fixture.publication.confirmPending(fixture.file) { _, _, _ -> attemptedReplay = true }
            assertFalse(attemptedReplay)
            assertEquals(1, fixture.revisions)
            assertFalse(fixture.readAuthoritative().single().acknowledged)
        }
    }

    @Test
    fun alreadyRecordedExitAfterProcessReplacement_requiresProofBeforeReplacingOldOwner() {
        Fixture().use { fixture ->
            val marker = File(fixture.directory, "process-marker").apply { writeBytes(byteArrayOf(1)) }
            val uncaught = RecordingIncident(
                occurredAtMillis = 10_000L,
                kind = RecordingIncidentKind.UNEXPECTED_ERROR,
                pid = 42,
                description = "Uncaught exception on main: failure",
            )
            fixture.failAfterPublication = 1
            assertThrows(IOException::class.java) { fixture.write(listOf(uncaught)) }
            assertEquals(listOf(uncaught), fixture.readVisible())

            // A replacement process has lost the previous process-local publication tracker.
            val restarted = IncidentHistoryPublication()
            fixture.failAfterPublication = 1
            var markerReplacements = 0
            fun recover() {
                assertTrue(confirmAlreadyRecordedAppExit(fixture.readVisible(), 42, 10_500L) { existing ->
                    restarted.write(fixture.file, existing, fixture::publish, fixture::signal)
                })
                marker.writeBytes(byteArrayOf(2))
                markerReplacements++
            }
            assertThrows(IOException::class.java) { recover() }
            assertArrayEquals(byteArrayOf(1), marker.readBytes())
            assertEquals(0, markerReplacements)
            assertEquals(listOf(uncaught), fixture.readVisible())

            recover()
            assertArrayEquals(byteArrayOf(2), marker.readBytes())
            assertEquals(1, markerReplacements)
            assertEquals(listOf(uncaught), fixture.readVisible())
        }
    }

    @Test
    fun existingExitProofPreservesEveryHistoryRecordAndDeletionIdentity() {
        Fixture().use { fixture ->
            val history = listOf(
                RecordingIncident(10_000L, kind = RecordingIncidentKind.UNEXPECTED_ERROR,
                    pid = 42, description = "Uncaught exception on main: failure"),
                RecordingIncident(20_000L, acknowledgedAtMillis = Long.MIN_VALUE),
            )
            fixture.write(history)
            val before = fixture.file.readBytes()
            var confirmed = false
            assertTrue(confirmAlreadyRecordedAppExit(fixture.readVisible(), 42, 10_200L) { existing ->
                fixture.write(existing)
                confirmed = true
            })
            assertTrue(confirmed)
            assertArrayEquals(before, fixture.file.readBytes())
            assertEquals(history, fixture.readVisible())
        }
    }

    @Test
    fun unmatchedExitCannotClaimExistingHistoryProofOrSkipItsAppend() {
        val history = listOf(RecordingIncident(10_000L, kind = RecordingIncidentKind.UNEXPECTED_ERROR,
            pid = 42, description = "Uncaught exception on main: failure"))
        assertFalse(confirmAlreadyRecordedAppExit(history, 99, 10_000L) { error("must append") })
        assertFalse(confirmAlreadyRecordedAppExit(history, 42, 30_001L) { error("must append") })
    }

    @Test
    fun independentRootsRetainAndReplayOnlyTheirOwnUncertainPublications() {
        Fixture().use { fixture ->
            val other = File(fixture.directory, "fixture-root").apply { mkdirs() }
            val otherFile = File(other, "history")
            val original = listOf(RecordingIncident(10L, acknowledgedAtMillis = Long.MIN_VALUE))
            val synthetic = listOf(RecordingIncident(20L, description = "synthetic"))
            fixture.failAfterPublication = 1
            assertThrows(IOException::class.java) { fixture.write(original) }
            val originalBytes = fixture.file.readBytes()
            fixture.failAfterPublication = 1
            assertThrows(IOException::class.java) {
                fixture.publication.write(otherFile, synthetic, fixture::publish, fixture::signal)
            }
            // A second root neither confirms the first root nor loses its own failed publication.
            assertArrayEquals(originalBytes, fixture.file.readBytes())
            val replayed = mutableListOf<File>()
            fixture.publication.confirmPending(otherFile) { file, incidents, started ->
                replayed += file
                assertEquals(synthetic, incidents)
                fixture.publish(file, incidents, started)
            }
            assertEquals(listOf(otherFile), replayed)
            fixture.publication.confirmPending(otherFile) { _, _, _ -> error("already confirmed") }
            val failure = IOException("original root still unavailable")
            assertSame(failure, assertThrows(IOException::class.java) {
                fixture.publication.confirmPending(fixture.file) { file, incidents, _ ->
                    assertEquals(fixture.file, file)
                    assertEquals(original, incidents)
                    throw failure
                }
            })
            fixture.publication.confirmPending(fixture.file) { file, incidents, started ->
                replayed += file
                fixture.publish(file, incidents, started)
            }
            assertEquals(listOf(otherFile, fixture.file), replayed)
            assertEquals(original, fixture.decode(fixture.file))
            assertEquals(synthetic, fixture.decode(otherFile))
        }
    }

    @Test
    fun anotherRootReadCannotConsumeRecoveryWakeOrTriggerPendingWrite() {
        Fixture().use { fixture ->
            val otherFile = File(fixture.directory, "other/history")
            fixture.failBeforePublication = 1
            assertThrows(IOException::class.java) { fixture.toggle() }
            fixture.publication.confirmPending(otherFile) { _, _, _ -> error("no owned publication") }
            assertFalse(fixture.publication.completeHistoryRead(otherFile))
            assertTrue(fixture.publication.completeHistoryRead(fixture.file))
            assertFalse(fixture.publication.completeHistoryRead(fixture.file))

            fixture.failAfterPublication = 1
            assertThrows(IOException::class.java) { fixture.toggle() }
            fixture.publication.confirmPending(otherFile) { _, _, _ -> error("foreign publication") }
            assertFalse(fixture.publication.completeHistoryRead(otherFile))
            assertTrue(fixture.readAuthoritative().single().acknowledged)
            assertFalse(fixture.publication.completeHistoryRead(fixture.file))
        }
    }

    private class Fixture : Closeable {
        val directory = Files.createTempDirectory(
            File("build/tmp/incident-history-publication").apply { mkdirs() }.toPath(), "owned-",
        ).toFile()
        val file = File(directory, "history")
        val publication = IncidentHistoryPublication()
        var failBeforePublication = 0
        var failAfterPublication = 0
        var revisions = 0
        var toggles = 0

        init {
            publish(file, listOf(RecordingIncident(occurredAtMillis = 10L))) {}
        }

        fun signal() { revisions++ }

        fun publish(target: File, incidents: List<RecordingIncident>, started: () -> Unit) {
            if (failBeforePublication > 0) {
                failBeforePublication--
                throw IOException("payload unavailable")
            }
            val staging = File(target.path + ".new")
            FileOutputStream(staging).use { raw ->
                val output = DataOutputStream(raw)
                RecordingIncidentStore.writeHistoryPayload(output, incidents)
                output.flush()
                raw.fd.sync()
            }
            started()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (failAfterPublication > 0) {
                failAfterPublication--
                throw IOException("directory barrier failed")
            }
            forceRecordingDirectoryDurable(directory)
        }

        fun decode(target: File): List<RecordingIncident> = DataInputStream(target.inputStream()).use { input ->
            readIncidentPayloadExact(input, "test history", RecordingIncidentStore::readHistoryPayload)
        }

        fun readVisible() = decode(file)

        fun readAuthoritative(): List<RecordingIncident> {
            publication.confirmPending(file, ::publish)
            val history = readVisible()
            if (publication.completeHistoryRead(file)) signal()
            return history
        }

        fun write(incidents: List<RecordingIncident>) =
            publication.write(file, incidents, ::publish, ::signal)

        fun toggle() {
            publication.confirmPending(file, ::publish)
            val history = readVisible()
            toggles++
            write(history.map { toggleRecordingIncidentAcknowledgement(it, 50L) })
            signal()
        }

        override fun close() { check(directory.deleteRecursively()) }
    }
}
