package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.NativeLibrarySavedScrollPosition
import app.smallthingz.reverb.nativeui.NativeListSavedPosition
import app.smallthingz.reverb.nativeui.SettingsListSavedPosition
import java.util.UUID

/** Fixed keys derived from the published 3e16f8d APK, not from the renamed QA renderer. */
internal object Released012SavedUiKeys {
    const val ROOT_REGISTRY = "SaveableStateRegistry:-1"
    const val MAIN = "12yje4gxvitua"
    const val SETTINGS = "-eyaoidljb495"
    const val ONBOARDING = "1gsfs1w2f79p1"
    const val ONBOARDING_SCROLL = "eyp6nl39l1ig"
    const val CAPTURE = "-x054okhhwefm"
    const val ABOUT_ID = "-eyaka9qcu04x"
    const val ABOUT_SCROLL = "-10hrid7x1ppih"
    const val INCIDENTS = "-h1ef73a0fn2b"
    const val LIBRARY_LIST = "9bedv091amy9"
    const val LIBRARY_EMPTY = "-jqtks9ihe1uc"
}

internal data class LegacySavedUiRejection(val key: String, val slot: Int?, val reason: String)

internal data class LegacyReverbSavedState(
    val main: NativeMainScreenSavedState?,
    val directoryResultKey: String?,
    val onboardingDraft: Map<String, Boolean>?,
    val onboardingViewport: NativeOnboardingViewportSavedState?,
    /** Only examined keys occur here; null removes an entirely consumed group. */
    val remainingGroups: Map<String, List<Any?>?>,
    val remainingAboutGroups: Map<String, List<Any?>?>,
    val rejections: List<LegacySavedUiRejection>,
)

/**
 * Reads only the released provider identities and ordered saver payloads. No jobs, permission
 * state, durable configuration, or registry request-code internals cross this UI-only boundary.
 */
