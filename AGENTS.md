# Reverb UI invariants

- Buffer switching is one stationary capture surface, never a pager or sliding screen transition.
- On buffer changes, the blob and buffer-specific bottom actions use a depth flip. The Library icon stays static and must never flip.
- The Library glyph is the Material Rounded bulleted-list icon (`FormatListBulleted`).
- Library normal and selection top bars use the same 58dp content height; multi-select uses `SelectAll`.
- Library vertical gestures reserve 15% on each side for close and the center 70% for pull-to-refresh.
- The active capture destination stays highlighted while idle; disabled buffers remain viewable, show Off, and cannot become active.
- Quick Settings buffer tiles are start/activate controls: usable tiles switch capture to that buffer; disabled or full buffers are unavailable.
