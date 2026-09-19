package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal enum class StoragePathState {
    PRESENT,
    MISSING,
    UNAVAILABLE,
}

internal data class StoragePathObservation(
    val state: StoragePathState,
    val isRegularFile: Boolean = false,
)

internal fun observeStoragePath(file: File): StoragePathObservation = try {
    val attributes = Files.readAttributes(
        file.toPath(),
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
    StoragePathObservation(
        state = StoragePathState.PRESENT,
        isRegularFile = attributes.isRegularFile,
    )
} catch (_: NoSuchFileException) {
    StoragePathObservation(StoragePathState.MISSING)
} catch (_: IOException) {
    StoragePathObservation(StoragePathState.UNAVAILABLE)
} catch (_: SecurityException) {
    StoragePathObservation(StoragePathState.UNAVAILABLE)
}

internal fun storagePathState(file: File): StoragePathState = observeStoragePath(file).state

internal fun storageDirectoryState(directory: File): StoragePathState = try {
    val attributes = Files.readAttributes(
        directory.toPath(),
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
    if (attributes.isDirectory) StoragePathState.PRESENT else StoragePathState.UNAVAILABLE
} catch (_: NoSuchFileException) {
    StoragePathState.MISSING
} catch (_: IOException) {
    StoragePathState.UNAVAILABLE
} catch (_: SecurityException) {
    StoragePathState.UNAVAILABLE
}

@Throws(IOException::class)
internal fun ensureDirectoryEntryNoFollow(directory: File): Boolean {
    when (storageDirectoryState(directory)) {
        StoragePathState.PRESENT -> return false
        StoragePathState.UNAVAILABLE -> throw IOException(
            "Directory path is not a trustworthy directory entry: ${directory.absolutePath}",
        )
        StoragePathState.MISSING -> Unit
    }

    val parent = directory.parentFile ?: throw IOException(
        "Directory path has no parent: ${directory.absolutePath}",
    )
    when (storageDirectoryState(parent)) {
        StoragePathState.PRESENT -> Unit
        StoragePathState.UNAVAILABLE -> throw IOException(
            "Directory parent is not trustworthy: ${parent.absolutePath}",
        )
        StoragePathState.MISSING -> {
            if (!parent.mkdirs() && storageDirectoryState(parent) != StoragePathState.PRESENT) {
                throw IOException("Unable to create directory parent: ${parent.absolutePath}")
            }
            if (storageDirectoryState(parent) != StoragePathState.PRESENT) {
                throw IOException("Created directory parent is not trustworthy: ${parent.absolutePath}")
            }
        }
    }

    try {
        Files.createDirectory(directory.toPath())
    } catch (_: FileAlreadyExistsException) {
        if (storageDirectoryState(directory) == StoragePathState.PRESENT) return false
        throw IOException("Directory path became unsafe while creating it: ${directory.absolutePath}")
    }
    if (storageDirectoryState(directory) != StoragePathState.PRESENT) {
        throw IOException("Created directory is not trustworthy: ${directory.absolutePath}")
    }
    return true
}

internal fun storagePathMayContainData(state: StoragePathState): Boolean =
    state != StoragePathState.MISSING

internal fun atomicFileBackingState(
    baseFile: File,
    observe: (File) -> StoragePathState = ::storagePathState,
): StoragePathState {
    var unavailable = false
    for (candidate in listOf(baseFile, File(baseFile.path + ".bak"), File(baseFile.path + ".new"))) {
        when (observe(candidate)) {
            StoragePathState.PRESENT -> return StoragePathState.PRESENT
            StoragePathState.UNAVAILABLE -> unavailable = true
            StoragePathState.MISSING -> Unit
        }
    }
    return if (unavailable) StoragePathState.UNAVAILABLE else StoragePathState.MISSING
}

internal fun atomicFileRegularBackingState(baseFile: File): StoragePathState {
    var present = false
    for (candidate in listOf(baseFile, File(baseFile.path + ".bak"), File(baseFile.path + ".new"))) {
        val observation = observeStoragePath(candidate)
        when (observation.state) {
            StoragePathState.MISSING -> Unit
            StoragePathState.UNAVAILABLE -> return StoragePathState.UNAVAILABLE
            StoragePathState.PRESENT -> {
                if (!observation.isRegularFile) return StoragePathState.UNAVAILABLE
                present = true
            }
        }
    }
    return if (present) StoragePathState.PRESENT else StoragePathState.MISSING
}

internal fun bindAtomicFileReadDescriptor(
    baseFile: File,
    descriptor: java.io.FileDescriptor,
): String? {
    if (atomicFileRegularBackingState(baseFile) != StoragePathState.PRESENT) return null
    val pathIdentity = resolveFileIdentity(baseFile).takeIf { it.isNotBlank() } ?: return null
    val descriptorIdentity = resolveFileDescriptorIdentity(descriptor).takeIf { it.isNotBlank() } ?: return null
    return descriptorIdentity.takeIf { fileDescriptorIdentityMatches(pathIdentity, descriptorIdentity) }
}

internal fun atomicFileReadDescriptorRemainsCurrent(
    baseFile: File,
    descriptorIdentity: String,
): Boolean {
    if (descriptorIdentity.isBlank() ||
        atomicFileRegularBackingState(baseFile) != StoragePathState.PRESENT
    ) {
        return false
    }
    val pathIdentity = resolveFileIdentity(baseFile).takeIf { it.isNotBlank() } ?: return false
    return fileDescriptorIdentityMatches(pathIdentity, descriptorIdentity)
}
