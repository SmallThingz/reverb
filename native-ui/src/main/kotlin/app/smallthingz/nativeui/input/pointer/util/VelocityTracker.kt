/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.smallthingz.nativeui.input.pointer.util

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.unit.Velocity
import app.smallthingz.nativeui.util.fastCoerceAtLeast
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

private const val AssumePointerMoveStoppedMilliseconds = 40
private const val HistorySize = 20
private const val HorizonMilliseconds = 100
private inline fun checkPrecondition(value: Boolean, message: () -> String) { if (!value) throw IllegalStateException(message()) }
private fun throwIllegalArgumentException(message: String): Nothing = throw IllegalArgumentException(message)

/** Exact LSQ2 planar tracker. Gesture owners supply original positions and history explicitly. */
class VelocityTracker {
    private val x = VelocityTracker1D()
    private val y = VelocityTracker1D()
    fun addPosition(timeMillis: Long, position: Offset) { x.addDataPoint(timeMillis, position.x); y.addDataPoint(timeMillis, position.y) }
    fun calculateVelocity(): Velocity = calculateVelocity(Velocity(Float.MAX_VALUE, Float.MAX_VALUE))
    fun calculateVelocity(maximumVelocity: Velocity): Velocity {
        checkPrecondition(maximumVelocity.x > 0f && maximumVelocity.y > 0f) { "maximumVelocity must be positive: $maximumVelocity" }
        return Velocity(x.calculateVelocity(maximumVelocity.x), y.calculateVelocity(maximumVelocity.y))
    }
    fun resetTracking() { x.resetTracking(); y.resetTracking() }
}

