package com.axioloom.app.ui.canvas

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
import com.axioloom.app.domain.dag.ReactiveGraphSnapshot
import com.axioloom.app.domain.model.BlockDisplayMode
import com.axioloom.app.domain.model.BlockStatus
import com.axioloom.app.domain.model.MathIntent
import com.axioloom.app.domain.model.touches
import com.axioloom.app.domain.clustering.InkWorkspaceState
import com.axioloom.app.ui.renderer.LatexCanvasRenderer
import com.axioloom.app.ui.theme.UmbraColors

/**
 * Renders completed vector strokes, mathematical expressions, and engineering overlays
 * in the Umbra technical canvas coordinate system with dot-matrix grid and corner L-brackets.
 */
@Composable
fun DryInkCanvas(
    workspace: InkWorkspaceState,
    graph: ReactiveGraphSnapshot,
    viewport: ViewportState,
    selectedBlockId: java.util.UUID?,
    fastNumericOverrides: Map<java.util.UUID, com.axioloom.app.domain.dag.FastNumericResult> = emptyMap(),
    activeSchematic: com.axioloom.app.domain.circuit.RecognizedSchematic? = null,
    mnaResult: com.axioloom.app.domain.circuit.MnaResult? = null,
    dimensionalWarnings: Map<java.util.UUID, List<com.axioloom.app.domain.units.DimensionalMismatchWarning>> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val strokeRenderer = remember { runCatching { CanvasStrokeRenderer.create() }.getOrNull() }
    val latexRenderer = remember { LatexCanvasRenderer() }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Umbra Recessed Graphite Canvas Background
        drawRect(UmbraColors.BgRecessed)

        // Architectural Micro-Dot Matrix Grid
        val gridStep = if (viewport.scale < 0.6f) 48f else 24f
        val startX = ((viewport.translation.x % gridStep) + gridStep) % gridStep
        val startY = ((viewport.translation.y % gridStep) + gridStep) % gridStep
        var y = startY
        while (y < size.height) {
            var x = startX
            while (x < size.width) {
                drawCircle(UmbraColors.GridDot, radius = 1.0f, center = Offset(x, y))
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
                            if (strokeRenderer != null && timedStroke.hasNativeStroke) {
                                val ok = runCatching {
                                    if (timedStroke.translationOffset != androidx.compose.ui.geometry.Offset.Zero) {
                                        val saveCount = canvas.save()
                                        canvas.translate(timedStroke.translationOffset.x, timedStroke.translationOffset.y)
                                        try {
                                            strokeRenderer.draw(canvas, timedStroke.stroke, AffineTransform.IDENTITY)
                                        } finally {
                                            canvas.restoreToCount(saveCount)
                                        }
                                    } else {
                                        strokeRenderer.draw(canvas, timedStroke.stroke, AffineTransform.IDENTITY)
                                    }
                                }.isSuccess
                                if (!ok) {
                                    drawFallbackStroke(canvas, timedStroke)
                                }
                            } else {
                                drawFallbackStroke(canvas, timedStroke)
                            }
                        }
                    }

                    val graphBlock = graph.blocks[block.id]
                    val status = graphBlock?.status ?: block.status
                    val result = graphBlock?.result ?: block.result

                    // Umbra Corner L-Brackets & Framing
                    val bracketColor = when {
                        block.id == selectedBlockId -> android.graphics.Color.rgb(245, 180, 89) // Amber Accent
                        status is BlockStatus.Error -> android.graphics.Color.rgb(212, 81, 92)  // Crimson
                        status is BlockStatus.Ready -> android.graphics.Color.rgb(82, 84, 88)   // Grid Crosshair
                        else -> android.graphics.Color.rgb(43, 44, 48)                          // Subtle Hairline
                    }
                    val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = bracketColor
                        style = Paint.Style.STROKE
                        strokeWidth = (if (block.id == selectedBlockId) 1.8f else 1.2f) / viewport.scale
                    }

                    // Render technical L-bracket registration ticks
                    val arm = 8f / viewport.scale
                    val pad = 4f / viewport.scale
                    val l = bounds.left - pad
                    val t = bounds.top - pad
                    val r = bounds.right + pad
                    val b = bounds.bottom + pad

                    // Top-left
                    canvas.drawLine(l, t + arm, l, t, bracketPaint)
                    canvas.drawLine(l, t, l + arm, t, bracketPaint)
                    // Top-right
                    canvas.drawLine(r - arm, t, r, t, bracketPaint)
                    canvas.drawLine(r, t, r, t + arm, bracketPaint)
                    // Bottom-left
                    canvas.drawLine(l, b - arm, l, b, bracketPaint)
                    canvas.drawLine(l, b, l + arm, b, bracketPaint)
                    // Bottom-right
                    canvas.drawLine(r - arm, b, r, b, bracketPaint)
                    canvas.drawLine(r, b, r, b - arm, bracketPaint)

                    val fastOverride = fastNumericOverrides[block.id]
                    val rawResult = result?.latex
                    val resultLatex = when {
                        fastOverride != null -> "= ${fastOverride.formattedPreview}"
                        rawResult != null -> {
                            if (block.resultAnchorPoint == null && !rawResult.trimStart().startsWith("=") &&
                                (block.intent is MathIntent.EvaluationQuery || block.intent == null)
                            ) {
                                "= $rawResult"
                            } else {
                                rawResult
                            }
                        }
                        else -> null
                    }
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
                            color = android.graphics.Color.rgb(245, 180, 89)
                        )
                    }

                    // Dimensional Unit Mismatch Warning Badge
                    val warnings = dimensionalWarnings[block.id]
                    if (!warnings.isNullOrEmpty()) {
                        val warningText = "[!] ${warnings.first().message}"
                        val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(212, 81, 92) // Crimson
                            textSize = 12f / viewport.scale
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                        canvas.drawText(warningText, bounds.left, bounds.top - 6f, warnPaint)
                    }
                }

                // Render Schematic Node Voltages from MNA Solver
                if (activeSchematic != null && mnaResult != null) {
                    val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(235, 14, 15, 17)
                        style = Paint.Style.FILL
                    }
                    val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(245, 180, 89) // Amber
                        style = Paint.Style.STROKE
                        strokeWidth = 1.2f / viewport.scale
                    }
                    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(234, 234, 234)
                        textSize = 12f / viewport.scale
                        typeface = android.graphics.Typeface.MONOSPACE
                    }

                    for ((nodeId, pos) in activeSchematic.nodePositions) {
                        val vStr = mnaResult.nodeVoltages[nodeId] ?: if (nodeId == 0) "0 V" else "V$nodeId"
                        val label = "V$nodeId: $vStr"
                        val textWidth = badgeTextPaint.measureText(label)
                        val textHeight = 13f / viewport.scale
                        val padding = 5f / viewport.scale

                        val bLeft = pos.x + 8f
                        val bTop = pos.y - textHeight - padding * 2
                        val bRight = bLeft + textWidth + padding * 2
                        val bBottom = pos.y

                        canvas.drawRect(RectF(bLeft, bTop, bRight, bBottom), badgeBgPaint)
                        canvas.drawRect(RectF(bLeft, bTop, bRight, bBottom), badgeBorderPaint)
                        canvas.drawText(label, bLeft + padding, bBottom - padding - 2f, badgeTextPaint)
                    }
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }
}

private fun drawFallbackStroke(canvas: android.graphics.Canvas, timedStroke: com.axioloom.app.domain.model.TimedStroke) {
    val pts = timedStroke.geometry.points
    if (pts.isEmpty()) return
    val ox = timedStroke.translationOffset.x
    val oy = timedStroke.translationOffset.y
    if (pts.size < 2) return
    val path = android.graphics.Path()
    path.moveTo(pts[0].x + ox, pts[0].y + oy)
    for (i in 1 until pts.size) {
        path.lineTo(pts[i].x + ox, pts[i].y + oy)
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
