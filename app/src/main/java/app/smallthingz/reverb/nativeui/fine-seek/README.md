# Native Library SpringFineSeek leaf

Status: integrated into native-hosts; uncompiled and unexecuted. No production route, backend/player operation, Home source, build or device work was changed.

Files: `ReverbSpringFineSeekView.kt`, `NativeSpringFineSeekState.kt`, `NativeSpringFineSeekStateTest.kt` (15 focused tests). This is the actual Library specialization of `SpringFineSeekControl`: the Library caller does not provide the optional Range-editor `createInputGesture`. The Range editor's separate Initial-pass pointer-tap ownership is not claimed by this leaf.

## Binding and ownership

Construct `ReverbSpringFineSeekView(context, SpringFineSeekEnvironment)` with the existing native window's palette, Density, touch slop, MonotonicFrameClock, coroutine scope, input root and actual ancestor gesture-interest callback. Bind the exact source values/actions through `SpringFineSeekBinding`; callbacks remain separate from `interactionKey`. The player presentation retains backend seeking/audio ownership. The leaf neither creates a MediaPlayer nor stores a recording/provider/operation token.

The source pure `FineSeekInputSession`, `RangeInputGesture`, rate/pull/constraint helpers and `cancelFineSeekOwnedAudio` are called directly, unchanged. Pointer-handler callbacks are captured at the first DOWN after a pointerInput key restart and retained across same-key gestures, while enabled/cancel callback values remain current as the released rememberUpdatedState fields do. A different key/changed enabled state/touch slop retires the old session; cancellation uses the updated source cancellation callback. Frame callbacks capture at drag-session start, and actual event-to-composition/frame scheduling remains unqualified. Current source handlers/methods, not this view, own any backend-generation admission.

The native window owns the frame clock. The leaf creates one attached child coroutine job for its current raw-frame drag/return effect and cancels it on detach/release. There is no extra scheduler, animator, reactive tree, virtual view tree or per-frame composition. Coroutine work, View objects and drawing allocations are real costs; no claim of literally zero runtime cost is made.

## Exact source geometry and drawing

- Full leaf size is supplied by the player: width fill, normal120dp or trim104dp, with the caller's unchanged trim alpha.
- Field path uses the four source cubic segments,10dp edge padding,24dp radius and the exact floating arithmetic order.
- Input travel clamps to1 physical px. Drawing travel is computed through source Dp arithmetic and clamps to1dp. These intentionally differ at tiny layout bounds.
- Parent Canvas uses physical X. The centered64dp hit child uses the released relative lambda offset: RTL negates only its inner X offset; the odd-pixel absolute centered placement is retained. The field/child RTL asymmetry is preserved literally, not silently corrected.
- Click hit rectangle is64dp; drag starts by the source32dp circle. Surface is independently rounded48dp, with23dp icon centered inside it. The rounded circle fill, marker, glow and source Play/Pause glyphs use the pinned native drawing primitives and paint flags.
- Field colors reuse pinned low-level `Color.lerp` (Oklab conversion/quantization), not native ARGB interpolation. The shader repeats the actual Android backend's Int colors and Clamp LinearGradient. No palette values are invented.
- Source puck indication is null. The shared native click controller still owns its actual press/key/focus lifecycle but no ripple or state layer is drawn.
- The210ms cosh return follows raw `withFrameNanos`; it is deliberately not rescaled through Android animator-duration scale. Drag frames cap elapsed time at50ms, commit through the existing33,333,333ns accumulator, and preserve sub-threshold shuttle/final-delta behavior.

## Shared phase/root wiring

The screen must place `fineSeekNode` on the full leaf **only while `fineSeekInputEnabled`**, matching removal of the parent pointerInput modifier when disabled. Place `puckClickNode` on the current `puckClickView` square as its child. The disabled child clickable remains in the hit path, as in foundation. Parent Main runs after child Main; Final consumption cancellation belongs to the existing `NativeClickPhaseNode`. The custom fine-seek gesture deliberately does not use generic horizontal draggable: source movement is radial, drag can begin on the initial outside-circle DOWN, the original pointer is never handed over, and consumed UP cancels while a child's ordinary tap does not pause audio.

The caller provides this real source hierarchy to the existing native pointer root; do not also dispatch the same stream through stock View interception or add another recognizer. `cancelInput` retires input; `release` retires binding/cursor leases and returns forgotten component state to zero for recycling. The existing root's mouse/indirect-input lane remains a qualification boundary; no complete mouse parity is claimed here.

`bindAccessibility` uses the window's existing cursor and `NativePlainTextViewGeometry`. The source topology is a generic merging action, separate described Play/Pause glyph, then a role-only Button child. It reuses the shared merged-row helper. Actual Library's64dp square needs no48dp minimum expansion; artificial layouts smaller than48dp require the same distinct expanded-touch mapping already identified for About before those accessibility extremes can be credited. No TalkBack/service pass is claimed.

## Focused tests and remaining qualification

The15 written tests cover child-owned tap cancellation, outside-circle immediate drag, strict radial slop boundary,33ms accumulator/pending shuttle, final flush ordering, cancellation discard,50ms hitch cap, consumed release, same-key/live-cancel callback ownership, key retirement, non-replayed disabled effects, original-pointer release/all-up wait,210ms return timing, and fractional-density/RTL/clamp geometry, and live-width center changes during a gesture with retained DOWN travel. They have not been run by this lane.

Required actual A/B remains: released SpringFineSeekControl under the same bound callbacks versus this native leaf, current density Light/Dark, normal/trim heights, source field/puck positions while dragging and returning, square corner tap versus circular drag start, parent scroll cancellation, multi-pointer release, keyboard/accessibility Play/Pause, enabled/key replacement and callback order. Actual frame/RenderThread phase, accessibility enumeration and complete parent hit-path integration are not proved by pure tests or source review.
