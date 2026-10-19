package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
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
internal class SilenceCalibrationTooShort : IOException()
internal class SilenceCalibrationTimedOut : IOException()

/** A review draft only. No preference writes or capture-intent changes belong to calibration. */
internal fun silenceCalibrationResult(sample: SilenceSampleState): SilenceCalibrationResult {
    require(sample.complete && !sample.cancelled && !sample.tooShort && sample.error == null && sample.progress == 1f)
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
    onProgress: (SilenceSampleState) -> Unit,
    samplingTimeoutMillis: Long? = null,
): SilenceCalibrationResult {
    require(samplingTimeoutMillis == null || samplingTimeoutMillis > 0L)
    val sampler = createSampler()
    var primaryFailure: Throwable? = null
    try {
        onProgress(sampler.request.state.value)
        sampler.start()
        // Follow the same request deadline as the Service, including held-to-tap conversion
        // while binding is pending. The small outer allowance covers terminal delivery/cleanup.
        val fixedDeadline = samplingTimeoutMillis?.let { System.nanoTime() + it * 1_000_000L }
        val request = sampler.request
        var result: SilenceSampleState
        while (true) {
            val snapshot = request.state.value
            if (snapshot.complete || snapshot.cancelled || snapshot.error != null) {
                onProgress(snapshot)
                result = snapshot
                break
            }
            val observedDeadline = request.deadlineNanos.value
            val remaining = silenceSampleRemainingMillis(
                fixedDeadline ?: (observedDeadline + 2_000_000_000L), System.nanoTime())
            if (remaining == 0L) throw SilenceCalibrationTimedOut()
            val event = withTimeoutOrNull(remaining) {
                combine(request.state.onEach(onProgress), request.deadlineNanos) { sample, deadline -> sample to deadline }
                    .first { (sample, deadline) ->
                        sample.complete || sample.cancelled || sample.error != null || deadline != observedDeadline
                    }
            } ?: throw SilenceCalibrationTimedOut()
            if (event.first.complete || event.first.cancelled || event.first.error != null) {
                result = event.first
                break
            }
        }
        if (result.cancelled) throw CancellationException("Calibration cancelled")
        if (result.tooShort) throw SilenceCalibrationTooShort()
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