internal fun decodeReleased012SavedUi(
    read: (String) -> Any?,
    nativeMain: NativeMainScreenSavedState?,
    nativeMainPresent: Boolean,
    nativeDirectoryResultKey: String?,
    nativeDirectoryKeyPresent: Boolean,
    nativeOnboardingDraftPresent: Boolean = false,
    nativeOnboardingViewport: NativeOnboardingViewportSavedState? = null,
    nativeOnboardingViewportPresent: Boolean = false,
    readAboutScroll: (UUID) -> Any? = { null },
): LegacyReverbSavedState {
    val rejected = ArrayList<LegacySavedUiRejection>()
    val remaining = LinkedHashMap<String, List<Any?>?>()
    val remainingAbout = LinkedHashMap<String, List<Any?>?>()
    var main = nativeMain
    var directoryKey = nativeDirectoryResultKey
    if (!nativeMainPresent) {
        var navigation = NativeMainSavedNavigation()
        var restoredAny = false
        legacyProviderGroup(Released012SavedUiKeys.MAIN, 5, read, rejected)?.let { group ->
            group.value(0) { released012StateValue(it) as? Boolean }?.let {
                navigation = navigation.copy(showSettings = it); restoredAny = true
            }
            group.value(1) { released012StateValue(it) as? Int }?.let {
                navigation = navigation.copy(settingsBufferTargetCode = it); restoredAny = true
            }
            group.value(2) { released012StateValue(it) as? Boolean }?.let {
                navigation = navigation.copy(showAboutDialog = it); restoredAny = true
            }
            group.value(3) { released012StateValue(it) as? Boolean }?.let {
                navigation = navigation.copy(showIncidents = it); restoredAny = true
            }
            group.value(4) { released012StateValue(it) as? Boolean }?.let {
                navigation = navigation.copy(showLibrary = it); restoredAny = true
            }
            group.saveRemainder(remaining)
        }
        var position: SettingsListSavedPosition? = null
        var picker: List<Any>? = null
        var tile: List<Any>? = null
        var draft: List<Any>? = null
        legacyProviderGroup(Released012SavedUiKeys.SETTINGS, 5, read, rejected)?.let { group ->
            position = group.value(0) { value ->
                val values = value as? List<*> ?: return@value null
                if (values.size == 2 && values[0] is Int && values[1] is Int)
                    SettingsListSavedPosition(values[0] as Int, values[1] as Int) else null
            }
            tile = group.value(2) { value ->
                primitiveSavedList(value)?.takeIf { QuickSettingsTileEditorTarget.restoreDraft(it) != null }
            }
            draft = group.value(3) { value ->
                primitiveSavedList(value)?.takeIf { SettingsUiDraft.restore(it) != null }
            }
            restoredAny = restoredAny || position != null || tile != null || draft != null

            // Registry registration can deliver immediately. The original transaction and its
            // original UUID must reach the owner together, before that lazy registration occurs.
            if (!nativeDirectoryKeyPresent) {
                val savedPicker = primitiveSavedList(group.peek(1))?.takeIf {
                    it.size == 4 && it[0] is Boolean && it[1] is Boolean &&
                        it[2] is Boolean && it[3] is String
                }
                val savedKey = group.peek(4) as? String
                if (savedPicker != null && savedKey != null) {
                    picker = savedPicker
                    directoryKey = savedKey
                    group.consume(1)
                    group.consume(4)
                    restoredAny = true
                } else {
                    group.rejectPresent(1, "Directory transaction and launcher identity must restore together")
                    group.rejectPresent(4, "Directory transaction and launcher identity must restore together")
                }
            }
            group.saveRemainder(remaining)
        }
        var selectedBuffer: Int? = null
        legacyProviderGroup(Released012SavedUiKeys.CAPTURE, 1, read, rejected)?.let { group ->
            selectedBuffer = group.value(0) { released012StateValue(it) as? ReverbService.BufferSlot }
                ?.storageCode?.toInt()
            restoredAny = restoredAny || selectedBuffer != null
            group.saveRemainder(remaining)
        }
        var incidentsPosition: NativeListSavedPosition? = null
        legacyProviderGroup(Released012SavedUiKeys.INCIDENTS, 1, read, rejected)?.let { group ->
            incidentsPosition = group.value(0) { value ->
                val values = value as? List<*> ?: return@value null
                if (values.size == 2 && values[0] is Int && values[1] is Int)
                    NativeListSavedPosition(values[0] as Int, values[1] as Int) else null
            }
            restoredAny = restoredAny || incidentsPosition != null
            group.saveRemainder(remaining)
        }
        var libraryListPosition: NativeListSavedPosition? = null
        var libraryEmptyPosition: Int? = null
        legacyProviderGroup(Released012SavedUiKeys.LIBRARY_LIST, 1, read, rejected)?.let { group ->
            libraryListPosition = group.value(0) { value ->
                val values = value as? List<*> ?: return@value null
                if (values.size == 2 && values[0] is Int && values[1] is Int)
                    NativeListSavedPosition(values[0] as Int, values[1] as Int) else null
            }
            group.saveRemainder(remaining)
        }
        legacyProviderGroup(Released012SavedUiKeys.LIBRARY_EMPTY, 1, read, rejected)?.let { group ->
            libraryEmptyPosition = group.value(0) { it as? Int }
            group.saveRemainder(remaining)
        }
        val libraryPosition = if (libraryListPosition != null || libraryEmptyPosition != null)
            NativeLibrarySavedScrollPosition(libraryListPosition, libraryEmptyPosition) else null
        restoredAny = restoredAny || libraryPosition != null
        var aboutScroll: Int? = null
        legacyProviderGroup(Released012SavedUiKeys.ABOUT_ID, 1, read, rejected)?.let { identity ->
            val uuid = identity.peek(0) as? UUID
            if (uuid == null) identity.rejectPresent(0, "Expected the released Dialog UUID object")
            else legacyProviderGroup(Released012SavedUiKeys.ABOUT_SCROLL, 1,
                { readAboutScroll(uuid) }, rejected)?.let { scroll ->
                aboutScroll = scroll.value(0) { it as? Int }
                if (aboutScroll != null) {
                    identity.consume(0)
                    restoredAny = true
                }
                scroll.saveRemainder(remainingAbout)
            }
            identity.saveRemainder(remaining)
        }
        if (restoredAny) main = NativeMainScreenSavedState(
            navigation = navigation,
            selectedBufferCode = selectedBuffer,
            aboutScrollPosition = aboutScroll,
            settingsDraft = draft,
            directoryPicker = picker,
            tileEditor = tile,
            settingsPosition = position,
            libraryPosition = libraryPosition,
            incidentsPosition = incidentsPosition,
        )
    }
    var onboardingDraft: Map<String, Boolean>? = null
    var onboardingViewport = nativeOnboardingViewport
    var pager: Pair<Int, Float>? = null
    if (!nativeOnboardingDraftPresent || !nativeOnboardingViewportPresent) {
        legacyProviderGroup(Released012SavedUiKeys.ONBOARDING, 7, read, rejected)?.let { group ->
            if (!nativeOnboardingViewportPresent) {
                pager = group.value(0) { raw ->
                    val values = raw as? List<*> ?: return@value null
                    if (values.size != 3 || values[0] !is Int || values[1] !is Float || values[2] !is Int)
                        return@value null
                    val page = values[0] as Int
                    val offset = values[1] as Float
                    // The source replaces the saved page-count callback with the current one.
                    if (page in 0..2 && offset in -.5f.. .5f) page to offset else null
                }
            }
            if (!nativeOnboardingDraftPresent) {
                val fields = arrayOf("one_shot", "looping", "original_compression", "original_silence",
                    "compression", "silence")
                val values = LinkedHashMap<String, Boolean>()
                fields.forEachIndexed { index, field ->
                    group.value(index + 1) { released012StateValue(it) as? Boolean }?.let { values[field] = it }
                }
                onboardingDraft = values.takeUnless { it.isEmpty() }
            }
            group.saveRemainder(remaining)
        }
    }
    if (!nativeOnboardingViewportPresent) {
        var scroll: List<Int>? = null
        legacyProviderGroup(Released012SavedUiKeys.ONBOARDING_SCROLL, 3, read, rejected)?.let { group ->
            val positions = (0..2).map { index -> group.value(index) { it as? Int } }
            if (positions.any { it != null }) scroll = positions.map { it ?: 0 }
            group.saveRemainder(remaining)
        }
        if (pager != null || scroll != null) onboardingViewport = NativeOnboardingViewportSavedState(
            pager?.first ?: 0, pager?.second ?: 0f, scroll ?: listOf(0, 0, 0))
    }
    return LegacyReverbSavedState(main, directoryKey, onboardingDraft, onboardingViewport, remaining, remainingAbout, rejected)
}

