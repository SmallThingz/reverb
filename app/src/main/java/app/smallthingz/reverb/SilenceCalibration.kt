package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

internal interface SilenceSampler {
    val request: SilenceSampleRequest
    fun start()
    fun close()
}

internal data class SilenceCalibrationResult(
    val points: List<SilencePoint>,
    val sampleRate: Int,
    val peaks: List<Float>,
)

internal class SilenceCalibrationTooLoud : IOException()
internal class SilenceCalibrationTimedOut : IOException()

/** A review draft only. No preference writes or capture-intent changes belong to calibration. */
internal fun silenceCalibrationResult(sample: SilenceSampleState): SilenceCalibrationResult {
    require(sample.complete && !sample.cancelled && sample.error == null && sample.progress == 1f)
    require(sample.sampleRate in 7_000..192_000)
    require(sample.peaks.size == SILENCE_GRAPH_BINS && sample.peaks.all { it.isFinite() })
    // A loud interruption is not a useful quiet-room baseline. Do not turn speech or a handling
    // bump into an aggressive cutoff; the user can retry without losing the current draft.
    if (sample.peaks.any { it > -18f }) throw SilenceCalibrationTooLoud()
    return SilenceCalibrationResult(
        points = sampledSilenceCurve(sample.peaks, minOf(SILENCE_MAX_HZ, sample.sampleRate / 2)),
        sampleRate = sample.sampleRate,
        peaks = sample.peaks.toList(),
    )
}

internal suspend fun calibrateSilence(
    createSampler: () -> SilenceSampler,
    onPreparing: (remainingSeconds: Int) -> Unit,
    onProgress: (SilenceSampleState) -> Unit,
    preparationMillis: Long = 3_000L,
    samplingTimeoutMillis: Long = 10_000L,
): SilenceCalibrationResult {
    require(preparationMillis in 0L..5_000L && samplingTimeoutMillis in 1L..30_000L)
    var remaining = preparationMillis
    while (remaining > 0L) {
        onPreparing(((remaining + 999L) / 1_000L).toInt())
        val wait = minOf(1_000L, remaining)
        delay(wait)
        remaining -= wait
    }
    val sampler = createSampler()
    var primaryFailure: Throwable? = null
    try {
        onProgress(sampler.request.state.value)
        sampler.start()
        val result = withTimeoutOrNull(samplingTimeoutMillis) {
            sampler.request.state.onEach(onProgress).first {
                it.complete || it.cancelled || it.error != null
            }
        } ?: throw SilenceCalibrationTimedOut()
        if (result.cancelled) throw CancellationException("Calibration cancelled")
        result.error?.let { throw IOException(it) }
        return silenceCalibrationResult(result)
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        try { sampler.close() }
        catch (cleanup: Throwable) {
            if (primaryFailure == null) throw cleanup
            if (cleanup !== primaryFailure) primaryFailure.addSuppressed(cleanup)
        }
    }
}
