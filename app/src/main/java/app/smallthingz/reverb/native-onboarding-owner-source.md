# Native onboarding state and permission owner

Integrated source, not yet compiled or device-qualified. MainActivity still routes to the released Compose UI. Baseline: v0.1.2 commit `3e16f8ddb78dca15df4b436d34d330801ce83ffd`; released MainActivity SHA256 `700410262137f40c16c327cc040b64f53de05ceebd8b14b9dd4d18f48cde5c15`.

`NativeOnboardingScreenOwner` directly owns the existing Activity state formerly read by OnboardingScreen. `NativeOnboardingDraft` holds the six source rememberSaveable values. The native visual host owns the one pager/navigation engine, per-page scroll states and Back/Continue/predictive-back motion. No additional renderer, state bus or process singleton is added.

Source anchors:

- MainActivity134–166: four RequestPermission callbacks and requested flags; notification results use the callback Boolean, recovery success posts existing feedback.
- 175–194,400–419: restored flags, initial grants, durable onboarding admission and onStart refresh.
- 223–297: page status, explicit row permission callbacks, accepted Finish submission and runtime handoff.
- 346–393: resumed-only success transition, IO hydration, cancellation propagation, one failure notice and existing retry backoff.
- 422–483: non-onboarding microphone, legacy storage, then notification request order.
- 558–564: initial one-shot repair, restored buffer choices, separate original and selected compression/silence values.
- 584–589,685–707: readiness changes highlighting only; renderer retains settled/target/pending/scroll/predictive Back/Continue/Finish admission.

There is NO automatic permission request when entering the released onboarding page. Explicit card clicks launch while onboarding; automatic microphone→legacy storage→notifications starts after successful Finish or on ordinary non-onboarding onStart/result. Battery exemption is excluded from footer readiness. Retaining three native pages must not create new page-entry effects.

Activity integration must preserve its four existing launcher registrations and their order, forwarding results to this owner. Call attach after constructing the owner at the released setup point, forward onStart after super, and forward saveInstanceState. The owner continues through Home until Activity disposal, because the same permission-result/requested state owns the subsequent permission flow. Close cancels only UI hydration/observation; accepted processOnboardingFinish work survives Activity replacement. No backend function or process coordinator has been edited.

The original four requested-flag Bundle keys are unchanged. Native draft save/restore uses an explicit nested key and retains unconsumed restoration through loading. Its six value semantics match source rememberSaveable, including preserving original audio even if preferences change before recreation and applying initial one-shot repair only when no restored value exists. Compatibility with an already-saved Compose positional registry payload across a renderer switch is not claimed; production rollout must decide that transition before replacing the released route. Pager/scroll restoration remains with the native visual host.

Existing OnboardingFinishCoordinator, commitOnboardingAndReloadRuntime, finishOnboarding and resolveOnboardingAudioSelection remain authoritative for durability, duplicate submission, fresh-state choice merging, codec/retention admission, commit ordering and failures. The owner adds no permission-ready gate to Finish and no RESUMED or page-settled gate to row callbacks.

Compilation, actual lifecycle/result delivery, recreation, resumed completion, permission-denial presentation and full native route qualification remain pending. No actual grants, app preferences or recorder operations were performed while implementing this source.
