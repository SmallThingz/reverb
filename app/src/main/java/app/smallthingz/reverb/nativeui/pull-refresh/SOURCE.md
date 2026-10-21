# Native Library pull-to-refresh

This implements the released FilesScreen.kt1011–1054 PullToRefreshBox, without a new gesture detector, list renderer, Composition, or refresh backend. Production routing remains unchanged.

Oracle: released Reverb 3e16f8ddb78dca15df4b436d34d330801ce83ffd and pinned AndroidX Material3 1.4.0 sources. PullToRefresh.kt is commonMain/androidx/compose/material3/pulltorefresh/PullToRefresh.kt in material3-android-1.4.0-sources.jar; extracted SHA256 a0c53b2d61af8b02ad1a80e186ea06358e27bcfe23debb785551fd16def9f0b9. The ignored retained source is .tmp/native-views-20261021/pull-refresh-source/PullToRefresh.kt. Upstream is Copyright2023 The Android Open Source Project, Apache-2.0; the full license is ../incidents/APACHE-2.0.txt.

## Source map

- Lines232–390: node attach, update, physical-Y consumption, release and cancellation finally ownership. Current pre-scroll consumes only upward user input; post-scroll consumes remaining user input. Side effects and active state animations do not acquire drag. Already-refreshing state consumes no drag/release. Release itself has no isAnimating guard.
- Lines249–256/325–388: adjusted distance is raw pull×0.5; integer threshold is roundToPx(80dp). Refresh is strictly greater than threshold on release. Positive release velocity is consumed only when a pull exists; negative velocity remains available. Hidden spring is awaited before return. Raw accumulation is not capped; visible overpull tension is capped.
- Lines598–619: public low-level Animatable default spring drives the saved fraction, with source snap and mutable exclusion. Native owner retains the original external refresh source of truth and callback ordering.
- Lines447–542/630–754:40dp indicator, top-only clipping, circle fill,3dp shadow when showing/refreshing, translation fraction×rounded80dp minus container height.16dp centered spinner/arrow;2.5dp stroke,5.5dp arc radius,10×5dp outlined arrow. Palette surfaceContainerHigh/onSurfaceVariant. DefaultEffects spring uses pinned StandardMotionTokens damping1/stiffness1600; arrow ink alpha starts.3 and targets1 at fraction≥1.
- AndroidComposeViewAccessibilityDelegateCompat1.11.2 lines956–972/3523–3549: determinate progress range preserves source current fraction and localized percentage clamps only its spoken text. No editable/set-progress action is added.

## Concrete integration

Create NativeLibraryPullRefreshOwner before Library body children with inherited window coroutine scope, immutable Density, initial isRefreshing, existing ancestor NativeVerticalNestedHost, and owner.refresh(showSpinner=true). Give pull.nested to the current Library list/empty scroll bridge. Ancestor pre runs before this node; current post runs before ancestor. The bridge keeps existing overscroll/pointer admission; this component never intercepts MotionEvents.

Create ReverbLibraryPullRefreshIndicator(context, environment, pull, frames, parentScope, durationScale) and place its40dp box at body topCenter above content and below Undo, as source Box order. Permit its horizontal/bottom shadow overflow; the indicator clips drawing above its original body top. Do not translate it externally. The always-present indicator's attach/detach drives the pull modifier-node lifetime; list/status replacement must not recreate it. Feed presentation changes through pull.bind(isRefreshing, currentRefreshCallback). bindPalette retains owned indicator identity. Close the pull owner when the Library composition is disposed. A saved source fraction can be supplied through initialDistanceFraction, although attach then snaps to externally refreshing state exactly as upstream.

## Validation and limits

NativeLibraryPullRefreshTest adds threshold-next-float, negative remainder, tension saturation, active-refresh behavior, arrow math, parent/current nested ordering, side-effect rejection, latest callback and suspending release coverage. Tests are written but not run in this lane.

Compilation, actual raster/shadow/software drawing, animation interruption/frame-phase, input dispatch and accessibility event delivery remain unqualified. Crossfade/ink springs use ordinary low-level animation values on the inherited window clock; the actual source/native motion comparison must establish dispatch parity. No known mismatch is accepted as a final implementation or hidden by the fixture. The reused indeterminate spinner retains its separately recorded source-derived qualification limits.
