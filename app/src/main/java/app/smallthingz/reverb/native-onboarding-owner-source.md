# Native onboarding state and permission owner

MainActivity routes through the native owner and View host; the released Compose implementation remains a reliability oracle. Baseline: v0.1.2 commit `3e16f8ddb78dca15df4b436d34d330801ce83ffd`; released MainActivity SHA256 `700410262137f40c16c327cc040b64f53de05ceebd8b14b9dd4d18f48cde5c15`. The onboarding amendments below await their own compilation and device qualification.

`NativeOnboardingScreenOwner` directly owns the Activity state formerly read by OnboardingScreen. `NativeOnboardingDraft` holds the buffer/audio choices and calibration draft. The native visual host owns the one pager/navigation engine, per-page scroll states and Back/Continue/predictive-back motion. No additional renderer, state bus or process singleton is added.

Source anchors:

- MainActivity134–166: four RequestPermission callbacks and requested flags; notification results use the callback Boolean, recovery success posts existing feedback.
- 175–194,400–419: restored flags, initial grants, durable onboarding admission and onStart refresh.
- 223–297: page status, explicit row permission callbacks, accepted Finish submission and runtime handoff.
- 346–393: resumed-only success transition, IO hydration, cancellation propagation, one failure notice and existing retry backoff.
- 422–483: non-onboarding microphone, legacy storage, then notification request order.
- 558–564: initial one-shot repair, restored buffer choices, separate original and selected compression/silence values.
- 584–589,685–707: readiness changes highlighting only; renderer retains settled/target/pending/scroll/predictive Back/Continue/Finish admission.

The released page had no automatic permission requests. The user's 2026-10-22 amendment explicitly changes this: while the permission page is visible and the Activity resumed, `OnboardingPermissionRequestSequence` requests missing microphone, applicable legacy storage/recovery, notifications and battery exemption serially. Each is attempted once automatically; denial advances the sequence, and whole-row taps can retry through the same platform launchers. Pending/attempted state survives recreation; returning from the battery Activity clears only its pending request. Battery exemption remains excluded from footer readiness. Ordinary post-onboarding permission handling stays separate.

Activity integration preserves its four existing launcher registrations and their order, forwarding results to this owner. Call attach after constructing the owner at the released setup point, forward onStart after super, and forward saveInstanceState. The owner continues through Home until Activity disposal, because the same permission-result/requested state owns the subsequent permission flow. Close releases UI hydration/observation and calibration ownership; accepted processOnboardingFinish work survives Activity replacement.

The original four requested-flag Bundle keys are unchanged. Native draft save/restore uses an explicit nested key and retains unconsumed restoration through loading, including original versus selected audio and the curve/histogram draft. Initial one-shot repair applies only without a restored choice. Compatibility with an already-saved Compose positional registry payload is not claimed. Pager/scroll restoration remains with the native visual host.

New setup defaults output compression and silence skipping on only when their durable preference is absent; compression still requires a supported input format. Existing stored choices are preserved. The third page reuses Settings controls for compression level, silence curve/histogram and calibration. Its primary action is Calibrate while enabled silence lacks a valid measurement for the current input, waits during sampling, and allows Finish after successful calibration or when silence skipping is disabled. Cancelled/failed sampling never completes onboarding. The existing immediate tap/hold sampler remains authoritative.

`OnboardingFinishCoordinator`, `commitOnboardingAndReloadRuntime`, `finishOnboarding` and `resolveOnboardingAudioSelection` remain authoritative for duplicate submission, fresh-state merging, codec/retention admission and commit failures. Accepted Finish persists the selected compression level, curve and matching histogram with the onboarding choices; sampling alone does not publish them. Home layout, palette and icon library are outside these authorized onboarding changes.

This source contract is not a runtime result. The amended defaults, automatic request sequence, calibration lifecycle and durable Finish require focused qualification; prior migration evidence retains its original source and scope bindings.
