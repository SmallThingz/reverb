package app.smallthingz.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.runtime.getValue
import smallthingz.ui.runtime.mutableLongStateOf
import smallthingz.ui.runtime.mutableStateOf
import smallthingz.ui.runtime.setValue
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A bounded microphone sample owns a draft, never a preference write or capture intent. */
internal class SettingsSilenceCalibration(
    private val context: Context,
    private val scope: CoroutineScope,
    private val view: View,
    private val lifecycle: Lifecycle,
    private val readOwner: (SilenceGateConfig, SilenceCalibrationInput) -> Pair<Long, Int>?,
    private val onCalibrated: (SilenceGateConfig, SilenceCalibrationHistogram) -> Unit,
    private val onChanged: () -> Unit = {},
) {
    var running by mutableStateOf(false)
        private set
    var holding by mutableStateOf(false)
        private set
    var elapsedMillis by mutableLongStateOf(0L)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var measured by mutableStateOf<SilenceCalibrationResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var generation = 0L
    private var job: Job? = null
    private var activeRequest: SilenceSampleRequest? = null
    private var disposed = false

    private fun acceptsInput(): Boolean = !disposed && scope.isActive &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        view.isAttachedToWindow && view.hasWindowFocus()

    fun beginHeld(config: SilenceGateConfig, input: SilenceCalibrationInput) = begin(config, input, held = true)

    fun beginTimed(config: SilenceGateConfig, input: SilenceCalibrationInput) = begin(config, input, held = false)

    private fun begin(config: SilenceGateConfig, input: SilenceCalibrationInput, held: Boolean) {
        if (!acceptsInput() || !config.enabled || running) return
        val owner = readOwner(config, input) ?: return
        val request = ++generation
        running = true
        holding = held
        elapsedMillis = 0L
        progress = null
        error = null
        measured = null
        fun isCurrent(): Boolean = request == generation && acceptsInput() &&
            readOwner(config, input) == owner
        val startedJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = calibrateSilence(
                    createSampler = {
                        if (!isCurrent()) throw CancellationException()
                        SilenceSampleOwner(context, expectedInput = input,
                            request = SilenceSampleRequest(held).also { activeRequest = it })
                    },
                    onProgress = {
                        if (!isCurrent()) throw CancellationException()
                        elapsedMillis = it.elapsedMillis
                        progress = if (holding) null else it.progress
                        onChanged()
                    },
                )
                // Both this live admission read and the draft callback execute on Main without
                // suspension. Undo, input changes and Back revoke ownership before recomposition.
                if (isCurrent()) {
                    measured = result
                    onCalibrated(config.copy(points = result.points),
                        SilenceCalibrationHistogram(input, result.sampleRate, result.peaks.toList()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent()) error = when (failure) {
                    is SilenceCalibrationTooLoud -> context.getString(R.string.silence_calibration_too_loud)
                    is SilenceCalibrationTooShort -> context.getString(R.string.silence_calibration_too_short)
                    is SilenceCalibrationTimedOut -> context.getString(R.string.silence_calibration_timeout)
                    else -> failure.message ?: context.getString(R.string.silence_sample_failed)
                }
            } finally {
                if (request == generation) {
                    job = null
                    activeRequest = null
                    running = false
                    holding = false
                    progress = null
                    onChanged()
                }
            }
        }
        // A bind can reject synchronously; do not reinstall its already-finished job after cleanup.
        if (request == generation && running) job = startedJob
        onChanged()
    }

    /** A short press keeps its already-started sample and converts it to the ordinary timed run. */
    fun completeTap() {
        if (!holding || !running || !acceptsInput()) return
        holding = false
        activeRequest?.useTimedSample()
        progress = activeRequest?.state?.value?.progress
        onChanged()
    }

    /** A held sample ends at release; the audio owner removes both handling edges. */
    fun finishHeld() {
        if (!holding || !running || !acceptsInput()) return
        holding = false
        activeRequest?.finishHold()
        onChanged()
    }

    fun cancel(clearMeasurement: Boolean = false) {
        generation++
        activeRequest?.cancel()
        job?.cancel()
        job = null
        activeRequest = null
        running = false
        holding = false
        elapsedMillis = 0L
        progress = null
        if (clearMeasurement) {
            measured = null
            error = null
        }
        onChanged()
    }

    fun close() {
        disposed = true
        cancel(clearMeasurement = true)
    }
}

