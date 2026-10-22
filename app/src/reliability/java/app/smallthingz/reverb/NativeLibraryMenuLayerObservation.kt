package app.smallthingz.reverb

import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Optional observations of existing menu layers, called only inside the caller's focused-window
 * capture guard. Never creates a layer/cache, requests layout, draws, or admits an input action.
 * Field names below are pinned to UI 1.11.2; unavailable metadata stays explicit.
 */
internal fun observeLibraryMenuLayers(root: View): JSONObject {
    val result = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
        .put("root_identity", System.identityHashCode(root)).put("traversal_complete", false)
    val owners = JSONArray().also { result.put("compose_owners", it) }
    try {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(root.isAttachedToWindow && root.isShown && root.hasWindowFocus())
        check(root.context.applicationInfo.uid == Process.myUid() && root.display?.displayId == 0)
        for (view in onboardingViewDescendants(root).filterIsInstance<ViewRootForTest>()) {
            val androidView = view as View
            check(androidView.rootView === root)
            val owner = JSONObject().put("identity", System.identityHashCode(view))
                .put("class", view.javaClass.name)
                .put("hardware_accelerated", androidView.isHardwareAccelerated)
                .also(owners::put)
            val entries = JSONArray().also { owner.put("coordinators", it) }
            val paths = JSONArray().also { owner.put("semantic_paths", it) }
            val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            for (node in view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)) {
                val path = JSONObject().put("semantic_id", node.id)
                    .put("text", node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
                    .also(paths::put)
                val chain = JSONArray().also { path.put("coordinator_ids", it) }
                try {
                    val layout = checkNotNull(menuLayerField(node, "layoutNode"))
                    val nodes = checkNotNull(menuLayerField(layout, "nodes"))
                    var coordinator = menuLayerField(nodes, "outerCoordinator")
                    var count = 0
                    while (coordinator != null) {
                        check(++count <= 128) { "Coordinator chain exceeded diagnostic bound" }
                        val current = coordinator
                        chain.put(System.identityHashCode(current))
                        if (visited.add(current)) {
                            val entry = JSONObject().put("identity", System.identityHashCode(current))
                                .put("class", current.javaClass.name).also(entries::put)
                            entry.menuObservation("last_layer_drawing_skipped") {
                                menuLayerField(current, "lastLayerDrawingWasSkipped")
                            }
                            val layer = menuLayerField(current, "layer")
                            entry.put("layer", layer?.let { observeMenuOwnedLayer(it, androidView) }
                                ?: JSONObject.NULL)
                        }
                        coordinator = menuLayerField(current, "wrappedBy")
                    }
                    path.put("complete", true)
                } catch (failure: Throwable) {
                    path.put("complete", false).put("unavailable", failure.toString())
                }
            }
        }
        result.put("traversal_complete", true)
    } catch (failure: Throwable) {
        result.put("unavailable", failure.toString())
    }
    return result
}

private fun observeMenuOwnedLayer(layer: Any, expectedOwner: View): JSONObject {
    val result = JSONObject().put("identity", System.identityHashCode(layer))
        .put("class", layer.javaClass.name)
    try {
        check(menuLayerField(layer, "ownerView") === expectedOwner) { "Layer belongs to another owner" }
        result.put("same_owner", true)
        result.menuObservation("dirty") { menuLayerField(layer, "isDirty") }
        result.menuObservation("destroyed") { menuLayerField(layer, "isDestroyed") }
        when (layer.javaClass.name) {
            "androidx.compose.ui.platform.GraphicsLayerOwnerLayer" -> {
                result.menuObservation("drawn_with_enabled_z") { menuLayerField(layer, "drawnWithEnabledZ") }
                val graphics = menuLayerField(layer, "graphicsLayer") as GraphicsLayer
                result.put("graphics_identity", System.identityHashCode(graphics))
                    .put("alpha", graphics.alpha.toDouble())
                    .put("scale_x", graphics.scaleX.toDouble()).put("scale_y", graphics.scaleY.toDouble())
                    .put("translation_x", graphics.translationX.toDouble())
                    .put("translation_y", graphics.translationY.toDouble())
                    .put("shadow_elevation", graphics.shadowElevation.toDouble())
                    .put("clip", graphics.clip).put("compositing_strategy", graphics.compositingStrategy.toString())
                    .put("size", graphics.size.toString()).put("top_left", graphics.topLeft.toString())
                    .put("pivot", graphics.pivotOffset.toString())
                    .put("ambient_shadow_color", graphics.ambientShadowColor.toString())
                    .put("spot_shadow_color", graphics.spotShadowColor.toString())
                // Read stored outline fields, not the outline getter, which may configure a cache.
                result.menuObservation("outline_dirty") { menuLayerField(graphics, "outlineDirty") }
                result.menuObservation("stored_outline") { menuLayerField(graphics, "internalOutline")?.toString() }
                result.menuObservation("implementation_class") { menuLayerField(graphics, "impl")?.javaClass?.name }
            }
            "androidx.compose.ui.platform.RenderNodeLayer" -> {
                result.menuObservation("drawn_with_enabled_z") { menuLayerField(layer, "drawnWithZ") }
                result.put("graphics_metadata_unavailable", "RenderNodeLayer route; no GraphicsLayer instance")
            }
            else -> result.put("graphics_metadata_unavailable", "Unrecognized pinned layer implementation")
        }
    } catch (failure: Throwable) {
        result.put("unavailable", failure.toString())
    }
    return result
}

private fun JSONObject.menuObservation(key: String, observe: () -> Any?) {
    try { put(key, observe() ?: JSONObject.NULL) }
    catch (failure: Throwable) { put("${key}_unavailable", failure.toString()) }
}

private fun menuLayerField(owner: Any, name: String): Any? {
    var type: Class<*>? = owner.javaClass
    while (type != null) {
        try { return type.getDeclaredField(name).apply { isAccessible = true }.get(owner) }
        catch (_: NoSuchFieldException) { type = type.superclass }
    }
    throw NoSuchFieldException("${owner.javaClass.name}.$name")
}
