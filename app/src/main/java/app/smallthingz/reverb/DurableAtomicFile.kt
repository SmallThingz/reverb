package app.smallthingz.reverb

import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

// The retained descriptor pins the written object even after AtomicFile closes its stream.
// Android 9 can write the base beside .bak; newer releases write .new beside the old base.
internal class AtomicWritePublicationProof(
    private val baseFile: File,
    private val matchesDescriptor: (File) -> Boolean,
    private val closeDescriptor: () -> Unit,
) : Closeable {
    init {
        if (atomicFileRegularBackingState(baseFile) != StoragePathState.PRESENT ||
            listOf(baseFile, File(baseFile.path + ".new")).count { candidate ->
                observeStoragePath(candidate).let {
                    it.state == StoragePathState.PRESENT && it.isRegularFile
                } && matchesDescriptor(candidate)
            } != 1
        ) {
            throw IOException("Unable to bind AtomicFile output: ${baseFile.path}")
        }
    }

    fun requirePublished() {
        val base = observeStoragePath(baseFile)
        if (base.state != StoragePathState.PRESENT || !base.isRegularFile ||
            storagePathState(File(baseFile.path + ".new")) != StoragePathState.MISSING ||
            storagePathState(File(baseFile.path + ".bak")) != StoragePathState.MISSING ||
            !matchesDescriptor(baseFile)
        ) {
            throw IOException("AtomicFile did not publish its written object: ${baseFile.path}")
        }
    }

    override fun close() = closeDescriptor()
}

private fun openAtomicWritePublicationProof(
    baseFile: File,
    outputDescriptor: FileDescriptor,
): AtomicWritePublicationProof {
    val retainedDescriptor = Os.dup(outputDescriptor)
    return try {
        AtomicWritePublicationProof(
            baseFile = baseFile,
            matchesDescriptor = { path ->
                // Re-sample ctime after rename. The duplicate keeps the inode alive throughout
                // publication and the directory barrier; a pre-rename identity would be stale.
                fileDescriptorIdentityMatches(
                    resolveFileIdentity(path),
                    resolveFileDescriptorIdentity(retainedDescriptor),
                )
            },
            closeDescriptor = { Os.close(retainedDescriptor) },
        )
    } catch (error: Throwable) {
        throwAfterClosePreservingPrimary(error) { Os.close(retainedDescriptor) }
    }
}

internal fun finishDurableAtomicWrite(
    baseFile: File,
    output: FileOutputStream,
    finishWrite: (FileOutputStream) -> Unit,
    syncDirectory: () -> Boolean,
    onPublicationStarted: () -> Unit,
    syncPayload: (FileOutputStream) -> Unit = { it.fd.sync() },
    openProof: (File, FileDescriptor) -> AtomicWritePublicationProof = ::openAtomicWritePublicationProof,
) {
    // AtomicFile.finishWrite only logs failed fsync/close/rename operations. Its return is not
    // a durability acknowledgement. Callers have already flushed their buffered payload.
    syncPayload(output)
    openProof(baseFile, output.fd).use { proof ->
        // Once finish begins it may have published even if it throws. Callers must retain all
        // uncertain artifacts from this boundary onward, rather than invoking failWrite.
        onPublicationStarted()
        withOwnedResource(output, release = { it.close() }) {
            finishWrite(it)
            proof.requirePublished()
            if (!syncDirectory()) {
                throw IOException("Unable to persist AtomicFile publication: ${baseFile.path}")
            }
            proof.requirePublished()
        }
    }
}
