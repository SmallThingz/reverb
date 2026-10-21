package app.smallthingz.reverb.nativeui

import kotlin.math.roundToInt

/** OutlinedTextField.calculateHeight uses Float padding sum before its final round, unlike constraints. */
internal fun nativeRenameFieldHeight(textHeight:Int,supportingHeight:Int,density:Float,minHeight:Int,maxHeight:Int):Int =
    ((16f*density+textHeight+16f*density).roundToInt()+supportingHeight).coerceIn(minHeight,maxHeight)
