package com.axioloom.app.domain

import androidx.compose.ui.geometry.Rect
import com.axioloom.app.domain.cas.ComplexNumber
import com.axioloom.app.domain.cas.RootLocusEngine
import com.axioloom.app.domain.circuit.SchematicComponentType
import com.axioloom.app.domain.circuit.SchematicStrokeRecognizer
import com.axioloom.app.domain.codegen.BilinearDiscretizer
import com.axioloom.app.domain.codegen.CPrecision
import com.axioloom.app.domain.codegen.EmbeddedCGenerator
import com.axioloom.app.domain.model.BinaryOperator
import com.axioloom.app.domain.model.InkPoint
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.StrokeGeometry
import com.axioloom.app.domain.model.TimedStroke
import com.axioloom.app.domain.sim.OdeNumericalSimulator
import com.axioloom.app.domain.units.DimensionVector
import com.axioloom.app.domain.units.DimensionalAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class Phase26To30V2Test {

    private fun makeSyntheticStroke(points: List<Pair<Float, Float>>, elapsedStepMs: Long = 10L): TimedStroke {
        val inkPoints = points.mapIndexed { idx, (x, y) ->
            InkPoint(x, y, idx * elapsedStepMs)
        }
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        val geom = StrokeGeometry(
            id = UUID.randomUUID(),
            points = inkPoints,
            bounds = Rect(minX, minY, maxX, maxY)
        )
        return TimedStroke.forTesting(geom)
    }

    @Test
    fun schematicStrokeRecognizerClassifiesResistorCapacitorAndGround() {
        // 1. Synthetic zig-zag resistor stroke: 6 lateral peaks perpendicular to x-axis
        val resistorPts = mutableListOf<Pair<Float, Float>>()
        resistorPts.add(0f to 0f)
        for (i in 1..6) {
            val x = i * 15f
            val y = if (i % 2 == 1) 18f else -18f
            resistorPts.add(x to y)
        }
        resistorPts.add(105f to 0f)
        val resistorStroke = makeSyntheticStroke(resistorPts)
        val rComp = SchematicStrokeRecognizer.classifySingleStroke(resistorStroke)
        assertEquals("Zig-zag stroke must be classified as Resistor", SchematicComponentType.RESISTOR, rComp.type)
        assertEquals(2, rComp.terminals.size)

        // 2. Synthetic capacitor: two parallel vertical plates separated by narrow gap
        val capPlate1 = makeSyntheticStroke(listOf(50f to 0f, 50f to 60f))
        val capPlate2 = makeSyntheticStroke(listOf(65f to 0f, 65f to 60f))
        val capSchematic = SchematicStrokeRecognizer.recognize(listOf(capPlate1, capPlate2))
        val capacitor = capSchematic.components.firstOrNull { it.type == SchematicComponentType.CAPACITOR }
        assertNotNull("Parallel strokes must be recognized as Capacitor", capacitor)
        assertEquals(2, capacitor!!.terminals.size)

        // 3. Synthetic 3-bar ground symbol: decreasing horizontal bars stacked vertically
        val gndBar1 = makeSyntheticStroke(listOf(20f to 100f, 80f to 100f)) // width 60
        val gndBar2 = makeSyntheticStroke(listOf(30f to 112f, 70f to 112f)) // width 40
        val gndBar3 = makeSyntheticStroke(listOf(40f to 124f, 60f to 124f)) // width 20

        val gndSchematic = SchematicStrokeRecognizer.recognize(listOf(gndBar1, gndBar2, gndBar3))
        val groundComp = gndSchematic.components.firstOrNull { it.type == SchematicComponentType.GROUND }
        assertNotNull("Three decreasing horizontal bars must be recognized as Ground", groundComp)
        assertEquals(1, groundComp!!.terminals.size)

        // 4. Combined circuit with wire connecting resistor to ground
        val wireStroke = makeSyntheticStroke(listOf(105f to 0f, 105f to 95f, 50f to 100f))
        val fullSchematic = SchematicStrokeRecognizer.recognize(
            listOf(resistorStroke, wireStroke, gndBar1, gndBar2, gndBar3),
            snapRadiusPx = 30f
        )
        assertTrue("Schematic elements should contain Resistor", fullSchematic.elements.isNotEmpty())
        assertTrue("Ground node 0 must be present in node positions", fullSchematic.nodePositions.containsKey(0))
    }

    @Test
    fun bilinearDiscretizerAndEmbeddedCGeneratorEmitValidC99Filter() {
        // Continuous 1st-order low-pass: H(s) = 1 / (s + 1)
        // Discretize at fs = 100 Hz (Ts = 0.01 s)
        val numCoeffs = doubleArrayOf(1.0)       // 1
        val denCoeffs = doubleArrayOf(1.0, 1.0)  // 1 + 1*s

        val filter = BilinearDiscretizer.discretize(numCoeffs, denCoeffs, samplingFreqHz = 100.0)

        assertEquals("Order 1 filter should have 2 numerator coefficients", 2, filter.bCoeffs.size)
        assertEquals("Order 1 filter should have 2 denominator coefficients", 2, filter.aCoeffs.size)

        // DC Gain H(z=1) must be exactly 1.0
        val dcNum = filter.bCoeffs.sum()
        val dcDen = filter.aCoeffs.sum()
        val dcGain = dcNum / dcDen
        assertEquals("DC Gain of low-pass filter must be 1.0", 1.0, dcGain, 1e-4)

        // Generate C99 firmware
        val cCode = EmbeddedCGenerator.generateFilterDriver(filter, filterPrefix = "lpf_100hz", precision = CPrecision.FLOAT)

        assertTrue("Header should contain function declaration", cCode.headerCode.contains("lpf_100hz_update"))
        assertTrue("Header should declare state struct", cCode.headerCode.contains("lpf_100hz_state_t"))
        assertTrue("Source should contain feedforward B coefficients", cCode.sourceCode.contains("B_COEFFS"))
        assertTrue("Source should contain feedback A coefficients", cCode.sourceCode.contains("A_COEFFS"))
        assertTrue("Arduino snippet should contain loop update", cCode.arduinoSnippet.contains("lpf_100hz_update"))

        // Also test MathExpr to C99 function generation (Mode A)
        val mathExpr = MathExpr.BinaryOp(
            BinaryOperator.MULTIPLY,
            MathExpr.FunctionCall("sin", MathExpr.Variable("theta")),
            MathExpr.FunctionCall("exp", MathExpr.Variable("t"))
        )
        val mathC = EmbeddedCGenerator.generateMathFunction(mathExpr, functionName = "pendulum_force", precision = CPrecision.FLOAT)
        assertTrue("C header should declare function", mathC.headerCode.contains("float pendulum_force(float t, float theta);"))
        assertTrue("C source should include sinf and expf", mathC.sourceCode.contains("sinf") && mathC.sourceCode.contains("expf"))
    }

    @Test
    fun rk45SimulatorAccuratelyIntegratesHarmonicOscillatorAndVanDerPol() {
        // 1. Harmonic Oscillator: y'' + y = 0, y(0) = 1, y'(0) = 0
        // Analytical solution: y(t) = cos(t). At t = 2*pi, y(2*pi) = 1.0, y'(2*pi) = 0.0
        val tFinal = 2.0 * PI
        val trajHarmonic = OdeNumericalSimulator.harmonicOscillator(omega = 1.0, y0 = 1.0, v0 = 0.0, tMax = tFinal)

        assertTrue("Trajectory should contain multiple adaptive steps", trajHarmonic.stepCount > 10)
        val finalState = trajHarmonic.states.last()
        val finalPos = finalState[0]
        val finalVel = finalState[1]

        assertEquals("Harmonic oscillator position at 2*pi should be 1.0", 1.0, finalPos, 1e-4)
        assertEquals("Harmonic oscillator velocity at 2*pi should be 0.0", 0.0, finalVel, 1e-4)

        // 2. Van der Pol non-linear oscillator
        val trajVdp = OdeNumericalSimulator.vanDerPol(mu = 1.5, y0 = 0.5, v0 = 0.0, tMax = 10.0)
        assertTrue("VdP should integrate smoothly", trajVdp.stepCount > 20)
        for (st in trajVdp.states) {
            assertTrue("State must be finite (no divergence)", st[0].isFinite() && st[1].isFinite())
        }
    }

    @Test
    fun dimensionalAnalyzerConfirmsRcTimeConstantIsSecondsAndCatchesUnitMismatch() {
        // 1. [Ohm] * [Farad] = [s] (RC time constant)
        val rcDimension = DimensionVector.OHM + DimensionVector.FARAD
        assertEquals("Ohm * Farad must equal Second", DimensionVector.SECOND, rcDimension)
        assertEquals("[s]", rcDimension.toCompactUnitString())

        // 2. [Henry] / [Ohm] = [s] (L/R inductive time constant)
        val lrDimension = DimensionVector.HENRY - DimensionVector.OHM
        assertEquals("Henry / Ohm must equal Second", DimensionVector.SECOND, lrDimension)
        assertEquals("[s]", lrDimension.toCompactUnitString())

        // 3. Dimensional Mismatch Warning: V + I
        val mismatchExpr = MathExpr.BinaryOp(
            BinaryOperator.ADD,
            MathExpr.Variable("V"),
            MathExpr.Variable("I")
        )
        val analysis = DimensionalAnalyzer.analyze(mismatchExpr)
        assertTrue("Adding Voltage and Current must trigger warning", analysis.warnings.isNotEmpty())
        assertTrue("Warning message must mention add/mismatch", analysis.warnings.first().message.contains("add"))
    }

    @Test
    fun aberthEhrlichRootLocusFindsExactCriticalGainAndClosedLoopPoles() {
        // G(s) = 1 / (s * (s + 1) * (s + 2)) = 1 / (s^3 + 3s^2 + 2s + 0)
        // Characteristic equation: s^3 + 3s^2 + 2s + K = 0
        // Routh-Hurwitz analytical result:
        // Critical gain Kcrit = 6.0, where closed-loop poles cross jw axis at w = sqrt(2) ~ 1.4142 rad/s
        val numCoeffs = doubleArrayOf(1.0)                      // 1
        val denCoeffs = doubleArrayOf(0.0, 2.0, 3.0, 1.0)       // 0 + 2s + 3s^2 + s^3

        val analysis = RootLocusEngine.analyze(numCoeffs, denCoeffs, kMax = 15.0, numKSteps = 80)

        // Verify open loop poles at 0, -1, -2
        assertEquals(3, analysis.openLoopPoles.size)
        assertTrue("Must have pole at s = 0", analysis.openLoopPoles.any { abs(it.re) < 1e-4 && abs(it.im) < 1e-4 })
        assertTrue("Must have pole at s = -1", analysis.openLoopPoles.any { abs(it.re - (-1.0)) < 1e-4 })
        assertTrue("Must have pole at s = -2", analysis.openLoopPoles.any { abs(it.re - (-2.0)) < 1e-4 })

        // Verify critical gain Kcrit = 6.0 and critical crossing frequency w = sqrt(2)
        val kCrit = analysis.margins.criticalGainK
        val wCrit = analysis.margins.criticalFreqRad
        assertNotNull("Critical gain must be detected", kCrit)
        assertNotNull("Critical frequency must be detected", wCrit)

        assertEquals("Critical gain Kcrit must be ~6.0", 6.0, kCrit!!, 0.3)
        assertEquals("Critical frequency must be ~sqrt(2) rad/s", sqrt(2.0), wCrit!!, 0.15)

        // At K = 6.0, verify Durand-Kerner finds imaginary roots at +- j*sqrt(2)
        val polyAt6 = doubleArrayOf(6.0, 2.0, 3.0, 1.0)
        val polesAt6 = RootLocusEngine.findRootsDurandKerner(polyAt6)
        assertTrue("Must find conjugate imaginary roots near +- j*sqrt(2)",
            polesAt6.any { abs(it.re) < 0.05 && abs(abs(it.im) - sqrt(2.0)) < 0.05 })
    }
}
