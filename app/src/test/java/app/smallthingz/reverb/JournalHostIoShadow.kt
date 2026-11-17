package app.smallthingz.reverb

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Real host file bytes/metadata/barriers restricted to the isolated fixture root.
 * dup is a metadata-only /proc descriptor reopen: same inode and retained lifetime,
 * not shared file offset/access semantics and not Android native durability proof.
 * Owned regular reads use real FileChannel position/bytes and Android zero-at-EOF.
 * Directory fd reflection requires --add-opens=java.base/sun.nio.ch=ALL-UNNAMED.
 */
@Implements(Os::class)
class JournalHostIoShadow {
    companion object {
        private data class Owner(
            val closeable: Closeable,
            val directory: FileChannel? = null,
            val readable: FileChannel? = null,
        )
        private val owners = IdentityHashMap<FileDescriptor, Owner>()
        private val fdNumber = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }
        var failDirectorySyncs: Int = 0
        var directorySyncAttempts: Int = 0
        val outstandingOwners: Int get() = synchronized(owners) { owners.size }

        private fun owned(path: Path): Path {
            val normalized = path.toAbsolutePath().normalize()
            check(normalized.startsWith(checkNotNull(ServiceStateOsShadow.fixtureRoot)))
            return normalized
        }

        @JvmStatic @Implementation fun lstat(path: String): StructStat = ServiceStateOsShadow.lstat(path)
        @JvmStatic @Implementation fun fstat(descriptor: FileDescriptor): StructStat = ServiceStateOsShadow.fstat(descriptor)

        @JvmStatic @Implementation fun dup(source: FileDescriptor): FileDescriptor {
            val before = fstat(source)
            val stream = FileInputStream("/proc/self/fd/${fdNumber.getInt(source)}")
            try {
                val retained = fstat(stream.fd)
                check(before.st_dev == retained.st_dev && before.st_ino == retained.st_ino)
                synchronized(owners) { check(owners.put(stream.fd, Owner(stream)) == null) }
                return stream.fd
            } catch (error: Throwable) {
                try { stream.close() } catch (close: Throwable) { if (close !== error) error.addSuppressed(close) }
                throw error
            }
        }

        @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            check(mode == 0)
            check(flags == OsConstants.O_RDONLY ||
                flags == (OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC))
            val target = owned(Path.of(path))
            val before = lstat(target.toString())
            val isDirectory = OsConstants.S_ISDIR(before.st_mode)
            check(isDirectory || OsConstants.S_ISREG(before.st_mode))
            if (!isDirectory) {
                check(flags == (OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC))
            }
            val channel = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            try {
                val fd = channel.javaClass.getDeclaredField("fd").apply { isAccessible = true }.get(channel) as FileDescriptor
                val stat = fstat(fd)
                val after = lstat(target.toString())
                check(before.st_dev == stat.st_dev && before.st_ino == stat.st_ino)
                check(after.st_dev == stat.st_dev && after.st_ino == stat.st_ino)
                check(if (isDirectory) OsConstants.S_ISDIR(stat.st_mode) && OsConstants.S_ISDIR(after.st_mode)
                    else OsConstants.S_ISREG(stat.st_mode) && OsConstants.S_ISREG(after.st_mode))
                val owner = if (isDirectory) Owner(channel, directory = channel)
                    else Owner(channel, readable = channel)
                synchronized(owners) { check(owners.put(fd, owner) == null) }
                return fd
            } catch (error: Throwable) {
                try { channel.close() } catch (close: Throwable) { if (close !== error) error.addSuppressed(close) }
                throw error
            }
        }

        @JvmStatic @Implementation fun read(fd: FileDescriptor, buffer: ByteArray, offset: Int, byteCount: Int): Int {
            val owner = synchronized(owners) { owners[fd] }
                ?: throw ErrnoException("read", OsConstants.EBADF)
            val channel = owner.readable ?: throw ErrnoException("read",
                if (owner.directory != null) OsConstants.EISDIR else OsConstants.EBADF)
            // ByteBuffer validates the caller's range and preserves bytes outside that slice.
            // Android Os.read reports zero at EOF, while FileChannel reports minus one.
            return channel.read(ByteBuffer.wrap(buffer, offset, byteCount)).coerceAtLeast(0)
        }

        @JvmStatic @Implementation fun fsync(fd: FileDescriptor) {
            val owner = synchronized(owners) { owners[fd] }
            val directory = owner?.directory
            if (directory != null) {
                directorySyncAttempts++
                if (failDirectorySyncs > 0) {
                    failDirectorySyncs--
                    throw ErrnoException("fsync", OsConstants.EIO)
                }
                directory.force(true)
            } else {
                fstat(fd)
                fd.sync()
            }
        }

        @JvmStatic @Implementation fun close(fd: FileDescriptor) {
            val owner = synchronized(owners) { owners.remove(fd) }
                ?: throw ErrnoException("close", OsConstants.EBADF)
            owner.closeable.close()
        }

        fun reset() {
            check(outstandingOwners == 0) { "Journal descriptor owner leaked" }
            failDirectorySyncs = 0
            directorySyncAttempts = 0
        }

        fun finishFixture(): Throwable? {
            val leaked = synchronized(owners) { owners.values.toList().also { owners.clear() } }
            var failure: Throwable? = if (leaked.isEmpty()) null
                else AssertionError("Journal descriptor owner leaked: ${leaked.size}")
            for (owner in leaked) {
                try { owner.closeable.close() }
                catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
            }
            failDirectorySyncs = 0
            directorySyncAttempts = 0
            return failure
        }
    }
}
