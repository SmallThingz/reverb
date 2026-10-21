# Native spring bridge proposal

This directory is outside the frozen candidate worktree. It contains one 183-line Kotlin
proposal and source provenance. Nothing here is installed, compiled, or integrated.

## Released uses being preserved

From v0.1.2 (3e16f8ddb78dca15df4b436d34d330801ce83ffd):

- SettingsScreen.kt:1995–2007: switch press overlay Float, track/thumb Color, thumb Dp.
- SettingsScreen.kt:2229–2233: selected-segment background/content Color.
- SettingsScreen.kt:2411: dropdown expanded/collapsed background Color.

These call animateFloatAsState/animateDpAsState/animateColorAsState without a custom spec.
The proposal directly calls the pinned AndroidX 1.11.2 TargetBasedAnimation, SpringSpec,
and converters. It does not approximate those springs using a guessed duration,
ArgbEvaluator, Android SpringAnimation, or a hand-written integration step.

## Supported contract

ReleasedSpringValue is a small stateful sampling seam. Its owner passes accepted target
changes and monotonic frame timestamps, and reads a typed value.

- Initial value is already at the first target, with no entrance animation.
- Equal target values do not restart the spring.
- Float uses the released 0.01 visibility threshold; Dp uses Dp.VisibilityThreshold (0.4dp).
  Color uses SpringSpec<Color>() without an explicit threshold, as upstream does.
- Color uses the pinned Oklab converter and its component clamps/Color packing. It stores
  Color, not an ARGB approximation. Conversion to the Paint's output representation belongs
  only at the drawing boundary.
- Retargeting starts from the last delivered value and last delivered frame time, not
  from a wall-clock extrapolation between frames.
- The interrupted velocity follows Animatable.animateTo's actual T-valued route:
  raw vector -> convertFromVector -> TargetBasedAnimation(initialVelocity: T)
  -> convertToVector. For Color this clamp/quantization round trip is observable. Reusing
  the raw four-dimensional velocity directly would not match the released implementation.
- A running retarget immediately delivers the replacement's zero-time frame. A fresh
  animation waits for its first frame timestamp. Completion resets velocity and frame
  ownership after publishing the exact target, matching Animatable.endAnimation.
- Duration scale uses the same Float division/toLong ordering. Scale zero samples the
  animation's terminal time. Per-frame sampling remains in the pinned spring math, including
  its integer-millisecond spring evaluation.
- Dp remains Dp until layout/drawing. The released switch uses thumbOffset.roundToPx();
  a caller must retain the same density-aware rounding instead of animating pixel positions.

ViewSpringDriver is an optional single Choreographer callback holder. It publishes into
ordinary View/Drawable fields and invalidates the owning View; it has no Composition,
Snapshot state, coroutine, virtual tree, reflection, or second layout/rendering pass.
It schedules only while active and removes its callback on explicit owner disposal.
A retained hidden Settings View must not be disposed merely because it loses focus.
It reads ValueAnimator.getDurationScale() as a numeric scale, not as an animator.

## Boundaries still requiring qualification

This is source-derived code, not a parity result. No Kotlin compile, host tests or native
capture was authorized for this proposal.

1. The sampler matches a sequence of *accepted animation targets*. Compose's SideEffect,
   conflated Channel, extra tryReceive, and coroutine dispatch determine when raw UI changes
   become accepted targets. ViewSpringDriver does not recreate that dispatcher. Same-turn
   repeated changes and Choreographer callback phase must be compared before claiming
   frame-identical UI motion. Do not feed every intermediate model mutation blindly.
2. The native driver can share frame timestamps with other native controls; whether its
   callback lands in the same display phase as AndroidUiDispatcher remains unmeasured.
   A target set during the input phase can reach a native Choreographer animation callback
   in that same frame. Compose's SideEffect/coroutine/withFrameNanos route may begin on the
   following frame. Identical low-level samples therefore do not establish identical host
   animation frames; an observed one-frame shift must be resolved before integration.
3. Confirm platform duration-scale observation equals the released WindowRecomposer's
   MotionDurationScale for this app, including a scale change during an animation.
4. Do not infer color rendering parity from identical spring values. Native Paint color
   conversion/compositing must preserve the released typed Color result and alpha behavior.
5. No completion-listener contract, bounds clamping, decay, custom tween, content-size,
   visibility transition, predictive Back or coroutine cancellation API is supplied.
   Home's explicit 100/160/260ms tweens are not replaced by this default-spring proposal.
6. Exact dependencies remain pinned. Reusing their math removes a handwritten math fork,
   but these functions and per-target objects still have runtime costs. This is not a claim
   that all Compose artifacts can immediately be removed or that animation itself is free.

Before integration, compare deterministic 1.11.2 Animatable traces to this sampler at the
same frame times for Float, Dp and Color; include reversals mid-flight, several accepted
retargets at one timestamp, transparent <-> colored targets, completion/reopen, and scale
0/0.5/1/2. Then compare native versus released controls with the same actual input/frame
schedule. Any scheduler discrepancy belongs in the small owner adapter, not in modified
spring constants or an unverified easing approximation.

PROVENANCE.json records source-jar hashes, individual upstream source entry hashes, the
released Reverb source hashes and the chosen release commit. Upstream sources retain their
Apache-2.0 license; this proposal uses their public implementation directly.
