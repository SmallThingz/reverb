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

import app.smallthingz.reverb.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputContentInfo
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.annotation.RequiresApi
import smallthingz.ui.graphics.Color
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.TextRange
import smallthingz.ui.text.input.TextFieldValue
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.resolveDefaults
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.unit.Constraints
import smallthingz.ui.unit.LayoutDirection
import smallthingz.ui.unit.sp
import smallthingz.ui.unit.isSpecified
import smallthingz.ui.text.style.LineHeightStyle
import androidx.core.view.inputmethod.EditorInfoCompat
import kotlin.math.ceil
import kotlin.math.roundToInt
import smallthingz.ui.animation.view.ViewColorTransition

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
) : ReverbEditableInputView(context, legacyCursor) {
    constructor(context: Context, initialEnvironment: ReverbViewEnvironment,
        initialConfiguration: NativeSingleLineInputConfiguration) : this(
        context, initialEnvironment, initialConfiguration, legacyNativeCursorBinding(context,
            2f * initialEnvironment.textDensity.density, initialConfiguration.cursorColor))
    private var environment = initialEnvironment
    protected override val cursorDrawable = legacyCursor?.cursor ?: NativeEditableCursorDrawable(
        2f * initialEnvironment.textDensity.density, initialConfiguration.cursorColor)
    protected override var initialized = false
        private set
    private var configuration = initialConfiguration
    private val colorMotion:ViewColorTransition by lazy {ViewColorTransition(this,
        intArrayOf(initialConfiguration.textColor,initialConfiguration.selectionBackgroundColor,
            initialConfiguration.cursorColor,initialConfiguration.selectionHandleColor),::applyColors)}
    private var onDone: (() -> Unit)? = null
    var onNativeFocusChanged: ((Boolean) -> Unit)? = null
    private var appliedTypeface: Typeface? = null
    private var appliedEnvironment: ReverbViewEnvironment? = null
    private var appliedConfiguration: NativeSingleLineInputConfiguration? = null
    private var minimumParagraph: Paragraph? = null
    private var lineHeightSpan: NativeEditableLineHeightSpan? = null
    protected override val editing = NativeEditableState(this, ::installLineHeightSpan)


    init {
        editing.initializeUnrestrictedSingleLineEditor()
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
        observeNativeEdits()
    }

    fun bind(
        rawString: String,
        configuration: NativeSingleLineInputConfiguration,
        environment: ReverbViewEnvironment,
        readCurrentRaw: () -> String,
        onRawChanged: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        editing.bindCallbacks(readCurrentRaw, onRawChanged)
        this.onDone = onDone
        this.environment = environment
        val imeChanged = this.configuration.ime != configuration.ime
        this.configuration = configuration
        applyTypography()
        applyImeHints()
        editing.acceptOwnerText(rawString)
        if (imeChanged && isFocused) {
            context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
        // No auto-focus: released RenameRecordingDialog has no FocusRequester/LaunchedEffect.
    }

    /** A temporary detach retains owner callbacks; the actual sheet lifetime calls this. */
    fun releaseCallbacks() {
        editing.releaseCallbacks()
        onDone = null
        onNativeFocusChanged = null
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
        return object : NativeQueuedInputConnection(connection, editing, hints.autoCorrect, ::sourceEditorAction) {
            override fun reportFullscreenMode(enabled: Boolean): Boolean = false
            override fun getHandler(): Handler? = null
            override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
            override fun getCursorCapsMode(reqModes: Int): Int = editing.currentFieldValue().let {
                TextUtils.getCapsMode(it.text, it.selection.min, reqModes)
            }
            @RequiresApi(33)
            override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
                requestCursorUpdates(cursorUpdateMode or cursorUpdateFilter)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!nativeSingleLineEnterCommand(keyCode, event.isAltPressed, event.isMetaPressed)) return false
            onDone?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) false
        else super.onKeyUp(keyCode, event)

    // Matches TextView's default IME traversal; focusSearch's annotation omits these logical directions.
    @android.annotation.SuppressLint("WrongConstant")
    private fun sourceEditorAction(action: Int) {
        when (action) {
            EditorInfo.IME_ACTION_DONE -> onDone?.invoke()
            EditorInfo.IME_ACTION_NEXT -> focusSearch(FOCUS_FORWARD)?.requestFocus(FOCUS_FORWARD)
            EditorInfo.IME_ACTION_PREVIOUS -> focusSearch(FOCUS_BACKWARD)?.requestFocus(FOCUS_BACKWARD)
            // Custom Done is the only released KeyboardActions callback. Other default actions
            // except focus traversal are no-ops, not native TextView's keyboard-hide fallback.
        }
    }

    /** Outer decorated field owns editable semantics; native Editor is only its edit/input leaf. */
    fun setTextFromSemantics(value: String): Boolean {
        editing.applyFieldValue(TextFieldValue(value, TextRange(value.length)))
        editing.publishNativeRaw()
        return true
    }

    fun setSelectionFromSemantics(start: Int, end: Int): Boolean {
        val current = editing.currentFieldValue()
        if (start == current.selection.start && end == current.selection.end) return false
        if (minOf(start, end) < 0 || maxOf(start, end) > current.text.length) return false
        editing.applyFieldValue(TextFieldValue(current.annotatedString, TextRange(start, end)))
        return true
    }

    private fun currentStyle(): TextStyle = configuration.style.copy(color = Color(configuration.textColor))

    private fun direction(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun applyColors() {
        setTextColor(colorMotion.color(0))
        highlightColor=colorMotion.color(1)
        val cursorWidth=2f*environment.textDensity.density
        if(Build.VERSION.SDK_INT>=29) {
            cursorDrawable.bind(cursorWidth,colorMotion.color(2))
            textSelectHandle?.setTint(colorMotion.color(3))
            textSelectHandleLeft?.setTint(colorMotion.color(3))
            textSelectHandleRight?.setTint(colorMotion.color(3))
        } else legacyCursor?.bind(cursorWidth,colorMotion.color(2))
    }

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
        val lineStyle = resolved.lineHeightStyle ?: LineHeightStyle.Default
        lineHeightSpan = NativeEditableLineHeightSpan(heightPx, lineStyle)
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = cursorDrawable
            textSelectHandle?.mutate()
            textSelectHandleLeft?.mutate()
            textSelectHandleRight?.mutate()
        }
        colorMotion.retarget(configuration.textColor,configuration.selectionBackgroundColor,
            configuration.cursorColor,configuration.selectionHandleColor)
        applyColors()
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

/** Shared native Editor mechanics; each leaf retains its initialization and presentation state. */
internal abstract class ReverbEditableInputView(
    context: Context,
    legacyCursor: LegacyNativeCursorBinding?,
) : EditText(legacyCursor?.context ?: context, null, 0,
    if (legacyCursor != null) R.style.ReverbNativeEditorCursor else 0) {
    protected abstract val cursorDrawable: NativeEditableCursorDrawable
    protected abstract val initialized: Boolean
    protected abstract val editing: NativeEditableState

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (nativeSurfaceInputAllowed(this)) super.createAccessibilityNodeInfo() else null

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!nativeSurfaceInputAllowed(this) && action != AccessibilityNodeInfo.ACTION_CLEAR_FOCUS &&
            action != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) return false
        return super.performAccessibilityAction(action, arguments)
    }

    /** Called by the leaf after its typography, at the original selection/watcher publication point. */
    protected fun observeNativeEdits() {
        Selection.setSelection(checkNotNull(editableText), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (initialized) editing.afterTextChanged()
            }
        })
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
        // Both released single-line fields place unrestricted raw text at (horizontalOffset, 0).
        // Native cursor-follow must not introduce a vertical scrolling axis for pasted newlines.
        super.scrollTo(x, 0)
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        // RecordingInputConnection/BasicTextField(String) preserves raw characters, not rich spans.
        editing.nativeEdit { super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        editing.nativeEdit { super.onKeyDown(keyCode, event) }
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

