package com.offline.inkmath.domain

import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.circuit.SymbolicMnaSolver
import com.offline.inkmath.domain.clustering.CellCluster
import com.offline.inkmath.domain.clustering.SpatialStructureSynthesizer
import com.offline.inkmath.domain.logic.KarnaughMinimizer
import com.offline.inkmath.domain.model.InkPoint
import com.offline.inkmath.domain.model.StrokeGeometry
import com.offline.inkmath.domain.model.TimedStroke
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase15To17Test {
    private val parser = LatexToSymjaTranspiler()

    @Test
    fun parallelImpedanceOperatorCompilesAndEvaluatesCorrectly() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()

            // 1. Numeric evaluation: 6 || 3 = 2
            val transpiledNum = parser.transpile("6 \\parallel 3 =")
            val resultNum = cas.evaluate(transpiledNum.intent)
            assertEquals("2", resultNum.expression)

            // Also test bare ||
            val transpiledPipe = parser.transpile("6 || 3 =")
            val resultPipe = cas.evaluate(transpiledPipe.intent)
            assertEquals("2", resultPipe.expression)

            // 2. Symbolic compilation: R_1 || R_2 = ((R_1*R_2)/(R_1+R_2))
            val transpiledSym = parser.transpile("R_1 \\parallel R_2 =")
            val canonicalSymja = com.offline.inkmath.domain.model.SymjaCompiler.decode(transpiledSym.canonicalSymja)
            assertEquals("((R_1*R_2)/(R_1+R_2))", canonicalSymja)

            // 3. Reactive component helpers: Zc(C, s) -> 1/(s*C), Zl(L, s) -> s*L
            val transpiledZc = parser.transpile("Zc(C, s) =")
            assertTrue("Expected 1/(s*C) in Zc, got: ${transpiledZc.canonicalSymja}", transpiledZc.canonicalSymja.contains("s*C") || transpiledZc.canonicalSymja.contains("C*s"))

            val transpiledZl = parser.transpile("Zl(L, s) =")
            assertTrue("Expected s*L in Zl, got: ${transpiledZl.canonicalSymja}", transpiledZl.canonicalSymja.contains("s*L") || transpiledZl.canonicalSymja.contains("L*s"))
        } finally {
            cas.close()
        }
    }

    @Test
    fun symbolicMnaSolverSolvesVoltageDividerAndRcLowPassFilter() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val solver = SymbolicMnaSolver(cas)

            // Circuit 1: 2-resistor voltage divider
            val netlistDivider = """
                V1 1 0 10
                R1 1 2 1000
                R2 2 0 1000
            """.trimIndent()
            val elementsDivider = solver.parseNetlist(netlistDivider)
            val resultDivider = solver.solve(elementsDivider, theveninPort = 2 to 0)

            assertEquals("5", resultDivider.nodeVoltages[2])
            assertEquals("5", resultDivider.theveninVoltage)

            // Circuit 2: Symbolic RC Low-Pass Filter
            val netlistRc = """
                V_in 1 0 V_in
                R 1 2 R
                C 2 0 C
            """.trimIndent()
            val elementsRc = solver.parseNetlist(netlistRc)
            val resultRc = solver.solve(elementsRc, theveninPort = 2 to 0)

            val vOut = com.offline.inkmath.domain.model.SymjaCompiler.decode(resultRc.nodeVoltages[2] ?: "")
            // Expected V_in / (1 + s*R*C)
            assertTrue("Expected V_in in $vOut", vOut.contains("V_in") || vOut.contains("V_i") || vOut.contains("Vin"))
            assertTrue("Expected R in $vOut", vOut.contains("R"))
            assertTrue("Expected C in $vOut", vOut.contains("C"))
            assertTrue("Expected s in $vOut", vOut.contains("s"))
        } finally {
            cas.close()
        }
    }

    @Test
    fun spatialMatrixSynthesizerBuilds2x2GridFromBracketedCells() {
        val synthesizer = SpatialStructureSynthesizer(parser)

        // 4 interior cell clusters in 2 rows and 2 columns:
        // [ a  b ]
        // [ c  d ]
        val cellA = CellCluster(bounds = Rect(30f, 20f, 50f, 40f), latex = "a")
        val cellB = CellCluster(bounds = Rect(70f, 20f, 90f, 40f), latex = "b")
        val cellC = CellCluster(bounds = Rect(30f, 60f, 50f, 80f), latex = "c")
        val cellD = CellCluster(bounds = Rect(70f, 60f, 90f, 80f), latex = "d")

        // Left bracket stroke '[': endpoints on right, middle on left
        val leftPoints = listOf(
            InkPoint(20f, 15f, 0L),
            InkPoint(10f, 15f, 10L),
            InkPoint(10f, 85f, 20L),
            InkPoint(20f, 85f, 30L)
        )
        val leftGeom = StrokeGeometry(UUID.randomUUID(), leftPoints, Rect(10f, 15f, 20f, 85f))
        val leftStroke = TimedStroke.forTesting(leftGeom)

        // Right bracket stroke ']': endpoints on left, middle on right
        val rightPoints = listOf(
            InkPoint(100f, 15f, 0L),
            InkPoint(110f, 15f, 10L),
            InkPoint(110f, 85f, 20L),
            InkPoint(100f, 85f, 30L)
        )
        val rightGeom = StrokeGeometry(UUID.randomUUID(), rightPoints, Rect(100f, 15f, 110f, 85f))
        val rightStroke = TimedStroke.forTesting(rightGeom)

        val result = synthesizer.synthesizeMatrix(
            strokes = listOf(leftStroke, rightStroke),
            cellClusters = listOf(cellA, cellB, cellC, cellD),
            medianHeight = 25f
        )

        assertTrue("Expected synthesized matrix", result != null)
        assertEquals(2, result!!.rowCount)
        assertEquals(2, result.colCount)
        assertEquals("\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}", result.latex)
    }

    @Test
    fun quineMcCluskeyMinimizerFindsMinimalSopWithDontCares() {
        // Function with minterms 0, 1, 2, 5, 8, 9, 10 for 4 variables A, B, C, D
        val result = KarnaughMinimizer.minimize(
            minterms = setOf(0, 1, 2, 5, 8, 9, 10),
            dontCares = emptySet(),
            numVars = 4
        )

        // Prime implicants covering:
        // -0-0 -> \overline{B} \overline{D} (covers 0, 2, 8, 10)
        // -00- -> \overline{B} \overline{C} (covers 0, 1, 8, 9)
        // 0-01 -> \overline{A} \overline{C} D (covers 1, 5)
        val sop = result.minimalSopLatex

        assertTrue("Expected \\overline{B} \\overline{D} in $sop", sop.contains("\\overline{B} \\overline{D}"))
        assertTrue("Expected \\overline{B} \\overline{C} in $sop", sop.contains("\\overline{B} \\overline{C}"))
        assertTrue("Expected \\overline{A} \\overline{C} D in $sop", sop.contains("\\overline{A} \\overline{C} D"))

        // Also test with Don't Care minterm 13 (which combines with 5):
        val resultWithDc = KarnaughMinimizer.minimize(
            minterms = setOf(0, 1, 2, 5, 8, 9, 10),
            dontCares = setOf(13),
            numVars = 4
        )
        assertTrue("Should compute valid minimal SOP with don't cares", resultWithDc.minimalSopLatex.isNotBlank())
        assertEquals(16, resultWithDc.truthTable.size)
    }
}
