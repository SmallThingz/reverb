# Isolated Android reliability checks

The opt-in `reliability` build type installs as `app.smallthingz.reverb.reliability`.
It does not update the normal `app.smallthingz.reverb` package. Do not use this
throwaway QA package for real recordings: its fixture data is intentionally changed
and its crash test deliberately terminates its own process.

The provider, instrumentation runner, and fault-injection code live only in
`app/src/reliability`. They are not included in ordinary debug or release builds.
The init script adds no runtime dependencies or changes to normal build settings.

## Build and run

Run from the repository root with an explicit device selected in `ANDROID_SERIAL`:

```sh
./gradlew -I scripts/reliability.gradle :app:assembleReliability
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/reliability/app-reliability.apk
adb -s "$ANDROID_SERIAL" shell pm grant app.smallthingz.reverb.reliability android.permission.RECORD_AUDIO
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  app.smallthingz.reverb.reliability/app.smallthingz.reverb.ReliabilityInstrumentation
```

The input-failure fixture allocates an `AudioRecord`, overrides reads with an
injected disconnection, and never starts microphone capture. The permission is
needed for constructing that Android object. Source and export fixtures are
synthetic and app-private; the suite does not publish files into shared Music.

Require the final `RESULT tests=... failures=0` line. Do not treat a zero `adb`
exit status as proof that instrumentation assertions passed. A single case can be
selected with `-e test rename_new_id_missing_old_uri`, `-e test capture_restart_failure`,
or `-e test wav_format_matrix` before the component name. Use
`-e test compatibility_sweep` for the provider-metadata, directory, and delayed
state-delivery regressions.
Use `-e test provider_terminal_sweep` for deletion, cleanup and export-timeout checks.

## Real process-death recovery

Run these two commands in order. The first intentionally reports that the
instrumentation process crashed, after reporting `PREPARED durable PCM`:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -e test crash_prepare \
  app.smallthingz.reverb.reliability/app.smallthingz.reverb.ReliabilityInstrumentation
adb -s "$ANDROID_SERIAL" shell am instrument -w -e test crash_recover \
  app.smallthingz.reverb.reliability/app.smallthingz.reverb.ReliabilityInstrumentation
