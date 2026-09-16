package app.smallthingz.reverb

internal const val APP_STORAGE_FOLDER_NAME = "Reverb"
internal const val BUFFER_CACHE_FOLDER_NAME = "reverb"
internal const val ONE_SHOT_BUFFER_CACHE_FOLDER_NAME = "reverb-one-shot"
internal const val BUFFER_CHUNKS_FOLDER_NAME = "chunks"
internal const val BUFFER_INDEX_A_FILE_NAME = "index.a"
internal const val BUFFER_INDEX_B_FILE_NAME = "index.b"
internal const val LEGACY_BUFFER_CACHE_FOLDER_NAME = "buffer-cache"

internal const val DATABASE_FILE_NAME = "reverb-recordings.db"
internal const val FALLBACK_MIME_TYPE_AUDIO = "audio/*"
internal const val FALLBACK_DISPLAY_NAME = "Reverb"
internal const val MIB_SUFFIX = " MiB"
internal const val CODEC_SUMMARY_SEPARATOR = " · "

internal const val PREFERRED_DEFAULT_SAMPLE_RATE = 44_100
internal const val DEFAULT_RETENTION_SECONDS = 86_400L
internal const val DEFAULT_RETENTION_SIZE_BYTES = 512L * 1024L * 1024L
internal val DEFAULT_CHANNEL_MODE = ChannelMode.MONO

internal const val FORMAT_SIZE_MIB = "0.0"
internal const val FORMAT_RETENTION_SIZE_MIB = "0.###"
internal const val THREAD_NAME_AUDIO = "reverbAudioThread"
internal const val THREAD_NAME_EXPORT_WORK = "reverbExportWork"
