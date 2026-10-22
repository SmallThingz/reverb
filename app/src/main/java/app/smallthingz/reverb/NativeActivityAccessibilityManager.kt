/*
 * Adapted from AndroidX ui 1.11.2 AndroidAccessibilityManager.android.kt.
 * Copyright 2021 The Android Open Source Project. Licensed under Apache License 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import app.smallthingz.nativeui.platform.AccessibilityManager

/** The source timeout adapter only; reads current user services, never enables or changes them. */
internal class NativeActivityAccessibilityManager(context: Context) : AccessibilityManager {
    private val platform = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as
        android.view.accessibility.AccessibilityManager

    override fun calculateRecommendedTimeoutMillis(
        originalTimeoutMillis: Long,
        containsIcons: Boolean,
        containsText: Boolean,
        containsControls: Boolean,
    ): Long {
        if (originalTimeoutMillis >= Int.MAX_VALUE) return originalTimeoutMillis
        var flags = 0
        if (containsIcons) flags = flags or 1
        if (containsText) flags = flags or 2
        if (containsControls) flags = flags or 4
        return if (Build.VERSION.SDK_INT >= 29) {
            val recommended = ActivityTimeoutApi29.recommended(platform, originalTimeoutMillis.toInt(), flags)
            if (recommended == Int.MAX_VALUE) Long.MAX_VALUE else recommended.toLong()
        } else if (containsControls && platform.isTouchExplorationEnabled) Long.MAX_VALUE
        else originalTimeoutMillis
    }
}

@RequiresApi(29)
private object ActivityTimeoutApi29 {
    fun recommended(manager: android.view.accessibility.AccessibilityManager, timeout: Int, flags: Int) =
        manager.getRecommendedTimeoutMillis(timeout, flags)
}
