# Native sheet resources

These resources retain the exact values from AndroidX Compose Material3 1.4.0's published Android AAR. Only resource identifiers were changed. The source uses the Android platform `Theme.DeviceDefault.Dialog`; no Material runtime or widget theme is needed.

Copied scope: the two floating-sheet window/dialog styles, their API30-qualified cutout integer, and the three sheet accessibility strings with every published locale variant. The AndroidX Apache-2.0 license is retained alongside this file. The rest of Material3 is not copied.

Source artifact: `androidx.compose.material3:material3-android:1.4.0`, cached artifact SHA256 `3a37e8b36df3822fe1e6059f0f9fafda8800388860477624ac1b9422c418a36e`.

Generated destination: `app/src/main/res/values*/native_sheet_resources.xml`. Source-to-destination verification reverses only declared resource-name replacements and compares each original XML element, including text, attributes and item order. Runtime resource selection and actual dialog rendering still need the normal build/device qualification.
