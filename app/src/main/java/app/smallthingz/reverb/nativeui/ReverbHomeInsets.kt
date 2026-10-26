package app.smallthingz.reverb.nativeui

/**
 * Resolved physical Scaffold content insets. The window owns system-inset/consumption policy;
 * this screen does not read, change or guess system bars, IME visibility or accessibility settings.
 */
internal data class ReverbHomeInsets(
    val statusTop: Int,
    val contentLeft: Int,
    val contentRight: Int,
    val contentBottom: Int,
)
