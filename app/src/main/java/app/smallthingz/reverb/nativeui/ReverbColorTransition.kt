package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.animation.view.transitionForegroundInk

/** Bind one retained paint to the shared timing without another drawable or frame loop. */
internal fun View.colorTransition(initial:Int, publish:(Int)->Unit):ViewColorTransition {
    lateinit var motion:ViewColorTransition
    motion=ViewColorTransition(this,intArrayOf(initial),{publish(motion.color(0))})
    return motion
}

/** Preserve caller endpoints; avoid an unreadable opaque fill/ink crossing during theme motion. */
internal fun transitionInk(motion:ViewColorTransition?,fill:Int,ink:Int):Int =
    transitionForegroundInk(motion,fill,ink)
