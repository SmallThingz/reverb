# Debug UI controls

The primary entry point is the explicit `DebugUiCommandReceiver`: each ordered
broadcast inspects or operates the **already-running MainActivity** in its current
process/session. A private startup-only `DebugUiInitializer` registers one weak
Activity registry. Neither launches, finishes, focuses or restarts an Activity,
forwards commands to a recorder Service, changes permission, or schedules polling.
Cold process queries return `NO_ACTIVITY` until the app is launched normally.

Implementation and manifest entry live only in `src/debug`; tests live only in
`src/testDebug`. Normal `release` has none of these sources or manifest declarations.
No BuildConfig-only exclusion, main-source hook, extra dependency, server, or UI
library demo application is added.

## Root-owned validation and installation

Do not install ordinary `assembleDebug` over the installed normal candidate: the
normal debug variant currently shares its application ID. Root assigns the single
Gradle runner and, if physical validation is needed, an isolated suffixed package
using existing variant machinery. The opt-in reliability runner remains restricted
to its existing suffixed packages. No package/runner guard is relaxed here. This
patch itself does **not** configure or install a suffixed debug artifact.

After root has provisioned an appropriate isolated debug installation, take a
snapshot using an explicit ordered shell broadcast (the sender needs `DUMP`):

```sh
adb shell am broadcast --receiver-foreground \
  -n '<installed-debug-package>/app.smallthingz.reverb.DebugUiCommandReceiver' \
  -a app.smallthingz.reverb.debug.ui.SNAPSHOT
```

Read the returned JSON's `generation`, then send one typed activation:

```sh
adb shell am broadcast --receiver-foreground \
  -n '<installed-debug-package>/app.smallthingz.reverb.DebugUiCommandReceiver' \
  -a app.smallthingz.reverb.debug.ui.ACTIVATE \
  --es control_id navigation.settings --es generation '<exact-snapshot-generation>'
```

The ordered broadcast result data is bounded JSON; ordinary unordered broadcasts
perform no UI action. There is no intent filter, network listener, general evaluator,
provider query/mutation API, or Service forwarding. Snapshot takes no extras;
ACTIVATE takes exactly the two string extras above. Unknown verbs/IDs/extra fields
return `INVALID_REQUEST` without invoking UI handlers. The process UUID invalidates
prior tokens after restart. Re-query after a frame/state transition rather than
retrying a stale token. The receiver returns immediately with the current state;
it has no await loop, executor, posted action, or focus promotion.

`DISPATCHED` means the accessible handler accepted the invocation, not that an
asynchronous result succeeded. Query again to observe rendering/hydration/scrolling.
A blocked query still reports its state. Onboarding, absent Main UI, dialogs that
own focus, lifecycle pause and ordinary permission gates stay in place.

## Stable IDs and results

| ID | Real action |
| --- | --- |
| `navigation.settings` | Currently admitted Settings gear |
| `navigation.incidents` | Currently admitted Incidents action |
| `navigation.library` | Currently admitted Files/Library action |
| `navigation.back` | Currently admitted labeled Back action |
| `library.scroll.forward` | Library viewport's advertised forward scroll action |
| `library.scroll.backward` | Library viewport's advertised backward scroll action |
| `incidents.selection.enter` | Incidents' real Multi-select action |
| `incidents.selection.all` | Incidents' real Select all action (never Deselect all) |

IDs map to an explicit allowlist of resource-backed semantic labels on real merged
accessibility action leaves. They do not accept arbitrary labels, view paths,
methods or code. Hidden duplicates are excluded; multiple eligible leaves return
`AMBIGUOUS` rather than guessing. This first version does not edit text, invoke
recording/delete actions, or control separate dialogs. Such actions require their
own explicit supported UI binding and tests; setting internal values is not an
extension mechanism.

Scroll dispatch uses the current viewport's accessibility delegate and its existing
semantic scroll handler; it never calls `scrollBy` or changes offsets directly. At
a boundary the unavailable action is reported. Incidents selection uses two real
clicks: enter selection, then select all. An all-selected header changes its label,
so `incidents.selection.all` becomes unavailable rather than toggling selection off.
Snapshots include the exposed Library scroll offset and Incidents selection-mode
flag; viewport scrolling and data loading can remain asynchronous.

Observation tokens bind Activity/root/lifecycle, observed navigation, and resolved
eligible control-leaf identities. An old Library Back observation cannot target a
later Settings Back in the same Activity. Queries and actions require main-thread
execution; actions additionally require RESUMED, attached/shown, window focus,
layout readiness, every ancestor's source input admission and accessibility
exposure (including `NO_HIDE_DESCENDANTS`), and a currently visible/enabled advertised
action. Rejected states have distinct statuses such as `STALE_OBSERVATION`,
`NOT_RESUMED`, `WINDOW_UNFOCUSED`, `SURFACE_BLOCKED`, `MOVING` and `NOT_PRESENT`.

The motion gate checks the existing Settings/Home translation, Library reveal
translation and Incidents presentation against their navigation endpoints. It
rejects in-flight presentation; it cannot establish that a private predictive
Back/drag owner has not just begun at an unchanged endpoint. Do not overlap harness
commands with physical gestures. No private owner state is read or overridden.

## Optional batch instrumentation

`DebugUiInstrumentation` is a separate scripted driver. Unlike the primary receiver,
it starts an instrumented process, launches MainActivity and finishes at plan end.
Use it for a new instrumented session. The separate `ReliabilityInstrumentation`
retains its own suffixed-package check. This batch driver relies on root provisioning
the isolated debug target described above and is declared only in the debug manifest.
It accepts up to 16 comma-separated `snapshot`, `activate:<id>`, `await:<id>` commands
(1024 characters). Await polls on the instrumentation worker with a 3-second polling
budget, capped by a 30-second plan budget that begins after startup. Deadlines are
checked between synchronous UI observations. Startup and waiting for main-thread
execution can extend elapsed time beyond those budgets. Results are JSON in
`ui_result` and final `ui_results`.

```sh
adb shell am instrument -w \
  -e plan 'snapshot,await:navigation.settings,activate:navigation.settings,snapshot' \
  '<installed-debug-package>/app.smallthingz.reverb.DebugUiInstrumentation'
```

## Tests (root runner only)

Host selector: `app.smallthingz.reverb.DebugUiControlsTest` on `testDebugUnitTest`.
Tests attach actual Main UI and invoke the real accessibility handlers. They cover
navigation dispatch, retained hidden Home exclusion, lifecycle/focus rejection,
old observation rejection, opening motion, explicit exposure policy and protocol
bounds. They are staged but have not been compiled or executed by this worker.
Release source exclusion is structural here; root's artifact check should confirm
no `DebugUi*` classes/instrumentation entry in the release APK before claiming a
measured zero-byte delta.