```

This writes a checksummed checkpoint, appends and syncs a newer PCM tail without
updating the header/index checkpoint, arms interruption tracking, then kills only
the isolated QA process. The fresh process must recover all 40,960 expected bytes
and exactly one interruption incident for the prior process. It must also recover
a verified but unpublished SAF export whose size/revision proof came from its
native descriptor, even though the restarted provider now supplies normal metadata.
The recovered WAV must match every expected byte. This tests process
death, not physical power loss or the ability to capture while Android has killed
or prohibited the microphone service.

## Coverage

The on-device suite exercises the real ContentResolver, DocumentsProvider,
WAV writer, output verification, publication, service failure handler, and storage
recovery code. Provider cases cover retired document IDs that throw instead of
returning an empty cursor, stable IDs, transport errors after committed rename,
no-op rename, provider-selected names, copy-like rename, changed bytes, and
loading/error/unavailable/duplicate child listings. The format matrix checks every
supported PCM format, mono/stereo, 8/16/48/96 kHz, private FILE and SAF output, and
odd-byte RIFF padding against exact expected bytes.
It also forces a write across Android's mutable creation-time fallback and rejects
a byte-identical replacement inode, while checking migration of existing same-revision
file identities without discarding their ctime guard.

Compatibility cases cover unknown size, absent/zero modification time, metadata
becoming available after writing, canonical-equivalent tree URIs, ordinary directory
child changes, and corrupt-database preservation with sidecars. Each successful
metadata-fallback export is also checked through discovery, verified sharing,
direct reads, and waveform/PCM readers. Known-row refresh must preserve an unchanged
native identity when optional metadata becomes known, but rebind an actual replacement.
Substituted same-byte descriptors and
replacement objects must fail; unknown-size nonempty creations and unprovable pipe
identities must never be opened for writing or deleted.

Delayed service callbacks are tested with independently controlled audio/main
queues: settings cannot be mixed across snapshots, older commands cannot replay,
and export acceptance/completion cannot have its busy state overwritten by a
queued callback. JVM tests additionally check that queued Quick Settings render
tasks sample current state when executed rather than when posted.

Terminal tests cover completed document deletions whose old IDs now throw, transport
failure after deletion, and cleanup queries that are temporarily loading or errored.
Unresolved output journals must survive until authoritative recovery. Move authority
and source replacements are tested during directory inspection. A provider write-open
timeout must report failure, preserve source PCM, clear busy ownership and allow a
byte-exact retry; explicit user cancellation must still deliver its neutral result.

The JVM sweep in `RecordingReliabilitySweepTest` checks 2,304 deterministic,
model-based operations over looping and one-shot stores: capture, size changes,
Off without Clear, explicit Clear, seal/checkpoint, and recovery. It uses the
service's deferred exact-boundary retention mode; legacy eager cleanup intentionally
has different whole-chunk eviction semantics. A separate lease test ensures accepted
export audio survives later capture, retention, explicit Clear, and reopen.

```sh
./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug
```

After collecting results, remove only the throwaway package:

```sh
adb -s "$ANDROID_SERIAL" uninstall app.smallthingz.reverb.reliability
```

The suite does not call or mutate the upstream issue tracker.

## Large-history performance and preparation UX

`-e test large_history_performance` uses 16,384 sparse one-MiB chunks (16 GiB logical
PCM, about 49.7 hours at 48 kHz mono/16-bit). Report the first process-local load
separately from subsequent cached loads. The retired-history case separates time to
restore the empty live timeline from background full-checksum cleanup. Both phases
must finish; moving cleanup later is not a reduction in its total work.

`process_cpu_ms` includes startup reader workers and other app threads; `cpu_ms`
measures only the calling thread. Do not report reduced caller CPU as reduced total
work after parallelization. `-e test cold_history_profile` samples only the calling
thread on an already-seeded fixture for attribution, not benchmark timing.
`parallel_history_recovery` checks the batched native path with corruption, a
symlink, truncation, a retired chunk and an ACTIVE checkpoint plus uncheckpointed
tail. All recovery and deletion still run serially after readers have finished.

`tile_hydration_convergence` runs the real persisted-state worker through repeated
identical observations, a genuine duration change and live capture authority. Only
the genuine change may republish: a refresh that hydrates identical data must not
request another SystemUI refresh and create a perpetual background loop.

`-e test saved_file_performance` writes and verifies a 64 MiB WAV, then measures
publication, structural discovery and repeated exact-revision byte proofs. `/proc`
read counters measure bytes requested, not physical flash traffic. Compare identical
fixtures on the same device and retain complete result logs and source/APK hashes.

For actual UI testing, install a fresh throwaway QA package, then run
`-e test ui_seed_large_history` followed by `-e test ui_range_loading`. The seeder
refuses any existing default history rather than clearing it. The UI test launches
the actual Compose screen, delays the audio queue, closes pending range preparation,
and checks that the late snapshot releases every lease. It saves `history-home.png`,
`range-preparing.png`, and `range-cancelled.png` in the QA package's `files` directory.
These screenshots require visual review; a successful instrumentation exit is not
visual approval. Never use the normal populated Reverb package for this procedure.
Grant `RECORD_AUDIO` and `POST_NOTIFICATIONS` to the throwaway package before the UI
run, so system permission dialogs do not obscure the tested surface. The test never
starts microphone capture. An enabled child icon alone is not action readiness:
wait for the actual clickable parent and select the populated Looping buffer.

Crash recovery additionally verifies that a v3-journaled retired range claim is
cleaned while newer PCM and an unrelated unowned file survive exactly. Unverified
partial exports and legacy/ambiguous claims remain preserved: neither their age nor
temporary-looking filename authorizes deleting potentially unique user audio.

`-e test provider_fingerprint_performance` checks three production pre-publication
proofs for a 64 MiB SAF document and a real MediaStore row. The MediaStore row stays
pending and is never published into the Library; cleanup targets only its exact
verified synthetic identity. This case also asserts that the default fingerprint
path still reads the complete payload for final/destructive verification. Repeated
pre-publication proof reuse requires the same native descriptor revision, not just
provider metadata. Run the identical fixture on baseline and candidate builds.

## Released Compose versus native Views

Use only the actual connected Android phone, after current activity admission. The
UI-only build has the separate package `app.smallthingz.reverb.nativeparity.reliability`,
a plain Application, and a disabled normal launcher Activity. It neither replaces the
user's Reverb package nor an existing reliability package. No microphone, media, or
notification grants are needed. It does not initialize the recorder, settings, or
incident recovery; Android may still initialize isolated QA providers and directories.

```sh
./gradlew -I scripts/reliability.gradle -PreverbNativeParity=true :app:assembleReliability
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/reliability/app-reliability.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -e test native_font_notifications \
  app.smallthingz.reverb.nativeparity.reliability/app.smallthingz.reverb.ReliabilityInstrumentation
adb -s "$ANDROID_SERIAL" shell am instrument -w -e test native_group_parity \
  app.smallthingz.reverb.nativeparity.reliability/app.smallthingz.reverb.ReliabilityInstrumentation
adb -s "$ANDROID_SERIAL" shell am instrument -w -e test native_text_parity \
  app.smallthingz.reverb.nativeparity.reliability/app.smallthingz.reverb.ReliabilityInstrumentation
