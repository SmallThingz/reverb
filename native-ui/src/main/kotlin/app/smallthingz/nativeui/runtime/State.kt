package app.smallthingz.nativeui.runtime

import kotlin.reflect.KProperty

/** Direct state cells for explicit View owners. No snapshots, subscriptions or rendering scheduler. */
interface State<out T> { val value: T }
interface MutableState<T> : State<T> {
    override var value: T
    operator fun component1(): T = value
    operator fun component2(): (T) -> Unit = { value = it }
}
private class ValueState<T>(initial: T) : MutableState<T> {
    override var value: T = initial
        set(next) { if (field != next) field = next }
}
fun <T> mutableStateOf(value: T): MutableState<T> = ValueState(value)
inline operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value
inline operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }
fun <T> derivedStateOf(calculation: () -> T): State<T> = object : State<T> { override val value get() = calculation() }
interface IntState : State<Int> { val intValue: Int; override val value get() = intValue }
interface MutableIntState : IntState, MutableState<Int> { override var intValue: Int; override var value: Int get() = intValue; set(value) { intValue = value } }
private class IntCell(override var intValue: Int) : MutableIntState
fun mutableIntStateOf(value: Int): MutableIntState = IntCell(value)
inline operator fun IntState.getValue(thisObj: Any?, property: KProperty<*>): Int = intValue
inline operator fun MutableIntState.setValue(thisObj: Any?, property: KProperty<*>, value: Int) { intValue = value }
interface LongState : State<Long> { val longValue: Long; override val value get() = longValue }
interface MutableLongState : LongState, MutableState<Long> { override var longValue: Long; override var value: Long get() = longValue; set(value) { longValue = value } }
private class LongCell(override var longValue: Long) : MutableLongState
fun mutableLongStateOf(value: Long): MutableLongState = LongCell(value)
inline operator fun LongState.getValue(thisObj: Any?, property: KProperty<*>): Long = longValue
inline operator fun MutableLongState.setValue(thisObj: Any?, property: KProperty<*>, value: Long) { longValue = value }
interface FloatState : State<Float> { val floatValue: Float; override val value get() = floatValue }
interface MutableFloatState : FloatState, MutableState<Float> { override var floatValue: Float; override var value: Float get() = floatValue; set(value) { floatValue = value } }
private class FloatCell(initial: Float) : MutableFloatState {
    override var floatValue: Float = initial
        set(next) { if (field != next) field = next }
}
fun mutableFloatStateOf(value: Float): MutableFloatState = FloatCell(value)
inline operator fun FloatState.getValue(thisObj: Any?, property: KProperty<*>): Float = floatValue
inline operator fun MutableFloatState.setValue(thisObj: Any?, property: KProperty<*>, value: Float) { floatValue = value }
