package app.smallthingz.reverb.nativeui

import smallthingz.ui.graphics.Color
import smallthingz.ui.unit.IntSize

internal typealias LibraryTweenValue<T, V> = smallthingz.ui.animation.AnimatedValue<T, V>
internal typealias LibraryTransitionValue<T, V> = smallthingz.ui.animation.TransitionValue<T, V>
internal typealias LibraryExpansionMotion = smallthingz.ui.reverb.library.LibraryExpansionMotion

internal fun libraryColorTween(initial: Color) = smallthingz.ui.reverb.library.libraryColorTween(initial)
internal fun librarySizeTween(initial: IntSize) = smallthingz.ui.reverb.library.librarySizeTween(initial)
