# Repository content color

The released About repository title supplies `titleSmall` but no explicit Text color.
Material3 1.4.0 `Text.kt:123` chooses explicit color, then `style.color`, then
`LocalContentColor`. Its clickable Surface supplies `contentColorFor(surfaceContainerHighest)`
(`Surface.kt:202,212–214`). The ordered value lookup in `ColorScheme.kt:1064–1091`
checks earlier roles, including `surfaceVariant`, before `surfaceContainerHighest`.
Equal colors therefore do not necessarily produce `onSurface`.

`ReverbAboutRepositoryColor` preserves that order for this known background and the
TextStyle override. The repository title and its Surface ripple use these resolved
values on creation and rebinding. The explicit GitHub glyph and subtitle colors,
palette, geometry and other About content are unchanged.

The hosts11 comparison retained 3,668 differing title pixels in each full light/dark
scene, bounded by `(211,673)..(497,708)`. The short case remained pixel exact.
Four focused source-oracle tests cover fallback palettes, colliding background roles,
lookup precedence and an explicit text style. They are added but not run in this lane;
the corrected raster still needs the next authorized paired probe.

The same probe separately failed the released Compose Close `RequestFocus` action
at `NativeAboutParityProbe.kt:598`. That reference failure is retained. It is not a
native Close result, and this correction changes neither fixture guards nor input.
