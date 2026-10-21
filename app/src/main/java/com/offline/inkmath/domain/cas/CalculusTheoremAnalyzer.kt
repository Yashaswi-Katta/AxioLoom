package com.offline.inkmath.domain.cas

import com.offline.inkmath.domain.evaluator.FastPlotEvaluator
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.model.SymjaCompiler
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import kotlin.math.abs

data class TangentLine(
    val c: Double,
    val fc: Double,
    val slope: Double,
    val equationLatex: String
)

data class RollesResult(
    val continuous: Boolean,
    val differentiable: Boolean,
    val faEqualsFb: Boolean,
    val a: Double,
    val b: Double,
    val fa: Double,
    val fb: Double,
    val derivativeLatex: String,
    val rootsC: List<Double>,
    val symbolicRootsC: List<String>,
    val isValid: Boolean,
    val summary: String
)

data class LagrangeMvtResult(
    val continuous: Boolean,
    val differentiable: Boolean,
    val a: Double,
    val b: Double,
    val fa: Double,
    val fb: Double,
    val secantSlope: Double,
    val secantSlopeLatex: String,
    val derivativeLatex: String,
    val rootsC: List<Double>,
    val symbolicRootsC: List<String>,
    val tangentLines: List<TangentLine>,
    val isValid: Boolean,
    val summary: String
)

data class CauchyMvtResult(
    val continuous: Boolean,
    val differentiable: Boolean,
    val a: Double,
    val b: Double,
    val fa: Double,
    val fb: Double,
    val ga: Double,
    val gb: Double,
    val ratio: Double,
    val ratioLatex: String,
    val rootsC: List<Double>,
    val symbolicRootsC: List<String>,
    val isValid: Boolean,
    val summary: String
)

/**
 * Symbolic & numeric verification engine for Rolle's, Lagrange's, and Cauchy's Mean Value Theorems.
 * Interrogates Symja on the dedicated worker thread and extracts valid interior roots c in (a, b).
 */
class CalculusTheoremAnalyzer(private val cas: OfflineCasEngine) {

    suspend fun verifyRolles(
        fSymjaExpr: String,
        variable: String = "x",
        a: Double,
        b: Double
    ): RollesResult {
        require(a < b) { "Interval must have a < b, got [$a, $b]" }
        val (continuous, differentiable) = checkContinuityAndDifferentiability(fSymjaExpr, variable, a, b)

        val fa = evaluateNumeric(fSymjaExpr, variable, a)
        val fb = evaluateNumeric(fSymjaExpr, variable, b)
        val faEqualsFb = abs(fa - fb) < 1e-4

        val derivLatex = cas.evalCommandToLatex("D($fSymjaExpr, $variable)")

        if (!continuous || !differentiable || !faEqualsFb) {
            val reason = when {
                !continuous -> "f($variable) is not continuous on [$a, $b]"
                !differentiable -> "f($variable) is not differentiable on ($a, $b)"
                else -> "f(a) != f(b) (f($a)=$fa, f($b)=$fb)"
            }
            return RollesResult(
                continuous = continuous,
                differentiable = differentiable,
                faEqualsFb = faEqualsFb,
                a = a,
                b = b,
                fa = fa,
                fb = fb,
                derivativeLatex = derivLatex,
                rootsC = emptyList(),
                symbolicRootsC = emptyList(),
                isValid = false,
                summary = "Rolle's theorem hypothesis not satisfied: $reason"
            )
        }

        // Solve f'(c) == 0
        val solveCmd = "Solve(D($fSymjaExpr, $variable) == 0, $variable)"
        val (symRoots, numRoots) = solveAndFilterRoots(solveCmd, variable, a, b)

        return RollesResult(
            continuous = true,
            differentiable = true,
            faEqualsFb = true,
            a = a,
            b = b,
            fa = fa,
            fb = fb,
            derivativeLatex = derivLatex,
            rootsC = numRoots,
            symbolicRootsC = symRoots,
            isValid = numRoots.isNotEmpty(),
            summary = if (numRoots.isNotEmpty()) {
                "Rolle's theorem verified: found ${numRoots.size} critical point(s) in ($a, $b): ${symRoots.joinToString(", ")}"
            } else {
                "Rolle's theorem: no real roots found in interior ($a, $b)"
            }
        )
    }

