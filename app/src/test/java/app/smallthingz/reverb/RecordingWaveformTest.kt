package app.smallthingz.reverb

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWaveformTest {
    @Test
    fun providerPcmRead_verifiesDescriptorHandoffBeforeConsumingBytes() {
        val expected = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT,
            "content://docs/tree/root/document/clip",
            4L,
            7L,
        )
        val replacement = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT,
            "content://docs/tree/root/document/other",
            4L,
            8L,
        )
        val events = mutableListOf<String>()

        assertThrows(IOException::class.java) {
            consumeProviderReadAfterVerifiedHandoff(
                expectedIdentity = expected,
                beforeOpenIdentity = expected,
                afterOpenIdentity = { events += "identity"; replacement },
                consume = { events += "consume"; Unit },
            )
        }

        assertEquals(listOf("identity"), events)
    }

    @Test
    fun wavLayoutParsesPcm16Frames() {
        val file = writePcm16Wav(
            sampleRate = 8_000,
            samples = ShortArray(8_000) { 2_000 },
        )
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = readWavPcmLayout(channel)
                assertEquals(8_000, layout.sampleRate)
                assertEquals(1, layout.channelCount)
                assertEquals(PcmSampleFormat.PCM_16, layout.sampleFormat)
                assertEquals(8_000L, layout.frameCount)
                assertEquals(1.0, layout.durationSeconds, 0.000_001)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun savedRecordingPcmRangeNormalizesToMonoPcm16AtRequestedRate() {
        val samples = ShortArray(8_000) { index -> ((index % 1_000) * 20 - 10_000).toShort() }
        val file = writePcm16Wav(sampleRate = 8_000, samples = samples)
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = readWavPcmLayout(channel)
                val pcm = readWavPcm16MonoRange(
                    channel = channel,
                    layout = layout,
                    startSeconds = 0.25,
                    endSeconds = 0.50,
                    targetSampleRate = 16_000,
                )
                assertEquals(4_000, pcm.size / 2)
                assertTrue(pcm.any { it.toInt() != 0 })
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun savedRecordingPcmRangeRejectsOversizedSourceBeforeAllocation() {
        val file = testFile("oversized-source-${System.nanoTime()}.wav")
        file.writeBytes(byteArrayOf(0, 0))
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = WavPcmLayout(
                    sampleRate = 1,
                    channelCount = 2,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    dataOffsetBytes = 0L,
                    dataBytes = Int.MAX_VALUE.toLong() + 1L,
                )
                val failure = runCatching {
                    readWavPcm16MonoRange(
                        channel = channel,
                        layout = layout,
                        startSeconds = 0.0,
                        endSeconds = layout.durationSeconds,
                        targetSampleRate = 1,
                    )
                }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals("Requested PCM source range is too large", failure?.message)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun savedRecordingPcmRangeRejectsOversizedOutputBeforeAllocation() {
        val file = testFile("oversized-output-${System.nanoTime()}.wav")
        file.writeBytes(byteArrayOf(0, 0))
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = WavPcmLayout(
                    sampleRate = 1,
                    channelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    dataOffsetBytes = 0L,
                    dataBytes = 2L,
                )
                val failure = runCatching {
                    readWavPcm16MonoRange(
                        channel = channel,
                        layout = layout,
                        startSeconds = 0.0,
                        endSeconds = 1.0,
                        targetSampleRate = Int.MAX_VALUE,
                    )
                }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals("Requested PCM output range is too large", failure?.message)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun savedRecordingWaveformUsesRangeExportShapeAndFindsLouderRegion() {
        val samples = ShortArray(12_000) { index ->
            if (index < 6_000) 500 else 28_000
        }
        val file = writePcm16Wav(sampleRate = 12_000, samples = samples)
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = readWavPcmLayout(channel)
                val envelope = sampleWavWaveformEnvelopeProgressive(
                    channel = channel,
                    layout = layout,
                    pass = RangeWaveformPass.COARSE,
                    onBucket = { _, _ -> true },
                )
                assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, envelope.size)
                assertTrue(envelope.all { it in 0f..1f })
                val firstHalf = envelope.take(envelope.size / 2).average()
                val secondHalf = envelope.drop(envelope.size / 2).average()
                assertTrue("loud half should remain visibly louder", secondHalf > firstHalf + 0.12)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun progressiveWaveformStopsAtRequestedFrontier() {
        val file = writePcm16Wav(
            sampleRate = 8_000,
            samples = ShortArray(8_000) { 12_000 },
        )
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = readWavPcmLayout(channel)
                val stopAfter = 11
                val envelope = sampleWavWaveformEnvelopeProgressive(
                    channel = channel,
                    layout = layout,
                    pass = RangeWaveformPass.COARSE,
                    onBucket = { index, _ -> index < stopAfter },
                )
                assertTrue(envelope.take(stopAfter + 1).all { it > 0f })
                assertTrue(envelope.drop(stopAfter + 1).all { it == 0f })
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLayoutAcceptsPaddedOddPcm8Container() {
        val file = testFile("pcm8-padded-${System.nanoTime()}.wav")
        val payload = byteArrayOf(1, 2, 3)
        file.writeBytes(
            buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_8, payload.size.toLong()) +
                payload + byteArrayOf(0),
        )
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = readWavPcmLayout(channel)
                assertEquals(PcmSampleFormat.PCM_8, layout.sampleFormat)
                assertEquals(3L, layout.frameCount)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLayoutAcceptsFloatFactChunkContainer() {
        val file = testFile("float-wave-${System.nanoTime()}.wav")
        val payload = ByteArray(8)
        file.writeBytes(buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_FLOAT, payload.size.toLong()) + payload)
        try {
            FileInputStream(file).channel.use { channel ->
                val layout = readWavPcmLayout(channel)
                assertEquals(PcmSampleFormat.PCM_FLOAT, layout.sampleFormat)
                assertEquals(2L, layout.frameCount)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLayoutRejectsPartialFinalPcmFrame() {
        val file = testFile("partial-frame-${System.nanoTime()}.wav")
        val payload = byteArrayOf(1, 2, 3)
        file.writeBytes(
            buildWavHeaderBytes(
                sampleRate = 8_000,
                channelCount = 1,
                sampleFormat = PcmSampleFormat.PCM_16,
                dataSize = payload.size.toLong(),
            ) + payload + byteArrayOf(0),
        )
        try {
            val failure = runCatching {
                FileInputStream(file).channel.use(::readWavPcmLayout)
            }.exceptionOrNull()
            assertTrue(failure is IOException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLayoutRejectsBytesOutsideDeclaredRiffContainer() {
        val file = testFile("riff-trailing-${System.nanoTime()}.wav")
        val payload = byteArrayOf(1, 0, 2, 0)
        file.writeBytes(
            buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, payload.size.toLong()) +
                payload + byteArrayOf(99),
        )
        try {
            assertThrows(IOException::class.java) {
                FileInputStream(file).channel.use(::readWavPcmLayout)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLayoutRejectsMissingOddDataPadding() {
        val file = testFile("riff-padding-${System.nanoTime()}.wav")
        val payload = byteArrayOf(1, 2, 3)
        file.writeBytes(
            buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_8, payload.size.toLong()) + payload,
        )
        try {
            assertThrows(IOException::class.java) {
                FileInputStream(file).channel.use(::readWavPcmLayout)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLayoutRejectsInconsistentDeclaredByteRate() {
        val file = testFile("riff-byte-rate-${System.nanoTime()}.wav")
        val payload = byteArrayOf(1, 0, 2, 0)
        val bytes = buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, payload.size.toLong()) + payload
        // PCM RIFF fmt payload begins at byte 20; byte-rate is bytes 28..31.
        bytes[28] = 0
        bytes[29] = 0
        bytes[30] = 0
        bytes[31] = 0
        file.writeBytes(bytes)
        try {
            assertThrows(IOException::class.java) {
                FileInputStream(file).channel.use(::readWavPcmLayout)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLayoutRejectsNonWaveInput() {
        val file = testFile("not-wave.bin")
        file.writeBytes(ByteArray(64) { it.toByte() })
        try {
            val failure = runCatching {
                FileInputStream(file).channel.use(::readWavPcmLayout)
            }.exceptionOrNull()
            assertTrue(failure is IOException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun trimNameKeepsOriginalStemAndDoesNotOverwriteOriginal() {
        assertEquals("17892961950114 trim", trimmedRecordingBaseName("17892961950114.wav"))
        assertEquals("meeting notes trim", trimmedRecordingBaseName("meeting notes.wav"))
    }
    @Test
    fun inlineTrim_requiresPreparedRealDuration() {
        assertFalse(canEnterInlineTrim(prepared = false, durationMillis = 120_000))
        assertFalse(canEnterInlineTrim(prepared = true, durationMillis = 0))
        assertTrue(canEnterInlineTrim(prepared = true, durationMillis = 120_000))
    }


    @Test
    fun sharedPcmMagnitudeMatchesExpectedScale() {
        val sample = (0.5f * Short.MAX_VALUE).roundToInt().toShort()
        val bytes = byteArrayOf(
            (sample.toInt() and 0xff).toByte(),
            ((sample.toInt() ushr 8) and 0xff).toByte(),
        )
        assertEquals(0.5f, waveformSampleMagnitude(bytes, 0, PcmSampleFormat.PCM_16), 0.001f)
    }

    @Test
    fun waveformCacheRoundTripsQuantizedDetailAndBuildsCoarseShape() {
        val detail = FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { index ->
            index.toFloat() / (RANGE_WAVEFORM_DETAIL_BUCKETS - 1).toFloat()
        }
        val encoded = encodeRecordingWaveform(detail)
        assertEquals(683, encoded.length)
        assertEquals(null, decodeRecordingWaveform("A".repeat(100_000)))
        val decoded = requireNotNull(decodeRecordingWaveform(encoded))
        assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, decoded.size)
        decoded.indices.forEach { index ->
            assertEquals(detail[index], decoded[index], 1f / 255f + 0.0001f)
        }
        val coarse = coarseWaveformFromDetail(decoded)
        assertEquals(RANGE_WAVEFORM_COARSE_BUCKETS, coarse.size)
        assertTrue(coarse.first() < coarse.last())
    }

    @Test
    fun waveformRevisionChangesWhenPhysicalContentIdentityChanges() {
        val base = RecordingEntity(
            id = "id", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L, codecSummary = "WAV",
            storageType = RecordingStorageType.FILE, directoryId = "dir", fileIdentity = "stat:a",
        )
        assertEquals(recordingWaveformRevision(base), recordingWaveformRevision(base.copy(displayName = "renamed.wav")))
        assertTrue(recordingWaveformRevision(base) != recordingWaveformRevision(base.copy(fileIdentity = "stat:b")))
        assertTrue(recordingWaveformRevision(base) != recordingWaveformRevision(base.copy(sizeBytes = 4_001L)))
        val provider = base.copy(
            id = "content://recording/7",
            storageType = RecordingStorageType.MEDIASTORE,
            fileIdentity = "provider:MEDIASTORE:old",
        )
        assertTrue(
            recordingWaveformRevision(provider) != recordingWaveformRevision(
                provider.copy(fileIdentity = "provider:MEDIASTORE:new"),
            ),
        )
    }

    @Test
    fun fileReadIdentity_requiresPinnedDescriptorAndCurrentPath() {
        val expected = "stat:1:2:100:5:77"
        assertTrue(
            fileReadIdentityRemainsCurrent(
                expectedIdentity = expected,
                descriptorIdentity = "statfd:1:2:100:5",
                pathIdentity = expected,
            ),
        )
        assertFalse(
            fileReadIdentityRemainsCurrent(
                expectedIdentity = expected,
                descriptorIdentity = "statfd:1:9:100:5",
                pathIdentity = expected,
            ),
        )
        assertFalse(
            fileReadIdentityRemainsCurrent(
                expectedIdentity = expected,
                descriptorIdentity = "statfd:1:2:100:5",
                pathIdentity = "stat:1:9:100:5:77",
            ),
        )
        assertFalse(
            fileReadIdentityRemainsCurrent(
                expectedIdentity = expected,
                descriptorIdentity = "statfd:1:2:100:5",
                pathIdentity = "",
            ),
        )
    }

    @Test
    fun waveformCacheValidationRejectsWrongRevisionMalformedPayloadAndMissingRows() {
        val recording = RecordingEntity(
            id = "id", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L, codecSummary = "WAV",
            storageType = RecordingStorageType.FILE, directoryId = "dir", fileIdentity = "stat:a",
        )
        val encoded = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.25f })
        val revision = recordingWaveformRevision(recording)

        assertTrue(isValidRecordingWaveformCache(recording, encoded, revision))
        assertFalse(isValidRecordingWaveformCache(recording, encoded, "stale"))
        assertFalse(isValidRecordingWaveformCache(recording, "not-base64!", revision))
        assertFalse(
            isValidRecordingWaveformCache(
                recording.copy(missingSinceMillis = 123L), encoded, revision,
            ),
        )
    }

    @Test
    fun waveformCacheRebindsOnlyAcrossVerifiedIdentities() {
        val source = RecordingEntity(
            id = "old", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L, codecSummary = "WAV",
            storageType = RecordingStorageType.FILE, directoryId = "old-dir", fileIdentity = "stat:old",
        )
        val encoded = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.6f })
        val cachedSource = source.copy(
            waveformData = encoded, waveformRevision = recordingWaveformRevision(source),
        )
        val target = source.copy(
            id = "content://media/9", storageType = RecordingStorageType.MEDIASTORE,
            directoryId = MEDIA_STORE_DIRECTORY_ID, fileIdentity = "provider:MEDIASTORE:new",
        )

        val rebound = rebindRecordingWaveformCache(cachedSource, target)
        assertEquals(encoded, rebound.waveformData)
        assertEquals(recordingWaveformRevision(target), rebound.waveformRevision)

        val malformed = rebindRecordingWaveformCache(cachedSource.copy(waveformData = "bad"), target)
        assertEquals("", malformed.waveformData)
        assertEquals("", malformed.waveformRevision)

        val staleSource = rebindRecordingWaveformCache(
            cachedSource.copy(waveformRevision = "stale-source-revision"), target,
        )
        assertEquals("", staleSource.waveformData)
        assertEquals("", staleSource.waveformRevision)

        val unproven = rebindRecordingWaveformCache(cachedSource, target.copy(fileIdentity = ""))
        assertEquals("", unproven.waveformData)
        assertEquals("", unproven.waveformRevision)
    }

    @Test
    fun providerIdentity_requiresTrustworthyRevisionAndChangesWithContentRevision() {
        val first = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE, "content://media/1", 4_000L, 10L,
        )
        assertTrue(first.startsWith("provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:"))
        assertEquals(first, providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE, "content://media/1", 4_000L, 10L,
        ))
        assertTrue(first != providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE, "content://media/1", 4_000L, 11L,
        ))
        assertEquals("", providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/1", 4_000L, 0L,
        ))
        val legacy = first.replaceFirst(
            "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:",
            "provider:MEDIASTORE:",
        )
        assertTrue(providerRecordingIdentityMatches(first, first))
        assertTrue(providerRecordingIdentityMatches(legacy, first))
        assertTrue(providerRecordingIdentityMatches(first, legacy))

        val request = VerifiedProviderRequest(
            storageType = RecordingStorageType.MEDIASTORE,
            sourceId = "content://media/external/audio/media/1",
            expectedIdentity = first,
            mimeType = "audio/wav",
            displayName = "clip.wav",
            sizeBytes = 4_000L,
        )
        assertEquals(request, decodeVerifiedProviderPathSegments(verifiedProviderPathSegments(request)))
        val documentRequest = request.copy(
            storageType = RecordingStorageType.DOCUMENT,
            sourceId = "content://docs/tree/root/document/1",
        )
        assertEquals(
            documentRequest,
            decodeVerifiedProviderPathSegments(verifiedProviderPathSegments(documentRequest)),
        )
        assertEquals(null, decodeVerifiedProviderPathSegments(verifiedProviderPathSegments(request).dropLast(1)))
        val aliasedSource = request.copy(sourceId = "content://me%64ia/external/audio/media/1")
        assertEquals(null, decodeVerifiedProviderPathSegments(verifiedProviderPathSegments(aliasedSource)))
        val mismatchedStorage = request.copy(storageType = RecordingStorageType.DOCUMENT)
        assertEquals(null, decodeVerifiedProviderPathSegments(verifiedProviderPathSegments(mismatchedStorage)))
        val malformedDocument = request.copy(
            storageType = RecordingStorageType.DOCUMENT,
            sourceId = "content://docs/tree/root/document/1?query=1",
        )
        assertEquals(null, decodeVerifiedProviderPathSegments(verifiedProviderPathSegments(malformedDocument)))
        assertEquals(request.mimeType, verifiedProviderMimeType(request, first))
        assertEquals(null, verifiedProviderMimeType(request, "provider:2:other:1234:9"))
        assertFalse(providerRecordingIdentityMatches("", first))
        assertFalse(providerRecordingIdentityMatches(first, ""))
        assertFalse(providerRecordingIdentityMatches(first, "provider:MEDIASTORE:other"))
    }

    @Test
    fun waveformCache_isDisabledWithoutPhysicalOrProviderIdentity() {
        val provider = RecordingEntity(
            id = "content://media/1", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L, codecSummary = "WAV",
            storageType = RecordingStorageType.MEDIASTORE, directoryId = "dir",
        )
        assertEquals("", recordingWaveformRevision(provider))
        val encoded = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.4f })
        assertFalse(isValidRecordingWaveformCache(provider, encoded, ""))
        assertTrue(recordingWaveformRevision(provider.copy(fileIdentity = "provider:MEDIASTORE:x:4000:9")).isNotBlank())
    }

    private fun writePcm16Wav(sampleRate: Int, samples: ShortArray): File {
        val payload = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            payload[index * 2] = (sample.toInt() and 0xff).toByte()
            payload[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        val file = testFile("wave-${System.nanoTime()}.wav")
        FileOutputStream(file).use { output ->
            output.write(
                buildWavHeaderBytes(
                    sampleRate = sampleRate,
                    channelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    dataSize = payload.size.toLong(),
                ),
            )
            output.write(payload)
        }
        return file
    }

    private fun testFile(name: String): File {
        val directory = File("build/tmp/recording-waveform-tests")
        check(directory.exists() || directory.mkdirs())
        return File(directory, name)
    }
    @Test
    fun pcmReader_terminalCloseFailureIsVisibleAndNotRetried() {
        val file = writePcm16Wav(
            sampleRate = 8_000,
            samples = shortArrayOf(1, 2, 3, 4),
        )
        val input = FileInputStream(file)
        val expected = IOException("reader close failed")
        var closeAttempts = 0
        val reader = RecordingPcm16MonoReader(
            channel = input.channel,
            validateRead = { true },
            closeAction = {
                closeAttempts++
                throw expected
            },
            layout = readWavPcmLayout(input.channel),
        )
        try {
            val thrown = assertThrows(IOException::class.java) { reader.close() }
            assertSame(expected, thrown)
            assertEquals(1, closeAttempts)

            reader.close()
            assertEquals(1, closeAttempts)
        } finally {
            input.close()
            file.delete()
        }
    }

}
