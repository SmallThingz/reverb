@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import app.smallthingz.reverb.nativeui.NativeLibrarySavedScrollPosition
import app.smallthingz.reverb.nativeui.NativeListSavedPosition
import app.smallthingz.reverb.nativeui.SettingsListSavedPosition
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Real Android Parcel/public registry check with synthetic exact released-format values only. */
internal fun verifyNativeLegacySavedStateParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-legacy-state-parity-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("passed", false).put("cases", cases)
        .put("format_baseline_apk", "057b909d0b37c4e1ab24c3aaa5b95dedbbfd197dea8ad6a037ec6f2e2efc59cd")
        .put("scope", "Synthetic released-format Bundle/State Parcel roundtrip and actual public registry adapter; no Activity launch")
        .put("limits", "Not an old-binary task restoration, production Activity, ActivityResult delivery, UI, or process-death test")
    var failure: Throwable? = null
    fun runCase(name: String, block: () -> Unit) {
        val entry = JSONObject().put("name", name).put("passed", false)
        cases.put(entry)
        try {
            nativeParityOnMain(instrumentation, block)
            entry.put("passed", true)
        } catch (error: Throwable) {
            entry.put("failure", error.stackTraceToString())
            throw error
        }
    }
    try {
        runCase("typed-values-parcel-import-and-residual-resave", ::verifyLegacyTypedRegistryRoundtrip)
        runCase("malformed-slot-and-count-preserve-original-positions", ::verifyLegacyMalformedRegistryRoundtrip)
        runCase("native-value-precedence-preserves-legacy-registries") { verifyLegacyNativePrecedence(false) }
        runCase("native-present-null-precedence-preserves-legacy-registries") { verifyLegacyNativePrecedence(true) }
        runCase("directory-identity-cannot-adopt-competing-transaction", ::verifyLegacyDirectoryRegistryRoundtrip)
        runCase("null-draft-slots-and-missing-about-scroll-remain-distinct", ::verifyLegacyAbsentRegistryRoundtrip)
        check(cases.length() == 6)
    } catch (error: Throwable) {
        failure = error
    }
    result.put("passed", failure == null).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
    try {
        File(directory, "result.json").writeText(result.toString(2))
    } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        result.put("passed", false).put("failure", failure!!.stackTraceToString())
        try { File(directory, "result.json").writeText(result.toString(2)) }
        catch (retry: Throwable) { if (failure !== retry) failure!!.addSuppressed(retry) }
    }
    report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    failure?.let { throw it }
    report.appendLine("Native legacy state registry: 6 bounded Android Parcel/public registry cases passed; synthetic payloads only")
}

private val legacyProbeUuid = UUID.fromString("b5eb765d-596d-4de5-834a-bc00e7060951")
private const val legacyProbeDirectoryKey = "87baf424-5233-4ed6-838c-868c63111011"
private val legacyProbeDialogKey = "SaveableStateRegistry:Dialog:$legacyProbeUuid"
private const val legacyProbeOtherRegistry = "qa-unrelated-owner"
private const val legacyProbeUnknown = "qa-unknown-provider"
private val legacyProbePicker = listOf(true, true, false, "")

