package com.offline.inkmath.domain.evaluator

import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.model.BindingSnapshot
import com.offline.inkmath.domain.model.CasMode
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FastPlotEvaluatorTest {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun polynomialEvaluationMatchesSymja() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("f(x) = x^2 - 4")
            val funcDef = transpiled.intent as MathIntent.FunctionDef

            val testPoints = listOf(-3.0, -2.0, 0.0, 2.0, 3.0, 5.0)
            for (x in testPoints) {
                val fastResult = FastPlotEvaluator.evaluate(funcDef.body, mapOf("x" to x))

                val evalQuery = parser.transpile("f($x) =?").intent
                val casResult = cas.evaluate(evalQuery, BindingSnapshot(functions = mapOf("f" to funcDef)), mode = CasMode.NUMERIC).expression.toDouble()

                assertEquals(casResult, fastResult, 1e-4)
            }
        } finally {
            cas.close()
        }
    }

    @Test
    fun trigEvaluationMatchesSymja() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("g(x) = \\sin(x) + \\cos(x)")
            val funcDef = transpiled.intent as MathIntent.FunctionDef

            val testPoints = listOf(0.0, Math.PI / 4, Math.PI / 2, Math.PI, 3 * Math.PI / 2)
            for (x in testPoints) {
                val fastResult = FastPlotEvaluator.evaluate(funcDef.body, mapOf("x" to x))

                val query = MathIntent.EvaluationQuery(
                    MathExpr.Call("g", listOf(MathExpr.Number(x.toString()))),
                    isApproximate = true
                )
                val casResult = cas.evaluate(
                    query,
                    BindingSnapshot(functions = mapOf("g" to funcDef)),
                    mode = CasMode.NUMERIC
                ).expression.toDouble()

                assertEquals(casResult, fastResult, 1e-4)
            }
        } finally {
            cas.close()
        }
    }

    @Test
    fun exponentialAndLogEvaluationMatchesSymja() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val expDef = (parser.transpile("h(x) = e^x").intent as MathIntent.FunctionDef)
            for (x in listOf(-1.0, 0.0, 1.0, 2.0)) {
                val fast = FastPlotEvaluator.evaluate(expDef.body, mapOf("x" to x))
                val query = MathIntent.EvaluationQuery(
                    MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Constant.E, MathExpr.Number(x.toString())),
                    isApproximate = true
                )
                val cas = cas.evaluate(query, mode = CasMode.NUMERIC).expression.toDouble()
                assertEquals(cas, fast, 1e-4)
            }

            val logDef = (parser.transpile("k(x) = \\ln(x)").intent as MathIntent.FunctionDef)
            for (x in listOf(0.5, 1.0, Math.E, 10.0)) {
                val fast = FastPlotEvaluator.evaluate(logDef.body, mapOf("x" to x))
                val query = MathIntent.EvaluationQuery(
                    MathExpr.Builtin(MathExpr.Function.LOG, listOf(MathExpr.Number(x.toString()))),
                    isApproximate = true
                )
                val cas = cas.evaluate(query, mode = CasMode.NUMERIC).expression.toDouble()
                assertEquals(cas, fast, 1e-4)
            }
        } finally {
            cas.close()
        }
    }

    @Test
    fun dagBoundVariablesAreCorrectlySubstituted() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("f(x) = a x^2 + b")
            val funcDef = transpiled.intent as MathIntent.FunctionDef

            val bindings = mapOf("a" to 3.0, "b" to -5.0)
            val fastResult = FastPlotEvaluator.evaluate(funcDef.body, mapOf("x" to 2.0), bindings)
            // 3.0 * (2.0)^2 + (-5.0) = 12 - 5 = 7.0
            assertEquals(7.0, fastResult, 1e-6)

            val query = MathIntent.EvaluationQuery(
                MathExpr.Binary(
                    MathExpr.Op.ADD,
                    MathExpr.Binary(MathExpr.Op.MULTIPLY, MathExpr.Symbol("a"), MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Number("2"), MathExpr.Number("2"))),
                    MathExpr.Symbol("b")
                ),
                isApproximate = true
            )
            val casResult = cas.evaluate(
                query,
                BindingSnapshot(values = mapOf("a" to MathExpr.Number("3"), "b" to MathExpr.Number("-5"))),
                mode = CasMode.NUMERIC
            ).expression.toDouble()

            assertEquals(casResult, fastResult, 1e-4)
        } finally {
            cas.close()
        }
    }

    @Test
    fun sampleProducesExpectedPointCountAndFiniteValues() {
        val transpiled = parser.transpile("f(x) = x^2 - 4")
        val funcDef = transpiled.intent as MathIntent.FunctionDef

        val points = FastPlotEvaluator.sample(funcDef.body, "x", -10.0, 10.0, 160)
        assertEquals(160, points.size)
        assertTrue(points.all { it.second.isFinite() })
        assertEquals(-10.0, points.first().first, 1e-6)
        assertEquals(10.0, points.last().first, 1e-6)

        // Root at x = 2 and x = -2
        val yAt2 = FastPlotEvaluator.evaluate(funcDef.body, mapOf("x" to 2.0))
        assertTrue(abs(yAt2) < 1e-6)
    }
}
