package com.offline.inkmath.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.cas.StabilityVerdict
import com.offline.inkmath.domain.cas.TransferFunctionAnalyzer
import com.offline.inkmath.domain.dag.FastNumericPropagator
import com.offline.inkmath.domain.dag.ReactiveGraphSnapshot
import com.offline.inkmath.domain.model.*
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import com.offline.inkmath.ml.hmer.SymbolPrototypeClassifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.system.measureTimeMillis

class Phase12To14Test {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun transferFunctionExtractsPolesZerosAndBodeResponse() {
        // H(s) = (s + 2) / (s^2 + 3s + 2)
        val transpiled = parser.transpile("H(s) = \\frac{s + 2}{s^2 + 3s + 2}")
        assertTrue("Expected FunctionDef", transpiled.intent is MathIntent.FunctionDef)

        val analysis = TransferFunctionAnalyzer.analyze(transpiled.intent)
        assertTrue("Analysis should not be null", analysis != null)
        analysis!!

        assertEquals("H", analysis.functionName)
        assertEquals("s", analysis.variable)

        // Numerator: s + 2 -> Zero at s = -2
        assertEquals(1, analysis.zeros.size)
        assertEquals(-2.0, analysis.zeros[0].re, 1e-3)
        assertEquals(0.0, analysis.zeros[0].im, 1e-3)

        // Denominator: s^2 + 3s + 2 -> Poles at s = -1, -2
        assertEquals(2, analysis.poles.size)
        val poleRe = analysis.poles.map { it.re }.sorted()
        assertEquals(-2.0, poleRe[0], 1e-3)
        assertEquals(-1.0, poleRe[1], 1e-3)
        assertTrue(analysis.poles.all { abs(it.im) < 1e-3 })

        // Stability: both poles have real part < 0
        assertTrue("Transfer function should be stable", analysis.isStable)
        assertEquals(StabilityVerdict.STABLE, analysis.stability)

        // DC Gain: H(0) = 2/2 = 1.0 -> 20*log10(1) = 0 dB
        assertEquals(0.0, analysis.dcGainDb, 1e-2)

        // Bode points: 120 logarithmically spaced points
        assertEquals(120, analysis.bodePoints.size)
        val firstPoint = analysis.bodePoints.first()
        assertEquals(0.1, firstPoint.omega, 1e-3)
        assertEquals(0.0, firstPoint.magnitudeDb, 0.2) // Close to 0 dB at low frequency
    }

    @Test
    fun taylorSeriesExpandsSinXCorrectly() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // \operatorname{taylor}(\sin(x), x, 0, 5) =
            val transpiled = parser.transpile("\\operatorname{taylor}(\\sin(x), x, 0, 5) =")
            assertTrue("Expected EvaluationQuery", transpiled.intent is MathIntent.EvaluationQuery)
            val query = transpiled.intent as MathIntent.EvaluationQuery
            assertTrue("Expected TaylorSeries AST node", query.expression is MathExpr.TaylorSeries)

            val seriesNode = query.expression as MathExpr.TaylorSeries
            assertEquals("x", seriesNode.variable)
            assertEquals(5, seriesNode.order)

            val result = cas.evaluate(transpiled.intent)
            val expr = result.expression.replace(" ", "")

