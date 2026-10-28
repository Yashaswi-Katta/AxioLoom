package com.axioloom.app.domain.sim

import kotlin.math.*

data class OdeTrajectory(
    val timePoints: DoubleArray,
    val states: List<DoubleArray>, // states[k] is DoubleArray of size N at timePoints[k]
    val variableNames: List<String>
) {
    val stateCount: Int get() = variableNames.size
    val stepCount: Int get() = timePoints.size

    fun getSeries(variableIndex: Int): DoubleArray {
        require(variableIndex in 0 until stateCount)
        return DoubleArray(stepCount) { states[it][variableIndex] }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OdeTrajectory) return false
        return timePoints.contentEquals(other.timePoints) &&
               variableNames == other.variableNames &&
               states.size == other.states.size &&
               states.indices.all { states[it].contentEquals(other.states[it]) }
    }

    override fun hashCode(): Int {
        var result = timePoints.contentHashCode()
        result = 31 * result + variableNames.hashCode()
        result = 31 * result + states.fold(0) { acc, arr -> 31 * acc + arr.contentHashCode() }
        return result
    }
}

/**
 * 100% Offline Adaptive Dormand-Prince 5(4) (RK45) Non-Linear ODE & State-Space Integrator.
 */
object OdeNumericalSimulator {

    // Dormand-Prince 5(4) Butcher Tableau constants
    private val C = doubleArrayOf(0.0, 1.0 / 5.0, 3.0 / 10.0, 4.0 / 5.0, 8.0 / 9.0, 1.0, 1.0)

    private val A21 = 1.0 / 5.0

    private val A31 = 3.0 / 40.0
    private val A32 = 9.0 / 40.0

    private val A41 = 44.0 / 45.0
    private val A42 = -56.0 / 15.0
    private val A43 = 32.0 / 9.0

    private val A51 = 19372.0 / 6561.0
    private val A52 = -25360.0 / 2187.0
    private val A53 = 64448.0 / 6561.0
    private val A54 = -212.0 / 729.0

    private val A61 = 9017.0 / 3168.0
    private val A62 = -355.0 / 33.0
    private val A63 = 46732.0 / 5247.0
    private val A64 = 49.0 / 176.0
    private val A65 = -5103.0 / 18656.0

    private val A71 = 35.0 / 384.0
    private val A73 = 500.0 / 1113.0
    private val A74 = 125.0 / 192.0
    private val A75 = -2187.0 / 6784.0
    private val A76 = 11.0 / 84.0

    // 5th order weights (same as A7)
    private val B1 = 35.0 / 384.0
    private val B3 = 500.0 / 1113.0
    private val B4 = 125.0 / 192.0
    private val B5 = -2187.0 / 6784.0
    private val B6 = 11.0 / 84.0

    // Difference coefficients for error estimation (b - b_hat)
    private val E1 = 71.0 / 57600.0
    private val E3 = -71.0 / 16695.0
    private val E4 = 71.0 / 1920.0
    private val E5 = -17253.0 / 339200.0
    private val E6 = 22.0 / 525.0
    private val E7 = -1.0 / 40.0

