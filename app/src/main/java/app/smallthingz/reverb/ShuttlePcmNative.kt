package app.smallthingz.reverb

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
        val result = filterNative(input, firstSourceFrame, sourceFrames, channelCount, reverse,
            outputFrames, sampleFrames, sourceStep, PcmRateConverter.kernelTableForShuttle(),
            admissionCheckpoint)
        checkPcmConversionNotInterrupted()
        return result
    }

    private external fun filterNative(
        input: ByteArray,
        firstSourceFrame: Int,
        sourceFrames: Int,
        channelCount: Int,
        reverse: Boolean,
        outputFrames: Int,
        sampleFrames: Int,
        sourceStep: Double,
        kernelTable: FloatArray,
        checkpoint: Runnable,
    ): FloatArray
}
