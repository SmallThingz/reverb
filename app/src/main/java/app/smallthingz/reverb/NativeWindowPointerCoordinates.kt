/*
 * Source-derived from UI 1.11.2 AndroidComposeView.calculateMatrixToWindow and
 * recalculateWindowPosition(MotionEvent). Copyright 2020 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0.
 */
package app.smallthingz.reverb

import android.graphics.Matrix
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import java.util.IdentityHashMap

/**
 * One ordinary native window's pointer geometry, sampled once before an input event's callbacks.
 * Root lookup is lazy so a popup factory can construct its root without a mutable global registry.
 * This cache belongs to a single dispatch; it is not a layout, drawing or accessibility state tree.
 */
internal class NativeWindowPointerCoordinates(private val root: () -> View) {
    private val rootToScreenMatrix = Matrix()
    private val position = FloatArray(2)
    private val location = IntArray(2)
    private val perView = IdentityHashMap<View, NativePointerCoordinates>()
    var windowPositionInScreen: Offset = Offset.Zero
        private set

    fun begin(event: MotionEvent) {
        perView.clear()
        val toWindow = matrixToWindow(root())
        position[0] = event.x
        position[1] = event.y
        toWindow.mapPoints(position)
        windowPositionInScreen = Offset(event.rawX - position[0], event.rawY - position[1])
        rootToScreenMatrix.set(toWindow)
        rootToScreenMatrix.postTranslate(windowPositionInScreen.x, windowPositionInScreen.y)
    }

    fun rootToScreen(point: Offset): Offset {
        position[0] = point.x
        position[1] = point.y
        rootToScreenMatrix.mapPoints(position)
        return Offset(position[0], position[1])
    }

    fun coordinates(view: View): NativePointerCoordinates = perView.getOrPut(view) {
        val localToScreen = matrixToWindow(view)
        localToScreen.postTranslate(windowPositionInScreen.x, windowPositionInScreen.y)
        val screenToLocal = Matrix()
        // Released onboarding and menu transforms remain invertible (no zero-scale layer).
        // Singular arbitrary View transforms are outside this source-derived host contract.
        localToScreen.invert(screenToLocal)
        val rootToLocal = Matrix().apply { setConcat(screenToLocal, rootToScreenMatrix) }
        position[0] = 0f
        position[1] = 0f
        localToScreen.mapPoints(position)
        NativePointerCoordinates(screenToLocal, rootToLocal, Offset(position[0], position[1]))
    }

    private fun matrixToWindow(view: View): Matrix {
        val result = Matrix()
        if (Build.VERSION.SDK_INT >= 29) {
            view.transformMatrixToGlobal(result)
            var top = view
            while (top.parent is View) top = top.parent as View
            top.getLocationOnScreen(location)
            val screenX = location[0]
            val screenY = location[1]
            top.getLocationInWindow(location)
            result.postTranslate((location[0] - screenX).toFloat(), (location[1] - screenY).toFloat())
        } else {
            // Preserve the pinned API21 fallback's CURRENT-view scroll ordering, including SDK28.
            calculateLegacy(view, result)
        }
        return result
    }

    private fun calculateLegacy(view: View, result: Matrix) {
        val parent = view.parent
        if (parent is View) {
            calculateLegacy(parent, result)
            result.preTranslate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            result.preTranslate(view.left.toFloat(), view.top.toFloat())
        } else {
            view.getLocationInWindow(location)
            result.preTranslate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            result.preTranslate(location[0].toFloat(), location[1].toFloat())
        }
        if (!view.matrix.isIdentity) result.preConcat(view.matrix)
    }
}
