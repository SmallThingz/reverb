package app.smallthingz.reverb

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
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

class DurableAtomicFileTest {
    @Test
    fun modernPublication_requiresPayloadSyncIntendedBaseAndDirectoryBarrier() {
        Fixture().use { fixture ->
            fixture.finish()
            assertArrayEquals(NEW, fixture.base.readBytes())
            assertFalse(fixture.newFile.exists())
            assertFalse(fixture.backup.exists())
            assertEquals(listOf("payload-sync", "proof-open", "publication-start", "finish", "directory-sync", "proof-close"), fixture.events)
            assertTrue(fixture.publicationStarted)
            assertFalse(fixture.output.fd.valid())
            assertEquals(1, fixture.proofCloses)
        }
    }

    @Test
    fun legacyPublication_acceptsBoundBaseOnlyAfterBackupRetirement() {
        Fixture(legacy = true).use { fixture ->
            assertArrayEquals(OLD, fixture.backup.readBytes())
            fixture.finish()
            assertArrayEquals(NEW, fixture.base.readBytes())
            assertFalse(fixture.backup.exists())
            assertEquals(1, fixture.directorySyncs)
            assertEquals(1, fixture.proofCloses)
        }
    }

    @Test
    fun throwingPayloadBarrier_neverStartsPublicationOrOpensProof() {
        Fixture().use { fixture ->
            val primary = IOException("payload sync failed")
            val observed = assertThrows(IOException::class.java) {
                fixture.finish(syncPayload = { throw primary })
            }
            assertSame(primary, observed)
            assertFalse(fixture.publicationStarted)
            assertEquals(emptyList<String>(), fixture.events)
            assertArrayEquals(OLD, fixture.base.readBytes())
            assertArrayEquals(NEW, fixture.newFile.readBytes())
            assertTrue(fixture.output.fd.valid()) // The caller still owns pre-publication rollback.
        }
    }

    @Test
    fun duplicateFailure_keepsPrePublicationRollbackOwnership() {
        Fixture().use { fixture ->
            val primary = IOException("dup failed")
            val observed = assertThrows(IOException::class.java) {
                fixture.finish(openProof = { _, _ -> throw primary })
            }
            assertSame(primary, observed)
            assertEquals(listOf("payload-sync"), fixture.events)
            assertFalse(fixture.publicationStarted)
            assertTrue(fixture.output.fd.valid())
            assertArrayEquals(OLD, fixture.base.readBytes())
        }
    }

    @Test
    fun failedCandidateBinding_closesProofAndPreservesPrimaryWithCleanupFailure() {
        Fixture().use { fixture ->
            val closeFailure = IOException("duplicate close failed")
            val observed = assertThrows(IOException::class.java) {
                fixture.finish(openProof = { base, _ ->
                    val heldInput = FileInputStream(fixture.newFile)
                    try {
                        AtomicWritePublicationProof(base, matchesDescriptor = { false }, closeDescriptor = { error("unreachable") })
                    } catch (error: Throwable) {
                        throwAfterClosePreservingPrimary(error) {
                            heldInput.close()
                            fixture.proofCloses++
                            throw closeFailure
                        }
                    }
                })
            }
            assertTrue(observed.message.orEmpty().contains("Unable to bind"))
            assertEquals(listOf(closeFailure), observed.suppressed.toList())
            assertEquals(1, fixture.proofCloses)
            assertFalse(fixture.publicationStarted)
            assertTrue(fixture.output.fd.valid())
        }
    }

    @Test
    fun nonthrowingRenameFailure_rejectsStaleBaseAndPreservesNewBytes() {
        Fixture().use { fixture ->
            assertThrows(IOException::class.java) { fixture.finish(publication = { }) }
            assertTrue(fixture.publicationStarted)
            assertEquals(0, fixture.directorySyncs)
            assertArrayEquals(OLD, fixture.base.readBytes())
            assertArrayEquals(NEW, fixture.newFile.readBytes())
            assertFalse(fixture.output.fd.valid())
            assertEquals(1, fixture.proofCloses)
        }
    }

