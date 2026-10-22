package app.smallthingz.reverb.nativeui

/**
 * One contour per drawing View. Selection and palette affect painting, not this geometry.
 * The waveform snapshot owns immutable copied samples; identity is therefore sufficient here.
 * Raw Float bits keep even signed-zero inputs distinct without changing the source math.
 */
internal class ProgressiveWaveformContourCache {
    private val geometry = ProgressiveWaveformGeometry()
    private var data: ProgressiveWaveformData? = null
    private var coarseBits = 0
    private var detailBits = 0
    private var morphBits = 0
    private var phaseBits = 0
    private var widthBits = 0
    private var heightBits = 0
    var pointCount = 0
        private set

    fun prepare(detailSampleCount: Int) {
        clear()
        geometry.prepare(detailSampleCount)
    }

    fun clear() {
        data = null
        pointCount = 0
    }

    fun amplitudeAt(point: Int): Float = geometry.amplitudeAt(point)

    /** True only when the owning View must reconstruct its Path from these amplitudes. */
    fun update(
        data: ProgressiveWaveformData,
        visibleCoarseFraction: Float,
        visibleDetailFraction: Float,
        morphProgress: Float,
        phase: Float,
        width: Float,
        height: Float,
    ): Boolean {
        val coarse = visibleCoarseFraction.toRawBits()
        val detail = visibleDetailFraction.toRawBits()
        val morph = morphProgress.toRawBits()
        val phaseValue = phase.toRawBits()
        val widthValue = width.toRawBits()
        val heightValue = height.toRawBits()
        if (this.data === data && coarseBits == coarse && detailBits == detail &&
            morphBits == morph && phaseBits == phaseValue && widthBits == widthValue &&
            heightBits == heightValue) return false

        pointCount = geometry.update(data, visibleCoarseFraction, visibleDetailFraction,
            morphProgress, phase, width, height)
        this.data = data
        coarseBits = coarse
        detailBits = detail
        morphBits = morph
        phaseBits = phaseValue
        widthBits = widthValue
        heightBits = heightValue
        return true
    }
}
