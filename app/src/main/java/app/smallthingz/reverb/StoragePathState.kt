package app.smallthingz.reverb

import java.io.File
import java.io.IOException
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