adb -s "$ANDROID_SERIAL" shell am instrument -w -e test native_icon_parity \
  app.smallthingz.reverb.nativeparity.reliability/app.smallthingz.reverb.ReliabilityInstrumentation
```

The build pins the released v0.1.2 group/divider implementation, chrome, and AppIcons source.
`native_group_parity` calls those actual Compose functions, then the native group,
with the same explicit bounds and synthetic rows. It checks light/dark palettes,
LTR/RTL, current/160dpi local contexts and 240/319dp widths (bounded by the actual
viewport). These are local View configurations on the physical device; system
settings and display density are not changed. All palette roles and fallback colors
are compared to Compose; a separate API31 mapping oracle is reported when available.
The latter does not claim execution on an older Android device.

After layout and draw/frame completion, PixelCopy captures each implementation twice
at the same app-content bounds. Every ARGB pixel participates: there is no mask or
tolerance, including border antialiasing and one-physical-pixel dividers. Both repeat
captures must also match. Unique `native-group-parity-*/result.json` directories in
the QA package's files directory contain exact differing-pixel counts, bounds,
maximum channel delta, pixel hashes and four PNGs per case. Preserve failed cases.
The final instrumentation `RESULT tests=... failures=0` is required; ADB success
alone is not a pass. PixelCopy failure, lost focus, or missing draws stop qualification
without reclaiming focus. No injected touch or keyboard input is used.

The separate text probe uses the released typography and callback-free Material3
Text as its oracle, with a separately raster-checked callback route for metrics.
Its six light/dark and font-scale 1/1.5/2 scenes compare exact pixels, baselines,
line counts, and measured dimensions against the native paragraph View.

Run `native_font_notifications` alone in a fresh UI-only instrumentation process.
It exercises native EmojiCompat readiness with a controlled real metadata loader:
attached automatic layout/draw, detached completion and reattachment, readiness before
Intrinsics construction, readiness during construction before observation, queued-callback
cancellation, and failed-load fallback. The construction race delegates the public native
font resolver unchanged and completes the loader once during resolution; the actual View
must replace its stale paragraph and draw again without manual invalidation. Empty metadata
avoids downloads and tests callback lifecycle only, not emoji glyph rasterization. The
previous EmojiCompat singleton is restored afterward. The retired Compose downloaded-font
and Snapshot bridge cases are no longer claimed as native coverage.

`native_text_backend` keeps 180 resolved software Paragraph cases against real Compose
Paragraph and adds 32 unresolved original-semantics cases after measuring a native View.
The latter compare against real MultiParagraph in English/Arabic process locales, two
roles and two font scales, including opposing leading text, wrapping, ellipsis and neutral
fallback. Process locale defaults are restored in `finally`; system locale settings are
untouched. A complete run has 212 cases. Exact metrics, UTF-16 geometry and pixels are
compared; failed images are retained for at most eight pixel cases and two metric-only
cases in the isolated QA files directory. This does not prove hardware/whole-screen or
TalkBack parity, or independently validate the role table. The separate Typography metadata
test and `native_text_parity` provide those role/reference checks.
`native_motion_spline` compares the real released/native decay engines numerically using
Android's friction value; it does not measure frame scheduling or smoothness. Both selectors
require the same exact UI-only package and plain Application as the other native probes.

The icon probe compares 32 released vectors with reused native Drawables through
grow/shrink/rectangle/retint-alpha/return states at current/160/280dpi. It checks
exact pixels, repeatability, and the pinned framework Fit/centering geometry.
Each probe has its own `native-*-*/result.json` and synthetic PNG evidence.

These focused probes do not prove interaction,
animation, accessibility, whole-screen, or cross-device parity. Review saved images
as well as machine results, retaining source/APK hashes and current device settings.

The normal Home probes are `native_home_parity` and `native_home_input`, using the
same isolated native-parity APK and instrumentation component. The static probe
compares 12 full-content scenes (six synthetic recorder states in light/LTR and
dark/RTL), including the real top bar and noise. A generated, pinned released
oracle substitutes only service-presence booleans; it never constructs a recorder.
Both routes use zero explicit local system insets and `visualizerVisible=false`.
Exact pixels, repeatability and blob bounds qualify that initial presentation;
live blob phase and system-inset dispatch remain outside the result.

The input probe checks six own-view finger streams, including expanded tab targets,
the overlapping direct Clear action, committed/cancelled swipes and RTL. It compares
exact callback actions and stable final pixels. Three intermediate captures per route
remain timestamped observations: sequential A/B captures cannot establish synchronized
frames or continuous smoothness. No physical/system touch injection occurs. Existing
physical-display/focus guards apply before every synthetic event. Source generation
and all adapter transformations are retained in the Home fixture provenance.
