/*
 * Interaction/animation selection adapted from AndroidX Material3 1.4.0 Button.kt and
 * internal/Elevation.kt, Copyright 2021 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.interaction.view.FilledButtonElevation
import smallthingz.ui.interaction.view.configureButtonShadowHost

/** Filled Continue gets the controller; released TextButton/Back has no elevation animation. */
internal fun createReverbOnboardingButtonElevation(
    host: View,
    filled: Boolean,
    initialEnabled: Boolean,
    setShadowElevationPx: (Float) -> Unit,
): FilledButtonElevation? {
    if (filled) return FilledButtonElevation(host, initialEnabled, setShadowElevationPx)
    configureButtonShadowHost(host)
    setShadowElevationPx(0f)
    return null
}
