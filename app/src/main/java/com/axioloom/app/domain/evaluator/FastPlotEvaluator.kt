package com.axioloom.app.domain.evaluator

import com.axioloom.app.domain.model.MathExpr
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * High-performance, allocation-free AST numeric evaluator for 60fps function plotting.
 * Evaluates in < 2ms without invoking the offline CAS engine.
 */
object FastPlotEvaluator {

    fun evaluate(
        expr: MathExpr,
        params: Map<String, Double> = emptyMap(),
        bindings: Map<String, Double> = emptyMap()
    ): Double = when (expr) {
        is MathExpr.Number -> expr.value.toDoubleOrNull() ?: Double.NaN
        is MathExpr.Constant -> when (expr) {
            MathExpr.Constant.PI -> Math.PI
            MathExpr.Constant.E -> Math.E
            MathExpr.Constant.INFINITY -> Double.POSITIVE_INFINITY
            MathExpr.Constant.I -> Double.NaN
        }
        is MathExpr.Symbol -> params[expr.name] ?: bindings[expr.name] ?: Double.NaN
        is MathExpr.Unary -> {
            val v = evaluate(expr.value, params, bindings)
            if (expr.negative) -v else v
        }
        is MathExpr.Binary -> {
            val left = evaluate(expr.left, params, bindings)
            val right = evaluate(expr.right, params, bindings)
            if (left.isNaN() || right.isNaN()) Double.NaN else when (expr.op) {
                MathExpr.Op.ADD -> left + right
                MathExpr.Op.SUBTRACT -> left - right
                MathExpr.Op.MULTIPLY -> left * right
                MathExpr.Op.DIVIDE -> if (right == 0.0) Double.NaN else left / right
                MathExpr.Op.POWER -> {
                    if (left < 0.0 && right != right.roundToInt().toDouble()) Double.NaN
                    else left.pow(right)
                }
                MathExpr.Op.PARALLEL -> if (left + right == 0.0) Double.NaN else (left * right) / (left + right)
            }
        }
        is MathExpr.Builtin -> {
            val arg = if (expr.arguments.isNotEmpty()) evaluate(expr.arguments[0], params, bindings) else Double.NaN
            if (arg.isNaN()) Double.NaN else when (expr.function) {
                MathExpr.Function.SIN -> sin(arg)
                MathExpr.Function.COS -> cos(arg)
                MathExpr.Function.TAN -> tan(arg)
                MathExpr.Function.ASIN -> if (arg in -1.0..1.0) asin(arg) else Double.NaN
                MathExpr.Function.ACOS -> if (arg in -1.0..1.0) acos(arg) else Double.NaN
                MathExpr.Function.ATAN -> atan(arg)
                MathExpr.Function.SINH -> sinh(arg)
                MathExpr.Function.COSH -> cosh(arg)
                MathExpr.Function.TANH -> tanh(arg)
                MathExpr.Function.LOG -> if (arg > 0.0) ln(arg) else Double.NaN
                MathExpr.Function.EXP -> exp(arg)
                MathExpr.Function.SQRT -> if (arg >= 0.0) sqrt(arg) else Double.NaN
                MathExpr.Function.ABS -> abs(arg)
                MathExpr.Function.FACTORIAL -> factorial(arg)
                MathExpr.Function.RE -> arg
                MathExpr.Function.IM -> 0.0
                MathExpr.Function.CONJUGATE -> arg
                MathExpr.Function.ARG -> if (arg >= 0.0) 0.0 else Math.PI
                MathExpr.Function.UNIT_STEP -> if (arg >= 0.0) 1.0 else 0.0
                MathExpr.Function.DIRAC_DELTA -> if (abs(arg) < 1e-9) Double.POSITIVE_INFINITY else 0.0
                MathExpr.Function.DET,
                MathExpr.Function.TRANSPOSE,
                MathExpr.Function.INVERSE,
                MathExpr.Function.EIGENVALUES,
                MathExpr.Function.EIGENVECTORS,
                MathExpr.Function.TRACE,
                MathExpr.Function.RANK,
                MathExpr.Function.RREF -> Double.NaN
            }
        }
        is MathExpr.Phasor -> Double.NaN
        is MathExpr.Call -> {
            val arg = if (expr.arguments.isNotEmpty()) evaluate(expr.arguments[0], params, bindings) else Double.NaN
            bindings[expr.function]?.let { it * arg } ?: Double.NaN
        }
        is MathExpr.TaylorSeries -> evaluate(expr.body, params, bindings)
        is MathExpr.Relational -> {
            val l = evaluate(expr.left, params, bindings)
            val r = evaluate(expr.right, params, bindings)
            if (l.isNaN() || r.isNaN()) Double.NaN else {
                val holds = when (expr.op) {
                    MathExpr.RelOp.LT -> l < r
                    MathExpr.RelOp.LE -> l <= r
                    MathExpr.RelOp.GT -> l > r
                    MathExpr.RelOp.GE -> l >= r
                    MathExpr.RelOp.EQ -> abs(l - r) < 1e-9
                    MathExpr.RelOp.NE -> abs(l - r) >= 1e-9
                }
                if (holds) 1.0 else 0.0
            }
        }
        is MathExpr.Otherwise -> 1.0
        is MathExpr.Piecewise -> {
            var result = Double.NaN
            for (branch in expr.branches) {
                val cond = evaluate(branch.condition, params, bindings)
                if (!cond.isNaN() && cond > 0.5) {
                    result = evaluate(branch.value, params, bindings)
                    break
                }
            }
            if (result.isNaN()) evaluate(expr.defaultExpr, params, bindings) else result
        }
        is MathExpr.Integral,
        is MathExpr.Derivative,
        is MathExpr.Sum,
        is MathExpr.Limit,
        is MathExpr.Matrix,
        is MathExpr.LaplaceTransform,
        is MathExpr.InverseLaplaceTransform,
        is MathExpr.ZTransform,
        is MathExpr.Gradient,
        is MathExpr.Divergence,
        is MathExpr.Curl,
        is MathExpr.DiscreteSequence,
        is MathExpr.DiscreteConvolution,
        is MathExpr.CircularConvolution,
        is MathExpr.DiscreteFourierTransform,
        is MathExpr.IndexedSequence -> Double.NaN
    }

