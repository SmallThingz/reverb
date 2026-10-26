package app.smallthingz.reverb

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** Host-only Android preference boundary; the journal parser/reader remains production code. */
internal class RecordingScanTestContext(
    readCleanupEntries: () -> Set<String>,
) : ContextWrapper(null) {
    private val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "contains" -> {
                check(args!![0] == PrefKey.PENDING_OUTPUT_CLEANUP.name)
                true
            }
            "getStringSet" -> {
                check(args!![0] == PrefKey.PENDING_OUTPUT_CLEANUP.name)
                readCleanupEntries()
            }
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RecordingScanTestPreferences"
            else -> error("Unexpected preference method: ${method.name}")
        }
    } as SharedPreferences

    override fun getPackageName(): String = "app.smallthingz.reverb.hosttest"

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == packageName && mode == MODE_PRIVATE)
        return preferences
    }
}
