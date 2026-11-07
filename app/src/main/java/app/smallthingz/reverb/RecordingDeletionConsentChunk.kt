package app.smallthingz.reverb

internal const val RECORDING_CONSENT_URI_LIMIT = 2000

internal data class RecordingConsentChunk<T>(val nextIndex: Int, val targets: List<IndexedValue<T>>)

/** Completed/failed attempts do not consume platform prompt slots. Original batch indices survive. */
internal suspend fun <T> prepareRecordingConsentChunk(
    targets: List<T>,
    startIndex: Int,
    promptLimit: Int,
    prepare: suspend (Int, T) -> T?,
): RecordingConsentChunk<T> {
    require(promptLimit in 1..RECORDING_CONSENT_URI_LIMIT)
    require(startIndex in 0..targets.size)
    val prompt = mutableListOf<IndexedValue<T>>()
    var next = startIndex
    while (next < targets.size && prompt.size < promptLimit) {
        val index = next++
        prepare(index, targets[index])?.let { prompt += IndexedValue(index, it) }
    }
    return RecordingConsentChunk(next, prompt.toList())
}
