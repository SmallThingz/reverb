package app.smallthingz.reverb

import java.io.File
import java.nio.file.Files

/** Single-writer host fixture only; actual no-replace semantics belong to the Android JNI fixture. */
internal fun moveOwnedTestFile(source: File, destination: File) {
    Files.move(source.toPath(), destination.toPath())
}
