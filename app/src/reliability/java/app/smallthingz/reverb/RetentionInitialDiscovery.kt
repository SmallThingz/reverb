package app.smallthingz.reverb

/** QA-only initial accessibility publication budget; never used after input begins. */
internal class RetentionInitialDiscovery(private val startedAt: Long) {
    var windowId: Int? = null
        private set

    fun ready(currentWindowId: Int, matchingTargets: Int, now: Long): Boolean {
        val previous = windowId
        check(previous == null || previous == currentWindowId) { "Initial editable application window changed" }
        windowId = currentWindowId
        check(matchingTargets in 0..1) { "Initial editable target is ambiguous: $matchingTargets" }
        check(now >= startedAt && now - startedAt < 8_000L) {
            "Initial editable accessibility target was not published within8s; no input sent"
        }
        return matchingTargets == 1
    }
}

internal class RetentionInitialTargetNotPublished : IllegalStateException("Initial editable target not published")

/** Finite regression checks run before any device window/input work in the keyboard probe. */
internal fun verifyRetentionInitialDiscoveryChecks(): List<String> {
    val checks = mutableListOf<String>()
    RetentionInitialDiscovery(100L).also {
        check(!it.ready(7, 0, 100L))
        check(!it.ready(7, 0, 140L))
        check(it.ready(7, 1, 180L))
        checks += "zero_then_unique_same_window"
    }
    RetentionInitialDiscovery(100L).also {
        check(!it.ready(7, 0, 8_099L))
        check(runCatching { it.ready(7, 0, 8_100L) }.exceptionOrNull() is IllegalStateException)
        checks += "persistent_zero_existing_eight_second_deadline"
    }
    RetentionInitialDiscovery(100L).also {
        check(runCatching { it.ready(7, 2, 100L) }.exceptionOrNull() is IllegalStateException)
        checks += "duplicate_rejected_immediately"
    }
    RetentionInitialDiscovery(100L).also {
        check(!it.ready(7, 0, 100L))
        check(runCatching { it.ready(8, 1, 140L) }.exceptionOrNull() is IllegalStateException)
        checks += "changed_window_rejected_before_unique_target"
    }
    return checks
}
