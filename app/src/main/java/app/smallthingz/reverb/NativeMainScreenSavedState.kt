package app.smallthingz.reverb

import android.os.Bundle
import app.smallthingz.reverb.nativeui.SettingsListSavedPosition

/** Only released rememberSaveable payloads. Library snapshots, selection, Undo and motion are absent. */
internal data class NativeMainScreenSavedState(
    val navigation: NativeMainSavedNavigation = NativeMainSavedNavigation(),
    val selectedBufferCode: Int? = null,
    val settingsDraft: List<Any>? = null,
    val directoryPicker: List<Any>? = null,
    val tileEditor: List<Any>? = null,
    val settingsPosition: SettingsListSavedPosition? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("settings", navigation.showSettings)
        putInt("buffer_target", navigation.settingsBufferTargetCode)
        putBoolean("about", navigation.showAboutDialog)
        putBoolean("incidents", navigation.showIncidents)
        putBoolean("library", navigation.showLibrary)
        selectedBufferCode?.let { putInt("capture_buffer", it) }
        // Existing savers contain only Int, Long, Boolean and String; preserve their exact values
        // and type/order. This is Activity saved state, never a new durable configuration store.
        settingsDraft?.let { putSerializable("settings_draft", ArrayList(it)) }
        directoryPicker?.let { putSerializable("directory_picker", ArrayList(it)) }
        tileEditor?.let { putSerializable("tile_editor", ArrayList(it)) }
        settingsPosition?.let {
            putIntArray("settings_position", intArrayOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset))
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle?): NativeMainScreenSavedState? {
            if (bundle == null) return null
            fun values(key: String): List<Any>? {
                val raw = bundle.getSerializable(key) as? List<*> ?: return null
                if (raw.any { it !is Int && it !is Long && it !is Boolean && it !is String }) return null
                @Suppress("UNCHECKED_CAST")
                return raw as List<Any>
            }
            val position = bundle.getIntArray("settings_position")
            return NativeMainScreenSavedState(
                NativeMainSavedNavigation(bundle.getBoolean("settings"), bundle.getInt("buffer_target", -1),
                    bundle.getBoolean("about"), bundle.getBoolean("incidents"), bundle.getBoolean("library")),
                if (bundle.containsKey("capture_buffer")) bundle.getInt("capture_buffer") else null,
                values("settings_draft"), values("directory_picker"), values("tile_editor"),
                position?.takeIf { it.size == 2 }?.let { SettingsListSavedPosition(it[0], it[1]) },
            )
        }
    }
}
