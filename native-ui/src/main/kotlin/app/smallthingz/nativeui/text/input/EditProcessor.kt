/*
 * Copyright 2019 The Android Open Source Project
 * Copyright 2026 Reverb contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.smallthingz.nativeui.text.input

import app.smallthingz.nativeui.text.AnnotatedString
import app.smallthingz.nativeui.text.TextRange

/** Optional native IME observer; Reverb's InputConnection applies returned snapshots directly. */
fun interface TextInputSession { fun updateState(oldValue: TextFieldValue, newValue: TextFieldValue) }

/** Batches native IME commands without a composition, snapshot runtime, or rendering tree. */
class EditProcessor {
    private var state = TextFieldValue()
    private var buffer = EditingBuffer(AnnotatedString(""), TextRange.Zero)

    fun reset(value: TextFieldValue, textInputSession: TextInputSession? = null) {
        var textChanged = false
        var selectionChanged = false
        val compositionChanged = value.composition != buffer.composition
        if (state.text != value.text) {
            buffer = EditingBuffer(value.annotatedString, value.selection)
            textChanged = true
        } else if (state.selection != value.selection) {
            buffer.setSelection(value.selection.min, value.selection.max)
            selectionChanged = true
        }
        if (value.composition == null) buffer.commitComposition()
        else if (!value.composition.collapsed) buffer.setComposition(value.composition.min, value.composition.max)
        val next = if (textChanged || (!selectionChanged && compositionChanged)) {
            buffer.commitComposition()
            value.copy(composition = null)
        } else value
        val previous = state
        state = next
        textInputSession?.updateState(previous, next)
    }

    fun apply(editCommands: List<EditCommand>): TextFieldValue {
        var failed: EditCommand? = null
        try {
            for (index in editCommands.indices) {
                val command = editCommands[index]
                failed = command
                command.applyTo(buffer)
            }
        } catch (error: Exception) {
            throw RuntimeException(buildString {
                append("Error applying IME batch (length=").append(buffer.length)
                append(", composition=").append(buffer.composition)
                append(", selection=").append(buffer.selection).append("):")
                for (command in editCommands) {
                    append('\n').append(if (command === failed) " > " else "   ")
                    append(command.safeDescription())
                }
            }, error)
        }
        val selected = buffer.selection
        state = TextFieldValue(buffer.toAnnotatedString(),
            if (state.selection.reversed) TextRange(selected.max, selected.min) else selected,
            buffer.composition)
        return state
    }

    fun toTextFieldValue(): TextFieldValue = state
}

private fun EditCommand.safeDescription(): String = when (this) {
    is CommitTextCommand -> "CommitTextCommand(text.length=${text.length}, newCursorPosition=$newCursorPosition)"
    is SetComposingTextCommand -> "SetComposingTextCommand(text.length=${text.length}, newCursorPosition=$newCursorPosition)"
    is SetComposingRegionCommand, is SetSelectionCommand, is FinishComposingTextCommand,
    is DeleteSurroundingTextCommand, is DeleteSurroundingTextInCodePointsCommand -> toString()
    else -> "Unknown EditCommand: ${javaClass.simpleName}"
}
