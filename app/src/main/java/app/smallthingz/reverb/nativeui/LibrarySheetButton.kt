package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeActionSheetContentContext
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.reverb.ReverbSheetButton
import smallthingz.ui.reverb.ReverbSheetButtonHost
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeSourceAccessibilityNodeProvider
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope

internal class NativeMaterialButtonHost(
    val environment: ReverbViewEnvironment,
    inputRoot: View,
    interestedParent: (NativeDragPointerChange) -> Boolean,
    textWindow: PlainTextAccessibilityWindowScope,
    textGeometry: NativePlainTextViewGeometry,
    sourceFacts: (View, Float) -> NativeSourceAccessibilityNode,
    registerTextClip: (View) -> AutoCloseable,
    createHit: (View, List<NativeViewPhasePointerNode>, () -> List<NativeViewPointerHitLayout>,
                (Offset) -> Boolean) -> NativeViewPointerHitLayout,
    admitChildren: ((ArrayList<View>, Int, (View) -> View?) -> Unit)? = null,
    sourceProvider: NativeSourceAccessibilityNodeProvider? = null,
) : ReverbSheetButtonHost(environment.theme, inputRoot, interestedParent, textWindow, textGeometry,
    sourceFacts, registerTextClip, createHit, admitChildren, sourceProvider)

internal typealias LibrarySheetButton = ReverbSheetButton

internal fun LibrarySheetButton(
    context: Context,
    host: NativeActionSheetContentContext,
    environment: ReverbViewEnvironment,
    filled: Boolean,
    text: String,
    onClick: () -> Unit,
    leadingIcon: ReverbIcon? = null,
    errorColors: Boolean = false,
): LibrarySheetButton = ReverbSheetButton(context, host, environment.theme, filled, text, onClick,
    leadingIcon, errorColors)

internal fun LibrarySheetButton.bindEnvironment(environment: ReverbViewEnvironment) =
    bindEnvironment(environment.theme)
