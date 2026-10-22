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

import app.smallthingz.reverb.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.LocaleList
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.inputmethod.InputContentInfo
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
import app.smallthingz.nativeui.graphics.Color
import app.smallthingz.nativeui.text.AnnotatedString
import app.smallthingz.nativeui.text.MultiParagraph
import app.smallthingz.nativeui.text.MultiParagraphIntrinsics
import app.smallthingz.nativeui.text.Paragraph
import app.smallthingz.nativeui.text.TextLayoutInput
import app.smallthingz.nativeui.text.TextLayoutResult
import app.smallthingz.nativeui.text.TextStyle
import app.smallthingz.nativeui.text.TextRange
import app.smallthingz.nativeui.text.input.CommitTextCommand
import app.smallthingz.nativeui.text.input.DeleteSurroundingTextCommand
import app.smallthingz.nativeui.text.input.DeleteSurroundingTextInCodePointsCommand
import app.smallthingz.nativeui.text.input.EditCommand
import app.smallthingz.nativeui.text.input.EditProcessor
import app.smallthingz.nativeui.text.input.FinishComposingTextCommand
import app.smallthingz.nativeui.text.input.SetComposingRegionCommand
import app.smallthingz.nativeui.text.input.SetComposingTextCommand
import app.smallthingz.nativeui.text.input.SetSelectionCommand
import app.smallthingz.nativeui.text.input.TextFieldValue
import app.smallthingz.nativeui.text.input.getSelectedText
import app.smallthingz.nativeui.text.input.getTextAfterSelection
import app.smallthingz.nativeui.text.input.getTextBeforeSelection
import app.smallthingz.nativeui.text.font.FontStyle
import app.smallthingz.nativeui.text.font.FontSynthesis
import app.smallthingz.nativeui.text.font.FontWeight
import app.smallthingz.nativeui.text.resolveDefaults
import app.smallthingz.nativeui.text.style.TextOverflow
import app.smallthingz.nativeui.unit.Constraints
import app.smallthingz.nativeui.unit.IntSize
import app.smallthingz.nativeui.unit.LayoutDirection
import app.smallthingz.nativeui.unit.sp
import app.smallthingz.nativeui.unit.isSpecified
import app.smallthingz.nativeui.text.style.LineHeightStyle
import androidx.core.view.inputmethod.EditorInfoCompat
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Native editable/IME owner extracted mechanically from SettingsRetentionInputView. No composition
 * or hidden rendering tree. The caller owns source style, IME hints and Done action; raw edits stay
 * unrestricted. This candidate keeps native Editor drawing/handles and therefore is NOT a qualified
 * replacement until cursor/selection/key/IME and actual paragraph raster match the released field.
 * API28 cursor styling uses its public constructor resource path; handle setters remain API29+.
 */
