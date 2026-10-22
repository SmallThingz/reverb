package app.smallthingz.reverb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Tests the draft controller's callback contract, not native-owner render counts. */
class SettingsDraftPublicationTest {
    private val baseline = SettingsSnapshot(
        retentionMode = RetentionMode.TIME,
        oneShotRetentionTime = 300,
        loopingRetentionTime = 600,
    )

    @Test
    fun parentReconciliationPrecedesChildReadinessForEachPublication() = runBlocking {
        val callbacks = mutableListOf<String>()
        var latestParent: SettingsDraftPresentation? = null
        val controller = SettingsDraftController(
            initiallyActive = true,
            isPanelActive = { true },
            onChanged = {
                latestParent = it
                callbacks += "parent"
            },
            onThemePreview = {},
            onChildEditorReadiness = { active, ready ->
                assertEquals("parent", callbacks.last())
                val parent = requireNotNull(latestParent)
                assertEquals(parent.active, active)
                assertEquals(parent.hydrated && parent.interactionReady, ready)
                callbacks += "child"
            },
        )
        controller.hydrate(
            readDurable = { SettingsDraftBaseline(SettingsUiDraft.normalized(baseline), null) },
            restoreFields = { it },
        )
        assertEquals(listOf("parent", "child", "parent", "child"), callbacks)
        assertTrue(requireNotNull(latestParent).interactionReady)

        callbacks.clear()
        val next = controller.state.displayed.copy(oneShotTimeText = "unfinished")
        controller.edited(next)
        assertEquals(listOf("parent", "child"), callbacks)
        assertEquals(next, requireNotNull(latestParent).displayed)

        callbacks.clear()
        val attempt = requireNotNull(controller.beginPersistence())
        controller.finishPersistence(attempt)
        controller.setActive(false)
        assertEquals(List(3) { listOf("parent", "child") }.flatten(), callbacks)
        assertFalse(requireNotNull(latestParent).active)
        assertFalse(requireNotNull(latestParent).interactionReady)
    }

    @Test
    fun disposalPreventsFurtherPublicationCallbacks() = runBlocking {
        var parentCalls = 0
        var childCalls = 0
        val controller = SettingsDraftController(
            initiallyActive = true,
            isPanelActive = { true },
            onChanged = { parentCalls++ },
            onThemePreview = {},
            onChildEditorReadiness = { _, _ -> childCalls++ },
        )
        controller.hydrate(
            readDurable = { SettingsDraftBaseline(SettingsUiDraft.normalized(baseline), null) },
            restoreFields = { it },
        )
        assertEquals(2, parentCalls)
        assertEquals(2, childCalls)
        controller.dispose()
        controller.edited(controller.state.displayed.copy(oneShotTimeText = "unfinished"))
        controller.setActive(false)
        assertEquals(SettingsDraftHydrationResult.NOT_NEEDED, controller.hydrate(
            readDurable = { error("Disposed controller must not read storage") },
            restoreFields = { error("Disposed controller must not restore fields") },
        ))
        assertEquals(2, parentCalls)
        assertEquals(2, childCalls)
    }
}
