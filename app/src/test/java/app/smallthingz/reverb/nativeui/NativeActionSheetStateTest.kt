package app.smallthingz.reverb.nativeui

import androidx.compose.animation.core.snap
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class NativeActionSheetStateTest {
    private fun state(density:Float=1f)=NativeActionSheetState({density},{})
    @Test fun hiddenAndExpandedAnchorsUseAvailableHeightAndMeasuredContent() {
        val state=state()
        state.measured(600,240)
        assertEquals(NativeSheetValue.HIDDEN,state.currentValue)
        assertEquals(600f,state.offset,0f)
        assertEquals(360f,state.anchors!!.expanded!!,0f)
        state.measured(600,800)
        assertEquals(0f,state.anchors!!.expanded!!,0f)
        state.measured(600,0)
        assertNull(state.anchors!!.expanded)
    }
    @Test fun positionalBoundaryIsInclusiveAtFiftySixDensityPixels() {
        val state=state(2f)
        state.measured(1000,600)
        assertEquals(NativeSheetValue.EXPANDED,state.computeTarget(511.99f,NativeSheetValue.EXPANDED,0f))
        assertEquals(NativeSheetValue.HIDDEN,state.computeTarget(512f,NativeSheetValue.EXPANDED,0f))
        assertEquals(NativeSheetValue.HIDDEN,state.computeTarget(888.01f,NativeSheetValue.HIDDEN,0f))
        assertEquals(NativeSheetValue.EXPANDED,state.computeTarget(888f,NativeSheetValue.HIDDEN,0f))
    }
    @Test fun velocityBoundaryUsesDirectionAndIsInclusive() {
        val state=state()
        state.measured(600,300)
        assertEquals(NativeSheetValue.EXPANDED,state.computeTarget(301f,NativeSheetValue.EXPANDED,124.99f))
        assertEquals(NativeSheetValue.HIDDEN,state.computeTarget(301f,NativeSheetValue.EXPANDED,125f))
        assertEquals(NativeSheetValue.HIDDEN,state.computeTarget(599f,NativeSheetValue.HIDDEN,-124.99f))
        assertEquals(NativeSheetValue.EXPANDED,state.computeTarget(599f,NativeSheetValue.HIDDEN,-125f))
    }
    @Test fun anchorTieRetainsInsertionOrderAndDirectionalSearchDoesNotChooseBehindPointer() {
        val anchors=NativeSheetAnchors(600f,300f)
        assertEquals(NativeSheetValue.HIDDEN,anchors.nearest(450f))
        assertEquals(NativeSheetValue.HIDDEN,anchors.nearest(301f,true))
        assertEquals(NativeSheetValue.EXPANDED,anchors.nearest(599f,false))
    }
    @Test fun rawNestedDeltaClampsAndDoesNotInventSettlement() {
        val state=state()
        state.measured(600,300)
        assertEquals(-300f,state.dispatchRawDelta(-400f),0f)
        assertEquals(300f,state.offset,0f)
        assertEquals(NativeSheetValue.HIDDEN,state.currentValue)
        assertEquals(0f,state.lastVelocity,0f)
        assertEquals(0f,state.dispatchRawDelta(-5f),0f)
        assertEquals(300f,state.dispatchRawDelta(400f),0f)
    }
    @Test fun explicitMissingAnchorUpdatesCurrentWithoutInventingOffset()=runBlocking {
        val state=state()
        state.animateTo(NativeSheetValue.EXPANDED,snap())
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
        assertTrue(state.offset.isNaN())
        state.measured(600,200)
        assertEquals(400f,state.offset,0f)
    }
    @Test fun exactSnapSettlesAndSubsequentMeasurePreservesExpandedTarget()=runBlocking {
        val state=state()
        state.measured(600,200)
        withContext(ImmediateFrame) {state.animateTo(NativeSheetValue.EXPANDED,snap())}
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
        assertEquals(400f,state.offset,0f)
        assertFalse(state.animationRunning)
        state.measured(800,300)
        assertEquals(500f,state.offset,0f)
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
    }
    @Test fun userMutationRejectsLowerPriorityAnimationWithoutEndingTheUser()=runBlocking {
        val state=state()
        state.measured(600,300)
        val drag=launch(start=CoroutineStart.UNDISPATCHED) {
            state.drag(MutatePriority.UserInput) {dragTo(450f);awaitCancellation()}
        }
        var rejected=false
        try {state.animateTo(NativeSheetValue.EXPANDED,snap())}
        catch(_:CancellationException) {rejected=true}
        assertTrue(rejected);assertTrue(drag.isActive)
        assertEquals(450f,state.offset,0f)
        drag.cancelAndJoin()
    }
    @Test fun cancelledDragOnlySettlesWithinHalfPixelOfAnAnchor()=runBlocking {
        val state=state()
        state.measured(600,300)
        val close=launch(start=CoroutineStart.UNDISPATCHED) {
            state.drag {dragTo(300.5f);awaitCancellation()}
        }
        close.cancelAndJoin()
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
        val distant=launch(start=CoroutineStart.UNDISPATCHED) {
            state.drag {dragTo(599.49f);awaitCancellation()}
        }
        distant.cancelAndJoin()
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
    }
    @Test fun nestedConnectionConsumesOnlySourceUserInputAndCorrectFlingDirection()=runBlocking {
        val state=state()
        state.measured(600,300)
        val flings=mutableListOf<Float>()
        val nested=NativeActionSheetNestedScroll(state) {flings+=it}
        assertEquals(0f,nested.preScroll(-100f,NativeVerticalScrollSource.SIDE_EFFECT),0f)
        assertEquals(-100f,nested.preScroll(-100f,NativeVerticalScrollSource.USER_INPUT),0f)
        assertEquals(0f,nested.preScroll(100f,NativeVerticalScrollSource.USER_INPUT),0f)
        assertEquals(100f,nested.postScroll(0f,100f,NativeVerticalScrollSource.USER_INPUT),0f)
        assertEquals(-500f,nested.preFling(-500f),0f)
        assertEquals(listOf(-500f),flings)
        assertEquals(0f,nested.preFling(500f),0f)
        assertEquals(250f,nested.postFling(0f,250f),0f)
        assertEquals(listOf(-500f,250f),flings)
    }
    private object ImmediateFrame:MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame:(Long)->R):R=onFrame(0L)
    }
}
