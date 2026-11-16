package app.smallthingz.reverb

import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructStat
import android.system.StructTimespec
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Host metadata encoder only; each Android shadow owns its path/descriptor admission policy. */
internal fun hostUnixStat(path: Path, noFollowLinks: Boolean): StructStat = try {
    val options = if (noFollowLinks) arrayOf(LinkOption.NOFOLLOW_LINKS) else emptyArray()
    val row = Files.readAttributes(path,
        "unix:dev,ino,mode,nlink,uid,gid,size,lastAccessTime,lastModifiedTime,ctime", *options)
    fun number(key: String) = (row[key] as Number).toLong()
    fun time(key: String): StructTimespec = (row[key] as FileTime).toInstant()
        .let { StructTimespec(it.epochSecond, it.nano.toLong()) }
    val bytes = number("size")
    StructStat(number("dev"), number("ino"), number("mode").toInt(), number("nlink"),
        number("uid").toInt(), number("gid").toInt(), 0L, bytes,
        time("lastAccessTime"), time("lastModifiedTime"), time("ctime"), 4096L, (bytes + 511L) / 512L)
} catch (error: IOException) {
    val errno = when (error) {
        is NoSuchFileException -> OsConstants.ENOENT
        is AccessDeniedException -> OsConstants.EACCES
        is NotDirectoryException -> OsConstants.ENOTDIR
        else -> OsConstants.EIO
    }
    throw ErrnoException("stat", errno).apply { initCause(error) }
} catch (error: SecurityException) {
    throw ErrnoException("stat", OsConstants.EACCES).apply { initCause(error) }
}
