# Library row motion proposal

Status: integrated after bounded source review; compilation and seven focused tests remain unrun for this slice. No device action or production route switch.

## Source and scope

The source is released RecordingCard.kt at 3e16f8ddb78dca15df4b436d34d330801ce83ffd. Pinned AndroidX 1.11.2 math/source files and SHA-256 hashes are recorded in MOTION-SOURCE.json; extracted source remains in the ignored library-motion-proposal/reference directory. AndroidX files retain their original Apache-2.0 headers. This proposal owns only row selection/expansion/content-size presentation. Library operations, playback admission, trim/export, Undo and accepted mutation ownership remain unchanged.

- Four colors and selection alpha: 180 ms FastOutSlowIn tween. Color uses the pinned Oklab converter; no ARGB interpolation.
- Inline expand: 320 ms FastOutSlowIn; shrink: 260 ms FastOutSlowIn; Bottom alignment and viewport clipping.
- Inline fade: 220 ms with 70 ms delay on entry; 140 ms on exit, retaining the source default tween easing.
- Outer content size: a separate 320 ms FastOutSlowIn Animatable, TopStart alignment and clipping. The animated expansion's measured height is the outer channel's new target. These are not flattened into one height curve.
- Initially visible content is settled, with no synthetic entrance. Completed exit disposes the expansion presentation; a later opening starts a fresh PreEnter subtree.
- The existing source populated LazyColumn has no animateItem. Native RecyclerView.itemAnimator remains null.

## Math and interruption ownership

LibraryTweenValue follows Animatable/SuspendAnimation. Running retarget uses the last delivered frame, converts velocity through T (including Color and IntSize quantization), and immediately delivers its restarted zero-time sample. Time scaling retains Long/Float/toLong. Equal targets do not restart active motion; a stopped unfinished content-size channel may restart.

LibraryTransitionValue and LibraryExpansionMotion follow Transition/EnterExitTransition. Transition retains raw vector velocity, uses its global play time plus a target-update delay, and uses Double division/roundToLong for scale. An interrupted non-spring child spec uses the source default interruption spring. Reversed PostExit-to-Visible segments use EnterExitTransition's own medium-low spring, as the source does. Each child can finish independently; disposal waits for both size and alpha.

The native row owns one Choreographer callback while any channel runs. It removes that callback on detach/recycle and starts no idle loop. Selection-only frames do not request layout. Size/fade frames do not allocate a new color-frame object when selection is settled. The inline lease supplies actual player content and owns only its presentation lifetime; it no longer supplies duplicate size/fade animations. Exit callbacks are guarded by recording identity and motion-instance identity before releasing the lease.

## Integrated files and retained patch

- New nativeui/LibraryRowMotion.kt and nativeui/LibraryExpansionViewport.kt.
- Library-row-motion.patch updates ReverbLibraryRecordingRow.kt and ReverbLibraryListView.kt.
- New test nativeui/LibraryRowMotionTest.kt (seven focused tests, currently unrun).
- Preserve SOURCE.json and this document alongside the integrated Library provenance.

The ignored proposal retains full row/list files and the pre-integration patch for review. The patch is against the frozen native-screens source hashes listed in MOTION-SOURCE.json. It was checked with git apply --check only; that is not compilation or runtime validation.

## Qualification that remains

This reproduces the source's accepted-target math and explicit native frame driver. It does not prove equivalence of Compose SideEffect/channel delivery and Android View traversal phase. That causal scheduling comparison needs a controlled dual-render fixture with the same accepted targets and frame timestamps, including rapid reversals, scale zero, size changes and row disposal.

RecyclerView attachment/cache/prefetch lifetimes and scrolling physics still require qualification against the released LazyColumn. Native viewport clipping/raster, expanded text accessibility/focus, row hit routing, surface layering and frame-perfect animation are not declared passed. No backend, personal recording, setting, Home layout, player or operation state was altered by this proposal.

The populated native list also retains the released final 84 dp Spacer. It remains a separate footer item, not viewport padding or inter-record spacing.
