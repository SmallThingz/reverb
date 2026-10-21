# Retention editor candidate and exact leaf probe

Status: integrated in native-hosts for probe-only qualification; the production Settings route is unchanged. No compilation, device operation or production route change has occurred. The native editor is deliberately NOT approved as a replacement. Its existing native cursor/selection/toolbar behavior is an observable qualification dependency, not a claimed match.

## Files and direct API

- SettingsRetentionInputView.kt: ordinary native EditText, not a hidden input behind a second renderer.
- ReleasedRetentionEditorOracle.kt: mechanically copied release code, provenance in RELEASE-ORACLE.json.
- NativeRetentionInputParityProbe.kt: isolated physical-device fixture proposal. Entry: verifyNativeRetentionInputParity(instrumentation, report).
- source/: pinned foundation/ui/ui-text 1.11.2 source excerpts plus source-jar/entry hashes.

Construct SettingsRetentionInputView(context, environment), then call:

    bind(rawString, mode, bufferLabel, environment, requestFocus, readCurrentRaw, onRawChanged)

The containing SettingsRetentionValueView already accepts the actual editor View. It owns the surrounding header/MiB/estimate geometry; the editor owns only its text/input/focus. Same identity rebinds update callbacks without recreating the editor. releaseCallbacks is explicit owner disposal; temporary detachment does not discard callbacks. There is no preference write, normalization, additional validation or separate Settings state tree.

## Source contracts implemented in this candidate

SettingsScreen2258–2352 supplies 34sp/40sp Normal bodyLarge, −1.1sp tracking, chrome.ink, primary cursor and label content description. It leaves the BasicTextField enabled and unrestricted even when its owner can reject a change. Time uses Ascii, Size uses Decimal, but these are keyboard hints only.

The candidate uses an unrestricted TextKeyListener and setRawInputType; setInputType(Decimal) would install DigitsKeyListener and incorrectly filter raw characters. singleLine is followed by transformationMethod=null because the source uses VisualTransformation.None; newlines remain in the raw draft. The native editor's actual rendering of these newlines is compared, never assumed.

BasicTextField759–825 retains internal selection/composition, preserves selection on owner string changes, calls the String callback only for a changed String and keys lastTextValue to owner value. The candidate tracks owner/raw-callback identity separately so rejected same-owner edits do not incorrectly reset callback deduplication. External string replacement clamps selection and commits composition (EditProcessor53–86). The callback/readCurrentRaw pair restores rejected edits without disabling the field or introducing character validation. Real IME batching/recomposition timing still needs direct qualification.

The source focus effect is keyed to the requestFocus Boolean. The candidate schedules one attach-safe request for a new true key, cancels pending work on detachment/disposal and does not retry on layout/resume or every bind.

Native paint flags, font family resolver, explicit Density conversion, font padding, break strategy, bounds-for-width and single-line line-height span are source-derived. The source's minimum dimensions come from a Paragraph containing ten H characters; no guessed baseline correction exists. sourceLayout is diagnostic only. Reports separately retain actual native layout, visible size and baseline; it cannot turn native disagreement into a pass.

The actual foundation EditorInfo.android.kt40–146 supplies Done, NO_FULLSCREEN, FORCE_ASCII for Time, text AUTO_CORRECT only for Time, and exact selection/surrounding text. The deprecated ui TextInputServiceAndroid copy has the same basic flags but is not the active legacy BasicTextField adapter; both source receipts are retained. Default Done hides the keyboard without clearing focus. Pasted/IME text preserves every raw character; rich formatting is not part of the released String state. Emoji/stylus metadata and gesture handling remain part of real IME qualification.

The actual foundation RecordingInputConnection158–204 queues commands and applies them only when batch depth returns to zero. The candidate now uses the same public EditProcessor backend: pending commands cannot leak partial native Editable changes, callbacks run after selection/composition updates, and closing discards an unfinished batch. API33 attributed overloads and API34 replaceText route through that queue rather than bypassing it through InputConnectionWrapper. Hardware-key and native paste callbacks are deferred until their complete native operation ends; exact source key/selection policy remains independently unqualified.

## Probe boundary and required next step

The finite A/B compares six unfocused cases: ordinary time, decimal size, empty large font, raw newline, Unicode/fallback large RTL and overflowing RTL. It uses actual release BasicTextField code and ReverbTypography, Light/Dark and supplied immutable Density. Every case retains four rendered captures, exact pixel differences, repeated-frame stability and actual visible geometry. It does not modify the reference with an onTextLayout callback; source metrics are read from GetTextLayoutResult semantics.

A separate native-only InputConnection diagnostic checks Unicode/newline retention, no raw callback on selection-only edits, owner rejection restoration, composition clearing on external replacement, no intermediate batch text/callbacks, one final batch callback and discard on connection close. It never claims that direct connection calls are real system IME delivery or a Compose/native behavior pass.

The fixture always reports these unqualified dimensions: focused cursor and blink phase; selection handles/toolbar/magnifier; hardware keys/undo; real IME session/composition ordering; editable accessibility actions; keyed focus/keyboard show-hide; and API28 cursor geometry. It does not hide these behind a green static screenshot result.

Stock EditText does not expose all Compose paragraph or native Editor geometry. API28 has no public cursor/selection-handle drawable setter; no hidden API/reflection workaround is permitted. The source cursor is a 2dp floored stroke, clamped and pixel-centered against source cursor rectangles, blinking every500ms while field/window focus and collapsed selection permit. Source handles are separate25dp popup geometry, not arbitrary platform defaults. These differences must be measured and, where demonstrated, replaced by source-derived native drawing/selection ownership before production. If that cannot be done within EditText, use the pinned low-level text/edit math directly in a native View; do not relax the oracle or silently accept a visually approximate widget.

No live Settings editor has been swapped. The next authorized step is a bounded independent source review, then parent-controlled compilation and actual isolated A/B. All actual app editing, release, preferences and recording flows remain unchanged.
