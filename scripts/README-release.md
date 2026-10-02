# Release gate

Never build first and tag a later documentation commit. F-Droid MR !50231 records
that exact failure for 0.1.1, followed by a separate dependency-metadata rejection.

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
   python3 scripts/verify_release.py v0.1.2-rc1 \
     .tmp/release-0.1.2-rc1/reverb-0.1.2-rc1.apk \
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

`0.1.2-rc1` uses Android version code 3. Every later public APK, including final
`0.1.2`, must use a higher code; do not reuse code 3 for the stable release.

This release candidate is intended for F-Droid pickup as well as GitHub. Keep
`versionCode` and `versionName` as literals in `android.defaultConfig`: the current
F-Droid Gradle parser cannot resolve arbitrary variables. The signing guard reads
that same Android configuration instead of maintaining a second version constant.

Verify the current upstream `check_tags` and Gradle parser against the exact release
tag and the current central fdroiddata metadata. They must select `0.1.2-rc1` (3),
and its `Binaries` URL must resolve to `reverb-0.1.2-rc1.apk` on the matching tag.
Central metadata currently uses unfiltered `Tags` and `AutoUpdateMode: Version`, so
it will select this RC as the newest version; GitHub's prerelease label does not
create a separate F-Droid beta channel. Keep GitHub's latest stable release intact.

Changes to this repository's `.fdroid.yml` alone do not update central fdroiddata.
Local checker and independent rebuild success are preflight evidence, not proof
that the F-Droid server has completed its later build and publication cycle.
