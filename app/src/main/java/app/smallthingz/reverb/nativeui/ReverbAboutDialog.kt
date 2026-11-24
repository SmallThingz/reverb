package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.core.net.toUri
import app.smallthingz.reverb.R
import smallthingz.ui.reverb.ReverbAboutDetails
import smallthingz.ui.reverb.ReverbAboutDialog as LibraryAboutDialog
import smallthingz.ui.reverb.ReverbTheme

/** Supplies application identity, localized labels and link behavior to the library brand panel. */
internal class ReverbAboutDialog(
    anchor: View,
    theme: ReverbTheme,
    restoredScrollPosition: Int,
    onDismiss: () -> Unit,
) {
    constructor(anchor: View, theme: ReverbTheme, onDismiss: () -> Unit) : this(anchor, theme, 0, onDismiss)

    private val context = anchor.context
    private val versionName = applicationVersion(context)
    private val dialog = LibraryAboutDialog(anchor, theme, details(), restoredScrollPosition, ::openRepository, onDismiss)

    private fun details() = ReverbAboutDetails(context.getString(R.string.app_name),
        context.getString(R.string.about_version, versionName), context.getString(R.string.github_repo),
        "SmallThingz/reverb", context.getString(R.string.no_app_available))

    private fun openRepository(): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/SmallThingz/reverb".toUri()))
        true
    } catch (_: RuntimeException) {
        false
    }

    val window get() = dialog.window
    fun scrollPosition() = dialog.scrollPosition()
    fun updateAppearance(theme: ReverbTheme, onDismiss: () -> Unit) = dialog.bind(theme, details(), onDismiss)
    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
    fun requestDismiss() = dialog.requestDismiss()
}

private fun applicationVersion(context: Context): String {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return info.versionName.orEmpty()
}
