package app.smallthingz.reverb

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Truthful host metadata only for the currently owned Service-test root. */
@Implements(Os::class)
class ServiceStateOsShadow {
    companion object {
        @Volatile var fixtureRoot: Path? = null
        private val descriptorNumber = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }

        private fun owned(path: Path, operation: String): Path {
            val normalized = path.toAbsolutePath().normalize()
            val root = fixtureRoot ?: throw ErrnoException(operation, OsConstants.EACCES)
            if (!normalized.startsWith(root)) throw ErrnoException(operation, OsConstants.EACCES)
            return normalized
        }

        @JvmStatic
        @Implementation
        fun lstat(path: String): StructStat =
            hostUnixStat(owned(Path.of(path), "lstat"), noFollowLinks = true)

        @JvmStatic
        @Implementation
        fun fstat(descriptor: FileDescriptor): StructStat {
            val fd = descriptorNumber.getInt(descriptor)
            if (fd < 0) throw ErrnoException("fstat", OsConstants.EBADF)
            val proc = Path.of("/proc/self/fd/$fd")
            val target = try {
                Files.readSymbolicLink(proc)
            } catch (error: IOException) {
                val errno = when (error) {
                    is NoSuchFileException -> OsConstants.EBADF
                    is AccessDeniedException -> OsConstants.EACCES
                    else -> OsConstants.EIO
                }
                throw ErrnoException("fstat", errno).apply { initCause(error) }
            } catch (error: SecurityException) {
                throw ErrnoException("fstat", OsConstants.EACCES).apply { initCause(error) }
            }
            owned(target, "fstat")
            return hostUnixStat(proc, noFollowLinks = false)
        }
    }
}
