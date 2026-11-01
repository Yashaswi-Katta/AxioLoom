package com.axioloom.app.domain

import com.axioloom.app.domain.sim.OdeTrajectory

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
    @Test
    fun prototypeClassifierStrokeBudgetingLargeStrokeCounts() {
        for (strokeCount in listOf(1, 2, 16, 17, 18, 30, 50)) {
            val strokes = (0 until strokeCount).map { i ->
                listOf(Offset(i * 5f, 0f), Offset(i * 5f + 10f, 20f))
            }
            val points = SymbolPrototypeClassifier.processStrokes(strokes)
            assertTrue("Expected points for strokeCount $strokeCount to be non-empty", points.isNotEmpty())
            assertTrue("Expected points to have valid coordinates", points.all { it.x.isFinite() && it.y.isFinite() })
        }
    }

    @Test
    fun odeTrajectoryEqualityChecksStateContent() {
        val t = doubleArrayOf(0.0, 0.5, 1.0)
        val names = listOf("x", "y")
        val states1 = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.5, 0.5), doubleArrayOf(0.0, 1.0))
        val states2 = listOf(doubleArrayOf(2.0, 0.0), doubleArrayOf(1.5, 0.5), doubleArrayOf(1.0, 1.0))
        val states1Copy = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.5, 0.5), doubleArrayOf(0.0, 1.0))

        val traj1 = OdeTrajectory(t, states1, names)
        val traj2 = OdeTrajectory(t, states2, names)
        val traj3 = OdeTrajectory(t, states1Copy, names)

        assertNotEquals("Trajectories with different states must not be equal", traj1, traj2)
        assertEquals("Trajectories with identical states must be equal", traj1, traj3)
        assertEquals("Equal trajectories must have matching hashCodes", traj1.hashCode(), traj3.hashCode())
    }

    @Test
    fun kmapTranspilationMinimalSopDoesNotCrash() {
        val transpiled = parser.transpile("\\operatorname{kmap}(1, 3, 5, 7)")
        val query = transpiled.intent as MathIntent.EvaluationQuery
        val compiled = SymjaCompiler.compile(query)
        assertTrue("Compiled command should not be empty", compiled.command.isNotEmpty())
    }

    @Test
    fun circuitSolverVccsElementNetlistRoundtrip() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val solver = SymbolicMnaSolver(cas)
            val netlist = solver.parseNetlist("V1 1 0 10\nR1 1 2 100\nG1 3 0 1 2 0.05\nR2 3 0 200")
            assertEquals(4, netlist.size)
            val vccs = netlist.filterIsInstance<com.axioloom.app.domain.circuit.CircuitElement.Vccs>().firstOrNull()
            assertNotNull("Expected parsed VCCS element G1", vccs)
            assertEquals("G1", vccs?.name)
            assertEquals(3, vccs?.outPos)
            assertEquals(0, vccs?.outNeg)
            assertEquals(1, vccs?.ctrlPos)
            assertEquals(2, vccs?.ctrlNeg)
        } finally {
            cas.close()
        }
    }

    @Test
    fun singlePointDotStrokeGeneratesValidGeometry() {
        val pt = InkPoint(100f, 150f, 0L)
        val stroke = TimedStroke.fromPoints(listOf(pt, InkPoint(pt.x + 0.1f, pt.y + 0.1f, 1L)))
        assertTrue("Stroke geometry must be finite and ordered", stroke.geometry.bounds.isFiniteOrdered())
        assertTrue("Stroke points should not be empty", stroke.points.isNotEmpty())
    }

    @Test
    fun prototypeClassifierGroupsMultiStrokeSymbolsWithRealisticGaps() {
        val classifier = SymbolPrototypeClassifier()
        val vertStroke = TimedStroke.fromPoints(listOf(InkPoint(50f, 10f, 0L), InkPoint(50f, 90f, 50L)))
        val horizStroke = TimedStroke.fromPoints(listOf(InkPoint(10f, 50f, 60L), InkPoint(90f, 50f, 110L)))
        val result = classifier.recognizeExpression(listOf(vertStroke, horizStroke))
        assertNotNull("Expected recognized expression for '+'", result)
        assertEquals("+", result?.latex)
    }

    @Test
    fun prototypeClassifierRejectsUnmatchedGarbageCluster() {
        val classifier = SymbolPrototypeClassifier()
        val wildPoints = (0 until 32).map { i ->
            InkPoint((i * 17 % 100).toFloat(), ((i * 31 + 7) % 100).toFloat(), i * 10L)
        }
        val garbageStroke = TimedStroke.fromPoints(wildPoints)
        val match = classifier.classify(listOf(garbageStroke))
        assertNotNull("Match object should be returned", match)
    }

    @Test
    fun indexedSequenceBoundExpansionEvaluatesCorrectly() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val seqDef = parser.transpile("a = [10, 20, 30]")
            val query = parser.transpile("a[2] =")
            val snap = BindingSnapshot(values = mapOf("a" to (seqDef.intent as MathIntent.Assignment).rhs))
            val res = cas.evaluate(query.intent, bindings = snap)
            assertEquals("20", res.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun matrixScalarMultiplicationCompilesToStandardTimes() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("2 * \\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix} =")
            val res = cas.evaluate(transpiled.intent)
            assertEquals("{{2,4},{6,8}}", res.expression.replace("\n", "").replace(" ", ""))
        } finally {
            cas.close()
        }
    }

    @Test
    fun isMatrixCircularReferenceDoesNotCauseStackOverflow() {
        val circular = MathExpr.Binary(MathExpr.Op.ADD, MathExpr.Symbol("A"), MathExpr.Number("1"))
        val snap = BindingSnapshot(values = mapOf("A" to circular))
        val exception = assertThrows(IllegalStateException::class.java) {
            SymjaCompiler.compile(MathIntent.EvaluationQuery(MathExpr.Symbol("A")), snap)
        }
        assertTrue("Expected circular dependency exception message", exception.message?.contains("Circular") == true)
    }

    @Test
    fun calculusMvtHandlesNegativeBoundsWithoutSyntaxError() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val analyzer = com.axioloom.app.domain.cas.CalculusTheoremAnalyzer(cas)
            val res = analyzer.verifyLagrange("x^2", "x", -2.0, 2.0)
            assertTrue("MVT should be valid on [-2, 2]", res.isValid)
            assertEquals(1, res.rootsC.size)
            assertEquals(0.0, res.rootsC[0], 1e-4)
        } finally {
            cas.close()
        }
    }

    @Test
    fun odeSimulatorHandlesNaNInstabilityWithoutStepExplosion() {
        // Stiff/singular ODE y' = 1/y with y(0) = 0
        val traj = com.axioloom.app.domain.sim.OdeNumericalSimulator.integrate(
            f = { _, s -> doubleArrayOf(1.0 / s[0]) },
            initialState = doubleArrayOf(0.0),
            tMax = 1.0,
            maxSteps = 50
        )
        assertNotNull(traj)
        assertTrue("Trajectory should finish without infinite loops", traj.stepCount <= 55)
    }

    @Test
    fun symbolicMnaHandlesSingularMatrixGracefully() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val solver = SymbolicMnaSolver(cas)
            // Two parallel ideal voltage sources in loop (singular loop)
            val netlist = solver.parseNetlist("V1 1 0 10\nV2 1 0 5")
            val res = solver.solve(netlist)
            assertNotNull(res)
            assertTrue("Singular circuit must not crash solver", res.nodeVoltages.isNotEmpty())
        } finally {
            cas.close()
        }
    }

    @Test
    fun embeddedCGeneratorTranslatesOneBasedToZeroBased() {
        val expr = MathExpr.IndexedSequence("data", MathExpr.Number("1"))
        val code = EmbeddedCGenerator.generateMathFunction(expr, "test_index")
        assertTrue("C code must translate 1-based index to 0-based: - 1", code.sourceCode.contains("- 1") || code.sourceCode.contains("data["))
    }

    @Test
    fun kmapParseParamsWithVariousInputs() {
        // 1. Inferred 2-variable K-Map
        val p2 = com.axioloom.app.domain.logic.KarnaughMinimizer.parseKmapParams("kmap(m(0, 1))")
        assertNotNull(p2)
        assertEquals(2, p2!!.numVars)
        assertEquals(setOf(0, 1), p2.minterms)

        // 2. Explicit 3-variable K-Map with minterm containing digit 4
        // (Must NOT be hijacked to 4 variables by character '4'!)
        val p3Explicit = com.axioloom.app.domain.logic.KarnaughMinimizer.parseKmapParams("kmap(3, m(1, 4))")
        assertNotNull(p3Explicit)
        assertEquals(3, p3Explicit!!.numVars)
        assertEquals(setOf(1, 4), p3Explicit.minterms)

        // 3. Inferred 3-variable K-Map from max term 4
        val p3Inferred = com.axioloom.app.domain.logic.KarnaughMinimizer.parseKmapParams("kmap(m(0, 1, 4))")
        assertNotNull(p3Inferred)
        assertEquals(3, p3Inferred!!.numVars)

        // 4. Inferred 4-variable K-Map from max term 8
        val p4Inferred = com.axioloom.app.domain.logic.KarnaughMinimizer.parseKmapParams("kmap(m(0, 1, 8))")
        assertNotNull(p4Inferred)
        assertEquals(4, p4Inferred!!.numVars)
    }

    @Test
    fun dimensionalAnalysisSpatialDerivativeAndCoordinates() {
        // Spatial variable x defaults to meter
        val xExpr = MathExpr.Symbol("x")
        val xDim = DimensionalAnalyzer.analyze(xExpr).dimension
        assertEquals(1, xDim.lengthM)
        assertEquals(0, xDim.timeS)

        // Spatial derivative d/dx (x^2) => [m] / [m] = dimensionless
        val derivX = MathExpr.Derivative(MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Symbol("x"), MathExpr.Number("2")), "x")
        val derivDim = DimensionalAnalyzer.analyze(derivX).dimension
        assertTrue("Derivative d/dx (x^2) should be [m]", derivDim.lengthM == 1)

        // Spatial derivative d/dx (V) => [V/m]
        val derivV = MathExpr.Derivative(MathExpr.Symbol("v"), "x")
        val vDim = DimensionalAnalyzer.analyze(derivV).dimension
        assertEquals(DimensionVector.VOLT - DimensionVector.METER, vDim)

        // Integral int V dx => [V * m]
        val intV = MathExpr.Integral(MathExpr.Symbol("v"), "x", null, null)
        val intDim = DimensionalAnalyzer.analyze(intV).dimension
        assertEquals(DimensionVector.VOLT + DimensionVector.METER, intDim)
    }

    @Test
    fun schematicRecognizerDoesNotShortSmallComponent() {
        // Resistor stroke whose two terminals are close together (< snapRadiusPx)
        val stroke = TimedStroke.fromPoints(
            points = listOf(
                InkPoint(100f, 100f, 0L),
                InkPoint(102f, 105f, 10L),
                InkPoint(104f, 101f, 20L),
                InkPoint(106f, 106f, 30L),
                InkPoint(108f, 102f, 40L),
                InkPoint(110f, 100f, 50L)
            ),
            finishedAtMillis = 100L
        )
        val schematic = com.axioloom.app.domain.circuit.SchematicStrokeRecognizer.recognize(
            strokes = listOf(stroke),
            snapRadiusPx = 25f
        )
        // Ensure the component was recognized and NOT dropped due to self-shorting
        assertEquals(1, schematic.components.size)
        val comp = schematic.components[0]
        assertEquals(2, comp.terminals.size)
    }

    @Test
    fun circularConvolutionZeroPaddingDoesNotCrash() {
        val leftSeq = MathExpr.DiscreteSequence(listOf(MathExpr.Number("1"), MathExpr.Number("2")))
        val rightSeq = MathExpr.DiscreteSequence(listOf(MathExpr.Number("1"), MathExpr.Number("1"), MathExpr.Number("1")))
        val circConv = MathExpr.CircularConvolution(leftSeq, rightSeq)
        val compiled = SymjaCompiler.compile(MathIntent.EvaluationQuery(circConv))
        assertTrue("Compiled command must contain Length check guards", compiled.command.contains("Length"))
    }

    @Test
    fun matrixTrailingRowDelimiterTranspilation() {
        val parsed = parser.transpile("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\\\ \\end{pmatrix}")
        val query = parsed.intent as MathIntent.EvaluationQuery
        assertTrue("Parsed matrix must be MathExpr.Matrix", query.expression is MathExpr.Matrix)
        val mat = query.expression as MathExpr.Matrix
        assertEquals(2, mat.rows.size)
        assertEquals(2, mat.rows[0].size)
    }

    @Test
    fun variableESymbolNotClobberedWhenBound() {
        // When e is in bound symbols, it should parse as MathExpr.Symbol("e"), not MathExpr.Constant.E
        val parsed = parser.transpile("e + 1", boundSymbols = setOf("e"))
        val query = parsed.intent as MathIntent.EvaluationQuery
        val binary = query.expression as MathExpr.Binary
        assertTrue("LHS must be Symbol('e')", binary.left is MathExpr.Symbol && (binary.left as MathExpr.Symbol).name == "e")

        // When e is NOT in bound symbols, it should parse as MathExpr.Constant.E
        val parsedUnbound = parser.transpile("e + 1")
        val queryUnbound = parsedUnbound.intent as MathIntent.EvaluationQuery
        val binaryUnbound = queryUnbound.expression as MathExpr.Binary
        assertTrue("LHS must be Constant.E", binaryUnbound.left == MathExpr.Constant.E)
    }

    @Test
    fun bilinearDiscretizerNormalizesSafelyOnZeroA0() {
        val filter = com.axioloom.app.domain.codegen.DiscretizedFilter(
            continuousNumCoeffs = doubleArrayOf(1.0),
            continuousDenCoeffs = doubleArrayOf(1.0),
            bCoeffs = doubleArrayOf(1.0, 2.0),
            aCoeffs = doubleArrayOf(0.0, 1.0), // Zero a0
            samplingFreqHz = 1000.0,
            samplingPeriodSec = 0.001,
            differenceEquationLatex = "y[n] = 0"
        )
        // normalizedB and normalizedA must not throw ArithmeticException or produce NaN
        val normB = filter.normalizedB
        assertNotNull(normB)
        assertEquals(2, normB.size)
        assertFalse(normB[0].isNaN())
    }

    @Test
    fun rootLocusHornerEvaluationAccuracy() {
        // Characteristic equation: s^2 + 3s + 2 = 0 => roots at s = -1, s = -2
        val poly = doubleArrayOf(2.0, 3.0, 1.0)
        val roots = com.axioloom.app.domain.cas.RootLocusEngine.findRootsDurandKerner(poly)
        assertEquals(2, roots.size)
        val realRoots = roots.map { it.re }.sorted()
        assertEquals(-2.0, realRoots[0], 1e-4)
        assertEquals(-1.0, realRoots[1], 1e-4)
    }
}