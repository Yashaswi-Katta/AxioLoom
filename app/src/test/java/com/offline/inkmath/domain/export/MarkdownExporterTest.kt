package com.offline.inkmath.domain.export

import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.clustering.InkWorkspaceState
import com.offline.inkmath.domain.dag.ReactiveGraphSnapshot
import com.offline.inkmath.domain.model.CasMode
import com.offline.inkmath.domain.model.CasResult
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.InkPoint
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.model.StrokeGeometry
import com.offline.inkmath.domain.model.TimedStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MarkdownExporterTest {

    private fun createBlock(
        bounds: Rect,
        latex: String,
        intent: MathIntent? = null,
        result: CasResult? = null
    ): EquationBlock {
        val stroke = TimedStroke.forTesting(
            StrokeGeometry(
                id = UUID.randomUUID(),
                points = listOf(
                    InkPoint(bounds.left, bounds.top, 0L),
                    InkPoint(bounds.right, bounds.bottom, 10L)
                ),
                bounds = bounds
            )
        )
        return EquationBlock(
            id = UUID.randomUUID(),
            strokes = listOf(stroke),
            bounds = bounds,
            medianStrokeHeight = bounds.height,
            resultAnchorPoint = null,
            recognizedLatex = latex,
            intent = intent,
            result = result
        )
    }

    @Test
    fun readingOrderSortingSortsTopToBottomThenLeftToRight() {
        // Block 1: Top-Left (x: 10..50, y: 10..40)
        val b1 = createBlock(Rect(10f, 10f, 50f, 40f), "a = 1")
        // Block 2: Top-Right (x: 200..250, y: 15..45) (same horizontal band)
        val b2 = createBlock(Rect(200f, 15f, 250f, 45f), "b = 2")
        // Block 3: Bottom-Left (x: 10..50, y: 150..180) (next band)
        val b3 = createBlock(Rect(10f, 150f, 50f, 180f), "c = a + b")

        val unordered = listOf(b3, b2, b1)
        val sorted = MarkdownExporter.sortByReadingOrder(unordered)

        assertEquals(listOf(b1, b2, b3), sorted)
    }

    @Test
    fun markdownExporterEmitsObsidianFormatWithFrontmatterAndSymbolTable() {
        val b1 = createBlock(
            bounds = Rect(0f, 0f, 50f, 30f),
            latex = "x = 42",
            intent = MathIntent.Assignment("x", MathExpr.Number("42")),
            result = CasResult("42", "42", approximate = false)
        )
        val b2 = createBlock(
            bounds = Rect(0f, 60f, 80f, 90f),
            latex = "y = x^2",
            intent = MathIntent.Assignment("y", MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Symbol("x"), MathExpr.Number("2"))),
            result = CasResult("1764", "1764", approximate = false)
        )

        val state = InkWorkspaceState(blocks = listOf(b1, b2))
        val graph = ReactiveGraphSnapshot(mode = CasMode.SYMBOLIC)

        val markdown = MarkdownExporter.export(
            title = "Circuits Lab 1",
            state = state,
            graph = graph,
            timestampMillis = 1700000000000L
        )

        // 1. Check Frontmatter
        assertTrue(markdown.startsWith("---\n"))
        assertTrue(markdown.contains("title: \"Circuits Lab 1\""))
        assertTrue(markdown.contains("mode: \"SYMBOLIC\""))
        assertTrue(markdown.contains("tags: [inkmath, engineering-notes]"))

        // 2. Check Title
        assertTrue(markdown.contains("# Circuits Lab 1"))

        // 3. Check Symbol Table
        assertTrue(markdown.contains("## Symbol Table"))
        assertTrue(markdown.contains("| Symbol | Definition | Evaluated Value |"))
        assertTrue(markdown.contains("| `x` | `42` | `42` |"))
        assertTrue(markdown.contains("| `y` | `x^2` | `1764` |"))

        // 4. Check Workspace Equations
        assertTrue(markdown.contains("## Workspace Equations"))
        assertTrue(markdown.contains("$$\nx = 42\n$$"))
        assertTrue(markdown.contains("$\\implies 42$"))
        assertTrue(markdown.contains("$$\ny = x^2\n$$"))
        assertTrue(markdown.contains("$\\implies 1764$"))
    }
}
