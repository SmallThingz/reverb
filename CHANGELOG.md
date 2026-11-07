# Changelog

## 0.1.3-rc3 — 2026-11-07 (prerelease)

- Save recordings in the background. Keep source audio until the saved file is verified, including after a restart or buffer clear.
- Show save progress in Library rows and notifications. Reduce repeated file checks and source-data copies.
- Request All files access during setup. Rename and delete shared recordings without extra Android prompts when access is granted.
- Fix rename failures, multi-recording deletion, and playback state at the end of a recording. Show specific error causes.
- Improve silence detection and calibration controls. Add a calibration adjustment from -6 to 18 dB.
- Improve light and dark theme contrast, shared controls, and progress animations.
- Restore the blob's full active color, remove its inner line, and smooth its response to silence.
- Fix Library scroll restoration, keyboard input, and gesture handling.

## 0.1.3-rc2 — 2026-10-29 (prerelease)

- Fix native animation compatibility on Android 9–12 while preserving system animation-scale settings.
- Resolve release validation findings in native focus, accessibility, drawing, and string fallback handling.
- Include the native UI, range-selection, preview, and recovery improvements listed below.

## 0.1.3-rc1 — 2026-10-29 (withdrawn; no APK published)

- Move the app UI to native Android Views backed by the separate smallthingz.ui library, retaining the capture, Library, Settings, and onboarding flows.
- Fix touch editing and keyboard focus, and prevent hidden panels from exposing stale accessibility actions.
- Correct fractional range selection at ordinary audio-chunk boundaries and improve export cleanup failure reporting.
- Reduce stereo preview conversion work while preserving exact PCM output.
- Improve permission retry and settings-return handling, onboarding controls, and saved UI state restoration.

## 0.1.2-rc1 — 2026-10-02 (prerelease)

- Accelerated large-history startup and saved-WAV discovery while retaining full integrity checks before export and deletion.
- Fixed repeated Quick Settings refreshes while idle and improved recorder startup coordination.
- Improved provider compatibility, export timeout reporting, interrupted-recording recovery, and safe recording cleanup.
- Refined range playback, incident navigation, selection animations, and shared launcher/splash branding.
- Added tagged-source, signing-certificate, APK metadata, and alignment verification to the release process.

## 0.1.1 — 2026-09-24

- Preserved recordings, buffer data, and legacy settings when upgrading from 0.1.0. Fixed MediaStore exports and migration for providers that omit a new file's size.
- Made recording interruption history more reliable across process exits, service shutdown, and delayed Android exit evidence. Incident cards can now be checked, copied, or deleted without deleted incidents reappearing.
- Improved inline playback and trim seeking. Rapid seeks now complete in order, and playback stops cleanly when the Library closes, the app pauses, or a trim is saved.
- Restored the Library Back button and refined predictive Back, buffer selection, and selection animations.
- Hardened Quick Settings and recorder service handoffs, storage recovery, provider identity checks, deletion journals, and failure rollback paths.

## 0.1.0 — 2026-08-30

- Initial Reverb release with disk-backed rolling capture, One-shot and Looping buffers, WAV export, Library playback and editing, Quick Settings controls, and configurable audio hardware settings.
