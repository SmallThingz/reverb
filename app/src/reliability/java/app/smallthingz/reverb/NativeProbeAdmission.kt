package app.smallthingz.reverb

/** Only these offscreen native probes use this guard; other reliability routes retain their rules. */
internal fun requireNativeMathProbeHost(packageName: String, plainApplication: Boolean) {
    check(packageName == "app.smallthingz.reverb.nativeparity.reliability" && plainApplication) {
        "Native text backend and motion probes require the isolated UI-only APK with a plain Application"
    }
}