    suspend fun verifyLagrange(
        fSymjaExpr: String,
        variable: String = "x",
        a: Double,
        b: Double
    ): LagrangeMvtResult {
        require(a < b) { "Interval must have a < b, got [$a, $b]" }
        val (continuous, differentiable) = checkContinuityAndDifferentiability(fSymjaExpr, variable, a, b)

        val fa = evaluateNumeric(fSymjaExpr, variable, a)
        val fb = evaluateNumeric(fSymjaExpr, variable, b)
        val secantSlope = (fb - fa) / (b - a)

        val secantSlopeLatex = cas.evalCommandToLatex("Together(($fb - $fa) / ($b - $a))")
        val derivLatex = cas.evalCommandToLatex("D($fSymjaExpr, $variable)")

        if (!continuous || !differentiable) {
            val reason = if (!continuous) "f($variable) is not continuous on [$a, $b]" else "f($variable) is not differentiable on ($a, $b)"
            return LagrangeMvtResult(
                continuous = continuous,
                differentiable = differentiable,
                a = a,
                b = b,
                fa = fa,
                fb = fb,
                secantSlope = secantSlope,
                secantSlopeLatex = secantSlopeLatex,
                derivativeLatex = derivLatex,
                rootsC = emptyList(),
                symbolicRootsC = emptyList(),
                tangentLines = emptyList(),
                isValid = false,
                summary = "Lagrange's MVT hypothesis not satisfied: $reason"
            )
        }

        // Solve f'(c) == secantSlope
        val solveCmd = "Solve(D($fSymjaExpr, $variable) == ($secantSlope), $variable)"
        val (symRoots, numRoots) = solveAndFilterRoots(solveCmd, variable, a, b)

        val tangentLines = numRoots.map { c ->
            val fc = evaluateNumeric(fSymjaExpr, variable, c)
            val tangentEquation = cas.evalCommandToLatex("Expand($secantSlope * ($variable - $c) + $fc)")
            TangentLine(
                c = c,
                fc = fc,
                slope = secantSlope,
                equationLatex = "y = $tangentEquation"
            )
        }

        return LagrangeMvtResult(
            continuous = true,
            differentiable = true,
            a = a,
            b = b,
            fa = fa,
            fb = fb,
            secantSlope = secantSlope,
            secantSlopeLatex = secantSlopeLatex,
            derivativeLatex = derivLatex,
            rootsC = numRoots,
            symbolicRootsC = symRoots,
            tangentLines = tangentLines,
            isValid = numRoots.isNotEmpty(),
            summary = if (numRoots.isNotEmpty()) {
                "Lagrange's MVT verified: found ${numRoots.size} tangent point(s) in ($a, $b): c = ${symRoots.joinToString(", ")}"
            } else {
                "Lagrange's MVT: no real roots found in interior ($a, $b)"
            }
        )
    }

    suspend fun verifyCauchy(
        fSymjaExpr: String,
        gSymjaExpr: String,
        variable: String = "x",
        a: Double,
        b: Double
    ): CauchyMvtResult {
        require(a < b) { "Interval must have a < b, got [$a, $b]" }
        val (fCont, fDiff) = checkContinuityAndDifferentiability(fSymjaExpr, variable, a, b)
        val (gCont, gDiff) = checkContinuityAndDifferentiability(gSymjaExpr, variable, a, b)

        val fa = evaluateNumeric(fSymjaExpr, variable, a)
        val fb = evaluateNumeric(fSymjaExpr, variable, b)
        val ga = evaluateNumeric(gSymjaExpr, variable, a)
        val gb = evaluateNumeric(gSymjaExpr, variable, b)

        val gDiffNonZero = abs(gb - ga) > 1e-7
        val ratio = if (gDiffNonZero) (fb - fa) / (gb - ga) else Double.NaN
        val ratioLatex = if (gDiffNonZero) cas.evalCommandToLatex("Together(($fb - $fa) / ($gb - ga))") else "\\text{Undefined}"

        val continuous = fCont && gCont
        val differentiable = fDiff && gDiff && gDiffNonZero

        if (!continuous || !differentiable) {
            return CauchyMvtResult(
                continuous = continuous,
                differentiable = differentiable,
                a = a,
                b = b,
                fa = fa,
                fb = fb,
                ga = ga,
                gb = gb,
                ratio = ratio,
                ratioLatex = ratioLatex,
                rootsC = emptyList(),
                symbolicRootsC = emptyList(),
                isValid = false,
                summary = "Cauchy's MVT hypothesis not satisfied"
            )
        }

        // Solve f'(c) / g'(c) == ratio => f'(c) - ratio * g'(c) == 0
        val solveCmd = "Solve(D($fSymjaExpr, $variable) - ($ratio) * D($gSymjaExpr, $variable) == 0, $variable)"
        val (symRoots, numRoots) = solveAndFilterRoots(solveCmd, variable, a, b)

        return CauchyMvtResult(
            continuous = true,
            differentiable = true,
            a = a,
            b = b,
            fa = fa,
            fb = fb,
            ga = ga,
            gb = gb,
            ratio = ratio,
            ratioLatex = ratioLatex,
            rootsC = numRoots,
            symbolicRootsC = symRoots,
            isValid = numRoots.isNotEmpty(),
            summary = if (numRoots.isNotEmpty()) {
                "Cauchy's MVT verified: found ${numRoots.size} point(s) in ($a, $b): c = ${symRoots.joinToString(", ")}"
            } else {
                "Cauchy's MVT: no real roots found in interior ($a, $b)"
            }
        )
    }

