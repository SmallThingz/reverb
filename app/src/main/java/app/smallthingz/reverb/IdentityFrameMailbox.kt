package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicReference

/** Main-thread owner transitions/delivery with concurrent producer publication. */
internal class IdentityFrameMailbox<T : Any, F : Any> {
    internal class Registration<T : Any, F : Any> internal constructor(val owner: T, val epoch: Long) {
        internal val pending = AtomicReference<F?>(null)
    }

    private val owners = IdentityOwnerRegistry<T>()
    private var lastEpoch = 0L
    @Volatile private var active: Registration<T, F>? = null

    fun current(): T? = active?.owner
    fun currentRegistration(): Registration<T, F>? = active
    fun currentEpoch(): Long? = active?.epoch
    fun isCurrent(registration: Registration<T, F>?): Boolean = active === registration

    @Synchronized
    fun register(owner: T): Registration<T, F>? {
        if (!owners.register(owner)) return null
        return Registration<T, F>(owner, ++lastEpoch).also { active = it }
    }

    @Synchronized
    fun unregister(owner: T): Boolean {
        if (!owners.unregister(owner)) return false
        active = owners.current()?.let { Registration<T, F>(it, ++lastEpoch) }
        return true
    }

    @Synchronized
    fun clearAll() {
        owners.clearAll()
        active = null
    }

    @Synchronized
    fun size(): Int = owners.size()

    fun publish(registration: Registration<T, F>, frame: F): Boolean {
        // A retiring producer can only write its old slot, never a successor's pending frame.
        registration.pending.set(frame)
        return isCurrent(registration)
    }

    fun dispatchPending(consume: (T, F) -> Unit) {
        val registration = active ?: return
        val frame = registration.pending.getAndSet(null) ?: return
        if (isCurrent(registration)) consume(registration.owner, frame)
    }

    fun hasPending(): Boolean = active?.pending?.get() != null
}
