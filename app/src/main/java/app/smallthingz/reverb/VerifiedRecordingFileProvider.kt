package app.smallthingz.reverb

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

class VerifiedRecordingFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Recording provider is read-only")
        val expectedIdentity = verifiedFileProviderIdentity(uri)
            ?: throw FileNotFoundException("Recording identity is missing")
        val descriptor = super.openFile(uri, mode)
            ?: throw FileNotFoundException("Unable to open recording")
        val currentIdentity = resolveFileDescriptorIdentity(descriptor.fileDescriptor)
        if (!fileDescriptorIdentityMatches(expectedIdentity, currentIdentity)) {
            runCatching { descriptor.close() }
            throw FileNotFoundException("Recording changed before it was opened")
        }
        return descriptor
    }
}