    /**
     * Solves dX/dt = f(t, X) from t = 0 to t = tMax using adaptive RK45.
     */
    fun integrate(
        f: (Double, DoubleArray) -> DoubleArray,
        initialState: DoubleArray,
        tMax: Double = 10.0,
        t0: Double = 0.0,
        variableNames: List<String> = List(initialState.size) { "x_${it + 1}" },
        rtol: Double = 1e-5,
        atol: Double = 1e-7,
        maxSteps: Int = 10000,
        initialStepSize: Double = 0.01
    ): OdeTrajectory {
        val n = initialState.size
        var t = t0
        var y = initialState.clone()
        var h = max(1e-6, min(initialStepSize, (tMax - t0) / 10.0))

        val timePoints = ArrayList<Double>(1000)
        val states = ArrayList<DoubleArray>(1000)

        timePoints.add(t)
        states.add(y.clone())

        val yStage = DoubleArray(n)
        var k1 = f(t, y)

        var stepCount = 0
        while (t < tMax && stepCount < maxSteps) {
            stepCount++
            if (t + h > tMax) {
                h = tMax - t
            }

            // Stage 2
            for (i in 0 until n) yStage[i] = y[i] + h * (A21 * k1[i])
            val k2 = f(t + C[1] * h, yStage)

            // Stage 3
            for (i in 0 until n) yStage[i] = y[i] + h * (A31 * k1[i] + A32 * k2[i])
            val k3 = f(t + C[2] * h, yStage)

            // Stage 4
            for (i in 0 until n) yStage[i] = y[i] + h * (A41 * k1[i] + A42 * k2[i] + A43 * k3[i])
            val k4 = f(t + C[3] * h, yStage)

            // Stage 5
            for (i in 0 until n) yStage[i] = y[i] + h * (A51 * k1[i] + A52 * k2[i] + A53 * k3[i] + A54 * k4[i])
            val k5 = f(t + C[4] * h, yStage)

            // Stage 6
            for (i in 0 until n) yStage[i] = y[i] + h * (A61 * k1[i] + A62 * k2[i] + A63 * k3[i] + A64 * k4[i] + A65 * k5[i])
            val k6 = f(t + C[5] * h, yStage)

            // Stage 7 (FSAL) and 5th order candidate
            val yNext = DoubleArray(n)
            for (i in 0 until n) {
                yNext[i] = y[i] + h * (B1 * k1[i] + B3 * k3[i] + B4 * k4[i] + B5 * k5[i] + B6 * k6[i])
            }
            val k7 = f(t + h, yNext)

            // Error estimation
            var errSumSq = 0.0
            for (i in 0 until n) {
                val err_i = h * (E1 * k1[i] + E3 * k3[i] + E4 * k4[i] + E5 * k5[i] + E6 * k6[i] + E7 * k7[i])
                val scale = atol + rtol * max(abs(y[i]), abs(yNext[i]))
                val ratio = err_i / max(scale, 1e-15)
                errSumSq += ratio * ratio
            }
            val err = sqrt(errSumSq / n)

            // Step size adjustment
            val factor = if (err > 0.0) {
                0.9 * (1.0 / err).pow(0.2).coerceIn(0.2, 5.0)
            } else 2.0

            if (err <= 1.0 || h <= 1e-12) {
                // Accept step
                t += h
                y = yNext
                k1 = k7 // FSAL property: reuse k7 as k1 for next step
                timePoints.add(t)
                states.add(y.clone())
                h *= factor
            } else {
                // Reject step and retry with smaller h
                h *= factor
            }
        }

        return OdeTrajectory(
            timePoints = timePoints.toDoubleArray(),
            states = states,
            variableNames = variableNames
        )
    }

    /**
     * Presets for standard engineering systems.
     */
    fun harmonicOscillator(omega: Double = 1.0, y0: Double = 1.0, v0: Double = 0.0, tMax: Double = 2.0 * PI): OdeTrajectory {
        return integrate(
            f = { _, s -> doubleArrayOf(s[1], -omega * omega * s[0]) },
            initialState = doubleArrayOf(y0, v0),
            tMax = tMax,
            variableNames = listOf("y (pos)", "v (vel)")
        )
    }

    fun vanDerPol(mu: Double = 1.5, y0: Double = 0.5, v0: Double = 0.0, tMax: Double = 20.0): OdeTrajectory {
        return integrate(
            f = { _, s -> doubleArrayOf(s[1], mu * (1.0 - s[0] * s[0]) * s[1] - s[0]) },
            initialState = doubleArrayOf(y0, v0),
            tMax = tMax,
            variableNames = listOf("y (displacement)", "y' (velocity)")
        )
    }

    fun nonLinearPendulum(lengthM: Double = 1.0, damping: Double = 0.1, theta0: Double = 1.2, omega0: Double = 0.0, tMax: Double = 15.0): OdeTrajectory {
        val g = 9.80665
        return integrate(
            f = { _, s -> doubleArrayOf(s[1], -(g / lengthM) * sin(s[0]) - damping * s[1]) },
            initialState = doubleArrayOf(theta0, omega0),
            tMax = tMax,
            variableNames = listOf("θ (angle rad)", "ω (angular vel)")
        )
    }

    fun lorenzAttractor(sigma: Double = 10.0, rho: Double = 28.0, beta: Double = 8.0 / 3.0, tMax: Double = 25.0, x0: Double = 1.0): OdeTrajectory {
        return integrate(
            f = { _, s -> doubleArrayOf(
                sigma * (s[1] - s[0]),
                s[0] * (rho - s[2]) - s[1],
                s[0] * s[1] - beta * s[2]
            ) },
            initialState = doubleArrayOf(x0, 1.0, 1.0),
            tMax = tMax,
            variableNames = listOf("x", "y", "z")
        )
    }
}
