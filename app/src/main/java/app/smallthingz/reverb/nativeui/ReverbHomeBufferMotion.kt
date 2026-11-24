package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.ReverbService.BufferSlot

internal typealias ReverbHomeBufferMotion = smallthingz.ui.reverb.home.ReverbHomeBufferMotion<BufferSlot>

internal fun ReverbHomeBufferMotion(initial: BufferSlot): ReverbHomeBufferMotion =
    ReverbHomeBufferMotion(initial, BufferSlot.ONE_SHOT, BufferSlot.LOOPING)
