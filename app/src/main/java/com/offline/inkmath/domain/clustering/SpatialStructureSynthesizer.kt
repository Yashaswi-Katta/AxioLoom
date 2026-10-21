package com.offline.inkmath.domain.clustering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.MathEquation
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.model.TimedStroke
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CellCluster(
    val bounds: Rect,
    val latex: String,
    val strokeIds: Set<UUID> = emptySet()
)

enum class BracketKind {
    LEFT_BRACKET,
    RIGHT_BRACKET,
    DETERMINANT_BAR,
    LEFT_BRACE
}

data class ClassifiedBracket(
    val stroke: TimedStroke,
    val kind: BracketKind,
    val bounds: Rect
)

data class SynthesizedMatrix(
    val latex: String,
    val rowCount: Int,
    val colCount: Int,
    val isDeterminant: Boolean,
    val bounds: Rect,
    val usedStrokeIds: Set<UUID>
)

data class SynthesizedSystem(
    val equationBlocks: List<EquationBlock>,
    val synthesizedLatex: String,
    val intent: MathIntent.SystemOfEq
)

/**
 * Spatial 2D Matrix & Multi-Block System-of-Equations Synthesizer.
 * Identifies tall bounding brackets ([, ], |, {) and projects interior cell clusters
 * into rows and columns to construct composite \begin{bmatrix} or \begin{vmatrix} matrices,
 * and groups vertically stacked equation blocks into simultaneous equation systems (\begin{cases}).
 */
