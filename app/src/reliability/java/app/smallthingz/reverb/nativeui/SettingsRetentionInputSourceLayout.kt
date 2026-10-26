/*
 * Editing/IME contracts adapted from AndroidX foundation/ui-text 1.11.2.
 * Copyright 2021 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package app.smallthingz.reverb.nativeui

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import smallthingz.ui.text.AnnotatedString
import smallthingz.ui.text.MultiParagraph
import smallthingz.ui.text.MultiParagraphIntrinsics
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.TextLayoutInput
import smallthingz.ui.text.TextLayoutResult
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.resolveDefaults
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.unit.Constraints
import smallthingz.ui.unit.IntSize
import smallthingz.ui.unit.LayoutDirection
import kotlin.math.ceil

/** Exact owner-state descriptors; a renamed or changed production member fails the QA probe. */
private object RetentionLayoutOwner {
    private val owner = SettingsRetentionInputView::class.java
    val environment = field("environment", ReverbViewEnvironment::class.java)
    val minimumParagraph = field("minimumParagraph", Paragraph::class.java)
    val currentStyle = method("currentStyle", TextStyle::class.java)
    val direction = method("direction", LayoutDirection::class.java)

    private fun field(name: String, type: Class<*>) = owner.getDeclaredField(name).apply {
        check(this.type == type) { "Unexpected retention field type: $name" }
        isAccessible = true
    }

    private fun method(name: String, type: Class<*>) = owner.getDeclaredMethod(name).apply {
        check(returnType == type) { "Unexpected retention method return type: $name" }
        isAccessible = true
    }

    fun invoke(method: Method, receiver: SettingsRetentionInputView): Any? = try {
        method.invoke(receiver)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}

/**
 * Source layout for the finite QA probe, including newline/fallback geometry. This is NOT
 * substituted for native Editor metrics in the parity report and cannot make a mismatch pass.
 */
internal fun SettingsRetentionInputView.sourceLayout(containerWidth: Int = width): TextLayoutResult {
    val environment = RetentionLayoutOwner.environment.get(this) as ReverbViewEnvironment
    val minimumParagraph = RetentionLayoutOwner.minimumParagraph.get(this) as Paragraph?
    fun currentStyle(): TextStyle =
        RetentionLayoutOwner.invoke(RetentionLayoutOwner.currentStyle, this) as TextStyle
    fun direction(): LayoutDirection =
        RetentionLayoutOwner.invoke(RetentionLayoutOwner.direction, this) as LayoutDirection
    val value = AnnotatedString(text.toString())
    val style = currentStyle()
    val resolved = resolveDefaults(style, direction())
    val intrinsics = MultiParagraphIntrinsics(value, resolved, emptyList(),
        environment.textDensity, environment.sharedFontResolver)
    val sourceWidth = maxOf(containerWidth, ceil(intrinsics.maxIntrinsicWidth).toInt())
    val constraints = Constraints.fitPrioritizingWidth(minWidth = containerWidth, maxWidth = sourceWidth,
        minHeight = ceil(checkNotNull(minimumParagraph).height).toInt(), maxHeight = Constraints.Infinity)
    val paragraph = MultiParagraph(intrinsics,
        Constraints.fitPrioritizingWidth(minWidth = 0, maxWidth = sourceWidth,
            minHeight = 0, maxHeight = Constraints.Infinity), maxLines = 1, overflow = TextOverflow.Clip)
    return TextLayoutResult(TextLayoutInput(value, style, emptyList(), 1, false,
        TextOverflow.Clip, environment.textDensity, direction(),
        environment.sharedFontResolver, constraints), paragraph,
        IntSize(sourceWidth, maxOf(constraints.minHeight, ceil(paragraph.height).toInt())))
}