class VelocityTracker1D
internal constructor(
    // whether the data points added to the tracker represent differential values
    // (i.e. change in the  tracked object's displacement since the previous data point).
    // If false, it means that the data points added to the tracker will be considered as absolute
    // values (e.g. positional values).
    val isDataDifferential: Boolean = false,
    // The velocity tracking strategy that this instance uses for all velocity calculations.
    private val strategy: Strategy = Strategy.Lsq2,
) {

    init {
        if (isDataDifferential && strategy.equals(Strategy.Lsq2)) {
            throw IllegalStateException("Lsq2 not (yet) supported for differential axes")
        }
    }

    /**
     * Constructor to create a new velocity tracker. It allows to specify whether or not the tracker
     * should consider the data ponits provided via [addDataPoint] as differential or
     * non-differential.
     *
     * Differential data ponits represent change in displacement. For instance, differential data
     * points of [2, -1, 5] represent: the object moved by "2" units, then by "-1" units, then by
     * "5" units. An example use case for differential data points is when tracking velocity for an
     * object whose displacements (or change in positions) over time are known.
     *
     * Non-differential data ponits represent position of the object whose velocity is tracked. For
     * instance, non-differential data points of [2, -1, 5] represent: the object was at position
     * "2", then at position "-1", then at position "5". An example use case for non-differential
     * data points is when tracking velocity for an object whose positions on a geometrical axis
     * over different instances of time are known.
     *
     * @param isDataDifferential `true` if the data ponits provided to the constructed tracker are
     *   differential. `false` otherwise.
     */
    constructor(isDataDifferential: Boolean) : this(isDataDifferential, Strategy.Impulse)

    private val minSampleSize: Int =
        when (strategy) {
            Strategy.Impulse -> 2
            Strategy.Lsq2 -> 3
        }

    /**
     * A strategy used for velocity calculation. Each strategy has a different philosophy that could
     * result in notably different velocities than the others, so make careful choice or change of
     * strategy whenever you want to make one.
     */
    internal enum class Strategy {
        /**
         * Least squares strategy. Polynomial fit at degree 2. Note that the implementation of this
         * strategy currently supports only non-differential data points.
         */
        Lsq2,

        /**
         * Impulse velocity tracking strategy, that calculates velocity using the mathematical
         * relationship between kinetic energy and velocity.
         */
        Impulse,
    }

    // Circular buffer; current sample at index.
    private val samples: Array<DataPointAtTime?> = arrayOfNulls(HistorySize)
    private var index: Int = 0

    // Reusable arrays to avoid allocation inside calculateVelocity.
    private val reusableDataPointsArray = FloatArray(HistorySize)
    private val reusableTimeArray = FloatArray(HistorySize)

    // Reusable array to minimize allocations inside calculateLeastSquaresVelocity.
    private val reusableVelocityCoefficients = FloatArray(3)

    /**
     * Adds a data point for velocity calculation at a given time, [timeMillis]. The data ponit
     * represents an amount of a change in position (for differential data points), or an absolute
     * position (for non-differential data points). Whether or not the tracker handles differential
     * data points is decided by [isDataDifferential], which is set once and finally during the
     * construction of the tracker.
     *
     * Use the same units for the data points provided. For example, having some data points in `cm`
     * and some in `m` will result in incorrect velocity calculations, as this method (and the
     * tracker) has no knowledge of the units used.
     */
    fun addDataPoint(timeMillis: Long, dataPoint: Float) {
        index = (index + 1) % HistorySize
        samples.set(index, timeMillis, dataPoint)
    }

    /**
     * Computes the estimated velocity at the time of the last provided data point.
     *
     * The units of velocity will be `units/second`, where `units` is the units of the data points
     * provided via [addDataPoint].
     *
     * This can be expensive. Only call this when you need the velocity.
     */
    fun calculateVelocity(): Float {
        val dataPoints = reusableDataPointsArray
        val time = reusableTimeArray
        var sampleCount = 0
        var index: Int = index

        // The sample at index is our newest sample.  If it is null, we have no samples so return.
        val newestSample: DataPointAtTime = samples[index] ?: return 0f

        var previousSample: DataPointAtTime = newestSample

        // Starting with the most recent PointAtTime sample, iterate backwards while
        // the samples represent continuous motion.
        do {
            val sample: DataPointAtTime = samples[index] ?: break

            val age: Float = (newestSample.time - sample.time).toFloat()
            val delta: Float = abs(sample.time - previousSample.time).toFloat()
            previousSample =
                if (strategy == Strategy.Lsq2 || isDataDifferential) {
                    sample
                } else {
                    newestSample
                }
            if (age > HorizonMilliseconds || delta > AssumePointerMoveStoppedMilliseconds) {
                break
            }

            dataPoints[sampleCount] = sample.dataPoint
            time[sampleCount] = -age
            index = (if (index == 0) HistorySize else index) - 1

            sampleCount += 1
        } while (sampleCount < HistorySize)

        if (sampleCount >= minSampleSize) {
            // Choose computation logic based on strategy.
            return when (strategy) {
                Strategy.Impulse -> {
                    calculateImpulseVelocity(dataPoints, time, sampleCount, isDataDifferential)
                }
                Strategy.Lsq2 -> {
                    calculateLeastSquaresVelocity(dataPoints, time, sampleCount)
                }
            } * 1000 // Multiply by "1000" to convert from units/ms to units/s
        }

        // We're unable to make a velocity estimate but we did have at least one
        // valid pointer position.
        return 0f
    }

    /**
     * Computes the estimated velocity at the time of the last provided data point.
     *
     * The method allows specifying the maximum absolute value for the calculated velocity. If the
     * absolute value of the calculated velocity exceeds the specified maximum, the return value
     * will be clamped down to the maximum. For example, if the absolute maximum velocity is
     * specified as "20", a calculated velocity of "25" will be returned as "20", and a velocity of
     * "-30" will be returned as "-20".
     *
     * @param maximumVelocity the absolute value of the maximum velocity to be returned in
     *   units/second, where `units` is the units of the positions provided to this VelocityTracker.
     */
    fun calculateVelocity(maximumVelocity: Float): Float {
        checkPrecondition(maximumVelocity > 0f) {
            "maximumVelocity should be a positive value. You specified=$maximumVelocity"
        }
        val velocity = calculateVelocity()

        return if (velocity == 0.0f || velocity.isNaN()) {
            0.0f
        } else if (velocity > 0) {
            velocity.coerceAtMost(maximumVelocity)
        } else {
            velocity.coerceAtLeast(-maximumVelocity)
        }
    }

    /** Clears data points added by [addDataPoint]. */
    fun resetTracking() {
        samples.fill(element = null)
        index = 0
    }

    /**
     * Calculates velocity based on [Strategy.Lsq2]. The provided [time] entries are in "ms", and
     * should be provided in reverse chronological order. The returned velocity is in "units/ms",
     * where "units" is unit of the [dataPoints].
     */
    private fun calculateLeastSquaresVelocity(
        dataPoints: FloatArray,
        time: FloatArray,
        sampleCount: Int,
    ): Float {
        // The 2nd coefficient is the derivative of the quadratic polynomial at
        // x = 0, and that happens to be the last timestamp that we end up
        // passing to polyFitLeastSquares.
        return try {
            polyFitLeastSquares(time, dataPoints, sampleCount, 2, reusableVelocityCoefficients)[1]
        } catch (exception: IllegalArgumentException) {
            0f
        }
    }
}

