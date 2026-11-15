# Release gate

Never build first and tag a later documentation commit. F-Droid MR !50231 records
that exact failure for 0.1.1, followed by a separate dependency-metadata rejection.

Local candidate preparation does not authorize publication. Complete and verify
all intended source metadata before tagging and building, even when the candidate
will remain local. Local tag, APK and reproducibility checks can run without a
GitHub release; asset upload/download and central F-Droid publication checks below
require a separately authorized publication. An expected asset URL is not evidence
that the asset exists.

1. Finish version, changelog, documentation and source changes. Commit them all.
2. Create the matching `v<versionName>` tag on that clean commit; never move a
   published tag or replace an already published APK. Keep the application ID and
   existing developer signing certificate unchanged.
3. Build with the committed Gradle wrapper, JDK 21 and pinned Android toolchain.
   Pass signing credentials through process-local Gradle-property environment
   variables, not command-line arguments or tracked files. The Gradle signing
   guard requires a clean HEAD exactly matching the version tag.
4. Run unit tests and release lint, then verify the actual signed output:

   ```sh
   python3 scripts/test_verify_release.py
   python3 scripts/verify_release.py v0.1.3-rc4 \
     .maintenance/release-0.1.3-rc4-20261115/reverb-0.1.3-rc4.apk \
     --build-tools ../../.android/sdk/build-tools/37.0.0
   ```

   This checks the embedded Git revision, package/version, original certificate,
   v2 signature, ZIP integrity, 16 KiB alignment, absence of dependency metadata,
   and absence of debug/network permissions. It does not rewrite the APK.
5. Make a separate clean checkout at the same tag, build an unsigned release with
   build caches disabled, then use `apksigcopier copy` to transfer the verified
   developer signature onto that independent build. Require byte-for-byte equality
   with the release APK and verify its signature. Keep this evidence outside Git
   so recording build results cannot change the tagged revision.
6. Upload the APK and its SHA-256 alongside release notes using `--verify-tag`.
   Prereleases also require `--prerelease --latest=false`. Re-download the
   published assets and compare their bytes and checksums before reporting success.

## Prerelease ordering and F-Droid

`0.1.2-rc1` uses Android version code 3. Stable `0.1.2` uses code 4; every later
public APK must use a higher code. Never reuse a published version code.
The source-only `0.1.3-rc1` was withdrawn before APK publication; `0.1.3-rc2`
advances the app version code to 6. `0.1.3-rc3` uses code 7; `0.1.3-rc4` uses code 8.

Stable releases and release candidates are intended for F-Droid pickup as well as GitHub. Keep
`versionCode` and `versionName` as literals in `android.defaultConfig`: the current
F-Droid Gradle parser cannot resolve arbitrary variables. The signing guard reads
that same Android configuration instead of maintaining a second version constant.

For the local candidate, verify the exact local tag and literal Gradle version
select `0.1.3-rc4` (8), with `reverb-0.1.3-rc4.apk` as the expected asset on
`v0.1.3-rc4`. Local `.fdroid.yml` candidate metadata does not make that asset
available or change the currently published release.

After publication is separately authorized, verify the current upstream
`check_tags` and Gradle parser against the published tag and current central
fdroiddata metadata, then require the `Binaries` URL to resolve to that exact APK.
Check central `UpdateCheckMode` and `AutoUpdateMode` rather than assuming GitHub's
prerelease label creates a separate F-Droid beta channel. Future prereleases must
leave GitHub's latest stable release intact.

Changes to this repository's `.fdroid.yml` alone do not update central fdroiddata.
Local checker and independent rebuild success are preflight evidence, not proof
that the F-Droid server has completed its later build and publication cycle.
