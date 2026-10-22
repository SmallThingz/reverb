package app.smallthingz.reverb.nativeui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import app.smallthingz.nativeui.text.TextRange
import app.smallthingz.nativeui.text.input.CommitTextCommand
import app.smallthingz.nativeui.text.input.EditCommand
import app.smallthingz.nativeui.text.input.EditProcessor
import app.smallthingz.nativeui.text.input.SetSelectionCommand
import app.smallthingz.nativeui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEditableConnectionActionsTest {
    @Test fun selectAllJoinsPendingCommandsAndUsesTheCurrentPublishedTextLength() {
        val initial = TextFieldValue("draft", TextRange(1))
        val pending = mutableListOf<EditCommand>(CommitTextCommand("X", 1))
        val processor = EditProcessor().apply { reset(initial, null) }
        val actions = NativeEditableConnectionActions(
            active = { true },
            currentTextLength = { initial.text.length },
            queueSelectAll = { pending += SetSelectionCommand(0, it) },
            sendKey = { _, _ -> error("Select all must not fall through to native keys") },
            onEditorAction = { error("Select all must not invoke an editor action") },
        )

        // RecordingInputConnection queues this operation and returns false even when handled.
        assertFalse(actions.performContextMenuAction(android.R.id.selectAll))
        assertEquals(initial, processor.toTextFieldValue())
        val committed = processor.apply(pending)
        assertEquals("dXraft", committed.text)
        assertEquals(TextRange(0, 5), committed.selection)
    }

    @Test fun closedConnectionDoesNotReadStateOrForwardAnyAction() {
        var active = true
        var callbacks = 0
        val actions = NativeEditableConnectionActions(
            active = { active },
            currentTextLength = { callbacks++; 5 },
            queueSelectAll = { callbacks++ },
            sendKey = { _, _ -> callbacks++ },
            onEditorAction = { callbacks++ },
        )
        assertTrue(actions.performEditorAction(EditorInfo.IME_ACTION_DONE))
        assertEquals(1, callbacks)
        active = false
        assertFalse(actions.performEditorAction(EditorInfo.IME_ACTION_DONE))
        assertFalse(actions.performContextMenuAction(android.R.id.selectAll))
        assertFalse(actions.performContextMenuAction(android.R.id.cut))
        assertFalse(actions.sendKeyEvent { callbacks++ })
        assertFalse(actions.performPrivateCommand())
        assertEquals(1, callbacks)
    }

    @Test fun clipboardContextCommandsDeliverOneSourceKeyPairAndReturnFalse() {
        val delivered = mutableListOf<Pair<Int, Int>>()
        val actions = NativeEditableConnectionActions(
            active = { true }, currentTextLength = { 0 },
            queueSelectAll = { error("Clipboard commands must not select all") },
            sendKey = { action, code -> delivered += action to code },
            onEditorAction = { error("Clipboard commands must not submit") },
        )
        for ((id, key) in listOf(
            android.R.id.cut to KeyEvent.KEYCODE_CUT,
            android.R.id.copy to KeyEvent.KEYCODE_COPY,
            android.R.id.paste to KeyEvent.KEYCODE_PASTE,
        )) {
            delivered.clear()
            assertFalse(actions.performContextMenuAction(id))
            assertEquals(listOf(KeyEvent.ACTION_DOWN to key, KeyEvent.ACTION_UP to key), delivered)
        }
    }

    @Test fun editorActionMappingDoesNotInventDoneForUnknownActions() {
        val delivered = mutableListOf<Int>()
        val actions = NativeEditableConnectionActions(
            active = { true }, currentTextLength = { 0 }, queueSelectAll = {},
            sendKey = { _, _ -> }, onEditorAction = delivered::add,
        )
        for (action in listOf(EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS, EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO)) {
            assertTrue(actions.performEditorAction(action))
            assertEquals(action, delivered.last())
        }
        assertTrue(actions.performEditorAction(-123))
        assertEquals(EditorInfo.IME_ACTION_UNSPECIFIED, delivered.last())
        assertTrue(actions.performPrivateCommand())
    }
}
