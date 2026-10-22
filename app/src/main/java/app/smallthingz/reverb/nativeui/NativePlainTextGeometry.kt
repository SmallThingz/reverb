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

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect
import app.smallthingz.nativeui.graphics.Matrix

/** A copied transform. No mutable View/Matrix state escapes a geometry capture. */
internal class NativeTextGeometryLayer(
    transform: Matrix,
    private val placement: Offset,
    private val localClip: Rect?,
) {
    private val matrix = Matrix(transform.values.copyOf())

    fun mapPosition(point: Offset): Offset = matrix.map(point) + placement

    fun mapBounds(bounds: Rect): Rect {
        // NodeCoordinator.rectInParent clips the layer's rectangular local bounds BEFORE mapping.
        // A rounded outline does not trim semantics to the actual rounded path.
        val clipped = localClip?.let(bounds::intersect) ?: bounds
        if (clipped.isEmpty) return Rect.Zero
        val mapped = matrix.map(clipped).translate(placement)
        return if (mapped.isEmpty) Rect.Zero else mapped
    }
}

internal data class NativeMappedPlainTextGeometry(
    val paragraphPosition: Offset,
    val bounds: Rect,
)

/**
 * Equivalent order to NodeCoordinator.localBoundingBoxOf / rectInParent and localToRoot.
 * Each transformed rectangle becomes an axis-aligned rectangle before the next ancestor clip.
 * Combining all matrices then clipping once is not equivalent under rotation/skew.
 *
 * The native root is normally only a coordinate boundary. Some hosts also implement an explicit
 * source content layer below the platform semantics root (for example Onboarding's Surface).
 * Only that owner-supplied rootContentClip clips mapped bounds; paragraph origin stays unclipped.
 */
internal fun mapNativePlainTextGeometry(
    originalParagraphOrigin: Offset,
    originalSemanticsBounds: Rect,
    layers: List<NativeTextGeometryLayer>,
    rootContentClip: Rect? = null,
): NativeMappedPlainTextGeometry {
    var position = originalParagraphOrigin
    var bounds = if (originalSemanticsBounds.isEmpty) Rect.Zero else originalSemanticsBounds
    for (layer in layers) {
        position = layer.mapPosition(position) // position is not clipped.
        if (!bounds.isEmpty) bounds = layer.mapBounds(bounds)
    }
    if (rootContentClip != null && !bounds.isEmpty) {
        val clipped = bounds.intersect(rootContentClip)
        bounds = if (clipped.isEmpty) Rect.Zero else clipped
    }
    return NativeMappedPlainTextGeometry(position, bounds)
}

/** Source localToScreen maps through viewToWindow THEN adds the sampled window offset. */
internal class NativeTextRootToScreen(transformToWindow: Matrix, private val windowPosition: Offset) {
    private val matrix = Matrix(transformToWindow.values.copyOf())
    fun map(point: Offset): Offset = matrix.map(point) + windowPosition
}

/** Select a known source semantic transform; unknown native layers keep their actual backend map.
 * Inline callbacks fill the already-owned Matrix, without allocating per-capture wrapper lambdas. */
internal inline fun populateNativeTextSemanticsTransform(
    target: Matrix,
    sourceTransform: (Matrix) -> Boolean,
    backendTransform: (Matrix) -> Unit,
) {
    if (!sourceTransform(target)) backendTransform(target)
}
