package app.smallthingz.reverb

import app.smallthingz.nativeui.runtime.MutableFloatState
import app.smallthingz.nativeui.runtime.MutableIntState
import app.smallthingz.nativeui.runtime.MutableState

/**
 * Native-render invalidation only. Storage remains the exact released snapshot state, including
 * its policy, primitive access and synchronous mutation. Null (released Compose callers) returns
 * the original delegate with no wrapper. The notification may occur for an equivalent assignment:
 * the host coalesces it, and admission always reads the original model directly.
 *
 * The callback must schedule rendering rather than render inline. A model operation can write
 * multiple properties; there is deliberately no new transaction, dispatch or delayed mutation.
 */
internal fun <T> rangeUiState(state: MutableState<T>, changed: (() -> Unit)?): MutableState<T> =
    if (changed == null) state else object : MutableState<T> {
        override var value: T
            get() = state.value
            set(value) { state.value = value; changed() }
        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }
    }

internal fun rangeUiState(state: MutableFloatState, changed: (() -> Unit)?): MutableFloatState =
    if (changed == null) state else object : MutableFloatState {
        override var floatValue: Float
            get() = state.floatValue
            set(value) { state.floatValue = value; changed() }
        override var value: Float
            get() = floatValue
            set(value) { floatValue = value }
        override fun component1(): Float = floatValue
        override fun component2(): (Float) -> Unit = { floatValue = it }
    }

internal fun rangeUiState(state: MutableIntState, changed: (() -> Unit)?): MutableIntState =
    if (changed == null) state else object : MutableIntState {
        override var intValue: Int
            get() = state.intValue
            set(value) { state.intValue = value; changed() }
        override var value: Int
            get() = intValue
            set(value) { intValue = value }
        override fun component1(): Int = intValue
        override fun component2(): (Int) -> Unit = { intValue = it }
    }
