package app.smallthingz.reverb

/**
 * Coalesces one continuously failing background state read without hiding a new failure or recovery.
 * Confined to the recorder's audio thread; explicit user-action failures do not pass through this gate.
 */
internal class RecorderStateReadErrorGate {
    private data class CauseSignature(val type: String, val message: String?)
    private var lastFailure: List<CauseSignature>? = null

    fun shouldReportFailure(error: Throwable): Boolean {
        // Bound malformed/cyclic cause chains and retain only their descriptive values, not stack traces.
        val previous = lastFailure
        var replacement: ArrayList<CauseSignature>? = null
        var cause: Throwable? = error
        var index = 0
        while (cause != null && index < 8) {
            val type = cause.javaClass.name
            val message = cause.message
            val old = previous?.getOrNull(index)
            if (replacement == null && (old == null || old.type != type || old.message != message)) {
                replacement = ArrayList(8)
                if (previous != null) {
                    for (prefix in 0 until index) replacement.add(previous[prefix])
                }
            }
            replacement?.add(CauseSignature(type, message))
            index++
            if (index < 8) cause = cause.cause
        }
        if (replacement == null) {
            if (previous != null && previous.size == index) return false
            // A shorter chain is a new failure even when every retained prefix still matches.
            replacement = ArrayList(index)
            if (previous != null) {
                for (prefix in 0 until index) replacement.add(previous[prefix])
            }
        }
        lastFailure = replacement
        return true
    }

    fun readSucceeded() {
        lastFailure = null
    }
}
