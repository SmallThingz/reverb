package app.smallthingz.reverb

/** Process-only requests never restore destructive authority after process death. */
internal class RecordingDeletionConsentState<T>(private val token: (T) -> String) {
    private enum class Phase { PREPARING, CREATING, READY, AWAITING_RESULT, RETRYING }
    private val queued = linkedMapOf<String, T>()
    private var active: String? = null
    private var phase: Phase? = null
    private var prompt: String? = null

    @Synchronized fun offer(value: T) { queued.putIfAbsent(token(value), value) }

    /** A queued request owns no factory, prompt or retry yet. */
    @Synchronized fun removeQueued(expectedToken: String): T? =
        if (active == expectedToken) null else queued.remove(expectedToken)

    @Synchronized fun claim(): T? {
        if (active != null) return null
        return queued.values.firstOrNull()?.also { active = token(it); phase = Phase.PREPARING }
    }

    /** Only the active batch may call this factory; queued API30 batches have no sender yet. */
    fun <R : Any> createRequest(batchToken: String, factory: () -> R): R? {
        synchronized(this) {
            if (active != batchToken || phase != Phase.PREPARING) return null
            phase = Phase.CREATING
        }
        return factory()
    }

    @Synchronized fun publishPrompt(batchToken: String, promptToken: String): Boolean {
        if (active != batchToken || phase != Phase.CREATING) return false
        prompt = promptToken
        phase = Phase.READY
        return true
    }

    @Synchronized fun claimPrompt(): String? {
        if (phase != Phase.READY) return null
        phase = Phase.AWAITING_RESULT
        return prompt
    }

    @Synchronized fun finish(expectedToken: String): T? {
        if (phase != Phase.AWAITING_RESULT || prompt != expectedToken) return null
        phase = Phase.RETRYING
        return active?.let { queued[it] }
    }

    @Synchronized fun continueBatch(batchToken: String): Boolean {
        if (active != batchToken || (phase != Phase.RETRYING && phase != Phase.CREATING)) return false
        prompt = null
        phase = Phase.PREPARING
        return true
    }

    @Synchronized fun complete(batchToken: String): T? {
        if (active != batchToken) return null
        active = null
        prompt = null
        phase = null
        return queued.remove(batchToken)
    }

    @Synchronized fun contains(expectedToken: String): Boolean =
        phase == Phase.AWAITING_RESULT && prompt == expectedToken
}
