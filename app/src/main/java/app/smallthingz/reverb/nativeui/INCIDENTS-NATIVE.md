# Native Incidents integration

Source oracle: released `3e16f8ddb78dca15df4b436d34d330801ce83ffd`, `IncidentsScreen.kt`, `IncidentsPanelMotion.kt`, `IncidentsBackMotion.kt`, and MainScreen's saved navigation/retained history observer in `MainActivity.kt`. Production routing is unchanged. This source has not yet been compiled or qualified on a device.

## Ownership and root binding

Create one `NativeIncidentsScreenOwner` for the main-screen lifetime with the existing window coroutine scope. Keep it while the overlay is hidden. It retains the existing `IncidentHistoryController` and its durable revision observation; accepted acknowledgement/delete work continues through the existing repository controller. Feed saved `showIncidents` and `showIncidents && !showAboutDialog` through `bindNavigation`. The header and committed predictive Back invoke the supplied current navigation callback.

Create `NativeIncidentsScreenView` with that owner, the same window scope/lifecycle/Back dispatcher, real main pointer root, shared window text owner and optional exact source-node geometry callback. Deliver status-bar insets through `setStatusBarInsets` from the main window's existing inset dispatcher, before descendant consumption. Retain the view as a full-window overlay at source z-order 6. `bindPalette` retains rows/list/popup identity; density/font-resolver changes belong to the main window's existing configuration lifetime.

The renderer is split: history callbacks bind content, while animation callbacks update only Surface translation and alpha. Content/list/menu state is mounted only for the released `showIncidents || settledProgress > 0` predicate. Fully closing disposes it; reopening starts a new list lifetime. Closing the renderer does not close the retained history owner. Closing the main owner releases its observation, Back callback and owned coroutine work, never accepted repository work.

Create one `NativeIncidentsPointerBinding(view, geometry, frames, scope, parentInterested)` using Main's existing window geometry/runtime. Include its `layout` at the same overlay z-order, forward preview PageUp/PageDown to `pageKey(focused, event)`, and close it with the view. Its one membership lease updates only on mount/history-branch/row membership changes. It does not rebind history or relayout during panel animation. The source translated Surface shields underlying controls; exact card/circle clips and 48 dp touch-only near hits are hit-tested by the existing window dispatcher, without MotionEvent redispatch. Existing click endpoints preserve the checkbox-before-card Main-pass consumption order.

The nonempty list uses the same native RV virtualization and source scroll owner as Library, with an empty ancestor nested-scroll chain (Incidents has no pull-to-refresh or surrounding scroller). RV's platform touch/fling recognizer is not dispatched. Leaving the nonempty source branch closes scroll work immediately; detached input nodes retire their retained hit-path identities even if the same physical row is reattached before another event. The header and cards retain their original layout and separate spacing.

## Preserved contracts

- Separate incident cards, original 18 dp shapes, colors, border alpha replacement, source text and dimensions; no Library-style merging.
- Original loading/empty/unavailable/stale-history branches and durable mutation admission.
- Ordinary panel 220 ms tween; predictive continuation and 160 ms cancellation return use the released helpers/generation rules.
- Item animation and spinner sampling inherit the supplied window frame clock and duration scale. No new independent clock or composition is created.
- Incident Copy and conditional Delete use the shared native Settings popup engine via a distinct leading-icon menu-item branch. Settings selection/check rows retain their own original branch. Popup scroll/menu-created lifetime is tied to row identity; source expanded=false precedes actions. Outside/Back invokes the current external dismiss callback when supplied.
- Card semantics retain separate original labels and a real described status icon under role-less merging action parents. One shared window text cursor is reused through `bindContent`; no synthetic joined text.

## Remaining qualification

The new pointer binding still needs compile/API checking and actual final-root interaction qualification. Exact rendered Light/Dark layout, RecyclerView versus released LazyColumn scrolling/item choreography, clipped accessibility child presence and Back-button node topology also remain unqualified. Shared `ReverbRowIndication` hover/focus state animation still uses its existing raw Choreographer path; the shared-clock follow-up is owned separately. Platform RippleDrawable is intentionally native. These source changes and existing controller tests do not establish pixel/frame/accessibility parity.
