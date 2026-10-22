package app.smallthingz.nativeui.animation

import app.smallthingz.nativeui.animation.core.Animatable
import app.smallthingz.nativeui.animation.core.AnimationVector4D
import app.smallthingz.nativeui.graphics.Color

fun Animatable(initialValue: Color): Animatable<Color, AnimationVector4D> =
    Animatable(initialValue, (Color.VectorConverter)(initialValue.colorSpace))
