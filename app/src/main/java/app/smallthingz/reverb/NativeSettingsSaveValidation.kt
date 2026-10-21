package app.smallthingz.reverb

internal data class NativeSettingsRetentionErrors(
    val oneShotTime:Int?=null,
    val oneShotSize:Int?=null,
    val loopingTime:Int?=null,
    val loopingSize:Int?=null,
)
internal data class NativeSettingsSaveValidation(
    val draft:SettingsUiDraft?=null,
    val errors:NativeSettingsRetentionErrors=NativeSettingsRetentionErrors(),
    val feedback:Int?=null,
)

/** Released SettingsScreen780–875 in its original early-return order. No new validation policy. */
internal fun validateNativeSettingsSave(
    previous:SettingsSnapshot,
    draft:SettingsUiDraft,
    codecSupported:(ExportFormat,ExportCodec,Int,ChannelMode)->Boolean=::isCodecSupported,
):NativeSettingsSaveValidation {
    val value=draft.snapshot
    val format=requireNotNull(value.format)
    val channelMode=requireNotNull(value.channelMode)
    val sampleFormat=value.sampleFormat
    if(format!=previous.format&&format==ExportFormat.FLAC&&sampleFormat!=PcmSampleFormat.PCM_16)
        return NativeSettingsSaveValidation(feedback=R.string.flac_pcm_required)
    val oneShotTime=if(value.retentionMode==RetentionMode.TIME)
        parseRetentionTimeSeconds(draft.oneShotTimeText.trim())else value.oneShotRetentionTime
    if(oneShotTime==null||oneShotTime<0)return NativeSettingsSaveValidation(
        errors=NativeSettingsRetentionErrors(oneShotTime=R.string.retention_time_invalid))
    val loopingTime=if(value.retentionMode==RetentionMode.TIME)
        parseRetentionTimeSeconds(draft.loopingTimeText.trim())else value.loopingRetentionTime
    if(loopingTime==null||loopingTime<0)return NativeSettingsSaveValidation(
        errors=NativeSettingsRetentionErrors(loopingTime=R.string.retention_time_invalid))
    if(value.retentionMode==RetentionMode.SIZE) {
        val first=parseRetentionSizeMib(draft.oneShotSizeText.trim())
        if(first==null||first<0.0)return NativeSettingsSaveValidation(
            errors=NativeSettingsRetentionErrors(oneShotSize=R.string.custom_memory_size_invalid))
        val second=parseRetentionSizeMib(draft.loopingSizeText.trim())
        if(second==null||second<0.0)return NativeSettingsSaveValidation(
            errors=NativeSettingsRetentionErrors(loopingSize=R.string.custom_memory_size_invalid))
        // Source checks visible rounded validity but preserves exact accepted backing byte counts.
    }
    if(value.sampleRate<=0||!codecSupported(format,ExportCodec.PCM_16,value.sampleRate,channelMode))
        return NativeSettingsSaveValidation()
    if(value.retentionMode==RetentionMode.SIZE) {
        val frameBytes=channelMode.channelCount.toLong()*sampleFormat.bytesPerSample
        if(value.oneShotRetentionSizeBytes!=0L&&value.oneShotRetentionSizeBytes<frameBytes)
            return NativeSettingsSaveValidation(errors=NativeSettingsRetentionErrors(oneShotSize=R.string.custom_memory_size_invalid))
        if(value.loopingRetentionSizeBytes!=0L&&value.loopingRetentionSizeBytes<frameBytes)
            return NativeSettingsSaveValidation(errors=NativeSettingsRetentionErrors(loopingSize=R.string.custom_memory_size_invalid))
    }
    val firstEnabled=when(value.retentionMode) {
        RetentionMode.TIME->oneShotTime>0
        RetentionMode.SIZE->value.oneShotRetentionSizeBytes>0L
    }
    val secondEnabled=when(value.retentionMode) {
        RetentionMode.TIME->loopingTime>0
        RetentionMode.SIZE->value.loopingRetentionSizeBytes>0L
    }
    if(!firstEnabled&&!secondEnabled)return NativeSettingsSaveValidation(errors=
        if(value.retentionMode==RetentionMode.TIME)NativeSettingsRetentionErrors(
            oneShotTime=R.string.buffer_required,loopingTime=R.string.buffer_required)
        else NativeSettingsRetentionErrors(oneShotSize=R.string.buffer_required,loopingSize=R.string.buffer_required))
    return NativeSettingsSaveValidation(draft=draft.copy(snapshot=value.copy(
        oneShotRetentionTime=oneShotTime,loopingRetentionTime=loopingTime)))
}
