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

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Selection
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.TextKeyListener
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.EditProcessor
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.getSelectedText
import androidx.compose.ui.text.input.getTextAfterSelection
import androidx.compose.ui.text.input.getTextBeforeSelection
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.view.inputmethod.EditorInfoCompat
import app.smallthingz.reverb.RetentionMode
import kotlin.math.ceil

/**
 * ISOLATED CANDIDATE, not a qualified replacement or a production route.
 *
 * This is the actual native editable/IME owner, not an invisible EditText behind a renderer.
 * It preserves unrestricted raw input, the released String callback contract, source keyboard
 * hints and keyed focus request. It does not own Settings validation, persistence or mode drafts.
 *
 * Exact text/cursor/selection raster and native Editor handle/toolbar/undo/key behavior still need
 * the accompanying A/B probe. In particular API28 has no public cursor/handle drawable setter;
 * no reflection or private API substitutes are used. No known mismatch may be accepted as parity.
 * [sourceLayout] exposes the pinned paragraph geometry so a mismatch is measurable rather than
 * hidden by baseline nudges. If native Editor cannot reproduce that geometry, replace its drawing
 * and selection ownership explicitly before any production integration.
 */
internal class SettingsRetentionInputView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
) : EditText(context, null, 0) {
    private var environment = initialEnvironment
    private var initialized = false
    private var applyingOwner = false
    private var previousOwnerText: String? = null
    private var lastCallbackText: String? = null
    private var mode = RetentionMode.TIME
    private var readCurrentRaw: (() -> String)? = null
    private var onRawChanged: ((String) -> Unit)? = null
    private var focusKey: Boolean? = null
    private var focusPending = false
    private var appliedTypeface: Typeface? = null
    private var appliedEnvironment: ReverbViewEnvironment? = null
    private var minimumParagraph: Paragraph? = null
    private var lineHeightSpan: RetentionLineHeightSpan? = null
    private var nativeEditDepth = 0
    private var nativeEditPending = false
    private val editProcessor = EditProcessor()
    // Public composing-span manipulation on the same Editable; never a registered IME session.
    private val composingSpans = object : BaseInputConnection(this, true) {
        override fun getEditable(): Editable = checkNotNull(this@SettingsRetentionInputView.editableText)
    }

    private val focusRequest = Runnable {
        if (!focusPending) return@Runnable
        focusPending = false
        if (isAttachedToWindow) requestFocus()
    }

    init {
        background = null
        foreground = null
        setPadding(0, 0, 0, 0)
        setCompoundDrawablePadding(0)
        minimumWidth = 0
        minimumHeight = 0
        setMinWidth(0)
        setMinHeight(0)
        setAutoSizeTextTypeWithDefaults(AUTO_SIZE_TEXT_TYPE_NONE)
        includeFontPadding = false
        setFallbackLineSpacing(true)
        gravity = Gravity.TOP or Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        paintFlags = Paint.ANTI_ALIAS_FLAG
        paint.hinting = Paint.HINTING_ON
        paint.isSubpixelText = false
        paint.isLinearText = false
        paint.isElegantTextHeight = false
        paint.textSkewX = 0f
        paint.isFakeBoldText = false
        fontFeatureSettings = ""
        textScaleX = 1f
        textLocales = LocaleList.getDefault()
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        justificationMode = Layout.JUSTIFICATION_MODE_NONE
        if (Build.VERSION.SDK_INT >= 33) {
            lineBreakStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_STYLE_NONE
            lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE
        }
        if (Build.VERSION.SDK_INT >= 35) {
            setUseBoundsForWidth(false)
            setShiftDrawingOffsetForStartOverhang(false)
        }
        // Decimal is an IME hint only. setInputType(DECIMAL) would install DigitsKeyListener and
        // silently reject valid source raw drafts (Unicode, punctuation, signs, pasted newlines).
        keyListener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
        filters = emptyArray()
        setSingleLine(true)
        transformationMethod = null // Source VisualTransformation.None retains raw newlines.
        setHorizontallyScrolling(true)
        setSelectAllOnFocus(false)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
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
        Selection.setSelection(checkNotNull(editableText), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (applyingOwner || !initialized) return
                nativeEditPending = true
                if (nativeEditDepth == 0) publishNativeRaw()
            }
        })
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
        this.readCurrentRaw = readCurrentRaw
        this.onRawChanged = onRawChanged
        this.environment = environment
        contentDescription = bufferLabel
        val modeChanged = this.mode != mode
        this.mode = mode
        applyTypography()
        applyImeHints()
        acceptOwnerText(rawString)
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
        readCurrentRaw = null
        onRawChanged = null
    }

    override fun scrollTo(x: Int, y: Int) {
        // Released singleLine CoreTextField uses HorizontalScrollLayoutModifier: its content
        // placement is (horizontalOffset, 0), including raw newlines. TextView's cursor-follow
        // pre-draw otherwise adds vertical slack for that raw multiline Layout, even unfocused.
        // Keep native horizontal scrolling; this editor has no source vertical scroll axis.
        super.scrollTo(x, 0)
    }

    private fun acceptOwnerText(raw: String) {
        if (raw != previousOwnerText) {
            previousOwnerText = raw
            lastCallbackText = raw // remember(value), not every rejected onValueChange callback.
        }
        if (text.toString() == raw) return
        val start = selectionStart.coerceAtLeast(0).coerceAtMost(raw.length)
        val end = selectionEnd.coerceAtLeast(0).coerceAtMost(raw.length)
        applyingOwner = true
        try {
            // Source EditProcessor.reset commits composition on an external string replacement.
            val value = checkNotNull(editableText)
            BaseInputConnection.removeComposingSpans(value)
            value.replace(0, length(), raw)
            Selection.setSelection(value, start, end)
            installLineHeightSpan()
        } finally {
            applyingOwner = false
        }
    }

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
        return RetentionInputConnection(connection, autoCorrect = true)
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        // RecordingInputConnection/BasicTextField(String) preserves raw characters, not rich spans.
        nativeEdit { super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        nativeEdit { super.onKeyDown(keyCode, event) }

    private inline fun <T> nativeEdit(block: () -> T): T {
        nativeEditDepth++
        try { return block() }
        finally {
            nativeEditDepth--
            if (nativeEditDepth == 0 && nativeEditPending) publishNativeRaw()
        }
    }

    private fun publishNativeRaw() {
        nativeEditPending = false
        val proposed = text.toString()
        val changed = proposed != lastCallbackText
        lastCallbackText = proposed
        if (changed) onRawChanged?.invoke(proposed)
        // Restore rejected drafts only after the complete command/key/paste updated selection.
        readCurrentRaw?.invoke()?.let(::acceptOwnerText)
        installLineHeightSpan()
    }

    private fun currentFieldValue(): TextFieldValue {
        val value = checkNotNull(editableText)
        val start = BaseInputConnection.getComposingSpanStart(value)
        val end = BaseInputConnection.getComposingSpanEnd(value)
        return TextFieldValue(value.toString(),
            TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0)),
            if (start >= 0 && end >= 0 && start != end) TextRange(start, end) else null)
    }

    private fun applyFieldValue(value: TextFieldValue) {
        applyingOwner = true
        try {
            val target = checkNotNull(editableText)
            if (target.toString() != value.text) target.replace(0, target.length, value.text)
            BaseInputConnection.removeComposingSpans(target)
            value.composition?.let { composingSpans.setComposingRegion(it.min, it.max) }
            Selection.setSelection(target, value.selection.start, value.selection.end)
            installLineHeightSpan()
        } finally { applyingOwner = false }
    }

    /**
     * Pinned foundation RecordingInputConnection158–204 queues EditCommands, rather than exposing
     * partially applied native Editable changes during an IME batch. Reuse the same public low-level
     * EditProcessor as released CoreTextField. No Composition or second rendering/state tree exists.
     */
    private inner class RetentionInputConnection(
        private val target: InputConnection,
        private val autoCorrect: Boolean,
    ) : InputConnectionWrapper(target, false) {
        private var active = true
        private var depth = 0
        private val commands = ArrayList<EditCommand>()
        private val actions = NativeEditableConnectionActions(
            active = { active },
            currentTextLength = { currentFieldValue().text.length },
            queueSelectAll = { command(SetSelectionCommand(0, it)) },
            sendKey = { action, code -> sendKeyEvent(KeyEvent(action, code)) },
            onEditorAction = { action ->
                // Released KeyboardActionRunner handles Done/Next/Previous; its other defaults
                // do nothing. In particular, native IME_NULL must not synthesize another Enter.
                when (action) {
                    EditorInfo.IME_ACTION_DONE,
                    EditorInfo.IME_ACTION_NEXT,
                    EditorInfo.IME_ACTION_PREVIOUS ->
                        this@SettingsRetentionInputView.onEditorAction(action)
                }
            },
        )

        override fun beginBatchEdit(): Boolean {
            if (!active) return false
            depth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            depth--
            if (depth == 0 && commands.isNotEmpty()) {
                editProcessor.reset(currentFieldValue(), null)
                val value = editProcessor.apply(commands.toList())
                target.beginBatchEdit()
                try {
                    applyFieldValue(value)
                    publishNativeRaw()
                } finally { target.endBatchEdit() }
                commands.clear()
            }
            return depth > 0
        }

        private fun command(value: EditCommand): Boolean {
            if (!active) return false
            depth++
            try { commands.add(value) }
            finally { endBatchEdit() }
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int) =
            command(CommitTextCommand(text.toString(), newCursorPosition))
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
            command(SetComposingTextCommand(text.toString(), newCursorPosition))
        override fun setComposingRegion(start: Int, end: Int) = command(SetComposingRegionCommand(start, end))
        override fun setSelection(start: Int, end: Int) = command(SetSelectionCommand(start, end))
        override fun finishComposingText() = command(FinishComposingTextCommand())
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            command(DeleteSurroundingTextCommand(beforeLength, afterLength))
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            command(DeleteSurroundingTextInCodePointsCommand(beforeLength, afterLength))

        // InputConnectionWrapper otherwise forwards these overloads directly to Native Editor,
        // bypassing our command queue. Source RecordingIC inherits the interface's forwarding.
        @RequiresApi(33)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            commitText(text, newCursorPosition)
        @RequiresApi(33)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            setComposingText(text, newCursorPosition)
        @RequiresApi(33)
        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?) =
            setComposingRegion(start, end)
        @RequiresApi(34)
        override fun replaceText(start: Int, end: Int, text: CharSequence,
                                 newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
            // Exact Android InputConnection default; these are API argument checks, not draft filters.
            require(start >= 0 && end >= 0)
            beginBatchEdit()
            finishComposingText()
            setSelection(start, end)
            commitText(text, newCursorPosition, textAttribute)
            endBatchEdit()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            currentFieldValue().getTextBeforeSelection(n).toString()
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
            currentFieldValue().getTextAfterSelection(n).toString()
        override fun getSelectedText(flags: Int): CharSequence? = currentFieldValue().let {
            if (it.selection.collapsed) null else it.getSelectedText().toString()
        }
        override fun commitCompletion(text: CompletionInfo?): Boolean = false
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = active && autoCorrect
        override fun performContextMenuAction(id: Int): Boolean = actions.performContextMenuAction(id)
        override fun performEditorAction(actionCode: Int): Boolean = actions.performEditorAction(actionCode)
        override fun sendKeyEvent(event: KeyEvent): Boolean =
            actions.sendKeyEvent { target.sendKeyEvent(event) }
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
            actions.performPrivateCommand()
        override fun clearMetaKeyStates(states: Int): Boolean = false
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
            active && target.requestCursorUpdates(cursorUpdateMode)
        @RequiresApi(33)
        override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
            active && target.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter)

        override fun closeConnection() {
            if (!active) return
            active = false
            commands.clear() // Source discards an unfinished batch without applying it.
            depth = 0
            val preserved = currentFieldValue()
            var primary: Throwable? = null
            try { target.closeConnection() }
            catch (error: Throwable) { primary = error; throw error }
            finally {
                // Native Editor may clear composing spans on close; source RecordingIC does not.
                try { applyFieldValue(preserved) }
                catch (error: Throwable) {
                    if (primary == null) throw error else primary.addSuppressed(error)
                }
            }
        }
    }

    private fun currentStyle(): TextStyle = ReverbParagraphStyles[ReverbTextRole.BODY_LARGE].copy(
        color = Color(environment.palette.ink),
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-1.1).sp,
    )

    private fun direction(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun applyTypography() {
        val style = currentStyle()
        val resolved = resolveDefaults(style, direction())
        val face = environment.sharedFontResolver.resolve(
            resolved.fontFamily, resolved.fontWeight ?: FontWeight.Normal,
            resolved.fontStyle ?: FontStyle.Normal, resolved.fontSynthesis ?: FontSynthesis.All,
        ).value as Typeface
        if (appliedEnvironment === environment && appliedTypeface === face) return
        appliedEnvironment = environment
        appliedTypeface = face
        val scale = object : ReverbTextScale {
            override val fontScale = environment.textDensity.fontScale
            override fun spToPixels(sp: Float) = with(environment.textDensity) { sp.sp.toPx() }
            override fun pixelsToSp(px: Float) = with(environment.textDensity) { px.toSp().value }
        }
        val metrics = ReverbTextStyle(34f, 40f, 400, -1.1f).resolveMetrics(scale)
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.fontSizePx)
        letterSpacing = metrics.letterSpacingEm
        setLineSpacing(0f, 1f)
        setTextColor(environment.palette.ink)
        highlightColor = (environment.palette.primary and 0x00ffffff) or (0x66 shl 24)
        lineHeightSpan = RetentionLineHeightSpan(metrics.lineHeightPx)
        minimumParagraph = Paragraph(
            text = "HHHHHHHHHH", style = resolved, spanStyles = emptyList(),
            maxLines = 1, overflow = TextOverflow.Clip,
            density = environment.textDensity, fontFamilyResolver = environment.sharedFontResolver,
            constraints = Constraints(),
        )
        minimumWidth = ceil(checkNotNull(minimumParagraph).minIntrinsicWidth).toInt()
        minimumHeight = ceil(checkNotNull(minimumParagraph).height).toInt()
        installLineHeightSpan()
        requestLayout()
        invalidate()
    }

    private fun installLineHeightSpan() {
        val value = editableText ?: return
        value.getSpans(0, value.length, RetentionLineHeightSpan::class.java).forEach(value::removeSpan)
        lineHeightSpan?.let { span ->
            span.reset(value.length, value.endsWith('\n'))
            value.setSpan(span, 0, value.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }

    /**
     * Source layout for the finite QA probe, including newline/fallback geometry. This is NOT
     * substituted for native Editor metrics in the parity report and cannot make a mismatch pass.
     */
    fun sourceLayout(containerWidth: Int = width): TextLayoutResult {
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

private class RetentionLineHeightSpan(private val heightPx: Float) : LineHeightSpan {
    private var endIndex = 0
    private var metrics: ReverbLineHeightMetrics? = null
    fun reset(length: Int, trailingNewline: Boolean) {
        endIndex = if (length == 0 || trailingNewline) length + 1 else length
        metrics = null
    }
    override fun chooseHeight(text: CharSequence, start: Int, end: Int,
                              spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        if (fm.descent - fm.ascent <= 0) return
        val first = start == 0
        val last = end == endIndex
        if (first && last) return
        val value = metrics ?: reverbLineHeightMetrics(fm.ascent, fm.descent, heightPx,
            ReverbLineHeightAlignment.PROPORTIONAL, true).also { metrics = it }
        fm.ascent = if (first) value.firstAscent else value.ascent
        fm.descent = if (last) value.lastDescent else value.descent
    }
}
