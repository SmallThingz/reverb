package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/** Only a definitive unsupported rename operation permits the caller's owned-copy protocol. */
internal class NoReplaceMoveUnsupported(message: String) : IOException(message)

/** JNI sentinel proves pathname rejection preceded directory close, so no rename occurred. */
internal const val NO_REPLACE_MOVE_NAME_TOO_LONG = 65536
internal class NoReplaceMoveNameTooLong(source: String, destination: String) :
    FileSystemException(source, destination, "Recording name is too long for this filesystem")

internal fun noReplaceMoveFailure(
    status: Int,
    source: String,
    destination: String,
    describeErrno: (Int) -> String = { errno ->
        "${android.system.ErrnoException("renameat2(RENAME_NOREPLACE)", errno).message} (errno $errno)"
    },
): IOException {
    require(status != 0)
    if (status == NO_REPLACE_MOVE_NAME_TOO_LONG) return NoReplaceMoveNameTooLong(source, destination)
    val errno = if (status < 0) -status else status
    val reason = describeErrno(errno)
    return when {
        status < 0 -> NoReplaceMoveUnsupported("No-replace move unsupported for $source -> $destination: $reason")
        status == android.system.OsConstants.EEXIST -> FileAlreadyExistsException(source, destination, reason)
        status == android.system.OsConstants.ENOENT -> NoSuchFileException(source, destination, reason)
        else -> FileSystemException(source, destination, reason)
    }
}

/** Only the initial rename move owns this unchanged-source rejection, never rollback or copy. */
internal inline fun <T> attemptRecordingRenameMove(move: () -> T): T = try {
    move()
} catch (failure: NoReplaceMoveNameTooLong) {
    throw RecordingRenameDefiniteFailure("Recording name is too long for this filesystem", failure)
}

/** Atomic destination non-replacement; source identity and directory durability remain caller-owned. */
internal fun moveFileWithoutOverwrite(source: File, destination: File) {
    val from = source.absoluteFile
    val to = destination.absoluteFile
    val parent = from.parentFile
    if (parent == null || parent != to.parentFile || from.name in listOf(".", "..") ||
        to.name in listOf(".", "..")) {
        throw IOException("No-replace move requires same-parent file paths")
    }
    val status = NoReplaceMoveNative.move(
        noReplaceUtf8(parent.path), noReplaceUtf8(from.name), noReplaceUtf8(to.name))
    if (status == 0) return
    throw noReplaceMoveFailure(status, from.path, to.path)
}

private fun noReplaceUtf8(value: String): ByteArray {
    if ('\u0000' in value) throw IOException("NUL in no-replace path")
    try {
        val bytes = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    } catch (failure: CharacterCodingException) {
        throw IOException("Malformed Unicode in no-replace path", failure)
    }
}

private object NoReplaceMoveNative {
    private val loadFailure: Throwable? by lazy {
        try {
            System.loadLibrary("reverb_file_move")
            null
        } catch (failure: LinkageError) {
            failure
        } catch (failure: SecurityException) {
            failure
        }
    }

    fun move(parent: ByteArray, source: ByteArray, destination: ByteArray): Int {
        loadFailure?.let { throw IOException("Cannot load no-replace file primitive", it) }
        return try {
            renameNoReplace(parent, source, destination)
        } catch (failure: LinkageError) {
            throw IOException("Cannot invoke no-replace file primitive", failure)
        }
    }

    // Zero is success; 65536 is pre-close name rejection; other positive values are errno.
    // Negative errno is syscall-only unsupported.
    private external fun renameNoReplace(parent: ByteArray, source: ByteArray, destination: ByteArray): Int
}
