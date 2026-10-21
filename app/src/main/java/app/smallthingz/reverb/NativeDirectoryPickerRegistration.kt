package app.smallthingz.reverb

import android.net.Uri

/** The existing Activity registry owns this exact Settings launcher lifetime and saved UUID key. */
internal class NativeDirectoryPickerRegistration(
    val launch: (Uri?) -> Unit,
    private val unregister: () -> Unit,
) : AutoCloseable {
    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        unregister()
    }
}
