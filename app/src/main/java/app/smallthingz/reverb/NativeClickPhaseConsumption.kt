package app.smallthingz.reverb

/**
 * Only the original down-change exemption is retained here; ReverbClickController still owns
 * every click/press/long-press state. The callback's handled Boolean has no consumption meaning.
 */
internal class NativeClickPhaseConsumption {
    private var downChange: NativeDragPointerChange? = null

    fun main(
        event: NativeDragPointerEvent,
        touch: (consumedByOther: Boolean, consumeChanges: (allPointers: Boolean) -> Unit) -> Boolean,
    ) {
        touch(event.changes.any { it.consumed }) { all ->
            if (all) event.changes.forEach { it.consume() }
            else event.changes.firstOrNull()?.let { change ->
                change.consume()
                if (change.changedToDown) downChange = change
            }
        }
    }

    fun final(event: NativeDragPointerEvent, afterParentDispatch: (Boolean) -> Unit) {
        afterParentDispatch(event.changes.any { it.consumed && it !== downChange })
        if (event.changes.none { it.pressed }) downChange = null
    }

    fun cancel() { downChange = null }
}
