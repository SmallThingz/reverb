package app.smallthingz.reverb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RANGE_WAVEFORM_MIN_AMPLITUDE_FRACTION = 0.035f
private const val RANGE_WAVEFORM_MAX_AMPLITUDE_START_FRACTION = 0.50f
private const val RANGE_WAVEFORM_MAX_AMPLITUDE_MORPH_REDUCTION_FRACTION = 0.06f
internal const val RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION =
    2f * (
        RANGE_WAVEFORM_MIN_AMPLITUDE_FRACTION +
            RANGE_WAVEFORM_MAX_AMPLITUDE_START_FRACTION -
            RANGE_WAVEFORM_MAX_AMPLITUDE_MORPH_REDUCTION_FRACTION
        )

internal suspend fun buildProgressiveWaveform(
    readPass: (RangeWaveformPass, (Int, Float) -> Boolean) -> Unit,
    onPassStarted: (RangeWaveformPass) -> Unit = {},
    onSnapshot: (RangeWaveformPass, ProgressiveWaveformSnapshot) -> Unit,
) = coroutineScope {
    for (pass in RangeWaveformPass.entries) {
        if (pass == RangeWaveformPass.DETAIL) delay(280L)
        onPassStarted(pass)
        val updates = Channel<ProgressiveWaveformSnapshot>(Channel.CONFLATED)
        val worker = launch(Dispatchers.IO) {
            val accumulator = ProgressiveWaveformAccumulator(pass.bucketCount)
            try {
                readPass(pass) { index, magnitude ->
                    val update = accumulator.record(index, magnitude)
                    update == null || updates.trySend(update).isSuccess
                }
                accumulator.finish()?.let { updates.trySend(it) }
            } finally {
                updates.close()
            }
        }
        try {
            for (update in updates) onSnapshot(pass, update)
        } finally {
            worker.cancel()
            updates.close()
        }
    }
}

internal fun rangeWaveformRenderPointCount(totalPoints: Int, morphProgress: Float): Int {
    val total = totalPoints.coerceAtLeast(2)
    return when {
        morphProgress < 0.85f -> minOf(total, 128)
        morphProgress < 0.995f -> minOf(total, 256)
        else -> total
    }
}

