package app.smallthingz.reverb

/** Rebinding a rendered row must not turn the service's delayed progress tick into a busy loop. */
internal class PlaybackPositionObservers(private val onChanged: () -> Unit) {
    private val owners = java.util.IdentityHashMap<Any, Unit>()
    val observed: Boolean get() = owners.isNotEmpty()

    fun observe(owner: Any, visible: Boolean) {
        if (owners.containsKey(owner) == visible) return
        if (visible) owners[owner] = Unit else owners.remove(owner)
        onChanged()
    }
}
