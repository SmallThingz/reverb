package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import androidx.core.graphics.ColorUtils

/** Bind one retained paint to the shared timing without another drawable or frame loop. */
internal fun View.colorTransition(initial:Int, publish:(Int)->Unit):ViewColorTransition {
    lateinit var motion:ViewColorTransition
    motion=ViewColorTransition(this,intArrayOf(initial),{publish(motion.color(0))})
    return motion
}

/** Preserve caller endpoints; avoid an unreadable opaque fill/ink crossing during theme motion. */
internal fun transitionInk(motion:ViewColorTransition?,fill:Int,ink:Int):Int {
    if(motion?.isInterpolating!=true||fill ushr 24!=255||ink ushr 24!=255||ColorUtils.calculateContrast(ink,fill)>=4.5)return ink
    val black=android.graphics.Color.BLACK
    val white=android.graphics.Color.WHITE
    return if(ColorUtils.calculateContrast(black,fill)>=ColorUtils.calculateContrast(white,fill))black else white
}