    @Test
    fun nonthrowingFirstPublicationFailure_rejectsNewFileWithoutBase() {
        Fixture(existing = false).use { fixture ->
            assertThrows(IOException::class.java) { fixture.finish(publication = { }) }
            assertFalse(fixture.base.exists())
            assertArrayEquals(NEW, fixture.newFile.readBytes())
            assertEquals(0, fixture.directorySyncs)
            assertEquals(1, fixture.proofCloses)
        }
    }

    @Test
    fun replacementBase_isRejectedEvenWhenPayloadBytesMatch() {
        Fixture().use { fixture ->
            assertThrows(IOException::class.java) {
                fixture.finish(publication = {
                    fixture.publish()
                    Files.move(fixture.base.toPath(), File(fixture.root, "held-written-object").toPath())
                    fixture.base.writeBytes(NEW)
                })
            }
            assertArrayEquals(NEW, fixture.base.readBytes())
            assertArrayEquals(NEW, File(fixture.root, "held-written-object").readBytes())
            assertEquals(0, fixture.directorySyncs)
        }
    }

    @Test
    fun residualRecoverySiblings_areNotAcceptedAsPublication() {
        for (legacy in listOf(false, true)) {
            Fixture(legacy = legacy).use { fixture ->
                assertThrows(IOException::class.java) {
                    fixture.finish(publication = {
                        if (!legacy) {
                            fixture.publish()
                            fixture.newFile.writeBytes(OLD)
                        } // Legacy models a nonthrowing failed backup deletion.
                    })
                }
                assertArrayEquals(NEW, fixture.base.readBytes())
                assertArrayEquals(OLD, (if (legacy) fixture.backup else fixture.newFile).readBytes())
                assertEquals(0, fixture.directorySyncs)
                assertEquals(1, fixture.proofCloses)
            }
        }
    }

    @Test
    fun finishExceptionAfterPublication_preservesPublishedBytesAndOriginalFailure() {
        Fixture().use { fixture ->
            val primary = IOException("finish failed after rename")
            val observed = assertThrows(IOException::class.java) {
                fixture.finish(publication = {
                    fixture.publish()
                    throw primary
                })
            }
            assertSame(primary, observed)
            assertTrue(fixture.publicationStarted)
            assertArrayEquals(NEW, fixture.base.readBytes())
            assertFalse(fixture.newFile.exists())
            assertFalse(fixture.output.fd.valid())
            assertEquals(1, fixture.proofCloses)
        }
    }

    @Test
    fun directoryBarrierFailure_preservesPublishedBaseWithoutClaimingSuccess() {
        Fixture().use { fixture ->
            assertThrows(IOException::class.java) { fixture.finish(syncDirectory = { false }) }
            assertTrue(fixture.publicationStarted)
            assertArrayEquals(NEW, fixture.base.readBytes())
            assertFalse(fixture.newFile.exists())
            assertFalse(fixture.output.fd.valid())
            assertEquals(1, fixture.proofCloses)
        }
    }

    @Test
    fun baseReplacedDuringDirectoryBarrier_isRejectedByFinalProof() {
        Fixture().use { fixture ->
            assertThrows(IOException::class.java) {
                fixture.finish(syncDirectory = {
                    Files.move(fixture.base.toPath(), File(fixture.root, "held-written-object").toPath())
                    fixture.base.writeBytes(NEW)
                    true
                })
            }
            assertArrayEquals(NEW, fixture.base.readBytes())
            assertEquals(1, fixture.proofCloses)
        }
    }

