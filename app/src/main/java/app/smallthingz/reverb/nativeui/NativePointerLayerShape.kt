package app.smallthingz.reverb.nativeui

/** Read-only actual layer geometry for a concrete View participating in shared phase hit testing. */
internal interface NativePointerLayerShape {
    fun containsPointerLayer(x: Float, y: Float): Boolean
}