    private fun factorial(value: Double): Double {
        val n = value.roundToInt()
        if (abs(value - n) > 1e-9 || n < 0 || n > 20) return Double.NaN
        var result = 1.0
        for (i in 2..n) {
            result *= i
        }
        return result
    }

    /**
     * Samples [sampleCount] points across domain [xMin] to [xMax].
     * Returns pairs of (x, y). Any invalid/infinite/NaN values produce Double.NaN for y.
     */
    fun sample(
        expr: MathExpr,
        paramName: String,
        xMin: Double,
        xMax: Double,
        sampleCount: Int = 160,
        bindings: Map<String, Double> = emptyMap()
    ): List<Pair<Double, Double>> {
        require(sampleCount >= 2)
        require(xMin < xMax)
        val step = (xMax - xMin) / (sampleCount - 1)
        val result = ArrayList<Pair<Double, Double>>(sampleCount)
        val params = HashMap<String, Double>(1)
        for (i in 0 until sampleCount) {
            val x = xMin + i * step
            params[paramName] = x
            val y = evaluate(expr, params, bindings)
            result.add(x to if (y.isFinite()) y else Double.NaN)
        }
        return result
    }

    /**
     * Samples 360 points across theta in [0, 2pi] for polar curves r(theta).
     * Returns (x, y) coordinates where x = r*cos(theta), y = r*sin(theta).
     */
    fun samplePolar(
        expr: MathExpr,
        paramName: String = "theta",
        sampleCount: Int = 360,
        bindings: Map<String, Double> = emptyMap()
    ): List<Pair<Double, Double>> {
        val step = (2.0 * Math.PI) / sampleCount
        val result = ArrayList<Pair<Double, Double>>(sampleCount + 1)
        for (i in 0..sampleCount) {
            val theta = i * step
            val r = evaluate(expr, mapOf(paramName to theta, "theta" to theta, "t" to theta), bindings)
            val x = if (r.isFinite()) r * cos(theta) else Double.NaN
            val y = if (r.isFinite()) r * sin(theta) else Double.NaN
            result.add(x to y)
        }
        return result
    }

