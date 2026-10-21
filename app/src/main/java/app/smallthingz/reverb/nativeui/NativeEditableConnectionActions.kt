/*
 * Command/action policy from AndroidX foundation/ui-text1.11.2 RecordingInputConnection.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * No native Editor fallback for source-owned actions. The caller supplies its existing batch queue
 * and key/IME dispatch, so a context selection cannot bypass pending EditCommands.
 */
internal class NativeEditableConnectionActions(
    private val active:()->Boolean,
    private val currentTextLength:()->Int,
    private val queueSelectAll:(Int)->Unit,
    private val sendKey:(action:Int,keyCode:Int)->Unit,
    private val onEditorAction:(Int)->Unit,
) {
    fun performContextMenuAction(id:Int):Boolean {
        if(!active())return false
        when(id) {
            android.R.id.selectAll->queueSelectAll(currentTextLength())
            android.R.id.cut->synthesizedKey(KeyEvent.KEYCODE_CUT)
            android.R.id.copy->synthesizedKey(KeyEvent.KEYCODE_COPY)
            android.R.id.paste->synthesizedKey(KeyEvent.KEYCODE_PASTE)
        }
        return false // Pinned RecordingInputConnection returns false even for handled context actions.
    }
    private fun synthesizedKey(code:Int) {
        sendKey(KeyEvent.ACTION_DOWN,code)
        sendKey(KeyEvent.ACTION_UP,code)
    }
    fun performEditorAction(action:Int):Boolean {
        if(!active())return false
        val mapped=when(action) {
            EditorInfo.IME_ACTION_DONE,EditorInfo.IME_ACTION_SEND,EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_PREVIOUS,EditorInfo.IME_ACTION_NEXT,EditorInfo.IME_ACTION_GO->action
            else->EditorInfo.IME_ACTION_UNSPECIFIED
        }
        onEditorAction(mapped)
        return true
    }
    fun sendKeyEvent(deliver:()->Unit):Boolean {
        if(!active())return false
        deliver()
        return true // Source eventCallback.onKeyEvent has no returned platform ownership result.
    }
    fun performPrivateCommand():Boolean=active() // Source acknowledges unknown commands without executing them.
}

/** Android's source default mapping admits Enter with None/Shift/Ctrl/CtrlShift, never Alt/Meta. */
internal fun nativeSingleLineEnterCommand(keyCode:Int,alt:Boolean,meta:Boolean):Boolean =
    (keyCode==KeyEvent.KEYCODE_ENTER||keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER)&&!alt&&!meta
