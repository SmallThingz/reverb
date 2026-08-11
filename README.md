<h1>
  <img src="SaidIt/src/main/icon.svg" alt="TimeTravel app icon" width="52" valign="middle" />
  TimeTravel
</h1>

![android](https://img.shields.io/badge/android-28%2B-3ddc84?logo=android&logoColor=0f172a)
![kotlin](https://img.shields.io/badge/kotlin-2.3.0-7c3aed?logo=kotlin&logoColor=ffffff)
![material](https://img.shields.io/badge/ui-Material%203-2563eb)

Rolling audio buffer recorder for Android. Keeps recent audio alive, survives restarts, and exports past audio fast.

Forked from `Echo`, now reworked and shipped as `TimeTravel`.

## 📸 Screenshots

<p>
  <img src="docs/home.png" alt="Home screen" width="400" />
  <img src="docs/recordings.png" alt="Recordings screen" width="400" />
</p>

## ✨ Highlights

- Rolling buffer with disk-backed restore
- Streamed WAV export without loading the whole history into memory
- In-app player, rename, share, delete, and move flows
- Hardware-aware sample format, rate, source, route, and channel selection

## 📦 App

- Package: `app.smallthingz.timetravel`
- Module: `SaidIt`

## 🛠️ Build

```bash
./gradlew :SaidIt:assembleDebug
./gradlew :SaidIt:assembleRelease
```
