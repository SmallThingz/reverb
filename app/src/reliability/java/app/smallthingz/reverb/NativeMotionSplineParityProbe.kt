package app.smallthingz.reverb

import android.view.ViewConfiguration
import androidx.compose.animation.SplineBasedFloatDecayAnimationSpec as SourceDecay
import app.smallthingz.nativeui.animation.SplineBasedFloatDecayAnimationSpec as NativeDecay

/**
 * Runs both real decay engines on Android. The platform supplies friction to both.
 * This is numerical motion qualification, not frame scheduling or smoothness proof.
 */
internal fun verifyNativeMotionSplineParity(report: StringBuilder) {
    var comparisons = 0
    fun exact(label: String, source: Float, native: Float) {
        check(source.toRawBits() == native.toRawBits()) {
            "$label source=$source/${source.toRawBits()} native=$native/${native.toRawBits()}"
        }
        comparisons++
    }
    val friction = ViewConfiguration.getScrollFriction()
    check(friction > 0f && friction.isFinite())
    for (density in listOf(1f, 421f / 160f, 3.5f)) {
        val source = SourceDecay(androidx.compose.ui.unit.Density(density))
        val native = NativeDecay(app.smallthingz.nativeui.unit.Density(density))
        exact("threshold", source.absVelocityThreshold, native.absVelocityThreshold)
        for (initial in listOf(0f, -42.125f, 1000f)) {
            for (velocity in listOf(0f, 1f, -1f, 100f, -100f, 3000f, -10000f, 40000f)) {
                val sourceDuration = source.getDurationNanos(initial, velocity)
                val nativeDuration = native.getDurationNanos(initial, velocity)
                check(sourceDuration == nativeDuration) {
                    "fling duration density=$density velocity=$velocity source=$sourceDuration native=$nativeDuration"
                }
                comparisons++
                exact("target density=$density velocity=$velocity",
                    source.getTargetValue(initial, velocity), native.getTargetValue(initial, velocity))
                val times = (listOf(0L, 1L, 999999L, 1000000L, 8333333L, 16666667L,
                    sourceDuration / 2, sourceDuration, sourceDuration + 1) +
                    (0..40).map { sourceDuration * it / 40 }).distinct()
                for (time in times) {
                    exact("fling value density=$density velocity=$velocity time=$time",
                        source.getValueFromNanos(time, initial, velocity),
                        native.getValueFromNanos(time, initial, velocity))
                    exact("fling velocity density=$density velocity=$velocity time=$time",
                        source.getVelocityFromNanos(time, initial, velocity),
                        native.getVelocityFromNanos(time, initial, velocity))
                }
            }
        }
    }
    report.appendLine("native_motion_spline: PASS exact_raw_bits=$comparisons platform_friction=$friction")
}
