package app.smallthingz.reverb

import java.io.File
import java.io.FileInputStream

/** Process-local full-byte proofs, never reconstructed from catalog or journal metadata. */
internal object VerifiedFileDigestCache {
    private const val MAX_ENTRIES = 128
    private val entries = LinkedHashMap<String, StableOutputFingerprint>(16, 0.75f, true)

    fun remember(file: File, fingerprint: StableOutputFingerprint) {
        val identity = fingerprint.fileKey ?: return
        if (!deletionClaimIdentityHasPinnedDescriptorAuthority(identity)) return
        val owned = fingerprint.copy(digest = fingerprint.digest.copy(sha256 = fingerprint.digest.sha256.clone()))
        synchronized(entries) {
            entries[file.absolutePath] = owned
            if (entries.size > MAX_ENTRIES) {
                val first = entries.entries.iterator()
                first.next(); first.remove()
            }
        }
    }

    fun current(file: File): StableOutputFingerprint? {
        val key = file.absolutePath
        val expected = synchronized(entries) { entries[key] } ?: return null
        val identity = requireNotNull(expected.fileKey)
        val current = runCatching {
            val before = resolveFileIdentity(file)
            if (!fileIdentityMatches(identity, before)) return@runCatching false
            FileInputStream(file).use { input ->
                val descriptor = resolveFileDescriptorIdentity(input.fd)
                verifiedFileReadHandoffMatchesExpected(identity, before, descriptor, resolveFileIdentity(file)) &&
                    input.channel.size() == expected.digest.byteCount &&
                    fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(input.fd)) &&
                    fileIdentityMatches(identity, resolveFileIdentity(file))
            }
        }.getOrDefault(false)
        if (!current) {
            synchronized(entries) { if (entries[key] === expected) entries.remove(key) }
            return null
        }
        return expected.copy(digest = expected.digest.copy(sha256 = expected.digest.sha256.clone()))
    }
}

internal fun readCurrentVerifiedFileFingerprint(file: File): StableOutputFingerprint? =
    VerifiedFileDigestCache.current(file) ?: readStableFileOutputFingerprint(file)
