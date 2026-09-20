# Reverb 0.1.1 validation

Validated on 20 September 2026. Version code advances from 1 to 2.

## Changes

- Legacy enum and buffer preference reads are now side-effect free. Removed redundant asynchronous migration writes that could overwrite a newer durable save or capture handoff. Explicit saves still write stable byte codes; legacy strings remain readable.
- Named null capture-authority values fail closed instead of masquerading as missing first-install preferences.
- New pending MediaStore outputs explicitly seed size zero. The reference provider previously returned null size and rejected ordinary exports and automatic legacy migration. Descriptor size and identity verification remain required.
- Inline playback captures its preparation effect key during composition, preventing the same MediaPlayer from being prepared twice and incorrectly falling back to an external viewer.
- The visible Library header restores its Back action. Inactive full buffers stay dimmed; selection colors animate smoothly.
- Stopped debug diagnostics use an ordinary started service instead of an unfulfilled foreground-service promotion request that caused an ANR. Null service-start returns are rejected.

## Automated checks

Command: ./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --offline --console=plain.

All 684 unit tests passed, including four new regression tests for legacy-read races, canonical reads, and null authority. Debug and R8-minified release APKs built successfully. Lint: zero errors, 18 debug warnings and 20 release warnings. Warnings remain; this is not a warning-free claim.

## Physical-device checks

Reference device: Xiaomi M2101K7BG, Android API 36, 1080 x 2400.

- Stopped capture before replacing the existing installation. The first update retained exact buffer byte counts, durations, and chunk counts. Subsequent authorized capture tests exercised the existing rolling buffer; its normal retention continued. Final capture state is stopped.
- Checked capture navigation, Settings and choices, Library, full One-shot behavior while Looping records, and Quick Settings. Viewing a buffer did not retarget capture; tapping full One-shot left Looping recording. QS stopped capture without opening an Activity or closing the shade; the recording tile showed Recording without a frozen timer.
- Built the actual v0.1.0 tag under an isolated test application ID, seeded legacy preferences, and used its microphone capture/export to create real retained chunks and a saved WAV. Upgraded that installation in place to the candidate build.
- Upgrade preserved legacy preferences and the retained One-shot payload: 2,159,136 bytes, 24.48 seconds, five chunks. Library database upgraded from schema 2 to 5. Automatic FILE-to-MediaStore migration preserved all 441,046 original WAV bytes.
- Original WAV SHA-256 before upgrade, after migration, and after trim: f70dc0b7fb860e2ca1682fcdcb27b5984ab1e1ffbac67fa01674f9fae957f7a3.
- Inline trim produced a separate 287,576-byte, 3.260-second recording while retaining the original five-second recording. Screenshot inspection confirmed inline waveform controls and both resulting Library rows.
- Installed a debug-key-signed copy of the minified release APK over the main test installation. Launch and persisted 48-hour buffer hydration passed. Restored the final debug build for further ADB debugging.
- Reproduced foreground-service diagnostic ANRs on the older builds; no new crash/ANR events appeared after the fixes during these checks.

Local evidence and APKs are in .tmp/production-audit/ (not versioned), including upgrade catalogs, integrity results, and screenshots of capture, Settings, Quick Settings, Library, and trim.

## Release limits

This is one physical API 36 device, not an exhaustive Android/OEM/provider or lifecycle-fault matrix. The existing durability regression suite passed, but every documented invariant was not independently fault-injected on hardware in this pass. The isolated v0.1.0 upgrade uses the real tagged code with a test application ID and debug signing; it does not prove compatibility with an unknown distributed signing certificate.

The release APK is unsigned. Distribution must use the same production signing identity as v0.1.0. The locally signed release smoke-test APK uses the debug key and is not a production distribution artifact. Nothing was published or pushed.
