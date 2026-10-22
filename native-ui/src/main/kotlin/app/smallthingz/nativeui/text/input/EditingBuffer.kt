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

/** One batch's owned plain-text buffer; no rendering state, observers or platform spans. */
class EditingBuffer internal constructor(text: AnnotatedString, selection: TextRange) {
    private val content = StringBuilder(text.text)
    internal var selectionStart = selection.min
        private set
    internal var selectionEnd = selection.max
        private set
    internal var compositionStart = -1
        private set
    internal var compositionEnd = -1
        private set
    internal val length: Int get() = content.length
    internal val selection: TextRange get() = TextRange(selectionStart, selectionEnd)
    internal val composition: TextRange?
        get() = if (hasComposition()) TextRange(compositionStart, compositionEnd) else null
    internal var cursor: Int
        get() = if (selectionStart == selectionEnd) selectionEnd else -1
        set(value) = setSelection(value, value)

    init { checkRange(selection.min, selection.max) }

    internal fun hasComposition() = compositionStart != -1
    internal operator fun get(index: Int) = content[index]

    internal fun replace(start: Int, end: Int, value: String) {
        checkRange(start, end)
        content.replace(start, end, value)
        selectionStart = start + value.length
        selectionEnd = selectionStart
        commitComposition()
    }

    internal fun delete(start: Int, end: Int) {
        checkRange(start, end)
        val deleted = TextRange(start, end)
        content.delete(start, end)
        val selected = updateRangeAfterDelete(selection, deleted)
        selectionStart = selected.min
        selectionEnd = selected.max
        composition?.let {
            val updated = updateRangeAfterDelete(it, deleted)
            if (updated.collapsed) commitComposition() else {
                compositionStart = updated.min
                compositionEnd = updated.max
            }
        }
    }

    internal fun setSelection(start: Int, end: Int) {
        checkRange(start, end)
        selectionStart = start
        selectionEnd = end
    }

    internal fun setComposition(start: Int, end: Int) {
        checkRange(start, end)
        require(start != end) { "Do not set an empty composition range: $start" }
        compositionStart = start
        compositionEnd = end
    }

    internal fun commitComposition() {
        compositionStart = -1
        compositionEnd = -1
    }

    private fun checkRange(start: Int, end: Int) {
        if (start !in 0..length) throw IndexOutOfBoundsException("start ($start) outside text length $length")
        if (end !in 0..length) throw IndexOutOfBoundsException("end ($end) outside text length $length")
        require(start <= end) { "Do not set reversed range: $start > $end" }
    }

    internal fun toAnnotatedString() = AnnotatedString(content.toString())
    override fun toString(): String = content.toString()
}

/** Selection/composition endpoints follow deletion, rather than moving to its end. */
internal fun updateRangeAfterDelete(target: TextRange, deleted: TextRange): TextRange {
    var start = target.min
    var end = target.max
    if (deleted.intersects(target)) {
        when {
            target in deleted -> { start = deleted.min; end = start }
            deleted in target -> end -= deleted.length
            start in deleted -> { start = deleted.min; end -= deleted.length }
            else -> end = deleted.min
        }
    } else if (end > deleted.min) {
        start -= deleted.length
        end -= deleted.length
    }
    return TextRange(start, end)
}
