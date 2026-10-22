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

/** Native InputConnection operations queued until the enclosing IME batch finishes. */
interface EditCommand { fun applyTo(buffer: EditingBuffer) }

data class CommitTextCommand(val annotatedString: AnnotatedString, val newCursorPosition: Int) : EditCommand {
    constructor(text: String, newCursorPosition: Int) : this(AnnotatedString(text), newCursorPosition)
    val text: String get() = annotatedString.text
    override fun applyTo(buffer: EditingBuffer) {
        if (buffer.hasComposition()) buffer.replace(buffer.compositionStart, buffer.compositionEnd, text)
        else buffer.replace(buffer.selectionStart, buffer.selectionEnd, text)
        buffer.placeCursorAfterInsertion(newCursorPosition, text.length)
    }
}

data class SetComposingTextCommand(val annotatedString: AnnotatedString, val newCursorPosition: Int) : EditCommand {
    constructor(text: String, newCursorPosition: Int) : this(AnnotatedString(text), newCursorPosition)
    val text: String get() = annotatedString.text
    override fun applyTo(buffer: EditingBuffer) {
        val start = if (buffer.hasComposition()) buffer.compositionStart else buffer.selectionStart
        val end = if (buffer.hasComposition()) buffer.compositionEnd else buffer.selectionEnd
        buffer.replace(start, end, text)
        if (text.isNotEmpty()) buffer.setComposition(start, start + text.length)
        buffer.placeCursorAfterInsertion(newCursorPosition, text.length)
    }
}

private fun EditingBuffer.placeCursorAfterInsertion(position: Int, insertedLength: Int) {
    // Keep the released cursor arithmetic, including its Int ordering, before clamping.
    val result = if (position > 0) cursor + position - 1 else cursor + position - insertedLength
    cursor = result.coerceIn(0, length)
}

data class SetComposingRegionCommand(val start: Int, val end: Int) : EditCommand {
    override fun applyTo(buffer: EditingBuffer) {
        buffer.commitComposition()
        val a = start.coerceIn(0, buffer.length)
        val b = end.coerceIn(0, buffer.length)
        if (a != b) buffer.setComposition(minOf(a, b), maxOf(a, b))
    }
}

data class SetSelectionCommand(val start: Int, val end: Int) : EditCommand {
    override fun applyTo(buffer: EditingBuffer) {
        val a = start.coerceIn(0, buffer.length)
        val b = end.coerceIn(0, buffer.length)
        buffer.setSelection(minOf(a, b), maxOf(a, b))
    }
}

class FinishComposingTextCommand : EditCommand {
    override fun applyTo(buffer: EditingBuffer) = buffer.commitComposition()
    override fun equals(other: Any?) = other is FinishComposingTextCommand
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "FinishComposingTextCommand()"
}

data class DeleteSurroundingTextCommand(val lengthBeforeCursor: Int, val lengthAfterCursor: Int) : EditCommand {
    init { requireNonnegativeLengths(lengthBeforeCursor, lengthAfterCursor) }
    override fun applyTo(buffer: EditingBuffer) {
        val end = buffer.selectionEnd.addExactOrElse(lengthAfterCursor) { buffer.length }
        buffer.delete(buffer.selectionEnd, minOf(end, buffer.length))
        val start = buffer.selectionStart.subtractExactOrElse(lengthBeforeCursor) { 0 }
        buffer.delete(maxOf(0, start), buffer.selectionStart)
    }
}

data class DeleteSurroundingTextInCodePointsCommand(
    val lengthBeforeCursor: Int,
    val lengthAfterCursor: Int,
) : EditCommand {
    init { requireNonnegativeLengths(lengthBeforeCursor, lengthAfterCursor) }
    override fun applyTo(buffer: EditingBuffer) {
        // Match the released algorithm, including lone-surrogate handling. Its documentation
        // promises more validation than its implementation; changing that would alter IME behavior.
        var before = 0
        for (i in 0 until lengthBeforeCursor) {
            before++
            if (buffer.selectionStart > before) {
                if (buffer[buffer.selectionStart - before - 1].isHighSurrogate() &&
                    buffer[buffer.selectionStart - before].isLowSurrogate()) before++
            } else { before = buffer.selectionStart; break }
        }
        var after = 0
        for (i in 0 until lengthAfterCursor) {
            after++
            if (buffer.selectionEnd + after < buffer.length) {
                if (buffer[buffer.selectionEnd + after - 1].isHighSurrogate() &&
                    buffer[buffer.selectionEnd + after].isLowSurrogate()) after++
            } else { after = buffer.length - buffer.selectionEnd; break }
        }
        buffer.delete(buffer.selectionEnd, buffer.selectionEnd + after)
        buffer.delete(buffer.selectionStart - before, buffer.selectionStart)
    }
}

private fun requireNonnegativeLengths(before: Int, after: Int) {
    require(before >= 0 && after >= 0) {
        "Expected non-negative surrounding lengths, were $before and $after"
    }
}
