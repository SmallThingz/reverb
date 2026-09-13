# Reverb UI invariants

- Buffer switching is one stationary capture surface, never a pager or sliding screen transition.
- On buffer changes, the blob and buffer-specific bottom actions use a depth flip. The Library icon stays static and must never flip.
- The Library glyph is the Material Rounded bulleted-list icon (`FormatListBulleted`).
- Library normal and selection top bars use the same 58dp content height; multi-select uses `SelectAll`.
- Library vertical gestures reserve 13% on each side for close and the center 74% for pull-to-refresh.
- The currently displayed buffer page owns the selector highlight; the other buffer stays visually dimmed even if it is the active capture destination. Disabled buffers remain viewable, show Off, and cannot become active.
- Quick Settings buffer tiles are start/activate controls: usable tiles switch capture to that buffer; disabled or full buffers are unavailable.
- Brand marks keep the main stroke on the current foreground color and derive echo/accent strokes from the Material primary color; Android 12+ launcher accents use system Material You colors and Android 13+ keeps a monochrome themed-icon mask.
- Buffer selector taps are navigation-only and follow the same path as horizontal swipes; only the blob starts or stops recording.
- The Library action is always visible and must open even when the library is empty.
- Both buffer readouts use the configured retention mode for their primary metric.

- Main vertical panel reveals and horizontal buffer flips track gesture progress continuously; release only decides whether to finish or return.

# Reverb durability invariants

- Missing or temporarily unavailable audio is never deletion evidence; only explicit user deletion may destroy saved audio.
- Destructive actions are journaled/retryable. Moves are copy + fsync + byte verification + catalog commit before source deletion.
- Verified exports survive metadata/UI/service failures; service teardown is not user cancellation.
- Android microphone foreground-service eligibility failures pause runtime capture but must preserve the durable recording intent; retry on a foreground bind or explicit start.
- Buffer read leases keep their referenced chunks readable across clear/retention changes; ambiguous or corrupt recovery artifacts are preserved, not silently deleted.