    @Test
    fun cleanupFailures_areSuppressedOnOriginalPublicationFailure() {
        val streamFailure = IOException("stream close failed")
        val descriptorFailure = IOException("duplicate close failed")
        Fixture(outputCloseFailure = streamFailure).use { fixture ->
            val primary = IOException("finish failed")
            val observed = assertThrows(IOException::class.java) {
                fixture.finish(
                    publication = { throw primary },
                    openProof = { base, _ -> fixture.openFileProof(base, descriptorFailure) },
                )
            }
            assertSame(primary, observed)
            assertEquals(listOf(streamFailure, descriptorFailure), observed.suppressed.toList())
            assertArrayEquals(OLD, fixture.base.readBytes())
            assertArrayEquals(NEW, fixture.newFile.readBytes())
            assertFalse(fixture.output.fd.valid())
            assertEquals(1, fixture.proofCloses)
        }
    }

    private class Fixture(
        existing: Boolean = true,
        private val legacy: Boolean = false,
        outputCloseFailure: IOException? = null,
    ) : Closeable {
        val root: File = Files.createTempDirectory(
            File("build/tmp/durable-atomic-file").apply { mkdirs() }.toPath(),
            "publication-",
        ).toFile()
        val base = File(root, "state")
        val newFile = File(root, "state.new")
        val backup = File(root, "state.bak")
        val output: FileOutputStream
        val events = mutableListOf<String>()
        var publicationStarted = false
        var directorySyncs = 0
        var proofCloses = 0

        init {
            if (existing) base.writeBytes(OLD)
            if (legacy && existing) Files.move(base.toPath(), backup.toPath())
            output = object : FileOutputStream(if (legacy) base else newFile) {
                private var reportedCloseFailure = false
                private var closeInProgress = false
                override fun close() {
                    // JDK IO-over-NIO can reenter this override through the channel's parent.
                    // Inject failure only after the outer close has actually released the fd.
                    if (closeInProgress) {
                        super.close()
                        return
                    }
                    closeInProgress = true
                    try {
                        super.close()
                    } finally {
                        closeInProgress = false
                    }
                    if (outputCloseFailure != null && !reportedCloseFailure) {
                        reportedCloseFailure = true
                        throw outputCloseFailure
                    }
                }
            }
            output.write(NEW)
        }

        fun publish() {
            if (legacy) Files.delete(backup.toPath())
            else Files.move(newFile.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        // JVM tests model the native dup/fstat handle with an open real-file owner and its NIO
        // object identity. They exercise real publication/path checks; Android dup/fstat itself
        // still needs device qualification and is never replaced in the production callers.
        fun openFileProof(baseFile: File, closeFailure: IOException? = null): AtomicWritePublicationProof {
            events += "proof-open"
            val candidate = if (legacy) base else newFile
            val identity = resolveFileIdentity(candidate)
            check(identity.isNotBlank())
            val heldInput = FileInputStream(candidate)
            return try {
                AtomicWritePublicationProof(
                    baseFile,
                    matchesDescriptor = { path -> sameFileObjectAcrossRename(identity, resolveFileIdentity(path)) },
                    closeDescriptor = {
                        heldInput.close()
                        proofCloses++
                        events += "proof-close"
                        if (closeFailure != null) throw closeFailure
                    },
                )
            } catch (error: Throwable) {
                throwAfterClosePreservingPrimary(error) { heldInput.close() }
            }
        }

        fun finish(
            publication: () -> Unit = { publish() },
            syncDirectory: () -> Boolean = { true },
            syncPayload: (FileOutputStream) -> Unit = {
                events += "payload-sync"
                it.fd.sync()
            },
            openProof: (File, FileDescriptor) -> AtomicWritePublicationProof = { base, _ -> openFileProof(base) },
        ) {
            finishDurableAtomicWrite(
                baseFile = base,
                output = output,
                finishWrite = {
                    events += "finish"
                    publication()
                },
                syncDirectory = {
                    directorySyncs++
                    events += "directory-sync"
                    syncDirectory()
                },
                onPublicationStarted = {
                    publicationStarted = true
                    events += "publication-start"
                },
                syncPayload = syncPayload,
                openProof = openProof,
            )
        }

        override fun close() {
            try {
                output.close()
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private companion object {
        val OLD = byteArrayOf(1, 2, 3)
        val NEW = byteArrayOf(8, 7, 6, 5)
    }
}
