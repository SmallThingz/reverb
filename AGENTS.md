# Reverb UI invariants

- Buffer switching is one stationary capture surface, never a pager or sliding screen transition.
- On buffer changes, the blob and buffer-specific bottom actions use a depth flip. The Library icon stays static and must never flip.
- The Library glyph is the Material Rounded bulleted-list icon (`FormatListBulleted`).