class SpatialStructureSynthesizer(
    private val transpiler: LatexToSymjaTranspiler = LatexToSymjaTranspiler()
) {

    /**
     * Classifies a stroke as a tall bracket or determinant bar if its geometry matches.
     */
    fun classifyBracket(stroke: TimedStroke, medianHeight: Float): ClassifiedBracket? {
        val bounds = stroke.bounds
        val h = bounds.height
        val w = bounds.width
        val refH = max(medianHeight, 20f)

        // Must be tall relative to median character height
        if (h < 2.0f * refH || w > 0.65f * h) return null

        val points = stroke.points
        if (points.size < 2) return null

        val first = points.first().offset
        val last = points.last().offset
        val mid = points[points.size / 2].offset

        val aspect = w / h

        // 1. Straight vertical determinant bar: |
        if (aspect <= 0.18f) {
            return ClassifiedBracket(stroke, BracketKind.DETERMINANT_BAR, bounds)
        }

        // 2. Left Bracket [ or ( or { (concave right, opening to the right)
        val endpointsOnRight = (first.x >= bounds.left + 0.25f * w) && (last.x >= bounds.left + 0.25f * w)
        val middleOnLeft = mid.x <= bounds.left + 0.55f * w

        if (endpointsOnRight && middleOnLeft) {
            // Check if curly brace { (midpoint cusping left)
            return if (aspect > 0.25f && points.size >= 5) {
                ClassifiedBracket(stroke, BracketKind.LEFT_BRACE, bounds)
            } else {
                ClassifiedBracket(stroke, BracketKind.LEFT_BRACKET, bounds)
            }
        }

        // 3. Right Bracket ] or ) (concave left, opening to the left)
        val endpointsOnLeft = (first.x <= bounds.right - 0.25f * w) && (last.x <= bounds.right - 0.25f * w)
        val middleOnRight = mid.x >= bounds.right - 0.55f * w

        if (endpointsOnLeft && middleOnRight) {
            return ClassifiedBracket(stroke, BracketKind.RIGHT_BRACKET, bounds)
        }

        return null
    }

    /**
     * Synthesizes 2D matrices by finding bracket pairs enclosing 2D grid cells.
     */
    fun synthesizeMatrix(
        strokes: List<TimedStroke>,
        cellClusters: List<CellCluster>,
        medianHeight: Float = 30f
    ): SynthesizedMatrix? {
        if (cellClusters.size < 2) return null

        // 1. Detect all tall brackets
        val brackets = strokes.mapNotNull { classifyBracket(it, medianHeight) }
        val leftBrackets = brackets.filter {
            it.kind == BracketKind.LEFT_BRACKET || it.kind == BracketKind.DETERMINANT_BAR
        }
        val rightBrackets = brackets.filter {
            it.kind == BracketKind.RIGHT_BRACKET || it.kind == BracketKind.DETERMINANT_BAR
        }

        for (left in leftBrackets) {
            for (right in rightBrackets) {
                if (left.stroke.id == right.stroke.id) continue
                if (left.bounds.left >= right.bounds.left) continue

                // Check vertical alignment overlap
                val topMax = max(left.bounds.top, right.bounds.top)
                val botMin = min(left.bounds.bottom, right.bounds.bottom)
                val overlapH = botMin - topMax
                val minH = min(left.bounds.height, right.bounds.height)
                if (overlapH < 0.5f * minH) continue

                // Interior bounding box
                val interiorLeft = left.bounds.right - 4f
                val interiorRight = right.bounds.left + 4f
                val interiorTop = min(left.bounds.top, right.bounds.top) - 10f
                val interiorBottom = max(left.bounds.bottom, right.bounds.bottom) + 10f

                // Find interior cells
                val insideCells = cellClusters.filter { cell ->
                    val cx = (cell.bounds.left + cell.bounds.right) / 2f
                    val cy = (cell.bounds.top + cell.bounds.bottom) / 2f
                    cx in interiorLeft..interiorRight && cy in interiorTop..interiorBottom
                }

                if (insideCells.size >= 2) {
                    val grid = alignGrid(insideCells, medianHeight)
                    if (grid.isNotEmpty() && grid.all { it.isNotEmpty() }) {
                        val isDet = left.kind == BracketKind.DETERMINANT_BAR && right.kind == BracketKind.DETERMINANT_BAR
                        val env = if (isDet) "vmatrix" else "bmatrix"

                        val rowStrings = grid.map { row ->
                            row.joinToString(" & ") { it.latex }
                        }
                        val latex = "\\begin{$env} ${rowStrings.joinToString(" \\\\ ")} \\end{$env}"

                        val combinedBounds = Rect(
                            left.bounds.left,
                            min(left.bounds.top, right.bounds.top),
                            right.bounds.right,
                            max(left.bounds.bottom, right.bounds.bottom)
                        )

                        val usedIds = mutableSetOf(left.stroke.id, right.stroke.id)
                        insideCells.forEach { usedIds.addAll(it.strokeIds) }

                        return SynthesizedMatrix(
                            latex = latex,
                            rowCount = grid.size,
                            colCount = grid.first().size,
                            isDeterminant = isDet,
                            bounds = combinedBounds,
                            usedStrokeIds = usedIds
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Projects cells into rows and columns by clustering centroids.
     */
    fun alignGrid(cells: List<CellCluster>, medianHeight: Float): List<List<CellCluster>> {
        val rowThreshold = max(medianHeight * 0.6f, 15f)

        // 1. Group into rows by vertical center
        val sortedByY = cells.sortedBy { (it.bounds.top + it.bounds.bottom) / 2f }
        val rows = mutableListOf<MutableList<CellCluster>>()

        for (cell in sortedByY) {
            val cy = (cell.bounds.top + cell.bounds.bottom) / 2f
            val matchedRow = rows.firstOrNull { r ->
                val rowCy = r.map { (it.bounds.top + it.bounds.bottom) / 2f }.average().toFloat()
                abs(cy - rowCy) <= rowThreshold
            }
            if (matchedRow != null) {
                matchedRow.add(cell)
            } else {
                rows.add(mutableListOf(cell))
            }
        }

        // 2. Sort each row left-to-right
        val grid = rows.map { row -> row.sortedBy { (it.bounds.left + it.bounds.right) / 2f } }

        // Must form at least a 1x2 or 2x1 grid
        val expectedCols = grid.first().size
        if (expectedCols > 0 && grid.all { it.size == expectedCols }) {
            return grid
        }
        return grid
    }

    /**
     * Synthesizes vertically stacked equation blocks into a simultaneous system of equations.
     */
    fun synthesizeEquationSystem(
        blocks: List<EquationBlock>,
        strokes: List<TimedStroke> = emptyList(),
        medianHeight: Float = 30f
    ): SynthesizedSystem? {
        val eqBlocks = blocks.filter { block ->
            val latex = block.recognizedLatex ?: ""
            latex.contains("=") && !latex.contains("\\begin{cases}")
        }.sortedBy { it.bounds.top }

        if (eqBlocks.size < 2 || eqBlocks.size > 5) return null

        // Check vertical stacking and horizontal alignment
        var validStack = true
        for (i in 1 until eqBlocks.size) {
            val prev = eqBlocks[i - 1]
            val curr = eqBlocks[i]
            val vGap = curr.bounds.top - prev.bounds.bottom
            val leftDiff = abs(curr.bounds.left - prev.bounds.left)
            if (vGap < -0.5f * medianHeight || vGap > 3.0f * medianHeight || leftDiff > 2.5f * medianHeight) {
                validStack = false
                break
            }
        }

        if (!validStack) return null

        // Build cases environment LaTeX
        val rows = eqBlocks.mapNotNull { it.recognizedLatex?.removeSuffix("=")?.trim() }
        if (rows.size < 2) return null

        val casesLatex = "\\begin{cases} " + rows.joinToString(" \\\\ ") + " \\end{cases}"

        return try {
            val transpiled = transpiler.transpile(casesLatex)
            if (transpiled.intent is MathIntent.SystemOfEq) {
                SynthesizedSystem(
                    equationBlocks = eqBlocks,
                    synthesizedLatex = casesLatex,
                    intent = transpiled.intent
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Synthesizes 2-column spatial cell clusters into a list of (x, y) DataPoints for regression.
     */
    fun synthesizeDataTable(cells: List<CellCluster>): List<com.offline.inkmath.domain.stats.DataPoint>? {
        if (cells.size < 4) return null
        val grid = alignGrid(cells)
        if (grid.size < 2 || !grid.all { it.size == 2 }) return null
        val points = mutableListOf<com.offline.inkmath.domain.stats.DataPoint>()
        for (row in grid) {
            val x = row[0].latex.trim().toDoubleOrNull() ?: return null
            val y = row[1].latex.trim().toDoubleOrNull() ?: return null
            points.add(com.offline.inkmath.domain.stats.DataPoint(x, y))
        }
        return points
    }
}