private class LegacyProbeRegistry(restored: Bundle?) : SavedStateRegistryOwner, AutoCloseable {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    init {
        controller.performAttach()
        controller.performRestore(restored)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    fun save(): Bundle = Bundle().also(controller::performSave)
    override fun close() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
}

/** Identical public serializer shape to released DisposableSaveableStateRegistry. */
private fun Bundle.legacyProviders(key: String, vararg values: Any?) {
    putParcelableArrayList(key, ArrayList(values.toList()) as ArrayList<Parcelable?>)
}

private fun legacyParcel(bundle: Bundle): Bundle {
    val encoded = Parcel.obtain()
    val decoded = Parcel.obtain()
    try {
        encoded.writeBundle(bundle)
        val bytes = encoded.marshall()
        decoded.unmarshall(bytes, 0, bytes.size)
        decoded.setDataPosition(0)
        return checkNotNull(decoded.readBundle(NativeMainScreenSavedState::class.java.classLoader))
    } finally {
        decoded.recycle()
        encoded.recycle()
    }
}

/** RegistryController supplies platform nesting; the fixture never guesses internal Bundle keys. */
private fun legacyRegistrySeed(root: Bundle, about: Bundle? = null): Bundle =
    LegacyProbeRegistry(null).use { source ->
        source.savedStateRegistry.registerSavedStateProvider(Released012SavedUiKeys.ROOT_REGISTRY) { root }
        about?.let { source.savedStateRegistry.registerSavedStateProvider(legacyProbeDialogKey) { it } }
        source.savedStateRegistry.registerSavedStateProvider(legacyProbeOtherRegistry) {
            Bundle().apply { putString("sentinel", "unrelated registry retained") }
        }
        legacyParcel(source.save())
    }

private fun LegacyProbeRegistry.importLegacy(
    nativeMain: NativeMainScreenSavedState? = null,
    nativeMainPresent: Boolean = false,
    directoryKey: String? = null,
    directoryPresent: Boolean = false,
    onboardingPresent: Boolean = false,
    viewport: NativeOnboardingViewportSavedState? = null,
): LegacyReverbSavedState = restoreReleased012SavedUi(
    savedStateRegistry, nativeMain, nativeMainPresent, directoryKey, directoryPresent,
    onboardingPresent, viewport, onboardingPresent,
)

private fun legacyMainProviders() = arrayOf<Any?>(mutableStateOf(true), mutableIntStateOf(1),
    mutableStateOf(true), mutableStateOf(false), mutableStateOf(true))

private fun Bundle.providers(key: String): List<*> = checkNotNull(get(key) as? List<*>)
private fun SavedStateRegistry.restoredRoot(): Bundle =
    checkNotNull(consumeRestoredStateForKey(Released012SavedUiKeys.ROOT_REGISTRY))
private fun SavedStateRegistry.checkUnrelated() {
    check(consumeRestoredStateForKey(legacyProbeOtherRegistry)?.getString("sentinel") == "unrelated registry retained")
}

private fun verifyLegacyTypedRegistryRoundtrip() {
    val draft = SettingsUiDraft.normalized(SettingsSnapshot(oneShotRetentionSizeBytes = 9_007_199_254_740_993L))
        .copy(oneShotTimeText = "0:", oneShotSizeText = "-", loopingTimeText = "", loopingSizeText = " 12,345678901 ").save()
    val tile = QuickSettingsTileEditorTarget().saveDraft()
    val flags = listOf(false, true, true, false, false, true)
    val root = Bundle().apply {
        legacyProviders(Released012SavedUiKeys.MAIN, *legacyMainProviders())
        legacyProviders(Released012SavedUiKeys.SETTINGS, listOf(7, 31), legacyProbePicker, tile, draft, legacyProbeDirectoryKey)
        legacyProviders(Released012SavedUiKeys.CAPTURE, mutableStateOf(ReverbService.BufferSlot.LOOPING))
        legacyProviders(Released012SavedUiKeys.INCIDENTS, listOf(71, 37))
        legacyProviders(Released012SavedUiKeys.LIBRARY_LIST, listOf(43, 19))
        legacyProviders(Released012SavedUiKeys.LIBRARY_EMPTY, 23)
        legacyProviders(Released012SavedUiKeys.ABOUT_ID, legacyProbeUuid)
        legacyProviders(Released012SavedUiKeys.ONBOARDING, listOf(2, -.25f, 3), *flags.map { mutableStateOf(it) }.toTypedArray())
        legacyProviders(Released012SavedUiKeys.ONBOARDING_SCROLL, 11, 22, 33)
        legacyProviders(legacyProbeUnknown, "keep", null, 17)
    }
    val about = Bundle().apply {
        legacyProviders(Released012SavedUiKeys.ABOUT_SCROLL, 157)
        legacyProviders(legacyProbeUnknown, "dialog keep", null)
    }
    val encoded = legacyRegistrySeed(root, about)
    // Actual Android State/UUID/nested saver objects survive Parcel with their expected types.
    LegacyProbeRegistry(legacyParcel(encoded)).use { inspection ->
        val restored = inspection.savedStateRegistry.restoredRoot()
        check(restored.providers(Released012SavedUiKeys.MAIN)[0] is State<*>)
        check((restored.providers(Released012SavedUiKeys.MAIN)[1] as State<*>).value == 1)
        check(restored.providers(Released012SavedUiKeys.ABOUT_ID)[0] == legacyProbeUuid)
        check((restored.providers(Released012SavedUiKeys.CAPTURE)[0] as State<*>).value == ReverbService.BufferSlot.LOOPING)
    }
    val resaved = LegacyProbeRegistry(encoded).use { target ->
        val imported = target.importLegacy()
        val expected = NativeMainScreenSavedState(
            navigation = NativeMainSavedNavigation(true, 1, true, false, true),
            selectedBufferCode = ReverbService.BufferSlot.LOOPING.storageCode.toInt(),
            settingsDraft = draft, directoryPicker = legacyProbePicker, tileEditor = tile,
            settingsPosition = SettingsListSavedPosition(7, 31),
            libraryPosition = NativeLibrarySavedScrollPosition(NativeListSavedPosition(43, 19), 23),
            incidentsPosition = NativeListSavedPosition(71, 37), aboutScrollPosition = 157,
        )
        check(imported.main == expected)
        check(NativeMainScreenSavedState.fromBundle(legacyParcel(expected.toBundle())) == expected)
        check(imported.directoryResultKey == legacyProbeDirectoryKey)
        check(imported.onboardingDraft == listOf("one_shot", "looping", "original_compression", "original_silence", "compression", "silence").zip(flags).toMap())
        check(imported.onboardingViewport == NativeOnboardingViewportSavedState(2, -.25f, listOf(11, 22, 33)))
        check(imported.rejections.isEmpty())
        check(target.savedStateRegistry.consumeRestoredStateForKey(Released012SavedUiKeys.ROOT_REGISTRY) == null)
        legacyParcel(target.save())
    }
    // Registry consumption mutates its restored Bundle; inspect an independent Parcel copy.
    LegacyProbeRegistry(legacyParcel(resaved)).use { inspection ->
        val residual = inspection.savedStateRegistry.restoredRoot()
        check(residual.keySet() == setOf(legacyProbeUnknown))
        check(residual.providers(legacyProbeUnknown) == listOf("keep", null, 17))
        val dialog = checkNotNull(inspection.savedStateRegistry.consumeRestoredStateForKey(legacyProbeDialogKey))
        check(dialog.keySet() == setOf(legacyProbeUnknown))
        check(dialog.providers(legacyProbeUnknown) == listOf("dialog keep", null))
        inspection.savedStateRegistry.checkUnrelated()
    }
    LegacyProbeRegistry(legacyParcel(resaved)).use { second ->
        val repeated = second.importLegacy()
        check(repeated.main == null && repeated.onboardingDraft == null && repeated.onboardingViewport == null)
        check(repeated.rejections.isEmpty())
        LegacyProbeRegistry(legacyParcel(second.save())).use { third ->
            check(third.savedStateRegistry.restoredRoot().providers(legacyProbeUnknown) == listOf("keep", null, 17))
        }
    }
}

private fun verifyLegacyMalformedRegistryRoundtrip() {
    val root = Bundle().apply {
        legacyProviders(Released012SavedUiKeys.MAIN, mutableStateOf(true), mutableStateOf("wrong Int"),
            mutableStateOf(false), mutableStateOf(true), mutableStateOf(false))
        legacyProviders(Released012SavedUiKeys.SETTINGS, listOf(1, 2), legacyProbePicker, null, null) // Four, not five providers.
    }
    val saved = LegacyProbeRegistry(legacyRegistrySeed(root)).use { owner ->
        val imported = owner.importLegacy()
        check(imported.main?.navigation == NativeMainSavedNavigation(true, -1, false, true, false))
        check(imported.rejections.map { it.key to it.slot }.toSet() == setOf(
            Released012SavedUiKeys.MAIN to 1, Released012SavedUiKeys.SETTINGS to null))
        legacyParcel(owner.save())
    }
    LegacyProbeRegistry(saved).use { inspection ->
        val residual = inspection.savedStateRegistry.restoredRoot()
        val values = residual.providers(Released012SavedUiKeys.MAIN)
        check(values.size == 5 && values[0] == null && values[2] == null && values[3] == null && values[4] == null)
        check((values[1] as State<*>).value == "wrong Int")
        check(residual.providers(Released012SavedUiKeys.SETTINGS) == listOf(listOf(1, 2), legacyProbePicker, null, null))
    }
}

private fun verifyLegacyNativePrecedence(presentNull: Boolean) {
    val root = Bundle().apply {
        legacyProviders(Released012SavedUiKeys.MAIN, *legacyMainProviders())
        legacyProviders(Released012SavedUiKeys.ABOUT_ID, legacyProbeUuid)
        legacyProviders(Released012SavedUiKeys.ONBOARDING, listOf(2, -.25f, 3), *List(6) { mutableStateOf(true) }.toTypedArray())
    }
    val about = Bundle().apply { legacyProviders(Released012SavedUiKeys.ABOUT_SCROLL, 157) }
    val native = if (presentNull) null else NativeMainScreenSavedState(aboutScrollPosition = 3)
    val viewport = NativeOnboardingViewportSavedState(1, 0f, listOf(4, 5, 6))
    val saved = LegacyProbeRegistry(legacyRegistrySeed(root, about)).use { owner ->
        val imported = owner.importLegacy(native, true, "native-directory", true, true, viewport)
        check(imported.main === native && imported.onboardingViewport === viewport)
        check(imported.onboardingDraft == null && imported.directoryResultKey == "native-directory")
        check(imported.remainingGroups.isEmpty() && imported.remainingAboutGroups.isEmpty() && imported.rejections.isEmpty())
        legacyParcel(owner.save())
    }
    LegacyProbeRegistry(saved).use { inspection ->
        val residual = inspection.savedStateRegistry.restoredRoot()
        check(residual.keySet() == root.keySet())
        check((residual.providers(Released012SavedUiKeys.MAIN)[0] as State<*>).value == true)
        check(residual.providers(Released012SavedUiKeys.ABOUT_ID)[0] == legacyProbeUuid)
        check(inspection.savedStateRegistry.consumeRestoredStateForKey(legacyProbeDialogKey)?.providers(Released012SavedUiKeys.ABOUT_SCROLL) == listOf(157))
        inspection.savedStateRegistry.checkUnrelated()
    }
}

private fun verifyLegacyDirectoryRegistryRoundtrip() {
    val root = Bundle().apply {
        legacyProviders(Released012SavedUiKeys.SETTINGS, listOf(2, 5), legacyProbePicker, null, null, legacyProbeDirectoryKey)
    }
    val saved = LegacyProbeRegistry(legacyRegistrySeed(root)).use { owner ->
        val imported = owner.importLegacy(directoryKey = "native-directory", directoryPresent = true)
        check(imported.directoryResultKey == "native-directory" && imported.main?.directoryPicker == null)
        check(imported.main?.settingsPosition == SettingsListSavedPosition(2, 5))
        legacyParcel(owner.save())
    }
    LegacyProbeRegistry(saved).use { inspection ->
        check(inspection.savedStateRegistry.restoredRoot().providers(Released012SavedUiKeys.SETTINGS) ==
            listOf(null, legacyProbePicker, null, null, legacyProbeDirectoryKey))
    }
}

private fun verifyLegacyAbsentRegistryRoundtrip() {
    val root = Bundle().apply {
        legacyProviders(Released012SavedUiKeys.SETTINGS, listOf(0, 0), legacyProbePicker, null, null, legacyProbeDirectoryKey)
        legacyProviders(Released012SavedUiKeys.ABOUT_ID, legacyProbeUuid)
    }
    val about = Bundle().apply { legacyProviders(legacyProbeUnknown, "no scroll") }
    val saved = LegacyProbeRegistry(legacyRegistrySeed(root, about)).use { owner ->
        val imported = owner.importLegacy()
        check(imported.main?.directoryPicker == legacyProbePicker && imported.directoryResultKey == legacyProbeDirectoryKey)
        check(imported.main?.tileEditor == null && imported.main?.settingsDraft == null && imported.main?.aboutScrollPosition == null)
        check(imported.rejections.isEmpty())
        legacyParcel(owner.save())
    }
    LegacyProbeRegistry(saved).use { inspection ->
        val residual = inspection.savedStateRegistry.restoredRoot()
        check(!residual.containsKey(Released012SavedUiKeys.SETTINGS))
        check(residual.providers(Released012SavedUiKeys.ABOUT_ID) == listOf(legacyProbeUuid))
        check(inspection.savedStateRegistry.consumeRestoredStateForKey(legacyProbeDialogKey)?.providers(legacyProbeUnknown) == listOf("no scroll"))
    }
}
