# Native onboarding text ownership source contract

This binding helper is integrated into native-screens-20261021; it has not yet been compiled or run. Permission/buffer/audio rows now bind the reviewed direct delegate and non-drawing role child; page host wiring is owned by the native host. The frozen integration candidate and released Compose oracle are unchanged.

`NativePageTextAccessibilityOwner.kt` declares NativeWindowTextAccessibilityOwner, which owns one existing `PlainTextAccessibilityWindowScope` per native window, not one per label/page. `bindPage` creates only a page-lifetime map of existing View-to-AutoCloseable binding leases for labels, status descriptions and merged rows. It does not copy text, paragraphs, focus, selection, layout or application state into a second tree. It has no frame callback, polling, global registry, permission or backend action.

## Direct integration

1. The native Activity/window owner creates one `NativeWindowTextAccessibilityOwner(windowRoot, hostClips)` beside its existing window resources. The same owner survives page/style replacements in that window; a new Window receives a new owner. The `windowRoot` must correspond to the actual shared native root, not each page.
2. `OnboardingPageContentView` privately calls `window.bindPage(this, viewport, sourceExposure)` after constructing its rows. Keep the returned `NativePageTextAccessibilityOwner` until page disposal. Do not close it merely for a pager detach/reattach.
3. Call `refresh()` only after actual optional permission-row membership changes. Stable labels keep their ReverbParagraphView semantic identity and shared cursor; removed labels lose their lease. GONE warnings may stay bound because exposure reads current visibility on demand.
4. Explicit page disposal closes its owner before releasing the page. The ordinary window owner drops its scope on window disposal. Old paragraph close handles are already token-guarded.
5. Keep text node exposure and current source page visibility explicit. `sourceExposure(label)` must include any source hide/transparent/pager exclusion policy; it must not hide a label just because a parent merges descendants. Native accessibility focus and keyboard focus remain platform-owned.

The page helper knows exactly two clipping sources in the released page: the explicit vertical-scroll viewport and the private content ancestor inside each ReverbGroupLayout (both the20dp group and14dp marker Surface). Other ancestors inside the audited released page are source-unclipped. Every ancestor between page and window root is delegated to the mandatory `hostClips` policy; unknown mapping returns null, so character/page geometry stays unavailable rather than using integer or guessed bounds. The helper does not infer source clipping from native clipChildren defaults. Future page clip/layout additions require updating this source mapping.

## Necessary semantic corrections in page rows

The binding alone cannot fix a native hierarchy that hides all Text children. The integrated row bindings implement the following source rules. The fixture must verify them without masking differences:

- Standalone header, page description, buffer-order text and warning Text nodes stay individually exposed and non-keyboard-focusable. Their text is the original string, including while translation displays a substitution. Bind each original Paragraph through the shared window owner.
- Permission marker Text, title and optional body are separate original Text nodes. Expose their actual descendant views. Keep structural marker/group wrappers non-important without hiding descendants.
- Permission status Icon is a separate node with its actual `Allowed`/`Allow` contentDescription. Its source Image role does not force ImageView class under a merging ancestor (delegate642–648). Do not move that description to the clickable row or concatenate it into text. Header/buffer marker icons have null source descriptions and remain decorative.
- Buffer/audio title/body Text nodes remain descendants of the toggleable row. Material3 Switch(onCheckedChange=null, thumbContent=null) adds no toggleable semantics of its own (Switch.kt104–117), so its purely visual native subtree remains hidden; the parent still owns checked state and action.
- Remove synthetic newline text from action rows. Screen-reader grouping is not equivalent to replacing separate Text/contentDescription fields with a single string.
- Merging action parents need source screenReaderFocusable grouping. Their separate Text/status children remain exposed but screenReaderFocusable=false and non-keyboard-focusable: SemanticsNode.isUnmergedLeafNode100–106 additionally requires no merging ancestor. Standalone page Text leaves are screenReaderFocusable=true. Preserve enabled/click action logic independently of child label exposure.

Exact role handling needs particular care: pinned SemanticsNode467–475 adds a role-only fake child when a merging node with a role has children. The native delegate632–650 applies role to that child; it does not always set the action parent's class to Button/Switch. Switch role uses a localized roleDescription. ReverbMergedRowAccessibility uses a direct non-drawing native semantic child, preserving actual child Views; a mixed real/virtual provider was rejected against Android prefetch behavior. The owner supplies row geometry and status-description cursor bindings alongside ordinary text bindings. Actual node/event qualification remains pending.

## Source provenance

Pinned ui-android1.11.2 AndroidComposeViewAccessibilityDelegateCompat:

- 621–650: default class, unmerged Text class and conditional role mapping.
- 789–803: enabled/focus capability independent of textual content.
- 892–938: plain-text traversal, cursor and character-location actions.
- 1933–1961/2114–2141: original UTF16 boxes, source clip rectangle and float screen transform.
- 3456–3467: screenReaderFocusable merging parent or speaking unmerged leaf.
- 3470–3473: info.text comes from the first UNMERGED Text string.

Pinned SemanticsNode.kt100–106/339–348/467–489: unmerged leaf excludes merging ancestors, replacedChildren, role-only and content-description fake nodes. Current delegate roots its exposed tree in unmergedRootSemanticsNode, so Compose test merged semantics must not be used as the Android node oracle.

The vertical scrolling drawing outline extends30dp on the cross axis (VerticalScrollableClipShape), but text semantic bounds do not: NodeCoordinator.rectInParent1309–1324 intersects clipBounds with0..layer.width/height independently of that outline. SemanticsNode.boundsInRoot167–168 uses this route. The helper therefore intentionally retains the viewport rectangle for accessibility while NativeVerticalViewport separately preserves the expanded drawing clip. This distinction was reviewed against pinned source; no integer-bound shortcut or drawing-shape approximation is used.

Pinned Material3 Switch1.4.0 lines104–117: null onCheckedChange yields Modifier without toggleable semantics. Source release MainActivity OnboardingPage→OnboardingCard remains the generated parity oracle.

## Qualification still required

No device pass is claimed. The existing whole-page fixture must retain separate Text/status/role payloads, exact text geometry and full raw trees. It must compare newly exposed descendants and role-only nodes rather than hiding differences. Follow-up native text actions should prove shared-window character/word/paragraph/line/page cursor behavior, original-text translation semantics, and UTF16 character locations under real viewport/group clipping. Parent-window occlusion, pager visibility, screen-reader focus traversal and node event timing remain separate runtime qualification; this binding does not approximate them or declare them complete.
