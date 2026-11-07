/*
 * Command/action policy from AndroidX foundation/ui-text1.11.2 RecordingInputConnection.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.view.KeyEvent

/** Android's source default mapping admits Enter with None/Shift/Ctrl/CtrlShift, never Alt/Meta. */
internal fun nativeSingleLineEnterCommand(keyCode:Int,alt:Boolean,meta:Boolean):Boolean =
    (keyCode==KeyEvent.KEYCODE_ENTER||keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER)&&!alt&&!meta
