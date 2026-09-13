# Reverb UI invariants

- Buffer switching is one stationary capture surface, never a pager or sliding screen transition.
- On buffer changes, the blob and buffer-specific bottom actions use a depth flip. The Library icon stays static and must never flip.
- The Library glyph is the Material Rounded bulleted-list icon (`FormatListBulleted`).
- Library normal and selection top bars use the same 58dp content height; multi-select uses `SelectAll`.
- Library vertical gestures reserve 13% on each side for close and the center 74% for pull-to-refresh.
- The currently displayed buffer page owns the selector highlight; the other buffer stays visually dimmed even if it is the active capture destination. Disabled buffers remain viewable, show Off, and cannot become active.
- Quick Settings tile taps must never surface MainActivity: usable tiles start/switch capture, the recording tile stops, disabled/full is unavailable, and long-press opens Reverb. On modern Android, cold OFF→ON may use the transparent no-history QuickTileActionActivity only until microphone capture is actually active.
- Brand marks keep the main stroke on the current foreground color and derive echo/accent strokes from the Material primary color; Android 12+ launcher accents use system Material You colors and Android 13+ keeps a monochrome themed-icon mask.
- Buffer selector taps navigate and activate the tapped usable buffer; horizontal swipes navigate only. Only the blob starts or stops recording.
- The Library action is always visible and must open even when the library is empty.
- Both buffer readouts use the configured retention mode for their primary metric.
- Main vertical panel reveals and horizontal buffer flips track gesture progress continuously; release only decides whether to finish or return.

- Library recording playback expands the tapped recording card vertically in place; never replace it with a player dialog or separate player page. Reuse the range-export continuous waveform renderer for playback and trim.
- Saved recording waveforms are cached in the catalog against a content revision and mirrored into the open Library state; never reuse a cache after the physical content identity, size, or duration changes.
- Library editors, confirmations, errors, and permission prompts use Reverb-styled sheets instead of stock alert dialogs; About remains a custom animated top panel.

# Reverb durability invariants

- Trimming a saved recording is non-destructive: write and verify a new output before cataloging it; never mutate or replace the source recording as part of trim.
- Missing or temporarily unavailable audio is never deletion evidence; only explicit user deletion may destroy saved audio.
- Destructive actions are journaled/retryable. Moves are copy + fsync + byte verification + catalog commit before source deletion.
- Verified exports survive metadata/UI/service failures; service teardown is not user cancellation.
- FILE/SAF exports write to scanner-excluded staging targets and publish final names only after verification; partial output must never enter the Library as a finished recording.
- Crash recovery may auto-publish only a structurally complete old-session `export` staging artifact; `copy` staging and current-process staging remain hidden and non-destructive.
- An active export owns an Android `dataSync` foreground-service lifetime when microphone capture is not already keeping the service alive; UI unbind/recorder stop must not destroy export work.
- Android microphone foreground-service eligibility failures pause runtime capture but must preserve the durable recording intent; retry on a foreground bind or explicit start.
- Buffer read leases keep their referenced chunks readable across clear/retention changes; ambiguous or corrupt recovery artifacts are preserved, not silently deleted.
- Physical deletion intents are versioned and content-fingerprinted; after process loss, never replay physical deletion against a present asset. Replay may only wait, abandon the intent, or finish catalog cleanup after confirmed deletion/absence.
- A verified move target becomes recoverable before source cleanup; delete the source only after re-verifying byte identity, and never discard the verified target because source cleanup is uncertain.
- Durable capture intent/destination changes use synchronous persistence; active PCM is force-synced on a bounded background cadence, and replacement PCM must be durable before retention evicts older audio.
- FILE catalog entries bind to a stable filesystem-object identity; stale path reuse must fail closed for delete/rename/read/play/share/copy, and explicit FILE deletion must atomically claim the selected object under a journaled tombstone before destruction.

- Range export is a home-screen state layered around the existing AudioBlobView; do not modify or restyle the blob renderer/animation to implement the timeline.
- Range-export fine adjustment is a 2D spring field: horizontal pull controls direction/base jog rate and is intentionally more sensitive than Y, upward pull accelerates, downward pull increases precision, jog rate is a percentage of total timeline duration, Y uses a tall low-sensitivity cosh field that stiffens toward horizontal edges, the foreground-colored puck tracks touch X 1:1 inside its visual bounds while seek sensitivity is applied separately, and release returns monotonically to center.
- Range waveform previews use a fixed source-sample budget independent of history length, build off the UI thread, and publish left-to-right while the unrevealed suffix remains animated.
- Blob-to-range transitions may transform the containing UI, but must not modify `AudioBlobView` rendering semantics.
- Range waveform construction is two-pass: a cheap coarse left-to-right materialization, then a finer fixed-budget left-to-right refinement; neither pass scales source reads with history duration.
- Direct timeline gestures invalidate focused time drafts; focused time fields must relinquish focus and resync when playback/scrubbing moves their target, while explicit export commits a valid focused draft first.
- Range timeline snapping is gesture-latched: once magnetism acquires, hold the snapped value for at least 1.25 s; a stationary held pointer may then resolve to its raw in-zone position, and must not re-snap until it exits and re-enters the magnetic zone.
- Android Back while range export is active dismisses range-export mode and releases/cancels its snapshot preparation; only Back from normal home may leave the app.
- Retired raw-buffer chunks persist an identity-bound tombstone before leaving the live timeline; index loss must never resurrect explicitly cleared or retention-evicted audio, including chunks held by abandoned read leases.
- Predictive Back mirrors each surface’s actual reverse navigation: Settings and Library track their vertical panels, range export reverses its blob/timeline morph, nested states reveal their parent, and root Home stays unhandled so Android owns app-to-launcher preview.
- Library predictive Back order is multi-select → inline trim → inline player → Library; the parent Library dismiss handler must stay disabled while an expanded inline player owns Back.
- Cancelled or failed pre-commit outputs use a content-fingerprinted cleanup journal; Library recovery suppresses those exact assets until deletion succeeds, and identity reuse must never authorize deletion of replacement bytes.
