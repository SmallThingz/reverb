package app.smallthingz.timetravel

object TimeTravelConfig {

    const val APP_STORAGE_FOLDER_NAME = "Timetravel"
    const val BUFFER_CACHE_FOLDER_NAME = "buffer-cache"
    const val BUFFER_META_FILE_NAME = "buffer.meta"
    const val BUFFER_PCM_FILE_NAME = "buffer.pcm"

    const val DATABASE_FILE_NAME = "timetravel-recordings.db"
    const val FALLBACK_MIME_TYPE_AUDIO = "audio/*"
    const val FALLBACK_DISPLAY_NAME = "TimeTravel"
    const val MIB_SUFFIX = " MiB"
    const val ESTIMATE_EXACT_PREFIX = "="
    const val ESTIMATE_APPROX_PREFIX = "~"
    const val CODEC_SUMMARY_SEPARATOR = " · "

    const val PREFERRED_DEFAULT_SAMPLE_RATE = 44_100
    const val DEFAULT_RETENTION_SECONDS = 86_400L
    const val DEFAULT_RETENTION_SIZE_BYTES = 512L * 1024L * 1024L

    val DEFAULT_CHANNEL_MODE = ChannelMode.MONO
    val DEFAULT_CUSTOM_EXPORT_MODE = CustomExportMode.PAST
    val DEFAULT_CUSTOM_EXPORT_UNIT = CustomExportUnit.TIME

    const val FORMAT_SIZE_MIB = "0.0"
    const val FORMAT_RETENTION_SIZE_MIB = "0.###"
    const val THREAD_NAME_AUDIO = "timeTravelAudioThread"
    const val THREAD_NAME_EXPORT_WORK = "timeTravelExportWork"
    const val DEBUG_ACTION_PREFIX = "app.smallthingz.timetravel.debug."
    const val EXTRA_SECONDS = "seconds"

}
