package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class CompressionSliderModelTest {
    @Test fun pointerTieChoosesLowerTickWhileAccessibilityChoosesHigher() {
        val model=CompressionSliderModel(2).apply {width=104;thumbWidth=4}
        model.press(45.75f) // Range2..102; exactly halfway between ticks3 and4.
        assertEquals(3f,model.tap()!!,0f)
        assertEquals(4f,model.semanticProgress(3.5f)!!,0f)
    }

    @Test fun requestedEditsRemainControlledUntilOwnerBindsAcceptedLevel() {
        val model=CompressionSliderModel(2).apply {width=104;thumbWidth=4}
        model.press(102f)
        assertEquals(8f,model.tap()!!,0f)
        assertEquals(2f,model.value,0f)
        model.bind(8)
        model.press(102f)
        assertNull(model.tap())
    }

    @Test fun rtlMirrorsPointerAndDirectionalKeysButNotPageKeys() {
        val model=CompressionSliderModel(2).apply {width=104;thumbWidth=4;rtl=true}
        model.press(104f-45.75f)
        assertEquals(3f,model.tap()!!,0f)
        assertEquals(1f,model.key(CompressionSliderKey.RIGHT),0f)
        assertEquals(1f,model.key(CompressionSliderKey.UP),0f)
        assertEquals(3f,model.key(CompressionSliderKey.LEFT),0f)
        assertEquals(1f,model.key(CompressionSliderKey.PAGE_UP),0f)
        assertEquals(3f,model.key(CompressionSliderKey.PAGE_DOWN),0f)
    }

    @Test fun boundaryActionsDoNotInventOutOfRangeEdits() {
        val model=CompressionSliderModel(0)
        assertNull(model.semanticProgress(-3f))
        assertEquals(0f,model.key(CompressionSliderKey.LEFT),0f)
        assertEquals(8f,model.semanticProgress(99f)!!,0f)
        model.bind(8)
        assertNull(model.semanticProgress(9f))
        assertEquals(8f,model.key(CompressionSliderKey.RIGHT),0f)
    }

    @Test fun oddPixelThumbPreservesRelativePlacementAsymmetry() {
        val density=421f/160f
        val ltr=CompressionSliderGeometry.measure(421,density,3f/8,false,false)
        val rtl=CompressionSliderGeometry.measure(421,density,3f/8,false,true)
        assertEquals(11,ltr.thumbWidth)
        assertEquals(116,ltr.thumbHeight)
        assertEquals(126,ltr.outerHeight)
        assertEquals(5,ltr.visualTop)
        assertEquals(410,ltr.trackWidth)
        assertEquals(42,ltr.trackHeight)
        assertEquals(159,ltr.thumbX)
        assertEquals(251,rtl.thumbX)
        assertEquals(5,ltr.trackX)
        assertEquals(6,rtl.trackX)
    }

    @Test fun endpointsBypassInteriorCornerOffsetAndDraggingUsesNarrowHandle() {
        val start=CompressionSliderGeometry.measure(100,1f,0f,false,false)
        val end=CompressionSliderGeometry.measure(100,1f,1f,false,false)
        val drag=CompressionSliderGeometry.measure(100,1f,.5f,true,false)
        assertEquals(0,start.thumbX)
        assertEquals(96,end.thumbX)
        assertEquals(2,drag.thumbWidth)
        assertEquals(98,drag.trackWidth)
        assertEquals(48,drag.outerHeight)
    }
}
