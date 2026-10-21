# Native onboarding qualification boundary

Reference: released v0.1.2, commit 3e16f8ddb78dca15df4b436d34d330801ce83ffd, MainActivity.kt:733–1041 for the three page bodies and :667–722 for the footer.

The integrated native leaves preserve source strings, roles, dimensions, grouping, dividers and the independent visual/interaction enabled states. Each OnboardingPageContentView has a fixed page identity and its own vertical scroll viewport. Pager state/motion, permission launchers, draft persistence and Finish remain external; production MainActivity is unchanged.

OnboardingNativeInteractionsFactory concretely joins ReverbClickController, ReverbRowIndication and the filled Button elevation helper. The same identity-bound interaction stream reaches ripple and elevation. Permission/card bodies use explicit logical-start/center rounding, not platform LinearLayout weight/center approximations. The parent pager still needs source-equivalent interested-parent/Final-pass coordination; Android ScrollView interception already cancels its child stream.

Shared dependencies: resolved ReverbPalette, caller-supplied exact TextStyle/Density/resolver, ReverbParagraphView, ReverbIconDrawable, ReverbGroupLayout, and ReverbMaterialSwitchView. Configuration replacement recreates these immutable rendering inputs; the owner saves/restores per-page scroll after layout.

Not yet qualified by this integration: actual page/footer pixels at density/font-scale/RTL combinations; Switch and ripple motion/frame phase; native Button shadow raster; ScrollView versus foundation verticalScroll physics, overscroll, keyboard/focus and accessibility scrolling; predictive-preview and retained-owner lifecycle; full accessibility node structure/fake-node behavior. Permission text and icon contentDescription stay separate. Toggle stateDescription uses the pinned localized state_on/state_off resources.

ReverbButtonElevationTest contains five focused state/math cases. NativeSwitchParityProbe contains separate static exact A/B/A/B and finite observed same-buffer motion checks. Source presence is not a test result. Root coordinates compilation and actual-device qualification.
