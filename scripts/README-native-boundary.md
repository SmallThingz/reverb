# Production native boundary

Run only in the central contained build lane, against the immutable source used to build the normal APK:

```sh
./gradlew -I scripts/native-boundary.gradle :app:writeNativeProductionDependencies -PnativeDependencyReport=/absolute/evidence/production-dependencies.json
python3 -B scripts/verify_native_boundary.py --dependencies /absolute/evidence/production-dependencies.json --apk /absolute/normal.apk --legacy-compatibility-manifest scripts/native-legacy-compatibility.json --output /absolute/evidence/native-boundary.json
```

The Gradle evidence resolves actual debug/release compile/runtime graphs; it does not inspect declared dependency strings. The Python gate validates every DEX checksum/signature and reads actual referenced/defined type tables, including multidex. Old class-name string constants do not count as dependencies. Unknown input/container formats, missing graphs, unresolved dependencies and malformed tables fail. The two exact data-only Parcelable readers have a bounded source-hash exception documented in `docs/native-parity/legacy-saved-state.md`.

Retained released renderer code, Compose compiler and libraries belong only to explicitly requested reliability tasks (for example `-I scripts/reliability.gradle :app:testReliabilityUnitTest`). Normal builds should omit the reliability init script. Material icon source packs remain in `nativeIconSources`, a non-transitive reference-only configuration, and the native icon paths/API are unchanged.

Host gate regressions: `python3 -B -m unittest discover -s scripts -p test_verify_native_boundary.py -v`. Actual normal40 and QA41 artifacts are retained negative controls; neither satisfies the new dependency boundary. A successful graph/type gate does not establish visual parity or runtime behavior.
