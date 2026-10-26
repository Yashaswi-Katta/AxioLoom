package com.axioloom.app.domain

import com.axioloom.app.domain.cas.MatrixAnalyzer
import com.axioloom.app.domain.cas.OfflineCasEngine
import com.axioloom.app.domain.gesture.ScratchOutDetector
import com.axioloom.app.domain.model.*
import com.axioloom.app.domain.parser.LatexToSymjaTranspiler
import com.axioloom.app.ml.hmer.HmerVocabulary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.geometry.Offset
import com.axioloom.app.domain.circuit.SymbolicMnaSolver
import com.axioloom.app.domain.codegen.EmbeddedCGenerator
import com.axioloom.app.domain.dag.FastNumericPropagator
import com.axioloom.app.domain.evaluator.FastPlotEvaluator
import com.axioloom.app.domain.units.DimensionalAnalyzer
import com.axioloom.app.domain.units.DimensionVector
import com.axioloom.app.ml.hmer.SymbolPrototypeClassifier
import kotlin.math.abs

class PhaseRemediationAuditTest {

    private val parser = LatexToSymjaTranspiler()

    @Test
    fun prefixOverlappingAliasesAndTaylorSeries() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // 1. Prefix-overlapping aliases: x_1 and x_10
            val parsed10 = parser.transpile("x_{10} = 5")
            val parsed1 = parser.transpile("x_1 = 3")
            cas.evaluate(parsed10.intent)
            cas.evaluate(parsed1.intent)

            val snap = BindingSnapshot(values = mapOf("x_10" to MathExpr.Number("5"), "x_1" to MathExpr.Number("3")))
            val query10 = cas.evaluate(parser.transpile("x_{10} + 1").intent, bindings = snap)
            assertEquals("6", query10.expression)

            val query1 = cas.evaluate(parser.transpile("x_1 + 1").intent, bindings = snap)
            assertEquals("4", query1.expression)

