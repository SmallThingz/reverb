/* Source-derived FilesScreen edge pointerInput and foundation 1.11.2 vertical drag helpers. */
package app.smallthingz.reverb

import app.smallthingz.nativeui.input.pointer.PointerType
import kotlin.math.abs
import kotlin.math.sign

/** Initial-pass DOWN, then ordinary Main/Final vertical slop and drag, exactly as FilesScreen. */
internal class NativeLibraryEdgeDismiss(
    private val width: () -> Float,
    private val touchSlop: () -> Float,
    private val requiredDistance: () -> Float,
    private val dismiss: () -> Unit,
) {
    private enum class State { DOWN, SLOP, DRAG, WAIT_UP }
    private var state = State.DOWN
    private var pointer = Long.MIN_VALUE
    private var accumulated = 0f
    private var downward = 0f
    private var threshold = 0f
    private var verifyFinal: Long? = null
    private var firstMain = false

    fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (state == State.WAIT_UP) {
            if (pass == NativePointerPass.FINAL && event.changes.none { it.pressed }) restart()
            return
        }
        if (state == State.DOWN) {
            if (pass != NativePointerPass.INITIAL || event.changes.isEmpty() ||
                event.changes.any { !it.changedToDown }) return
            val down = event.changes.first()
            if (!isLibraryDismissEdge(down.position.x, width())) { state = State.WAIT_UP; return }
            pointer = down.id
            accumulated = 0f
            downward = 0f
            threshold = if (down.type == PointerType.Mouse) touchSlop() * (0.125f / 18f) else touchSlop()
            state = State.SLOP
            firstMain = true
            return
        }
        if (pass == NativePointerPass.INITIAL) return
        if (pass == NativePointerPass.FINAL) {
            val watched = verifyFinal
            verifyFinal = null
            if (watched != null && event.changes.firstOrNull { it.id == watched }?.consumed == true) {
                state = State.WAIT_UP
            }
            return
        }
        if (firstMain) { firstMain = false; return }
        val change = event.changes.firstOrNull { it.id == pointer }
        if (change == null) { state = State.WAIT_UP; return }
        when (state) {
            State.SLOP -> {
                if (change.consumed) { state = State.WAIT_UP; return }
                if (change.changedToUp) {
                    val next = event.changes.firstOrNull { it.pressed }
                    if (next == null) state = State.WAIT_UP else pointer = next.id
                    return
                }
                accumulated += change.delta.y
                if (abs(accumulated) >= threshold) {
                    val over = accumulated - sign(accumulated) * threshold
                    if (over > 0f) {
                        downward += over
                        change.consume()
                        state = State.DRAG
                    } else {
                        // awaitVerticalTouchSlopOrCancellation restarts accumulation when the
                        // callback declines the crossing; upward motion is not accepted.
                        accumulated = 0f
                    }
                } else verifyFinal = change.id
            }
            State.DRAG -> {
                if (change.changedToUp) {
                    val next = event.changes.firstOrNull { it.pressed }
                    if (next != null) { pointer = next.id; return }
                    state = State.WAIT_UP
                    if (!change.consumed && downward >= requiredDistance()) dismiss()
                } else if (change.delta.y != 0f) {
                    if (change.consumed) state = State.WAIT_UP else {
                        downward = (downward + change.delta.y).coerceAtLeast(0f)
                        change.consume()
                    }
                }
            }
            State.DOWN, State.WAIT_UP -> Unit
        }
    }
    /** Source pointer-key replacement and stream cancellation never commit dismissal. */
    fun restart() {
        state = State.DOWN
        pointer = Long.MIN_VALUE
        accumulated = 0f
        downward = 0f
        verifyFinal = null
        firstMain = false
    }
}
