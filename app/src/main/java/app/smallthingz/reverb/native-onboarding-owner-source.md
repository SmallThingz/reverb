# Native onboarding state and permission owner

MainActivity routes through the native owner and View host; the released Compose implementation remains a reliability oracle. Baseline: v0.1.2 commit `3e16f8ddb78dca15df4b436d34d330801ce83ffd`; released MainActivity SHA256 `700410262137f40c16c327cc040b64f53de05ceebd8b14b9dd4d18f48cde5c15`. This contract describes the restored released flow; current rendering and platform permission delivery still require device qualification.

`NativeOnboardingScreenOwner` directly owns the Activity state formerly read by OnboardingScreen. `NativeOnboardingDraft` holds the buffer and two audio-switch choices. The native visual host owns the one pager/navigation engine, per-page scroll states and Back/Continue/predictive-back motion. No additional renderer, state bus or process singleton is added.

Source anchors:

- MainActivity134–166: four RequestPermission callbacks and requested flags; notification results use the callback Boolean, recovery success posts existing feedback.
- 175–194,400–419: restored flags, initial grants, durable onboarding admission and onStart refresh.
- 223–297: page status, explicit row permission callbacks, accepted Finish submission and runtime handoff.
- 346–393: resumed-only success transition, IO hydration, cancellation propagation, one failure notice and existing retry backoff.
- 422–483: non-onboarding microphone, legacy storage, then notification request order.
- 558–564: initial one-shot repair, restored buffer choices, separate original and selected compression/silence values.
- 584–589,685–707: readiness changes highlighting only; renderer retains settled/target/pending/scroll/predictive Back/Continue/Finish admission.

Permission requests during onboarding require explicit row taps. `OnboardingPermissionRequestSequence` only serializes those taps and pending results; hydration, lifecycle resume, page changes and permission results never claim the next request. Saved pending/attempted state remains readable; returning from the battery Activity clears only its pending request. Battery exemption remains excluded from footer readiness. Ordinary post-onboarding permission handling stays separate.

Activity integration preserves its four existing launcher registrations and their order, forwarding results to this owner. Call attach after constructing the owner at the released setup point, forward onStart after super, and forward saveInstanceState. The owner continues through Home until Activity disposal, because the same permission-result/requested state owns the subsequent permission flow. Close releases UI hydration/observation ownership; accepted processOnboardingFinish work survives Activity replacement.

The original four requested-flag Bundle keys are unchanged. Native draft save/restore uses an explicit nested key and retains unconsumed restoration through loading, including original versus selected switch choices. Initial one-shot repair applies only without a restored choice. Compatibility with an already-saved Compose positional registry payload is not claimed. Pager/scroll restoration remains with the native visual host.

Initial switch values come from configured output format and silence settings, including their existing absent-preference defaults. The audio page contains only compression and skip-silence switches plus the released unavailable/invalid warnings. Get started directly submits Finish; enabling skip-silence does not start or require calibration. Settings continues to own level, curve and calibration editing.

`OnboardingFinishCoordinator`, `commitOnboardingAndReloadRuntime`, `finishOnboarding` and `resolveOnboardingAudioSelection` remain authoritative for duplicate submission, fresh-state merging, codec/retention admission and commit failures. Compatibility with already-saved native drafts preserves supported buffer/switch choices and their original-versus-selected ownership. Unsupported uncommitted level/curve/histogram edits are ignored: those fields always come from current durable audio, so an invisible old edit cannot overwrite a profile or trap Finish. Persisted profiles, histograms and raw preferences are not changed by draft restoration; untouched fields follow current storage. Atomic rollback and process-lifetime commit ownership remain unchanged.

This source contract is not a runtime result. The implementation at `dcbead5` previously added automatic requests, force-on defaults and calibration controls; those expectations are superseded by released v0.1.2 parity. Historical receipts retain their original source bindings and are not evidence for this repair. Current permission delivery, page/footer geometry, keyboard/accessibility and lifecycle behavior require appropriate qualification.
