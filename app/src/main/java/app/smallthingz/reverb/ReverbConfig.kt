package app.smallthingz.reverb

object ReverbConfig {

    const val APP_STORAGE_FOLDER_NAME = "Reverb"
    const val BUFFER_CACHE_FOLDER_NAME = "reverb"
    const val ONE_SHOT_BUFFER_CACHE_FOLDER_NAME = "reverb-one-shot"
    const val BUFFER_CHUNKS_FOLDER_NAME = "chunks"
    const val BUFFER_INDEX_A_FILE_NAME = "index.a"
    const val BUFFER_INDEX_B_FILE_NAME = "index.b"
    const val LEGACY_BUFFER_CACHE_FOLDER_NAME = "buffer-cache"

    const val DATABASE_FILE_NAME = "reverb-recordings.db"
    const val FALLBACK_MIME_TYPE_AUDIO = "audio/*"
    const val FALLBACK_DISPLAY_NAME = "Reverb"
    const val MIB_SUFFIX = " MiB"
    const val ESTIMATE_EXACT_PREFIX = "="
    const val CODEC_SUMMARY_SEPARATOR = " · "

    const val PREFERRED_DEFAULT_SAMPLE_RATE = 44_100
    const val DEFAULT_RETENTION_SECONDS = 86_400L
    const val DEFAULT_RETENTION_SIZE_BYTES = 512L * 1024L * 1024L

    val DEFAULT_CHANNEL_MODE = ChannelMode.MONO

    const val FORMAT_SIZE_MIB = "0.0"
    const val FORMAT_RETENTION_SIZE_MIB = "0.###"
    const val THREAD_NAME_AUDIO = "reverbAudioThread"
    const val THREAD_NAME_EXPORT_WORK = "reverbExportWork"
    const val DEBUG_ACTION_PREFIX = "app.smallthingz.reverb.debug."
    const val EXTRA_SECONDS = "seconds"

}
