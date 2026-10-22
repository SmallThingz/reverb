package app.smallthingz.nativeui.dsl

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Construction only: the returned value is the actual View, with no retained DSL or wrapper. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class NativeViewDsl

@NativeViewDsl
inline fun <V : View> nativeView(context: Context, factory: (Context) -> V, configure: V.() -> Unit = {}): V =
    factory(context).apply(configure)

/** Attach in exactly the supplied order, retaining the caller's concrete LayoutParams unchanged. */
@NativeViewDsl
inline fun <V : View> ViewGroup.child(view: V, params: ViewGroup.LayoutParams, configure: V.() -> Unit = {}): V {
    view.configure()
    addView(view, params)
    return view
}

@NativeViewDsl
inline fun <V : View> ViewGroup.child(view: V, configure: V.() -> Unit = {}): V {
    view.configure()
    addView(view)
    return view
}
