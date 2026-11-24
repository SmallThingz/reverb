package app.smallthingz.reverb.nativeui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import app.smallthingz.reverb.requiresLegacyPublicStoragePermission
import smallthingz.ui.reverb.home.ReverbPermissionRequiredView

internal class NativePermissionRequiredView(
    context: Context,
    host: NativeMaterialButtonHost,
    onReviewPermission: () -> Unit,
    onLibrary: () -> Unit,
) : ReverbPermissionRequiredView(context, host, host.environment, onReviewPermission, onLibrary) {
    fun bind(permissionsGranted: Boolean, showPermissionDenied: Boolean) {
        val show = !permissionsGranted && !showPermissionDenied
        bindPermissions(show,
            show && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            show && (!requiresLegacyPublicStoragePermission() ||
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED))
    }
}
