package com.offline.inkmath.ui.canvas

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.offline.inkmath.domain.dag.ReactiveGraphSnapshot
import com.offline.inkmath.domain.model.BlockDisplayMode
import com.offline.inkmath.domain.model.BlockStatus
import com.offline.inkmath.domain.model.touches
import com.offline.inkmath.domain.clustering.InkWorkspaceState
import com.offline.inkmath.ui.renderer.LatexCanvasRenderer

/** Renders completed vector strokes and CAS output in the same workspace coordinate system. */
@Composable
fun DryInkCanvas(
    workspace: InkWorkspaceState,
    graph: ReactiveGraphSnapshot,
    viewport: ViewportState,
    selectedBlockId: java.util.UUID?,
    fastNumericOverrides: Map<java.util.UUID, com.offline.inkmath.domain.dag.FastNumericResult> = emptyMap(),
    activeSchematic: com.offline.inkmath.domain.circuit.RecognizedSchematic? = null,
    mnaResult: com.offline.inkmath.domain.circuit.MnaResult? = null,
    dimensionalWarnings: Map<java.util.UUID, List<com.offline.inkmath.domain.units.DimensionalMismatchWarning>> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val strokeRenderer = remember { runCatching { CanvasStrokeRenderer.create() }.getOrNull() }
    val latexRenderer = remember { LatexCanvasRenderer() }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color(0xFF121417))
        val gridStep = if (viewport.scale < 0.6f) 56f else 28f
        val startX = ((viewport.translation.x % gridStep) + gridStep) % gridStep
        val startY = ((viewport.translation.y % gridStep) + gridStep) % gridStep
        var y = startY
        while (y < size.height) {
            var x = startX
            while (x < size.width) {
                drawCircle(Color(0xFF252A31), radius = 1.1f, center = Offset(x, y))
                x += gridStep
            }
            y += gridStep
        }

        val visibleWorld = viewport.visibleWorldRect(size.width, size.height)

        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val save = canvas.save()
            canvas.concat(viewport.worldToScreenMatrix())
            try {
                workspace.blocks.forEach { block ->
                    val bounds = block.bounds
                    val expandedBounds = androidx.compose.ui.geometry.Rect(
                        bounds.left - 16f,
                        bounds.top - 16f,
                        bounds.right + 350f, // account for result anchor and rendered LaTeX result
                        bounds.bottom + 16f
                    )
                    // Viewport Frustum Culling
                    if (!visibleWorld.touches(expandedBounds)) return@forEach

                    if (block.displayMode == BlockDisplayMode.TYPESET && !block.recognizedLatex.isNullOrBlank()) {
                        latexRenderer.drawAt(
                            canvas = canvas,
                            latex = block.recognizedLatex,
                            left = bounds.left + 4f,
                            centerY = (bounds.top + bounds.bottom) / 2f,
                            targetHeight = (bounds.height * 0.85f).coerceIn(20f, 64f),
                            color = android.graphics.Color.WHITE
                        )
                    } else {
                        block.strokes.forEach { timedStroke ->
                            if (strokeRenderer != null) {
                                strokeRenderer.draw(canvas, timedStroke.stroke, AffineTransform.IDENTITY)
                            } else {
                                drawFallbackStroke(canvas, timedStroke)
                            }
                        }
                    }

                    val graphBlock = graph.blocks[block.id]
                    val status = graphBlock?.status ?: block.status
                    val result = graphBlock?.result ?: block.result
                    val boxColor = when {
                        block.id == selectedBlockId -> android.graphics.Color.rgb(255, 179, 0)
                        status is BlockStatus.Error -> android.graphics.Color.rgb(220, 20, 60)
                        status is BlockStatus.Ready -> android.graphics.Color.rgb(120, 130, 145)
                        else -> android.graphics.Color.rgb(90, 100, 114)
                    }
                    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = boxColor
                        style = Paint.Style.STROKE
                        strokeWidth = 1.4f / viewport.scale
                    }
                    canvas.drawRoundRect(
                        RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        7f / viewport.scale,
                        7f / viewport.scale,
                        boxPaint
                    )

                    val fastOverride = fastNumericOverrides[block.id]
                    val resultLatex = if (fastOverride != null) "= ${fastOverride.formattedPreview}" else result?.latex
                    if (!resultLatex.isNullOrBlank()) {
                        val anchor = block.resultAnchorPoint
                        val left = (anchor?.x ?: bounds.right) + 12f
                        val centerY = anchor?.y ?: (bounds.top + bounds.bottom) / 2f
                        latexRenderer.drawAt(
                            canvas = canvas,
                            latex = resultLatex,
                            left = left,
                            centerY = centerY,
                            targetHeight = block.medianStrokeHeight.coerceAtLeast(24f),
                            color = android.graphics.Color.rgb(255, 179, 0)
                        )
                    }

                    // Dimensional Unit Mismatch Warning Badge
                    val warnings = dimensionalWarnings[block.id]
                    if (!warnings.isNullOrEmpty()) {
                        val warningText = "[!] ${warnings.first().message}"
                        val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(220, 20, 60) // Crimson Red
                            textSize = 12f / viewport.scale
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                        canvas.drawText(warningText, bounds.left, bounds.top - 6f, warnPaint)
                    }
                }

                // Render Schematic Node Voltages from MNA Solver
                if (activeSchematic != null && mnaResult != null) {
                    val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(220, 25, 29, 34)
                        style = Paint.Style.FILL
                    }
                    val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(255, 179, 0) // Amber
                        style = Paint.Style.STROKE
                        strokeWidth = 1.5f / viewport.scale
                    }
                    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(255, 213, 79) // Gold
                        textSize = 13f / viewport.scale
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }

                    for ((nodeId, pos) in activeSchematic.nodePositions) {
                        val vStr = mnaResult.nodeVoltages[nodeId] ?: if (nodeId == 0) "0 V" else "V$nodeId"
                        val label = "V$nodeId: $vStr"
                        val textWidth = badgeTextPaint.measureText(label)
                        val textHeight = 14f / viewport.scale
                        val padding = 6f / viewport.scale

                        val bLeft = pos.x + 8f
                        val bTop = pos.y - textHeight - padding * 2
                        val bRight = bLeft + textWidth + padding * 2
                        val bBottom = pos.y

                        canvas.drawRoundRect(RectF(bLeft, bTop, bRight, bBottom), 4f, 4f, badgeBgPaint)
                        canvas.drawRoundRect(RectF(bLeft, bTop, bRight, bBottom), 4f, 4f, badgeBorderPaint)
                        canvas.drawText(label, bLeft + padding, bBottom - padding - 2f, badgeTextPaint)
                    }
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }
}

private fun drawFallbackStroke(canvas: android.graphics.Canvas, timedStroke: com.offline.inkmath.domain.model.TimedStroke) {
    val pts = timedStroke.geometry.points
    if (pts.size < 2) return
    val path = android.graphics.Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        path.lineTo(pts[i].x, pts[i].y)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, paint)
}
