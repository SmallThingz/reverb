package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.components.NativeActionSheetContentContext
import smallthingz.ui.reverb.ReverbSheetActions

internal typealias LibrarySheetActions = ReverbSheetActions

internal fun LibrarySheetActions(
    context: Context,
    host: NativeActionSheetContentContext,
    environment: ReverbViewEnvironment,
    buttons: List<LibrarySheetButton>,
): LibrarySheetActions = ReverbSheetActions(context, host, environment.theme, buttons)

internal fun LibrarySheetActions.bindEnvironment(environment: ReverbViewEnvironment) =
    bindEnvironment(environment.theme)
