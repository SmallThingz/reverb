package app.smallthingz.reverb

/** Semantic proofs from a complete FLAC decode, addressed by the verified complete bytes.
 * Callers must separately prove the current descriptor revision has this whole-file digest.
 * Catalog size, timestamps, STREAMINFO and PCM MD5 alone never grant reuse authority.
 */
internal object VerifiedFlacMetadataCache {
    private const val MAX_ENTRIES = 128
    private val entries = LinkedHashMap<String, FlacInfo>(16, 0.75f, true)

    private fun key(digest: CopyDigest): String? {
        if (digest.byteCount <= 0L || digest.sha256.size != 32) return null
        return digest.byteCount.toString() + ":" + digest.sha256.toHexString()
    }

    fun remember(digest: CopyDigest, info: FlacInfo) {
        val key = key(digest) ?: return
        synchronized(entries) {
            entries[key] = info.copy(md5 = info.md5.clone())
            if (entries.size > MAX_ENTRIES) {
                val oldest = entries.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    fun get(digest: CopyDigest): FlacInfo? {
        val key = key(digest) ?: return null
        return synchronized(entries) { entries[key]?.let { it.copy(md5 = it.md5.clone()) } }
    }
}
