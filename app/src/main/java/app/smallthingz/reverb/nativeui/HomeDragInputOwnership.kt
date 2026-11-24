package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.NativeHomeHorizontalDrag

internal fun restartHomeDragInput(
    motion: ReverbHomeBufferMotion,
    detector: NativeHomeHorizontalDrag,
    durationScale: Float,
) = smallthingz.ui.reverb.home.restartHomeDragInput(motion, detector, durationScale)
