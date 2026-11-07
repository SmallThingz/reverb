package app.smallthingz.reverb

import android.app.AppOpsManager

/** Foreground/default modes are not denial; recorder policy resolves concurrent capture silencing. */
internal fun calibrationMicrophoneBlocked(permissionGranted: Boolean, appOpMode: Int?, clientSilenced: Boolean): Boolean =
    !permissionGranted || clientSilenced || appOpMode == AppOpsManager.MODE_IGNORED ||
        appOpMode == AppOpsManager.MODE_ERRORED
