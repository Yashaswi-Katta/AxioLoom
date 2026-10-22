package com.axioloom.app.ui.canvas

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axioloom.app.domain.evaluator.FastPlotEvaluator
import com.axioloom.app.domain.model.MathIntent
import kotlin.math.*

enum class PlotCoordinateMode {
    CARTESIAN,
    POLAR,
    PARAMETRIC
}

/**
 * 60fps native Compose 2D multi-coordinate function plotter.
 * Supports:
 *   1. Cartesian mode (y = f(x)) with touch scrubbing and root markers.
 *   2. Polar mode (r(theta)) with concentric circular grid lines and 30-degree spokes.
 *   3. Parametric mode ((x(t), y(t))) with trajectory path tracing.
 */
@Composable
fun FunctionPlotCard(
    functionDef: MathIntent.FunctionDef,
    boundValues: Map<String, Double> = emptyMap(),
    allFunctions: List<MathIntent.FunctionDef> = emptyList(),
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val paramName = functionDef.params.firstOrNull() ?: "x"
    val isPolarDefault = functionDef.funcName.equals("r", ignoreCase = true) ||
            paramName in setOf("theta", "\\theta", "θ")

    var coordMode by remember(functionDef.funcName) {
        mutableStateOf(if (isPolarDefault) PlotCoordinateMode.POLAR else PlotCoordinateMode.CARTESIAN)
    }

    var xMin by remember(functionDef.funcName) { mutableDoubleStateOf(-10.0) }
    var xMax by remember(functionDef.funcName) { mutableDoubleStateOf(10.0) }
    var scrubParam by remember { mutableStateOf<Double?>(null) }

    // Check for parametric pair: x(t) and y(t)
    val parametricYDef = remember(functionDef, allFunctions) {
        if (functionDef.funcName.equals("x", ignoreCase = true)) {
            allFunctions.firstOrNull { it.funcName.equals("y", ignoreCase = true) }
        } else null
    }

    // 1. Cartesian Points
    val cartesianPoints = remember(functionDef, boundValues, xMin, xMax) {
        FastPlotEvaluator.sample(
            expr = functionDef.body,
            paramName = paramName,
            xMin = xMin,
            xMax = xMax,
            sampleCount = 180,
            bindings = boundValues
        )
    }

    // 2. Polar Points: x = r*cos(theta), y = r*sin(theta)
    val polarPoints = remember(functionDef, boundValues) {
        FastPlotEvaluator.samplePolar(
            expr = functionDef.body,
            paramName = paramName,
            sampleCount = 360,
            bindings = boundValues
        )
    }

    // 3. Parametric Points: (x(t), y(t))
    val parametricPoints = remember(functionDef, parametricYDef, boundValues) {
        if (parametricYDef != null) {
            FastPlotEvaluator.sampleParametric(
                xExpr = functionDef.body,
                yExpr = parametricYDef.body,
                paramName = paramName,
                sampleCount = 200,
                bindings = boundValues
            )
        } else emptyList()
    }

    // Bounds for Cartesian
    val validY = cartesianPoints.mapNotNull { if (it.second.isFinite()) it.second else null }
    val rawYMin = if (validY.isNotEmpty()) validY.minOrNull() ?: -10.0 else -10.0
    val rawYMax = if (validY.isNotEmpty()) validY.maxOrNull() ?: 10.0 else 10.0
    val yPadding = if (rawYMax > rawYMin) (rawYMax - rawYMin) * 0.12 else 2.0
    val yMin = rawYMin - yPadding
    val yMax = rawYMax + yPadding

    Surface(
        color = Color(0xFF191D22),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Header Row: Title, Coordinate Mode selector, Zoom/Reset, Hide
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${functionDef.funcName}($paramName)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )

                    // Coordinate Mode Selector
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = coordMode == PlotCoordinateMode.CARTESIAN,
                            onClick = { coordMode = PlotCoordinateMode.CARTESIAN },
                            label = { Text("Cartesian", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = coordMode == PlotCoordinateMode.POLAR,
                            onClick = { coordMode = PlotCoordinateMode.POLAR },
                            label = { Text("Polar", style = MaterialTheme.typography.labelSmall) }
                        )
                        if (parametricYDef != null) {
                            FilterChip(
                                selected = coordMode == PlotCoordinateMode.PARAMETRIC,
                                onClick = { coordMode = PlotCoordinateMode.PARAMETRIC },
                                label = { Text("Parametric", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (coordMode == PlotCoordinateMode.CARTESIAN) {
                        TextButton(onClick = {
                            val span = (xMax - xMin) * 0.75
                            val mid = (xMin + xMax) / 2.0
                            xMin = mid - span / 2.0
                            xMax = mid + span / 2.0
                        }) { Text("+", color = Color(0xFFF4F5F7)) }
                        TextButton(onClick = {
                            val span = (xMax - xMin) * 1.33
                            val mid = (xMin + xMax) / 2.0
                            xMin = mid - span / 2.0
                            xMax = mid + span / 2.0
                        }) { Text("-", color = Color(0xFFF4F5F7)) }
                        TextButton(onClick = {
                            xMin = -10.0; xMax = 10.0; scrubParam = null
                        }) { Text("Reset", color = Color(0xFFADB5BD), style = MaterialTheme.typography.labelSmall) }
                    }
                    IconButton(onClick = onDismiss) {
                        Text("\u2715", color = Color(0xFFDC143C), fontSize = 16.sp)
                    }
                }
            }

            // Canvas Plotting Area
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color(0xFF121417), RoundedCornerShape(6.dp))
                    .pointerInput(coordMode, xMin, xMax) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (coordMode == PlotCoordinateMode.CARTESIAN) {
                                    scrubParam = xMin + (offset.x / size.width.toFloat()) * (xMax - xMin)
                                }
                            }
                        )
                    }
                    .pointerInput(coordMode, xMin, xMax) {
                        detectDragGestures(
                            onDrag = { change, _ ->
                                if (coordMode == PlotCoordinateMode.CARTESIAN) {
                                    scrubParam = (xMin + (change.position.x / size.width.toFloat()) * (xMax - xMin)).coerceIn(xMin, xMax)
                                }
                            },
                            onDragEnd = { scrubParam = null },
                            onDragCancel = { scrubParam = null }
                        )
                    }
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas

                    when (coordMode) {
                        PlotCoordinateMode.CARTESIAN -> {
                            if (xMax <= xMin || yMax <= yMin) return@Canvas
                            fun worldX(x: Double): Float = ((x - xMin) / (xMax - xMin) * w).toFloat()
                            fun worldY(y: Double): Float = ((yMax - y) / (yMax - yMin) * h).toFloat()

                            // Grid
                            val xGridCount = 6
                            for (i in 0..xGridCount) {
                                val gx = xMin + i * (xMax - xMin) / xGridCount
                                val sx = worldX(gx)
                                drawLine(Color(0xFF252A31), Offset(sx, 0f), Offset(sx, h), 1f)
                            }
                            val yGridCount = 4
                            for (i in 0..yGridCount) {
                                val gy = yMin + i * (yMax - yMin) / yGridCount
                                val sy = worldY(gy)
                                drawLine(Color(0xFF252A31), Offset(0f, sy), Offset(w, sy), 1f)
                            }

                            // Zero Axes
                            if (0.0 in xMin..xMax) {
                                val zx = worldX(0.0)
                                drawLine(Color(0xFF444C56), Offset(zx, 0f), Offset(zx, h), 1.5f)
                            }
                            if (0.0 in yMin..yMax) {
                                val zy = worldY(0.0)
                                drawLine(Color(0xFF444C56), Offset(0f, zy), Offset(w, zy), 1.5f)
                            }

                            // Curve
                            val path = Path()
                            var inSeg = false
                            for (pt in cartesianPoints) {
                                val x = pt.first
                                val y = pt.second
                                if (y.isFinite() && y in (yMin - 1000.0)..(yMax + 1000.0)) {
                                    val sx = worldX(x)
                                    val sy = worldY(y).coerceIn(-50f, h + 50f)
                                    if (!inSeg) {
                                        path.moveTo(sx, sy)
                                        inSeg = true
                                    } else {
                                        path.lineTo(sx, sy)
                                    }
                                } else {
                                    inSeg = false
                                }
                            }
                            drawPath(path, color = Color(0xFFFFB300), style = Stroke(width = 2.5f))

                            // Touch Cursor
                            scrubParam?.let { sx ->
                                val sy = FastPlotEvaluator.evaluate(functionDef.body, mapOf(paramName to sx), boundValues)
                                if (sy.isFinite()) {
                                    val px = worldX(sx)
                                    val py = worldY(sy).coerceIn(0f, h)
                                    drawLine(Color(0xFF888888), Offset(px, 0f), Offset(px, h), 1f)
                                    drawCircle(Color.White, radius = 5f, center = Offset(px, py))
                                    drawCircle(Color(0xFFFFB300), radius = 5f, center = Offset(px, py), style = Stroke(1.5f))
                                }
                            }
                        }

                        PlotCoordinateMode.POLAR -> {
                            val centerX = w / 2f
                            val centerY = h / 2f
                            val maxRadius = min(centerX, centerY) * 0.85f

                            // Find maximum magnitude for normalization
                            val maxR = polarPoints.mapNotNull {
                                if (it.first.isFinite() && it.second.isFinite()) hypot(it.first, it.second) else null
                            }.maxOrNull()?.coerceAtLeast(1e-4) ?: 1.0

                            val scale = (maxRadius / maxR).toFloat()

                            // Concentric Circles
                            for (i in 1..4) {
                                val r = maxRadius * (i / 4f)
                                drawCircle(Color(0xFF252A31), radius = r, center = Offset(centerX, centerY), style = Stroke(1f))
                            }

                            // 30-degree spokes
                            for (deg in 0 until 360 step 30) {
                                val rad = deg * PI / 180f
                                val ex = centerX + maxRadius * cos(rad).toFloat()
                                val ey = centerY - maxRadius * sin(rad).toFloat()
                                drawLine(Color(0xFF252A31), Offset(centerX, centerY), Offset(ex, ey), 1f)
                            }

                            // Axes
                            drawLine(Color(0xFF444C56), Offset(centerX - maxRadius, centerY), Offset(centerX + maxRadius, centerY), 1.5f)
                            drawLine(Color(0xFF444C56), Offset(centerX, centerY - maxRadius), Offset(centerX, centerY + maxRadius), 1.5f)

                            // Polar Path
                            val path = Path()
                            var inSeg = false
                            for (pt in polarPoints) {
                                val px = pt.first
                                val py = pt.second
                                if (px.isFinite() && py.isFinite()) {
                                    val sx = centerX + (px * scale).toFloat()
                                    val sy = centerY - (py * scale).toFloat()
                                    if (!inSeg) {
                                        path.moveTo(sx, sy)
                                        inSeg = true
                                    } else {
                                        path.lineTo(sx, sy)
                                    }
                                } else {
                                    inSeg = false
                                }
                            }
                            drawPath(path, color = Color(0xFFFFB300), style = Stroke(width = 2.5f))
                        }

                        PlotCoordinateMode.PARAMETRIC -> {
                            val validX = parametricPoints.mapNotNull { if (it.first.isFinite()) it.first else null }
                            val validYPar = parametricPoints.mapNotNull { if (it.second.isFinite()) it.second else null }
                            if (validX.isNotEmpty() && validYPar.isNotEmpty()) {
                                val pXMin = validX.minOrNull() ?: -5.0
                                val pXMax = validX.maxOrNull() ?: 5.0
                                val pYMin = validYPar.minOrNull() ?: -5.0
                                val pYMax = validYPar.maxOrNull() ?: 5.0

                                val padX = max((pXMax - pXMin) * 0.1, 1.0)
                                val padY = max((pYMax - pYMin) * 0.1, 1.0)
                                val bXMin = pXMin - padX; val bXMax = pXMax + padX
                                val bYMin = pYMin - padY; val bYMax = pYMax + padY

                                fun mapX(x: Double) = ((x - bXMin) / (bXMax - bXMin) * w).toFloat()
                                fun mapY(y: Double) = ((bYMax - y) / (bYMax - bYMin) * h).toFloat()

                                // Axes
                                if (0.0 in bXMin..bXMax) {
                                    val zx = mapX(0.0)
                                    drawLine(Color(0xFF444C56), Offset(zx, 0f), Offset(zx, h), 1.5f)
                                }
                                if (0.0 in bYMin..bYMax) {
                                    val zy = mapY(0.0)
                                    drawLine(Color(0xFF444C56), Offset(0f, zy), Offset(w, zy), 1.5f)
                                }

                                val path = Path()
                                var inSeg = false
                                for (pt in parametricPoints) {
                                    val px = pt.first
                                    val py = pt.second
                                    if (px.isFinite() && py.isFinite()) {
                                        val sx = mapX(px)
                                        val sy = mapY(py)
                                        if (!inSeg) {
                                            path.moveTo(sx, sy)
                                            inSeg = true
                                        } else {
                                            path.lineTo(sx, sy)
                                        }
                                    } else {
                                        inSeg = false
                                    }
                                }
                                drawPath(path, color = Color(0xFFFFD54F), style = Stroke(width = 2.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}
