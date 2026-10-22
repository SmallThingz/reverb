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
import app.smallthingz.nativeui.text.coerceIn

/** Immutable plain-text IME snapshot. Native View owners persist their own drafts. */
class TextFieldValue(
    val annotatedString: AnnotatedString,
    selection: TextRange = TextRange.Zero,
    composition: TextRange? = null,
) {
    constructor(text: String = "", selection: TextRange = TextRange.Zero, composition: TextRange? = null) :
        this(AnnotatedString(text), selection, composition)

    val text: String get() = annotatedString.text
    val selection: TextRange = selection.coerceIn(0, text.length)
    val composition: TextRange? = composition?.coerceIn(0, text.length)

    fun copy(
        annotatedString: AnnotatedString = this.annotatedString,
        selection: TextRange = this.selection,
        composition: TextRange? = this.composition,
    ) = TextFieldValue(annotatedString, selection, composition)

    fun copy(text: String, selection: TextRange = this.selection, composition: TextRange? = this.composition) =
        TextFieldValue(text, selection, composition)

    override fun equals(other: Any?): Boolean = this === other || other is TextFieldValue &&
        selection == other.selection && composition == other.composition && annotatedString == other.annotatedString

    override fun hashCode(): Int = 31 * (31 * annotatedString.hashCode() + selection.hashCode()) +
        (composition?.hashCode() ?: 0)

    override fun toString() = "TextFieldValue(text='$annotatedString', selection=$selection, composition=$composition)"
}

fun TextFieldValue.getTextBeforeSelection(maxChars: Int): AnnotatedString =
    annotatedString.subSequence(maxOf(0, selection.min.subtractExactOrElse(maxChars) { 0 }), selection.min)

fun TextFieldValue.getTextAfterSelection(maxChars: Int): AnnotatedString =
    annotatedString.subSequence(selection.max,
        minOf(selection.max.addExactOrElse(maxChars) { text.length }, text.length))

fun TextFieldValue.getSelectedText(): AnnotatedString =
    annotatedString.subSequence(selection.min, selection.max)

internal inline fun Int.addExactOrElse(right: Int, defaultValue: () -> Int): Int {
    val result = this + right
    return if (((this xor result) and (right xor result)) < 0) defaultValue() else result
}

internal inline fun Int.subtractExactOrElse(right: Int, defaultValue: () -> Int): Int {
    val result = this - right
    return if (((this xor right) and (this xor result)) < 0) defaultValue() else result
}
