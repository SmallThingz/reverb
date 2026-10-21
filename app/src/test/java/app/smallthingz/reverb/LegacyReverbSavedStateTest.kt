package app.smallthingz.reverb

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.smallthingz.reverb.nativeui.NativeLibrarySavedScrollPosition
import app.smallthingz.reverb.nativeui.NativeLibraryScrollRestoration
import app.smallthingz.reverb.nativeui.NativeListSavedPosition
import app.smallthingz.reverb.nativeui.SettingsListSavedPosition
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LegacyReverbSavedStateTest {
    private val mainValues: List<Any?> get() = listOf(
        mutableStateOf(true), mutableIntStateOf(1), mutableStateOf(true),
        mutableStateOf(false), mutableStateOf(true),
    )
    private val directoryValues = listOf(true, true, false, "")
    private val directoryKey = "f3cb8ed6-7dd1-47d8-ab62-3f0bd8ee4a82"

    private fun decode(vararg groups: Pair<String, Any?>) =
        decodeReleased012SavedUi(mapOf(*groups)::get, null, false, null, false)

    @Test fun releasedStateWrappersRestoreAllFiveFieldsWithoutNormalizingPanels() {
        val result = decode(Released012SavedUiKeys.MAIN to mainValues)
        assertEquals(NativeMainSavedNavigation(true, 1, true, false, true), result.main?.navigation)
        assertTrue(result.remainingGroups.containsKey(Released012SavedUiKeys.MAIN))
        assertNull(result.remainingGroups[Released012SavedUiKeys.MAIN])
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun unrelatedKeysAndBarePrimitivesAreNeverInferredAsReleasedStateWrappers() {
        val input = mapOf(
            "unknown" to mainValues,
            Released012SavedUiKeys.MAIN to listOf(true, 1, true, false, true),
        )
        val reads = ArrayList<String>()
        val result = decodeReleased012SavedUi({ reads += it; input[it] }, null, false, null, false)
        assertFalse(reads.contains("unknown"))
        assertNull(result.main)
        assertEquals(5, result.rejections.size)
        assertFalse(result.remainingGroups.containsKey(Released012SavedUiKeys.MAIN))
    }

    @Test fun nullTileAndDraftSlotsDoNotShiftDirectoryIdentity() {
        val original = listOf(listOf(7, 31), directoryValues, null, null, directoryKey)
        val result = decode(Released012SavedUiKeys.SETTINGS to original)
        assertEquals(SettingsListSavedPosition(7, 31), result.main?.settingsPosition)
        assertEquals(directoryValues, result.main?.directoryPicker)
        assertEquals(directoryKey, result.directoryResultKey)
        assertNull(result.main?.tileEditor)
        assertNull(result.main?.settingsDraft)
        assertNull(result.remainingGroups[Released012SavedUiKeys.SETTINGS])
        assertEquals(directoryKey, original[4])
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun nativeFormatsWinWithoutReadingTheirLegacyGroups() {
        val main = NativeMainScreenSavedState(NativeMainSavedNavigation(showLibrary = true))
        val viewport = NativeOnboardingViewportSavedState(2, .25f, listOf(10, 20, 30))
        val result = decodeReleased012SavedUi(
            read = { error("Native formats must not read legacy fields") },
            nativeMain = main, nativeMainPresent = true,
            nativeDirectoryResultKey = "native-key", nativeDirectoryKeyPresent = true,
            nativeOnboardingDraftPresent = true,
            nativeOnboardingViewport = viewport, nativeOnboardingViewportPresent = true,
        )
        assertSame(main, result.main)
        assertEquals("native-key", result.directoryResultKey)
        assertSame(viewport, result.onboardingViewport)
        assertNull(result.onboardingDraft)
        assertTrue(result.remainingGroups.isEmpty())
    }

    @Test fun differentNativeDirectoryIdentityCannotAdoptOldOutstandingRequest() {
        val original = listOf(listOf(2, 5), directoryValues, null, null, directoryKey)
        val result = decodeReleased012SavedUi(
            mapOf(Released012SavedUiKeys.SETTINGS to original)::get,
            null, false, "native-key", true,
        )
        assertEquals("native-key", result.directoryResultKey)
        assertNull(result.main?.directoryPicker)
        assertEquals(SettingsListSavedPosition(2, 5), result.main?.settingsPosition)
        assertEquals(listOf(null, directoryValues, null, null, directoryKey),
            result.remainingGroups[Released012SavedUiKeys.SETTINGS])
    }

    @Test fun wrongProviderCountIsRetainedRatherThanPartiallyReinterpreted() {
        val result = decode(Released012SavedUiKeys.MAIN to mainValues.dropLast(1))
        assertNull(result.main)
        assertEquals(1, result.rejections.size)
        assertNull(result.rejections.single().slot)
        assertFalse(result.remainingGroups.containsKey(Released012SavedUiKeys.MAIN))
    }

    @Test fun wrongTypedSlotIsIsolatedAndPreservedInItsOriginalPosition() {
        val wrong = mutableStateOf("not an Int")
        val values = mainValues.toMutableList().apply { this[1] = wrong }
        val result = decode(Released012SavedUiKeys.MAIN to values)
        assertEquals(NativeMainSavedNavigation(true, -1, true, false, true), result.main?.navigation)
        assertEquals(1, result.rejections.single().slot)
        assertEquals(listOf(null, wrong, null, null, null),
            result.remainingGroups[Released012SavedUiKeys.MAIN])
        assertSame(wrong, values[1])
    }

    @Test fun rawSettingsDraftAndTileDraftRemainExactUntilExistingOwnersHydrate() {
        val draft = SettingsUiDraft.normalized(SettingsSnapshot(
            oneShotRetentionSizeBytes = 9_007_199_254_740_993L,
        )).copy(oneShotTimeText = "0:", oneShotSizeText = "-", loopingTimeText = "",
            loopingSizeText = " 12,345678901 ")
        val tile = QuickSettingsTileEditorTarget().saveDraft()
        val originalDraft = draft.save()
        val result = decode(Released012SavedUiKeys.SETTINGS to
            listOf(listOf(4, 9), directoryValues, tile, originalDraft, directoryKey))
        assertEquals(originalDraft, result.main?.settingsDraft)
        assertEquals(tile, result.main?.tileEditor)
        val pending = SettingsDraftRestoration.restore(requireNotNull(result.main?.settingsDraft))
        val beforeHydration = pending.save(true, false, false, SettingsSnapshot()) {
            error("Restoration must not replace a pending draft with placeholders")
        }
        assertEquals(originalDraft, beforeHydration)
        val hydrated = pending.hydrate(0, 0) { SettingsUiDraft.normalized(SettingsSnapshot()) }
        assertEquals(draft, hydrated?.displayed)
    }

    @Test fun releasedVersionOneDraftStillUsesItsExistingSaver() {
        val values = SettingsUiDraft.normalized(SettingsSnapshot()).save().dropLast(1).toMutableList()
        values[0] = 1
        val result = decode(Released012SavedUiKeys.SETTINGS to
            listOf(listOf(0, 0), directoryValues, null, values, directoryKey))
        assertEquals(values, result.main?.settingsDraft)
        assertNotNull(SettingsUiDraft.restore(requireNotNull(result.main?.settingsDraft)))
    }

    @Test fun retiredDirectoryResultRemainsRetiredAfterMigration() {
        val retired = listOf(false, true, true, "")
        val result = decode(Released012SavedUiKeys.SETTINGS to
            listOf(listOf(0, 0), retired, null, null, directoryKey))
        val state = SettingsDirectoryPickerState.restore(requireNotNull(result.main?.directoryPicker))
        assertFalse(state.acceptsResult)
        state.receiveResult("content://example/tree/ignored")
        state.setPanelActive(true)
        assertNull(state.takePendingResult(hydrated = true))
        assertEquals(directoryKey, result.directoryResultKey)
    }

    @Test fun onboardingFlagsPagerAndThreeScrollSlotsUseTheirExactSchemas() {
        val flags = listOf(false, false, true, false, false, true)
        val result = decode(
            Released012SavedUiKeys.ONBOARDING to listOf(listOf(2, -.25f, 3)) + flags.map { mutableStateOf(it) },
            Released012SavedUiKeys.ONBOARDING_SCROLL to listOf(11, 22, 33),
        )
        assertEquals(mapOf("one_shot" to false, "looping" to false, "original_compression" to true,
            "original_silence" to false, "compression" to false, "silence" to true), result.onboardingDraft)
        assertEquals(NativeOnboardingViewportSavedState(2, -.25f, listOf(11, 22, 33)),
            result.onboardingViewport)
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun savedPagerCountIsTypedButCurrentCallbackOwnsItsValue() {
        val result = decode(Released012SavedUiKeys.ONBOARDING to
            listOf(listOf(1, .5f, 17), null, null, null, null, null, null))
        assertEquals(NativeOnboardingViewportSavedState(1, .5f), result.onboardingViewport)
        assertNull(result.onboardingDraft)
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun nativeOnboardingDraftDoesNotPreventIndependentLegacyViewportRestore() {
        val values = listOf(listOf(1, 0f, 3)) + List(6) { mutableStateOf(false) }
        val result = decodeReleased012SavedUi(
            mapOf(Released012SavedUiKeys.ONBOARDING to values)::get,
            null, false, null, false, nativeOnboardingDraftPresent = true,
        )
        assertNull(result.onboardingDraft)
        assertEquals(NativeOnboardingViewportSavedState(1), result.onboardingViewport)
        assertEquals(listOf(null) + values.drop(1), result.remainingGroups[Released012SavedUiKeys.ONBOARDING])
    }

    @Test fun missingDirectoryIdentityDoesNotRestoreAnOrphanedAwaitingTransaction() {
        val result = decode(Released012SavedUiKeys.SETTINGS to
            listOf(listOf(0, 0), directoryValues, null, null, null))
        assertNull(result.main?.directoryPicker)
        assertNull(result.directoryResultKey)
        assertEquals(listOf(null, directoryValues, null, null, null),
            result.remainingGroups[Released012SavedUiKeys.SETTINGS])
        assertEquals(1, result.rejections.single().slot)
    }

    @Test fun captureRestoresOnlyWrappedReleasedBufferEnum() {
        for (slot in ReverbService.BufferSlot.entries) {
            val result = decode(Released012SavedUiKeys.CAPTURE to listOf(mutableStateOf(slot)))
            assertEquals(slot.storageCode.toInt(), result.main?.selectedBufferCode)
            assertTrue(result.rejections.isEmpty())
            assertTrue(result.remainingGroups.containsKey(Released012SavedUiKeys.CAPTURE))
            assertNull(result.remainingGroups[Released012SavedUiKeys.CAPTURE])
        }
        for (wrong in listOf<Any>(ReverbService.BufferSlot.LOOPING,
            mutableStateOf(ReverbService.BufferSlot.LOOPING.storageCode.toInt()), mutableStateOf("LOOPING"))) {
            val result = decode(Released012SavedUiKeys.CAPTURE to listOf(wrong))
            assertNull(result.main)
            assertEquals(1, result.rejections.size)
            assertFalse(result.remainingGroups.containsKey(Released012SavedUiKeys.CAPTURE))
        }
    }

    @Test fun aboutReadsOnlyRegistryLinkedByExactReleasedUuidObject() {
        val uuid = UUID.fromString(directoryKey)
        val reads = ArrayList<UUID>()
        val result = decodeReleased012SavedUi(
            mapOf(Released012SavedUiKeys.ABOUT_ID to listOf(uuid))::get,
            null, false, null, false,
            readAboutScroll = { reads += it; listOf(157) },
        )
        assertEquals(listOf(uuid), reads)
        assertEquals(157, result.main?.aboutScrollPosition)
        assertTrue(result.remainingGroups.containsKey(Released012SavedUiKeys.ABOUT_ID))
        assertNull(result.remainingGroups[Released012SavedUiKeys.ABOUT_ID])
        assertTrue(result.remainingAboutGroups.containsKey(Released012SavedUiKeys.ABOUT_SCROLL))
        assertNull(result.remainingAboutGroups[Released012SavedUiKeys.ABOUT_SCROLL])
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun aboutNeverInfersUuidStringOrMissingScrollAsCurrentDialogState() {
        val stringResult = decodeReleased012SavedUi(
            mapOf(Released012SavedUiKeys.ABOUT_ID to listOf(directoryKey))::get,
            null, false, null, false, readAboutScroll = { error("String is not the released UUID saver") },
        )
        assertNull(stringResult.main)
        assertEquals(1, stringResult.rejections.size)
        assertTrue(stringResult.remainingGroups.isEmpty())

        val absentScroll = decodeReleased012SavedUi(
            mapOf(Released012SavedUiKeys.ABOUT_ID to listOf(UUID.fromString(directoryKey)))::get,
            null, false, null, false, readAboutScroll = { null },
        )
        assertNull(absentScroll.main)
        assertTrue(absentScroll.remainingGroups.isEmpty())
        assertTrue(absentScroll.remainingAboutGroups.isEmpty())
        assertTrue(absentScroll.rejections.isEmpty())
    }

    @Test fun wrongAboutScrollPayloadRetainsItsIdentityAndLinkedGroup() {
        val result = decodeReleased012SavedUi(
            mapOf(Released012SavedUiKeys.ABOUT_ID to listOf(UUID.fromString(directoryKey)))::get,
            null, false, null, false, readAboutScroll = { listOf(mutableIntStateOf(157)) },
        )
        assertNull(result.main)
        assertEquals(Released012SavedUiKeys.ABOUT_SCROLL, result.rejections.single().key)
        assertTrue(result.remainingGroups.isEmpty())
        assertTrue(result.remainingAboutGroups.isEmpty())
    }

    @Test fun nativeMainPrecedenceAlsoPreventsLinkedDialogRegistryConsumption() {
        val native = NativeMainScreenSavedState(aboutScrollPosition = 37, selectedBufferCode = 0)
        val result = decodeReleased012SavedUi(
            { null }, native, true, null, false,
            readAboutScroll = { error("Existing native Main owns About restoration") },
        )
        assertSame(native, result.main)
        assertTrue(result.remainingAboutGroups.isEmpty())
    }

    @Test fun incidentsPositionRemainsUnclampedUntilActualListMeasurement() {
        val result = decode(Released012SavedUiKeys.INCIDENTS to listOf(listOf(71, 37)))
        assertEquals(NativeListSavedPosition(71, 37), result.main?.incidentsPosition)
        assertTrue(result.rejections.isEmpty())
        assertNull(result.remainingGroups[Released012SavedUiKeys.INCIDENTS])
    }

    @Test fun libraryListAndEmptyBranchPositionsKeepTheirIndependentPendingLifetimes() {
        val result = decode(
            Released012SavedUiKeys.LIBRARY_LIST to listOf(listOf(43, 19)),
            Released012SavedUiKeys.LIBRARY_EMPTY to listOf(23),
        )
        val positions = NativeLibrarySavedScrollPosition(NativeListSavedPosition(43, 19), 23)
        assertEquals(positions, result.main?.libraryPosition)
        val owner = NativeLibraryScrollRestoration(result.main?.libraryPosition)
        assertEquals(positions, owner.save(null, null))
        assertEquals(positions.listPosition, owner.consumeList())
        assertEquals(NativeLibrarySavedScrollPosition(emptyScrollPosition = 23), owner.save(null, null))
        assertEquals(23, owner.consumeEmpty())
        assertEquals(NativeLibrarySavedScrollPosition(), owner.save(null, null))
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun libraryAbsenceIsNotZeroAndRejectedOldCandidateKeysAreNeverRead() {
        val input = mapOf<String, Any?>(
            "9bedvc37yysx" to listOf(listOf(9, 11)),
            "-jqtks9iin00k" to listOf(29),
        )
        val reads = ArrayList<String>()
        val absent = decodeReleased012SavedUi({ reads += it; input[it] }, null, false, null, false)
        assertNull(absent.main)
        assertFalse(reads.any { it in input })
        val zero = decode(Released012SavedUiKeys.LIBRARY_EMPTY to listOf(0))
        assertEquals(NativeLibrarySavedScrollPosition(emptyScrollPosition = 0), zero.main?.libraryPosition)
        assertTrue(zero.rejections.isEmpty())
    }

    @Test fun malformedLibraryListDoesNotDiscardSeparateValidEmptyPosition() {
        val result = decode(
            Released012SavedUiKeys.LIBRARY_LIST to listOf(listOf(4L, 19)),
            Released012SavedUiKeys.LIBRARY_EMPTY to listOf(23),
        )
        assertEquals(NativeLibrarySavedScrollPosition(emptyScrollPosition = 23), result.main?.libraryPosition)
        assertEquals(Released012SavedUiKeys.LIBRARY_LIST, result.rejections.single().key)
        assertFalse(result.remainingGroups.containsKey(Released012SavedUiKeys.LIBRARY_LIST))
        assertTrue(result.remainingGroups.containsKey(Released012SavedUiKeys.LIBRARY_EMPTY))
        assertNull(result.remainingGroups[Released012SavedUiKeys.LIBRARY_EMPTY])
    }
}