/**
 * Extension to simplify either creating a new [DataPointAtTime] at an array index (if the index was
 * never populated), or to update an existing [DataPointAtTime] (if the index had an existing
 * element). This helps to have zero allocations on average, and avoid performance hit that can be
 * caused by creating lots of objects.
 */
private fun Array<DataPointAtTime?>.set(index: Int, time: Long, dataPoint: Float) {
    val currentEntry = this[index]
    if (currentEntry == null) {
        this[index] = DataPointAtTime(time, dataPoint)
    } else {
        currentEntry.time = time
        currentEntry.dataPoint = dataPoint
    }
}

internal data class DataPointAtTime(var time: Long, var dataPoint: Float)

/**
 * TODO (shepshapard): If we want to support varying weights for each position, we could accept a
 * 3rd FloatArray of weights for each point and use them instead of the [DefaultWeight].
 */
private const val DefaultWeight = 1f

/**
 * Fits a polynomial of the given degree to the data points.
 *
 * If the [degree] is larger than or equal to the number of points, a polynomial will be returned
 * with coefficients of the value 0 for all degrees larger than or equal to the number of points.
 * For example, if 2 data points are provided and a quadratic polynomial (degree of 2) is requested,
 * the resulting polynomial ax^2 + bx + c is guaranteed to have a = 0;
 *
 * Throws an IllegalArgumentException if:
 * <ul>
 * <li>[degree] is not a positive integer.
 * <li>[sampleCount] is zero.
 * </ul>
 */
internal fun polyFitLeastSquares(
    /** The x-coordinates of each data point. */
    x: FloatArray,
    /** The y-coordinates of each data point. */
    y: FloatArray,
    /** number of items in each array */
    sampleCount: Int,
    degree: Int,
    coefficients: FloatArray = FloatArray((degree + 1).coerceAtLeast(0)),
): FloatArray {
    if (degree < 1) {
        throwIllegalArgumentException("The degree must be at positive integer")
    }
    if (sampleCount == 0) {
        throwIllegalArgumentException("At least one point must be provided")
    }

    val truncatedDegree =
        if (degree >= sampleCount) {
            sampleCount - 1
        } else {
            degree
        }

    // Shorthands for the purpose of notation equivalence to original C++ code.
    val m = sampleCount
    val n = truncatedDegree + 1

    // Expand the X vector to a matrix A, pre-multiplied by the weights.
    val a = Matrix(n, m)
    for (h in 0 until m) {
        a[0, h] = DefaultWeight
        for (i in 1 until n) {
            a[i, h] = a[i - 1, h] * x[h]
        }
    }

    // Apply the Gram-Schmidt process to A to obtain its QR decomposition.

    // Orthonormal basis, column-major order.
    val q = Matrix(n, m)
    // Upper triangular matrix, row-major order.
    val r = Matrix(n, n)
    for (j in 0 until n) {
        val w = q[j]
        a[j].copyInto(w, 0, 0, m)

        for (i in 0 until j) {
            val z = q[i]
            val dot = w.dot(z)
            for (h in 0 until m) {
                w[h] -= dot * z[h]
            }
        }

        val inverseNorm = 1.0f / w.norm().fastCoerceAtLeast(1e-6f)
        for (h in 0 until m) {
            w[h] *= inverseNorm
        }

        val v = r[j]
        for (i in 0 until n) {
            v[i] = if (i < j) 0.0f else w.dot(a[i])
        }
    }

    // Solve R B = Qt W Y to find B. This is easy because R is upper triangular.
    // We just work from bottom-right to top-left calculating B's coefficients.
    var wy = y

    // NOTE: DefaultWeight is currently always set to 1.0f, there's no need to allocate a new
    // array and to perform several multiplications for no reason
    @Suppress("KotlinConstantConditions")
    if (DefaultWeight != 1.0f) {
        // TODO: Even when we pass the test above, this allocation is likely unnecessary.
        // We could just modify wy (y) in place instead. This would need to be documented
        // to avoid surprises for the caller though.
        wy = FloatArray(m)
        for (h in 0 until m) {
            wy[h] = y[h] * DefaultWeight
        }
    }

    for (i in n - 1 downTo 0) {
        var c = q[i].dot(wy)
        val ri = r[i]
        for (j in n - 1 downTo i + 1) {
            c -= ri[j] * coefficients[j]
        }
        coefficients[i] = c / ri[i]
    }

    return coefficients
}

