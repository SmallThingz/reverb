package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RetentionForegroundPublicationTest {
    @Test
    fun fortyUnchangedRetentionPassesPublishOnce() {
        val owner = PublicationOwner()

        repeat(40) { owner.retentionPass() }

        assertEquals(1, owner.platformPublications)
        assertEquals(1, owner.fullNotificationBuilds)
        assertEquals(DATA_SYNC, owner.types)
    }

    @Test
    fun titleAndTextChangesEachRequireOneNewPublication() {
        val owner = PublicationOwner()
        owner.retentionPass()
        owner.content = owner.content.copy(title = "Localized app")
        repeat(4) { owner.retentionPass() }
        owner.content = owner.content.copy(text = "Localized retention")
        repeat(4) { owner.retentionPass() }

        assertEquals(3, owner.platformPublications)
    }

    @Test
    fun everyOtherNotificationRoleRequiresRetentionHandoffEvenWithIdenticalText() {
        val roles = listOf(
            Role(exporting = true),
            Role(clearing = true),
            Role(),
            Role(exporting = true, retaining = true),
            Role(clearing = true, retaining = true),
        )
        roles.forEach { role ->
            val owner = PublicationOwner()
            owner.retentionPass()
            // Equal translated strings do not prove the same notification owner.
            owner.publish(role = role)
            repeat(4) { owner.retentionPass() }

            assertEquals("role=$role", 3, owner.platformPublications)
        }
    }

    @Test
    fun stoppedLostAndMicrophoneForegroundTypesCannotReuseDataSyncProof() {
        listOf(0, MICROPHONE, DATA_SYNC or MICROPHONE).forEach { observedType ->
            val owner = PublicationOwner()
            owner.retentionPass()
            owner.types = observedType
            repeat(4) { owner.retentionPass() }

            assertEquals("type=$observedType", 2, owner.platformPublications)
            assertEquals(DATA_SYNC, owner.types)
        }
    }

    @Test
    fun captureRequestCannotReuseRetentionProofInsteadOfAcquiringMicrophoneType() {
        val owner = PublicationOwner()
        owner.retentionPass()

        owner.publication.publishRetentionIfNeeded(
            retentionOnly = false,
            foregroundServiceTypes = owner.types,
            content = owner.content,
        ) {
            owner.publish(types = MICROPHONE, role = Role())
        }

        assertEquals(2, owner.platformPublications)
        assertEquals(MICROPHONE, owner.types)
        owner.retentionPass()
        assertEquals(3, owner.platformPublications)
        assertEquals(DATA_SYNC, owner.types)
    }

    @Test
    fun stopInvalidatesProofEvenIfPlatformStopThrowsAndTypeStaysUnchanged() {
        val owner = PublicationOwner()
        owner.retentionPass()
        // stopForegroundTracked invalidates before attempting the platform stop.
        owner.publication.invalidate()
        assertEquals(DATA_SYNC, owner.types)

        repeat(4) { owner.retentionPass() }

        assertEquals(2, owner.platformPublications)
    }

    @Test
    fun failedOtherOwnerPublicationCannotLeaveReusableRetentionProof() {
        val owner = PublicationOwner()
        owner.retentionPass()
        owner.platformFailure = IllegalStateException("platform transition failed")
        assertThrows(IllegalStateException::class.java) {
            owner.publish(role = Role(exporting = true))
        }
        assertEquals(DATA_SYNC, owner.types)
        owner.platformFailure = null

        repeat(4) { owner.retentionPass() }

        assertEquals(3, owner.platformPublications)
    }

    @Test
    fun contentResolutionFailureAlsoInvalidatesPriorPublication() {
        val owner = PublicationOwner()
        owner.retentionPass()
        owner.contentFailure = IllegalStateException("resource resolution failed")
        assertThrows(IllegalStateException::class.java) { owner.publish() }
        owner.contentFailure = null

        repeat(4) { owner.retentionPass() }

        assertEquals(2, owner.platformPublications)
    }

    @Test
    fun failedFirstPromotionIsRetriedAndNeverCachedAsSuccess() {
        val owner = PublicationOwner()
        owner.platformFailure = IllegalStateException("first promotion failed")
        assertThrows(IllegalStateException::class.java) { owner.retentionPass() }
        assertEquals(0, owner.types)
        owner.platformFailure = null

        repeat(4) { owner.retentionPass() }

        assertEquals(2, owner.platformPublications)
        assertEquals(DATA_SYNC, owner.types)
    }

    private data class Role(
        val exporting: Boolean = false,
        val clearing: Boolean = false,
        val retaining: Boolean = false,
    )

    /** Exercises the production admission/publication policy with the platform call injected. */
    private class PublicationOwner {
        val publication = RetentionForegroundPublication(DATA_SYNC)
        var content = ForegroundNotificationContent("Reverb", "Updating buffer retention")
        var types = 0
        var platformPublications = 0
        var fullNotificationBuilds = 0
        var platformFailure: RuntimeException? = null
        var contentFailure: RuntimeException? = null

        fun retentionPass() {
            publication.publishRetentionIfNeeded(
                retentionOnly = true,
                foregroundServiceTypes = types,
                content = content,
            ) { publish() }
        }

        fun publish(types: Int = DATA_SYNC, role: Role = Role(retaining = true)) {
            publication.publish(
                types = types,
                exporting = role.exporting,
                clearing = role.clearing,
                retaining = role.retaining,
                content = {
                    contentFailure?.let { throw it }
                    content
                },
            ) { publishedContent ->
                fullNotificationBuilds++
                platformPublications++
                assertEquals(content, publishedContent)
                platformFailure?.let { throw it }
                this.types = types
            }
        }
    }

    private companion object {
        const val DATA_SYNC = 1
        const val MICROPHONE = 2
    }
}
