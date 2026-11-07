package app.smallthingz.reverb

/** Immutable identity sampled before the native read, never reconstructed from writer state. */
internal data class CaptureReadIdentity(
    val inputEpoch: Long,
    val continuityGeneration: Long,
    val commandGeneration: Long,
    val destinationCode: Byte,
    val sampleRate: Int,
    val channels: Int,
    val bytesPerSample: Int,
    val encodingCode: Int,
    val startedAtNanos: Long,
) {
    val frameBytes: Int = Math.multiplyExact(channels, bytesPerSample)
    init { require(sampleRate > 0 && channels > 0 && bytesPerSample > 0) }
}