internal class ReverbSingleLineInputView private constructor(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initialConfiguration: NativeSingleLineInputConfiguration,
    private val legacyCursor: LegacyNativeCursorBinding?,
) : EditText(legacyCursor?.context ?: context, null, 0,
    if (legacyCursor != null) R.style.ReverbNativeEditorCursor else 0) {
    constructor(context: Context, initialEnvironment: ReverbViewEnvironment,
        initialConfiguration: NativeSingleLineInputConfiguration) : this(
        context, initialEnvironment, initialConfiguration, legacyNativeCursorBinding(context,
            2f * initialEnvironment.textDensity.density, initialConfiguration.cursorColor))
    private var environment = initialEnvironment
    private val cursorDrawable = legacyCursor?.cursor ?: NativeEditableCursorDrawable(
        2f * initialEnvironment.textDensity.density, initialConfiguration.cursorColor)
    private var initialized = false
    private var applyingOwner = false
    private var previousOwnerText: String? = null
    private var lastCallbackText: String? = null
    private var configuration = initialConfiguration
    private var onDone: (() -> Unit)? = null
    var onNativeFocusChanged: ((Boolean) -> Unit)? = null
    private var readCurrentRaw: (() -> String)? = null
    private var onRawChanged: ((String) -> Unit)? = null
    private var appliedTypeface: Typeface? = null
    private var appliedEnvironment: ReverbViewEnvironment? = null
    private var appliedConfiguration: NativeSingleLineInputConfiguration? = null
    private var minimumParagraph: Paragraph? = null
    private var lineHeightSpan: NativeEditableLineHeightSpan? = null
    private var nativeEditDepth = 0
    private var nativeEditPending = false
    private val editProcessor = EditProcessor()
    // Public composing-span manipulation on the same Editable; never a registered IME session.
    private val composingSpans = object : BaseInputConnection(this, true) {
        override fun getEditable(): Editable = checkNotNull(this@ReverbSingleLineInputView.editableText)
    }


    init {
        background = null
        foreground = null
        // AndroidComposeView opts out of the platform overlay; cursor/selection own field focus.
        defaultFocusHighlightEnabled = false
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
        // Hints never install an input-type-specific key listener/filter. BasicTextField's raw
        // String contract admits Unicode, punctuation and pasted newlines even with Ascii hints.
        keyListener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
        filters = emptyArray()
        setSingleLine(true)
        transformationMethod = null // Source VisualTransformation.None retains raw newlines.
        setHorizontallyScrolling(true)
        setSelectAllOnFocus(false)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        applyImeHints()
        setOnEditorActionListener { _, action, event ->
            // Android16 calls this for hardware DOWN and UP. Hardware Enter is handled once by
            // our key route below; IME actions have their own source action dispatch.
            if (event == null && action == EditorInfo.IME_ACTION_DONE) {
                onDone?.invoke()
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
        configuration: NativeSingleLineInputConfiguration,
        environment: ReverbViewEnvironment,
        readCurrentRaw: () -> String,
        onRawChanged: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        this.readCurrentRaw = readCurrentRaw
        this.onRawChanged = onRawChanged
        this.onDone = onDone
        this.environment = environment
        val imeChanged = this.configuration.ime != configuration.ime
        this.configuration = configuration
        applyTypography()
        applyImeHints()
        acceptOwnerText(rawString)
        if (imeChanged && isFocused) {
            context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
        // No auto-focus: released RenameRecordingDialog has no FocusRequester/LaunchedEffect.
    }

    /** A temporary detach retains owner callbacks; the actual sheet lifetime calls this. */
    fun releaseCallbacks() {
        readCurrentRaw = null
        onRawChanged = null
        onDone = null
        onNativeFocusChanged = null
    }

    override fun onDraw(canvas: Canvas) {
        cursorDrawable.beginTextDraw()
        try {
            super.onDraw(canvas)
            // Released BasicTextField draws its cursor after drawContent, above glyph edges.
            cursorDrawable.drawAfterText(canvas, compoundPaddingLeft, extendedPaddingTop)
        } finally {
            cursorDrawable.endTextDraw()
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        // Released Rename uses singleLine CoreTextField: HorizontalScrollLayoutModifier places
        // text at (horizontalOffset, 0), even when its unrestricted raw value contains newlines.
        // TextView cursor-follow must not introduce a source-absent vertical scrolling axis.
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
        val hints = configuration.ime
        // Keep the unrestricted key listener installed above; never use setInputType here.
        setRawInputType(hints.inputType)
        imeOptions = hints.imeOptions
        transformationMethod = null
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        val hints = configuration.ime
        outAttrs.inputType = hints.inputType
        outAttrs.imeOptions = hints.imeOptions // Do not inherit native NEXT/PREVIOUS flags.
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd
        EditorInfoCompat.setInitialSurroundingText(outAttrs, text.toString())
        return NativeSingleLineInputConnection(connection, autoCorrect = hints.autoCorrect)
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        // RecordingInputConnection/BasicTextField(String) preserves raw characters, not rich spans.
        nativeEdit { super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!nativeSingleLineEnterCommand(keyCode, event.isAltPressed, event.isMetaPressed)) return false
            onDone?.invoke()
            return true
        }
        return nativeEdit { super.onKeyDown(keyCode, event) }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) false
        else super.onKeyUp(keyCode, event)

    private fun sourceEditorAction(action: Int) {
        when (action) {
            EditorInfo.IME_ACTION_DONE -> onDone?.invoke()
            EditorInfo.IME_ACTION_NEXT -> focusSearch(FOCUS_FORWARD)?.requestFocus(FOCUS_FORWARD)
            EditorInfo.IME_ACTION_PREVIOUS -> focusSearch(FOCUS_BACKWARD)?.requestFocus(FOCUS_BACKWARD)
            // Custom Done is the only released KeyboardActions callback. Other default actions
            // except focus traversal are no-ops, not native TextView's keyboard-hide fallback.
        }
    }

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

    /** Outer decorated field owns editable semantics; native Editor is only its edit/input leaf. */
    fun setTextFromSemantics(value: String): Boolean {
        applyFieldValue(TextFieldValue(value, TextRange(value.length)))
        publishNativeRaw()
        return true
    }

    fun setSelectionFromSemantics(start: Int, end: Int): Boolean {
        val current = currentFieldValue()
        if (start == current.selection.start && end == current.selection.end) return false
        if (minOf(start, end) < 0 || maxOf(start, end) > current.text.length) return false
        applyFieldValue(TextFieldValue(current.annotatedString, TextRange(start, end)))
        return true
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
    private inner class NativeSingleLineInputConnection(
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
            onEditorAction = ::sourceEditorAction,
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
        override fun performContextMenuAction(id: Int): Boolean = actions.performContextMenuAction(id)
        override fun performEditorAction(action: Int): Boolean = actions.performEditorAction(action)
        override fun sendKeyEvent(event: KeyEvent): Boolean = actions.sendKeyEvent { target.sendKeyEvent(event) }
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = actions.performPrivateCommand()
        override fun clearMetaKeyStates(states: Int): Boolean = false
        override fun reportFullscreenMode(enabled: Boolean): Boolean = false
        override fun getHandler(): Handler? = null
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
        override fun getCursorCapsMode(reqModes: Int): Int = currentFieldValue().let {
            TextUtils.getCapsMode(it.text, it.selection.min, reqModes)
        }
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
            active && target.requestCursorUpdates(cursorUpdateMode)
        @RequiresApi(33)
        override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
            requestCursorUpdates(cursorUpdateMode or cursorUpdateFilter)

        override fun commitCompletion(text: CompletionInfo?): Boolean = false
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = active && autoCorrect

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

    private fun currentStyle(): TextStyle = configuration.style.copy(color = Color(configuration.textColor))

    private fun direction(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun applyTypography() {
        val style = currentStyle()
        val resolved = resolveDefaults(style, direction())
        val face = environment.sharedFontResolver.resolve(
            resolved.fontFamily, resolved.fontWeight ?: FontWeight.Normal,
            resolved.fontStyle ?: FontStyle.Normal, resolved.fontSynthesis ?: FontSynthesis.All,
        ).value as Typeface
        if (appliedEnvironment === environment && appliedTypeface === face && appliedConfiguration == configuration) return
        appliedEnvironment = environment
        appliedConfiguration = configuration
        appliedTypeface = face
        val density = environment.textDensity
        val sizePx = with(density) { resolved.fontSize.toPx() }
        val heightPx = with(density) {
            when {
                resolved.lineHeight.isEm -> resolved.lineHeight.value * sizePx
                fontScale > 1.05f -> resolved.lineHeight.value / sizePx.toSp().value * sizePx
                else -> resolved.lineHeight.toPx()
            }
        }
        val tracking = with(density) {
            when {
                resolved.letterSpacing.isEm -> resolved.letterSpacing.value
                resolved.letterSpacing.isSpecified && sizePx != 0f -> resolved.letterSpacing.toPx() / sizePx
                else -> 0f
            }
        }
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        letterSpacing = tracking
        setLineSpacing(0f, 1f)
        setTextColor(configuration.textColor)
        highlightColor = configuration.selectionBackgroundColor
        val lineStyle = resolved.lineHeightStyle ?: LineHeightStyle.Default
        lineHeightSpan = NativeEditableLineHeightSpan(heightPx, lineStyle)
        if (Build.VERSION.SDK_INT >= 29) {
            cursorDrawable.bind(2f * density.density, configuration.cursorColor)
            textCursorDrawable = cursorDrawable
            textSelectHandle?.mutate()?.setTint(configuration.selectionHandleColor)
            textSelectHandleLeft?.mutate()?.setTint(configuration.selectionHandleColor)
            textSelectHandleRight?.mutate()?.setTint(configuration.selectionHandleColor)
        } else {
            legacyCursor?.bind(2f * density.density, configuration.cursorColor)
        }
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
        value.getSpans(0, value.length, NativeEditableLineHeightSpan::class.java).forEach(value::removeSpan)
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
            Constraints.fitPrioritizingWidth(minWidth = 0, maxWidth = sourceWidth, minHeight = 0, maxHeight = Constraints.Infinity), maxLines = 1, overflow = TextOverflow.Clip)
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

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onNativeFocusChanged?.invoke(gainFocus)
    }
}

internal data class NativeSingleLineImeContract(val inputType:Int,val imeOptions:Int,val autoCorrect:Boolean)

internal data class NativeSingleLineInputConfiguration(
    val style:TextStyle,
    val textColor:Int,
    val cursorColor:Int,
    val selectionBackgroundColor:Int,
    val selectionHandleColor:Int,
    val ime:NativeSingleLineImeContract,
)

internal fun nativeRenameImeContract() = NativeSingleLineImeContract(
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
    EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_FORCE_ASCII,
    autoCorrect=true,
)

private class NativeEditableLineHeightSpan(private val heightPx: Float, private val style:LineHeightStyle) : LineHeightSpan {
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
        val trimFirst = style.trim == LineHeightStyle.Trim.Both || style.trim == LineHeightStyle.Trim.FirstLineTop
        val trimLast = style.trim == LineHeightStyle.Trim.Both || style.trim == LineHeightStyle.Trim.LastLineBottom
        if (first && last && trimFirst && trimLast) return
        val value = metrics ?: reverbLineHeightMetrics(fm.ascent, fm.descent, heightPx,
            if (style.alignment == LineHeightStyle.Alignment.Center) ReverbLineHeightAlignment.CENTER
            else ReverbLineHeightAlignment.PROPORTIONAL, false).also { metrics = it }
        if (!(first && trimFirst)) fm.ascent = value.ascent
        if (!(last && trimLast)) fm.descent = value.descent
    }
}

