# Native row indication proposal

Status: source-only, outside the frozen native migration candidate. No build, device execution or pixel/frame pass. The proposed implementation is `ReverbRowIndication.kt`, SHA-256 `94b6853e187c9e988ac781c3688733e55975aec6fcb35b538df62a0a66f37680`.

This is a foreground Drawable/controller on the row's existing attached Android View. It adds no View, virtual tree, diff engine, selector resource, pool or Compose runtime dependency. A RippleDrawable is created lazily on the first accepted press. A Choreographer callback exists only while the separate state layer is transitioning. Those objects and callbacks still have costs: this is not a claim of literally zero allocation/runtime overhead.

## Pinned source contract

AndroidX material-ripple Android1.11.2 sources were downloaded read-only from Google Maven:
https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple-android/1.11.2/material-ripple-android-1.11.2-sources.jar

Jar SHA-256: `65b50cad7a19b27d55e319536478069bef9e7b185c55a5363f8660fcef7bab93`. Extracted sources are retained in `pinned-sources/`. `Material3Ripple.kt` is from pinned Material3 Android1.4.0, SHA-256 `4553848987696d7158587b4df50a941c043c6fe3c843a6c7e4f42df5930f2a2f`. AndroidX Apache2 attribution/license is retained.

| Contract | Source | Native proposal |
| --- | --- | --- |
| Default clickable/toggleable indication | M3 Ripple.kt74–83,270–302 | Bounded; unspecified radius; actual inherited LocalContentColor. Caller supplies the resolved color. |
| Onboarding ripple color | MainActivity OnboardingGroup, M3 ColorScheme.contentColorFor1064–1091 | Group field usually resolves to onSurface. Resolve the actual inherited content color rather than infer it from each label/icon tint. Equality precedence in contentColorFor remains caller-owned. |
| Press alpha | StateTokens.kt |0.10 replacement alpha, not multiplication by incoming color alpha. Reverb minSdk28 makes the source's pre28 doubled-alpha branch unreachable. |
| End radius | ripple/RippleAnimation.kt168–177 | sqrt(width²+height²)/2 +10dp; platform radius rounded with roundToInt. Never RADIUS_AUTO. |
| Radius/bounds update order | RippleHostView165–188 | Explicit radius first, then color, then bounds. Property equality avoids invalidation loops. |
| Touch origin | RippleHostView143–153 | Exact accepted press location, row-local. Keyboard host supplies row center. No moving hotspot following ACTION_MOVE. |
| Press states | RippleHostView259–261 | Only pressed+enabled or empty. Platform focus/hover states are never passed to RippleDrawable. |
| Fast release/cancel | RippleHostView209–257 | Release/cancel both start fade-out. If last state change was less than5ms ago, post resting state50ms later. Flush a pending reset before the next state change. Do not delay the click action. |
| State layer | ripple/Ripple.kt433–529 | Separate latest-active-interaction stack. Hover alpha.08, focus.10, drag.16. Incoming hover15ms, focus/drag45ms; outgoing hover/focus15ms, drag150ms. All linear. Draw content, then state circle clipped to row bounds, then ripple. |
| Tween arithmetic | animation-core FloatAnimationSpec210–223, VectorConverters | Nanosecond fraction; `initial*(1-f)+target*f`. The port preserves first-frame start and interruption from the previous animation frame. Duration scale comes from the host's existing system-aware source. |
| Projection | RippleHostView271–343 | Bounded rectangular white mask and isProjected=false. Outer group owns its rounded clipping. |

The actual released onboarding row modifiers are MainActivity912–992: permission rows use default clickable, buffer/audio rows use default toggleable. Custom controls with `indication=null` must retain no ripple; this is not a blanket replacement of every control.

## Integration contract

Create once for a row and install **before** `ReverbGroupLayout.addRow` installs the row's divider wrapper:

```kotlin
val indication = ReverbRowIndication.install(row, inheritedContentColor) { currentDurationScale }
```

Call `onMeasured(widthPx,heightPx,density)` from the row's actual measurement/layout path. Its supplied geometry must match the clickable modifier's full bounds, including its padding. No independent inset, shape, or minimum target is introduced.

The row's interaction controller calls `press(x,y)`, `release()` or `cancel()`. Hover/focus/drag use identity tokens returned by `enterState(kind)` and removed by `leaveState(token)`; latest active interaction wins. `updateColor` changes the source color without replacing the active ripple. Android's already-running RenderThread ripple color/radius limitations remain those of the release backend.

