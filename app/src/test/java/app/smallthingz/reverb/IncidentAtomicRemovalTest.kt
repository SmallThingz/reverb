package app.smallthingz.reverb

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentAtomicRemovalTest {
    @Test(timeout = 5_000)
    fun unlinkSuccessThenFailedBarriers_requireANewBarrierOnEveryMissingRetry() {
        Fixture().use { fixture ->
            fixture.paths.forEach { it.writeBytes(ORIGINAL) }
            val first = IOException("first directory sync failed")
            val second = IOException("second directory sync failed")
            var attempts = 0
            val sync: (File) -> Unit = { parent ->
                assertEquals(fixture.directory, parent)
                attempts++
                when (attempts) {
                    1 -> throw first
                    2 -> throw second
                    else -> forceRecordingDirectoryDurable(parent)
                }
            }
            assertSame(first, assertThrows(IOException::class.java) { fixture.remove(sync) })
            assertEquals(StoragePathState.MISSING, atomicFileRegularBackingState(fixture.base))
            assertEquals(1, fixture.removals)
            assertSame(second, assertThrows(IOException::class.java) { fixture.remove(sync) })
            assertEquals(1, fixture.removals) // Missing retry cannot repeat the deletion.
            fixture.remove(sync)
            assertEquals(3, attempts)
            assertEquals(1, fixture.removals)
            assertEquals(StoragePathState.MISSING, atomicFileRegularBackingState(fixture.base))
        }
    }

    @Test(timeout = 5_000)
    fun initiallyMissingState_stillRequiresTheRealParentBarrier() {
        Fixture().use { fixture ->
            var synced = false
            fixture.remove { parent ->
                forceRecordingDirectoryDurable(parent)
                synced = true
            }
            assertTrue(synced)
            assertEquals(0, fixture.removals)
        }
    }

    @Test(timeout = 5_000)
    fun missingMarkerRetry_keepsOwnerAndEvidenceThroughRepeatedBarrierFailure() {
        Fixture().use { fixture ->
            fixture.base.writeBytes(ORIGINAL)
            val failure = IOException("retirement sync failed")
            assertSame(failure, assertThrows(IOException::class.java) {
                fixture.remove { throw failure }
            })
            val owner = Any()
            val owners = mutableListOf(owner)
            var appends = 0
            var syncs = 0
            val repeated = IOException("retry sync failed")
            fun retry(sync: (File) -> Unit) {
                val iterator = owners.iterator()
                assertSame(owner, iterator.next())
                completeMissingIncidentMarkerRetry(
                    fixture.base,
                    preserveIncident = { appends++ },
                    retireRetry = { iterator.remove() },
                    syncDirectory = { parent -> syncs++; sync(parent) },
                )
            }
            repeat(2) {
                assertSame(repeated, assertThrows(IOException::class.java) { retry { throw repeated } })
                assertEquals(listOf(owner), owners)
                assertEquals(0, appends)
            }
            retry(::forceRecordingDirectoryDurable)
            assertTrue(owners.isEmpty())
            assertEquals(1, appends)
            assertEquals(3, syncs)
            assertEquals(1, fixture.removals)
        }
    }

    @Test(timeout = 5_000)
    fun retainedIncidentFailure_preservesPrimaryAndRetryUntilAppendCompletes() {
        Fixture().use { fixture ->
            val owner = Any()
            val owners = mutableListOf(owner)
            val failure = IOException("history publication failed")
            var writes = 0
            var syncs = 0
            val incident = RecordingIncident(occurredAtMillis = 100L, pid = 42, captureArmedAtMillis = 50L)
            var history = emptyList<RecordingIncident>()
            fun retry(failAfterAppend: Boolean) {
                val iterator = owners.iterator()
                assertSame(owner, iterator.next())
                completeMissingIncidentMarkerRetry(
                    fixture.base,
                    preserveIncident = {
                        // Exercise the existing same-evidence merge after a published append whose
                        // terminal acknowledgement failed. The production append path is unchanged.
                        val existing = history.singleOrNull()
                        val next = if (existing == null) incident else mergeRecordingIncidentEvidence(existing, incident)
                        if (history != listOf(next)) { history = listOf(next); writes++ }
                        if (failAfterAppend) throw failure
                    },
                    retireRetry = { iterator.remove() },
                    syncDirectory = { forceRecordingDirectoryDurable(it); syncs++ },
                )
            }
            assertSame(failure, assertThrows(IOException::class.java) { retry(true) })
            assertEquals(listOf(owner), owners)
            retry(false)
            assertTrue(owners.isEmpty())
            assertEquals(listOf(incident), history)
            assertEquals(1, writes)
            assertEquals(2, syncs)
        }
    }

    @Test(timeout = 5_000)
    fun reappearingAtomicMembersAfterUnlink_areRejectedAndNeverDeletedAgain() {
        for (suffix in SUFFIXES) Fixture().use { fixture ->
            fixture.base.writeBytes(ORIGINAL)
            val replacement = fixture.member(suffix)
            assertThrows(IOException::class.java) {
                fixture.remove { parent ->
                    replacement.writeBytes(REPLACEMENT)
                    forceRecordingDirectoryDurable(parent)
                }
            }
            assertEquals(1, fixture.removals)
            assertArrayEquals(REPLACEMENT, replacement.readBytes())
        }
    }

    @Test(timeout = 5_000)
    fun missingRetryPreservesEveryReappearedMemberAndRetainsItsOwner() {
        for (suffix in SUFFIXES) Fixture().use { fixture ->
            val owners = mutableListOf(Any())
            val iterator = owners.iterator()
            iterator.next()
            var appends = 0
            val replacement = fixture.member(suffix)
            assertThrows(IOException::class.java) {
                completeMissingIncidentMarkerRetry(
                    fixture.base,
                    preserveIncident = { appends++ },
                    retireRetry = { iterator.remove() },
                    syncDirectory = { parent ->
                        replacement.writeBytes(REPLACEMENT)
                        forceRecordingDirectoryDurable(parent)
                    },
                )
            }
            assertEquals(1, owners.size)
            assertEquals(0, appends)
            assertEquals(0, fixture.removals)
            assertArrayEquals(REPLACEMENT, replacement.readBytes())
            // A subsequent missing-marker retry has no deletion authority over this new state.
            assertThrows(IOException::class.java) {
                completeMissingIncidentMarkerRetry(fixture.base, { appends++ }, { iterator.remove() })
            }
            assertEquals(1, owners.size)
            assertEquals(0, appends)
            assertArrayEquals(REPLACEMENT, replacement.readBytes())
        }
    }

    @Test(timeout = 5_000)
    fun unavailableAtomicMembers_failBeforeDeletionOrOwnerRetirement() {
        for (suffix in SUFFIXES) Fixture().use { fixture ->
            val unavailable = fixture.member(suffix)
            assertTrue(unavailable.mkdir()) // Real non-regular state, not a fabricated enum.
            var synced = false
            var retired = false
            assertThrows(IOException::class.java) { fixture.remove { synced = true } }
            assertThrows(IOException::class.java) {
                completeMissingIncidentMarkerRetry(
                    fixture.base, { error("must not append") }, { retired = true }, { synced = true },
                )
            }
            assertEquals(0, fixture.removals)
            assertFalse(synced)
            assertFalse(retired)
            assertTrue(unavailable.isDirectory)
        }
    }

    @Test(timeout = 5_000)
    fun unavailableStateAfterBarrier_retainsOwnerAndOriginalState() {
        for (suffix in SUFFIXES) Fixture().use { fixture ->
            val unavailable = fixture.member(suffix)
            var retired = false
            assertThrows(IOException::class.java) {
                completeMissingIncidentMarkerRetry(
                    fixture.base, { error("must not append") }, { retired = true },
                    syncDirectory = { parent ->
                        assertTrue(unavailable.mkdir())
                        forceRecordingDirectoryDurable(parent)
                    },
                )
            }
            assertFalse(retired)
            assertTrue(unavailable.isDirectory)
        }
    }

    @Test(timeout = 5_000)
    fun removalExceptionRemainsPrimaryAndDoesNotRunSuccessBarrier() {
        Fixture().use { fixture ->
            fixture.base.writeBytes(ORIGINAL)
            val failure = IOException("remove failed", IOException("underlying I/O"))
            var synced = false
            assertSame(failure, assertThrows(IOException::class.java) {
                deleteIncidentAtomicStateDurably(fixture.base, { throw failure }, { synced = true })
            })
            assertFalse(synced)
            assertArrayEquals(ORIGINAL, fixture.base.readBytes())
            assertEquals("underlying I/O", failure.cause?.message)
        }
    }

    @Test(timeout = 5_000)
    fun nonthrowingFailedRemoval_cannotAcknowledgeDurability() {
        for (suffix in SUFFIXES) Fixture().use { fixture ->
            val retained = fixture.member(suffix)
            retained.writeBytes(ORIGINAL)
            var synced = false
            assertThrows(IOException::class.java) {
                deleteIncidentAtomicStateDurably(fixture.base, {}, { synced = true })
            }
            assertFalse(synced)
            assertArrayEquals(ORIGINAL, retained.readBytes())
        }
    }

    private class Fixture : Closeable {
        val directory: File = Files.createTempDirectory(
            File("build/tmp/incident-atomic-removal").apply { mkdirs() }.toPath(), "owned-",
        ).toFile()
        val base = File(directory, "session")
        val paths get() = SUFFIXES.map(::member)
        var removals = 0
        fun member(suffix: String) = File(base.path + suffix)
        fun remove(sync: (File) -> Unit = ::forceRecordingDirectoryDurable) =
            deleteIncidentAtomicStateDurably(base, removePresent = {
                removals++
                paths.forEach { Files.deleteIfExists(it.toPath()) }
            }, syncDirectory = sync)
        override fun close() { check(directory.deleteRecursively()) }
    }

    companion object {
        private val SUFFIXES = listOf("", ".new", ".bak")
        private val ORIGINAL = byteArrayOf(1, 3, 5, 7)
        private val REPLACEMENT = byteArrayOf(2, 4, 6, 8)
    }
}
