package com.axioloom.app.domain

import com.axioloom.app.domain.cas.OfflineCasEngine
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.MathIntent
import com.axioloom.app.domain.parser.AmbiguousSolveVariableException
import com.axioloom.app.domain.parser.LatexToSymjaTranspiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Phase5GrammarTest {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun trigIdentitySinSquaredPlusCosSquaredEvaluatesToOne() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("\\sin^2(x) + \\cos^2(x) =")
            val result = cas.evaluate(transpiled.intent)
            assertEquals("1", result.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun inverseTrigArcsinEvaluatesToExactFraction() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiledArcsin = parser.transpile("\\arcsin(1/2) =")
            assertTrue(transpiledArcsin.intent is MathIntent.EvaluationQuery)
            val query = transpiledArcsin.intent as MathIntent.EvaluationQuery
            assertTrue(query.expression is MathExpr.Builtin)
            assertEquals(MathExpr.Function.ASIN, (query.expression as MathExpr.Builtin).function)

            val resultArcsin = cas.evaluate(transpiledArcsin.intent)
            assertEquals("Pi/6", resultArcsin.expression)

            val transpiledSinInv = parser.transpile("\\sin^{-1}(1/2) =")
            val resultSinInv = cas.evaluate(transpiledSinInv.intent)
            assertEquals("Pi/6", resultSinInv.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun postfixFactorialEvaluatesCorrectly() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("5! =")
            assertTrue(transpiled.intent is MathIntent.EvaluationQuery)
            val query = transpiled.intent as MathIntent.EvaluationQuery
            assertTrue(query.expression is MathExpr.Builtin)
            assertEquals(MathExpr.Function.FACTORIAL, (query.expression as MathExpr.Builtin).function)

            val result = cas.evaluate(transpiled.intent)
            assertEquals("120", result.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun higherOrderDerivativeEvaluatesCorrectly() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val transpiled = parser.transpile("\\frac{d^2}{dx^2}(x^4) =")
            assertTrue(transpiled.intent is MathIntent.EvaluationQuery)
            val query = transpiled.intent as MathIntent.EvaluationQuery
            assertTrue(query.expression is MathExpr.Derivative)
            val deriv = query.expression as MathExpr.Derivative
            assertEquals("x", deriv.variable)
            assertEquals(2, deriv.order)

            val result = cas.evaluate(transpiled.intent)
            assertEquals("12*x^2", result.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun ambiguousSolveVariableThrowsExceptionWithSortedCandidates() {
        try {
            parser.transpile("x + y = 10")
            fail("Expected AmbiguousSolveVariableException when solving multi-variable equation without explicit target")
        } catch (ex: AmbiguousSolveVariableException) {
            assertEquals(listOf("x", "y"), ex.candidates)
        }

        val resolved = parser.transpile("x + y = 10", solveTarget = "x")
        assertTrue(resolved.intent is MathIntent.EquationSolve)
        val solve = resolved.intent as MathIntent.EquationSolve
        assertEquals("x", solve.targetVar)
    }
}
