# Library scroll restoration

The released `FilesScreen.kt` at `3e16f8ddb78dca15df4b436d34d330801ce83ffd` has separate conditional saveable slots: the empty branch's `rememberScrollState()` at line 1044 and the populated branch's implicit `rememberLazyListState()` at line 1051. Loading/failure has neither. Selection and Undo are not added to this saved payload.

`NativeLibrarySavedScrollPosition` stores those slots independently. A restored slot remains pending through loading, another branch and repeated saves until that branch first enters. Once consumed, the actual branch owns it; removal discards that branch state, while hiding the retained Library screen does not.

The populated list uses the shared `NativePendingListPosition`. The original request remains the saved value until actual placement. It is normalized against the current item count inside `LinearLayoutManager.onLayoutChildren`, matching pinned foundation 1.11.2 `LazyListMeasure.kt` lines 132–138 (past-end index becomes the last index and zero offset). Normalizing at publication was rejected: a second publication before layout can change that count. A fresh branch explicitly keeps pending zero until placement, preventing detached stale holders from contaminating a save before layout. Completed real placement replaces the pending request; empty layouts do not.

The empty branch constructs `NativeVerticalScrollState(initial)` when its slot is consumed. This state already retains the saved integer until measurement establishes its bound, then clamps like source `ScrollState`.

Five `NativeLibraryScrollRestorationTest` cases cover pending slot preservation, independent slots, consumed branch disposal, zero versus absent and stale-holder save-before-layout. The shared pending-list test additionally covers item-count changes before placement. These tests have been added but not run by this implementation agent. Actual native scrolling/configuration recreation remains a later device check; no renderer parity is inferred from these source/state tests.
