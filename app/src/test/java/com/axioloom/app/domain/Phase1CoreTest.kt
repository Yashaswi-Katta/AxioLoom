package com.axioloom.app.domain

import com.axioloom.app.domain.cas.OfflineCasEngine
import com.axioloom.app.domain.dag.ReactiveSymbolGraph
import com.axioloom.app.domain.diagnostic.Phase1DiagnosticHarness
import com.axioloom.app.domain.model.BlockStatus
import com.axioloom.app.domain.model.CasMode
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.MathIntent
import com.axioloom.app.domain.model.SymbolKey
import com.axioloom.app.domain.model.references
import com.axioloom.app.domain.parser.LatexToSymjaTranspiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase1CoreTest {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun unicodeMathOperatorsAndConstantsTranspileAndEvaluate() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val parsedOp = parser.transpile("2 \u00D7 3 \u2212 4 \u00F7 2 =")
            val evalOp = cas.evaluate(parsedOp.intent)
            assertEquals("4", evalOp.expression)

            val parsedPi = parser.transpile("\u03C0")
            val piQuery = parsedPi.intent as MathIntent.EvaluationQuery
            assertEquals(MathExpr.Constant.PI, piQuery.expression)

            val parsedE = parser.transpile("e^2 =?")
            val evalE = cas.evaluate(parsedE.intent, mode = CasMode.NUMERIC)
            assertEquals("7.38906", evalE.expression)
        } finally {
            cas.close()
        }
    }

    @Test
    fun selfReassignmentUpdatesExistingVariableWithoutTreatingAsEquationSolve() {
        val transpiled = parser.transpile("a = 2", boundSymbols = setOf("a"))
        assertEquals(MathIntent.Assignment("a", MathExpr.Number("2")), transpiled.intent)
    }

    @Test
    fun integralRecognizesOnlyItsUnboundParameterAsDependency() {
        val intent = parser.transpile("\\int_{0}^{a} 3 x^2 d x =").intent as MathIntent.EvaluationQuery
        assertEquals(setOf(SymbolKey("a")), intent.expression.references())
        assertTrue(intent.expression is MathExpr.Integral)
    }

    @Test
    fun requiredIntegralDiagnosticPropagatesWithoutRecognitionPass() = runBlocking {
        val result = Phase1DiagnosticHarness.run()
        assertEquals("4", result.assignmentValueBefore)
        assertEquals("64", result.integralValueBefore)
        assertEquals("2", result.assignmentValueAfter)
        assertEquals("8", result.integralValueAfter)
    }

    @Test
    fun allNodesInVariableCycleAreMarkedError() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            val graph = ReactiveSymbolGraph(cas)
            val aId = UUID.randomUUID()
            val bId = UUID.randomUUID()
            graph.upsert(aId, parser.transpile("a = b + 1").intent)
            val result = graph.upsert(bId, parser.transpile("b = 2 a").intent)
            assertEquals(setOf(aId, bId), result.cyclicBlocks)
            assertTrue(result.blocks.getValue(aId).status is BlockStatus.Error)
            assertTrue(result.blocks.getValue(bId).status is BlockStatus.Error)
        } finally {
            cas.close()
        }
    }

    @Test
    fun unsupportedTexCommandFailsClosed() {
        try {
            parser.transpile("\\write18{bad}")
            throw AssertionError("Unsupported command was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected: unknown commands are rejected before reaching Symja.
        }
    }
}
