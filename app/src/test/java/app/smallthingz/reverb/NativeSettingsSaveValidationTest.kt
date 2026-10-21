package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class NativeSettingsSaveValidationTest {
    private val baseline=SettingsSnapshot(format=ExportFormat.WAV,codec=ExportCodec.PCM_16,
        channelMode=ChannelMode.MONO,source=AudioSourceMode.MIC,
        route=InputRouteMode.AUTO,sampleRate=48_000,oneShotRetentionTime=5)
    private fun validate(previous:SettingsSnapshot=baseline,draft:SettingsUiDraft)=
        validateNativeSettingsSave(previous,draft) {_,_,_,_->true}

    @Test fun sizeValidationRetainsExactBytesInsteadOfReparsingRoundedText() {
        val value=baseline.copy(retentionMode=RetentionMode.SIZE,oneShotRetentionSizeBytes=4097)
        val draft=SettingsUiDraft.normalized(value).copy(oneShotSizeText="0.00")
        val accepted=requireNotNull(validate(draft=draft).draft)
        assertEquals(4097L,accepted.snapshot.oneShotRetentionSizeBytes)
        assertEquals("0.00",accepted.oneShotSizeText)
    }

    @Test fun inactiveRawModeTextRemainsUnrestrictedAndUnchanged() {
        val draft=SettingsUiDraft.normalized(baseline).copy(oneShotSizeText="unfinished.",loopingSizeText="not yet")
        val accepted=requireNotNull(validate(draft=draft).draft)
        assertEquals("unfinished.",accepted.oneShotSizeText)
        assertEquals("not yet",accepted.loopingSizeText)
    }

    @Test fun firstInvalidTimeRetainsSourceEarlyReturnOrder() {
        val draft=SettingsUiDraft.normalized(baseline).copy(oneShotTimeText="bad",loopingTimeText="also bad")
        val result=validate(draft=draft)
        assertNull(result.draft)
        assertEquals(R.string.retention_time_invalid,result.errors.oneShotTime)
        assertNull(result.errors.loopingTime)
    }

    @Test fun bothDisabledBuffersMarkOnlyTheActiveModeFields() {
        val draft=SettingsUiDraft.normalized(baseline.copy(oneShotRetentionTime=0))
        val result=validate(draft=draft)
        assertEquals(R.string.buffer_required,result.errors.oneShotTime)
        assertEquals(R.string.buffer_required,result.errors.loopingTime)
        assertNull(result.errors.oneShotSize)
        assertNull(result.errors.loopingSize)
    }

    @Test fun unchangedFlacFormatDoesNotAddNewPcmValidation() {
        val previous=baseline.copy(format=ExportFormat.FLAC,sampleFormat=PcmSampleFormat.PCM_FLOAT)
        val draft=SettingsUiDraft.normalized(previous)
        assertNotNull(validate(previous,draft).draft)
        assertEquals(R.string.flac_pcm_required,validate(baseline,draft).feedback)
    }

    @Test fun unsupportedCodecKeepsTheOriginalSilentRejection() {
        val result=validateNativeSettingsSave(baseline,SettingsUiDraft.normalized(baseline)) {_,_,_,_->false}
        assertNull(result.draft)
        assertNull(result.feedback)
        assertEquals(NativeSettingsRetentionErrors(),result.errors)
    }
}
