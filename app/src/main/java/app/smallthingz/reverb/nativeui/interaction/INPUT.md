# Native click input adapter

The source-derived proposal implements the direct-pointer and keyboard paths actually used by released Reverb's clickable/toggleable and combinedClickable controls. It attaches to an existing Android View and sends identity-bearing interactions to the existing foreground renderer. It adds no View, renderer tree, reactive diff, global listener, or polling job. Handler timers and interaction tokens still have ordinary runtime costs; this is not a zero-cost claim.

Reference: v0.1.2, commit 3e16f8ddb78dca15df4b436d34d330801ce83ffd; AndroidX foundation/ui 1.11.2. Exact extracted-source hashes are in source-provenance.json. The default non-suspending combinedClickable implementation is authoritative. Released Reverb has no onDoubleClick callback, so this adapter does not introduce double-click recognition.

## Files and ownership

- ReverbClickController.kt: one input controller, immutable gesture-owner binding, event tokens, and explicit parent-routing seam.
- ReverbRowIndicationSink.kt: adapter to the separately implemented ReverbRowIndication foreground. Press positions are explicitly mapped between input and drawing coordinate spaces.
- LICENSE-2.0.txt and source-provenance.json: source license and pinned references.

This directory retains the proposal. Parent-authorized integration is in the successor native-integration-20261021 worktree, not the frozen native-views-20261021 candidate. Neither this source review nor copying a file proves compilation, actual dispatch timing, or pixel parity.

## Host wiring

Create ReverbClickController(inputHost, environment, indication). The input View remains responsible for semantics, action labels, role, click accessibility events, focusability, and its normal lifecycle. The indication can draw on a distinct existing View.

Bind NativeClickBinding(ownerKey, enabled, onClick, onLongClick?, hapticFeedbackEnabled). Keys are immutable and value-equal only for the same logical item. Same-key enrichment updates the action lambdas immediately without canceling an active gesture. Changing the key, enablement, or presence of a long-click action retires pointer/key ownership. A same-owner, still-enabled long-click capability change preserves the exact focus interaction because the released FocusableNode remains focused. bind(null) models a removed clickable modifier; disabled-but-present is a distinct state.

Forward attached/detached, touch, key, hover, and focusChanged. Detach retires all old callbacks and clears the binding; a retained/recycled View must bind its current owner when reattached. A generic cancel cancels pointer/key/hover interaction without inventing a focus loss. The host should forward real focus state after replacing the semantic owner. Calls are main-thread serialized.

The parent's actual gesture router implements NativeClickEnvironment:
- admitDown checks the released clip/shape and touch-only minimum-target admission.
- interestedParent is interest in this particular down, not a blanket “a scroll or pager exists” flag.
- minimumTouchWidthPx/minimumTouchHeightPx use the released ViewConfiguration minimum target.
- longPressOwnershipChanged coordinates consumption with the current native parent recognizer.
- touch(event, consumedByOther) supplies source-equivalent Main-pass consumption; afterParentDispatch supplies Final-pass consumption. Native interception ACTION_CANCEL must also reach the controller.

A bare Android ViewGroup does not supply Compose's Main/Final passes. These environment methods are an executable integration seam, not evidence that an arbitrary parent is equivalent. The onboarding/card owner must implement them from the actual parent gesture route.

For the foreground, install ReverbRowIndication before the group wraps its foreground with a divider. Pass NativeRowIndicationSink the foreground and a mandatory mapPressPosition(x, y, outputPointF). The callback must populate both output coordinates. Use identity only when input and drawing coordinates coincide. For an untransformed direct content child, subtract content.left/top; the footer's 48 dp input target and inset 40 dp content therefore retain the correct hotspot. A transformed hierarchy requires the actual transform. The sink retains one PointF rather than allocating a new point per press.

Do not install the same Drawable on both the input and indication Views. The drawing host owns its callback and foreground exactly once. A filled Button's elevation observer can receive the same identity-bearing stream in addition to the row indication; a row ripple does not itself implement Button elevation.

Action lambdas may call inputHost.performClick() with a separate current app listener, preserving native accessibility click events. Do not wire performClick back into the controller recursively. Pointer long press performs the source haptic after its callback; routing that callback through an additional default platform performLongClick haptic would double-vibrate. Keyboard long press does not add the pointer haptic.

## Source behavior retained

Clickable.kt 2082–2224 and Clickable.android.kt 25–40: press indication is delayed only for an interested gesture parent or a native ancestor whose shouldDelayChildPressedState returns true. The delay is ViewConfiguration.getTapTimeout(). A successful quick tap cancels its exact pending delay and queues Press then Release together; the app action runs synchronously on up, not after the ripple. A canceled delayed press emits no Press. Delivered interactions terminate with the exact matching token.

