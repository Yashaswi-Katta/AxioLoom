package com.axioloom.app.domain.stats

import kotlin.math.*

data class DataPoint(val x: Double, val y: Double)

data class LinearRegressionResult(
    val slope: Double,
    val intercept: Double,
    val r: Double,
    val rSquared: Double,
    val rmse: Double,
    val equationLatex: String
)

data class PolynomialRegressionResult(
    val degree: Int,
    val coefficients: List<Double>, // [a0, a1, a2, ...] where y = sum_k a_k * x^k
    val rSquared: Double,
    val rmse: Double,
    val equationLatex: String
)

data class ExponentialRegressionResult(
    val a: Double,
    val b: Double,
    val rSquared: Double,
    val rmse: Double,
    val equationLatex: String
)

data class DescriptiveStats(
    val count: Int,
    val mean: Double,
    val variance: Double,
    val stdev: Double,
    val min: Double,
    val max: Double,
    val median: Double
)

/**
 * Pure Kotlin statistical regression and curve-fitting engine.
 * Computes exact closed-form linear, polynomial (Vandermonde normal equations via Gaussian elimination),
 * and exponential fits with R^2 and RMSE error metrics.
 */
object DataRegressionEngine {

    fun linearRegression(points: List<DataPoint>): LinearRegressionResult {
        require(points.size >= 2) { "Linear regression requires at least 2 points" }
        val n = points.size.toDouble()

        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0

        for (pt in points) {
            sumX += pt.x
            sumY += pt.y
            sumXY += pt.x * pt.y
            sumX2 += pt.x * pt.x
            sumY2 += pt.y * pt.y
        }

        val denom = n * sumX2 - sumX * sumX
        val slope = if (abs(denom) > 1e-12) (n * sumXY - sumX * sumY) / denom else 0.0
        val intercept = (sumY - slope * sumX) / n

        // Pearson r
        val rDenom = sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY))
        val r = if (abs(rDenom) > 1e-12) ((n * sumXY - sumX * sumY) / rDenom).coerceIn(-1.0, 1.0) else 1.0
        val r2 = r * r

        // RMSE
        var sse = 0.0
        for (pt in points) {
            val pred = slope * pt.x + intercept
            sse += (pt.y - pred) * (pt.y - pred)
        }
        val rmse = sqrt(sse / n)

        val sign = if (intercept >= 0.0) "+" else "-"
        val eq = "y = ${"%.4f".format(slope)} x $sign ${"%.4f".format(abs(intercept))}"

        return LinearRegressionResult(
            slope = slope,
            intercept = intercept,
            r = r,
            rSquared = r2,
            rmse = rmse,
            equationLatex = eq
        )
    }

    /**
     * Polynomial least squares of degree d (Vandermonde normal equations: (X^T X) a = X^T y).
     * Solved via Gaussian elimination with partial pivoting.
     */
    fun polynomialRegression(points: List<DataPoint>, degree: Int = 2): PolynomialRegressionResult {
        require(degree in 1..4) { "Degree must be between 1 and 4" }
        require(points.size > degree) { "Need at least ${degree + 1} points for degree $degree fit" }

        val m = degree + 1
        // Normal matrix A = X^T X (size m x m)
        val A = Array(m) { DoubleArray(m) }
        // Vector B = X^T y (size m)
        val B = DoubleArray(m)

        for (i in 0 until m) {
            for (j in 0 until m) {
                var sumXPower = 0.0
                for (pt in points) {
                    sumXPower += pt.x.pow(i + j)
                }
                A[i][j] = sumXPower
            }

            var sumXYPower = 0.0
            for (pt in points) {
                sumXYPower += pt.y * pt.x.pow(i)
            }
            B[i] = sumXYPower
        }

        // Solve A a = B via Gaussian Elimination with Partial Pivoting
        val coeffs = solveLinearSystem(A, B)

        // Compute R^2 and RMSE
        var sse = 0.0
        var sst = 0.0
        val meanY = points.map { it.y }.average()

        for (pt in points) {
            var pred = 0.0
            for (k in 0 until m) {
                pred += coeffs[k] * pt.x.pow(k)
            }
            sse += (pt.y - pred) * (pt.y - pred)
            sst += (pt.y - meanY) * (pt.y - meanY)
        }
        val rmse = sqrt(sse / points.size)
        val r2 = if (sst > 1e-12) (1.0 - sse / sst).coerceIn(0.0, 1.0) else 1.0

        // Format LaTeX equation: y = a_d x^d + ... + a_1 x + a_0
        val terms = mutableListOf<String>()
        for (k in degree downTo 0) {
            val c = coeffs[k]
            if (abs(c) > 1e-6 || k == 0) {
                val cStr = "%.4f".format(c)
                when (k) {
                    0 -> terms.add(cStr)
                    1 -> terms.add("$cStr x")
                    else -> terms.add("$cStr x^$k")
                }
            }
        }
        val eq = "y = " + (if (terms.isNotEmpty()) terms.joinToString(" + ").replace("+ -", "- ") else "0")

        return PolynomialRegressionResult(
            degree = degree,
            coefficients = coeffs.toList(),
            rSquared = r2,
            rmse = rmse,
            equationLatex = eq
        )
    }

    /**
     * Exponential fit: y = A * e^(B * x) => ln(y) = ln(A) + B * x
     */
    fun exponentialRegression(points: List<DataPoint>): ExponentialRegressionResult {
        val validPoints = points.filter { it.y > 0.0 }
        require(validPoints.size >= 2) { "Exponential fit requires at least 2 points with y > 0" }

        val logPoints = validPoints.map { DataPoint(it.x, ln(it.y)) }
        val linFit = linearRegression(logPoints)

        val b = linFit.slope
        val a = exp(linFit.intercept)

        var sse = 0.0
        var sst = 0.0
        val meanY = validPoints.map { it.y }.average()

        for (pt in validPoints) {
            val pred = a * exp(b * pt.x)
            sse += (pt.y - pred) * (pt.y - pred)
            sst += (pt.y - meanY) * (pt.y - meanY)
        }
        val rmse = sqrt(sse / validPoints.size)
        val r2 = if (sst > 1e-12) (1.0 - sse / sst).coerceIn(0.0, 1.0) else 1.0

        val eq = "y = ${"%.4f".format(a)} e^{${"%.4f".format(b)} x}"

        return ExponentialRegressionResult(
            a = a,
            b = b,
            rSquared = r2,
            rmse = rmse,
            equationLatex = eq
        )
    }

    fun descriptiveStats(values: List<Double>): DescriptiveStats {
        if (values.isEmpty()) return DescriptiveStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val count = values.size
        val mean = values.average()
        val variance = if (count > 1) values.sumOf { (it - mean) * (it - mean) } / (count - 1) else 0.0
        val stdev = sqrt(variance)
        val sorted = values.sorted()
        val median = if (count % 2 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0

        return DescriptiveStats(
            count = count,
            mean = mean,
            variance = variance,
            stdev = stdev,
            min = sorted.first(),
            max = sorted.last(),
            median = median
        )
    }

    /**
     * Gaussian elimination with partial pivoting solving A x = B.
     */
    private fun solveLinearSystem(A: Array<DoubleArray>, B: DoubleArray): DoubleArray {
        val n = B.size
        val a = Array(n) { i -> A[i].clone() }
        val b = B.clone()

        for (p in 0 until n) {
            // Find pivot
            var maxIdx = p
            for (i in p + 1 until n) {
                if (abs(a[i][p]) > abs(a[maxIdx][p])) {
                    maxIdx = i
                }
            }

            // Swap rows
            val tempRow = a[p]
            a[p] = a[maxIdx]
            a[maxIdx] = tempRow

            val tempB = b[p]
            b[p] = b[maxIdx]
            b[maxIdx] = tempB

            if (abs(a[p][p]) < 1e-12) continue

            // Pivot within a and b
            for (i in p + 1 until n) {
                val alpha = a[i][p] / a[p][p]
                b[i] -= alpha * b[p]
                for (j in p until n) {
                    a[i][j] -= alpha * a[p][j]
                }
            }
        }

        // Back substitution
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = 0.0
            for (j in i + 1 until n) {
                sum += a[i][j] * x[j]
            }
            x[i] = if (abs(a[i][i]) > 1e-12) (b[i] - sum) / a[i][i] else 0.0
        }
        return x
    }
}
