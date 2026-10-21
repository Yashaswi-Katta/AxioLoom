package com.offline.inkmath.domain

import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.model.SymbolicTransform
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7EngineeringCasTest {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun matrixMultiplicationCompilesToDotProduct() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val latex = "\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix} * \\begin{pmatrix} 2 & 0 \\\\ 1 & 2 \\end{pmatrix} ="
            val transpiled = parser.transpile(latex)
            assertTrue(transpiled.intent is MathIntent.EvaluationQuery)

            val result = cas.evaluate(transpiled.intent)
            // [1*2 + 2*1, 1*0 + 2*2] = [4, 4]
            // [3*2 + 4*1, 3*0 + 4*2] = [10, 8]
            assertEquals("{{4,4},{10,8}}", result.expression.replace("\n", "").replace(" ", ""))
        } finally {
            cas.close()
        }
    }

    @Test
    fun matrixInverseCompilesCorrectly() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val latex = "\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}^{-1} ="
            val transpiled = parser.transpile(latex)
            val result = cas.evaluate(transpiled.intent)
            // Det = 1*4 - 2*3 = -2. Inverse = {{-2, 1}, {3/2, -1/2}}
            assertEquals("{{-2,1},{3/2,-1/2}}", result.expression.replace("\n", "").replace(" ", ""))
        } finally {
            cas.close()
        }
    }

    @Test
    fun eigenvaluesBuiltinEvaluatesCorrectly() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val latex = "\\operatorname{eigenvalues}(\\begin{pmatrix} 2 & 0 \\\\ 0 & 5 \\end{pmatrix}) ="
            val transpiled = parser.transpile(latex)
            val result = cas.evaluate(transpiled.intent)
            assertTrue("Expected eigenvalues 2 and 5 in ${result.expression}", result.expression.contains("2") && result.expression.contains("5"))
        } finally {
            cas.close()
        }
    }

    @Test
    fun partialFractionsTransformationProducesApartForm() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val latex = "\\frac{1}{s^2 + 3s + 2} ="
            val transpiled = parser.transpile(latex)
            val result = cas.evaluate(transpiled.intent, transform = SymbolicTransform.PARTIAL_FRACTIONS)
            // Apart of 1/((s+1)(s+2)) is 1/(1+s) - 1/(2+s)
            assertTrue(
                "Result expression should contain partial fractions: ${result.expression}",
                result.expression.contains("1/(1+s)") && result.expression.contains("1/(2+s)")
            )
        } finally {
            cas.close()
        }
    }

    @Test
    fun phasorArithmeticEvaluatesInDegrees() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiledPureZero = parser.transpile("10 \\angle 0 =")
            val resultZero = cas.evaluate(transpiledPureZero.intent)
            assertEquals("10", resultZero.expression)

            val transpiledPure90 = parser.transpile("10 \\angle 90 =")
            val result90 = cas.evaluate(transpiledPure90.intent)
            assertTrue("Expected 10*I or I*10", result90.expression == "10*I" || result90.expression == "I*10")

            val transpiledSum = parser.transpile("10 \\angle 0 + 10 \\angle 90 =")
            val resultSum = cas.evaluate(transpiledSum.intent)
            assertTrue("Expected 10 and I in sum", resultSum.expression.contains("10") && resultSum.expression.contains("I"))
        } finally {
            cas.close()
        }
    }

    @Test
    fun ordinaryDifferentialEquationSolvesViaDSolve() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("y' + y = 0")
            assertTrue(
                "y' + y = 0 must be classified as OdeSolve",
                transpiled.intent is MathIntent.OdeSolve
            )
            val ode = transpiled.intent as MathIntent.OdeSolve
            assertEquals("y", ode.dependentVar)
            assertEquals("x", ode.independentVar)

            val result = cas.evaluate(transpiled.intent)
            assertTrue(
                "DSolve result should solve for y[x]: ${result.expression}",
                (result.expression.contains("y[x]->") || result.expression.contains("y(x)->")) && (result.expression.contains("E^(-x)") || result.expression.contains("/E^x") || result.expression.contains("E^x"))
            )
        } finally {
            cas.close()
        }
    }
}
