/* AndroidX ui1.11.2 TwoDimensionalFocusSearch; declaration names changed only.
 * Copyright 2021 The Android Open Source Project. Apache License 2.0. */
package app.smallthingz.reverb

import smallthingz.ui.focus.FocusDirection
import smallthingz.ui.focus.FocusDirection.Companion.Left
import smallthingz.ui.focus.FocusDirection.Companion.Right
import smallthingz.ui.focus.FocusDirection.Companion.Up
import smallthingz.ui.focus.FocusDirection.Companion.Down
import smallthingz.ui.geometry.Rect
import smallthingz.ui.util.fastCoerceAtLeast

private const val InvalidFocusDirection = "Expected two-dimensional focus direction"

internal fun <T> nativeBestDirectionalFocusCandidate(
    candidates: List<T>, focused: Rect, direction: FocusDirection, bounds: (T) -> Rect?,
): T? {
    var best = when (direction) {
        Left -> focused.translate(focused.width + 1, 0f)
        Right -> focused.translate(-(focused.width + 1), 0f)
        Up -> focused.translate(0f, focused.height + 1)
        Down -> focused.translate(0f, -(focused.height + 1))
        else -> error(InvalidFocusDirection)
    }
    var result: T? = null
    for (candidate in candidates) {
        val rect = bounds(candidate) ?: continue
        if (nativeIsBetterFocusCandidate(rect, best, focused, direction)) {
            best = rect
            result = candidate
        }
    }
    return result
}

internal fun nativeIsBetterFocusCandidate(
    proposedCandidate: Rect,
    currentCandidate: Rect,
    focusedRect: Rect,
    direction: FocusDirection,
): Boolean {

    // Is this Rect a candidate for the next focus given the direction? This checks whether the
    // rect is at least partially to the direction of (e.g left of) from source. Includes an edge
    // case for an empty rect (which is used in some cases when searching from a point on the
    // screen).
    fun Rect.isCandidate() =
        when (direction) {
            Left ->
                (focusedRect.right > right || focusedRect.left >= right) && focusedRect.left > left
            Right ->
                (focusedRect.left < left || focusedRect.right <= left) && focusedRect.right < right
            Up ->
                (focusedRect.bottom > bottom || focusedRect.top >= bottom) && focusedRect.top > top
            Down ->
                (focusedRect.top < top || focusedRect.bottom <= top) && focusedRect.bottom < bottom
            else -> error(InvalidFocusDirection)
        }

    // The distance from the edge furthest in the given direction of source to the edge nearest
    // in the given direction of dest. If the dest is not in the direction from source, return 0.
    fun Rect.majorAxisDistance(): Float {
        val majorAxisDistance =
            when (direction) {
                Left -> focusedRect.left - right
                Right -> left - focusedRect.right
                Up -> focusedRect.top - bottom
                Down -> top - focusedRect.bottom
                else -> error(InvalidFocusDirection)
            }
        return majorAxisDistance.fastCoerceAtLeast(0f)
    }

    // Find the distance on the minor axis w.r.t the direction to the nearest edge of the
    // destination rectangle.
    fun Rect.minorAxisDistance() =
        when (direction) {
            // the distance between the center verticals
            Left,
            Right -> (focusedRect.top + focusedRect.height / 2) - (top + height / 2)
            // the distance between the center horizontals
            Up,
            Down -> (focusedRect.left + focusedRect.width / 2) - (left + width / 2)
            else -> error(InvalidFocusDirection)
        }

    // Fudge-factor opportunity: how to calculate distance given major and minor axis distances.
    // Warning: This fudge factor is finely tuned, run all focus tests if you dare tweak it.
    fun weightedDistance(candidate: Rect): Long {
        val majorAxisDistance = candidate.majorAxisDistance().toLong()
        val minorAxisDistance = candidate.minorAxisDistance().toLong()
        return 13 * majorAxisDistance * majorAxisDistance + minorAxisDistance * minorAxisDistance
    }

    return when {
        // to be a better candidate, need to at least be a candidate in the first place.
        !proposedCandidate.isCandidate() -> false

        // If the currentCandidate is not a candidate, proposedCandidate is better.
        !currentCandidate.isCandidate() -> true

        // if proposedCandidate is better by beam, it wins.
        nativeFocusBeamBeats(focusedRect, proposedCandidate, currentCandidate, direction) -> true

        // if currentCandidate is better, then the proposedCandidate can't be.
        nativeFocusBeamBeats(focusedRect, currentCandidate, proposedCandidate, direction) -> false
        else -> weightedDistance(proposedCandidate) < weightedDistance(currentCandidate)
    }
}

/**
 * A rectangle may be a better candidate by virtue of being exclusively in the beam of the source
 * rect.
 *
 * @return Whether rect1 is a better candidate than rect2 by virtue of it being in the source's
 *   beam.
 */
private fun nativeFocusBeamBeats(source: Rect, rect1: Rect, rect2: Rect, direction: FocusDirection): Boolean {
    // Do the "beams" w.r.t the given direction's axis of rect1 and rect2 overlap?
    fun Rect.inSourceBeam() =
        when (direction) {
            Left,
            Right -> this.bottom > source.top && this.top < source.bottom
            Up,
            Down -> this.right > source.left && this.left < source.right
            else -> error(InvalidFocusDirection)
        }

    // Whether the rect is in the direction of search.
    fun Rect.isInDirectionOfSearch() =
        when (direction) {
            Left -> source.left >= right
            Right -> source.right <= left
            Up -> source.top >= bottom
            Down -> source.bottom <= top
            else -> error(InvalidFocusDirection)
        }

    // The distance from the edge furthest in the given direction of source to the edge nearest
    // in the given direction of dest. If the dest is not in the direction from source, return 0.
    fun Rect.majorAxisDistance(): Float {
        val majorAxisDistance =
            when (direction) {
                Left -> source.left - right
                Right -> left - source.right
                Up -> source.top - bottom
                Down -> top - source.bottom
                else -> error(InvalidFocusDirection)
            }
        return majorAxisDistance.fastCoerceAtLeast(0f)
    }

    // The distance along the major axis w.r.t the direction from the edge of source to the far
    // edge of dest. If the dest is not in the direction from source, return 1 (to break ties
    // with Rect.majorAxisDistance).
    fun Rect.majorAxisDistanceToFarEdge(): Float {
        val majorAxisDistance =
            when (direction) {
                Left -> source.left - left
                Right -> right - source.right
                Up -> source.top - top
                Down -> bottom - source.bottom
                else -> error(InvalidFocusDirection)
            }
        return majorAxisDistance.fastCoerceAtLeast(1f)
    }

    return when {
        // if rect1 isn't exclusively in the src beam, it doesn't win
        rect2.inSourceBeam() || !rect1.inSourceBeam() -> false

        // We know rect1 is in the beam, and rect2 is not. If Rect2 is not in the direction of
        // search, rect1 wins (since rect2 could be reached by going in another direction).
        !rect2.isInDirectionOfSearch() -> true

        // for horizontal directions, being exclusively in beam always wins
        direction == Left || direction == Right -> true

        // for vertical directions, beams only beat up to a point:
        // now, as long as rect2 isn't completely closer, rect1 wins
        // e.g for direction down, completely closer means for rect2's top
        // edge to be closer to the source's top edge than rect1's bottom edge.
        else -> rect1.majorAxisDistance() < rect2.majorAxisDistanceToFarEdge()
    }
}

