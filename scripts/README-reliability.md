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
