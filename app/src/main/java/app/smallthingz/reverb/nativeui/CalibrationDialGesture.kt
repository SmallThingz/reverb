package app.smallthingz.reverb.nativeui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import app.smallthingz.reverb.SILENCE_MIN_CLEARANCE_DB
import app.smallthingz.reverb.SILENCE_MAX_CLEARANCE_DB

internal fun calibrationDialFraction(value:Float):Double =
    (value.coerceIn(SILENCE_MIN_CLEARANCE_DB,SILENCE_MAX_CLEARANCE_DB).toDouble()-SILENCE_MIN_CLEARANCE_DB)/
        (SILENCE_MAX_CLEARANCE_DB-SILENCE_MIN_CLEARANCE_DB)

/** Clockwise motion follows the displayed arc; radial motion has no adjustment authority. */
internal fun calibrationDialDelta(value:Float,dx:Float,dy:Float,density:Float):Float {
    if(!value.isFinite()||!dx.isFinite()||!dy.isFinite()||!density.isFinite()||density<=0f)return 0f
    val angle=Math.toRadians(135.0+270.0*calibrationDialFraction(value))
    val tangent=-sin(angle)*dx+cos(angle)*dy
    // Float pointer coordinates can leave a rounding residual on an exactly radial movement.
    if(abs(tangent)<=hypot(dx.toDouble(),dy.toDouble())*1e-7)return 0f
    return (tangent/(density*10.0)).toFloat()
}
