# Native Settings composition slice

Reference: released v0.1.2 `3e16f8ddb78dca15df4b436d34d330801ce83ffd`. These files were integrated into the native-hosts successor after bounded source review. They are not yet compiled, rendered, or connected to MainActivity. The frozen native-screens candidate is unchanged.

## Executable bodies

- `SettingsSegmentedControlView.kt`: released SettingsScreen2212–2254 geometry,30dp outer Surface,4dp inset,equal34dp cells,22dp selectable clips,separate color springs. Theme content retains16dp icons and6dp gaps. Plain touch/key/ripple endpoints are concrete. Radio native accessibility remains a specific pending item below.
- `SettingsSectionLayouts.kt`: exact keyed item order; theme row with16dp inset/gap,12dp vertical padding and trailing12dp spacer; retention header/126dp selector,23dp card/20dp inner padding/equal values/24dp gap; recording title and existing continuous audio group with explicit source dividers. The validation text wraps inside14dp/8dp padding. No new text or app-icon picker exists: released Settings has the existing theme-mode icon segments.
- `SettingsRetentionValueView.kt`:17dp icon,8dp header gap,14sp/20sp label,10dp gap to raw editor, optional14sp MiB unit with7dp gap and6dp bottom inset,13sp/20sp estimate with3dp top inset. The native editor View is a concrete external leaf owned by the onboarding agent; this layout does not substitute a default EditText. Keyed focus requests also belong to that leaf.
- `SettingsWakeLockView.kt`: source ReliabilityRow,20dp outer shape,14dp/12dp row padding,8dp text end inset,2dp summary gap,custom48dp switch host with44×26 track and18dp thumb. Four separate released springs drive track/thumb/offset/press alpha. No row ripple, no M3 Switch replacement. Hardware outline clipping is on the track layer; software uses its cached path. Colors come from the shared palette.
- `SettingsToggleRowView.kt`: the three shared Audio toggle-header geometries only,64dp minheight,14dp/8dp padding,12dp spacing,source M3 null-callback switch. Explicit disabled-text policy preserves Output compression's muted text versus the other two unchanged ink labels. Source checked action and text descendants use the existing merged-row helper.
- `SettingsNotificationSaveView.kt`: complete conditional duration-wheel presentation and imperative extraction of released NotificationSaveSettings39–214. Reuses the existing RangeDurationWheelView, NotificationDurationBinding, interaction/save owner and exact key/accessibility stepping. Lifecycle PAUSE/STOP invalidation is preserved because it exists in the released wheel; ordinary View detach adds no new permission or persistence gate. Disabling/removing the wheel releases only its binding. The160dp wheel retains14dp/8dp external padding and its original palette roles.
- `SettingsSectionListView.kt`: finite RecyclerView host with source keys,stable IDs,no item animator,bottom24dp content padding,live key-anchor preservation and two-integer saved position. Retention focus maps to item2 with battery warning or item1 without. It owns no durable settings,permission,state normalization or save work. Native list internals need not clone LazyColumn, but observable scrolling/focus/anchor/restoration/input still requires comparison.

All padding edges are rounded separately. Weighted widths follow pinned Row's rounded unit plus signed remainder. Labels inside weighted Columns wrap; direct weighted Text fills its allocation. No production reference source was edited.

## Remaining concrete assembly dependencies

1. The released M3 CenterAlignedTopAppBar/Scaffold insets and whole-screen input/accessibility guard. Library's custom66dp toolbar is not this toolbar.
2. Native raw retention editable leaf: assigned to `native_onboarding_audit`. It owns IME/selection/raw callback acceptance/cursor/focus. Source BasicTextField2315–2333 is authoritative; no normalization or input filter is authorized.
3. Output compression M3 Slider0..8 and source AnimatedVisibility expansion/fade; silence calibration action/pointer lifecycle,curve and progress controls with their expansion/fade. The curve and wheel remain existing native Views.
4. Quick Save tiles control/sheet and storage/battery-action leaves. Existing typed editor/controller and permission/persistence ownership must remain unchanged.
5. Radio selectable native-node mapping: source merging parent remains View; selected is checkable/checked and localized Selected/Not selected,not isSelected; selected item does not advertise click; separate role-only child is RadioButton. Parent touch bounds expand toward48dp in Float while fake-role bounds remain34dp visual bounds. Existing64dp merged-row helper cannot be used without this exact distinction.
6. Actual per-window pointer-root and text accessibility geometry bindings; ordinary callbacks are present, but a standalone row is not full scroll/semantic qualification. Whole-panel inactive/commit-barrier checks remain the screen/controller's existing conditions.
7. Compile and finite actual comparison of pixels,controls,scroll,keyboard/accessibility and animation. No source or device pass is claimed by this proposal.

The parent can review/integrate complete bounded files separately. This is not a completed replacement Settings route, and unsupported leaves are not represented by fake controls.
