package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DurableSaveJournalTest {
    @Test
    fun recoveryObserverReceivesOriginalReadFailureWithoutBlockingHealthyJob() = withRoot { root ->
        val rejected = fixture(root)
        rejected.journal.write(rejected.record)
        val healthy = fixture(root)
        healthy.journal.write(healthy.record)
        val deniedIdentity = resolveFileIdentity(rejected.manifest)
        val denial = SecurityException("Provider denied reading this manifest")
        val observerFailure = IOException("Presentation unavailable")
        val journal = DurableSaveJournal(rejected.root, descriptorMatches = { identity, descriptor ->
            if (fileIdentityMatches(deniedIdentity, identity)) throw denial
            descriptor.valid() && identity.isNotBlank()
        })
        var observed: Exception? = null
        val recovered = ArrayList<String>()
        val result = journal.recoverEach(onRecord = { recovered += it.id }, onFailure = { id, failure ->
            assertEquals(rejected.record.id, id)
            observed = failure
            throw observerFailure
        })
        assertSame(denial, observed)
        assertEquals(AppFailureReason.PERMISSION, appFailureDetail(observed).reason)
        assertSame(observerFailure, denial.suppressed.single())
        assertEquals(mapOf(rejected.record.id to denial.message), result.failures)
        assertEquals(setOf(rejected.record.id, healthy.record.id), result.presentIds)
        assertEquals(listOf(healthy.record.id), recovered)
        assertTrue(rejected.manifest.isFile)
        rejected.assertPinsUnchanged(); healthy.assertPinsUnchanged()
    }

    @Test
    fun recoveryObserverDoesNotSwallowCancellationOrFatalFailure() = withRoot { root ->
        val fixture = fixture(root)
        fixture.journal.write(fixture.record)
        val cancelled = java.util.concurrent.CancellationException("Observer cancelled")
        val fatal = AssertionError("Observer fatal")
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException::class.java) {
            fixture.journal.recoverEach(onRecord = { throw IOException("Read callback failed") },
                onFailure = { _, _ -> throw cancelled })
        })
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            fixture.journal.recoverEach(onRecord = { throw IOException("Read callback failed") },
                onFailure = { _, _ -> throw fatal })
        })
        // The original explicit positional API remains source-compatible and successful afterward.
        val ids = ArrayList<String>()
        val consumer: (DurableSaveRecord) -> Unit = { ids += it.id }
        assertTrue(fixture.journal.recoverEach(consumer).failures.isEmpty())
        assertEquals(listOf(fixture.record.id), ids)
        fixture.assertPinsUnchanged()
    }

    @Test
    fun acquiredRangeWithBackwardsWallClockRoundTripsItsExactPositivePcmDuration() = withRoot { root ->
        val journal = hostJournal(File(root, "jobs"))
        val id = UUID.randomUUID().toString()
        val directory = journal.createDirectory(id)
        PersistentAudioChunkStore(File(root, "source")).use { store ->
            store.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
            val first = ByteArray(16_000 * 2) { (it % 97).toByte() }
            assertEquals(first.size, store.appendCapturedAt(first, 0, first.size, 100_000L))
            store.sealActiveChunk()
            val last = byteArrayOf(11, 22)
            assertEquals(last.size, store.appendCapturedAt(last, 0, last.size, 100_001L))
            requireNotNull(store.acquireRange(0.875, store.durationSeconds())).use { lease ->
                lease.withSealedRange { range ->
                    assertEquals(100_875L, range.startedAtMillis)
                    assertEquals(100_001L, range.endedAtMillis)
                    assertEquals(2001.0 / 16_000, range.durationSeconds, 0.0)
                    val records = (range.segments + range.leadingContext + range.trailingContext)
                        .map { it.record }.distinctBy { it.file }
                    val proofs = records.mapIndexed { index, record ->
                        DurablePcmPins.ObjectProof("pcm-$index", resolveFileIdentity(record.file),
                            record.payloadOffsetBytes, record.payloadBytes, record.payloadChecksum,
                            record.file.absolutePath, resolveDirectoryIdentity(record.file.parentFile),
                            resolveDirectoryIdentity(record.file.parentFile.parentFile))
                    }
                    val output = request().copy(name = "Selected.wav", format = ExportFormat.WAV,
                        sampleRate = 16_000, channelCount = 1)
                    journal.write(DurableSaveRecord(id, DurableSaveStage.QUEUED, output, DurablePcmPins.Snapshot(range, proofs)))
                    val restored = journal.read(id).source.range
                    assertEquals(range.startedAtMillis, restored.startedAtMillis)
                    assertEquals(range.endedAtMillis, restored.endedAtMillis)
                    assertEquals(range.durationSeconds, restored.durationSeconds, 0.0)
                    val decoded = ByteArrayOutputStream()
                    restored.readNormalized(16_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, count ->
                        decoded.write(bytes, offset, count); count
                    }
                    assertArrayEquals(first.copyOfRange(14_000 * 2, first.size) + last, decoded.toByteArray())
                }
            }
        }
        assertTrue(File(directory, "manifest").isFile)
    }

    @Test
    fun timestampsRemainNonnegativeAndPcmDurationRemainsFiniteAndPositive() = withRoot { root ->
        val fixture = fixture(root)
        val source = fixture.record.source
        val zero = fixture.record.copy(source = source.copy(range = source.range.copy(startedAtMillis = 0, endedAtMillis = 0)))
        fixture.journal.write(zero)
        assertEquals(0L, fixture.journal.read(zero.id).source.range.endedAtMillis)
        val invalid = listOf(source.range.copy(startedAtMillis = -1), source.range.copy(endedAtMillis = -1),
            source.range.copy(durationSeconds = 0.0), source.range.copy(durationSeconds = Double.NaN))
        invalid.forEach { range ->
            assertThrows(IOException::class.java) { fixture.journal.write(fixture.record.copy(source = source.copy(range = range))) }
        }
        assertEquals(0L, fixture.journal.read(zero.id).source.range.startedAtMillis)
    }

    @Test
    fun caughtReservationBarrierFailureCanRetireOnlyItsOwnEmptyDirectory() = withRoot { root ->
        val journalRoot = File(root, "jobs")
        var failBarrier = true
        var retirementBarrier = false
        val journal = hostJournal(journalRoot, directorySync = { directory ->
            if (directory == journalRoot && failBarrier) throw IOException("reservation barrier")
            forceRecordingDirectoryDurable(directory)
            if (directory == journalRoot) retirementBarrier = true
        })
        var reservation: DurableSaveJournal.DirectoryReservation? = null
        assertThrows(IOException::class.java) {
            journal.createDirectory(UUID.randomUUID().toString()) { reservation = it }
        }
        val owned = requireNotNull(reservation)
        assertTrue(owned.directory.isDirectory)
        failBarrier = false
        journal.abandonEmptyReservation(owned)
        assertFalse(owned.directory.exists())
        assertTrue(retirementBarrier)
    }

    @Test
    fun reservationCleanupPreservesUnknownContentsAndReplacementDirectories() = withRoot { root ->
        val journal = hostJournal(File(root, "jobs"))
        var reservation: DurableSaveJournal.DirectoryReservation? = null
        journal.createDirectory(UUID.randomUUID().toString()) { reservation = it }
        val owned = requireNotNull(reservation)
        val evidence = File(owned.directory, "manifest.new").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThrows(IOException::class.java) { journal.abandonEmptyReservation(owned) }
        assertArrayEquals(byteArrayOf(1, 2, 3), evidence.readBytes())
        val preserved = File(root, "preserved-reservation")
        Files.move(owned.directory.toPath(), preserved.toPath())
        Files.createDirectory(owned.directory.toPath())
        assertThrows(IOException::class.java) { journal.abandonEmptyReservation(owned) }
        assertTrue(owned.directory.isDirectory)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(preserved, "manifest.new").readBytes())
    }

    @Test
    fun reservationRetirementBarrierFailureIsNotReportedAsDurableSuccess() = withRoot { root ->
        val journalRoot = File(root, "jobs")
        var failBarrier = false
        val journal = hostJournal(journalRoot, directorySync = { directory ->
            if (directory == journalRoot && failBarrier) throw IOException("retirement barrier")
            forceRecordingDirectoryDurable(directory)
        })
        var reservation: DurableSaveJournal.DirectoryReservation? = null
        journal.createDirectory(UUID.randomUUID().toString()) { reservation = it }
        failBarrier = true
        assertThrows(IOException::class.java) { journal.abandonEmptyReservation(requireNotNull(reservation)) }
        failBarrier = false
        assertTrue(journal.recoverEach { fail("No manifest was ever committed") }.presentIds.isEmpty())
    }

    @Test
    fun versionOnePinningManifestKeepsLegacyJobLocalPathsWithoutInventingSourceReferences() = withRoot { root ->
        val fixture = fixture(root)
        val proofs = fixture.record.source.objects.map {
            it.copy(sourcePath = "", sourceDirectoryIdentity = "", storeDirectoryIdentity = "")
        }
        val sourceNames = fixture.record.source.objects.associate { it.sourcePath to it.name }
        fun legacySegments(items: List<PersistentAudioChunkStore.Segment>) = items.map { segment ->
            segment.copy(record = segment.record.copy(file = File(fixture.directory,
                requireNotNull(sourceNames[segment.record.file.absolutePath]))))
        }
        val range = fixture.record.source.range
        val legacy = fixture.record.copy(stage = DurableSaveStage.PINNING, source = DurablePcmPins.Snapshot(
            range.copy(segments = legacySegments(range.segments), leadingContext = legacySegments(range.leadingContext),
                trailingContext = legacySegments(range.trailingContext)), proofs))
        val wire = versionOneManifest(fixture.journal.encode(legacy))
        fixture.manifest.writeBytes(wire)
        val restored = fixture.journal.read(fixture.record.id)
        assertEquals(DurableSaveStage.PINNING, restored.stage)
        assertTrue(restored.source.objects.all { it.sourcePath.isEmpty() })
        assertEquals(File(fixture.directory, "pcm-1"), restored.source.range.segments.single().record.file)
        assertFalse(restored.source.range.segments.single().record.file.exists())
        assertArrayEquals(wire, fixture.manifest.readBytes())
        fixture.assertPinsUnchanged()
    }

    @Test
    fun onDiskRoundTripRetainsPrefixContextAndExplicitOutputConfiguration() = withRoot { root ->
        for (format in PcmSampleFormat.entries) {
            val fixture = fixture(File(root, format.name), format)
            fixture.journal.write(fixture.record)
            val restored = fixture.journal.decode(fixture.manifest.readBytes(), fixture.directory)
            assertEquals(fixture.record.id, restored.id)
            assertEquals(DurableSaveStage.QUEUED, restored.stage)
            assertEquals("Selected audio.flac", restored.request.name)
            assertEquals("destination-at-admission", restored.request.directoryId)
            assertEquals(ExportFormat.FLAC, restored.request.format)
            assertEquals(ExportCodec.PCM_16, restored.request.codec)
            assertEquals(96_000, restored.request.sampleRate)
            assertEquals(2, restored.request.channelCount)
            assertEquals(PcmSampleFormat.PCM_16, restored.request.sampleFormat)
            assertEquals(8, restored.request.flacCompression)
            assertEquals(1000L, restored.source.range.startedAtMillis)
            assertEquals(1001L, restored.source.range.endedAtMillis)
            assertEquals(3.0 / 48_000, restored.source.range.durationSeconds, 0.0)
            assertEquals(3, restored.source.objects.size)
            val selected = restored.source.range.segments.single()
            assertEquals(File(fixture.record.source.objects[1].sourcePath), selected.record.file)
            assertEquals(1u, selected.record.id)
            assertEquals(format, selected.record.sampleFormat)
            assertEquals(48_000, selected.record.sampleRate)
            assertEquals(2, selected.record.channelCount)
            assertEquals(8L, selected.record.sampleFrames)
            assertEquals(7L, selected.record.headerGeneration)
            assertEquals(16L, selected.record.payloadOffsetBytes)
            assertEquals(1L, selected.startFrame)
            assertEquals(3L, selected.frameCount)
            assertEquals(6L * selected.record.frameBytes, selected.payloadBytesAtAcquire)
            assertEquals(crc(fixture.payloads[1].copyOf(6 * selected.record.frameBytes)), selected.payloadChecksumAtAcquire)
            assertTrue(selected.continuesPreviousChunkAtAcquire)
            assertEquals(9L, selected.timelineStartFrame)
            assertEquals(9.0 / 48_000, selected.timelineStartSeconds, 0.0)
            assertEquals(12.0 / 48_000, selected.timelineEndSeconds, 0.0)
            assertEquals(File(fixture.record.source.objects[0].sourcePath), restored.source.range.leadingContext.single().record.file)
            assertEquals(6L, restored.source.range.leadingContext.single().startFrame)
            assertEquals(File(fixture.record.source.objects[2].sourcePath), restored.source.range.trailingContext.single().record.file)
            assertEquals(2L, restored.source.range.trailingContext.single().frameCount)
            assertEquals(fixture.record.source.objects, restored.source.objects)
            fixture.assertPinsUnchanged()
        }
    }

    @Test
    fun verifiedOutputFingerprintAndFinalNameSurviveDiskRoundTrip() = withRoot { root ->
        val fixture = fixture(root)
        val targetFile = File(root, "output.wav").absoluteFile
        val target = RecordingOutputTarget(targetFile.path, "output.wav", "audio/wav", RecordingStorageType.FILE,
            root.absolutePath, 1000L, file = targetFile, staging = true,
            stagingDisplayName = ".reverb-export.pending", stagingIdentity = "stat:1:2:3:4:0", publishedIdentity = "")
        val digest = ByteArray(32) { (it * 7).toByte() }
        val record = fixture.record.copy(stage = DurableSaveStage.VERIFIED, output = target,
            request = fixture.record.request.copy(name = "output.wav", format = ExportFormat.WAV),
            fingerprint = StableOutputFingerprint(CopyDigest(1234, digest), "stat:1:2:3:4:0", null))
        fixture.journal.write(record)
        val restored = fixture.journal.decode(fixture.manifest.readBytes(), fixture.directory)
        assertEquals(target, restored.output)
        assertEquals(1234L, restored.fingerprint!!.digest.byteCount)
        assertArrayEquals(digest, restored.fingerprint!!.digest.sha256)
        assertEquals("stat:1:2:3:4:0", restored.fingerprint!!.fileKey)
        assertNull(restored.fingerprint!!.providerIdentity)
        fixture.assertPinsUnchanged()
    }

    @Test
    fun corruptionTruncationUnknownVersionStageAndInvalidCountNeverRemovePins() = withRoot { root ->
        val fixture = fixture(root)
        fixture.journal.write(fixture.record)
        val valid = fixture.manifest.readBytes()
        val offsets = offsets(valid)
        val cases = linkedMapOf(
            "checksum" to valid.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() },
            "truncated" to valid.copyOf(valid.size - 9),
            "version" to mutateBody(valid) { ByteBuffer.wrap(it).putInt(4, 999) },
            "stage" to mutateBody(valid) { it[offsets.first] = 127 },
            "negative count" to mutateBody(valid) { ByteBuffer.wrap(it).putInt(offsets.second, -1) },
            "oversized count" to mutateBody(valid) {
                ByteBuffer.wrap(it).putInt(offsets.second, DurablePcmPins.MAX_OBJECTS + 1)
            },
            "trailing bytes" to appendBody(valid, byteArrayOf(42)),
        )
        for ((name, bytes) in cases) {
            fixture.manifest.writeBytes(bytes)
            assertThrows(name, IOException::class.java) { fixture.journal.decode(fixture.manifest.readBytes(), fixture.directory) }
            val records = mutableListOf<DurableSaveRecord>()
            val recovery = fixture.journal.recoverEach { records += it }
            assertTrue(name, records.isEmpty())
            assertTrue(name, recovery.failures.isNotEmpty())
            assertArrayEquals(name, bytes, fixture.manifest.readBytes())
            fixture.assertPinsUnchanged()
        }
    }

    @Test
    fun completePendingManifestIsPromotedAfterRestart() = withRoot { root ->
        val fixture = fixture(root)
        fixture.journal.write(fixture.record)
        val updated = fixture.record.copy(stage = DurableSaveStage.FAILED, error = "Destination unavailable")
        val temporary = File(fixture.directory, "manifest.new")
        temporary.writeBytes(fixture.journal.encode(updated))
        val records = mutableListOf<DurableSaveRecord>()
        val recovered = hostJournal(fixture.root).recoverEach { records += it }
        assertTrue(recovered.failures.isEmpty())
        assertEquals(DurableSaveStage.FAILED, records.single().stage)
        assertEquals("Destination unavailable", records.single().error)
        assertFalse(temporary.exists())
        assertEquals(DurableSaveStage.FAILED,
            fixture.journal.decode(fixture.manifest.readBytes(), fixture.directory).stage)
        fixture.assertPinsUnchanged()
    }

    @Test
    fun partialPendingManifestPreservesBothOldAuthorityAndEveryPin() = withRoot { root ->
        val fixture = fixture(root)
        fixture.journal.write(fixture.record)
        val previous = fixture.manifest.readBytes()
        val temporary = File(fixture.directory, "manifest.new")
        val torn = fixture.journal.encode(fixture.record.copy(stage = DurableSaveStage.WRITING)).copyOf(19)
        temporary.writeBytes(torn)
        val records = mutableListOf<DurableSaveRecord>()
        val recovered = hostJournal(fixture.root).recoverEach { records += it }
        assertEquals(fixture.record.id, records.single().id)
        assertEquals(DurableSaveStage.QUEUED, records.single().stage)
        assertTrue(recovered.failures.isEmpty())
        assertArrayEquals(previous, fixture.manifest.readBytes())
        assertFalse(temporary.exists())
        val preserved = requireNotNull(File(fixture.root.parentFile, "durable-save-preserved").listFiles()).single()
        assertArrayEquals(torn, preserved.readBytes())
        fixture.journal.write(fixture.record)
        fixture.assertPinsUnchanged()
    }

    @Test
    fun tornCandidateWithoutValidCommittedBaseCannotBePromotedOrDiscarded() = withRoot { root ->
        val fixture = fixture(root)
        val temporary = File(fixture.directory, "manifest.new")
        val torn = fixture.journal.encode(fixture.record).copyOf(19)
        temporary.writeBytes(torn)
        val records = mutableListOf<DurableSaveRecord>()
        val recovered = fixture.journal.recoverEach { records += it }
        assertTrue(records.isEmpty())
        assertTrue(recovered.failures.isNotEmpty())
        assertFalse(fixture.manifest.exists())
        assertArrayEquals(torn, temporary.readBytes())
        fixture.assertPinsUnchanged()
    }

    @Test
    fun nonterminalRecordCannotRetirePins() = withRoot { root ->
        val fixture = fixture(root)
        fixture.journal.write(fixture.record)
        for (stage in listOf(DurableSaveStage.QUEUED, DurableSaveStage.WRITING, DurableSaveStage.FAILED)) {
            assertThrows(IOException::class.java) { fixture.journal.retire(fixture.record.copy(stage = stage)) }
            assertTrue(fixture.manifest.exists())
            fixture.assertPinsUnchanged()
        }
    }

    @Test
    fun rejectedDescriptorProofPreservesUnpublishedManifestAndPins() = withRoot { root ->
        val fixture = fixture(root, descriptorAccepted = { false })
        assertThrows(IOException::class.java) { fixture.journal.write(fixture.record) }
        assertFalse(fixture.manifest.exists())
        val pending = File(fixture.directory, "manifest.new")
        assertTrue(pending.exists())
        assertEquals(0L, pending.length())
        fixture.assertPinsUnchanged()
    }

    @Test
    fun rejectedReadDescriptorNeverDeliversOrDeletesPreviouslyCommittedJob() = withRoot { root ->
        var descriptorAccepted = true
        val fixture = fixture(root, descriptorAccepted = { descriptorAccepted })
        fixture.journal.write(fixture.record)
        val committed = fixture.manifest.readBytes()
        descriptorAccepted = false
        val records = mutableListOf<DurableSaveRecord>()
        val recovered = fixture.journal.recoverEach { records += it }
        assertTrue(records.isEmpty())
        assertTrue(recovered.failures.isNotEmpty())
        assertArrayEquals(committed, fixture.manifest.readBytes())
        fixture.assertPinsUnchanged()
    }

    @Test
    fun directoryBarrierFailureAfterRenamePreservesRecoverableManifestAndPins() = withRoot { root ->
        var failBarrier = false
        val fixture = fixture(root, directorySync = { directory ->
            forceRecordingDirectoryDurable(directory)
            if (failBarrier) throw IOException("Injected directory durability failure")
        })
        failBarrier = true
        assertThrows(IOException::class.java) { fixture.journal.write(fixture.record) }
        assertTrue(fixture.manifest.exists())
        assertFalse(File(fixture.directory, "manifest.new").exists())
        assertEquals(fixture.record.id, fixture.journal.decode(fixture.manifest.readBytes(), fixture.directory).id)
        failBarrier = false
        val records = mutableListOf<DurableSaveRecord>()
        assertTrue(fixture.journal.recoverEach { records += it }.failures.isEmpty())
        assertEquals(fixture.record.id, records.single().id)
        fixture.assertPinsUnchanged()
    }

    @Test
    fun overlappingReferencesKeepOriginalSourceAndOtherJobOwnershipAfterRetirement() = withRoot { root ->
        val source = File(root, "store/chunks/0").absoluteFile.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(16) + ByteArray(32) { it.toByte() })
        }
        requireDescriptorProof(source)
        val record = PersistentAudioChunkStore.ChunkRecord(0u, source,
            PersistentAudioChunkStore.ChunkState.FINALIZED, 1000, 32, 8, 48_000, 2,
            PcmSampleFormat.PCM_16, crc(source.readBytes().copyOfRange(16, 48)), 1, 16)
        val segment = PersistentAudioChunkStore.Segment(record, 0, 8, 16, 32, record.payloadChecksum,
            0.0, 8.0 / 48_000)
        val range = FrozenPcmRange(listOf(segment), emptyList(), emptyList(), 1000, 1001, 8.0 / 48_000)
        val journal = DurableSaveJournal(File(root, "jobs"))
        fun admit(): DurableSaveRecord {
            val id = UUID.randomUUID().toString()
            val directory = journal.createDirectory(id)
            val pinned = DurablePcmPins.link(directory, range) { snapshot ->
                journal.write(DurableSaveRecord(id, DurableSaveStage.PINNING, request(), snapshot))
            }
            return DurableSaveRecord(id, DurableSaveStage.QUEUED, request(), pinned).also(journal::write)
        }
        val first = admit()
        val second = admit()
        val firstPin = DurablePcmPins.referenceFile(journal.directory(first.id), first.source.objects.single())
        val secondPin = DurablePcmPins.referenceFile(journal.directory(second.id), second.source.objects.single())
        assertNotEquals(firstPin, secondPin)
        assertTrue(firstPin.isFile)
        assertTrue(secondPin.isFile)
        assertEquals(source, first.source.range.segments.single().record.file)
        assertEquals(source, second.source.range.segments.single().record.file)
        val expected = source.readBytes()
        assertTrue(DurablePcmPins.isProtected(source))
        val cancelled = first.copy(stage = DurableSaveStage.CANCELLED)
        journal.write(cancelled)
        journal.retire(cancelled)
        assertFalse(journal.directory(first.id).exists())
        assertFalse(firstPin.exists())
        assertTrue(secondPin.exists())
        assertArrayEquals(expected, source.readBytes())
        assertTrue(DurablePcmPins.isProtected(source))
        DurablePcmPins.verify(journal.directory(second.id), second.source.objects.single())
        assertTrue(File(journal.directory(second.id), "manifest").exists())
    }

    @Test
    fun authoritativeMembershipIncludesMalformedJobsAndRequiresFinalRootBarrier() = withRoot { root ->
        var rootBarriers = 0
        var countBarriers = false
        val journalRoot = File(root, "jobs")
        val fixture = fixture(root, directorySync = { directory ->
            forceRecordingDirectoryDurable(directory)
            if (countBarriers && directory == journalRoot && ++rootBarriers == 2) {
                throw IOException("Injected final journal membership barrier failure")
            }
        })
        fixture.journal.write(fixture.record)
        val corruptId = UUID.randomUUID().toString()
        val corruptDirectory = fixture.journal.createDirectory(corruptId)
        val corruptBytes = byteArrayOf(1, 2, 3)
        File(corruptDirectory, "manifest").writeBytes(corruptBytes)
        val stable = fixture.journal.recoverEach { }
        assertEquals(setOf(fixture.record.id, corruptId), stable.presentIds)
        assertTrue(corruptId in stable.failures)
        countBarriers = true
        rootBarriers = 0
        assertThrows(IOException::class.java) { fixture.journal.recoverEach { } }
        assertEquals(2, rootBarriers)
        fixture.assertPinsUnchanged()
        assertArrayEquals(corruptBytes, File(corruptDirectory, "manifest").readBytes())
    }

    private data class Fixture(val root: File, val journal: DurableSaveJournal, val directory: File,
        val record: DurableSaveRecord, val payloads: List<ByteArray>, val pinBytes: Map<File, ByteArray>) {
        val manifest get() = File(directory, "manifest")
        fun assertPinsUnchanged() { pinBytes.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) } }
    }

    private fun fixture(parent: File, format: PcmSampleFormat = PcmSampleFormat.PCM_16,
        descriptorAccepted: () -> Boolean = { true },
        directorySync: (File) -> Unit = ::forceRecordingDirectoryDurable): Fixture {
        parent.mkdirs()
        val root = File(parent, "jobs")
        val journal = hostJournal(root, descriptorAccepted, directorySync)
        val id = UUID.randomUUID().toString()
        val directory = journal.createDirectory(id)
        val frameBytes = 2 * format.bytesPerSample
        val sourceDirectory = File(parent, "source/chunks").absoluteFile.apply { mkdirs() }
        val payloads = List(3) { ordinal -> ByteArray(8 * frameBytes) { (ordinal * 19 + it).toByte() } }
        val records = payloads.mapIndexed { index, payload ->
            val file = File(sourceDirectory, index.toString()).apply { writeBytes(ByteArray(16) + payload) }
            PersistentAudioChunkStore.ChunkRecord(index.toUInt(), file, PersistentAudioChunkStore.ChunkState.FINALIZED,
                1000L + index, payload.size.toLong(), 8, 48_000, 2, format, crc(payload), 7, 16)
        }
        fun segment(index: Int, start: Long, count: Long, prefixFrames: Int = 8) = PersistentAudioChunkStore.Segment(
            records[index], start, count, 16, prefixFrames.toLong() * frameBytes,
            crc(payloads[index].copyOf(prefixFrames * frameBytes)), (index * 8 + start).toDouble() / 48_000,
            (index * 8 + start + count).toDouble() / 48_000, true, index * 8L + start)
        val range = FrozenPcmRange(listOf(segment(1, 1, 3, 6)), listOf(segment(0, 6, 2)),
            listOf(segment(2, 0, 2)), 1000, 1001, 3.0 / 48_000)
        val proofs = records.mapIndexed { index, record ->
            DurablePcmPins.ObjectProof("pcm-$index", resolveFileIdentity(record.file),
                16, record.payloadBytes, record.payloadChecksum, sourcePath = record.file.absolutePath,
                sourceDirectoryIdentity = resolveDirectoryIdentity(sourceDirectory),
                storeDirectoryIdentity = resolveDirectoryIdentity(requireNotNull(sourceDirectory.parentFile)))
        }
        return Fixture(root, journal, directory, DurableSaveRecord(id, DurableSaveStage.QUEUED, request(),
            DurablePcmPins.Snapshot(range, proofs)), payloads, records.associate { it.file to it.file.readBytes() })
    }

    private fun request() = DurableSaveRequest("Selected audio.flac", "destination-at-admission", ExportFormat.FLAC,
        ExportCodec.PCM_16, 96_000, 2, PcmSampleFormat.PCM_16, 8)

    // Android Os.fstat is unavailable under ordinary JVM stubs. Model only that syscall's
    // proof result; all file creation, reads, CRC validation, fsyncs and renames remain real.
    // Explicit rejection tests exercise fail-closed branches. Native binding is covered on device.
    private fun hostJournal(root: File, descriptorAccepted: () -> Boolean = { true },
        directorySync: (File) -> Unit = ::forceRecordingDirectoryDurable) = DurableSaveJournal(root,
        descriptorMatches = { identity: String, descriptor: FileDescriptor ->
            descriptorAccepted() && descriptor.valid() && identity.isNotBlank() &&
                root.walkTopDown().any { file -> file.isFile && fileIdentityMatches(identity, resolveFileIdentity(file)) }
        }, directorySync = directorySync)

    /** Wire offsets are only for deliberate malformed-input injection, not a second encoder. */
    private fun offsets(bytes: ByteArray): Pair<Int, Int> {
        val stream = ByteArrayInputStream(bytes)
        val input = DataInputStream(stream)
        input.readInt(); input.readInt(); input.readUTF()
        val stage = bytes.size - stream.available()
        input.readByte(); input.readUTF(); input.readUTF(); input.readByte(); input.readByte()
        input.readInt(); input.readInt(); input.readByte(); input.readInt(); input.readUTF()
        input.readUTF(); input.readLong()
        input.readLong(); input.readLong(); input.readDouble()
        return stage to bytes.size - stream.available()
    }

    private fun mutateBody(bytes: ByteArray, mutate: (ByteArray) -> Unit): ByteArray =
        withChecksum(bytes.copyOf(bytes.size - 8).also(mutate))
    private fun appendBody(bytes: ByteArray, suffix: ByteArray) = withChecksum(bytes.copyOf(bytes.size - 8) + suffix)
    private fun withChecksum(body: ByteArray): ByteArray = body + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        .putLong(CRC32().apply { update(body) }.value).array()
    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value.toInt()

    /** v1 omits each proof's v2 source path and directory-identity fields. */
    private fun versionOneManifest(bytes: ByteArray): ByteArray {
        val stream = ByteArrayInputStream(bytes)
        val input = DataInputStream(stream)
        input.skipBytes(offsets(bytes).second)
        repeat(3) {
            repeat(input.readInt()) {
                input.readLong(); input.readUTF()
                repeat(3) { input.readLong() }
                input.readInt(); input.readInt(); input.readByte(); input.readInt()
                repeat(6) { input.readLong() }
                input.readInt(); input.readDouble(); input.readDouble(); input.readBoolean(); input.readLong()
            }
        }
        val count = input.readInt()
        val output = ByteArrayOutputStream()
        var copied = 0
        repeat(count) {
            input.readUTF(); input.readUTF(); input.readLong(); input.readLong(); input.readInt()
            val pathOffset = bytes.size - stream.available()
            output.write(bytes, copied, pathOffset - copied)
            repeat(3) { assertEquals("", input.readUTF()) }
            copied = bytes.size - stream.available()
        }
        output.write(bytes, copied, bytes.size - 8 - copied)
        val body = output.toByteArray()
        ByteBuffer.wrap(body).putInt(4, 1)
        return withChecksum(body)
    }

    private fun requireDescriptorProof(file: File) {
        FileInputStream(file).use { input ->
            assumeTrue("Platform file-descriptor identity is required for physical pin recovery",
                fileDescriptorIdentityMatches(resolveFileIdentity(file), resolveFileDescriptorIdentity(input.fd)))
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/durable-save-journal").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile().absoluteFile
        try { block(root) } finally { root.deleteRecursively() }
    }
}
