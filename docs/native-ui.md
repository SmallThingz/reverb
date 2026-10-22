# Native UI

The production UI uses ordinary Kotlin `View`, `ViewGroup`, `RecyclerView` and dialog classes. `MainActivity.setContentView` installs `NativeActivityContentView`; it chooses native onboarding or `NativeMainScreenView`. There is no production `Composition`, `ComposeView`, reflective renderer dispatch, virtual DOM or declarative rendering runtime.

The compatibility reference is released **v0.1.2**, commit `3e16f8ddb78dca15df4b436d34d330801ce83ffd`. Source geometry, palette resolution, text, input, motion and state lifetimes are the reference—not reconstructed screenshots or platform-widget approximations. The source cutover does not itself establish complete pixel or behavior parity.

## Ownership and reuse

| Boundary | Responsibility |
| --- | --- |
| `MainActivity` / `NativeActivityContentView` | Permission/result registration, theme presentation, lifecycle, saved-state import, window insets and onboarding/Main ownership. |
| `NativeMainScreenView` and screen owners | Navigation, retained drafts, backend subscriptions and accepted operations; explicitly bind existing screen Views. |
| `nativeui/` components | Measurement, layout, Canvas/Drawable rendering, input and accessibility for concrete controls. Components accept typed values and callbacks rather than creating recorder/storage owners. |
| Existing Android Views | `AudioBlobView` and `RangeDurationWheelView` remain authoritative renderers. Their native wrappers preserve attachment, update and release behavior. |

`ReverbViewEnvironment` contains an immutable palette, the actual configuration's `Density`, and a shared font resolver. It owns no View or listener. Use `ReverbPalette` roles and `ReverbParagraphStyles`; use `reverbButtonTextStyle` when the released label inherits Button/TextButton typography. Palette previews rebind existing owners and controls; they do not recreate a sheet, discard a draft or restart a gesture.

Reuse is ordinary Kotlin construction and direct binding. `environment.paragraph(...)` returns the actual paragraph View; `ReverbGroupLayout.addRow(...)` attaches actual child Views. Shared shapes, icon paths, noise, indications, switches, buttons, viewports and sheets live in `nativeui/`. There is no separate UI-description tree to interpret or reconcile.

The reusable primitives and inline construction DSL live in the independent
`native-ui` Git repository. Reverb pins its commit as a Git submodule while keeping
the Gradle project name `:native-ui` and namespace `app.smallthingz.nativeui`.
`third_party/native-ui.json` binds that commit and the SHA-256 of the accompanying
compact Git bundle. No unpublished remote URL or absolute sibling path is needed.

After a plain clone, or in a fresh CI checkout or source archive, run:

```sh
python3 scripts/bootstrap-native-ui.py
./gradlew :app:assembleDebug
```

The bootstrap verifies the bundle and the parent gitlink (when Git metadata is
present), checks out the pinned commit offline, and preserves conflicting or dirty
library work by refusing to replace it. `--check` verifies an existing checkout.
It writes no global Git configuration. Ordinary Gradle development builds do not
reject intentional library edits; source/release qualification pins the exact tree
separately. The library's own wrapper supports independent builds and tests; when
included in Reverb it inherits the existing Android/Kotlin toolchain classloader.
The bundled locator is supported by this bootstrap, not generic recursive clone
or `git submodule update --init` commands.

To update the library, commit its change in the independent repository, export a
full bundle containing that commit, update the bundle checksum and commit in the
pin file, and stage the matching `native-ui` gitlink in Reverb. Keep all four inputs
together. A published remote can replace the bundled locator in a later explicit
publication change; this extraction does not publish one.

## Window services and input

The Activity owns one `NativeAboutWindowRuntime` (also used outside About), one window text/accessibility owner, and one inset listener. Screen children receive those services. Separate dialog/popup windows own the corresponding window lifetime. The shared scroll-accessibility queue belongs to the window; a component must not create a second text cursor, inset observer or event queue for the same host.

Custom controls use the shared click/drag adapters and `NativePagerTouchRoot` phase dispatch. Hit-layout nodes describe existing Views and source hit/clip rules; they do not draw or build another layout tree. Existing Android editing/wheel controls use the explicit interop path. Keep callback updates separate from gesture-owner identity, and retire owned input, subscriptions and jobs at their actual detach/rebind/close boundary.

## Adding a component

1. Find the exact released component and reuse an existing native leaf where its contract matches. Keep changes bounded to the component and its owner.
2. Pass the current environment, real window services, typed state and callbacks. Keep backend work and operation admission in the existing owner.
3. Implement direct measurement/layout/drawing and explicit `bind`/`close` behavior. Preserve RTL, font scaling, minimum targets, clipping, accessibility and cancellation. Android defaults are not automatically equivalent: for example, child clipping can remove valid glyph overhang even when outer rounded clipping is required.
4. Use the supplied frame clock for source-owned coroutine animation and the pinned math where needed. Platform Drawables and existing View callbacks retain their own lifetimes; dispatch-phase equivalence still needs qualification.
5. Add focused tests for changed state/math and a bounded reliability comparison for observable rendering or interaction. Retain failures and unmeasured cases; do not loosen pixel tolerances or modify the reference to manufacture parity.

## State and reference sources

`NativeMainScreenSavedState`, onboarding savers and screen-specific pending state preserve the released conditional lifetimes. `LegacyReverbSavedState` reads only exact keys derived from the published v0.1.2 APK. `LegacyReverbSavedStateRegistry` preserves unconsumed public registry data; native values take precedence. The directory transaction and original launcher UUID restore together. UI restoration does not restore jobs, grants, capture intent or accepted-operation ownership.

Released Compose renderers are retained in `app/src/reliability`, including `ReleasedMainActivityOracle`. Mixed files keep backend/state/math in production and move renderer adapters to reliability. `scripts/native-renderer-source-map.json` and `scripts/reliability.gradle` pin actual compiled outputs and reconstruct original bytes, preserving the original oracle hashes. They do not substitute a detached baseline for checking current code. Parity probes remain QA-only.

## Retained dependencies and performance

The production Views use the `native-ui` library's source-derived text/font,
geometry, animation and construction primitives. Required resources and upstream
attribution remain in source. Annotation and emoji2 are internal library
dependencies, and coroutines remains an API dependency. Production has no Compose
or Material3 UI runtime or Compose compiler plugin; the released-renderer comparison
lane enables Compose only for QA. The four pinned data-only legacy Parcelable reader
classes retain their old names solely to import saved state and are not a UI runtime.

Direct View reuse removes the added renderer/reconciliation layer by construction; it is **not a measured zero-overhead claim**. View allocation, text shaping, snapshots, callbacks and animation still cost work. APK size, startup, memory, frame timing and input latency require measurements against the exact reference artifact; source scans and passing host tests cannot establish them.
