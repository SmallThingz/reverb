# Released saved-state compatibility

Normal Reverb has no Compose dependency or UI/runtime framework. Two app-owned data-only Parcelable readers retain the exact names serialized by released0.1.2: `androidx.compose.runtime.ParcelableSnapshotMutableState` and `ParcelableSnapshotMutableIntState`. Their only additional classes are named `LegacyCreator` implementations. The two Java sources total3196bytes before compilation. They have no mutable state, observers, snapshot engine, UI functions, Compose superclass or Compose interface.

The names are an Android wire-format requirement: `Parcel.readParcelableCreatorInternal` calls `Class.forName`; ART checks that the loaded class descriptor exactly matches the requested name. A ClassLoader alias to a renamed native class fails (`android-16.0.0_r1` Parcel.java5323; class_linker.cc3416,3460–3470). No Parcel byte rewriting or general custom parser is used.

The pinned AndroidX runtime1.11.2 source archive SHA256 is `211c6dc3f74166fbb5438264408e59b80c55544f201e04625f27d2cbd2dc462a`. Its generic wire format is `writeValue(value)` then one policy Int (0,1,2); the primitive Int format is one Int. Readers preserve those fields and reject unsupported policy codes. They preserve unconsumed legacy values when the existing fixed-key migration resaves its remainder. Future native saved UI uses its existing Bundle schema and does not create these wrappers.

The fixed released keys cover navigation flags, selected buffer, onboarding draft flags and associated primitive Settings/scroll drafts. They never restore capture jobs, permission state or durable recording settings. The published APK's two Parcelable classes/CREATOR fields and BufferSlot enum identity were independently inspected before this replacement.

The readers compile only from `src/nativeCompatibility` into debug/release. The reliability variant retains the actual released Compose runtime and uses its own `released012StateValue` adapter; it must never compile both implementations. The decoder host test is debug-specific. Actual Android Parcel roundtrip qualification remains a separate required runtime check; source format equality and host tests are not claimed as that evidence.

The production boundary gate rejects every Compose/Material resolved dependency. Its APK DEX gate permits only these four exact class descriptors, only when both Java source hashes match `scripts/native-legacy-compatibility.json`; every other defined or referenced Compose/Material type fails. The exception is never a namespace-prefix allowance.
