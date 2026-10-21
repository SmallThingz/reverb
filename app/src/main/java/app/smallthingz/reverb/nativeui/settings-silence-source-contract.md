# Native silence/calibration Settings body

Release oracle: Reverb v0.1.2 commit3e16f8ddb78dca15df4b436d34d330801ce83ffd, SkipSilenceSettings.kt49–281 and SettingsScreen.kt1802–1843. This is presentation and its existing pointer gesture only. SilenceCurveView, SettingsSilenceCalibration, calibration timing/edge-discard, sampler and persistence remain unchanged.

SettingsSilenceView receives immutable render state plus typed SettingsSilenceActions. The host must bind the current callbacks with each rendered draft: released onConfigChange captures rendered configuration/revision and rechecks featureInputReady before committing. beginHeld must retain the controller's synchronous running check; then call existing beginHeld(config, originalSnapshot.silenceCalibrationInput()) and return running&&holding. Timed, completeTap, finishHeld and cancel map to that same existing owner. No new microphone request, permission gate, setting write, timer or capture lifecycle is introduced here.

The view retains selected curve index outside the expansion lifetime. Existing SilenceCurveView receives the same config, persisted histogram peaks, frequency maximum, palette, editability and onCommit/onSelect callbacks. Closing an expanded body clears the curve callbacks and histogram reference and disables editing, exactly as its released AndroidView onRelease. It does not erase the stored histogram.

Geometry:64dp toggle header,14dp horizontal/8dp vertical header padding,12dp title-to-switch gap. Body16dp horizontal/12dp vertical padding,8dp gaps, existing curve's intrinsic height with minimum245dp. The calibration action row stays before sampling/error/unavailable text. Calibrate has weight1; Cancel takes natural width and appears only for timed calibration after pointer release. Plain text wraps its bounded width. Default expandVertically/shrinkVertically align Bottom; source default fade/size springs use the shared SettingsExpansionView and inherited window clock.

SettingsCalibrationGesture preserves the source Initial/Final detector:
- First unconsumed DOWN starts the same sample immediately and leaves DOWN/moves for child click/parent scroll.
- Pointer identity, second pointer, slop, bounds, prior consumption and parent Final-pass consumed motion cancel ownership.
- UP is consumed after accepted AND cancelled presses, suppressing the inner TextButton's separate pointer click.
- Short UP converts the current sample to timed; long UP finishes held. No second sample is created.
- Callback rebinding and progress updates keep the current gesture.
- Detach/dispose cancel a still-owned sample once. The existing calibration controller still independently owns focus/lifecycle cancellation.

The custom gesture's node is exposed on the OUTER TextButton48dp minimum-size box. The actual native root must place it before the inner visual TextButton click node in the same source hit tree. The existing SilenceCurveView must retain its Android-view interop event route. These are concrete host wiring requirements, not an alternate recognizer.

Primitives owns the two supporting leaves: SettingsCalibrationActionView (M3 default TextButton, including disabled colors and source node exposure) and SettingsCalibrationProgressView (pinned M3 1.4.0 determinate LinearProgressIndicator). No extra progress animation is added. Progress passes through the caller's finite/clamped sanitization. Shared window text/node geometry must bind the progress semantics rectangle and existing child text/role views.

Six SettingsCalibrationGestureTest cases are written for immediate start, timed/held release, scroll and multipointer cancellation, rejected begin and detach. They have not been run in this handoff. The gesture/body have bounded self-review only; a separate adversarial reviewer was unavailable, so no clean independent pass is claimed. Static pixels, native accessibility, phase-ordered interaction and live sampling remain device-unqualified until the normal finite release comparison.
