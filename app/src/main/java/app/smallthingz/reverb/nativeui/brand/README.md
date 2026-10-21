# Native brand mark proposal

Not integrated, compiled, or device-qualified. Candidate4 and production Home remain unchanged.

## Source contract

ReverbBrandMarkView is the leaf counterpart of released ReverbBrandMark at
3e16f8ddb78dca15df4b436d34d330801ce83ffd. SOURCE.json pins the composable, canonical
icon.svg, generated foreground XML, all three original color-resource files, and
the existing generator. All seven files were compared byte-for-byte with that
release. Retain these pins in the parity runner before accepting a future build.

- Resolves launcher_background/accent/main from the same Resources and Theme.
  It does not substitute Material color roles, choose a new local theme, or use a
  fixed palette. Released pre-31 resources are navy/teal/white; API31+ light/night
  variants reference original Android dynamic resources. No independent app-icon
  selection enum exists in this release.
- Draws the two canonical Paths six times: far body/leg, near body/leg, main
  body/leg. Root 44.5 and child 56/28/0 translations stay separate. Stroke width26,
  round cap/join, miter4; no local artwork offsets.
- Uses the exact resource-dimension Float conversion, FillBounds multiplication,
  centered rounded draw bounds, ceil cache dimensions, and 1:1 bitmap composite
  from pinned Compose UI1.11.2. The multicolor vector uses ARGB_8888, not an alpha
  mask. Transparent-black fills are omitted only because they draw nothing;
  their source presence still informs the correct non-tintable cache selection.
- CircleShape is a round rect with min(width,height)/2 radius. Hardware uses the
  native outline, matching Compose's simple outline strategy; software uses a
  Path clip. A nonsquare mark remains a capsule, not an oval.
- Cache grows/reuses across layout changes and redraws only on source size/palette
  changes. Detach drops owned bitmap references without recycling hardware-used
  bitmaps. No timer, scheduler, workers, runtime vector interpreter, or render tree.
- Null description is decorative; a description exposes an ImageView role/name.
  Click/ripple and accessibility merging remain with the parent. No padding is
  added. A bounded dimension fills its constraints; an unbounded axis uses the
  original108dp intrinsic size.
- Configuration/attach refresh resource colors and geometry. A host changing its
  theme object without configuration delivery calls refreshResourcePalette().
  That is a binding requirement, not permission to change original theme policy.

## Finite fixture proposal

fixture/NativeBrandParityProbe.kt calls actual released ReverbBrandMark and parses
the original foreground through the pinned ImageVector resource loader for an
independent FillBounds geometry oracle. It uses the existing isolated native
parity Application/Activity helpers, no production startup or user interaction.

ABAB exact-ARGB comparisons cover current density and280dpi, resource day/night
contexts,46dp topbar,104dp About, nonsquare104x46dp capsule, RTL and return-to-small
with retained native cache. Context overrides do not change system settings.
There is no tolerance, masking, screenshot reconstruction, or older-SDK simulation.
Both references and candidates must repeat exactly; all mismatch counts, failures,
cleanup errors and evidence-write errors remain visible.

Required integration later:
1. Add View plus source/license provenance under nativeui.
2. Add the probe to the existing reliability source set and dispatch a new
   native_brand case. Enforce every SOURCE.json source pin before the build.
3. Root-owned compile and actual physical-device fixture execution.
4. Only after rendered/semantics qualification, replace the existing brand leaf
   inside About/topbar while retaining their parent layout, click, and surface.

Source review by root found no concrete defect. Raster, real accessibility merging,
configuration-rebinding, and parent layout integration remain unqualified. This
proposal is not evidence of pixel parity or a production cutover.

## License/provenance

Artwork comes only from this project's GPLv3 source (repository LICENSE.txt);
there is no external replacement artwork. Renderer/measurement behavior derives
from AndroidX Compose UI/ui-graphics1.11.2, copyright The Android Open Source
Project, Apache2.0. The adjacent APACHE-2.0.txt preserves that license.
