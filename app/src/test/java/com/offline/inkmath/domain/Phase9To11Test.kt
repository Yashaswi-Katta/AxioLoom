package com.offline.inkmath.domain

import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.cas.StepDerivationEngine
import com.offline.inkmath.domain.evaluator.FastPlotEvaluator
import com.offline.inkmath.domain.model.*
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase9To11Test {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun siEngineeringSuffixesExpansion() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // Test 1: 4.7k + 300 =
            val transpiled1 = parser.transpile("4.7k + 300 =")
            val result1 = cas.evaluate(transpiled1.intent)
            assertEquals("5000", result1.expression.removeSuffix(".0"))

            // Test 2: 10u expansion
            val expanded = EngineeringConstants.expandSiSuffixes("10u")
            assertTrue("Expected 10 * 10^-6 in $expanded", expanded.contains("10^-6"))

            // Test 3: 2.4G
            val expandedG = EngineeringConstants.expandSiSuffixes("2.4G")
            assertTrue("Expected 10^9 in $expandedG", expandedG.contains("10^9"))
        } finally {
            cas.close()
        }
    }

    @Test
    fun physicalConstantsLibraryBindings() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val constants = EngineeringConstants.getPhysicalConstantsBindings()
            assertTrue(constants.containsKey("c_0"))
            assertTrue(constants.containsKey("q_e"))
            assertTrue(constants.containsKey("k_B"))
            assertTrue(constants.containsKey("eps_0"))
            assertTrue(constants.containsKey("mu_0"))
            assertTrue(constants.containsKey("g_0"))

            val transpiled = parser.transpile("c_0 =")
            val bindings = BindingSnapshot(values = constants)
            val result = cas.evaluate(transpiled.intent, bindings = bindings)
            assertEquals("299792458", result.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun laplaceAndInverseLaplaceTransforms() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // L{ t } = 1/s^2
            val transpiledLaplace = parser.transpile("\\mathcal{L}{t} =")
            assertTrue(transpiledLaplace.intent is MathIntent.EvaluationQuery)
            assertTrue((transpiledLaplace.intent as MathIntent.EvaluationQuery).expression is MathExpr.LaplaceTransform)
            val resultLaplace = cas.evaluate(transpiledLaplace.intent)
            assertTrue("Expected s^(-2) or 1/s^2 or 1/(s^2)", resultLaplace.expression == "s^(-2)" || resultLaplace.expression == "1/s^2" || resultLaplace.expression == "1/(s^2)")

            // L^{-1}{ 1/s^2 } = t
            val transpiledInv = parser.transpile("\\mathcal{L}^{-1}{\\frac{1}{s^2}} =")
            assertTrue((transpiledInv.intent as MathIntent.EvaluationQuery).expression is MathExpr.InverseLaplaceTransform)
            val resultInv = cas.evaluate(transpiledInv.intent)
            assertEquals("t", resultInv.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun heavisideUnitStepAndDiracDeltaASTAndPlot() {
        val transpiledStep = parser.transpile("u(t - 2)")
        assertTrue(transpiledStep.intent is MathIntent.EvaluationQuery)
        val stepExpr = (transpiledStep.intent as MathIntent.EvaluationQuery).expression
        assertTrue(stepExpr is MathExpr.Builtin && stepExpr.function == MathExpr.Function.UNIT_STEP)

        // Verify fast plot evaluator works for UnitStep
        val funcDef = MathIntent.FunctionDef("f", listOf("x"), MathExpr.Builtin(MathExpr.Function.UNIT_STEP, listOf(MathExpr.Binary(MathExpr.Op.SUBTRACT, MathExpr.Symbol("x"), MathExpr.Number("2")))))
        val points = FastPlotEvaluator.sample(funcDef.body, paramName = "x", xMin = 0.0, xMax = 4.0, sampleCount = 5)
        assertEquals(5, points.size)
        // At x = 0 (0 - 2 < 0) -> y = 0
        assertEquals(0.0, points[0].second, 1e-4)
        // At x = 4 (4 - 2 > 0) -> y = 1
        assertEquals(1.0, points[4].second, 1e-4)
    }

    @Test
    fun vectorCalculusGradientDivergenceCurl() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // 1. \nabla (x^2 + y^2) =
            val transpiledGrad = parser.transpile("\\nabla (x^2 + y^2) =")
            assertTrue((transpiledGrad.intent as MathIntent.EvaluationQuery).expression is MathExpr.Gradient)
            val resultGrad = cas.evaluate(transpiledGrad.intent)
            assertEquals("{2*x,2*y,0}", resultGrad.expression)

            // 2. \nabla \cdot {x, y, z} =
            val transpiledDiv = parser.transpile("\\nabla \\cdot \\begin{pmatrix} x \\\\ y \\\\ z \\end{pmatrix} =")
            assertTrue((transpiledDiv.intent as MathIntent.EvaluationQuery).expression is MathExpr.Divergence)
            val resultDiv = cas.evaluate(transpiledDiv.intent)
            assertEquals("3", resultDiv.expression)

            // 3. \nabla \times {x, y, z} =
            val transpiledCurl = parser.transpile("\\nabla \\times \\begin{pmatrix} x \\\\ y \\\\ z \\end{pmatrix} =")
            assertTrue((transpiledCurl.intent as MathIntent.EvaluationQuery).expression is MathExpr.Curl)
            val resultCurl = cas.evaluate(transpiledCurl.intent)
            assertEquals("{0,0,0}", resultCurl.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun stepDerivationDefiniteIntegral() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("\\int_0^1 x^2 dx =")
            val block = EquationBlock(
                id = UUID.randomUUID(),
                strokes = emptyList(),
                bounds = Rect(0f, 0f, 100f, 50f),
                medianStrokeHeight = 24f,
                resultAnchorPoint = null,
                recognizedLatex = "\\int_0^1 x^2 dx =",
                intent = transpiled.intent
            )

            val steps = StepDerivationEngine.deriveSteps(block, BindingSnapshot(), cas)
            assertTrue("Expected at least 3 derivation steps, got ${steps.size}", steps.size >= 3)
            assertEquals("Initial Problem", steps[0].title)

            val intermediate = steps.find { it.title.contains("Antiderivative") }
            assertTrue("Expected antiderivative step to be present", intermediate != null)
            assertTrue(intermediate!!.latexExpression.contains("x^3") || intermediate.latexExpression.contains("3"))

            val exact = steps.find { it.title.contains("Exact Result") }
            assertTrue("Expected exact result step to be present", exact != null)
            assertEquals("1/3", exact!!.latexExpression.replace(" ", "").replace("\\frac{1}{3}", "1/3"))
        } finally {
            cas.close()
        }
    }
}
