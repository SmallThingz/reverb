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

# Reverb durability invariants

- Missing or temporarily unavailable audio is never deletion evidence; only explicit user deletion may destroy saved audio.
- Destructive actions are journaled/retryable. Moves are copy + fsync + byte verification + catalog commit before source deletion.
- Verified exports survive metadata/UI/service failures; service teardown is not user cancellation.
- FILE/SAF exports write to scanner-excluded staging targets and publish final names only after verification; partial output must never enter the Library as a finished recording.
- Crash recovery may auto-publish only a structurally complete old-session `export` staging artifact; `copy` staging and current-process staging remain hidden and non-destructive.
- An active export owns an Android `dataSync` foreground-service lifetime when microphone capture is not already keeping the service alive; UI unbind/recorder stop must not destroy export work.
- Android microphone foreground-service eligibility failures pause runtime capture but must preserve the durable recording intent; retry on a foreground bind or explicit start.
- Buffer read leases keep their referenced chunks readable across clear/retention changes; ambiguous or corrupt recovery artifacts are preserved, not silently deleted.
- Physical deletion intents are versioned and content-fingerprinted; after process loss, never replay physical deletion against a present asset. Replay may only wait, abandon the intent, or finish catalog cleanup after confirmed deletion/absence.

- Range export is a home-screen state layered around the existing AudioBlobView; do not modify or restyle the blob renderer/animation to implement the timeline.
