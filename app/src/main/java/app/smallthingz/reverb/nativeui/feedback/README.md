# Native process feedback

Integrated native implementation; no production route switch, compilation, test execution or device qualification is implied by integration.

Authority: released0.1.2 commit3e16f8ddb78dca15df4b436d34d330801ce83ffd, AppFeedback.kt148–247 and MainActivity1342–1358; pinned animation1.11.2 EnterExitTransition304–326,785–795,843–854,928–1000 and AnimatedVisibility672–789. Original process queue, delivery helper and timeout rules remain unchanged.

NativeAppFeedbackOwner retains the released RESUMED lifecycle collection, latest allowed/window focus gate and sequential queue-head delivery. Cancellation/obscuration never acknowledges the head. It calls existing deliverFeedbackEvent with two inherited withFrameNanos waits and the caller's existing AccessibilityManager adapter. There is no second bus or timeout implementation.

NativeAppFeedbackHostView owns only the text-only FeedbackCard and native input shield. It shares the existing window text cursor, actual geometry and source-node callback. The original polite live-region label is bodyMedium. ERROR/SUCCESS/INFO use the released palette pairs and conditional2dp tonal overlay. Exact18dp corners and16/8dp logical horizontal plus8/8dp vertical inner padding are preserved.

Main supplies BottomCenter placement,18dp horizontal padding and z7 for Incidents overlay, otherwise5 for visible/closing Library, otherwise2. Bottom is20dp plus navigation-bar bottom while Incidents/Library overlay is visible,20dp only while showSettings, otherwise104dp. The Settings-progress branch is intentionally absent because it is absent in released source. Onboarding instead supplies safeDrawing padding plus84dp. Main binds deliveryAllowed=false for About or visible Library Undo when Incidents does not cover Library.

Before root hit testing, call parentPointerLayout(rootWindowPositionInScreen) to refresh actual float matrices for that event, then place the returned layout at the same z as the real host. The per-card empty Surface node shields only its current rounded ink and retires when that card leaves. Padding does not acquire a hit box. No action handler belongs to this host.

The source event=null branch removes FeedbackCard immediately inside its exiting AnimatedVisibility. This native host likewise removes the card, retaining only the empty spring transition state for interruption. Suppression removes the whole transition immediately. Adjacent queue-head state writes are coalesced in one posted render; they do not force a null-card frame or replay entry. The motion owner samples public TargetBasedAnimation springs on the inherited window MonotonicFrameClock and MotionDurationScale. No raw Choreographer or idle timer exists.

Six pure motion cases are written but not run. Existing deliverFeedbackEvent tests continue to cover queue/timeout/acknowledgement; do not claim those prove this view's native delivery or draw phase. Render coalescing, two-frame visibility, spring frame phase, touch shielding, raw accessibility/live-region delivery, font scaling/RTL and all three tone rasters remain actual qualification work. Production routes are unchanged.
