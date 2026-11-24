package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition

/** Bind one retained paint to the shared timing without another drawable or frame loop. */
internal fun View.colorTransition(initial:Int, publish:(Int)->Unit):ViewColorTransition {
    lateinit var motion:ViewColorTransition
    motion=ViewColorTransition(this,intArrayOf(initial),{publish(motion.color(0))})
    return motion
}
