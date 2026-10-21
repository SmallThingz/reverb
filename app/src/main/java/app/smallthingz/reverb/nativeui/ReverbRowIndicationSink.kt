package app.smallthingz.reverb.nativeui

import android.graphics.PointF
import java.util.IdentityHashMap

/**
 * Thin adapter to the separate foreground proposal. Input and drawing hosts may be different Views.
 * A filled Button's separate elevation observer can consume the same NativeClickIndication stream;
 * that observer is not implemented by a row ripple.
 */
internal class ReverbRowIndicationSink(
    private val foreground: ReverbRowIndication,
    /** Map input-local coordinates into the drawing host, including any minimum-target inset. */
    private val mapPressPosition: (x: Float, y: Float, output: PointF) -> Unit,
) : NativeClickIndication {
    private val states = IdentityHashMap<NativeStateToken, ReverbRowIndication.StateToken>()
    private val position = PointF()

    override fun press(token: NativePressToken) {
        mapPressPosition(token.x, token.y, position)
        foreground.press(position.x, position.y)
    }
    override fun release(token: NativePressToken) = foreground.release()
    override fun cancel(token: NativePressToken) = foreground.cancel()

    override fun stateEnter(token: NativeStateToken) {
        val kind = when (token.kind) {
            NativeStateToken.Kind.HOVER -> ReverbRowIndication.StateKind.HOVER
            NativeStateToken.Kind.FOCUS -> ReverbRowIndication.StateKind.FOCUS
        }
        states[token] = foreground.enterState(kind)
    }

    override fun stateExit(token: NativeStateToken) {
        states.remove(token)?.let(foreground::leaveState)
    }
}
