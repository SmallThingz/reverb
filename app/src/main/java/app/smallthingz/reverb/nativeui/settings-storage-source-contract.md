# Native Settings Storage section

Oracle: released v0.1.2 SettingsScreen.kt1874–1962. This section renders existing state and invokes the original picker/default-folder/move callbacks. It does not request permissions, launch activities, read/write folders, move files, or change directory-picker ownership.

Geometry and roles are explicit:
- Section title uses the same19sp/26sp Medium helper as other Settings sections, with16dp horizontal/12dp vertical inset.
- Continuous field surface has20dp corners, original field/border palette and16dp horizontal/4dp vertical external padding.
- Inner column has16dp horizontal/10dp vertical padding. Its first row has8dp spacing, weighted BODY_LARGE muted path and optional default-folder action followed by folder action.
- Folder surfaces are48dp square with14dp corners, raised fill and24dp glyphs. Their ripple inherits onSurface while glyphs explicitly use onSurfaceVariant.
- Move recordings remains the default TextButton measurement (58dp minimum width,40dp visual height,48dp minimum target,12dp horizontal/8dp vertical content padding), but with the released12dp shape and raised fill in BOTH enabled and disabled states.
- The Move text/ripple colors retain M3 primary or onSurface38% disabled. Only canMove&&!settingsPersisting disables it; the two folder actions preserve their source callback-level Settings admission instead of adding visual disabling.
- Fill is painted by existing ReverbGroupLayout outside its rounded child layer; interaction and ripple stay inside the actual14/12dp clipped content. No capsule-only hit approximation is reused for the12dp action.

SettingsStorageActionView shares the existing exact text/icon renderers, source click controller, ripple and merged-role semantics. Text or described Icon remains a separate real node; the shared window geometry/node facts still must be bound before native accessibility exposure, as with other native sections.

This handoff has bounded self-review only, not compile/raster/input/device qualification. No new tests mirror the straightforward layout implementation. The eventual finite Settings reference/native fixture must cover custom/default-folder membership, enabled/disabled Move, long path wrapping and RTL along with the existing palette/font cases.
