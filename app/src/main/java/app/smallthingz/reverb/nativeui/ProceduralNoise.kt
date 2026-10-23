package app.smallthingz.reverb.nativeui

internal const val NOISE_TILE_SIZE = smallthingz.ui.graphics.NOISE_TILE_SIZE

// Retained for the existing common-grain qualification oracle; the implementation is library-owned.
internal fun generateNoisePixels(): IntArray = smallthingz.ui.graphics.generateNoisePixels()
