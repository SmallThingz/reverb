package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOutputFormatPolicyTest {
    @Test fun formatChangeToFlacIsReadAtCommitAndRejectsIncompatiblePrecision() {
        for (samples in PcmSampleFormat.entries.filter { it != PcmSampleFormat.PCM_16 }) {
            var format = ExportFormat.WAV
            val readFormat = { format }
            format = ExportFormat.FLAC
            var commits = 0
            assertFalse(commitSettingsForCurrentOutput(readFormat, ExportCodec.PCM_16,
                48_000, ChannelMode.MONO, samples) { commits++; true })
            assertEquals(0, commits)
            assertEquals(ExportFormat.FLAC, format)
        }
    }

    @Test fun existingFlacAndWavCommitWithoutChangingTheirFormat() {
        for (format in ExportFormat.entries) {
            var reads = 0
            var commits = 0
            assertTrue(commitSettingsForCurrentOutput({ reads++; format }, ExportCodec.PCM_16,
                48_000, ChannelMode.MONO, PcmSampleFormat.PCM_16) { commits++; true })
            assertEquals(1, reads)
            assertEquals(1, commits)
        }
    }

    @Test fun unsupportedLatestFlacRateDoesNotCommit() {
        var commits = 0
        assertFalse(commitSettingsForCurrentOutput({ ExportFormat.FLAC }, ExportCodec.PCM_16,
            655_351, ChannelMode.MONO, PcmSampleFormat.PCM_16) { commits++; true })
        assertEquals(0, commits)
    }

    @Test fun durableCommitFailureRemainsFailure() {
        assertFalse(commitSettingsForCurrentOutput({ ExportFormat.FLAC }, ExportCodec.PCM_16,
            48_000, ChannelMode.MONO, PcmSampleFormat.PCM_16) { false })
    }

    @Test fun selectingCompressionRejectsEveryIncompatiblePrecisionBeforeCommit() {
        for (samples in PcmSampleFormat.entries.filter { it != PcmSampleFormat.PCM_16 }) {
            var commits = 0
            assertFalse(commitSettingsForSelectedOutput(
                ExportFormat.WAV, ExportFormat.FLAC,
                { error("An edited format must not be replaced by the persisted format") },
                ExportCodec.PCM_16, 48_000, ChannelMode.MONO, samples,
            ) { commits++; true })
            assertEquals(0, commits)
        }
    }

    @Test fun disablingCompressionAllowsWavPrecisionDespitePersistedFlac() {
        for (samples in PcmSampleFormat.entries) {
            var output: ExportFormat? = null
            assertTrue(commitSettingsForSelectedOutput(
                ExportFormat.FLAC, ExportFormat.WAV,
                { error("The explicit WAV selection owns this output change") },
                ExportCodec.PCM_16, 48_000, ChannelMode.STEREO, samples,
            ) { output = it; true })
            assertEquals(ExportFormat.WAV, output)
        }
    }

    @Test fun enablingCompressionCommitsSelectedFlacOnce() {
        var commits = 0
        var output = ExportFormat.WAV
        assertTrue(commitSettingsForSelectedOutput(
            ExportFormat.WAV, ExportFormat.FLAC,
            { error("The explicit FLAC selection owns this output change") },
            ExportCodec.PCM_16, 48_000, ChannelMode.STEREO, PcmSampleFormat.PCM_16,
        ) { output = it; commits++; true })
        assertEquals(ExportFormat.FLAC, output)
        assertEquals(1, commits)
    }

    @Test fun untouchedSelectionReadsTheLatestFormatOnceAndPassesItToCommit() {
        for (original in ExportFormat.entries) {
            val latest = ExportFormat.entries.single { it != original }
            var reads = 0
            var commits = 0
            var output: ExportFormat? = null
            assertTrue(commitSettingsForSelectedOutput(
                original, original, { reads++; latest },
                ExportCodec.PCM_16, 48_000, ChannelMode.MONO, PcmSampleFormat.PCM_16,
            ) { output = it; commits++; true })
            assertEquals(latest, output)
            assertEquals(1, reads)
            assertEquals(1, commits)
        }
    }

    @Test fun untouchedWavRejectsPrecisionWhenConcurrentWriterSelectedFlac() {
        for (samples in PcmSampleFormat.entries.filter { it != PcmSampleFormat.PCM_16 }) {
            var persisted = ExportFormat.WAV
            val readCurrent = { persisted }
            persisted = ExportFormat.FLAC
            var commits = 0
            assertFalse(commitSettingsForSelectedOutput(
                ExportFormat.WAV, ExportFormat.WAV, readCurrent,
                ExportCodec.PCM_16, 48_000, ChannelMode.MONO, samples,
            ) { commits++; true })
            assertEquals(0, commits)
            assertEquals(ExportFormat.FLAC, persisted)
        }
    }

    @Test fun untouchedFormerFlacUsesLatestWavPrecisionRules() {
        var output: ExportFormat? = null
        assertTrue(commitSettingsForSelectedOutput(
            ExportFormat.FLAC, ExportFormat.FLAC, { ExportFormat.WAV },
            ExportCodec.PCM_16, 48_000, ChannelMode.MONO, PcmSampleFormat.PCM_FLOAT,
        ) { output = it; true })
        assertEquals(ExportFormat.WAV, output)
    }

    @Test fun unsupportedSelectedFlacRateDoesNotCommit() {
        var commits = 0
        assertFalse(commitSettingsForSelectedOutput(
            ExportFormat.WAV, ExportFormat.FLAC, { ExportFormat.WAV },
            ExportCodec.PCM_16, 655_351, ChannelMode.MONO, PcmSampleFormat.PCM_16,
        ) { commits++; true })
        assertEquals(0, commits)
    }

    @Test fun selectedOutputDurableCommitFailureRemainsFailure() {
        var commits = 0
        assertFalse(commitSettingsForSelectedOutput(
            ExportFormat.WAV, ExportFormat.FLAC, { ExportFormat.WAV },
            ExportCodec.PCM_16, 48_000, ChannelMode.MONO, PcmSampleFormat.PCM_16,
        ) { commits++; false })
        assertEquals(1, commits)
    }

    @Test fun unchangedOutputRevisionAdoptsActualCommitDespiteUnrelatedDraftEdits() {
        // Theme/retention edits advance the enclosing draft revision, not the output revision.
        for ((current, committed) in listOf(
            ExportFormat.WAV to ExportFormat.FLAC, ExportFormat.FLAC to ExportFormat.WAV,
        )) {
            assertEquals(committed, reconcileSettingsOutputAfterCommit(
                current, committed, submittedOutputRevision = 7, currentOutputRevision = 7,
            ))
        }
    }

    @Test fun undoToTheSubmittedValueSurvivesLateAdoptionOfConcurrentFlac() {
        // The submitted value and the explicit Undo are both WAV, so value equality is insufficient.
        assertEquals(ExportFormat.WAV, reconcileSettingsOutputAfterCommit(
            ExportFormat.WAV, ExportFormat.FLAC, submittedOutputRevision = 4, currentOutputRevision = 5,
        ))
    }

    @Test fun outputEditsAndRoundTripsAfterSubmissionRemainUnsaved() {
        assertEquals(ExportFormat.FLAC, reconcileSettingsOutputAfterCommit(
            ExportFormat.FLAC, ExportFormat.WAV, submittedOutputRevision = 10, currentOutputRevision = 11,
        ))
        assertEquals(ExportFormat.WAV, reconcileSettingsOutputAfterCommit(
            ExportFormat.WAV, ExportFormat.FLAC, submittedOutputRevision = 10, currentOutputRevision = 12,
        ))
    }
}
