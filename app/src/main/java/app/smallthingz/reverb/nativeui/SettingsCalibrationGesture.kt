package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.nativeui.geometry.Offset

/** Exact Initial/Final detector from released SkipSilenceSettings176–239; callback updates keep it. */
internal class SettingsCalibrationGesture(
    private val canBegin:()->Boolean,
    private val begin:()->Boolean,
    private val tap:()->Unit,
    private val finish:()->Unit,
    private val cancelSample:()->Unit,
    private val pointerChanged:(Boolean)->Unit,
    private val width:()->Int,
    private val height:()->Int,
    private val slop:()->Float,
    private val longPressMillis:()->Long,
) {
    private var pointer:Long?=null
    private var downPosition=Offset.Zero
    private var downTime=0L
    private var owns=false
    private var cancelled=false
    private var awaitAllUp=false
    private var checkFinal=false
    val pointerDown get()=pointer!=null

    fun dispatch(event:NativeDragPointerEvent,pass:NativePointerPass) {
        if(pass==NativePointerPass.MAIN)return
        if(awaitAllUp) {
            if(event.changes.none {it.pressed})awaitAllUp=false
            return
        }
        val id=pointer
        if(id==null) {
            if(pass!=NativePointerPass.INITIAL||event.changes.isEmpty()||
                event.changes.any {!it.changedToDown||it.consumed})return
            if(!canBegin()) {awaitAllUp=true;return}
            val down=event.changes.first()
            pointer=down.id;downPosition=down.position;downTime=down.timeMillis
            cancelled=false;owns=false;checkFinal=false
            pointerChanged(true)
            try {owns=begin();cancelled=!owns}
            catch(failure:Throwable) {retire(false);throw failure}
            return
        }
        val change=event.changes.firstOrNull {it.id==id}
        if(pass==NativePointerPass.FINAL) {
            if(checkFinal&&change!=null&&change.consumed&&change.position!=change.previousPosition)cancelOwned()
            return
        }
        checkFinal=false
        if(change==null) {cancelOwned();retire(false);awaitAllUp=event.changes.any {it.pressed};return}
        if(change.consumed||event.changes.any {it.id!=id&&it.pressed}||
            change.position.x<0f||change.position.y<0f||change.position.x>width()||change.position.y>height()||
            (change.position-downPosition).getDistance()>slop())cancelOwned()
        if(!change.pressed) {
            change.consume() // Suppress TextButton click after both accepted and cancelled presses.
            try {
                if(!cancelled) {
                    owns=false
                    if(change.timeMillis-downTime>=longPressMillis())finish()else tap()
                }
            }finally {retire(true);awaitAllUp=event.changes.any {it.pressed}}
        }else checkFinal=true
    }

    private fun cancelOwned() {
        if(cancelled)return
        cancelled=true
        if(owns) {owns=false;cancelSample()}
    }
    private fun retire(released:Boolean) {
        try {if(!released)cancelOwned()}
        finally {pointer=null;checkFinal=false;pointerChanged(false)}
    }
    fun cancel() {
        if(pointer!=null)retire(false)
        awaitAllUp=false;checkFinal=false
    }
}