/**
 * Calculates velocity based on the Impulse strategy. The provided [time] entries are in "ms", and
 * should be provided in reverse chronological order. The returned velocity is in "units/ms", where
 * "units" is unit of the [dataPoints].
 *
 * Calculates the resulting velocity based on the total immpulse provided by the data ponits.
 *
 * The moving object in these calculations is the touchscreen (if we are calculating touch
 * velocity), or any input device from which the data points are generated. We refer to this object
 * as the "subject" below.
 *
 * Initial condition is discussed below, but for now suppose that v(t=0) = 0
 *
 * The kinetic energy of the object at the release is E=0.5*m*v^2 Then vfinal = sqrt(2E/m). The goal
 * is to calculate E.
 *
 * The kinetic energy at the release is equal to the total work done on the object by the finger.
 * The total work W is the sum of all dW along the path.
 *
 * dW = F*dx, where dx is the piece of path traveled. Force is change of momentum over time, F =
 * dp/dt = m dv/dt. Then substituting: dW = m (dv/dt) * dx = m * v * dv
 *
 * Summing along the path, we get: W = sum(dW) = sum(m * v * dv) = m * sum(v * dv) Since the mass
 * stays constant, the equation for final velocity is: vfinal = sqrt(2*sum(v * dv))
 *
 * Here, dv : change of velocity = `(v[i+1]-v[i])` dx : change of distance = `(x[i+1]-x[i])` dt :
 * change of time = `(t[i+1]-t[i])` v : instantaneous velocity = dx/dt
 *
 * The final formula is: vfinal = `sqrt(2) * sqrt(sum((v[i]-v[i-1])*|v[i]|))` for all i The absolute
 * value is needed to properly account for the sign. If the velocity over a particular segment
 * descreases, then this indicates braking, which means that negative work was done. So for two
 * positive, but decreasing, velocities, this contribution would be negative and will cause a
 * smaller final velocity.
 *
 * Initial condition There are two ways to deal with initial condition:
 * 1) Assume that v(0) = 0, which would mean that the subject is initially at rest. This is not
 *    entirely accurate. We are only taking the past X ms of touch data, where X is currently equal
 *    to 100. However, a touch event that created a fling probably lasted for longer than that,
 *    which would mean that the user has already been interacting with the subject, and it has
 *    probably already been moving.
 * 2) Assume that the subject has already been moving at a certain velocity, calculate this initial
 *    velocity and the equivalent energy, and start with this initial energy. Consider an example
 *    where we have the following data, consisting of 3 points: time: t0, t1, t2 x : x0, x1, x2 v :
 *    0, v1, v2 Here is what will happen in each of these scenarios:
 * 1) By directly applying the formula above with the v(0) = 0 boundary condition, we will get
 *    vfinal = sqrt(2*(|v1|*(v1-v0) + |v2|*(v2-v1))). This can be simplified since v0=0 vfinal =
 *    sqrt(2*(|v1|*v1 + |v2|*(v2-v1))) = sqrt(2*(v1^2 + |v2|*(v2 - v1))) since velocity is a real
 *    number
 * 2) If we treat the subject as already moving, then it must already have an energy (per mass)
 *    equal to 1/2*v1^2. Then the initial energy should be 1/2*v1*2, and only the second segment
 *    will contribute to the total kinetic energy (since we can effectively consider that v0=v1).
 *    This will give the following expression for the final velocity: vfinal = sqrt(2*(1/2*v1^2 +
 *    |v2|*(v2-v1))) This analysis can be generalized to an arbitrary number of samples.
 *
 * Comparing the two equations above, we see that the only mathematical difference is the factor of
 * 1/2 in front of the first velocity term. This boundary condition would allow for the "proper"
 * calculation of the case when all of the samples are equally spaced in time and distance, which
 * should suggest a constant velocity.
 *
 * Note that approach 2) is sensitive to the proper ordering of the data in time, since the boundary
 * condition must be applied to the oldest sample to be accurate.
 *
 * NOTE: [sampleCount] MUST be >= 2
 */
