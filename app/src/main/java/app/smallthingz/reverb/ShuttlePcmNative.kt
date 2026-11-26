package app.smallthingz.reverb

import java.io.Closeable

/** Shuttle-only FIR batching. Null preserves the Kotlin path when this ABI is unavailable. */
internal object ShuttlePcmNative {
    // A 24 ms grain at 96 kHz and the 3x audible cap, plus independently rounded endpoints.
    internal const val MAX_SOURCE_FRAMES = 6_913
    // Runnable.run is an Android interface method, so shrinking cannot rename this JNI entry.
    private val admissionCheckpoint = Runnable { checkPcmConversionNotInterrupted() }
    private val available: Boolean by lazy {
        try {
            System.loadLibrary("reverb_file_move")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private val workerScratch = ThreadLocal<Scratch>()

    /** Both phase alignment and full-grain rendering run synchronously on this worker. */
    fun openRun(): Closeable {
        check(workerScratch.get() == null)
        val scratch = Scratch()
        val run = Closeable {
            check(workerScratch.get() === scratch)
            workerScratch.remove()
            scratch.close()
        }
        workerScratch.set(scratch)
        return run
    }

    private class Scratch : Closeable {
        private var handle = 0L
        private var inUse = false

        inline fun render(action: (Long) -> FloatArray): FloatArray? {
            // JNI invokes admission on this thread. A nested call can use Kotlin without
            // overwriting the outer filter's PCM or weights.
            if (inUse) return null
            inUse = true
            try {
                if (handle == 0L) {
                    handle = createScratchNative(PcmRateConverter.kernelTableForShuttle(), admissionCheckpoint)
                }
                return action(handle)
            } finally {
                inUse = false
            }
        }

        override fun close() {
            val owned = handle
            handle = 0L
            if (owned != 0L) releaseScratchNative(owned)
        }
    }

    fun filter(
        input: ByteArray,
        firstSourceFrame: Int,
        sourceFrames: Int,
        channelCount: Int,
        reverse: Boolean,
        outputFrames: Int,
        sampleFrames: Int,
        sourceStep: Double,
    ): FloatArray? {
        require(channelCount in 1..2)
        require(firstSourceFrame >= 0 && sourceFrames > 0)
        require((firstSourceFrame.toLong() + sourceFrames) * channelCount * 2 <= input.size)
        require(outputFrames in 1..sourceFrames && sampleFrames in 1..outputFrames)
        require(sourceStep.isFinite() && sourceStep >= 1.0)
        require(outputFrames == 1 || sourceStep == (sourceFrames - 1).toDouble() / (outputFrames - 1))
        checkPcmConversionNotInterrupted()
        // Short rounding geometries reach a step of six even though audible speed is capped at three.
        if (sourceStep <= 1.0 || sourceFrames > MAX_SOURCE_FRAMES || sourceStep > 6.0 || !available) return null
        val run = workerScratch.get()
        val scratch = run ?: Scratch()
        val result = try {
            scratch.render { handle ->
                filterWithScratchNative(handle, input, firstSourceFrame, sourceFrames, channelCount, reverse,
                    outputFrames, sampleFrames, sourceStep, admissionCheckpoint)
            }
        } finally {
            if (run == null) scratch.close()
        }
        checkPcmConversionNotInterrupted()
        return result
    }

    private external fun createScratchNative(kernelTable: FloatArray, checkpoint: Runnable): Long

    private external fun releaseScratchNative(handle: Long)

    private external fun filterWithScratchNative(
        handle: Long,
        input: ByteArray,
        firstSourceFrame: Int,
        sourceFrames: Int,
        channelCount: Int,
        reverse: Boolean,
        outputFrames: Int,
        sampleFrames: Int,
        sourceStep: Double,
        checkpoint: Runnable,
    ): FloatArray
}
