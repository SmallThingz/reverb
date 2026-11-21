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

import smallthingz.ui.components.input.NativeEditableState
import smallthingz.ui.components.input.NativeQueuedInputConnection

import smallthingz.ui.animation.view.ViewColorTransition

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import smallthingz.ui.text.RetentionLineHeightSpan
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.resolveDefaults
import smallthingz.ui.text.resolveEditorMetrics
import smallthingz.ui.unit.LayoutDirection
import androidx.core.view.inputmethod.EditorInfoCompat
import app.smallthingz.reverb.RetentionMode

/**
 * ISOLATED CANDIDATE, not a qualified replacement or a production route.
 *
 * This is the actual native editable/IME owner, not an invisible EditText behind a renderer.
 * It preserves unrestricted raw input, the released String callback contract, source keyboard
 * hints and keyed focus request. It does not own Settings validation, persistence or mode drafts.
 *
 * Exact text/cursor/selection raster and native Editor handle/toolbar/undo/key behavior still need
 * the accompanying A/B probe. API28 cursor styling uses its public constructor resource path;
 * handle drawable setters remain API29+. No reflection or private API substitutes are used.
 * The reliability probe exposes pinned paragraph geometry so a mismatch is measurable rather than
 * hidden by baseline nudges. If native Editor cannot reproduce that geometry, replace its drawing
 * and selection ownership explicitly before any production integration.
 */
internal class SettingsRetentionInputView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
) : ReverbEditableInputView(context,
    initialEnvironment.appearance.editorVisuals.cursorWidthDp * initialEnvironment.textDensity.density,
    initialEnvironment.palette.primary) {
    private var environment = initialEnvironment
    private val colorMotion:ViewColorTransition by lazy {ViewColorTransition(this,
        intArrayOf(initialEnvironment.palette.ink,initialEnvironment.palette.primary),::applyColors)}
    protected override var initialized = false
        private set
    private var mode = RetentionMode.TIME
    private var focusKey: Boolean? = null
    private var focusPending = false
    private var appliedTypeface: Typeface? = null
    private var appliedEnvironment: ReverbViewEnvironment? = null
    private var minimumParagraph: Paragraph? = null
    private var lineHeightSpan: RetentionLineHeightSpan? = null
    protected override val editing = NativeEditableState(this, ::installLineHeightSpan)

    private val focusRequest = Runnable {
        if (!focusPending) return@Runnable
        focusPending = false
        if (isAttachedToWindow) requestFocus()
    }

    init {
        editing.initializeUnrestrictedSingleLineEditor()
        applyImeHints()
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                // Pinned default Done hides the keyboard; it does not clear field focus.
                context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(windowToken, 0)
                true
            } else false
        }
        initialized = true
        applyTypography()
        observeNativeEdits()
    }

    fun bind(
        rawString: String,
        mode: RetentionMode,
        bufferLabel: String,
        environment: ReverbViewEnvironment,
        requestFocus: Boolean,
        readCurrentRaw: () -> String,
        onRawChanged: (String) -> Unit,
    ) {
        editing.bindCallbacks(readCurrentRaw, onRawChanged)
        this.environment = environment
        contentDescription = bufferLabel
        val modeChanged = this.mode != mode
        this.mode = mode
        applyTypography()
        applyImeHints()
        editing.acceptOwnerText(rawString)
        if (modeChanged && isFocused) {
            context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
        // Corresponds to LaunchedEffect(requestFocus), not an on-layout/on-resume focus loop.
        if (focusKey != requestFocus) {
            focusKey = requestFocus
            removeCallbacks(focusRequest)
            focusPending = requestFocus
            if (focusPending && isAttachedToWindow) post(focusRequest)
        }
    }

    /** Explicit owner disposal. A temporary detach alone must not discard current callbacks. */
    fun releaseCallbacks() {
        removeCallbacks(focusRequest)
        focusPending = false
        editing.releaseCallbacks()
    }

    override fun beforeEditorTextDraw() = publishTextInk()

    private fun applyImeHints() {
        val hints = retentionImeContract(mode)
        // Keep the unrestricted key listener installed above; never use setInputType here.
        setRawInputType(hints.inputType)
        imeOptions = hints.imeOptions
        transformationMethod = null
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        val hints = retentionImeContract(mode)
        outAttrs.inputType = hints.inputType
        outAttrs.imeOptions = hints.imeOptions // Do not inherit native NEXT/PREVIOUS flags.
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd
        EditorInfoCompat.setInitialSurroundingText(outAttrs, text.toString())
        // KeyboardOptions defaults autoCorrect to true for both modes. Decimal only omits the
        // TYPE_TEXT_FLAG_AUTO_CORRECT bit; source commitCorrection still returns this option.
        return NativeQueuedInputConnection(connection, editing, autoCorrect = true) { action ->
            // Native IME_NULL must not synthesize another Enter; other source defaults do nothing.
            when (action) {
                EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS ->
                    onEditorAction(action)
            }
        }
    }

    private fun currentStyle(): TextStyle = environment.appearance.editorVisuals.largeValueStyle(
        environment.appearance.typography[ReverbTextRole.BODY_LARGE], environment.palette.ink)

    private fun direction(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun publishTextInk() {
        val ink = smallthingz.ui.controls.textSurfaceForeground(settingsTextSurface(this),colorMotion.color(0))
        if (currentTextColor != ink) setTextColor(ink)
    }
    private fun applyColors() {
        publishTextInk()
        val primary=colorMotion.color(1)
        val recipe = environment.appearance.editorVisuals
        bindCaret(recipe.cursorWidthDp * environment.textDensity.density, primary, recipe.selectionColor(primary))
    }

    private fun applyTypography() {
        val style = currentStyle()
        val resolved = resolveDefaults(style, direction())
        val face = editorTypeface(resolved, environment.sharedFontResolver)
        if (appliedEnvironment === environment && appliedTypeface === face) return
        appliedEnvironment = environment
        appliedTypeface = face
        val metrics = environment.appearance.editorVisuals.largeValueText.resolveEditorMetrics(environment.textDensity)
        applyEditorTypography(face, metrics)
        lineHeightSpan = RetentionLineHeightSpan(metrics.lineHeightPx)
        installCaret()
        colorMotion.retarget(environment.palette.ink,environment.palette.primary)
        applyColors()
        minimumParagraph = applyEditorMinimum(resolved, environment.textDensity, environment.sharedFontResolver)
        installLineHeightSpan()
        requestLayout()
        invalidate()
    }

    private fun installLineHeightSpan() {
        val value = editableText ?: return
        lineHeightSpan?.install(value)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (initialized) {
            appliedEnvironment = null
            applyTypography()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (focusPending) post(focusRequest)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(focusRequest)
        focusPending = false
        super.onDetachedFromWindow()
    }
}

internal data class RetentionImeContract(val inputType: Int, val imeOptions: Int)

internal fun retentionImeContract(mode: RetentionMode): RetentionImeContract {
    val ascii = mode == RetentionMode.TIME
    return RetentionImeContract(
        if (ascii) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            if (ascii) EditorInfo.IME_FLAG_FORCE_ASCII else 0,
    )
}
