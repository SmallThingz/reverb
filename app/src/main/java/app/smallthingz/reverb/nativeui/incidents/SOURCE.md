# Incidents native content slice

Candidate only. No production screen is replaced. These classes have not yet been compiled
or qualified on the actual phone; neither source inspection nor a build proves pixel/motion parity.

ReverbIncidentsContentView consumes the existing prepared IncidentHistoryUiState and direct
IncidentCardActions. The host keeps IncidentHistoryController.observeHistory alive for the root
lifetime, including a hidden panel. incidentContentActions bridges existing process-owned toggle
and delete commands and the released clipboard/success-feedback behavior. No optimistic state,
formatting on bind, history read, recorder mutation, or Home change is introduced.

The caller retains the released panel translation/alpha, open progress, predictive Back and
navigation. The native body itself is stationary, exactly as the source. It uses original palette
roles and existing noise resource/seed behavior, a66dp topbar plus current status-bar insets,
14dp header side padding,46dp Back target and24dp glyph. Empty/loading/unavailable text and
28/34dp icon sizes preserve existing strings and roles. Loading uses the source2dp round arc,
three pinned6000ms animations, and localized indeterminate accessibility state description.
Its observer only reads duration-scale changes; it changes no system setting.

RecyclerView1.4.0 supplies native recycling. Adapter identity is the existing presentation key;
same-key enrichment rebinds callbacks and prepared text. Local monotonic stable IDs cover only
current rows, avoiding hash collisions and unbounded historical ID maps. Maximum visible
history remains the backend's128-row policy. Diff calculation is synchronous on that bounded
prepared list and performs no storage/formatting work. Cards stay separate with18dp side,
12dp top,28dp bottom insets and10dp spacing. A stale-history text item precedes retained rows
when unavailable. It does not receive card appearance/motion. Transition to no rows removes
the adapter and retires each card's popup/input exactly at the removed-list lifetime boundary.

ReverbIncidentItemAnimator uses the pinned animation-core engine directly: MediumLow spring
fades and IntOffset placement with1px visibility threshold, preserving interrupted velocity.
No default RecyclerView crossfade or ViewPropertyAnimator duration is substituted. A removal
retires every old-holder channel before RecyclerView receives its terminal callback. Frame
dispatch, interrupted/reordered/removing-row behavior, scroll anchoring, viewport clipping,
overscroll and source LazyColumn's moving-away placement remain actual comparison obligations.
Do not claim exact LazyColumn behavior from matching spring constants alone.

ReverbIncidentBackView uses the shared direct click controller and row indication; its circle
clip, colors and RTL Back glyph come from the released M3 IconButton + explicit Icon tint.
The shared Main phase hit test preserves the46→48dp touch-only near-hit route without centering
the hotspot. NativeIncidentsPointerBinding uses actual panel/item matrices, existing card and
checkbox click endpoints, source circle/card layer shapes and the full Surface pointer shield.
It reuses NativeLibraryScrollOwner rather than dispatching RV touch/fling, and removes the old
manual near-hit forwarding paths. Source branches and physical detach retire input lifetimes;
body/checkbox consumption, parent drag arbitration and accessibility still need actual qualification.

Further integration obligations:
- Root owns ReliabilityInstrumentation/Gradle registrations and the device fixture.
- Menu host must implement the existing18dp popup/12dp menu-item shapes and source action identity.
- Host supplies exact typography/Density/FontFamily.Resolver and refreshes appearances on config.
- Root must not wire this slice into production until actual light/dark, large-font, RTL, empty,
  unavailable, populated, keyboard, accessibility, long-press/cancel and motion parity is qualified.
- Parent/source Home, blob, app icon/theme layout, audio/storage behavior are untouched.

SOURCE.json pins source/reference files. Artwork/project code retains repository GPLv3 licensing.
AndroidX source-derived rendering/animation policy is Apache2.0; adjacent license retained.
