package app.smallthingz.nativeui
import kotlin.coroutines.CoroutineContext
interface MotionDurationScale : CoroutineContext.Element {
    val scaleFactor: Float
    override val key: CoroutineContext.Key<*> get() = Key
    companion object Key : CoroutineContext.Key<MotionDurationScale>
}
