package app.smallthingz.reverb

/** Service lifetime authority, independent of rows, windows, and native callback order. */
internal class RecordingPlaybackOwnership(private val nextGeneration: (() -> Long)? = null) {
    var generation = 0L
        private set
    var transportIntentRevision = 0L
        private set
    var active = false
        private set
    var wantsPlayback = false
        private set
    val seeks = InlinePlaybackSeekQueue()
    val idleRetirementAllowed: Boolean get() = active && !wantsPlayback

    fun replace(): Long {
        generation = nextGeneration?.invoke() ?: (generation + 1)
        active = true
        wantsPlayback = true
        seeks.cancel()
        return generation
    }
    fun current(token: Long): Boolean = active && generation == token
    fun resume() { if (active) wantsPlayback = true }
    fun currentGesture(token: Long, intentToken: Long): Boolean = current(token) && transportIntentRevision == intentToken
    fun suspendForGesture() {
        wantsPlayback = false
        seeks.resume = false
    }
    fun beginExternalCommand(): Long = ++transportIntentRevision
    fun pause() {
        beginExternalCommand()
        suspendForGesture()
    }
    fun retire() {
        pause()
        active = false
        seeks.cancel()
    }
}
