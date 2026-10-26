package app.smallthingz.reverb

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publishes one QA receipt in its owned directory. A failed write leaves the previous receipt intact
 * and retains the uncommitted `.pending` bytes for diagnosis. Callers serialize writes per target.
 * Atomic-move failure propagates; there is deliberately no truncate/copy fallback or post-commit rollback.
 */
internal fun writeQaEvidenceAtomically(target: File, write: (OutputStream) -> Unit) {
    val pending = File(target.parentFile, target.name + ".pending")
    FileOutputStream(pending).use { output ->
        write(output)
        output.fd.sync()
    }
    Files.move(pending.toPath(), target.toPath(),
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
