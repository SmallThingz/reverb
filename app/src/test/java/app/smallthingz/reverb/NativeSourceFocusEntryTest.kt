package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the ancestor admission used by NativeSourceFocusPolicy's direct leaf request. */
class NativeSourceFocusEntryTest {
    private class Node(
        var parent: Node? = null,
        override var sourceFocusEntryAllowed: Boolean = true,
    ) : NativeSourceFocusEntryBoundary

    @Test fun directLeafEntryCannotBypassBlockedSettingsAncestor() {
        val window = Node()
        val home = Node(window)
        val settings = Node(window, sourceFocusEntryAllowed = false)
        val field = Node(Node(settings))

        assertFalse(nativeMayEnterSourceFocus(field, home) { it.parent })
        assertFalse(nativeMayEnterSourceFocus(field, null) { it.parent })
    }

    @Test fun readySettingsAllowsEntryAndRechecksReadinessOnEveryRequest() {
        val settings = Node()
        val field = Node(settings)

        assertTrue(nativeMayEnterSourceFocus(field, null) { it.parent })
        settings.sourceFocusEntryAllowed = false
        assertFalse(nativeMayEnterSourceFocus(field, null) { it.parent })
        settings.sourceFocusEntryAllowed = true
        assertTrue(nativeMayEnterSourceFocus(field, null) { it.parent })
    }

    @Test fun blockedEntryBoundaryDoesNotTrapCurrentFocusInsideIt() {
        val window = Node()
        val settings = Node(window, sourceFocusEntryAllowed = false)
        val field = Node(settings)
        val home = Node(window)

        assertTrue(nativeMayEnterSourceFocus(home, field) { it.parent })
    }

    @Test fun movementAlreadyInsideTheSameBoundaryIsNotAnotherEntry() {
        val settings = Node(sourceFocusEntryAllowed = false)
        val first = Node(settings)
        val second = Node(settings)

        assertTrue(nativeMayEnterSourceFocus(second, first) { it.parent })
        assertTrue(nativeMayEnterSourceFocus(first, first) { it.parent })
    }

    @Test fun outerActiveBoundaryDoesNotAuthorizeEnteringBlockedNestedBoundary() {
        val outer = Node(sourceFocusEntryAllowed = false)
        val active = Node(outer)
        val inner = Node(outer, sourceFocusEntryAllowed = false)
        val nestedField = Node(inner)

        assertFalse(nativeMayEnterSourceFocus(nestedField, active) { it.parent })
        assertTrue(nativeMayEnterSourceFocus(nestedField, Node(inner)) { it.parent })
    }

    @Test fun reparentedFormerChildDoesNotRetainEntryAuthority() {
        val settings = Node(sourceFocusEntryAllowed = false)
        val oldChild = Node(settings)
        val field = Node(settings)
        oldChild.parent = null

        assertFalse(nativeMayEnterSourceFocus(field, oldChild) { it.parent })
    }

    @Test fun focusInAnotherBlockedBoundaryDoesNotAuthorizeTargetBoundary() {
        val window = Node()
        val first = Node(window, sourceFocusEntryAllowed = false)
        val second = Node(window, sourceFocusEntryAllowed = false)

        assertFalse(nativeMayEnterSourceFocus(Node(second), Node(first)) { it.parent })
    }
}
