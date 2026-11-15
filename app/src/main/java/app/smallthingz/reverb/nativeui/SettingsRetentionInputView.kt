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
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
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
import smallthingz.ui.graphics.Color
import smallthingz.ui.text.Paragraph
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontStyle
import smallthingz.ui.text.font.FontSynthesis
import smallthingz.ui.text.font.FontWeight
import smallthingz.ui.text.resolveDefaults
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.unit.Constraints
import smallthingz.ui.unit.LayoutDirection
import smallthingz.ui.unit.sp
import androidx.core.view.inputmethod.EditorInfoCompat
import app.smallthingz.reverb.RetentionMode
import app.smallthingz.reverb.R
import kotlin.math.ceil

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
internal class SettingsRetentionInputView private constructor(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    private val legacyCursor: LegacyNativeCursorBinding?,
) : EditText(legacyCursor?.context ?: context, null, 0,
    if (legacyCursor != null) R.style.ReverbNativeEditorCursor else 0) {
    constructor(context: Context, initialEnvironment: ReverbViewEnvironment) : this(
        context, initialEnvironment, legacyNativeCursorBinding(context,
            2f * initialEnvironment.textDensity.density, initialEnvironment.palette.primary))
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (nativeSurfaceInputAllowed(this)) super.createAccessibilityNodeInfo() else null

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!nativeSurfaceInputAllowed(this) && action != AccessibilityNodeInfo.ACTION_CLEAR_FOCUS &&
            action != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) return false
        return super.performAccessibilityAction(action, arguments)
    }

    private var environment = initialEnvironment
    private val colorMotion:ViewColorTransition by lazy {ViewColorTransition(this,
        intArrayOf(initialEnvironment.palette.ink,initialEnvironment.palette.primary),::applyColors)}
    private val cursorDrawable = legacyCursor?.cursor ?: NativeEditableCursorDrawable(
        2f * initialEnvironment.textDensity.density, initialEnvironment.palette.primary)
    private var initialized = false
    private var mode = RetentionMode.TIME
    private var focusKey: Boolean? = null
    private var focusPending = false
    private var appliedTypeface: Typeface? = null
    private var appliedEnvironment: ReverbViewEnvironment? = null
    private var minimumParagraph: Paragraph? = null
    private var lineHeightSpan: RetentionLineHeightSpan? = null
    private val editing = NativeEditableState(this, ::installLineHeightSpan)

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
        Selection.setSelection(checkNotNull(editableText), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (initialized) editing.afterTextChanged()
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

    override fun onDraw(canvas: Canvas) {
        publishTextInk()
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
        // Released singleLine CoreTextField uses HorizontalScrollLayoutModifier: its content
        // placement is (horizontalOffset, 0), including raw newlines. TextView's cursor-follow
        // pre-draw otherwise adds vertical slack for that raw multiline Layout, even unfocused.
        // Keep native horizontal scrolling; this editor has no source vertical scroll axis.
        super.scrollTo(x, 0)
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
        return NativeQueuedInputConnection(connection, editing, autoCorrect = true) { action ->
            // Native IME_NULL must not synthesize another Enter; other source defaults do nothing.
            when (action) {
                EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS ->
                    onEditorAction(action)
            }
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        // RecordingInputConnection/BasicTextField(String) preserves raw characters, not rich spans.
        editing.nativeEdit { super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        editing.nativeEdit { super.onKeyDown(keyCode, event) }

    private fun currentStyle(): TextStyle = ReverbParagraphStyles[ReverbTextRole.BODY_LARGE].copy(
        color = Color(environment.palette.ink),
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-1.1).sp,
    )

    private fun direction(): LayoutDirection =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun publishTextInk() {
        val ink = smallthingz.ui.controls.textSurfaceForeground(settingsTextSurface(this),colorMotion.color(0))
        if (currentTextColor != ink) setTextColor(ink)
    }
    private fun applyColors() {
        publishTextInk()
        val primary=colorMotion.color(1)
        highlightColor=(primary and 0x00ffffff) or (0x66 shl 24)
        if(Build.VERSION.SDK_INT>=29)cursorDrawable.bind(2f*environment.textDensity.density,primary)
        else legacyCursor?.bind(2f*environment.textDensity.density,primary)
    }

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
        lineHeightSpan = RetentionLineHeightSpan(metrics.lineHeightPx)
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = cursorDrawable
        }
        colorMotion.retarget(environment.palette.ink,environment.palette.primary)
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
        value.getSpans(0, value.length, RetentionLineHeightSpan::class.java).forEach(value::removeSpan)
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
