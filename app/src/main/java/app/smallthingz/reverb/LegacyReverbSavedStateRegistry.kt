package app.smallthingz.reverb

import android.os.Bundle
import android.os.Parcelable
import androidx.savedstate.SavedStateRegistry

/** Uses the same public owner key as released DisposableSaveableStateRegistry. */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
internal fun restoreReleased012SavedUi(
    registry: SavedStateRegistry,
    nativeMain: NativeMainScreenSavedState?,
    nativeMainPresent: Boolean,
    nativeDirectoryResultKey: String?,
    nativeDirectoryKeyPresent: Boolean,
    nativeOnboardingDraftPresent: Boolean,
    nativeOnboardingViewport: NativeOnboardingViewportSavedState?,
    nativeOnboardingViewportPresent: Boolean,
): LegacyReverbSavedState {
    val root = registry.consumeRestoredStateForKey(Released012SavedUiKeys.ROOT_REGISTRY)
    var aboutRegistryKey: String? = null
    var aboutRoot: Bundle? = null
    val restored = decodeReleased012SavedUi(
        read = { key -> root?.get(key) },
        nativeMain = nativeMain,
        nativeMainPresent = nativeMainPresent,
        nativeDirectoryResultKey = nativeDirectoryResultKey,
        nativeDirectoryKeyPresent = nativeDirectoryKeyPresent,
        nativeOnboardingDraftPresent = nativeOnboardingDraftPresent,
        nativeOnboardingViewport = nativeOnboardingViewport,
        nativeOnboardingViewportPresent = nativeOnboardingViewportPresent,
        readAboutScroll = { uuid ->
            val key = "SaveableStateRegistry:Dialog:$uuid"
            aboutRegistryKey = key
            aboutRoot = registry.consumeRestoredStateForKey(key)
            aboutRoot?.get(Released012SavedUiKeys.ABOUT_SCROLL)
        },
    )
    if (root != null) {
        restored.remainingGroups.forEach { (key, values) ->
            if (values == null) root.remove(key)
            else root.putParcelableArrayList(key, ArrayList(values) as ArrayList<Parcelable?>)
        }
        // Preserve only unconsumed legacy data, including keys this fixed migration never reads.
        // Native Activity fields own imported values on every subsequent save.
        registry.registerSavedStateProvider(Released012SavedUiKeys.ROOT_REGISTRY) { root }
    }
    aboutRoot?.let { dialog ->
        restored.remainingAboutGroups.forEach { (key, values) ->
            if (values == null) dialog.remove(key)
            else dialog.putParcelableArrayList(key, ArrayList(values) as ArrayList<Parcelable?>)
        }
        registry.registerSavedStateProvider(requireNotNull(aboutRegistryKey)) { dialog }
    }
    return restored
}