Clickable.kt 877–1032, 1146–1197 and PointerEvent.kt 1230–1248: direct pointer cancellation checks all current pointers, not just the first pointer id. Finger touch alone receives the minimum-target rectangular padding; mouse/stylus do not. Comparisons are strict, so x==width and y==height remain inside. Ordinary up is checked before coordinate bounds. A second pointer does not automatically cancel, and lifting the original finger while another remains does not finish the gesture. Android does not impose the Desktop-only primary-mouse-button restriction. Native pointer slop is not substituted for the direct-click boundary rule.

The initial shape admission is separate from movement cancellation: after an admitted down, movement within the rectangular source bounds does not cancel just because it crosses a rounded corner. A source-equivalent parent must route touch-only minimum-target hits outside the child bounds without recentering the original hotspot; a stock TouchDelegate cannot be assumed equivalent.

CombinedClickable's platform long-press timeout and API29 deep-press path invoke the latest long-click action, then the latest mutable haptic setting. As in the released node, an already-executing callback does not gain a new post-callback detach/disable guard; retirement prevents pending future callbacks. Once long press owns the stream, a later up does not click. Explicit native cancellation still retires it. Queued work is fenced by the binding generation; a recycled owner cannot receive an old timeout or feedback.

Clickable.kt 669–687, 1565–1655, 1982–2023: DpadCenter, Enter, NumpadEnter and Space emit a centered initial Press, ignore repeat Presses, and act only on the matching tracked up. Each active key has its own long timer. Keyboard long press suppresses the up click. Focus loss cancels key interactions without canceling an unrelated direct pointer. Delayed work captures keyCode, not a recyclable KeyEvent object.

Hover and focus remain separate identity-bearing interactions. The host must also call its native super.onHoverEvent so accessibility hover events remain available; platform drawableState must not drive a second ripple/state layer.

## Honest boundaries

1. Direct pointer, key, hover, and focus paths are implemented. The released indirect/trackpad path is separate and is not implemented here. It has distinct slop/gesture semantics; do not credit it from touch results.
2. Main/Final pointer routing and parent gesture-interest behavior require actual host integration. Android's native interception provides cancellation but not automatically every Compose pass.
3. Interaction delivery uses the main Handler. It preserves queued ordering and synchronous app actions, but AndroidUiDispatcher/coroutine versus Handler/frame phase remains unmeasured. Do not adjust delays or ripple constants to conceal a scheduling mismatch.
4. Timer admission, multi-pointer/platform event mapping, native accessibility action routing, minimum-target hit arbitration, and detach/recycle behavior require actual device qualification. Source review is not that qualification.
5. Native ripple RenderThread/foreground phase and Material3 Button elevation remain their own qualified components. This controller does not turn the current renderer into a proven pixel-identical one.
6. Parent dispatch and drawing transforms must be stable for an admitted event; this adapter does not create a global gesture router or force another component to release input.

## Finite qualification cases

Compare released controls and the direct View adapter with identical input timelines and record action/interaction tokens, hotspot, current owner, rendered indication, and lifecycle:
1. Quick tap before the scroll-parent tap timeout; stationary press after timeout; parent consumption/cancel before and after timeout.
2. Finger boundary equality and one-pixel-outside; stylus/mouse without minimum padding; rounded-corner admission versus already-admitted movement; outside-child 48 dp near-hit retaining its real hotspot.
3. Two pointers with original-pointer lift first, final-pointer lift, consumed move/up, and explicit cancel. Deep press when supported.
4. Pointer long press, callback switching same-owner haptic to false, pointer up after long press, and owner replacement/disable/detach before timeout.
5. All four activation keys, repeats, unmatched up, key long press, simultaneous keys, and focus loss while a key is held.
6. Same-key callback enrichment during press versus different-key recycle; long-click capability change while focused, both before and after focus-token delivery.
7. Hover enter/exit with native accessibility hover handling, focus retained through scroll cancellation, reattach/rebind, and interaction cancellation after a queued callback.
8. Input host versus inset indication host hotspot, including centered keyboard press; group foreground divider remains above the one indication.

No compilation, host execution, or device input was run by this lane. Independent bounded source review found and corrected focus loss on same-owner capability change and stale post-callback haptic settings. Self-review corrected input-to-indication hotspot mapping, retained KeyEvent capture, deep-press ordering, and focus preservation during generic cancel. These are source findings, not new runtime passes.