            // 2. Taylor series on y-variable expression expands with respect to y
            val sinYIntent = parser.transpile("\\sin(y)").intent
            val taylorResult = cas.evaluate(sinYIntent, transform = SymbolicTransform.TAYLOR_SERIES)
            assertTrue("Taylor series of sin(y) should contain y", taylorResult.expression.contains("y"))
        } finally {
            cas.close()
        }
    }

    @Test
    fun inverseZTransformTranspilationAndCompilation() {
        val transpiled = parser.transpile("\\mathcal{Z}^{-1}{X(z)}")
        val query = transpiled.intent as MathIntent.EvaluationQuery
        assertTrue("Intent expression must be InverseZTransform", query.expression is MathExpr.InverseZTransform)

        val compiled = SymjaCompiler.compile(query)
        assertTrue("Compiled Symja must contain InverseZTransform", compiled.command.contains("InverseZTransform"))
    }

    @Test
    fun matrixAnalyzerCayleyHamiltonOnArbitrary2x2And3x3() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val analyzer = MatrixAnalyzer(cas)

            // Arbitrary 2x2: [[1, 2], [3, 4]]
            val res2x2 = analyzer.analyzeMatrix(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)))
            assertTrue("2x2 Cayley-Hamilton must be verified", res2x2.cayleyHamiltonVerified)
            assertTrue("2x2 char poly must contain lambda^2", res2x2.characteristicPolynomial.contains("\\lambda^{2}") || res2x2.characteristicPolynomial.contains("lambda"))

            // Arbitrary 3x3: [[1, 0, 2], [0, 1, 0], [3, 0, 1]]
            val res3x3 = analyzer.analyzeMatrix(
                listOf(
                    listOf(1.0, 0.0, 2.0),
                    listOf(0.0, 1.0, 0.0),
                    listOf(3.0, 0.0, 1.0)
                )
            )
            assertEquals(3, res3x3.dimension)
            assertTrue("3x3 Cayley-Hamilton must be verified", res3x3.cayleyHamiltonVerified)
        } finally {
            cas.close()
        }
    }

    @Test
    fun hmerVocabularyDecodesSpacesBetweenCommandsAndLetters() {
        val tokens = listOf(
            "<sos>", "<eos>", "<pad>", "<unk>",
            "\\int", "\\sin", "\\alpha", "x", "t", "+", "1"
        )
        val vocab = HmerVocabulary.fromLines(tokens)

        val intX = vocab.decode(listOf(vocab.idFor("\\int")!!, vocab.idFor("x")!!))
        assertEquals("\\int x", intX)

        val sinX = vocab.decode(listOf(vocab.idFor("\\sin")!!, vocab.idFor("x")!!))
        assertEquals("\\sin x", sinX)

        val alphaT = vocab.decode(listOf(vocab.idFor("\\alpha")!!, vocab.idFor("t")!!))
        assertEquals("\\alpha t", alphaT)
    }

    @Test
    fun scratchOutDetectorHandlesStrokesWithoutNativeInkMesh() {
        val detector = ScratchOutDetector()
        val pts1 = listOf(
            InkPoint(10f, 10f, 0),
            InkPoint(100f, 10f, 50)
        )
        val target = TimedStroke.fromPoints(pts1, finishedAtMillis = 100L)
        assertFalse("Synthetic stroke must have hasNativeStroke == false", target.hasNativeStroke)

        // Oscillatory scratch stroke crossing the target
        val scratchPts = mutableListOf<InkPoint>()
        for (i in 0..10) {
            val x = 20f + i * 5f
            val y = if (i % 2 == 0) -10f else 30f
            scratchPts.add(InkPoint(x, y, i * 10L))
        }
        val scratch = TimedStroke.fromPoints(scratchPts, finishedAtMillis = 200L)

        // Must evaluate without throwing IllegalStateException
        val decision = detector.detect(scratch, listOf(target))
        assertNotNull(decision)
    }

    @Test
    fun engineeringConstantsExpandSiSuffixesPreservesVariables() {
        // Preserves bare variables with spaces
        val expr1 = EngineeringConstants.expandSiSuffixes("2 n + 1")
        assertEquals("2 n + 1", expr1)

        // Preserves unit step functions
        val expr2 = EngineeringConstants.expandSiSuffixes("10 u(t)")
        assertEquals("10 u(t)", expr2)

        val expr3 = EngineeringConstants.expandSiSuffixes("10u(t)")
        assertEquals("10u(t)", expr3)

        // Expands valid adjacent SI prefixes
        val expandedK = EngineeringConstants.expandSiSuffixes("4.7k")
        assertEquals("(4.7 * 10^3)", expandedK)

        val expandedU = EngineeringConstants.expandSiSuffixes("10u")
        assertEquals("(10 * 10^-6)", expandedU)
    }

    @Test
    fun bareMilliSiSuffixExpansion() {
        val expandedM = EngineeringConstants.expandSiSuffixes("5m")
        assertEquals("(5 * 10^-3)", expandedM)

        val expandedDecM = EngineeringConstants.expandSiSuffixes("2.5m")
        assertEquals("(2.5 * 10^-3)", expandedDecM)

        val preservedBare = EngineeringConstants.expandSiSuffixes("m + 1")
        assertEquals("m + 1", preservedBare)

        val preservedFunc = EngineeringConstants.expandSiSuffixes("10m(t)")
        assertEquals("10m(t)", preservedFunc)
    }

    @Test
    fun transpilerNewOperatorsAndLeibnizDerivatives() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // 1. hbar
            val hbarParsed = parser.transpile("\\hbar =")
            assertTrue("Expected hbar in canonical Symja", hbarParsed.canonicalSymja.contains("hbar"))

            // 2. Leibniz derivatives
            val d1 = parser.transpile("\\frac{dy}{dx} =").intent as MathIntent.EvaluationQuery
            assertTrue("Expected Derivative order 1", d1.expression is MathExpr.Derivative && (d1.expression as MathExpr.Derivative).order == 1)

            val d2 = parser.transpile("\\frac{d^2 y}{dx^2} =").intent as MathIntent.EvaluationQuery
            assertTrue("Expected Derivative order 2", d2.expression is MathExpr.Derivative && (d2.expression as MathExpr.Derivative).order == 2)

            // 3. Degree / Radian conversion
            val degTranspiled = parser.transpile("\\operatorname{deg}(\\pi) =")
            val degRes = cas.evaluate(degTranspiled.intent)
            assertEquals("180", degRes.expression)

            val radTranspiled = parser.transpile("\\operatorname{rad}(180) =")
            val radRes = cas.evaluate(radTranspiled.intent)
            assertEquals("Pi", radRes.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun fastNumericPropagatorEvaluatesUserFunctionsAndHigherDerivatives() {
        // Higher-order derivative: d^2/dx^2 (x^3) at x = 2 -> 6 * x = 12
        val deriv2 = MathExpr.Derivative(
            MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Symbol("x"), MathExpr.Number("3")),
            "x",
            2
        )
        val d2Val = FastNumericPropagator.evaluateExpr(deriv2, mapOf("x" to 2.0))
        assertTrue("Expected d2/dx2(x^3) at 2 to be ~12, got $d2Val", abs(d2Val - 12.0) < 0.1)

        // User function: f(x) = x^2 + 1, f(3) = 10
        val funcDef = MathIntent.FunctionDef(
            "f",
            listOf("x"),
            MathExpr.Binary(MathExpr.Op.ADD, MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Symbol("x"), MathExpr.Number("2")), MathExpr.Number("1"))
        )
        val callVal = FastNumericPropagator.evaluateExpr(
            MathExpr.Call("f", listOf(MathExpr.Number("3"))),
            emptyMap(),
            mapOf("f" to funcDef)
        )
        assertEquals(10.0, callVal, 1e-6)
    }

    @Test
    fun fastPlotEvaluatorDelegatesSpecialExpressions() {
        // Phasor 10 /_ 60 deg -> 10 * cos(60) = 5.0
        val phasor = MathExpr.Phasor(MathExpr.Number("10"), MathExpr.Number("60"))
        val pVal = FastPlotEvaluator.evaluate(phasor, emptyMap())
        assertEquals(5.0, pVal, 1e-4)
    }

    @Test
    fun embeddedCGeneratorProducesCseAndSanitizes() {
        val xy = MathExpr.Binary(MathExpr.Op.MULTIPLY, MathExpr.Symbol("x"), MathExpr.Symbol("y"))
        val expr = MathExpr.Binary(MathExpr.Op.ADD, xy, xy)
        val code = EmbeddedCGenerator.generateMathFunction(expr, "test_func")

        assertTrue("Generated source should contain CSE temp tmp_1", code.sourceCode.contains("tmp_1"))
        assertTrue("Generated header should declare test_func", code.headerCode.contains("test_func"))
    }

    @Test
    fun symbolicMnaSolverWithBindings() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val solver = SymbolicMnaSolver(cas)
            val netlist = solver.parseNetlist("V1 1 0 10\nR1 1 2 R_1\nR2 2 0 R_2")
            val snap = BindingSnapshot(values = mapOf("R_1" to MathExpr.Number("100"), "R_2" to MathExpr.Number("100")))
            val res = solver.solve(netlist, bindings = snap)
            assertEquals("5", res.nodeVoltages[2])
        } finally {
            cas.close()
        }
    }

    @Test
    fun prototypeClassifierDifferentiatesTAndPlusByCrossbar() {
        val classifier = SymbolPrototypeClassifier()
        val vert = listOf(Offset(50f, 0f), Offset(50f, 100f))

        // High crossbar -> 't'
        val highHoriz = listOf(Offset(20f, 20f), Offset(80f, 20f))
        val tMatches = classifier.classifyStrokes(listOf(vert, highHoriz))
        assertTrue("Expected top match for high crossbar to be 't'", tMatches.isNotEmpty() && tMatches[0].label == "t")

        // Center crossbar -> '+'
        val centerHoriz = listOf(Offset(20f, 50f), Offset(80f, 50f))
        val plusMatches = classifier.classifyStrokes(listOf(vert, centerHoriz))
        assertTrue("Expected top match for centered crossbar to be '+'", plusMatches.isNotEmpty() && plusMatches[0].label == "+")
    }

    @Test
    fun dimensionalAnalyzerHandlesExponentsAndUnits() {
        val expr = MathExpr.Binary(MathExpr.Op.DIVIDE, MathExpr.Symbol("m"), MathExpr.Symbol("s"))
        val res = DimensionalAnalyzer.analyze(expr, variableDimensions = mapOf("m" to DimensionVector.METER, "s" to DimensionVector.SECOND))
        assertEquals("[m·s^-1]", res.compactUnit)
    }
}
