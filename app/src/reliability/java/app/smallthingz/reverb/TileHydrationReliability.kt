package app.smallthingz.reverb

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises the real persisted-state worker without requiring a QA Quick Settings tile. */
internal fun verifyTileHydrationConverges(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val cache = RecordingQuickTileStateCache
    val type = cache.javaClass
    fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    val worker = field("persistedReadExecutor").get(cache) as ExecutorService
    fun drain() { worker.submit {}.get(15, TimeUnit.SECONDS) }
    drain()
    val lock = requireNotNull(field("stateLock").get(cache))
    val names = listOf("cachedSnapshot", "stateGeneration", "hydrationGeneration", "runtimeAuthoritative")
    val original = synchronized(lock) { names.associateWith { field(it).get(cache) } }
    val preferences = getRecorderPreferences(context)
    val duration = preferences.snapshotDurablePreferenceValue(PrefKey.QUICK_TILE_ONE_SHOT_DURATION_MILLIS)
    try {
        val persisted = type.getDeclaredMethod("readPersisted", Context::class.java)
            .apply { isAccessible = true }.invoke(cache, context) as RecordingTileSnapshot
        synchronized(lock) {
            field("cachedSnapshot").set(cache, persisted)
            field("stateGeneration").setLong(cache, 100L)
            field("hydrationGeneration").set(cache, null)
            field("runtimeAuthoritative").setBoolean(cache, false)
        }
        repeat(24) {
            cache.hydratePersistedAsync(context)
            drain()
            check(field("stateGeneration").getLong(cache) == 100L) {
                "Unchanged tile hydration republished state and scheduled another SystemUI refresh"
            }
        }
        val changedSeconds = if (persisted.oneShotSeconds == 3600f) 7200f else 3600f
        check(preferences.edit().putLong(PrefKey.QUICK_TILE_ONE_SHOT_DURATION_MILLIS,
            (changedSeconds * 1000).toLong()).commit())
        cache.hydratePersistedAsync(context)
        drain()
        check(field("stateGeneration").getLong(cache) == 101L)
        check(cache.readNonBlocking().oneShotSeconds == changedSeconds)
        repeat(24) { cache.hydratePersistedAsync(context); drain() }
        check(field("stateGeneration").getLong(cache) == 101L)
        val running = cache.readNonBlocking().copy(listening = true, commandGeneration = 77L)
        cache.publish(running)
        cache.hydratePersistedAsync(context)
        drain()
        check(cache.readNonBlocking() === running) { "Stopped hydration overwrote live capture" }
    } finally {
        drain()
        check(preferences.edit().restoreDurablePreferenceValue(
            PrefKey.QUICK_TILE_ONE_SHOT_DURATION_MILLIS, duration).commit())
        synchronized(lock) { original.forEach { (name, value) -> field(name).set(cache, value) } }
    }
}
