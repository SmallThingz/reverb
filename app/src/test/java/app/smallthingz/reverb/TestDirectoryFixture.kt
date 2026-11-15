package app.smallthingz.reverb

import java.io.File
import java.nio.file.Files

internal inline fun withTestDirectory(parentPath: String, prefix: String, block: (File) -> Unit) {
    val parent = File(parentPath).apply { mkdirs() }
    val root = Files.createTempDirectory(parent.toPath(), prefix).toFile()
    try { block(root) } finally { root.deleteRecursively() }
}
