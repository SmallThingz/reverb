package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import app.smallthingz.reverb.R
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.reverb.ReverbLibraryStatusLabels
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.scroll.NativeVerticalNestedHost

internal typealias LibraryBodyMode = smallthingz.ui.reverb.LibraryBodyMode

/** Supplies localized Library labels and the existing input owner. */
internal class ReverbLibraryStatusView(context: Context, environment: ReverbViewEnvironment,
    frames: MonotonicFrameClock, parentScope: CoroutineScope, nested: NativeVerticalNestedHost,
    nativeInputRoot: View, durationScale: () -> Float) : smallthingz.ui.reverb.ReverbLibraryStatusView(
    context, environment, frames, parentScope, nested, nativeInputRoot,
    ReverbLibraryStatusLabels({ context.getString(R.string.recordings_refresh_failed) },
        { context.getString(R.string.library_retry) }), durationScale)
