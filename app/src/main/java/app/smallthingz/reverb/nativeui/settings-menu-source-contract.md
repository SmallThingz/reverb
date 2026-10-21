# Settings audio dropdown proposal

Reference: released v0.1.2 `3e16f8ddb78dca15df4b436d34d330801ce83ffd`, unchanged SettingsScreen.kt2397–2486.

This source contract accompanies the native popup window, exact menu presentation, typed owner, and direct audio-section assembly. It does not route MainActivity or change the released SettingsScreen/Home oracle. No build or device action was performed by this lane. The source was integrated after parent and independent bounded review; production routing remains unchanged.

## Source contract

- Material3 AndroidMenu.android.kt40–89: one mutable transition per dropdown call; Popup exists while currentState OR targetState. Closing an unfinished initial entry removes that content before its transition can process an exit. A completed entry retains its popup through exit. Reopening an exit updates currentState to the previous target.
- Material3 Menu.kt359–415,419–520: FastSpatial(.9,1400) scale .8→1; FastEffects(1,3800) alpha0→1;18dp released Surface shape, raised color,0dp tonal elevation,3dp shadow. Fixed8dp vertical outer padding, intrinsic maximum width, inner vertical scroll.
- Released items: labelLarge, ink when selected/muted otherwise; selected primary check with no description;16dp horizontal/8dp vertical item padding,48dp minimum height,112–280dp width bounds,12dp item clip. No invented selected semantic flag, per-item background, separator or text.
- MenuPosition.kt: ordered start/end/window horizontal candidates and below/above/center/window vertical candidates;48dp placement margin; float bias alignment rounded separately; integer-overlap transform origins.
- AndroidPopup.android.kt603–1139: native WindowManager application sub-panel, WATCH_OUTSIDE_TOUCH, inherited SECURE, focusable, no platform width cap or platform popup animation;8dp invisible root shadow allocation; parent-window coordinates; full visible-frame measurement; native Back/Escape tracking and API33 overlay Back registration; outside-click dismissal and gesture exclusion rect.
- Transition.kt1035–1108,1176–1194,1381–1387,1458–1489,1559–1579: shared transition clock, Double division/roundToLong duration scale; raw Float velocity retention and delayed children at current play-time on interruption. Uses existing public low-level TargetBasedAnimation/SpringSpec, no Composition.
- Shared NativePagerTouchRoot/NativeWindowPointerCoordinates/NativeClickPhaseNode and NativeVerticalPhaseBinding perform actual source-derived touch dispatch. Pointer geometry is source Surface18dp→scroll clip→reverse placed rows12dp, not native elevation/rectangular hit-test approximation. Popup has no nested scroll ancestor.
- Retained native ScrollState survives close/reopen. Saved integer map is exported to the screen saved-state owner. Removing a conditional choice drops that choice's state. The current typed selection callback runs before dismissal; an exception preserves source statement order.
- ViewTree lifecycle/store/saved-state owners inherit from the anchor. Explicit close releases pointer/window/frame/font ownership; cleanup retains primary failures with suppressed secondary failures.

Pinned sources are cached under old migration `.tmp/native-palette-source/{material3-android-1.4.0,ui-android-1.11.2,animation-core-android-1.11.2}-sources.jar`. Source-derived files carry Apache attribution; source URLs are Google Maven artifacts with those exact versions.

## Assembly and checks

`SettingsAudioChoicesSection` owns only per-choice popup/expanded state and groups the existing five resolved option controls. Durable option validation, raw Settings drafts and commit barriers stay in the existing typed callback/controller owners. Its `active(false)` mirrors the released effect closing menus. `SettingsAudioChoicesView.kt` here is an owned integration copy adding typed popup-content refresh.

Eight focused tests are prepared, unrun: SettingsMenuGeometryTest(4), SettingsMenuTransitionTest(4). Geometry covers placement/RTL/oversized/zero-size cases; transition tests distinguish initial-entry dismissal from settled exit and interrupted reopening.

Independent source review found and corrected four proposal defects: hover must call native accessibility handling even when the input controller handles it; reattachment must rebind the retained item callback after controller detach; unchanged idle-frame position polling must return before querying the full visible-window frame. The latter retains layout/inset updates through the anchor tree's layout listener. Activation keys also stay exclusively controller-owned, including repeated/unmatched events; native clickable fallback is used only for other keys. No latency or IPC cost was measured or asserted.

Remaining qualification is explicit: compilation; actual native-vs-released pixels/geometry, shadow/clip, scroll, keyboard focus/Back/outside behavior and accessibility window/child topology; frame/accepted-target phase. Shared root currently has a direct-touch source port and platform fallback for mouse/indirect inputs; this proposal does not claim that fallback is full source-parity. No timing or performance measurement is claimed. The exact Settings draft IO/normalization adapter and the rest of Settings are separate work; this does not replace them with guessed rules.
