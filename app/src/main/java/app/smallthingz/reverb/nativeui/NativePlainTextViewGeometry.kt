/*
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package app.smallthingz.reverb.nativeui

import android.os.Build
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.setFrom

/** Explicit source semantics clips; absence of knowledge differs from a known unclipped layer. */
internal sealed interface NativeTextSemanticsClip {
    data object Unclipped : NativeTextSemanticsClip
    data class Rectangle(val bounds: Rect) : NativeTextSemanticsClip
}

/**
 * Supplied by the existing layout owner, not a new semantic tree or process registry.
 *
 * Return Unclipped for an ordinary source layout and Rectangle for an actual source clip layer.
 * A source shape/rounded clip contributes its entire rectangular layer bounds. Multiple modifiers
 * with distinct placement/clip order need separate native ancestors; do not collapse their clips.
 *
 * null means the correspondence is not known: the caller cannot truthfully return character bounds.
 * Native clipChildren/clipToPadding defaults are not evidence of a source modifier clip. Configure
 * native drawing consistently; do not infer a source clip from integer getGlobalVisibleRect.
 */
internal fun interface NativeTextSemanticsClipPolicy {
    fun localClip(view: View): NativeTextSemanticsClip?
}

/** Optional source graphicsLayer math for a specifically known ported native layer. */
internal fun interface NativeTextSemanticsTransformPolicy {
    /** Fill target and return true only for that source layer; false keeps native View.matrix. */
    fun populate(view: View, target: Matrix): Boolean
}

/** Exactly the existing one Matrix per ancestor; no extra renderer or transform cache. */
internal fun nativeTextSemanticsMatrix(
    view: View,
    policy: NativeTextSemanticsTransformPolicy? = null,
): Matrix = Matrix().also { target ->
    populateNativeTextSemanticsTransform(target,
        sourceTransform = { policy?.populate(view, it) == true },
        backendTransform = { it.setFrom(view.matrix) })
}

/**
 * Direct on-demand geometry capture for an existing native root. No View is retained in a returned
 * snapshot, no global listener is installed, and no work runs on draw/frame callbacks.
 *
 * originalParagraphOriginInView/originalSemanticsBoundsInView must describe ORIGINAL text layout,
 * in visible View-local coordinates (including original padding and any leaf-content scroll).
 * Normal ReverbParagraphView does not scroll its own content. Ancestor scroll is handled below.
 * Source accessibility translates glyph boxes by mapped origin; it does not transform glyph boxes
 * through the child matrix. PlainTextAccessibility intentionally retains that source behavior.
 */
@MainThread
internal class NativePlainTextViewGeometry(
    private val root: View,
    private val clips: NativeTextSemanticsClipPolicy,
    // Explicit source content layer at root coordinates, not an implicit native viewport clip.
    private val rootContentClip: (() -> Rect)? = null,
    private val transformPolicy: NativeTextSemanticsTransformPolicy? = null,
) {
    fun capture(
        label: View,
        originalParagraphOriginInView: Offset,
        originalSemanticsBoundsInView: Rect,
        // Optional owner-sampled offset for the source's MotionEvent raw-coordinate correction.
        // This is NOT an arbitrary tweak: omit unless the native host owns that correction.
        windowPositionInScreen: Offset? = null,
    ): PlainTextAccessibilityGeometry? {
        if (!root.isAttachedToWindow || !label.isAttachedToWindow) return null
        val layers = ArrayList<NativeTextGeometryLayer>()
        var current = label
        while (current !== root) {
            val parent = current.parent as? View ?: return null // foreign root or detached chain
            val clip = when (val local = clips.localClip(current) ?: return null) {
                NativeTextSemanticsClip.Unclipped -> null
                is NativeTextSemanticsClip.Rectangle -> local.bounds
            }
            // Known source graphicsLayer nodes can retain their semantic matrix arithmetic. All
            // other native View matrices include their actual transform properties. Parent
            // scroll belongs to the placement edge (AOSP View.transformMatrixToGlobal), not the
            // child's transform. Keep placement separate to retain the source arithmetic order.
            layers += NativeTextGeometryLayer(
                nativeTextSemanticsMatrix(current, transformPolicy),
                Offset(
                    current.left.toFloat() - parent.scrollX.toFloat(),
                    current.top.toFloat() - parent.scrollY.toFloat(),
                ),
                clip,
            )
            current = parent
        }
        val mapped = mapNativePlainTextGeometry(
            originalParagraphOriginInView, originalSemanticsBoundsInView, layers,
            rootContentClip = rootContentClip?.invoke(),
        )
        val screen = captureRootToScreen(root, windowPositionInScreen)
        return PlainTextAccessibilityGeometry(
            mapped.paragraphPosition, mapped.bounds, screen::map,
        )
    }
}

private fun captureRootToScreen(root: View, suppliedWindowPosition: Offset?): NativeTextRootToScreen {
    var top = root
    while (top.parent is View) top = top.parent as View
    val screen = IntArray(2)
    val window = IntArray(2)
    top.getLocationOnScreen(screen)
    top.getLocationInWindow(window)
    val measuredWindowPosition = Offset(
        (screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat(),
    )
    val matrix = if (Build.VERSION.SDK_INT >= 29) {
        NativeTextMatrixApi29.toWindow(root, screen, window)
    } else {
        // minSdk28: preserve the pinned Compose API21 fallback, including its CURRENT View scroll
        // ordering. This differs from the API29 native transform helper and must not be "fixed".
        Matrix().also { calculateLegacyMatrixToWindow(root, it, Matrix(), IntArray(2)) }
    }
    return NativeTextRootToScreen(matrix, suppliedWindowPosition ?: measuredWindowPosition)
}

@RequiresApi(29)
private object NativeTextMatrixApi29 {
    fun toWindow(root: View, topScreen: IntArray, topWindow: IntArray): Matrix {
        val native = android.graphics.Matrix()
        root.transformMatrixToGlobal(native)
        native.postTranslate(
            (topWindow[0] - topScreen[0]).toFloat(),
            (topWindow[1] - topScreen[1]).toFloat(),
        )
        return Matrix().apply { setFrom(native) }
    }
}

/** Pinned AndroidComposeView.CalculateMatrixToWindowApi21 algorithm, with public Matrix APIs. */
private fun calculateLegacyMatrixToWindow(
    view: View,
    result: Matrix,
    scratch: Matrix,
    location: IntArray,
) {
    val parent = view.parent
    if (parent is View) {
        calculateLegacyMatrixToWindow(parent, result, scratch, location)
        preTranslate(result, -view.scrollX.toFloat(), -view.scrollY.toFloat(), scratch)
        preTranslate(result, view.left.toFloat(), view.top.toFloat(), scratch)
    } else {
        view.getLocationInWindow(location)
        preTranslate(result, -view.scrollX.toFloat(), -view.scrollY.toFloat(), scratch)
        preTranslate(result, location[0].toFloat(), location[1].toFloat(), scratch)
    }
    if (!view.matrix.isIdentity) {
        scratch.setFrom(view.matrix)
        // Same dot-product order as AndroidComposeView.preTransform(other).
        scratch *= result
        result.setFrom(scratch)
    }
}

private fun preTranslate(result: Matrix, x: Float, y: Float, scratch: Matrix) {
    scratch.reset()
    scratch.translate(x, y)
    scratch *= result
    result.setFrom(scratch)
}
