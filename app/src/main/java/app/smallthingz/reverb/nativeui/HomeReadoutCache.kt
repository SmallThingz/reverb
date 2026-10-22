package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.CaptureBufferReadout
import app.smallthingz.reverb.RetentionMode
import app.smallthingz.reverb.captureBufferReadout

/** One body's released remember(readout inputs), independent of its changing flip transform. */
internal class HomeReadoutCache {
    private var cached: CaptureBufferReadout? = null
    private var mode: RetentionMode? = null
    private var disabled = false
    private var secondsBits = 0
    private var bytes = 0L
    private var disabledLabel = ""

    fun read(
        mode: RetentionMode?,
        disabled: Boolean,
        seconds: Float,
        bytes: Long,
        disabledLabel: String,
    ): CaptureBufferReadout {
        val bits = seconds.toRawBits()
        val previous = cached
        if (previous != null && this.mode == mode && this.disabled == disabled &&
            secondsBits == bits && this.bytes == bytes && this.disabledLabel == disabledLabel
        ) return previous
        val next = captureBufferReadout(mode, disabled, seconds, bytes, disabledLabel)
        this.mode = mode
        this.disabled = disabled
        secondsBits = bits
        this.bytes = bytes
        this.disabledLabel = disabledLabel
        cached = next
        return next
    }

    /** The window replaces its environment after configuration/theme changes. */
    fun invalidate() { cached = null }
}
