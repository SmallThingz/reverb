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

The original 20 September release APK was unsigned. Its locally signed release smoke-test copy used the debug key and was not a production distribution artifact. Nothing was published or pushed during that validation.

## Production signing and screenshots: 24 September 2026

- Built version 0.1.1 (version code 2) from source commit `3df76aedea5d9a580486017978e56a727ccf3419` using the Gradle wrapper and the cached JDK 21 toolchain.
- Used `~/.gradle/release.env` and `~/.gradle/release.keystore`, passing signing credentials as process-local Gradle property environment variables. Signing material remains outside the repository.
- `:app:assembleRelease :app:testDebugUnitTest :app:lintRelease --offline --console=plain` succeeded. All 693 unit tests passed with no skips; release lint reported zero errors and 20 warnings.
- The R8-minified release APK is signed with APK Signature Scheme v2. `apksigner verify` and 16 KiB page-aware `zipalign` verification passed. The APK certificate SHA-256 exactly matches the supplied keystore certificate: `3866bc43d3dd58bc0cfa9c716a93aaa6f34abdfaa7315a3b924079397d1decfe`.
- APK SHA-256: `a92fc744640702a328ddf3712badc70652f49ff167a1f5145edc0f77d9d4a408`. The release package is `app.smallthingz.reverb`, targets API 37, requires API 28+, is not debuggable and has no network permission.
- Store screenshots are unedited 1080 x 2400 captures from the physical Xiaomi M2101K7BG on API 36. A separate debug installation, `app.smallthingz.reverb.screenshots`, used the same source commit with an application ID override. It contained synthetic sample WAV recordings and a silent sample capture buffer. The screenshots show the actual Capture, Library, Trim and Settings screens; they are not evidence that the production-signed APK was installed on the device.
- The screenshot gallery lives in `fastlane/metadata/android/en-US/images/phoneScreenshots/` and is reused by the README. [F-Droid descriptions do not support embedded images](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/); its gallery supplies them alongside the text.

This signing check proves that the APK matches the supplied key. It does not establish whether that key matches a previously distributed APK or an F-Droid-signed installation. No remote release was published.