The indication neither clicks nor toggles, emits accessibility actions, moves focus, handles hit testing, or decides whether the parent consumed a gesture. The native row owns those behaviors. A normal platform `View.isPressed` listener is not a substitute for the following released foundation rules, independently checked against foundation1.11.2:

- Clickable.kt2082–2095,2276–2287: delay a touch press when an interested gesture-consumption parent or native ancestor requests delayed pressed state. The default foundation flag selects the gesture-consumption path.
- Clickable.android.kt25–40: native ancestor `shouldDelayChildPressedState()`; delay is `ViewConfiguration.getTapTimeout()`.
- Clickable.kt2138–2182: a successful quick tap before the delay emits Press→Release together after cancelling that exact pending delay. Click runs immediately at pointer up.
- Clickable.kt2192–2220: cancellation before delayed Press produces no ripple; cancellation after Press sends Cancel. Out-of-bounds/consumed input and detach cancel the tracked interactions.
- Clickable.kt669–687,1982–2019: DpadCenter, Enter, NumpadEnter and Space produce a centered initial Press, no repeated-press interaction for key repeats, and click only for the matching tracked key up. Focus loss cancels key presses.
- Toggleable.kt285–309 uses the same ClickableNode timing; it only changes the click callback.

These gesture/input rules are deliberately not reimplemented inside a drawing helper. They remain a concrete dependency for onboarding-row integration.

## Lifecycle and callback ownership

The previous foreground is preserved. Installation order matters because View.setForeground clears its previous Drawable callback; the implementation installs itself first and then wraps the previous foreground. The group divider wrapper can then wrap this indication, yielding prior foreground → indication → separator. Never install the indication around an already-installed divider: that reverses released draw order.

Ripple invalidations/schedules go through the installed foreground callback chain. Scheduled ripple work is tracked until execution so detach can remove only ripple callbacks, without cancelling another foreground's animation. The50ms press reset is posted to the owning View and cancelled explicitly. Native View visibility is forwarded to the nested ripple; reattachment restores visibility. Detach clears active interactions, pending presses and state animation, stops the ripple and removes callbacks. Valid measured geometry survives reattachment, matching RippleNode's retained size.

Source review found and corrected an initial missing visibility restoration after detach. A second reviewer found no further callback/state-alpha arithmetic defect, but this is source evidence only. Repeated detach/reattach and rapid cancellation still need native validation.

## Why no Compose host bridge

Compose's Android backend uses a zero-size RippleContainer and up to five RippleHostViews because it must host an Android Drawable/RenderNode inside Compose drawing. That pool/host adaptation is retained only under `reference-only-compose-bridge/` as source analysis; **do not integrate those files**. A native row already supplies an attached View, Drawable callback and render-node lifetime. The actual proposal therefore uses the existing native foreground.

This architecture needs actual hardware proof: drawing RippleDrawable as a native foreground rather than Compose's hosted background may differ in RenderThread phase or platform lifecycle details. Source similarity is not that proof. If the native foreground path fails the exact ripple fixture, inspect the platform draw path before introducing any bridge or tolerance.

The source Compose pool also caps host ownership at five. This proposal does not reproduce cross-row pool eviction, and must not be advertised as global six-simultaneous-ripple parity. The immediate onboarding group has at most five rows. Any later screen whose accepted interactions expose that pool limit needs a specific disposition (potentially a lightweight active-owner cap, not extra Views).

## Required finite A/B before integration

Use actual released default clickable/toggleable rows and the existing physical-phone fixture, with identical theme/density/dimensions, explicit press coordinates and observed frame boundaries:

1. Held press at center and near edge; normal release.
2. Press→release within the same animation timestamp; verify the source50ms reset path and immediate action behavior.
3. Delayed press cancelled by parent scroll before timeout; no press ripple.
4. Press cancellation after timeout; fade-out rather than instant disappearance.
5. Hover→focus→hover removal; token ordering and15/45ms state layer.
6. Key down/repeat/up and focus-loss cancellation.
7. Detach while pressed, then reattach and press again; no stuck or invisible ripple.
8. Palette/size update during an active ripple, and indication inside the actual grouped first/middle/last rows.

Record exact dispatch/frame timestamps and source/native captures. RenderThread animation and Compose clock phase are **unqualified**; no guessed ValueAnimator timings, tolerance or visual similarity may turn them into a pass. This row helper is not full Material Button parity: filled Button shape, hover elevation/motion and content/layout are separate contracts.
