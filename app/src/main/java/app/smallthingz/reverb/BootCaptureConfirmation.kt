package app.smallthingz.reverb

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** One boot helper or reminder attempt owns this proof. Close revokes queued delivery. */
internal class BootCaptureConfirmation(
    private val afterNanos: Long,
    private var remainingPolicyOpenings: Int? = null,
    callback: () -> Unit,
) : Closeable {
    private val callback = AtomicReference<(() -> Unit)?>(callback)
    // The PCM writer owns policy proof and query admission. The worker only samples Android.
    private var policyInputEpoch = Long.MIN_VALUE
    private var policyQueryInFlight = false
    private var lastPolicyQueryAtNanos: Long? = null
    private var unsilencedBeforeReadAtNanos: Long? = null
    private var policyRecheckRequested = false
    var commandGeneration = Long.MIN_VALUE
        private set
    var continuityGeneration = Long.MIN_VALUE
        private set

    val active: Boolean get() = callback.get() != null

    fun arm(command: Long, continuity: Long) {
        commandGeneration = command
        continuityGeneration = continuity
    }

    fun accepts(command: Long, continuity: Long, readEndedAtNanos: Long, byteCount: Int): Boolean =
        active && command == commandGeneration && continuity == continuityGeneration &&
            readEndedAtNanos >= afterNanos && byteCount > 0

    fun requestPolicyRecheck() {
        if (remainingPolicyOpenings != null) policyRecheckRequested = true
    }

    fun checkMicrophonePolicy(
        inputEpoch: Long,
        readStartedAtNanos: Long,
        postToWriter: (() -> Unit) -> Boolean,
        sample: () -> Boolean?,
        confirmed: () -> Unit,
    ) {
        if (!active || policyQueryInFlight) return
        if (policyInputEpoch != inputEpoch) {
            policyInputEpoch = inputEpoch
            unsilencedBeforeReadAtNanos = null
        }
        val beforeRead = unsilencedBeforeReadAtNanos
        if (beforeRead == null && remainingPolicyOpenings == 0 && !policyRecheckRequested) return
        if (beforeRead != null && readStartedAtNanos - beforeRead < 0L) return
        val now = System.nanoTime()
        if (lastPolicyQueryAtNanos?.let { now - it < POLICY_RETRY_NANOS } == true) return

        lastPolicyQueryAtNanos = now
        if (beforeRead == null) {
            remainingPolicyOpenings = remainingPolicyOpenings?.let { (it - 1).coerceAtLeast(0) }
            policyRecheckRequested = false
        }
        policyQueryInFlight = true
        try {
            executeCaptureInputPolicyQuery {
                if (!active) return@executeCaptureInputPolicyQuery
                val unsilenced = sample() == false
                val observedAtNanos = System.nanoTime()
                if (!active) return@executeCaptureInputPolicyQuery
                if (!postToWriter {
                    policyQueryInFlight = false
                    if (!active) return@postToWriter
                    if (!unsilenced) unsilencedBeforeReadAtNanos = null
                    else if (beforeRead == null) unsilencedBeforeReadAtNanos = observedAtNanos
                    else confirmed()
                }) close()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            policyQueryInFlight = false
            unsilencedBeforeReadAtNanos = null
        }
    }

    fun deliver(current: Boolean) {
        val receiver = callback.getAndSet(null)
        if (current) receiver?.invoke()
    }

    override fun close() { callback.set(null) }

    private companion object { const val POLICY_RETRY_NANOS = 250_000_000L }
}
