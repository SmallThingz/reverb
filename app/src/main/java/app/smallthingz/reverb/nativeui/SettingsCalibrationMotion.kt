package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.smoothProgressSpring

/** Continuous velocity across 100ms PCM publications, without overshooting progress. */
internal fun calibrationProgressSpring() = smoothProgressSpring()
