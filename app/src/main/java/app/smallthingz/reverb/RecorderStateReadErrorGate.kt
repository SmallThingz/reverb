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
        val signature = generateSequence(error) { it.cause }
            .take(8)
            .map { CauseSignature(it.javaClass.name, it.message) }
            .toList()
        if (signature == lastFailure) return false
        lastFailure = signature
        return true
    }

    fun readSucceeded() {
        lastFailure = null
    }
}
