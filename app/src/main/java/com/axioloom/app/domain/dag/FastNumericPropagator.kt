package com.axioloom.app.domain.dag

import com.axioloom.app.domain.model.EquationBlock
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.MathIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.*

data class FastNumericResult(
    val numericValue: Double,
    val formattedPreview: String
)

/**
 * 60fps Fast Numeric DAG Propagator.
 * Bypasses the single-threaded Symja CAS engine during live parameter slider dragging
 * by evaluating dependent AST nodes numerically in topological order in < 0.2ms.
 * Supports standard arithmetic, powers, elementary functions, and numeric definite integrals
 * via 16-point Gauss-Legendre quadrature.
 */
object FastNumericPropagator {

    // 16-point Gauss-Legendre abscissae and weights on [-1, 1] (symmetric pairs)
    private val GAUSS_X = doubleArrayOf(
        0.09501250983763744,
        0.28160355077970637,
        0.4580167776572274,
        0.6178762444026438,
        0.7554044083550030,
        0.8656312023878318,
        0.9445750230732326,
        0.9894009349916499
    )

    private val GAUSS_W = doubleArrayOf(
        0.1894506104550685,
        0.1826034150449236,
        0.1691565193950025,
        0.1495959888165767,
        0.1246289712555339,
        0.0951585116824928,
        0.0622535239386479,
        0.0271524594117541
    )

    suspend fun propagate(
        graph: ReactiveGraphSnapshot,
        blocks: List<EquationBlock>,
        overrides: Map<String, Double>
    ): Map<UUID, FastNumericResult> = withContext(Dispatchers.Default) {
        val blockMap = blocks.associateBy { it.id }
        val bindings = mutableMapOf<String, Double>()
        val functions = blocks.mapNotNull { it.intent as? MathIntent.FunctionDef }.associateBy { it.funcName }

        // 1. Seed initial bindings from existing graph scalar results or evaluate rhs numerically
        graph.blocks.forEach { (id, blockState) ->
            val assignment = blockState.intent as? MathIntent.Assignment
            if (assignment != null) {
                val numVal = blockState.result?.expression?.toDoubleOrNull()
                if (numVal != null) {
                    bindings[assignment.variableName] = numVal
                } else {
                    val evaluated = evaluateExpr(assignment.rhs, bindings, functions)
                    if (!evaluated.isNaN()) {
                        bindings[assignment.variableName] = evaluated
                    }
                }
            }
        }

        // 2. Inject active parameter overrides
        bindings.putAll(overrides)

        val results = mutableMapOf<UUID, FastNumericResult>()

        // 3. Evaluate blocks in topological order
        val inDegree = mutableMapOf<UUID, Int>()
        val dependents = mutableMapOf<UUID, MutableList<UUID>>()
        val blockIdSet = blocks.map { it.id }.toSet()

        for (id in blockIdSet) {
            inDegree[id] = 0
        }

        for (id in blockIdSet) {
            val deps = graph.dependencies[id]?.filter { it in blockIdSet } ?: emptyList()
            inDegree[id] = deps.size
            for (dep in deps) {
                dependents.getOrPut(dep) { mutableListOf() }.add(id)
            }
        }

        val queue = ArrayDeque<UUID>()
        for ((id, deg) in inDegree) {
            if (deg == 0) queue.add(id)
        }

        val orderedIds = mutableListOf<UUID>()
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            orderedIds.add(curr)
            dependents[curr]?.forEach { dep ->
                val remaining = (inDegree[dep] ?: 1) - 1
                inDegree[dep] = remaining
                if (remaining == 0) {
                    queue.add(dep)
                }
            }
        }

        for (b in blocks) {
            if (b.id !in orderedIds) {
                orderedIds.add(b.id)
            }
        }

        for (id in orderedIds) {
            val block = blockMap[id] ?: continue
            val intent = block.intent ?: continue

            when (intent) {
                is MathIntent.Assignment -> {
                    val evaluated = if (intent.variableName in overrides) {
                        overrides.getValue(intent.variableName)
                    } else {
                        evaluateExpr(intent.rhs, bindings, functions)
                    }
                    if (!evaluated.isNaN()) {
                        bindings[intent.variableName] = evaluated
                        results[id] = FastNumericResult(evaluated, formatSignificant(evaluated, 4))
                    }
                }
                is MathIntent.EvaluationQuery -> {
                    val evaluated = evaluateExpr(intent.expression, bindings, functions)
                    if (!evaluated.isNaN()) {
                        results[id] = FastNumericResult(evaluated, formatSignificant(evaluated, 4))
                    }
                }
                else -> Unit
            }
        }

