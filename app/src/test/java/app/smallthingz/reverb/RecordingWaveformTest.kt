package app.smallthingz.reverb

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWaveformTest {
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
            storageType = RecordingStorageType.FILE.name, directoryId = "dir", fileIdentity = "stat:a",
        )
        assertEquals(recordingWaveformRevision(base), recordingWaveformRevision(base.copy(displayName = "renamed.wav")))
        assertTrue(recordingWaveformRevision(base) != recordingWaveformRevision(base.copy(fileIdentity = "stat:b")))
        assertTrue(recordingWaveformRevision(base) != recordingWaveformRevision(base.copy(sizeBytes = 4_001L)))
        val provider = base.copy(
            id = "content://recording/7",
            storageType = RecordingStorageType.MEDIASTORE.name,
            fileIdentity = "provider:MEDIASTORE:old",
        )
        assertTrue(
            recordingWaveformRevision(provider) != recordingWaveformRevision(
                provider.copy(fileIdentity = "provider:MEDIASTORE:new"),
            ),
        )
    }

    @Test
    fun waveformCacheValidationRejectsWrongRevisionMalformedPayloadAndMissingRows() {
        val recording = RecordingEntity(
            id = "id", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L, codecSummary = "WAV",
            storageType = RecordingStorageType.FILE.name, directoryId = "dir", fileIdentity = "stat:a",
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
            storageType = RecordingStorageType.FILE.name, directoryId = "old-dir", fileIdentity = "stat:old",
        )
        val encoded = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.6f })
        val cachedSource = source.copy(
            waveformData = encoded, waveformRevision = recordingWaveformRevision(source),
        )
        val target = source.copy(
            id = "content://media/9", storageType = RecordingStorageType.MEDIASTORE.name,
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
        assertFalse(providerRecordingIdentityMatches("", first))
        assertFalse(providerRecordingIdentityMatches(first, ""))
        assertFalse(providerRecordingIdentityMatches(first, "provider:MEDIASTORE:other"))
    }

    @Test
    fun waveformCache_isDisabledWithoutPhysicalOrProviderIdentity() {
        val provider = RecordingEntity(
            id = "content://media/1", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L, codecSummary = "WAV",
            storageType = RecordingStorageType.MEDIASTORE.name, directoryId = "dir",
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
}
