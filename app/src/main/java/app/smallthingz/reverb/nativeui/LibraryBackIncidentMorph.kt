package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativePathMorph

internal fun libraryBackIncidentMorph(density: Float) = NativePathMorph(
    ReverbIconPaths.copyLibraryBackPath(), ReverbIconPaths.copyHomeIncidentPath(),
    ReverbIconDrawable(ReverbIcon.Incidents, density, 0),
)