        results
    }

    fun evaluateExpr(
        expr: MathExpr,
        bindings: Map<String, Double>,
        functions: Map<String, MathIntent.FunctionDef> = emptyMap(),
        depth: Int = 0
    ): Double = when (expr) {
        is MathExpr.Number -> expr.value.toDoubleOrNull() ?: Double.NaN
        is MathExpr.Constant -> when (expr) {
            MathExpr.Constant.PI -> Math.PI
            MathExpr.Constant.E -> Math.E
            MathExpr.Constant.INFINITY -> Double.POSITIVE_INFINITY
            MathExpr.Constant.I -> Double.NaN
        }
        is MathExpr.Symbol -> bindings[expr.name] ?: Double.NaN
        is MathExpr.Unary -> {
            val v = evaluateExpr(expr.value, bindings, functions, depth)
            if (v.isNaN()) Double.NaN else if (expr.negative) -v else v
        }
        is MathExpr.Binary -> {
            val left = evaluateExpr(expr.left, bindings, functions, depth)
            val right = evaluateExpr(expr.right, bindings, functions, depth)
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
            val arg = if (expr.arguments.isNotEmpty()) evaluateExpr(expr.arguments[0], bindings, functions, depth) else Double.NaN
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
                MathExpr.Function.UNIT_STEP -> if (arg >= 0.0) 1.0 else 0.0
                MathExpr.Function.DIRAC_DELTA -> if (abs(arg) < 1e-9) Double.POSITIVE_INFINITY else 0.0
                else -> Double.NaN
            }
        }
        is MathExpr.Integral -> {
            if (expr.lower != null && expr.upper != null) {
                val a = evaluateExpr(expr.lower, bindings, functions, depth)
                val b = evaluateExpr(expr.upper, bindings, functions, depth)
                if (a.isNaN() || b.isNaN() || a == b) {
                    if (a == b) 0.0 else Double.NaN
                } else {
                    quadrature16(expr.body, expr.variable, a, b, bindings, functions, depth)
                }
            } else Double.NaN
        }
        is MathExpr.Relational -> {
            val l = evaluateExpr(expr.left, bindings, functions, depth)
            val r = evaluateExpr(expr.right, bindings, functions, depth)
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
            var res = Double.NaN
            for (branch in expr.branches) {
                val cond = evaluateExpr(branch.condition, bindings, functions, depth)
                if (!cond.isNaN() && cond > 0.5) {
                    res = evaluateExpr(branch.value, bindings, functions, depth)
                    break
                }
            }
            if (res.isNaN()) evaluateExpr(expr.defaultExpr, bindings, functions, depth) else res
        }
        is MathExpr.Call -> {
            if (depth > 16) Double.NaN
            else {
                val funcDef = functions[expr.function]
                if (funcDef != null && expr.arguments.size == funcDef.params.size) {
                    val evaluatedArgs = expr.arguments.map { evaluateExpr(it, bindings, functions, depth + 1) }
                    val localBindings = bindings + funcDef.params.zip(evaluatedArgs).toMap()
                    evaluateExpr(funcDef.body, localBindings, functions, depth + 1)
                } else {
                    val arg = if (expr.arguments.isNotEmpty()) evaluateExpr(expr.arguments[0], bindings, functions, depth + 1) else Double.NaN
                    bindings[expr.function]?.let { it * arg } ?: Double.NaN
                }
            }
        }
        is MathExpr.Phasor -> {
            val mag = evaluateExpr(expr.magnitude, bindings, functions, depth)
            val deg = evaluateExpr(expr.angleDegrees, bindings, functions, depth)
            if (mag.isNaN() || deg.isNaN()) Double.NaN else mag * cos(deg * Math.PI / 180.0)
        }
        is MathExpr.Derivative -> {
            val step = 1e-4
            val x0 = bindings[expr.variable] ?: 0.0
            if (expr.order <= 1) {
                val y1 = evaluateExpr(expr.body, bindings + (expr.variable to x0 + step), functions, depth)
                val y0 = evaluateExpr(expr.body, bindings + (expr.variable to x0 - step), functions, depth)
                if (y1.isNaN() || y0.isNaN()) Double.NaN else (y1 - y0) / (2.0 * step)
            } else {
                val lowerOrder = expr.copy(order = expr.order - 1)
                val d1 = evaluateExpr(lowerOrder, bindings + (expr.variable to x0 + step), functions, depth + 1)
                val d0 = evaluateExpr(lowerOrder, bindings + (expr.variable to x0 - step), functions, depth + 1)
                if (d1.isNaN() || d0.isNaN()) Double.NaN else (d1 - d0) / (2.0 * step)
            }
        }
        is MathExpr.Sum -> {
            val low = evaluateExpr(expr.lower, bindings, functions, depth).roundToInt()
            val high = evaluateExpr(expr.upper, bindings, functions, depth).roundToInt()
            if (low > high || high - low > 10_000) Double.NaN else {
                var sum = 0.0
                for (k in low..high) {
                    val term = evaluateExpr(expr.body, bindings + (expr.variable to k.toDouble()), functions, depth)
                    if (term.isNaN()) { sum = Double.NaN; break }
                    sum += term
                }
                sum
            }
        }
        is MathExpr.Limit -> {
            val towardVal = evaluateExpr(expr.toward, bindings, functions, depth)
            if (towardVal.isNaN()) Double.NaN else evaluateExpr(expr.body, bindings + (expr.variable to towardVal), functions, depth)
        }
        is MathExpr.Matrix -> Double.NaN
        is MathExpr.LaplaceTransform,
        is MathExpr.InverseLaplaceTransform,
        is MathExpr.ZTransform,
        is MathExpr.InverseZTransform,
        is MathExpr.Gradient,
        is MathExpr.Divergence,
        is MathExpr.Curl -> Double.NaN
        is MathExpr.TaylorSeries -> evaluateExpr(expr.body, bindings, functions, depth)
        is MathExpr.DiscreteSequence -> {
            if (expr.elements.isNotEmpty()) evaluateExpr(expr.elements[0], bindings, functions, depth) else Double.NaN
        }
        is MathExpr.IndexedSequence -> {
            bindings["${expr.name}_${evaluateExpr(expr.index, bindings, functions, depth).roundToInt()}"] ?: Double.NaN
        }
        is MathExpr.DiscreteConvolution,
        is MathExpr.CircularConvolution,
        is MathExpr.DiscreteFourierTransform -> Double.NaN
    }

    private fun quadrature16(
        body: MathExpr,
        variable: String,
        a: Double,
        b: Double,
        bindings: Map<String, Double>,
        functions: Map<String, MathIntent.FunctionDef> = emptyMap(),
        depth: Int = 0
    ): Double {
        val c1 = (b - a) / 2.0
        val c2 = (a + b) / 2.0
        var sum = 0.0
        val scoped = bindings.toMutableMap()

        for (i in 0 until 8) {
            val xi = GAUSS_X[i]
            val wi = GAUSS_W[i]

            scoped[variable] = c1 * xi + c2
            val fPos = evaluateExpr(body, scoped, functions, depth)

            scoped[variable] = -c1 * xi + c2
            val fNeg = evaluateExpr(body, scoped, functions, depth)

            if (fPos.isNaN() || fNeg.isNaN()) return Double.NaN
            sum += wi * (fPos + fNeg)
        }

        return c1 * sum
    }

    private fun factorial(value: Double): Double {
        val n = value.roundToInt()
        if (abs(value - n) > 1e-9 || n < 0 || n > 20) return Double.NaN
        var result = 1.0
        for (i in 2..n) result *= i
        return result
    }

    fun formatSignificant(value: Double, digits: Int = 4): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == 0.0) return "0"
        val absVal = abs(value)
        return if (absVal >= 1e6 || absVal < 1e-3) {
            String.format(Locale.US, "%.${digits - 1}e", value)
        } else {
            val mag = floor(log10(absVal)).toInt()
            val decimals = (digits - 1 - mag).coerceIn(0, 6)
            val formatted = String.format(Locale.US, "%.${decimals}f", value)
            if (formatted.contains('.')) formatted.trimEnd('0').trimEnd('.') else formatted
        }
    }
}
