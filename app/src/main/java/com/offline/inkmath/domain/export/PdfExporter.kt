package com.offline.inkmath.domain.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.offline.inkmath.domain.clustering.InkWorkspaceState
import com.offline.inkmath.domain.dag.ReactiveGraphSnapshot
import com.offline.inkmath.domain.model.BlockDisplayMode
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.unionBounds
import com.offline.inkmath.ui.renderer.LatexCanvasRenderer
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

enum class PdfTheme {
    DARK_ENGINEERING,
    PRINT_LIGHT
}

/**
 * 100% offline native vector PDF exporter using Android's built-in [android.graphics.pdf.PdfDocument].
 * Renders mathematical equations, handwritten vector strokes, and typeset JLatexMath symbols
 * scaled to an A4 layout.
 */
object PdfExporter {

    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842
    private const val MARGIN_PT = 36f

    fun export(
        title: String,
        state: InkWorkspaceState,
        graph: ReactiveGraphSnapshot,
        outputStream: OutputStream,
        theme: PdfTheme = PdfTheme.DARK_ENGINEERING
    ) {
        val document = PdfDocument()
        val latexRenderer = LatexCanvasRenderer()

        try {
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val bgColor = if (theme == PdfTheme.DARK_ENGINEERING) Color.rgb(18, 20, 23) else Color.WHITE
            val strokeColor = if (theme == PdfTheme.DARK_ENGINEERING) Color.WHITE else Color.rgb(25, 29, 34)
            val resultColor = if (theme == PdfTheme.DARK_ENGINEERING) Color.rgb(255, 179, 0) else Color.rgb(13, 71, 161)
            val headerColor = if (theme == PdfTheme.DARK_ENGINEERING) Color.rgb(244, 245, 247) else Color.rgb(33, 37, 41)
            val subheaderColor = if (theme == PdfTheme.DARK_ENGINEERING) Color.rgb(173, 181, 189) else Color.rgb(108, 117, 125)

            // Fill page background
            canvas.drawColor(bgColor)

            // Draw Header
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = headerColor
                textSize = 20f
                isFakeBoldText = true
            }
            canvas.drawText(title, MARGIN_PT, MARGIN_PT + 16f, headerPaint)

            val subheaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subheaderColor
                textSize = 10f
            }
            canvas.drawText("InkMath Offline Engineering Notebook", MARGIN_PT, MARGIN_PT + 32f, subheaderPaint)

            val sortedBlocks = MarkdownExporter.sortByReadingOrder(state.blocks)

            if (sortedBlocks.isNotEmpty()) {
                // Compute total workspace bounding box
                val totalBounds = sortedBlocks.drop(1).fold(sortedBlocks.first().bounds) { acc, b ->
                    acc.unionBounds(b.bounds)
                }

                val availableWidth = (A4_WIDTH_PT - 2 * MARGIN_PT)
                val availableHeight = (A4_HEIGHT_PT - MARGIN_PT - 60f - MARGIN_PT)

                val scaleX = availableWidth / max(100f, totalBounds.width)
                val scaleY = availableHeight / max(100f, totalBounds.height)
                val scale = min(scaleX, scaleY).coerceIn(0.2f, 2.5f)

                val offsetX = MARGIN_PT - totalBounds.left * scale
                val offsetY = (MARGIN_PT + 50f) - totalBounds.top * scale

                val saveCount = canvas.save()
                canvas.translate(offsetX, offsetY)
                canvas.scale(scale, scale)

                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = strokeColor
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                for (block in sortedBlocks) {
                    val bounds = block.bounds

                    if (block.displayMode == BlockDisplayMode.TYPESET && !block.recognizedLatex.isNullOrBlank()) {
                        latexRenderer.drawAt(
                            canvas = canvas,
                            latex = block.recognizedLatex,
                            left = bounds.left + 4f,
                            centerY = (bounds.top + bounds.bottom) / 2f,
                            targetHeight = (bounds.height * 0.85f).coerceIn(20f, 64f),
                            color = strokeColor
                        )
                    } else {
                        // Render handwritten vector strokes
                        for (stroke in block.strokes) {
                            strokePaint.strokeWidth = 3.5f
                            val path = Path()
                            val points = stroke.points
                            if (points.isNotEmpty()) {
                                path.moveTo(points.first().x, points.first().y)
                                for (i in 1 until points.size) {
                                    path.lineTo(points[i].x, points[i].y)
                                }
                                canvas.drawPath(path, strokePaint)
                            }
                        }
                    }

                    // Render CAS Result
                    val graphBlock = graph.blocks[block.id]
                    val result = graphBlock?.result ?: block.result
                    val resultLatex = result?.latex
                    if (!resultLatex.isNullOrBlank()) {
                        val anchor = block.resultAnchorPoint
                        val left = (anchor?.x ?: bounds.right) + 12f
                        val centerY = anchor?.y ?: (bounds.top + bounds.bottom) / 2f
                        latexRenderer.drawAt(
                            canvas = canvas,
                            latex = "= $resultLatex",
                            left = left,
                            centerY = centerY,
                            targetHeight = block.medianStrokeHeight.coerceAtLeast(24f),
                            color = resultColor
                        )
                    }
                }

                canvas.restoreToCount(saveCount)
            }

            document.finishPage(page)
            document.writeTo(outputStream)
        } finally {
            document.close()
        }
    }
}
