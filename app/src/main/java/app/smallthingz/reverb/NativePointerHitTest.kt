/*
 * Source-derived specialization of UI 1.11.2 NodeCoordinator / HitTestResult.
 * Copyright 2021-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.pointer.PointerType
import kotlin.math.max

/**
 * Direct input geometry supplied by the concrete layout owner, not a second renderer or registry.
 * Each instance is a real released coordinator boundary; modifier nodes retain their source order.
 * childrenInHitOrder is the source's reversed z-sorted *placed* children, never View.elevation order.
 *
 * Pointer sibling sharing follows source acceptHits/siblingHits. Explicit stylus touch-bound
 * expansion and interceptOutOfBoundsChildEvents remain outside these concrete screen nodes.
 */
internal interface NativePointerHitLayout {
    val placed: Boolean
    val size: Size
    val minimumTouchTarget: Size
    val nodes: List<NativePhasePointerNode>
    val childrenInHitOrder: List<NativePointerHitLayout>
    val shareWithSiblings: Boolean get() = nodes.any { it.attached && it.shareWithSiblings }
    fun screenToLocal(screen: Offset): Offset
    fun withinLayerBounds(local: Offset): Boolean
}

/** The default flags branch of DistanceAndFlags: no explicit expanded bounds in these pages. */
internal data class NativePointerHitScore(val distance: Float, val inLayer: Boolean) : Comparable<NativePointerHitScore> {
    override fun compareTo(other: NativePointerHitScore): Int {
        if (inLayer != other.inLayer) return if (inLayer) -1 else 1
        return distance.compareTo(other.distance)
    }
    val direct: Boolean get() = inLayer && distance < 0f
}

internal fun nativeMinimumTouchDistance(local: Offset, size: Size, minimum: Size): Float {
    if (size.width >= minimum.width && size.height >= minimum.height) return Float.POSITIVE_INFINITY
    val horizontal = max(0f, (minimum.width - size.width) / 2f)
    val vertical = max(0f, (minimum.height - size.height) / 2f)
    val edgeX = max(0f, if (local.x < 0f) -local.x else local.x - size.width)
    val edgeY = max(0f, if (local.y < 0f) -local.y else local.y - size.height)
    return if ((horizontal > 0f || vertical > 0f) && edgeX <= horizontal && edgeY <= vertical)
        edgeX * edgeX + edgeY * edgeY else Float.POSITIVE_INFINITY
}

/** One DOWN hit test; movement/UP use NativePointerPhaseTree's retained identities. */
internal fun nativePointerHitPath(
    root: NativePointerHitLayout,
    screen: Offset,
    type: PointerType,
): List<NativePhasePointerNode> {
    val hits = mutableListOf<NativePhasePointerNode>()
    val scores = mutableListOf<NativePointerHitScore>()
    var depth = -1
    fun best(): NativePointerHitScore {
        var score = NativePointerHitScore(Float.POSITIVE_INFINITY, false)
        for (index in depth + 1 until scores.size) {
            if (scores[index] < score) score = scores[index]
            if (score.direct) break
        }
        return score
    }
    fun record(node: NativePhasePointerNode, score: NativePointerHitScore, child: () -> Unit) {
        val startDepth = depth
        while (hits.size > depth + 1) {
            hits.removeAt(hits.lastIndex)
            scores.removeAt(scores.lastIndex)
        }
        hits += node
        scores += score
        depth++
        child()
        depth = startDepth
    }
    fun hit(layout: NativePointerHitLayout, inheritedLayer: Boolean) {
        if (!layout.placed) return
        val local = layout.screenToLocal(screen)
        if (!local.x.isFinite() || !local.y.isFinite()) return
        val withinLayer = layout.withinLayerBounds(local)
        val distance = if (type == PointerType.Touch)
            nativeMinimumTouchDistance(local, layout.size, layout.minimumTouchTarget)
        else Float.POSITIVE_INFINITY
        if (!withinLayer && (!distance.isFinite() ||
                    best() <= NativePointerHitScore(distance, false))) return
        val inLayer = inheritedLayer && withinLayer
        val inBounds = local.x >= 0f && local.y >= 0f &&
            local.x < layout.size.width && local.y < layout.size.height
        val score = NativePointerHitScore(if (withinLayer && inBounds) -1f else distance, inLayer)
        // A near hit is recorded only when better than the previous sibling. A miss still
        // traverses unclipped children, but default pointer nodes do not intercept that miss.
        val recordOwn = layout.nodes.isNotEmpty() && (
            withinLayer && inBounds || distance.isFinite() && best() > score
        )
        fun children() {
            val startDepth = depth
            for (child in layout.childrenInHitOrder) {
                hit(child, inLayer)
                if (best().direct) {
                    if (child.shareWithSiblings) depth = hits.lastIndex // HitTestResult.acceptHits.
                    else break
                }
            }
            depth = startDepth // HitTestResult.siblingHits restores the caller's depth.
        }
        fun modifiers(index: Int) {
            if (index == layout.nodes.size) children()
            else if (recordOwn) record(layout.nodes[index], score) { modifiers(index + 1) }
            else modifiers(index + 1)
        }
        modifiers(0)
    }
    hit(root, true)
    return hits
}
