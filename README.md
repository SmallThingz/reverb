<h1>
  <img src="app/src/main/icon.svg" alt="Reverb app icon" width="52" valign="middle" />
  Reverb
</h1>

![android](https://img.shields.io/badge/android-28%2B-3ddc84?logo=android&logoColor=0f172a)
![kotlin](https://img.shields.io/badge/kotlin-2.3.0-7c3aed?logo=kotlin&logoColor=ffffff)
![native](https://img.shields.io/badge/ui-Native%20Views-2563eb)

[<img src="https://f-droid.org/badge/get-it-on.svg" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/app.smallthingz.reverb/)

Rolling audio buffer recorder for Android. Keeps recent audio alive, survives restarts, and exports past audio fast.

Forked from `Echo`, now reworked and shipped as `Reverb`.

## ✨ Highlights

- Rolling buffer with disk-backed restore
- Material audio blob driven live from the recorder, with visualization work disabled when the capture UI is not visible
- Streamed WAV export without loading the whole history into memory
- In-app player, rename, delete, and move flows
- Hardware-aware sample format, rate, source, route, and channel selection

## Screenshots

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1-capture.png" width="230" alt="Capture screen with One-shot and Looping buffers" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2-library.png" width="230" alt="Library with saved recordings and inline waveform playback" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3-trim.png" width="230" alt="Non-destructive trimming with waveform selection controls" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4-settings.png" width="230" alt="Retention limits and audio settings" />
</p>

*Screenshots use sample recordings.*

## 📦 App

- Package: `app.smallthingz.reverb`
- Module: `app`

## 🛠️ Build

```bash
python3 scripts/bootstrap-native-ui.py
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The native UI library is a separate Git repository pinned as `native-ui`.
The bootstrap command verifies and restores it from the committed offline bundle;
it does not fetch from a network or overwrite local library changes. Use the same
step after a plain clone, in a fresh CI checkout or in a source archive. The bundled
locator uses this bootstrap flow, not `git clone --recurse-submodules` or generic
`git submodule update --init`. See [Native UI](docs/native-ui.md).
