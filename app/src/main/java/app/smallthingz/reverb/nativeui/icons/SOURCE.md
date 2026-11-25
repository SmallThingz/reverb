# Reverb icon compatibility adapters

App `ReverbIcon`, `ReverbIconPaths` and `ReverbIconDrawable` are typealiases into
native-ui. The recommended common API is `smallthingz.ui.icons.MaterialRoundedIcon`
(AndroidX Material Rounded 1.7.8); see `native-ui/docs/icons.md`.

Icon geometry, licenses and provenance are owned by native-ui under
`src/main/resource-provenance/reverb-icons/`. This directory's SOURCE.json and
license remain historical extraction evidence, not an app-owned drawing source.
App platform icon resources are aliases to library assets. Launcher/splash resources
continue to be generated from the library's canonical brand source.
