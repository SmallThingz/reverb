package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.InlinePlaybackRowOwner
import app.smallthingz.reverb.InlinePlayerBookkeeping
import app.smallthingz.reverb.InlineTrimUiCallbackGate
import app.smallthingz.reverb.submitInlinePlaybackAutoStart
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryPlayerBindingTest {
    @Test
    fun initialDetachedBind_preservesUnusedGrantUntilFirstAttachment() {
        val owner = InlinePlaybackRowOwner("fixture")
        var attached = false
        var creations = 0
        var controller: InlinePlayerBookkeeping? = null
        fun bind() {
            controller = bindAttachedLibraryPlayer(controller, attached,
                create = {
                    creations++
                    InlinePlayerBookkeeping().apply {
                        // The real controller's initial bind retires a hidden expansion.
                        if (!attached) retireExpansion {}
                    }
                },
                bindExisting = { if (!attached) it.revokeResume() })
        }

        bind()
        bind() // RecyclerView can deliver another model update before attachment.
        assertNull(controller)
        assertEquals(0, creations)
        attached = true
        bind()
        val admitted = checkNotNull(controller)
        var starts = 0
        assertTrue(submitInlinePlaybackAutoStart(admitted, owner, true, true, false) { starts++ })
        bind()
        assertSame(admitted, controller)
        assertFalse(submitInlinePlaybackAutoStart(admitted, owner, true, true, false) { starts++ })
        assertEquals(1, creations)
        assertEquals(1, starts)
    }

    @Test
    fun existingController_detachStillPausesAndKeepsAcceptedTrimReceiver() {
        val owner = InlinePlaybackRowOwner("fixture")
        val playback = InlinePlayerBookkeeping()
        val trimReceiver = InlineTrimUiCallbackGate({}, {}, visible = true)
        val controller = playback to trimReceiver
        assertTrue(submitInlinePlaybackAutoStart(playback, owner, true, true, false) {
            playback.requestSeek(1_000, owner)
        })
        playback.resumeAfterScrub = true
        var pauses = 0

        val detached = bindAttachedLibraryPlayer(controller, attached = false,
            create = { error("An existing controller must not be replaced on detach") },
            bindExisting = {
                pauses++
                it.first.revokeResume()
                it.second.setVisible(false)
            })
        assertSame(controller, detached)
        assertSame(trimReceiver, checkNotNull(detached).second)
        assertTrue(trimReceiver.attached)
        assertFalse(trimReceiver.visible)
        assertNull(playback.seekResumeOwner)
        assertFalse(playback.resumeAfterScrub)
        assertTrue(playback.seeks.pending) // Native completion can drain the accepted seek.
        assertEquals(1, pauses)

        val reattached = bindAttachedLibraryPlayer(detached, attached = true,
            create = { error("Reattachment must reuse the controller") },
            bindExisting = { it.second.setVisible(true) })
        assertSame(controller, reattached)
        assertTrue(trimReceiver.visible)
        assertFalse(submitInlinePlaybackAutoStart(playback, owner, true, true, false) {
            error("A consumed expansion grant must not be rearmed")
        })
    }

    @Test
    fun recycledController_doesNotReplayConsumedExpansionOnAttachment() {
        val owner = InlinePlaybackRowOwner("fixture")
        assertTrue(submitInlinePlaybackAutoStart(InlinePlayerBookkeeping(), owner, true, true, false) {})
        val detached = bindAttachedLibraryPlayer<InlinePlayerBookkeeping>(null, attached = false,
            create = { error("A detached replacement must remain unconstructed") }, bindExisting = {})
        val replacement = checkNotNull(bindAttachedLibraryPlayer(detached, attached = true,
            create = { InlinePlayerBookkeeping() }, bindExisting = {}))
        assertTrue(replacement.initialAutoStartPending)
        assertFalse(submitInlinePlaybackAutoStart(replacement, owner, true, true, false) {
            error("Recycling cannot grant a second initial playback")
        })
    }
}
