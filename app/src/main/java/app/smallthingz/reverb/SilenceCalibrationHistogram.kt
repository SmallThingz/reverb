package app.smallthingz.reverb

/**
 * Display-only, fixed-size spectrum from an accepted calibration. This is never recorder authority
 * or raw audio. It travels with the Settings draft and becomes durable only with the ordinary Save.
 */
internal data class SilenceCalibrationHistogram(
    val input: SilenceCalibrationInput,
    val measuredSampleRate: Int,
    val peaks: List<Float>,
) {
    fun peaksFor(currentInput: SilenceCalibrationInput): List<Float>? =
        peaks.takeIf { input == currentInput }

    fun validate(): SilenceCalibrationHistogram {
        require(input.sampleRate in 7_000..192_000 && measuredSampleRate in 7_000..192_000)
        require(input.channelMode != null && input.source != null && input.route != null)
        require(peaks.size == SILENCE_GRAPH_BINS && peaks.all { it.isFinite() && it in -120f..0f })
        return this
    }
}

private const val MAX_SILENCE_HISTOGRAM_CHARS = 2_048

internal fun encodeSilenceCalibrationHistogram(histogram: SilenceCalibrationHistogram): String {
    histogram.validate()
    val input = histogram.input
    return listOf(
        "1",
        input.sampleRate.toString(),
        input.sampleFormat.storageCode.toString(),
        requireNotNull(input.channelMode).storageCode.toString(),
        requireNotNull(input.source).storageCode.toString(),
        requireNotNull(input.route).storageCode.toString(),
        histogram.measuredSampleRate.toString(),
        histogram.peaks.joinToString(",") { it.toString() },
    ).joinToString(";").also { require(it.length <= MAX_SILENCE_HISTOGRAM_CHARS) }
}

/** Missing, legacy or damaged optional presentation data never blocks Settings or capture. */
internal fun decodeSilenceCalibrationHistogram(encoded: String?): SilenceCalibrationHistogram? {
    if (encoded == null || encoded.length > MAX_SILENCE_HISTOGRAM_CHARS) return null
    return try {
        val fields = encoded.split(';')
        require(fields.size == 8 && fields[0] == "1")
        val sourceCode = fields[4].toInt()
        val input = SilenceCalibrationInput(
            sampleRate = fields[1].toInt(),
            sampleFormat = requireNotNull(PcmSampleFormat.fromStorageCode(fields[2].toInt())),
            channelMode = requireNotNull(ChannelMode.fromStorageCode(fields[3].toInt())),
            source = requireNotNull(AudioSourceMode.entries.firstOrNull {
                it.storageCode.toInt() == sourceCode
            }),
            route = requireNotNull(InputRouteMode.fromStorageCode(fields[5].toInt())),
        )
        val levels = fields[7].split(',')
        require(levels.size == SILENCE_GRAPH_BINS)
        SilenceCalibrationHistogram(input, fields[6].toInt(), levels.map(String::toFloat)).validate()
    } catch (_: IllegalArgumentException) {
        null
    }
}
