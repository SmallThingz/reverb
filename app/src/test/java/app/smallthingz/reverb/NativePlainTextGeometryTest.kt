package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.populateOnboardingPreviewSemanticTransform
import smallthingz.ui.geometry.Offset
import smallthingz.ui.graphics.Matrix
import org.junit.Assert.assertEquals
import org.junit.Test

/** App-specific onboarding transform remains independently pinned after generic geometry moves. */
class NativePlainTextGeometryTest {
    @Test fun knownPreviewUsesExactPinnedPivotOperationOrder() {
        val transform = Matrix()
        populateOnboardingPreviewSemanticTransform(transform, 954, 1968,
            -45.79199981689453f, 0f, 0.9820000529289246f, 0.9820000529289246f)
        val origin = transform.map(Offset.Zero) + Offset(63f, 153f)
        // Retained source semantics origin; backend View.matrix instead produces25.7939682f.
        assertEquals(25.7939453125f, origin.x, 0f)
        assertEquals(170.71197509765625f, origin.y, 0f)
    }
}