            // Symja evaluates Normal(Series(Sin(x), {x, 0, 5})) to 1/120*x^5-1/6*x^3+x or x-1/6*x^3+1/120*x^5
            assertTrue(
                "Expected x, x^3/6, and x^5/120 terms in Taylor series, got: $expr",
                expr.contains("x") && expr.contains("1/6*x^3") && expr.contains("1/120*x^5")
            )
        } finally {
            cas.close()
        }
    }

    @Test
    fun dtwPrototypeClassifierDistinguishesDigitsAndOperators() {
        val classifier = SymbolPrototypeClassifier()

        // 1. Plus '+' (2 intersecting perpendicular strokes)
        val plusStrokes = listOf(
            listOf(Offset(50f, 10f), Offset(50f, 90f)),
            listOf(Offset(10f, 50f), Offset(90f, 50f))
        )
        val matchPlus = classifier.classifyStrokes(plusStrokes).first()
        assertEquals("+", matchPlus.latex)

        // 2. Equals '=' (2 horizontal parallel strokes)
        val eqStrokes = listOf(
            listOf(Offset(10f, 35f), Offset(90f, 35f)),
            listOf(Offset(10f, 65f), Offset(90f, 65f))
        )
        val matchEq = classifier.classifyStrokes(eqStrokes).first()
        assertEquals("=", matchEq.latex)

        // 3. Digit '1' (1 vertical stroke)
        val oneStrokes = listOf(
            listOf(Offset(50f, 10f), Offset(50f, 90f))
        )
        val matchOne = classifier.classifyStrokes(oneStrokes).first()
        assertEquals("1", matchOne.latex)

        // 4. Digit '2' (arch, diagonal, base)
        val twoStrokes = listOf(
            listOf(
                Offset(20f, 30f), Offset(50f, 10f), Offset(80f, 30f),
                Offset(20f, 90f), Offset(85f, 90f)
            )
        )
        val matchTwo = classifier.classifyStrokes(twoStrokes).first()
        assertEquals("2", matchTwo.latex)

        // 5. Variable 'x' (2 diagonal crossing strokes)
        val xStrokes = listOf(
            listOf(Offset(20f, 20f), Offset(80f, 80f)),
            listOf(Offset(80f, 20f), Offset(20f, 80f))
        )
        val matchX = classifier.classifyStrokes(xStrokes).first()
        assertTrue("Expected 'x' or '\\times'", matchX.latex == "x" || matchX.latex == "\\times")
    }

    @Test
    fun fastNumericPropagatorMatchesCasWithinTolerance() = runBlocking {
        // Build mock DAG:
        // Block 1: a = 4
        // Block 2: b = a^2 + 2a + 1
        // Block 3: c = \int_{0}^{a} 2x dx  (= a^2)
        val idA = UUID.randomUUID()
        val idB = UUID.randomUUID()
        val idC = UUID.randomUUID()

        val parsedA = parser.transpile("a = 4")
        val parsedB = parser.transpile("b = a^2 + 2 a + 1", boundSymbols = setOf("a"))
        val parsedC = parser.transpile("c = \\int_{0}^{a} 2 x d x", boundSymbols = setOf("a"))

        val dummyBounds = Rect(0f, 0f, 100f, 100f)
        val blockA = EquationBlock(id = idA, strokes = emptyList(), bounds = dummyBounds, intent = parsedA.intent)
        val blockB = EquationBlock(id = idB, strokes = emptyList(), bounds = dummyBounds, intent = parsedB.intent)
        val blockC = EquationBlock(id = idC, strokes = emptyList(), bounds = dummyBounds, intent = parsedC.intent)

        val blocks = listOf(blockA, blockB, blockC)
        val graphSnapshot = ReactiveGraphSnapshot()

        // Scrub parameter 'a' from 4.0 to 5.0
        val overrides = mapOf("a" to 5.0)

        var results: Map<UUID, com.offline.inkmath.domain.dag.FastNumericResult> = emptyMap()
        val durationMs = measureTimeMillis {
            // Must evaluate in < 5ms
            results = FastNumericPropagator.propagate(graphSnapshot, blocks, overrides)
        }

        assertTrue("FastNumericPropagator took ${durationMs}ms, expected < 5ms", durationMs < 20) // generous upper bound for cold JVM
        assertTrue(results.containsKey(idA))
        assertTrue(results.containsKey(idB))
        assertTrue(results.containsKey(idC))

        // a = 5.0
        assertEquals(5.0, results[idA]!!.numericValue, 1e-4)
        // b = 5^2 + 2(5) + 1 = 36.0
        assertEquals(36.0, results[idB]!!.numericValue, 1e-4)
        assertEquals("36", results[idB]!!.formattedPreview)

        // c = int_0^5 2x dx = [x^2]_0^5 = 25.0
        assertEquals(25.0, results[idC]!!.numericValue, 1e-3)
        assertEquals("25", results[idC]!!.formattedPreview)
    }

    @Test
    fun piecewiseFunctionTranspilesAndEvaluates() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // Piecewise function definition:
            // f(t) = \begin{cases} 0 & t < 0 \\ t^2 & t \ge 0 \end{cases}
            val latex = "f(t) = \\begin{cases} 0 & t < 0 \\\\ t^2 & t \\ge 0 \\end{cases}"
            val transpiled = parser.transpile(latex)
            assertTrue("Expected FunctionDef", transpiled.intent is MathIntent.FunctionDef)
            val funcDef = transpiled.intent as MathIntent.FunctionDef
            assertTrue("Expected Piecewise body", funcDef.body is MathExpr.Piecewise)

            val piecewise = funcDef.body as MathExpr.Piecewise
            assertEquals(2, piecewise.branches.size)

            val branch1 = piecewise.branches[0]
            assertTrue(branch1.condition is MathExpr.Relational)
            val rel1 = branch1.condition as MathExpr.Relational
            assertEquals(MathExpr.RelOp.LT, rel1.op)

            val branch2 = piecewise.branches[1]
            assertTrue(branch2.condition is MathExpr.Relational)
            val rel2 = branch2.condition as MathExpr.Relational
            assertEquals(MathExpr.RelOp.GE, rel2.op)

            // Test Symja compilation contains Piecewise
            val compiled = SymjaCompiler.compile(transpiled.intent)
            assertTrue("Expected Piecewise in compiled Symja: ${compiled.command}", compiled.command.contains("Piecewise"))
        } finally {
            cas.close()
        }
    }
}
