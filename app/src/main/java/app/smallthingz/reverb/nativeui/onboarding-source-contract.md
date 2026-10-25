# Native onboarding qualification boundary

Reference: released v0.1.2, commit 3e16f8ddb78dca15df4b436d34d330801ce83ffd, MainActivity.kt:733–1041 for the three page bodies and :667–722 for the footer.

The native leaves use the source palette, icons, grouping, dividers and independent visual/interaction enabled states. Each OnboardingPageContentView has a fixed page identity and its own vertical scroll viewport. Pager state/motion, permission launchers, draft persistence and Finish remain external to these leaves; production MainActivity uses the native host.

OnboardingNativeInteractionsFactory joins ReverbClickController, ReverbRowIndication and the filled Button elevation helper. The same identity-bound interaction stream reaches ripple and elevation. Permission/card bodies use explicit logical-start/center rounding. Native vertical viewports and the parent pager retain their existing pointer-phase and cancellation coordination.

Shared dependencies: resolved ReverbPalette, caller-supplied exact TextStyle/Density/resolver, ReverbParagraphView, ReverbIconDrawable, ReverbGroupLayout, and ReverbMaterialSwitchView. Configuration replacement recreates these immutable rendering inputs; the owner saves/restores per-page scroll after layout.

The page bodies and footer follow released v0.1.2: the permission intro precedes its group by16dp; permission and buffer explanations use the original resources. Requests are explicit row actions. The audio group contains only the compression and skip-silence switches and the original warnings, and its footer remains Get started. Configured preferences determine initial switch values.

The richer calibration/level controls, shortened text, force-on defaults and automatic permission flow introduced in `dcbead5` are superseded. The old `native_onboarding_audio_flow` calibration fixture and any historical results describe that earlier implementation; they do not establish current parity.

Permission text and icon contentDescription stay separate; toggle stateDescription uses the pinned localized state_on/state_off resources. Current-owner callbacks, predictive-preview interaction disabling, offscreen semantics, pager/scroll ownership and cleanup remain in place.

ReverbButtonElevationTest and NativeSwitchParityProbe retain their separate source/math and static/motion scopes. This document does not establish page/footer pixel parity, frame timing, keyboard/accessibility delivery or lifecycle correctness. Current-source checks and actual-device captures remain necessary; historical migration results are not automatically credited.
