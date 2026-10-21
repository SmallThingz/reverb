/*
 * Source-derived retained hit path and pass order from UI 1.11.2 HitPathTracker.kt.
 * Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

/** Input-only retained path, not a render/state tree. Nodes are existing concrete input owners. */
internal interface NativePhasePointerNode {
    val attached: Boolean
    val shareWithSiblings: Boolean get() = false
    fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass)
    fun cancel()
}

internal class NativePointerPhaseTree {
    private class Branch(val node: NativePhasePointerNode) {
        val pointers = linkedSetOf<Long>()
        val children = mutableListOf<Branch>()
        var event: NativeDragPointerEvent? = null
    }

    private val roots = mutableListOf<Branch>()
    private var dispatching = false
    private var cancelAfterDispatch = false
    val hasPaths: Boolean get() = roots.isNotEmpty()

    fun add(pointerId: Long, path: List<NativePhasePointerNode>) {
        var siblings = roots
        for (node in path) {
            val branch = siblings.firstOrNull { it.node === node }
                ?: Branch(node).also(siblings::add)
            branch.pointers += pointerId
            siblings = branch.children
        }
    }

    fun release(pointerIds: Set<Long>) {
        fun releaseIn(branches: MutableList<Branch>) {
            val iterator = branches.iterator()
            while (iterator.hasNext()) {
                val branch = iterator.next()
                branch.pointers.removeAll(pointerIds)
                releaseIn(branch.children)
                if (branch.pointers.isEmpty()) iterator.remove()
            }
        }
        releaseIn(roots)
    }

    /** Called at the event boundary, not in the middle of another node's callback. */
    fun pruneDetached() {
        fun prune(branches: MutableList<Branch>) {
            val iterator = branches.iterator()
            while (iterator.hasNext()) {
                val branch = iterator.next()
                if (!branch.node.attached) {
                    cancelBranch(branch)
                    iterator.remove()
                } else prune(branch.children)
            }
        }
        prune(roots)
    }

    fun dispatch(
        eventFor: (NativePhasePointerNode, Set<Long>) -> NativeDragPointerEvent,
    ): Boolean {
        fun cache(branch: Branch) {
            branch.event = eventFor(branch.node, branch.pointers)
            branch.children.forEach(::cache)
        }
        roots.forEach(::cache)
        var dispatched = false
        fun initialAndMain(branch: Branch) {
            if (!branch.node.attached) return
            val event = requireNotNull(branch.event)
            if (event.changes.isEmpty()) return
            branch.node.dispatch(event, NativePointerPass.INITIAL)
            dispatched = true
            if (branch.node.attached) branch.children.forEach(::initialAndMain)
            if (branch.node.attached) branch.node.dispatch(event, NativePointerPass.MAIN)
        }
        fun final(branch: Branch) {
            if (!branch.node.attached) return
            val event = requireNotNull(branch.event)
            if (event.changes.isEmpty()) return
            branch.node.dispatch(event, NativePointerPass.FINAL)
            if (branch.node.attached) branch.children.forEach(::final)
        }
        fun clear(branch: Branch) {
            branch.event = null
            branch.children.forEach(::clear)
        }
        dispatching = true
        try {
            roots.forEach(::initialAndMain)
            roots.forEach(::final)
        } finally {
            dispatching = false
            roots.forEach(::clear)
            if (cancelAfterDispatch) {
                cancelAfterDispatch = false
                cancel()
            }
        }
        return dispatched
    }

    /** Match HitPathTracker: cancellation requested during callbacks waits until dispatch finishes. */
    fun cancel() {
        if (dispatching) {
            cancelAfterDispatch = true
            return
        }
        roots.forEach(::cancelBranch)
        roots.clear()
    }

    private fun cancelBranch(branch: Branch) {
        branch.children.forEach(::cancelBranch)
        branch.node.cancel()
    }
}