    private suspend fun evaluateNumeric(expr: String, variable: String, value: Double): Double {
        return try {
            val transpiled = LatexToSymjaTranspiler().transpile(expr).intent
            if (transpiled is MathIntent.EvaluationQuery) {
                val fast = FastPlotEvaluator.evaluate(transpiled.expression, mapOf(variable to value))
                if (fast.isFinite()) return fast
            }
            val cmd = "N(ReplaceAll($expr, $variable -> ($value)))"
            val raw = cas.evalRaw(cmd)
            raw.toDoubleOrNull() ?: Double.NaN
        } catch (_: Exception) {
            Double.NaN
        }
    }

    private suspend fun checkContinuityAndDifferentiability(
        expr: String,
        variable: String,
        a: Double,
        b: Double
    ): Pair<Boolean, Boolean> {
        return try {
            // Check denominator singularities
            val denomSolve = cas.evalRaw("N(Solve(Denominator($expr) == 0, $variable))")
            val denomRoots = extractRootsFromSymja(denomSolve)
            val hasSingularity = denomRoots.any { it in a..b }
            if (hasSingularity) return false to false
            true to true
        } catch (_: Exception) {
            true to true
        }
    }

    private suspend fun solveAndFilterRoots(
        solveCommand: String,
        variable: String,
        a: Double,
        b: Double
    ): Pair<List<String>, List<Double>> {
        val symRaw = cas.evalRaw(solveCommand)
        val numRaw = cas.evalRaw("N($solveCommand)")

        val symList = extractSymbolicRootsFromSymja(symRaw, variable)
        val numList = extractNumericRootsFromSymja(numRaw, variable)

        val validSym = mutableListOf<String>()
        val validNum = mutableListOf<Double>()

        val eps = 1e-6
        for (i in numList.indices) {
            val n = numList[i]
            if (!n.isNaN() && n > (a + eps) && n < (b - eps)) {
                validNum += n
                val s = symList.getOrNull(i) ?: n.toString()
                validSym += s
            }
        }

        return validSym to validNum
    }

    private fun extractNumericRootsFromSymja(raw: String, variable: String): List<Double> {
        val results = mutableListOf<Double>()
        val regex = Regex("""$variable\s*->\s*([0-9eE.+\-]+)""")
        regex.findAll(raw).forEach { match ->
            val v = match.groupValues[1].toDoubleOrNull()
            if (v != null) results += v
        }
        return results
    }

    private fun extractSymbolicRootsFromSymja(raw: String, variable: String): List<String> {
        val results = mutableListOf<String>()
        // Match {variable -> expression}
        val regex = Regex("""$variable\s*->\s*([^,}]+)""")
        regex.findAll(raw).forEach { match ->
            val sym = match.groupValues[1].trim()
            if (!sym.contains("I")) { // exclude complex
                results += sym
            }
        }
        return results
    }

    private fun extractRootsFromSymja(raw: String): List<Double> {
        val results = mutableListOf<Double>()
        val regex = Regex("""->\s*([0-9eE.+\-]+)""")
        regex.findAll(raw).forEach { match ->
            val v = match.groupValues[1].toDoubleOrNull()
            if (v != null) results += v
        }
        return results
    }
}