private fun calculateImpulseVelocity(
    dataPoints: FloatArray,
    time: FloatArray,
    sampleCount: Int,
    isDataDifferential: Boolean,
): Float {
    var work = 0f
    val start = sampleCount - 1
    var nextTime = time[start]
    for (i in start downTo 1) {
        val currentTime = nextTime
        nextTime = time[i - 1]
        if (currentTime == nextTime) {
            continue
        }
        val dataPointsDelta =
            if (isDataDifferential) -dataPoints[i - 1] else dataPoints[i] - dataPoints[i - 1]
        val vCurr = dataPointsDelta / (currentTime - nextTime)
        val vPrev = kineticEnergyToVelocity(work)
        work += (vCurr - vPrev) * abs(vCurr)
        if (i == start) {
            work = (work * 0.5f)
        }
    }
    return kineticEnergyToVelocity(work)
}

/**
 * Calculates the velocity for a given [kineticEnergy], using the formula: Kinetic Energy = 0.5 *
 * mass * (velocity)^2 where a mass of "1" is used.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun kineticEnergyToVelocity(kineticEnergy: Float): Float {
    return sign(kineticEnergy) * sqrt(2 * abs(kineticEnergy))
}

private typealias Vector = FloatArray

private fun FloatArray.dot(a: FloatArray): Float {
    var result = 0.0f
    for (i in indices) {
        result += this[i] * a[i]
    }
    return result
}

@Suppress("NOTHING_TO_INLINE") private inline fun FloatArray.norm(): Float = sqrt(this.dot(this))

private typealias Matrix = Array<FloatArray>

@Suppress("NOTHING_TO_INLINE")
private inline fun Matrix(rows: Int, cols: Int) = Array(rows) { Vector(cols) }

@Suppress("NOTHING_TO_INLINE")
private inline operator fun Matrix.get(row: Int, col: Int): Float = this[row][col]

@Suppress("NOTHING_TO_INLINE")
private inline operator fun Matrix.set(row: Int, col: Int, value: Float) {
    this[row][col] = value
}

/**
 * A flag to indicate that we'll use the fix of how we add points to the velocity tracker.
 *
 * This is an experiment flag and will be removed once the experiments with the fix a finished. The
 * final goal is that we will use the true path once the flag is removed. If you find any issues
 * with the new fix, flip this flag to false to confirm they are newly introduced then file a bug.
 * Tracking bug: (b/318621681)
 */
@Suppress("GetterSetterNames")
var VelocityTrackerAddPointsFix: Boolean = true

@RequiresOptIn(
    "This an opt-in flag to test the Velocity Tracker strategy algorithm used " +
        "for calculating gesture velocities in Compose."
)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalVelocityTrackerApi
