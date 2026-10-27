package app.smallthingz.reverb

/** A confirmation belongs to one unchanged draft and one visible Settings lifetime. */
internal class SettingsBackConfirmation {
    private var armedAt: Long? = null
    private var revision = 0L

    fun confirm(nowMillis: Long, editRevision: Long): Boolean {
        val previous = armedAt
        if (previous != null && revision == editRevision &&
            nowMillis >= previous && nowMillis - previous <= WINDOW_MILLIS) {
            reset()
            return true
        }
        armedAt = nowMillis
        revision = editRevision
        return false
    }

    fun reset() { armedAt = null }

    private companion object { const val WINDOW_MILLIS = 2_000L }
}
