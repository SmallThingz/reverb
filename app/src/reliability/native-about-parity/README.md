# About dual-window qualification proposal

Status: integrated into native-hosts reliability sources after bounded review; uncompiled and unexecuted. No product route, build, install, phone input, permission, system setting, or recording was changed.

`NativeAboutParityProbe.kt` adds one reliability-only entry, `verifyNativeAboutParity(instrumentation, report)`. It uses the existing isolated `NativeParityActivity`, `nativeParityOnMain`, launch/draw helpers, exact bitmap comparison, and PNG persistence. The native_about_parity entry is registered in ReliabilityInstrumentation and all six oracle hashes are pinned in the existing verifyNativeParityOracle build guard. No other fixture behavior or product source was changed.

## Oracle and transformations

The fixture calls the existing public `AboutDialog` directly. Its body, modifiers, source window, animation, predictive Back handler, strings, resource decoding and version lookup are unchanged. There is **no extraction, visibility change, function rename, body substitution, fake animation clock, accessibility enabling, or replacement drawing oracle**. `SOURCE.json` pins the complete source file plus five direct drawing/motion dependencies. Each was compared byte-for-byte with `v0.1.2`. `verify_about_oracle.py PROJECT_ROOT` only reads and verifies those hashes.

The only inputs changed between cases are explicit local light/dark `Configuration`, corresponding Material3 dynamic/default colors, and, for one labelled stress case, the own Dialog Window height. The real device density/font scale/insets remain current. Both implementations receive the same `Typography()` and local configuration. The reference receives an attached per-configuration FrameLayout and its public `ComposeViewContext`, exactly as the reviewed Brand fixture does, before its first composition. The Dialog inherits that parent composition context. This isolates the root ImageVectorCache across Light/Dark synthetic configurations; explicit LocalResources matches the local Context. Native/reference node absence is retained rather than dereferenced. No global theme or animation scale is changed. The short case sets both already-created windows to exactly 240dp height; it qualifies constrained content/clipping, not the unmodified production window-height policy. Full cases retain the original production window policy.

## Bounded cases

- Full Light and full Dark windows: exact settled ARGB comparison, repeat capture stability, window attributes/insets and semantic snapshots. Close uses freshly resolved source semantics/native description identity and current bounds, dispatching only to the exact own DecorView.
- Dark 240dp window: exact initial clipped pixels and repeat stability, source/native semantic scroll-forward action, actual increased offset and a retained scrolled capture, then ordinary Back. The scrolled images are evidence, not falsely compared at potentially different spring/fling times.
- Fresh Dark windows: keyboard Close, ordinary Back, repeated ordinary Back, and predictive start/progress/cancel followed by start/progress/commit. Cancellation must restore the original exact settled pixels and deliver no dismissal. Every completion must deliver exactly one owner dismissal and remove its own window.

Repeated Back is explicitly unexercised if the first event has already dismissed under the current animation scale. It does not receive a passing result. Keyboard focus requests do not change native focusability or the device input mode; inability to admit native focus is preserved as a fixture/input-mode limitation for diagnosis, not silently worked around.

## Ownership and input

The Activity guard is retained before opening a dialog. While the dialog is open, Activity focus is correctly absent. The new window guard requires the exact newly created QA window, attached/visible focused decor, physical display 0, expected isolated package, live host, and membership in public `WindowInspector`. It runs in the same main-thread dispatch before each input and PixelCopy submission. Reference discovery considers only windows created after its guarded opening and rejects ambiguity. It does not claim ownership from a foreign window label, dismiss another window, or reacquire focus. The public inspector requires API29; there is no hidden-API fallback.

All touch/key/Back events are direct dispatch to the fixture's own View or actual own `OnBackPressedDispatcher`. No device-wide input injection or platform Back gesture is synthesized. Pointer down/up preserve one real downTime; target identity/bounds are re-read before each action. Only read-only public `ViewRootForTest.semanticsOwner` inspection is used; force-accessibility/test clock setters are never used. Repository links are not activated, so no browser or external activity is launched.

## Evidence and limits

Every image is saved before comparison, including a layout-size mismatch. A fresh private QA evidence directory contains source/native/repeat PNGs, the short scrolled and predictive partial images, exact ARGB differences, semantic/window snapshots, callbacks and raw Choreographer frame timestamps/Close bounds. Partial traces and terminal/cleanup errors survive a failure. PixelCopy timeout retains its bitmap until an already-submitted callback finishes. Cleanup owns only the fixture's windows/composition and Activity.

The raw frame trace records the actual callback phase; it is not normalized to pretend the two sequential runs shared a frame clock. Close bounds show rendered motion placement but do not prove fade pixels or RenderThread timing. The current fixture therefore sets `motion_phase_qualified=false`, even when static pixels and terminal actions pass. It does not claim full system predictive-Back dispatch, stopped/resumed Activity behavior, repository launch/error handling, accessibility service enumeration/TalkBack, mouse/indirect-input routing, or complete frame-for-frame entry/exit identity. Those remain distinct obligations and must not be credited from this fixture.
