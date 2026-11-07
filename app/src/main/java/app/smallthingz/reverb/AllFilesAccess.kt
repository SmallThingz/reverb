package app.smallthingz.reverb

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.ChecksSdkIntAtLeast
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
internal fun supportsAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/** Special access is an app-op, not a runtime-permission callback or a persisted preference. */
internal fun hasAllFilesAccess(): Boolean = supportsAllFilesAccess() && Environment.isExternalStorageManager()

/** Some devices expose only the list of apps with special storage access. */
internal fun openAllFilesAccessReview(context: Context): Boolean {
    if (!supportsAllFilesAccess()) return false
    val intents = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null)),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the platform's general special-access page.
        } catch (_: SecurityException) {
            // An OEM can disallow the app-specific page while exposing the general page.
        }
    }
    return false
}

/** The narrower fallback remains independent of all-files access so Settings can show its real grant. */
internal fun recordingAudioReadPermission(): String? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> null
}

internal fun hasRecordingAudioReadPermission(context: Context): Boolean =
    recordingAudioReadPermission()?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true

internal fun openRecordingAudioAccessReview(context: Context): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
