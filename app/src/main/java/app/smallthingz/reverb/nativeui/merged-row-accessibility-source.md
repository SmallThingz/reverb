# Merged onboarding row accessibility delegate

`ReverbMergedRowAccessibility.kt` and the permission/buffer/audio row bindings are integrated in native-screens. They are not yet compiled or device-qualified. It changes no rendering, recorder/backend, permissions or system accessibility settings.

## Why a native semantic child instead of a mixed provider

The first draft used an AccessibilityNodeProvider on the existing row and returned actual child Views plus one virtual role child. Source review rejected that design before integration: Android16 AccessibilityInteractionController1665–1684 prefetches all provider child IDs through the same provider using only their virtual-descendant ID. A real View child contributes HOST_VIEW_ID, which would resolve back to the row. ViewGroup3819–3832 also skips adding its native children when a provider exists. This is a concrete platform incompatibility, not a reason to hide or flatten label children.

The replacement keeps the normal native hierarchy. It adds one ordinary non-drawing, non-input View as the source role-only semantic child and a direct row delegate. The role View has no background, outline, layer, content drawing, gesture handling, keyboard stop or app state. Existing actual Text/status Views keep their native ownership. The only added retained object is the required role child plus the direct delegate; no parallel semantic/render state tree is introduced.

The row owner appends `roleView` after its ordinary children and measures/layouts it to the row's full bounds. That does not affect the custom row's measured size or place any painted pixels; full bounds preserve native accessibility-focus geometry. Touch/hover/key dispatch all return false. Native Android owns focus across parent, labels, role child and the rest of the window. A zero-size role View was deliberately avoided because it would produce wrong native accessibility-focus geometry even if node bounds were overridden.

## Direct integration API

Create `ReverbMergedRowAccessibility(host, role, sourceState, invokeSourceClick)` once for the row identity, then install it as the row's accessibilityDelegate and append its roleView. Onboarding permission uses BUTTON; buffer/audio rows use SWITCH. Same-identity binds update existing row fields read by sourceState. A different recycled identity gets a new delegate/role child. Owner-controlled row disposal removes the child/delegate; a detach/reattach does not recreate semantic identity.

SourceState reads enabled/checked/click label and real ordered semantic child Views without copying child text. The page/window owner installs an explicit float geometry and source-exposure lease through bindGeometry. Snapshot computes current presence/visibility and screen bounds from those existing Views; do not use getGlobalVisibleRect. The helper `nativeMergedRoleBoundsInScreen` consumes the existing float root geometry and preserves the source round-before-screen-map rule.

`invokeSourceClick` must invoke the current source action callback directly, not View.performClick. The delegate emits exactly one source click envelope after invocation, including a false callback result. Disabled source parents retain isClickable=true but have no ACTION_CLICK and reject it. Native focus actions remain delegated to Android, with the same source enabled gate for keyboard focus.

Remove previous row onInitializeAccessibilityNodeInfo overrides that inject spokenText/newlines, status description or parent Button/Switch class; they must not overwrite this delegate after the super call. Expose real child nodes in exact source drawing order and remove NO_HIDE_DESCENDANTS on their structural ancestors. Paragraph children remain non-keyboard-focusable. Source merging parents are screenReaderFocusable=true; exposed Text/status descendants under them are false. Standalone page Text is true. Keep permission status description on its actual Icon child. A nested Icon under a merging row does not automatically get ImageView class; pinned Image-role mapping is conditional. Decorative Switch(onCheckedChange=null) has no independent source semantics.

## Pinned node contract

The clickable/toggleable parent has no own Text or ContentDescription. With real children its class is android.view.View, screenReaderFocusable is true while not hidden, and it owns enabled/click/focus state. Switch parents additionally have checkable/checked and localized On/Off stateDescription. RoleDescription is absent on that parent because role mapping is deferred to the role-only child.

The role-only child:

- Has Button class for BUTTON; for SWITCH it keeps android.view.View class and localized switch_role roleDescription.
- Has no text, contentDescription, stateDescription, checkable/checked state, click action, keyboard focus or traversal actions.
- Is enabled=true even if the parent is disabled: its source configuration contains only Role, never Disabled.
- Is screenReaderFocusable=false because a fake node is neither merging nor an unmerged leaf.
- Exposes only accessibility-focus/clear-focus actions and follows current source visibility/importance.
- Comes after actual semantic children in drawing order.
- Uses the parent's clipped boundsInRoot, rounded to IntRect before root-to-screen mapping and floor/ceil output. It does not reuse parent touch-target/occlusion-adjusted bounds. Source unplaced fallback is Rect(0,0,10,10) in root space.

Source references, ui-android1.11.2:

- SemanticsNode100–106: a real leaf under a merging ancestor is not isUnmergedLeafNode.
- SemanticsNode439–440/467–475/494–522: fake parent coordinator, role-only child, separate configuration.
- SemanticsOwner149/183–194:10x10 fallback and parent clipped bounds rounded to IntRect.
- AndroidComposeViewAccessibilityDelegateCompat578–609: screen transform/rounding.
- Delegate621–650/696–708/789–838/1145/3456–3473: class/role, focus, enabled/click, screen-reader grouping and unmerged text.
- Delegate1242–1267/1392–1403: accessibility focus is allowed separately from enabled state; Android native focus ownership is used here.
- Delegate1486–1490/1345–1353: click callback then exactly one enabled=true/android.view.View event envelope.

Android16 native sources:

- ViewGroup2477–2496: exposed real children follow visibility/importance and do not require drawing.
- AccessibilityInteractionController171–173: real-node admission uses native visibility, not nonzero rendered bounds.
- View15184–15202: native accessibility focus owns ViewRootImpl focus and is permitted for a visible non-keyboard-focusable View.

## Remaining qualification and integration limits

No actual node, focus or event pass is claimed. Kotlin nullability/overload compilation, native child-order prefetch, role focus highlight, parent/child callbacks, disabled states, clipping and actual unmerged trees must be checked in the isolated physical-device fixture. Keep strict role-only canonical comparison and full raw nodes. Internal Compose semantic-ID extras are not fabricated as native identities.

The bound geometry preserves the bounded page/host correspondence. Released onboarding rows are at least64dp high, full-width and nonoverlapping, so their minimum48dp touch expansion is zero and their adjusted parent bounds equal the clipped parent rectangle. This equality is not generalized to arbitrary rows. Arbitrary overlapping layout occlusion is not approximated by this delegate. The native page has ordinary nonoverlapping rows; pager/viewport host visibility remains its owner's responsibility. Real Text traversal and character extras still use the shared window PlainTextAccessibility binding; this delegate never replaces them.

The project minimum SDK is28. Direct screenReaderFocusable properties on View and AccessibilityNodeInfo are API28 and supported on every admitted device. Status contentDescription nodes share the same window cursor as text but expose only character/word/paragraph granularities, with no layout-derived boxes.