    /**
     * Samples parametric trajectory (x(t), y(t)) for t in [tMin, tMax].
     */
    fun sampleParametric(
        xExpr: MathExpr,
        yExpr: MathExpr,
        paramName: String = "t",
        tMin: Double = 0.0,
        tMax: Double = 2.0 * Math.PI,
        sampleCount: Int = 200,
        bindings: Map<String, Double> = emptyMap()
    ): List<Pair<Double, Double>> {
        val step = (tMax - tMin) / sampleCount
        val result = ArrayList<Pair<Double, Double>>(sampleCount + 1)
        for (i in 0..sampleCount) {
            val t = tMin + i * step
            val x = evaluate(xExpr, mapOf(paramName to t), bindings)
            val y = evaluate(yExpr, mapOf(paramName to t), bindings)
            result.add(x to y)
        }
        return result
    }

    data class SurfacePoint3D(val x: Double, val y: Double, val z: Double)
    data class SurfaceGridData(
        val vertices: List<SurfacePoint3D>,
        val steps: Int,
        val zMin: Double,
        val zMax: Double
    )

    fun sampleSurface(
        expr: MathExpr,
        var1: String = "x",
        var2: String = "y",
        range1: ClosedFloatingPointRange<Double> = -5.0..5.0,
        range2: ClosedFloatingPointRange<Double> = -5.0..5.0,
        steps: Int = 36,
        bindings: Map<String, Double> = emptyMap()
    ): SurfaceGridData {
        val dx = (range1.endInclusive - range1.start) / (steps - 1)
        val dy = (range2.endInclusive - range2.start) / (steps - 1)
        val points = ArrayList<SurfacePoint3D>(steps * steps)
        var minZ = Double.POSITIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        val params = HashMap<String, Double>(2)

        for (i in 0 until steps) {
            val x = range1.start + i * dx
            params[var1] = x
            for (j in 0 until steps) {
                val y = range2.start + j * dy
                params[var2] = y
                val rawZ = evaluate(expr, params, bindings)
                val z = if (rawZ.isFinite()) rawZ.coerceIn(-50.0, 50.0) else 0.0
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z
                points.add(SurfacePoint3D(x, y, z))
            }
        }
        if (minZ.isInfinite() || maxZ.isInfinite() || minZ == maxZ) {
            minZ = -1.0; maxZ = 1.0
        }
        return SurfaceGridData(points, steps, minZ, maxZ)
    }

    data class VectorFieldPoint(val x: Double, val y: Double, val u: Double, val v: Double, val magnitude: Double)

    fun sampleVectorField(
        pExpr: MathExpr,
        qExpr: MathExpr,
        xRange: ClosedFloatingPointRange<Double> = -5.0..5.0,
        yRange: ClosedFloatingPointRange<Double> = -5.0..5.0,
        gridSize: Int = 15,
        bindings: Map<String, Double> = emptyMap()
    ): List<VectorFieldPoint> {
        val dx = (xRange.endInclusive - xRange.start) / (gridSize - 1)
        val dy = (yRange.endInclusive - yRange.start) / (gridSize - 1)
        val result = ArrayList<VectorFieldPoint>(gridSize * gridSize)
        val params = HashMap<String, Double>(2)

        for (i in 0 until gridSize) {
            val x = xRange.start + i * dx
            params["x"] = x
            for (j in 0 until gridSize) {
                val y = yRange.start + j * dy
                params["y"] = y
                val u = evaluate(pExpr, params, bindings).let { if (it.isFinite()) it else 0.0 }
                val v = evaluate(qExpr, params, bindings).let { if (it.isFinite()) it else 0.0 }
                val mag = kotlin.math.hypot(u, v)
                result.add(VectorFieldPoint(x, y, u, v, mag))
            }
        }
        return result
    }
}
