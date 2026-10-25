package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.NativeHomeHorizontalDrag

/** Retire both halves of Home's input lifetime; detector restart alone has no cancel callback. */
internal fun restartHomeDragInput(
    motion: ReverbHomeBufferMotion,
    detector: NativeHomeHorizontalDrag,
    durationScale: Float,
) {
    // This only returns an active drag. Already accepted navigation/settling keeps its owner.
    motion.dragCancelled(durationScale)
    detector.restart()
}
