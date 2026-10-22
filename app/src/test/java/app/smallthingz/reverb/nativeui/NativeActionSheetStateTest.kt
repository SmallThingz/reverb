package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.animation.core.snap
import app.smallthingz.nativeui.animation.core.tween
import app.smallthingz.nativeui.foundation.MutatePriority
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
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
    @Test fun blockedDismissalPreservesOpeningAndKeepsCloseTargetExpanded()=runBlocking {
        val state=NativeActionSheetState({1f},{},{false})
        state.measured(600,300)
        // A non-dismissible progress sheet must still open normally from Hidden.
        withContext(SteppingFrames()) {state.show();state.hide()}
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
        assertEquals(300f,state.offset,0f)
        assertFalse(state.animationRunning)
    }
    @Test fun blockedDismissalRejectsBothNestedAndDirectDragOffsets()=runBlocking {
        val state=NativeActionSheetState({1f},{},{false})
        state.measured(600,300)
        withContext(ImmediateFrame) {state.animateTo(NativeSheetValue.EXPANDED,snap())}
        assertEquals(0f,state.dispatchRawDelta(220f),0f)
        state.drag {dragTo(590f,900f)}
        assertEquals(300f,state.offset,0f)
        assertEquals(0f,state.lastVelocity,0f)
        withContext(SteppingFrames()) {state.settle(900f)}
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
    }
    @Test fun admissionChangingDuringDragRestoresTheVisibleSurfaceBeforeRelease()=runBlocking {
        var allowed=true
        val state=NativeActionSheetState({1f},{},{allowed})
        state.measured(600,300)
        withContext(ImmediateFrame) {state.animateTo(NativeSheetValue.EXPANDED,snap())}
        state.drag {
            dragTo(540f)
            allowed=false
            state.dismissalAdmissionChanged()
            assertEquals(300f,state.offset,0f)
            dragTo(580f)
        }
        withContext(SteppingFrames()) {state.settle(900f)}
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
        assertEquals(300f,state.offset,0f)
    }
    @Test fun busyAdmissionDuringHideCannotResumeAnObsoleteDismissAfterBecomingIdle()=runBlocking {
        var allowed=true
        var vetoObserved=false
        val vetoTargets=mutableListOf<NativeSheetValue>()
        lateinit var state:NativeActionSheetState
        state=NativeActionSheetState({1f},{if(vetoObserved)vetoTargets+=state.targetValue},{allowed})
        state.measured(600,300)
        withContext(ImmediateFrame) {state.animateTo(NativeSheetValue.EXPANDED,snap())}
        withContext(SteppingFrames { frame ->
            if(frame==1) {
                allowed=false;vetoObserved=true;state.dismissalAdmissionChanged()
                assertEquals(NativeSheetValue.EXPANDED,state.targetValue)
            }
            if(frame==2) {
                allowed=true
                assertEquals(NativeSheetValue.EXPANDED,state.targetValue)
            }
        }) {state.animateTo(NativeSheetValue.HIDDEN,tween(durationMillis=96))}
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
        assertEquals(300f,state.offset,0f)
        assertFalse(state.animationRunning)
        assertTrue(vetoTargets.isNotEmpty())
        assertTrue(vetoTargets.all {it==NativeSheetValue.EXPANDED})
        vetoObserved=false
        // A fresh dismissal after terminal failure/idle remains available.
        withContext(ImmediateFrame) {state.animateTo(NativeSheetValue.HIDDEN,snap())}
        assertEquals(NativeSheetValue.HIDDEN,state.currentValue)
        assertEquals(600f,state.offset,0f)
    }
    @Test fun liveAdmissionIsCheckedInsideHideEvenBeforeAnOwnerRebind()=runBlocking {
        var allowed=true
        var vetoObserved=false
        val vetoTargets=mutableListOf<NativeSheetValue>()
        lateinit var state:NativeActionSheetState
        state=NativeActionSheetState({1f},{if(vetoObserved)vetoTargets+=state.targetValue},{allowed})
        state.measured(600,300)
        withContext(ImmediateFrame) {state.animateTo(NativeSheetValue.EXPANDED,snap())}
        withContext(SteppingFrames {frame->
            if(frame==1) {allowed=false;vetoObserved=true}
            if(frame==2) {
                allowed=true
                assertEquals(NativeSheetValue.EXPANDED,state.targetValue)
            }
        }) {
            state.animateTo(NativeSheetValue.HIDDEN,tween(durationMillis=96))
        }
        assertEquals(NativeSheetValue.EXPANDED,state.currentValue)
        assertEquals(300f,state.offset,0f)
        assertTrue(vetoTargets.isNotEmpty())
        assertTrue(vetoTargets.all {it==NativeSheetValue.EXPANDED})
    }
    private class SteppingFrames(private val beforeFrame:(Int)->Unit={}):MonotonicFrameClock {
        private var frame=0
        override suspend fun <R> withFrameNanos(onFrame:(Long)->R):R {
            check(frame<1000) {"Sheet animation did not settle"}
            val current=frame++
            beforeFrame(current)
            return onFrame(current*16_000_000L)
        }
    }
    private object ImmediateFrame:MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame:(Long)->R):R=onFrame(0L)
    }
}
