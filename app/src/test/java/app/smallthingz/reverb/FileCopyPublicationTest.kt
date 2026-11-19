package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.*
import org.junit.Test

class FileCopyPublicationTest {
    private val source = File("/owned/source.partial")
    private val destination = File("/owned/clip.wav")
    private val bytes = ByteArray(300_007) { (it * 17).toByte() }

    private class Store : FileCopyPublicationStore {
        val entries = mutableListOf<FileCopyPublication>()
        val preserved = mutableListOf<Pair<FileCopyPublication, StableOutputFingerprint>>()
        var rejectPhase: FileCopyPhase? = null
        var rejectPreservation = false
        var rejectRemoval = false
        override fun records() = entries.toList()
        override fun replace(previous: FileCopyPublication?, next: FileCopyPublication?) {
            if (next != null && next.phase == rejectPhase) throw IOException("journal boundary")
            if (next == null && rejectRemoval) throw IOException("journal retirement")
            if (previous != null) check(entries.remove(previous))
            if (next != null) entries += next
        }
        override fun preserveIncomplete(record: FileCopyPublication, fingerprint: StableOutputFingerprint) {
            if (rejectPreservation) throw IOException("suppression boundary")
            preserved += record to fingerprint
            replace(record, null)
        }
    }

    private class Io : FileCopyPublicationIo {
        data class Node(val inode: Int, var bytes: ByteArray, var revision: Int = 1) {
            fun identity() = "stat:1:$inode:$revision:0:0"
            fun descriptor() = "statfd:1:$inode:$revision:0"
        }
        val nodes = mutableMapOf<String, Node>()
        val nonRegular = mutableSetOf<String>()
        val unavailable = mutableSetOf<String>()
        var serial = 1
        var creates = 0
        var closes = 0
        var fileSyncs = 0
        var directorySyncs = 0
        var failDirectorySyncAt = 0
        var failWrite = false
        var failClose = false
        var corruptWrite = false
        var afterRead: (() -> Unit)? = null
        var afterDirectorySync: (() -> Unit)? = null
        var atCreate: ((File) -> Unit)? = null
        fun put(file: File, bytes: ByteArray): Node = Node(serial++, bytes.copyOf()).also { nodes[file.path] = it }
        override fun state(file: File) = when (file.path) {
            in unavailable -> StoragePathState.UNAVAILABLE
            in nodes, in nonRegular -> StoragePathState.PRESENT
            else -> StoragePathState.MISSING
        }
        override fun isRegularFile(file: File): Boolean? = when (file.path) {
            in unavailable -> null
            in nonRegular -> false
            in nodes -> true
            else -> null
        }
        override fun fingerprint(file: File): StableOutputFingerprint? =
            if (file.path in unavailable || file.path in nonRegular) null else nodes[file.path]?.let {
                StableOutputFingerprint(sha256(ByteArrayInputStream(it.bytes)), it.identity(), null)
            }
        override fun forceDirectory(directory: File) {
            directorySyncs++
            if (directorySyncs == failDirectorySyncAt) throw IOException("directory durability")
            afterDirectorySync?.invoke()
        }
        override fun forceFile(file: File, expected: StableOutputFingerprint) {
            check(fingerprint(file)?.fileKey == expected.fileKey)
            fileSyncs++
        }
        override fun createExclusive(file: File): FileCopyWriter? {
            atCreate?.invoke(file)
            if (state(file) != StoragePathState.MISSING) return null
            creates++
            val node = put(file, byteArrayOf())
            val written = ByteArrayOutputStream()
            return object : FileCopyWriter {
                override val stream = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(value: ByteArray, offset: Int, count: Int) {
                        val accepted = if (failWrite) minOf(3, count) else count
                        written.write(value, offset, accepted)
                        node.bytes = written.toByteArray()
                        if (corruptWrite && node.bytes.isNotEmpty()) node.bytes[0] = (node.bytes[0].toInt() xor 1).toByte()
                        node.revision++
                        if (failWrite) throw IOException("partial write")
                    }
                }
                override fun identity() = node.descriptor()
                override fun size() = node.bytes.size.toLong()
                override fun sync() { fileSyncs++ }
                override fun close() {
                    closes++
                    if (failClose) throw IOException("writer close")
                }
            }
        }
        override fun readSource(file: File, expected: StableOutputFingerprint, read: (InputStream) -> CopyDigest): CopyDigest {
            val node = requireNotNull(nodes[file.path])
            check(node.identity() == expected.fileKey)
            val result = read(ByteArrayInputStream(node.bytes))
            afterRead?.invoke()
            if (node.identity() != expected.fileKey || !copyDigestMatches(expected.digest, result)) {
                throw IOException("source changed")
            }
            return result
        }
    }

    private fun fixture(): Triple<Store, Io, StableOutputFingerprint> {
        val store = Store()
        val io = Io().apply { put(source, bytes) }
        return Triple(store, io, requireNotNull(io.fingerprint(source)))
    }

    private fun copy(store: Store, io: Io, expected: StableOutputFingerprint,
                     progress: (Long, Long) -> Unit = { _, _ -> }): FileCopyPublication =
        copyFileForPublication(store, io, source, destination, "clip.wav", expected, FileCopyPurpose.STAGING, progress)

    @Test fun successUsesNewCreatedInodeAndRetainsSourceUntilCatalogOwner() {
        val (store, io, expected) = fixture()
        val progress = mutableListOf<Long>()
        val receipt = copy(store, io, expected) { done, total ->
            assertEquals(bytes.size.toLong(), total)
            progress += done
        }
        assertEquals(FileCopyPhase.VERIFIED, receipt.phase)
        assertFalse(sameFileObjectAcrossRename(expected.fileKey!!, receipt.targetIdentity))
        assertTrue(receipt.matchesTarget(requireNotNull(io.fingerprint(destination))))
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
        assertArrayEquals(bytes, io.nodes.getValue(destination.path).bytes)
        assertEquals(1, io.closes)
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun targetLookupRejectsOnlyEligibleMatchingConflictsBeforeFingerprinting() {
        val (store, io, expected) = fixture()
        val receipt = copy(store, io, expected)
        val target = RecordingEntity(destination.path, destination.name, "audio/wav", 0, 1,
            receipt.byteCount, "PCM", RecordingStorageType.FILE, destination.parent!!,
            fileIdentity = receipt.targetIdentity)
        val conflict = receipt.copy(token = "other exact receipt")
        val snapshot = FileCopyPublicationSnapshot(0, listOf(receipt, conflict))

        assertNull(fileCopyPublicationForTarget(snapshot, target.copy(fileIdentity = "replacement")))
        assertNull(fileCopyPublicationForTarget(snapshot, target.copy(storageType = RecordingStorageType.DOCUMENT)))
        val ineligible = listOf(receipt.copy(purpose = FileCopyPurpose.CLAIM_RESTORE),
            conflict.copy(phase = FileCopyPhase.CREATED))
        assertNull(fileCopyPublicationForTarget(snapshot.copy(records = ineligible), target))
        assertEquals("Conflicting FILE copy publication receipts", assertThrows(IOException::class.java) {
            fileCopyPublicationForTarget(snapshot, target)
        }.message)
    }

    @Test fun ordinaryCreatorWinningAfterReservationIsNeverOpenedOrChanged() {
        val (store, io, expected) = fixture()
        val foreign = byteArrayOf(99, 98, 97)
        io.atCreate = { io.put(it, foreign) }
        assertThrows(FileAlreadyExistsException::class.java) { copy(store, io, expected) }
        assertTrue(store.entries.isEmpty())
        assertEquals(0, io.creates)
        assertArrayEquals(foreign, io.nodes.getValue(destination.path).bytes)
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
    }

    @Test fun failedReservationCreatesNothing() {
        val (store, io, expected) = fixture()
        store.rejectPhase = FileCopyPhase.RESERVED
        assertThrows(IOException::class.java) { copy(store, io, expected) }
        assertEquals(0, io.creates)
        assertFalse(destination.path in io.nodes)
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
    }

    @Test fun failedCreatedMarkerCannotWritePayloadAndHandsOffAmbiguityWithoutDeleteAuthority() {
        val (store, io, expected) = fixture()
        store.rejectPhase = FileCopyPhase.CREATED
        assertThrows(IOException::class.java) { copy(store, io, expected) }
        assertEquals(0, io.nodes.getValue(destination.path).bytes.size)
        assertEquals(FileCopyPhase.RESERVED, store.entries.single().phase)
        val reserved = store.entries.single()
        store.rejectPhase = null
        assertThrows(FileCopyRecoveryRequired::class.java) {
            recoverFileCopyPublication(store, io, source, "clip.wav", expected)
        }
        assertTrue(store.entries.isEmpty())
        assertEquals(reserved, store.preserved.single().first)
        val suppression = requireNotNull(suppressionOnlyFileOutputRecord(destination.path, store.preserved.single().second.digest))
        assertNull(suppression.fileKey)
        assertFalse(pendingFileOutputCleanupHasDeleteAuthority(suppression))
        assertEquals(0, io.nodes.getValue(destination.path).bytes.size)
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
        assertEquals(1, io.closes)
    }

    @Test fun ordinaryPublicationRecoversFromFinitePreCreatedFailuresIntoNewExclusiveTarget() {
        for (purpose in listOf(FileCopyPurpose.STAGING, FileCopyPurpose.RENAME)) {
            for (failure in listOf("directory", "created-marker")) {
                val selected = if (purpose == FileCopyPurpose.RENAME) File("/owned/original.wav") else source
                val store = Store().apply { if (failure == "created-marker") rejectPhase = FileCopyPhase.CREATED }
                val io = Io().apply {
                    put(selected, bytes)
                    if (failure == "directory") failDirectorySyncAt = 1
                }
                val expected = requireNotNull(io.fingerprint(selected))
                assertThrows(IOException::class.java) {
                    copyFileForPublication(store, io, selected, destination, destination.name, expected, purpose)
                }
                val reserved = store.entries.single()
                assertEquals(FileCopyPhase.RESERVED, reserved.phase)
                assertEquals(0, io.nodes.getValue(destination.path).bytes.size)
                assertEquals(1, io.closes)
                store.rejectPhase = null
                io.failDirectorySyncAt = 0
                assertThrows(FileCopyRecoveryRequired::class.java) {
                    recoverFileCopyPublication(store, io, selected, destination.name, expected)
                }
                assertEquals(reserved, store.preserved.single().first)
                assertTrue(store.entries.isEmpty())
                assertNull(recoverFileCopyPublication(store, io, selected, destination.name, expected))
                val nextName = findAvailableFileDisplayName(destination.name) { candidate ->
                    io.state(File(destination.parentFile, candidate)) != StoragePathState.MISSING
                }
                assertEquals("clip (2).wav", nextName)
                val next = File(destination.parentFile, nextName)
                val published = copyFileForPublication(store, io, selected, next, destination.name, expected, purpose)
                assertEquals(FileCopyPhase.VERIFIED, published.phase)
                assertTrue(published.matchesTarget(requireNotNull(io.fingerprint(next))))
                assertEquals(2, io.creates)
                assertArrayEquals(bytes, io.nodes.getValue(next.path).bytes)
                assertArrayEquals(bytes, io.nodes.getValue(selected.path).bytes)
                assertEquals(0, io.nodes.getValue(destination.path).bytes.size)
            }
        }
    }

    @Test fun changedRequestedNameCanClassifyReservationButCannotAdoptAnEarlierCompletedCopy() {
        for (purpose in listOf(FileCopyPurpose.STAGING, FileCopyPurpose.RENAME)) {
            val (store, io, expected) = fixture()
            store.rejectPhase = FileCopyPhase.CREATED
            assertThrows(IOException::class.java) {
                copyFileForPublication(store, io, source, destination, destination.name, expected, purpose)
            }
            store.rejectPhase = null
            val changedName = "different.wav"
            assertThrows(FileCopyRecoveryRequired::class.java) {
                recoverFileCopyPublication(store, io, source, changedName, expected)
            }
            assertEquals(1, store.preserved.size)
            assertTrue(store.entries.isEmpty())
            val target = File(destination.parentFile, changedName)
            val completed = copyFileForPublication(store, io, source, target, changedName, expected, purpose)
            for (phase in listOf(FileCopyPhase.CREATED, FileCopyPhase.VERIFIED, FileCopyPhase.CATALOGED)) {
                val prior = store.entries.single()
                val stored = completed.copy(phase = phase,
                    targetIdentity = if (phase == FileCopyPhase.CREATED) "" else completed.targetIdentity)
                store.replace(prior, stored)
                assertThrows(FileCopyRecoveryRequired::class.java) {
                    recoverFileCopyPublication(store, io, source, "yet-another.wav", expected)
                }
                assertEquals(stored, store.entries.single())
                assertArrayEquals(bytes, io.nodes.getValue(target.path).bytes)
            }
        }
    }

    @Test fun reservationObservationAndSuppressionFailuresKeepOwnershipUntilDurableHandoff() {
        for (purpose in listOf(FileCopyPurpose.STAGING, FileCopyPurpose.RENAME)) {
            val (store, io, expected) = fixture()
            store.rejectPhase = FileCopyPhase.CREATED
            assertThrows(IOException::class.java) {
                copyFileForPublication(store, io, source, destination, destination.name, expected, purpose)
            }
            store.rejectPhase = null
            val reserved = store.entries.single()
            io.unavailable += destination.path
            assertThrows(FileCopyRecoveryRequired::class.java) {
                recoverFileCopyPublication(store, io, source, "changed.wav", expected)
            }
            assertEquals(listOf(reserved), store.entries)
            assertTrue(store.preserved.isEmpty())
            io.unavailable.clear()
            store.rejectPreservation = true
            assertThrows(IOException::class.java) {
                recoverFileCopyPublication(store, io, source, "changed.wav", expected)
            }
            assertEquals(listOf(reserved), store.entries)
            assertTrue(store.preserved.isEmpty())
            store.rejectPreservation = false
            store.rejectRemoval = true
            assertThrows(IOException::class.java) {
                recoverFileCopyPublication(store, io, source, "changed.wav", expected)
            }
            assertEquals(listOf(reserved), store.entries)
            assertEquals(reserved, store.preserved.single().first)
            assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
            assertEquals(0, io.nodes.getValue(destination.path).bytes.size)
        }
    }

    @Test fun nonRegularCollisionWithFailedReservationRetirementCanRecoverForOrdinaryPurposes() {
        for (purpose in listOf(FileCopyPurpose.STAGING, FileCopyPurpose.RENAME)) {
            val (store, io, expected) = fixture()
            io.nonRegular += destination.path
            store.rejectRemoval = true
            assertThrows(IOException::class.java) {
                copyFileForPublication(store, io, source, destination, destination.name, expected, purpose)
            }
            val reserved = store.entries.single()
            assertEquals(FileCopyPhase.RESERVED, reserved.phase)
            store.rejectRemoval = false
            io.failDirectorySyncAt = io.directorySyncs + 1
            assertThrows(IOException::class.java) {
                recoverFileCopyPublication(store, io, source, "changed.wav", expected)
            }
            assertEquals(listOf(reserved), store.entries)
            io.failDirectorySyncAt = 0
            assertNull(recoverFileCopyPublication(store, io, source, "changed.wav", expected))
            assertTrue(store.entries.isEmpty())
            assertTrue(store.preserved.isEmpty())
            assertTrue(destination.path in io.nonRegular)
            assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
        }
    }

    @Test fun partialWriteAndCloseFailureKeepPrimaryAndRetainBothObjects() {
        val (store, io, expected) = fixture()
        io.failWrite = true
        io.failClose = true
        val failure = assertThrows(IOException::class.java) { copy(store, io, expected) }
        assertEquals("partial write", failure.message)
        assertEquals("writer close", failure.suppressed.single().message)
        assertEquals(1, io.closes)
        assertEquals(FileCopyPhase.CREATED, store.entries.single().phase)
        assertEquals(3, io.nodes.getValue(destination.path).bytes.size)
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
    }

    @Test fun completeCopyWithFailedDirectoryBarrierRecoversSameCandidateWithoutAnotherCopy() {
        val (store, io, expected) = fixture()
        io.failDirectorySyncAt = 2
        assertThrows(IOException::class.java) { copy(store, io, expected) }
        assertEquals(FileCopyPhase.CREATED, store.entries.single().phase)
        val recovered = requireNotNull(recoverFileCopyPublication(store, io, source, "clip.wav", expected))
        assertEquals(FileCopyPhase.VERIFIED, recovered.phase)
        assertEquals(1, io.creates)
        assertEquals(2, io.fileSyncs)
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
    }

    @Test fun partialRecoveryTransfersSuppressionWithoutGainingDeletionAuthority() {
        val (store, io, expected) = fixture()
        io.failWrite = true
        assertThrows(IOException::class.java) { copy(store, io, expected) }
        assertThrows(FileCopyRecoveryRequired::class.java) {
            recoverFileCopyPublication(store, io, source, "clip.wav", expected)
        }
        assertTrue(store.entries.isEmpty())
        assertEquals(3L, store.preserved.single().second.digest.byteCount)
        assertEquals(3, io.nodes.getValue(destination.path).bytes.size)
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
    }

    @Test fun byteCorruptionDoesNotAcquireVerifiedPublication() {
        val (store, io, expected) = fixture()
        io.corruptWrite = true
        assertThrows(IOException::class.java) { copy(store, io, expected) }
        assertEquals(FileCopyPhase.CREATED, store.entries.single().phase)
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
    }

    @Test fun sourceChangeDuringCopyFailsBeforeVerifiedPhase() {
        val (store, io, expected) = fixture()
        io.afterRead = { io.nodes.getValue(source.path).revision++ }
        assertThrows(IOException::class.java) { copy(store, io, expected) }
        assertEquals(FileCopyPhase.CREATED, store.entries.single().phase)
        assertTrue(source.path in io.nodes)
    }

    @Test fun verifiedReceiptDoesNotAdoptSameBytesInReplacementInode() {
        val (store, io, expected) = fixture()
        val receipt = copy(store, io, expected)
        io.put(destination, bytes)
        assertFalse(receipt.matchesTarget(requireNotNull(io.fingerprint(destination))))
        assertThrows(FileCopyRecoveryRequired::class.java) {
            recoverFileCopyPublication(store, io, source, "clip.wav", expected)
        }
        assertArrayEquals(bytes, io.nodes.getValue(source.path).bytes)
    }

    @Test fun suffixReceiptResumesOriginalOrAllocatedNameWithoutDuplicate() {
        val (store, io, expected) = fixture()
        val suffixed = File(destination.parentFile, "clip (2).wav")
        val copied = copyFileForPublication(store, io, source, suffixed, "clip.wav", expected, FileCopyPurpose.STAGING)
        assertEquals(copied, recoverFileCopyPublication(store, io, source, "clip.wav", expected))
        assertEquals(copied, recoverFileCopyPublication(store, io, source, "clip (2).wav", expected))
        assertThrows(FileCopyRecoveryRequired::class.java) {
            recoverFileCopyPublication(store, io, source, "other.wav", expected)
        }
        assertEquals(1, io.creates)
    }

    @Test fun wireRequiresExplicitCreatedAndFinalIdentitiesAndRoundTripsUnicode() {
        val (store, io, expected) = fixture()
        val record = copy(store, io, expected).copy(requestedName = "\u00e9\u6771\u4eac\uD83C\uDF99.wav")
        assertEquals(record, decodeFileCopyPublication(encodeFileCopyPublication(record)))
        assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(createdIdentity = ""))))
        assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(targetIdentity = ""))))
        assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(targetIdentity = "stat:1:999:2:0:0"))))
        assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(
            phase = FileCopyPhase.CATALOGED, createdIdentity = "stat:1:999:1:0:0"))))
        assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(targetId = "/elsewhere/clip.wav"))))
        assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(requestedName = "../clip.wav"))))
    }

    private val claim = File("/owned/.reverb-delete-00000000-0000-0000-0000-000000000777.pending")

    @Test fun zeroByteNonAudioClaimIsCopiedAndRetiredWithoutCatalogOrFreshClaim() {
        val store = Store()
        val io = Io().apply { put(claim, byteArrayOf()) }
        val target = File("/owned/recovered-empty.data")
        var retired = 0
        moveDeletionClaimForRecoveryOwned(store, io, claim, target,
            nativeMove = { _, _ -> throw NoReplaceMoveUnsupported("fixture") },
            retireExistingClaim = { record ->
                assertEquals(FileCopyPurpose.CLAIM_RESTORE, record.purpose)
                assertEquals(FileCopyPhase.VERIFIED, record.phase)
                assertEquals(0L, record.byteCount)
                assertEquals(record, decodeFileCopyPublication(encodeFileCopyPublication(record)))
                assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(purpose = FileCopyPurpose.STAGING))))
                assertNull(decodeFileCopyPublication(encodeFileCopyPublication(record.copy(sourceId = "/owned/not-a-claim.pending"))))
                assertTrue(record.matchesTarget(requireNotNull(io.fingerprint(target))))
                io.nodes.remove(claim.path)
                retired++
                true
            })
        assertEquals(1, retired)
        assertEquals(StoragePathState.MISSING, io.state(claim))
        assertEquals(0L, requireNotNull(io.fingerprint(target)).digest.byteCount)
        assertTrue(store.entries.isEmpty())
    }

    @Test fun completedClaimCopyIsResumedBeforeNativeMoveDespiteNewRecoveryName() {
        val store = Store()
        val io = Io().apply { put(claim, bytes) }
        val record = copyFileForPublication(store, io, claim, destination, destination.name,
            requireNotNull(io.fingerprint(claim)), FileCopyPurpose.CLAIM_RESTORE)
        val proposed = File("/owned/recovered-new-timestamp.wav")
        moveDeletionClaimForRecoveryOwned(store, io, claim, proposed,
            nativeMove = { _, _ -> error("unresolved ownership must precede native mutation") },
            retireExistingClaim = { receipt ->
                assertEquals(record, receipt)
                io.nodes.remove(claim.path)
                true
            })
        assertEquals(1, io.creates)
        assertEquals(StoragePathState.MISSING, io.state(proposed))
        assertArrayEquals(bytes, requireNotNull(io.nodes[destination.path]).bytes)
        assertTrue(store.entries.isEmpty())
    }

    @Test fun claimRestorationExclusiveCollisionNeverRetiresSourceOrOpensWinner() {
        val store = Store()
        val foreign = byteArrayOf(9, 8, 7)
        val io = Io().apply {
            put(claim, bytes)
            atCreate = { put(it, foreign) }
        }
        try {
            moveDeletionClaimForRecoveryOwned(store, io, claim, destination,
                nativeMove = { _, _ -> throw NoReplaceMoveUnsupported("fixture") },
                retireExistingClaim = { error("collision cannot authorize claim retirement") })
            fail("collision must remain a collision")
        } catch (_: FileAlreadyExistsException) { }
        assertArrayEquals(bytes, requireNotNull(io.nodes[claim.path]).bytes)
        assertArrayEquals(foreign, requireNotNull(io.nodes[destination.path]).bytes)
        assertTrue(store.entries.isEmpty())
        assertEquals(0, io.creates)
    }

    @Test fun reservedEmptyClaimCandidateIsPreservedWithoutAdoptionThenAnotherNameCanRecover() {
        val store = Store().apply { rejectPhase = FileCopyPhase.CREATED }
        val io = Io().apply { put(claim, byteArrayOf()) }
        val expected = requireNotNull(io.fingerprint(claim))
        try {
            copyFileForPublication(store, io, claim, destination, destination.name, expected, FileCopyPurpose.CLAIM_RESTORE)
            fail("created marker must fail")
        } catch (_: IOException) { }
        store.rejectPhase = null
        try {
            moveDeletionClaimForRecoveryOwned(store, io, claim, destination,
                nativeMove = { _, _ -> error("reserved ownership cannot be bypassed") },
                retireExistingClaim = { error("ambiguous equal bytes are not ownership") })
            fail("ambiguous candidate needs suppression handoff")
        } catch (_: FileCopyRecoveryRequired) { }
        assertEquals(1, store.preserved.size)
        assertTrue(store.entries.isEmpty())
        assertEquals(StoragePathState.PRESENT, io.state(claim))
        assertEquals(StoragePathState.PRESENT, io.state(destination))
        val another = File("/owned/recovered-again.wav")
        moveDeletionClaimForRecoveryOwned(store, io, claim, another,
            nativeMove = { _, _ -> throw NoReplaceMoveUnsupported("fixture") },
            retireExistingClaim = { io.nodes.remove(claim.path); true })
        assertEquals(StoragePathState.PRESENT, io.state(another))
        assertEquals(StoragePathState.PRESENT, io.state(destination))
        assertEquals(2, io.creates)
    }

    @Test fun claimCopyReplayAfterDurableUnlinkOnlyRetiresItsExactJournal() {
        val store = Store()
        val io = Io().apply { put(claim, bytes) }
        val record = copyFileForPublication(store, io, claim, destination, destination.name,
            requireNotNull(io.fingerprint(claim)), FileCopyPurpose.CLAIM_RESTORE)
        io.nodes.remove(claim.path)
        val barriers = io.directorySyncs
        assertTrue(resumeDeletionClaimCopyOwned(store, io, record) { error("already absent claim cannot be deleted again") })
        assertTrue(io.directorySyncs > barriers)
        assertTrue(store.entries.isEmpty())
        assertArrayEquals(bytes, requireNotNull(io.nodes[destination.path]).bytes)
    }

    @Test fun changedRecoveryTargetCannotAuthorizeClaimRetirement() {
        val store = Store()
        val io = Io().apply { put(claim, bytes) }
        val record = copyFileForPublication(store, io, claim, destination, destination.name,
            requireNotNull(io.fingerprint(claim)), FileCopyPurpose.CLAIM_RESTORE)
        io.put(destination, bytes)
        try {
            resumeDeletionClaimCopyOwned(store, io, record) { error("replacement must not authorize retirement") }
            fail("same bytes in a replacement inode must fail")
        } catch (_: FileCopyRecoveryRequired) { }
        assertEquals(listOf(record), store.entries)
        assertArrayEquals(bytes, requireNotNull(io.nodes[claim.path]).bytes)
    }

    @Test fun targetLossDuringClaimRetirementKeepsRecoveryOwnershipAndFailsTerminal() {
        val store = Store()
        val io = Io().apply { put(claim, bytes) }
        val record = copyFileForPublication(store, io, claim, destination, destination.name,
            requireNotNull(io.fingerprint(claim)), FileCopyPurpose.CLAIM_RESTORE)
        try {
            resumeDeletionClaimCopyOwned(store, io, record) {
                io.nodes.remove(claim.path)
                io.nodes.remove(destination.path)
                true
            }
            fail("target loss cannot report completed restoration")
        } catch (_: FileCopyRecoveryRequired) { }
        assertEquals(listOf(record), store.entries)
        assertEquals(StoragePathState.MISSING, io.state(claim))
    }

    @Test fun sameByteTargetReplacementDuringAbsenceBarrierKeepsRecoveryOwnership() {
        val store = Store()
        val io = Io().apply { put(claim, bytes) }
        val record = copyFileForPublication(store, io, claim, destination, destination.name,
            requireNotNull(io.fingerprint(claim)), FileCopyPurpose.CLAIM_RESTORE)
        io.afterDirectorySync = { io.put(destination, bytes) }
        try {
            resumeDeletionClaimCopyOwned(store, io, record) { io.nodes.remove(claim.path); true }
            fail("same bytes in a new target cannot retire ownership")
        } catch (_: FileCopyRecoveryRequired) { }
        assertEquals(listOf(record), store.entries)
        assertFalse(record.matchesTarget(requireNotNull(io.fingerprint(destination))))
    }
}