private fun primitiveSavedList(value: Any?): List<Any>? {
    val list = value as? List<*> ?: return null
    if (list.any { it !is Int && it !is Long && it !is Boolean && it !is String }) return null
    @Suppress("UNCHECKED_CAST")
    return list as List<Any>
}

/** Null placeholders are provider positions, never a reason to compact the list. */
private class LegacyProviderGroup(
    private val key: String,
    private val values: List<*>,
    private val rejected: MutableList<LegacySavedUiRejection>,
) {
    private val remaining = values.toMutableList()
    private var consumed = false

    fun peek(slot: Int): Any? = values[slot]

    fun consume(slot: Int) {
        remaining[slot] = null
        consumed = true
    }

    fun <T : Any> value(slot: Int, decode: (Any) -> T?): T? {
        val raw = values[slot]
        if (raw == null) {
            consume(slot)
            return null
        }
        val result = decode(raw)
        if (result != null) consume(slot)
        else rejected += LegacySavedUiRejection(key, slot, "Unexpected released saver payload")
        return result
    }

    fun rejectPresent(slot: Int, reason: String) {
        if (values[slot] == null) consume(slot)
        else rejected += LegacySavedUiRejection(key, slot, reason)
    }

    fun saveRemainder(destination: MutableMap<String, List<Any?>?>) {
        if (consumed) destination[key] = remaining.takeUnless { it.all { value -> value == null } }
    }
}

private fun legacyProviderGroup(
    key: String,
    size: Int,
    read: (String) -> Any?,
    rejected: MutableList<LegacySavedUiRejection>,
): LegacyProviderGroup? {
    val raw = read(key) ?: return null
    val values = raw as? List<*>
    if (values == null || values.size != size) {
        rejected += LegacySavedUiRejection(key, null, "Unexpected released provider count or list")
        return null
    }
    return LegacyProviderGroup(key, values, rejected)
}
