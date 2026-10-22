# Native onboarding qualification boundary

Reference: released v0.1.2, commit 3e16f8ddb78dca15df4b436d34d330801ce83ffd, MainActivity.kt:733–1041 for the three page bodies and :667–722 for the footer.

The native leaves use the source palette, icons, grouping, dividers and independent visual/interaction enabled states. Each OnboardingPageContentView has a fixed page identity and its own vertical scroll viewport. Pager state/motion, permission launchers, draft persistence and Finish remain external to these leaves; production MainActivity uses the native host.

OnboardingNativeInteractionsFactory joins ReverbClickController, ReverbRowIndication and the filled Button elevation helper. The same identity-bound interaction stream reaches ripple and elevation. Permission/card bodies use explicit logical-start/center rounding. Native vertical viewports and the parent pager retain their existing pointer-phase and cancellation coordination.

Shared dependencies: resolved ReverbPalette, caller-supplied exact TextStyle/Density/resolver, ReverbParagraphView, ReverbIconDrawable, ReverbGroupLayout, and ReverbMaterialSwitchView. Configuration replacement recreates these immutable rendering inputs; the owner saves/restores per-page scroll after layout.

The user's 2026-10-22 amendment explicitly departs from released onboarding in these bounded ways:

- Seven concise native-only body strings replace verbose page-one/two copy; the redundant permission intro is removed. Released oracle strings remain unchanged, including the original explanatory copy.
- Missing permission status uses a dashed circle; granted status keeps its check. Whole-row request hitboxes remain, and the owner sequences automatic requests on the visible permission page.
- The third page reuses `SettingsOutputCompressionView` and `SettingsSilenceView`: enabled compression exposes its slider, and enabled silence exposes its curve/histogram and calibration controls. These are the existing native leaves, not duplicate widgets or a new UI framework.
- New choices default on without overwriting durable choices. The audio footer shows Calibrate when a current-input measurement is required; running, failed or cancelled sampling cannot silently Finish. Draft ownership, permission delivery and persistence stay with the screen owner.

These exceptions do not authorize Home geometry, palette, outlines or icon-library changes. Permission text and icon contentDescription stay separate; toggle stateDescription uses the pinned localized state_on/state_off resources.

ReverbButtonElevationTest and NativeSwitchParityProbe retain their separate source/math and static/motion scopes. This document does not establish page/footer pixel parity, frame timing, keyboard/accessibility delivery or lifecycle correctness. The changed onboarding requires focused current-source checks and actual-device captures; historical migration results are not automatically credited to these new behaviors.
