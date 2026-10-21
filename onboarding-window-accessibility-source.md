# Onboarding footer and pager accessibility proposal

Integrated into the native-hosts candidate from the ignored onboarding-accessibility-proposal directory. No build, device run, or production routing change has qualified this addition. Candidate screens1's compiler correction is separate. Native node/event qualification and the documented host obligations remain outstanding.

## Files and binding

- The source of `nativeui/OnboardingFooterView.kt` and `nativeui/NativePageTextAccessibilityOwner.kt` is recorded in the ignored `footer-window-accessibility.patch` and full proposal copies.
- `NativeOnboardingPagerAccessibility.kt` is a direct delegate and typed event boundary for the existing single pager engine. It does not copy navigation state or render a second tree.
- `NativeWindowScrollAccessibilityEvents.kt` implements that shared window queue. Reliability-source `NativeWindowScrollEventCases.kt` is an unexecuted QA-only regression helper for the real queue with deterministic coroutine scheduling and captured transport.
- `source/SOURCE-SHA256.json` covers all 11 saved pinned source files. The earlier `PROVENANCE.json` contains URLs for the initial subset. Additional files come from the same public Google Maven ui/foundation 1.11.2 or material3 1.4.0 sources jars; no released Reverb source is changed.

The footer lease is `footer.bindTextAccessibility(windowOwner, sourceExposure)`. It uses the same `PlainTextAccessibilityWindowScope` as page labels/status descriptions, binds the actual label Views, and keeps child keyboard focus absent. Each button has a merging generic parent, a real Text child and a separate non-drawing role-only Button child. Back disabled state and Continue readiness remain the existing renderer/controller facts. These buttons have source heightIn(min=48dp) on the outer modifier; unlike IconButton's inner40dp visual, their source visual/touch bounds coincide under this footer's bounds. The generic row helper's documentation currently describes its original64dp row users; this48dp footer is the additional no-expansion invariant.

The parent must close the footer lease on disposal, not every bind/frame. Native detach cancels input through existing lifecycle code but does not silently discard source callback ownership. Exact pixels and button callbacks are untouched by this patch; this is still subject to the complete fixture.

For the pager:
1. Install one `NativeOnboardingPagerAccessibility` on the actual pager viewport.
2. Its actual View overrides `createAccessibilityNodeInfo()` and checks `isNodePresent()` before `super`. View.AccessibilityDelegate has no create hook.
3. Supply the actual one pager/scope and `userScrollEnabled=!finishing&&!predictiveBack`.
4. Supply exact source geometry, source node presence/visibility and actual semantic viewport children after source placement/occlusion pruning.
5. Call `sourceStateChanged()` after the existing pager state update. First observation seeds the old range and emits no initial scroll event.
6. Open one queue via `windowOwner.openScrollEvents(scope, onScrollGeometryChanged, onBatchFinished)` and pass it to pager and vertical owners. There is no per-control fallback event timer.
7. Close this delegate when the host is disposed; source scope still owns accepted animation cancellation.

## Exact source contracts

MainActivity's released footer is lines667–722 at3e16f8d: equalweight48dp-min buttons, separate progress dots, Back enabled by page, Continue enabled by !finishing and only recolored by readiness. M3 Button137–166 and Surface214–239 preserve role on the parent semantic modifier plus merging clickable and child Text.

Pinned ui SemanticsNode467–475 appends a role-only fake child to merging nodes with real children. Delegate620–650 maps role class only for fake/leaf nodes. Parent text is not synthesized from child labels. SemanticsOwner183–193 uses parent visual bounds for fake-role bounds. Button nodes keep the actual clickable state/focus and child labels retain original paragraph traversal through the shared cursor.

Pinned pager LazyLayoutSemantics180–194 declares traversal group, horizontal range, optional ScrollToIndex and collection info. Pager-specific LazyLayoutSemanticState maps range to currentAbsoluteScrollOffset and max to calculateNewMaxScrollOffset; collection is1×3. Delegate1007–1051 keeps generic View class because collection info exists, provides edge-aware forward/back and RTL physical scroll actions. Explicit PageLeft/Right are logical previous/next, regardless RTL. Pager.kt source actions check canScroll then launch animateScrollToPage using currentPage read inside the coroutine. They do not use the app footer navigation transaction.

