package app.smallthingz.reverb.nativeui

import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.graphics.Matrix
import app.smallthingz.reverb.populateOnboardingPreviewSemanticTransform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Source-derived float geometry cases; no Android stubs, screen coordinates or bitmap guesses. */
class NativePlainTextGeometryTest {
    @Test fun keepsFractionalPlacementAndIndependentParagraphPadding() {
        val result = mapNativePlainTextGeometry(
            Offset(2.5f, 3.25f), Rect(0f, 0f, 10f, 20f),
            listOf(layer(placement = Offset(10.125f, -0.75f))),
        )
        assertEquals(Offset(12.625f, 2.5f), result.paragraphPosition)
        assertEquals(Rect(10.125f, -0.75f, 20.125f, 19.25f), result.bounds)
    }

    @Test fun clipsBeforeRotationThenAddsPlacement() {
        // x'=-y, y'=x; clipping after this map would produce different bounds.
        val rotate90 = Matrix().apply {
            this[0, 0] = 0f; this[0, 1] = 1f
            this[1, 0] = -1f; this[1, 1] = 0f
        }
        val result = mapNativePlainTextGeometry(
            Offset(-1f, 2f), Rect(-2f, 1f, 6f, 5f),
            listOf(NativeTextGeometryLayer(rotate90, Offset(10.5f, 20.25f), Rect(0f, 0f, 4f, 4f))),
        )
        assertEquals(Offset(8.5f, 19.25f), result.paragraphPosition) // origin remains unclipped
        assertEquals(Rect(6.5f, 20.25f, 9.5f, 24.25f), result.bounds)
    }

    @Test fun keepsIntermediateAxisAlignedBoundsInsteadOfCombiningTransforms() {
        val shear = Matrix().apply { this[1, 0] = 1f }
        val inverseShear = Matrix().apply { this[1, 0] = -1f }
        val result = mapNativePlainTextGeometry(
            Offset.Zero, Rect(0f, 0f, 2f, 2f),
            listOf(
                NativeTextGeometryLayer(shear, Offset.Zero, null),
                NativeTextGeometryLayer(inverseShear, Offset.Zero, null),
            ),
        )
        // Transforming an AABB at each ancestor is intentionally not the identity matrix result.
        assertEquals(Rect(-2f, 0f, 4f, 2f), result.bounds)
    }

    @Test fun ancestorScrollIsPlacementBeforeAncestorClipAndTransform() {
        val result = mapNativePlainTextGeometry(
            Offset.Zero, Rect(0f, 0f, 20f, 20f),
            listOf(
                layer(placement = Offset(10f - 13f, 8f - 2f)),
                layer(placement = Offset(100.5f, 200.25f), clip = Rect(0f, 0f, 12f, 16f)),
            ),
        )
        assertEquals(Offset(97.5f, 206.25f), result.paragraphPosition)
        assertEquals(Rect(100.5f, 206.25f, 112.5f, 216.25f), result.bounds)
    }

    @Test fun anEmptyClipStaysZeroButPositionStillMapsThroughRemainingAncestors() {
        val result = mapNativePlainTextGeometry(
            Offset(3f, 4f), Rect(0f, 0f, 2f, 2f),
            listOf(
                layer(Offset(10f, 20f), Rect(4f, 4f, 6f, 6f)),
                layer(Offset(30f, 40f)),
            ),
        )
        assertEquals(Rect.Zero, result.bounds)
        assertEquals(Offset(43f, 64f), result.paragraphPosition)
    }

    @Test fun rootDoesNotAddAnImplicitViewportClip() {
        val result = mapNativePlainTextGeometry(
            Offset(-2.5f, -3.25f), Rect(-2.5f, -3.25f, 400.5f, 900.25f), emptyList(),
        )
        assertEquals(Rect(-2.5f, -3.25f, 400.5f, 900.25f), result.bounds)
    }

    @Test fun explicitSourceRootContentClipRunsAfterLayersWithoutMovingOrigin() {
        val result = mapNativePlainTextGeometry(
            Offset(-12f, -23f), Rect(-2f, -3f, 40f, 30f),
            listOf(layer(placement = Offset(10.5f, 20.25f))),
            rootContentClip = Rect(0f, 0f, 32f, 35f),
        )
        assertEquals(Offset(-1.5f, -2.75f), result.paragraphPosition)
        assertEquals(Rect(8.5f, 17.25f, 32f, 35f), result.bounds)
    }

    @Test fun explicitRootContentClipRetainsNoSiblingOcclusionPolicy() {
        // A role-only child's own Surface clip is required even though sibling occlusion is not.
        val result = mapNativePlainTextGeometry(
            Offset.Zero, Rect(0f, 0f, 954f, 168f),
            listOf(layer(placement = Offset(445f, 458f))),
            rootContentClip = Rect(0f, 0f, 1080f, 2400f),
        )
        assertEquals(Rect(445f, 458f, 1080f, 626f), result.bounds)
        assertEquals(Offset(445f, 458f), result.paragraphPosition)
    }

    @Test fun explicitRootContentClipCanEmptyBoundsAtTheRootItself() {
        val result = mapNativePlainTextGeometry(
            Offset(40.5f, -1.25f), Rect(40f, 0f, 50f, 10f), emptyList(),
            rootContentClip = Rect(0f, 0f, 32f, 35f),
        )
        assertEquals(Rect.Zero, result.bounds)
        assertEquals(Offset(40.5f, -1.25f), result.paragraphPosition)
    }

    @Test fun snapshotCopiesMutableMatricesAndMapsWindowOffsetAfterTransform() {
        val transform = Matrix().apply { this[0, 0] = 2f; this[1, 1] = 3f }
        val screen = NativeTextRootToScreen(transform, Offset(0.25f, -0.5f))
        transform.reset()
        assertEquals(Offset(2.75f, 6.25f), screen.map(Offset(1.25f, 2.25f)))
    }

    @Test fun knownPreviewUsesExactPinnedPivotOperationOrder() {
        val transform = Matrix()
        populateOnboardingPreviewSemanticTransform(transform, 954, 1968,
            -45.79199981689453f, 0f, 0.9820000529289246f, 0.9820000529289246f)
        val origin = transform.map(Offset.Zero) + Offset(63f, 153f)
        // Retained source semantics origin; backend View.matrix instead produces25.7939682f.
        assertEquals(25.7939453125f, origin.x, 0f)
        assertEquals(170.71197509765625f, origin.y, 0f)
    }

    @Test fun unknownSemanticTransformUsesUnchangedBackendMatrix() {
        val target = Matrix()
        val backend = Matrix().apply { translate(3.125f, -4.75f); scale(2f, 3f) }
        var backendReads = 0
        populateNativeTextSemanticsTransform(target,
            sourceTransform = { it.translate(99f, 99f); false },
            backendTransform = { backendReads++; it.setFrom(backend) })
        assertEquals(1, backendReads)
        assertEquals(backend.map(Offset(2.5f, 1.25f)), target.map(Offset(2.5f, 1.25f)))
    }

    @Test fun knownSemanticTransformNeverReadsBackendFallback() {
        val target = Matrix()
        populateNativeTextSemanticsTransform(target,
            sourceTransform = { it.translate(7.25f, 2.5f); true },
            backendTransform = { error("Known source layer must not be replaced by backend math") })
        assertEquals(Offset(7.25f, 2.5f), target.map(Offset.Zero))
    }

    private fun layer(placement: Offset = Offset.Zero, clip: Rect? = null) =
        NativeTextGeometryLayer(Matrix(), placement, clip)
}
