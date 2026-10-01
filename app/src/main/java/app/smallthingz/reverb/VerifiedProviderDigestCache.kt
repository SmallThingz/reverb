package app.smallthingz.reverb

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.util.LinkedHashMap

internal data class ProviderPayloadRevision(
    val device: Long,
    val inode: Long,
    val sizeBytes: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
)

internal fun nativeProviderPayloadRevision(descriptor: FileDescriptor): ProviderPayloadRevision? = runCatching {
    val stat = Os.fstat(descriptor)
    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino == 0L || stat.st_size < 0L) return@runCatching null
    ProviderPayloadRevision(stat.st_dev, stat.st_ino, stat.st_size, stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec)
}.getOrNull()

/** Optimization for the redundant pre-publication recheck only, never destructive cleanup or final publication proof. */
internal object VerifiedProviderDigestCache {
    private data class Key(
        val storage: RecordingStorageType,
        val id: String,
        val identity: String,
        val revision: ProviderPayloadRevision,
    )
    private val entries = LinkedHashMap<Key, CopyDigest>(16, 0.75f, true)
    private const val MAX_ENTRIES = 128

    @Synchronized
    fun remember(storage: RecordingStorageType, id: String, fingerprint: StableOutputFingerprint, revision: ProviderPayloadRevision?) {
        val native = revision ?: return
        val identity = fingerprint.providerIdentity?.takeIf { it.isNotBlank() } ?: return
        if (storage == RecordingStorageType.FILE || fingerprint.digest.byteCount != native.sizeBytes ||
            fingerprint.digest.sha256.size != 32) return
        entries[Key(storage, id, identity, native)] = CopyDigest(fingerprint.digest.byteCount, fingerprint.digest.sha256.clone())
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun read(storage: RecordingStorageType, id: String, identity: String, revision: ProviderPayloadRevision?): CopyDigest? {
        val native = revision ?: return null
        val digest = entries[Key(storage, id, identity, native)] ?: return null
        return CopyDigest(digest.byteCount, digest.sha256.clone())
    }
}
