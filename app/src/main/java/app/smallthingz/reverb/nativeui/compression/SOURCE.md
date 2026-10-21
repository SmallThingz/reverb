# Native Output compression slice

The release oracle is Reverb v0.1.2, commit 3e16f8ddb78dca15df4b436d34d330801ce83ffd, OutputCompressionSettings.kt31–112. Existing SettingsScreen and its Compose rendering remain unchanged. This slice ports only its toggle row, conditional slider body, Faster/Smaller labels, and default Top expansion/fade. Settings drafts own accepted enabled/level values.

## Pinned source

AndroidX Material3 1.4.0, `commonMain/androidx/compose/material3/Slider.kt`, Apache-2.0:
- SliderImpl744–875: minimum target, required visual size, integer relative placement, tap-before-drag modifier order.
- slideOnKeyEvents878–957: RTL directional stepping, non-RTL PageUp/PageDown and Home/End.
- SliderColors1163–1188 and SliderTokens: source enabled/disabled roles and compositing.
- Track1430+ and drawTrack1858–2110: float corner/gap round trips, exact track thresholds and stop/tick placement.
- sliderSemantics2224–2282 and SliderState2730–2855: controlled value, press/raw offsets, pointer first-tie and semantics last-tie snapping.
- Slider.kt SHA-256: 7d934ad8abe4345e9e959dd84fd94826e91661d5e715671ad3c1bd99405d6a90.
- SliderTokens.kt SHA-256: 27f9bcbad619b87083eeb8761f492846420140bfdf52c8049ff9b55c6308dea7.

AndroidX foundation/UI/animation 1.11.2, Apache-2.0:
- TapGestureDetector.processTapGesture and waitForUpOrCancellation: consume the first down/up, final-pass cancellation, extended touch bounds. Android firstDownRefersToPrimaryMouseButtonOnly=false.
- EnterExitTransition.fadeIn/fadeOut and expandVertically/shrinkVertically: default critically damped stiffness400 springs, size threshold IntSize(1,1), default Float threshold; TopCenter alignment for this caller.
- Transition.updateTarget1176–1194, updateAnimation1458–1488, onFrame1035–1108: shared scaled clock, raw vector momentum, delayed interruption, target-state reset, content lifetime.
- IncreaseHorizontalSemanticsBounds: horizontal10dp semantics expansion only.
- The low-level public TargetBasedAnimation, Color compositing and vector converters are reused without a Composition, Recomposer or layout tree.

Full Apache license is included in `compression/LICENSE-2.0.txt`. The exact upstream source copies are retained in the ignored evidence proposal's reference directory, not duplicated into production.

## Ownership and input

`SettingsOutputCompressionView.bind` receives the existing draft. It disables the outgoing slider before starting collapse; the child remains measured/drawn until both animations finish, then close removes callback/input ownership. A fresh expansion gets a fresh source child-transition lifetime. Level requests never silently update accepted state.

The native window must install the current slider's `pointerNodes` on `pointerView` in its source-order hit tree. This reuses the shared Main/Final drag router and parent-interest handling. No second MotionEvent detector, stock SeekBar, click ripple or press animation was added. The released slider emits drag interaction width changes; its ordinary tap onPress only records the press offset.

Keyboard and accessibility actions call the same controlled owner callback. The source Slider's progress range is float0..8 with seven intermediate steps. Screen-reader focus, disabled actions and 10dp horizontal node expansion are represented; actual resulting nodes still require the normal finite device comparison.

## Review and remaining evidence

The independent input review found an incorrect primary-mouse-only down test; it was replaced with Android's all-unconsumed changed-to-down test. Tap/drag source ordering and consumption otherwise had no finding in that bounded review. The shared native root's broader mouse/indirect-input qualification is separate.

CompressionSliderModelTest has six cases; SettingsExpansionMotionTest has five. They cover controlled rejection, exact tie direction, RTL/key boundaries, odd-pixel placement, drag width, initial/terminal expansion, interruption, and pre-measure cancellation. SettingsExpansionFramesTest adds four cases for inherited duration scale, attachment, paused-window clock, retained transition time and explicit disposal. They are written for the aggregate owner to run; this handoff is not a test pass.

The initial raw Choreographer driver was rejected in source review because it ignored the paused window clock. SettingsExpansionFrames now runs one cancellable child job on the caller's existing MonotonicFrameClock and MotionDurationScale. Detach cancels queued delivery but leaves the transition pose and clock origin intact; reattach resumes the same motion. Window START/STOP and already-queued frame admission remain owned by the single inherited window runtime. Explicit close retires the animation and child content without cancelling unrelated window jobs.

No production route is switched. Exact raster, native accessibility tree, retained-window input, accepted-target/frame scheduling and visible motion remain to be compared against the release oracle. Public animation math equivalence alone is not frame-phase evidence. No latency claim is made.
