package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DurablePcmVerificationConcurrencyTest {
    @Test
    fun unrelatedRetentionAndAppendProgressDuringHeldVerificationWhileExactReferenceCannotRetire() = withFixture { fixture ->
        val secondDirectory = jobDirectory(fixture.root)
        val second = DurablePcmPins.link(secondDirectory, fixture.snapshot.range, ::nativeIdentity, ::descriptorProof) { }
        val marker = DurablePcmPins.referenceFile(fixture.directory, fixture.proof)
        val candidate = File(marker.parentFile, marker.name + ".pending").apply { writeBytes(marker.readBytes()) }
        PersistentAudioChunkStore(File(fixture.root, "unrelated")).use { peer ->
            // Build two explicit chunks before shrinking; small windows change rollover granularity.
            configure(peer, Long.MAX_VALUE)
            val first = ByteArray(2048) { 13 }
            val retained = ByteArray(2048) { (it * 7).toByte() }
            assertEquals(first.size, peer.append(first, 0, first.size))
            peer.sealActiveChunk()
            assertEquals(retained.size, peer.append(retained, 0, retained.size))
            peer.sealActiveChunk()
            assertEquals(2, requireNotNull(peer.peekSnapshot()).chunkCount)
            configure(peer, 2048)
            assertTrue(peer.retentionMaintenanceNeeded())
            val bound = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val verification = workers.submit {
                    verify(fixture, afterBinding = {
                        assertFalse(Thread.holdsLock(DurablePcmPins.mutationLock))
                        bound.countDown()
                        check(resume.await(20, TimeUnit.SECONDS)) { "Verifier was not released" }
                    })
                }
                assertTrue(bound.await(5, TimeUnit.SECONDS))
                assertThrows(IOException::class.java) {
                    DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
                }
                assertTrue(marker.isFile && candidate.isFile)
                DurablePcmPins.release(secondDirectory, second.objects, ::descriptorProof)
                assertFalse(DurablePcmPins.hasReference(secondDirectory, second.objects.single()))
                assertTrue(DurablePcmPins.isProtected(fixture.source))
                val capture = workers.submit {
                    var steps = 0
                    while (peer.retentionMaintenanceNeeded()) {
                        check(++steps <= 8)
                        check(peer.performRetentionMaintenanceStep().progressed)
                    }
                    check(steps > 0)
                    configure(peer, 4096)
                    val appended = byteArrayOf(1, 2, 3, 4, 5, 6)
                    check(peer.append(appended, 0, appended.size) == appended.size)
                    assertArrayEquals(retained + appended, read(peer))
                }
                // This is a progress property while CRC is deliberately paused, not a latency benchmark.
                capture.get(10, TimeUnit.SECONDS)
                assertEquals(1L, resume.count)
                resume.countDown()
                verification.get(5, TimeUnit.SECONDS)
                assertArrayEquals(fixture.pcm, read(fixture.store))
                DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
                assertFalse(marker.exists())
                assertFalse(candidate.exists())
            } finally {
                resume.countDown()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun holdSurvivesTerminalCloseAndBothFailurePathsReleaseOnlyTheInMemoryHold() = withFixture { fixture ->
        val primary = IOException("read-boundary failure")
        val close = IOException("terminal close failure")
        var closes = 0
        val thrown = assertThrows(IOException::class.java) {
            verify(fixture, afterBinding = { throw primary }, accessCloser = { input ->
                assertFalse(Thread.holdsLock(DurablePcmPins.mutationLock))
                assertThrows(IOException::class.java) {
                    DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
                }
                closes++
                input.close()
                throw close
            })
        }
        assertSame(primary, thrown)
        assertArrayEquals(arrayOf(close), thrown.suppressed)
        assertEquals(1, closes)
        assertTrue(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
        val terminalOnly = IOException("close after complete CRC")
        assertSame(terminalOnly, assertThrows(IOException::class.java) {
            verify(fixture, accessCloser = { input ->
                assertThrows(IOException::class.java) {
                    DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
                }
                closes++
                input.close()
                throw terminalOnly
            })
        })
        assertEquals(2, closes)
        assertTrue(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
        DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
        assertFalse(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
        assertArrayEquals(fixture.pcm, read(fixture.store))
    }

    @Test
    fun rejectedSourceDescriptorClosesOnceWithoutAcquiringAReferenceHold() = withFixture { fixture ->
        var closes = 0
        assertThrows(IOException::class.java) {
            DurablePcmPins.verify(fixture.directory, fixture.proof,
                descriptorMatches = { identity, fd -> identity != fixture.proof.identity && descriptorProof(identity, fd) },
                identityResolver = ::nativeIdentity,
                accessCloser = { input -> closes++; input.close() })
        }
        assertEquals(1, closes)
        assertTrue(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
        DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
        assertFalse(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
    }

    @Test
    fun unlockedPayloadMutationTruncationAndReplacementFailClosedAndDoNotLeakHolds() {
        for (change in 0..2) withFixture { fixture ->
            assertThrows(IOException::class.java) {
                verify(fixture, afterBinding = {
                    when (change) {
                        0 -> RandomAccessFile(fixture.source, "rw").use { file ->
                            file.seek(fixture.proof.payloadOffset)
                            file.writeByte(255)
                        }
                        1 -> RandomAccessFile(fixture.source, "rw").use { file ->
                            file.setLength(fixture.source.length() - 2)
                        }
                        else -> {
                            val bytes = fixture.source.readBytes()
                            Files.move(fixture.source.toPath(), File(fixture.source.parentFile, "preserved-original").toPath())
                            fixture.source.writeBytes(bytes)
                        }
                    }
                })
            }
            assertTrue(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
            DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
            assertFalse(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
        }
    }

    @Test
    fun changedMarkerFailsPostcheckAndLegacyVerificationKeepsItsDestructiveLock() = withFixture { fixture ->
        val marker = DurablePcmPins.referenceFile(fixture.directory, fixture.proof)
        val original = marker.readBytes()
        assertThrows(IOException::class.java) { verify(fixture, afterBinding = { marker.appendText("changed") }) }
        assertTrue(marker.isFile)
        marker.writeBytes(original)
        DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
        val local = File(fixture.directory, fixture.proof.name).apply { writeBytes(fixture.source.readBytes()) }
        val legacy = fixture.proof.copy(identity = nativeIdentity(local), sourcePath = "",
            sourceDirectoryIdentity = "", storeDirectoryIdentity = "")
        var observedLegacyLock = false
        DurablePcmPins.verify(fixture.directory, legacy, ::descriptorProof, ::nativeIdentity,
            afterBinding = { observedLegacyLock = Thread.holdsLock(DurablePcmPins.mutationLock) })
        assertTrue(observedLegacyLock)
    }

    @Test
    fun callerOwnedScratchReadsEveryByteAgainAndRejectsAChangedTail() = withFixture { fixture ->
        val scratch = ByteArray(64 * 1024)
        repeat(2) {
            scratch.fill(0x5a.toByte())
            DurablePcmPins.verify(fixture.directory, fixture.proof, ::descriptorProof, ::nativeIdentity,
                scratch = scratch)
            assertArrayEquals(fixture.pcm.copyOfRange(fixture.pcm.size - scratch.size, fixture.pcm.size), scratch)
        }
        // Keep the identity oracle fixed to isolate CRC detection beyond the first read batch.
        assertThrows(IOException::class.java) {
            DurablePcmPins.verify(fixture.directory, fixture.proof, ::descriptorProof, { fixture.proof.identity },
                afterBinding = {
                    RandomAccessFile(fixture.source, "rw").use { file ->
                        file.seek(fixture.proof.payloadOffset + fixture.proof.payloadBytes - 1)
                        file.writeByte(255)
                    }
                }, scratch = scratch)
        }
        assertTrue(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
        DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
        assertFalse(DurablePcmPins.hasReference(fixture.directory, fixture.proof))
    }

    @Test
    fun reusedScratchIgnoresResidualBytesAcrossDifferentSourceLengths() {
        val scratch = ByteArray(64 * 1024) { 0x5a.toByte() }
        // Even byte counts preserve the fixture's PCM16 frame alignment.
        for (payloadBytes in listOf(64 * 1024 + 8, 4, 64 * 1024 + 2)) withFixture(payloadBytes) { fixture ->
            DurablePcmPins.verify(fixture.directory, fixture.proof, ::descriptorProof, ::nativeIdentity,
                scratch = scratch)
            val tail = payloadBytes % scratch.size
            assertArrayEquals(fixture.pcm.copyOfRange(payloadBytes - tail, payloadBytes), scratch.copyOf(tail))
            scratch[tail] = 0x77.toByte()
            DurablePcmPins.verify(fixture.directory, fixture.proof, ::descriptorProof, ::nativeIdentity,
                scratch = scratch)
            DurablePcmPins.release(fixture.directory, fixture.snapshot.objects, ::descriptorProof)
        }
    }

    private data class Fixture(val root: File, val store: PersistentAudioChunkStore, val directory: File,
        val snapshot: DurablePcmPins.Snapshot, val pcm: ByteArray) {
        val proof get() = snapshot.objects.single()
        val source get() = File(proof.sourcePath)
    }

    private fun withFixture(payloadBytes: Int = 128 * 1024, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("pcm-verification-hold").toFile().absoluteFile
        try {
            PersistentAudioChunkStore(File(root, "source")).use { store ->
                // Unbounded retention leaves the normal 1 MiB chunk cap in force. A 1 MiB
                // retention window itself would divide rollover into much smaller chunks.
                configure(store, Long.MAX_VALUE)
                val pcm = ByteArray(payloadBytes) { (it % 113).toByte() }
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                val directory = jobDirectory(root)
                val snapshot = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    lease.withSealedRange { range ->
                        DurablePcmPins.link(directory, range, ::nativeIdentity, ::descriptorProof) { }
                    }
                }
                assertEquals("Fixture must own exactly one complete source object", 1, snapshot.objects.size)
                assertEquals(pcm.size.toLong(), snapshot.objects.single().payloadBytes)
                if (payloadBytes == 128 * 1024) assertTrue("Exercise more than one verifier read batch", pcm.size > 64 * 1024)
                block(Fixture(root, store, directory, snapshot, pcm))
            }
        } finally { root.deleteRecursively() }
    }

    private fun verify(fixture: Fixture, afterBinding: () -> Unit = {},
        accessCloser: (RandomAccessFile) -> Unit = RandomAccessFile::close) =
        DurablePcmPins.verify(fixture.directory, fixture.proof, ::descriptorProof, ::nativeIdentity, afterBinding, accessCloser)

    private fun configure(store: PersistentAudioChunkStore, bytes: Long) =
        store.configure(RetentionMode.SIZE, bytes, 16_000, 1, PcmSampleFormat.PCM_16, deferRetentionCleanup = true)

    private fun read(store: PersistentAudioChunkStore): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
            val output = ByteArrayOutputStream()
            lease.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, size ->
                output.write(bytes, offset, size); size
            }
            output.toByteArray()
        }

    private fun jobDirectory(root: File) = File(File(root, "jobs"), UUID.randomUUID().toString()).apply { mkdirs() }
    // Only unavailable Android descriptor proof is modeled; streams, CRC, identities and store work use real files.
    private fun descriptorProof(identity: String, descriptor: FileDescriptor) = identity.isNotBlank() && descriptor.valid()
    private fun nativeIdentity(file: File): String {
        val dev = Files.getAttribute(file.toPath(), "unix:dev") as Long
        val ino = Files.getAttribute(file.toPath(), "unix:ino") as Long
        val ctime = (Files.getAttribute(file.toPath(), "unix:ctime") as FileTime).toInstant()
        return "stat:$dev:$ino:${ctime.epochSecond}:${ctime.nano}:0"
    }
}