Disabling user scrolling removes actions; it does not add Disabled semantics. The pager remains enabled=true and retains collection/range. Scrollable Focusability.Never contributes no keyboard Focused property. API28 lacks PageLeft/Right native actions; API29+ exposes both at edges and returns false if unable to move. Source native delegate does not map ScrollToIndex to ACTION_SCROLL_TO_POSITION.

Native accessibility focus remains platform-owned. Stock select/clear-selection/show-on-screen/focus actions are not forwarded. Page actions use the existing low-level pager animation; no synthetic click, selected page event, or automatic keyboard focus transfer is invented.

## Window responsibilities deliberately not approximated

The source delegate2366–2411/2438–2465 batches subtree-change then TYPE_VIEW_SCROLLED events across the window, with100ms between batches. Delta observation2950–3033 seeds old offsets, schedules only changes, and reads latest range on dispatch. It also updates the accessibility focus rect and semantic structure cache. The new queue proposal uses source Channel(1), immediate first batch then100ms gaps, one ArraySet of pending actual registrations, per-batch subtree target deduplication, and latest range lambdas. It has no continuously running timer and does not reset the delay on each movement. Pending attached sources remain deliverable after semantic visibility changes; retired, detached or reparented sources are suppressed. Old close handles cannot affect replacement registrations. Parent-scope cancellation closes platform listeners/registrations and releases window ownership.

The queue mirrors cached AccessibilityManager service admission and attach/state listeners, excluding UIAutomator-only state. Test-only force-enable/captured event transport correspond to the pinned delegate's own test seams; they change no system accessibility state. The QA helper checks batching, coalescing/latest values, max-only layout events, simultaneous horizontal/vertical changes, token retirement, hidden-after-scheduling behavior, detach/reattach and parent cancellation. It has not been compiled or run.

Native integration must remove current vertical stock onScrollChanged accessibility delivery and the max-change direct content event before using the queue, preserving their actual child placement. Call scrollChanged after placement, not before coordinates move; max-only changes call layoutChanged. Callback onScrollGeometryChanged belongs the existing window focus owner; native actual View focus geometry must be qualified rather than importing a separate virtual focus cache. Callback onBatchFinished posts/coalesces the existing source-equivalent structural/bounds reconciliation. These window callbacks, actual event transport, physical timing and focus visuals remain unqualified. The queue implementation alone does not prove them.

Retaining three native page Views must not expose all three. Source placed/lazy-visible page membership and sibling occlusion matter. The host must not use NO_HIDE_DESCENDANTS to approximate a predictive preview: SemanticsOwner249–269 includes attached/placed intersecting hidden nodes, delegate807 marks visibility separately, and descendant traversal is not blanket suppressed. Ancestor alpha0 does not by itself prove semantic absence. Likewise an exposed:false callback must classify hidden nodes rather than delete them.

Partially visible merging rows can retain completely offscreen Text/status descendants with unclipped bounds and visibleToUser=false under the pinned ui flag. Existing clipped-only page helpers have not yet been fully qualified for this edge. This proposal does not silently normalize that difference or weaken the strict fixture.

Keyboard descendant focus discovery/bring-into-view must follow the existing pager and vertical owners. The adapter does not make all offscreen pages focusable or permanently hide them from focus to manufacture a pass.

## Finite qualification still needed

- Source build, then native footer raw node tree: generic parent, real original label and role-only child; Back disabled vs enabled and Continue unready vs finishing.
- Native pager raw collection/actions at pages0/1/2, RTL physical scroll vs logical PageLeft/Right, and API28 omission of page actions.
- Same-window original-label cursor traversal across page and footer; text never a new keyboard tab stop.
- Predictive preview visible/hidden/interrupted topology and keyboard focus compared to source, including alpha0 node presence.
- One shared-window event capture across pager and vertical scroll to verify actual payload, batching, focus bounds and disposal, not source inference.
