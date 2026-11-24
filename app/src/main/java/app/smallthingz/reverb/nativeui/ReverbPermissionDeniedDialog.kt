package app.smallthingz.reverb.nativeui

import android.view.View
import app.smallthingz.reverb.R
import smallthingz.ui.reverb.ReverbPermissionDeniedSheet

internal fun requiredPermissionMessageResource(microphoneAllowed: Boolean, legacyStorageAllowed: Boolean): Int =
    if (microphoneAllowed && !legacyStorageAllowed) R.string.storage_permission_required_message
    else R.string.permission_required_message

internal class ReverbPermissionDeniedDialog(
    anchor: View,
    environment: ReverbViewEnvironment,
    microphoneAllowed: Boolean,
    legacyStorageAllowed: Boolean,
    onDismiss: () -> Unit,
    onOpenApplicationSettings: () -> Unit,
) : AutoCloseable {
    private val context = anchor.context
    private val sheet = ReverbPermissionDeniedSheet(anchor, environment.theme,
        context.getString(R.string.permission_required),
        context.getString(requiredPermissionMessageResource(microphoneAllowed, legacyStorageAllowed)),
        context.getString(R.string.close), context.getString(R.string.allow), onDismiss, onOpenApplicationSettings)

    fun bind(microphoneAllowed: Boolean, legacyStorageAllowed: Boolean, onDismiss: () -> Unit,
             onOpenApplicationSettings: () -> Unit) = sheet.bind(
        context.getString(R.string.permission_required),
        context.getString(requiredPermissionMessageResource(microphoneAllowed, legacyStorageAllowed)),
        context.getString(R.string.close), context.getString(R.string.allow), onDismiss, onOpenApplicationSettings,
    )

    fun bindEnvironment(environment: ReverbViewEnvironment) = sheet.bindEnvironment(environment.theme)
    fun show() = sheet.show()
    override fun close() = sheet.close()
}
