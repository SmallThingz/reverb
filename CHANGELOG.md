# Changelog

## 0.1.1 — 2026-09-24

- Preserved recordings, buffer data, and legacy settings when upgrading from 0.1.0. Fixed MediaStore exports and migration for providers that omit a new file's size.
- Made recording interruption history more reliable across process exits, service shutdown, and delayed Android exit evidence. Incident cards can now be checked, copied, or deleted without deleted incidents reappearing.
- Improved inline playback and trim seeking. Rapid seeks now complete in order, and playback stops cleanly when the Library closes, the app pauses, or a trim is saved.
- Restored the Library Back button and refined predictive Back, buffer selection, and selection animations.
- Hardened Quick Settings and recorder service handoffs, storage recovery, provider identity checks, deletion journals, and failure rollback paths.

## 0.1.0 — 2026-08-30

- Initial Reverb release with disk-backed rolling capture, One-shot and Looping buffers, WAV export, Library playback and editing, Quick Settings controls, and configurable audio hardware settings.
