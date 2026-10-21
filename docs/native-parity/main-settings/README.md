# Bounded Main / Settings paired probe

This proposal is **not integrated, compiled or run**. It is the first assembled Main route check, not a full native rewrite qualification.

Files:
- `ReleasedMainScreenParityOracle.kt`: the complete released MainScreen body from git3e16f8d MainActivity, unchanged except entry / private enum / private duration constant names. The original SettingsScreen, AppTopBar, feedback, navigation and panel code remain the actual rendering implementation. There is no injected state hook.
- `generate_oracle.py`: SHA-bound mechanical extraction. It refuses a changed source. The exact body and file hashes are in `oracle-provenance.json`.
- `NativeMainSettingsParityProbe.kt`: entry `verifyNativeMainSettingsParity(instrumentation, report)`, using the existing NativeParityActivity / main-thread exception helper / focus guards / PixelCopy / exact ARGB comparison.

Finite flow: Main → Settings → select the opposite resolved theme → Undo → Back. The initial setting comes from the isolated QA package; the probe does not overwrite preferences to manufacture a baseline. Both renderers use actual window configuration, actual dispatched insets and the released own-window edge-to-edge / transparent system-bar policy. Theme preview changes the app palette, never global night mode, accessibility inversion, grants or device state.

The probe uses the existing application-ID and plain-Application admission. `permissionsGranted=false` prevents both released CaptureScreen and native Capture owner from mounting. Permission and picker callbacks fail locally if reached. There is no calibration, recording, export, tile, Save, external intent or production Activity launch. It checks that the QA package's persisted theme remains unchanged after every captured step. Source Settings launcher registration remains real; the native registration has a non-launching callback since this flow never opens the picker. Picker delivery is explicitly outside this probe.

Each renderer uses its actual owner lifecycle. Native has one runtime, window text cursor and insets adapter; both renderer passes have isolated attached composition-cache hosts. Renderers run A/B/A/B. Five settled captures per pass preserve raw PNGs, source semantics, native View node payloads, palette role values and raw theme callback order. After observing at least the released 220ms panel duration at the unchanged animation scale, the probe requires three identical full-frame captures; it never masks pixels, changes tolerance, suppresses frames or retimes a source animation. The final changing frame is retained if that bounded stability check fails. Action readiness and window focus failures remain failures.

Compare exact source/native ARGB, each renderer's repeat stability, and unmodified callback sequences. The source / native node payloads are retained for review, not falsely compared as identical internal node IDs or class allocation trees. Actual root, glyph and grouped-setting appearance must still be inspected in the resulting captures.

Not qualified by this probe: physical pointer dispatch, frame-phase equivalence, TalkBack delivery, keyboard/IME, Library operations (another agent owns a separate paired probe), production startup, capture or audio integrity. A timeout is not automatically an app defect. No runner registration, build or device action was performed by this lane.

Integration after root review: copy the two Kotlin files into `app/src/reliability/java/app/smallthingz/reverb/`; register one existing-runner command for the entry function. Keep the production route and MainActivity unchanged.

Review corrections: the first unintegrated draft mistakenly used the active incident-controller extraction; the current oracle reverses exactly to the pinned original v0.1.2 body. Cleanup failures now invalidate result/PASS and preserve primary/suppressed errors. Native runtime follows actual Activity START/STOP. These are source-reviewed corrections, not native passes.
