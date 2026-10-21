package com.offline.inkmath.domain

import com.offline.inkmath.domain.cas.CalculusTheoremAnalyzer
import com.offline.inkmath.domain.cas.MatrixAnalyzer
import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.dsp.Complex
import com.offline.inkmath.domain.dsp.DiscreteSignalEngine
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import com.offline.inkmath.domain.templates.WorkspaceTemplates
import com.offline.inkmath.ui.canvas.SmithChartMath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class Phase18To20Test {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun lagrangeMvtFindsExactTangentPointOnQuadraticAndCubic() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val analyzer = CalculusTheoremAnalyzer(cas)

            // 1. Quadratic: f(x) = x^2 on [0, 4] => f'(c) = (16 - 0)/4 = 4 => 2c = 4 => c = 2
            val lagrangeResult = analyzer.verifyLagrange("x^2", "x", 0.0, 4.0)
            assertTrue("Lagrange MVT should be valid on [0, 4]", lagrangeResult.isValid)
            assertEquals("Expected 1 root for quadratic MVT", 1, lagrangeResult.rootsC.size)
            assertEquals(2.0, lagrangeResult.rootsC[0], 1e-4)
            assertEquals(4.0, lagrangeResult.secantSlope, 1e-4)
            assertTrue(lagrangeResult.tangentLines.isNotEmpty())
            assertEquals(2.0, lagrangeResult.tangentLines[0].c, 1e-4)

            // 2. Cubic: f(x) = x^3 - 3x on [-sqrt(3), sqrt(3)] where f = 0 => Rolle's theorem finds c = +-1
            val sqrt3 = sqrt(3.0)
            val rollesResult = analyzer.verifyRolles("x^3 - 3*x", "x", -sqrt3, sqrt3)
            assertTrue("Rolle's theorem should be valid on [-sqrt(3), sqrt(3)]", rollesResult.isValid)
            assertEquals("Expected 2 critical points (+-1)", 2, rollesResult.rootsC.size)
            assertTrue("Expected root c = -1", rollesResult.rootsC.any { abs(it - (-1.0)) < 1e-4 })
            assertTrue("Expected root c = +1", rollesResult.rootsC.any { abs(it - 1.0) < 1e-4 })
        } finally {
            cas.close()
        }
    }

    @Test
    fun matrixAnalyzerComputesCharacteristicPolynomialAndCayleyHamilton() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val matrixAnalyzer = MatrixAnalyzer(cas)

            // A = [[4, 1], [2, 3]]
            val result = matrixAnalyzer.analyzeMatrix(listOf(listOf(4.0, 1.0), listOf(2.0, 3.0)))

            // p(lambda) = lambda^2 - 7*lambda + 10
            assertTrue(
                "Expected lambda^2 in characteristic polynomial, got: ${result.characteristicPolynomialRaw}",
                result.characteristicPolynomialRaw.contains("lambda^2")
            )
            assertTrue(
                "Expected 7*lambda in characteristic polynomial, got: ${result.characteristicPolynomialRaw}",
                result.characteristicPolynomialRaw.contains("7*lambda") || result.characteristicPolynomialRaw.contains("7.0*lambda") || result.characteristicPolynomialRaw.contains("7 * lambda")
            )
            assertTrue(
                "Expected 10 in characteristic polynomial, got: ${result.characteristicPolynomialRaw}",
                result.characteristicPolynomialRaw.contains("10")
            )
            assertEquals("\\lambda^2 - 7\\lambda + 10", result.characteristicPolynomial)

            // Eigenvalues are {5, 2}
            assertTrue("Expected eigenvalue 5", result.eigenvalues.contains("5") || result.numericEigenvalues.contains(5.0))
            assertTrue("Expected eigenvalue 2", result.eigenvalues.contains("2") || result.numericEigenvalues.contains(2.0))

            // Cayley-Hamilton verified
            assertTrue("Cayley-Hamilton should be verified", result.cayleyHamiltonVerified)
            assertTrue(result.cayleyHamiltonEquation.contains("A^2"))
        } finally {
            cas.close()
        }
    }

    @Test
    fun discreteSignalEngineComputesLinearCircularConvolutionAndFft() {
        // 1. Linear convolution of [1, 2, 3] and [1, 1] yields [1, 3, 5, 3]
        val x = listOf(1.0, 2.0, 3.0)
        val h = listOf(1.0, 1.0)
        val y = DiscreteSignalEngine.convolve(x, h)
        assertEquals(listOf(1.0, 3.0, 5.0, 3.0), y)

        val stepResult = DiscreteSignalEngine.convolveWithSteps(x, h)
        assertEquals(listOf(1.0, 3.0, 5.0, 3.0), stepResult.output)
        assertEquals(4, stepResult.steps.size)

        // 2. Circular convolution
        val yCirc = DiscreteSignalEngine.circularConvolve(x, h, 3)
        // y_circ[0] = 1*1 + 3*1 = 4
        // y_circ[1] = 1*1 + 2*1 = 3
        // y_circ[2] = 2*1 + 3*1 = 5
        assertEquals(listOf(4.0, 3.0, 5.0), yCirc)

        // 3. IDFT(DFT(x)) round-trips within 1e-6
        val complexInput = listOf(
            Complex(1.0, 0.0),
            Complex(2.5, 0.0),
            Complex(-1.0, 0.5),
            Complex(3.0, -2.0)
        )
        val dftOut = DiscreteSignalEngine.dft(complexInput)
        val idftOut = DiscreteSignalEngine.idft(dftOut)

        assertEquals(complexInput.size, idftOut.size)
        for (i in complexInput.indices) {
            assertEquals(complexInput[i].re, idftOut[i].re, 1e-6)
            assertEquals(complexInput[i].im, idftOut[i].im, 1e-6)
        }

        // Also verify Radix-2 FFT round-trip
        val fftOut = DiscreteSignalEngine.fft(complexInput)
        val ifftOut = DiscreteSignalEngine.ifft(fftOut)
        for (i in complexInput.indices) {
            assertEquals(complexInput[i].re, ifftOut[i].re, 1e-6)
            assertEquals(complexInput[i].im, ifftOut[i].im, 1e-6)
        }
    }

    @Test
    fun smithChartMathComputesReflectionCoefficientAndVswr() {
        // Z_L = 100 + j0, Z_0 = 50
        val analysis = SmithChartMath.compute(100.0, 0.0, 50.0)

        // z_L = 100/50 = 2
        assertEquals(2.0, analysis.rNorm, 1e-6)
        assertEquals(0.0, analysis.xNorm, 1e-6)

        // Gamma = (2 - 1)/(2 + 1) = 1/3
        assertEquals(1.0 / 3.0, analysis.gammaMag, 1e-6)
        assertEquals(1.0 / 3.0, analysis.gammaRe, 1e-6)
        assertEquals(0.0, analysis.gammaIm, 1e-6)

        // VSWR = (1 + 1/3)/(1 - 1/3) = 2.0
        assertEquals(2.0, analysis.vswr, 1e-6)

        // Quarter wave input impedance Zin = 50^2 / 100 = 25
        assertEquals(25.0, analysis.zInRe, 1e-6)
        assertEquals(0.0, analysis.zInIm, 1e-6)
    }

    @Test
    fun engineeringTemplatesInstantiateProperly() {
        assertEquals(5, WorkspaceTemplates.ALL_TEMPLATES.size)
        val mvtState = WorkspaceTemplates.instantiateTemplate(WorkspaceTemplates.CALCULUS_MVT)
        assertEquals(4, mvtState.blocks.size)
        assertEquals(4, mvtState.strokes.size)

        val linearAlgState = WorkspaceTemplates.instantiateTemplate(WorkspaceTemplates.LINEAR_ALGEBRA)
        assertEquals(4, linearAlgState.blocks.size)

        val dspState = WorkspaceTemplates.instantiateTemplate(WorkspaceTemplates.DIGITAL_LOGIC_DSP)
        assertEquals(2, dspState.blocks.size)
    }

    @Test
    fun discreteConvolutionSyntaxTranspilesToSymja() {
        val transpiled = parser.transpile("\\{1, 2, 3\\} \\ast \\{1, 1\\} =")
        assertTrue(transpiled.intent is com.offline.inkmath.domain.model.MathIntent.EvaluationQuery)
        val expr = (transpiled.intent as com.offline.inkmath.domain.model.MathIntent.EvaluationQuery).expression
        assertTrue(expr is MathExpr.DiscreteConvolution)
        val conv = expr as MathExpr.DiscreteConvolution
        assertTrue(conv.left is MathExpr.DiscreteSequence)
        assertTrue(conv.right is MathExpr.DiscreteSequence)
        assertTrue(transpiled.canonicalSymja.contains("Table"))
    }
}
