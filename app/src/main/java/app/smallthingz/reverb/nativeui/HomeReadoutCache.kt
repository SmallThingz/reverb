package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.RetentionMode
import app.smallthingz.reverb.formatShortFileSize
import app.smallthingz.reverb.formatShortTimer
import smallthingz.ui.reverb.home.ReverbHomeReadoutCache

internal fun HomeReadoutCache(): ReverbHomeReadoutCache<RetentionMode> =
    ReverbHomeReadoutCache({ it == RetentionMode.TIME }, ::formatShortTimer, ::formatShortFileSize)
