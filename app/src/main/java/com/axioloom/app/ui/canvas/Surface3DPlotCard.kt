package com.axioloom.app.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axioloom.app.domain.evaluator.FastPlotEvaluator
import com.axioloom.app.domain.model.MathExpr
import kotlin.math.*

data class ProjectedQuad(
    val p0: Offset,
    val p1: Offset,
    val p2: Offset,
    val p3: Offset,
    val avgDepth: Double,
    val avgHeightRatio: Double
)

object Surface3DProjector {

    data class Point3D(val x: Double, val y: Double, val z: Double)
    data class ScreenPoint(val x: Float, val y: Float, val depth: Double)

    fun project(
        x: Double,
        y: Double,
        z: Double,
        yawDeg: Double,
        pitchDeg: Double,
        scale: Double,
        centerX: Float,
        centerY: Float
    ): ScreenPoint {
        val theta = yawDeg * PI / 180.0
        val phi = pitchDeg * PI / 180.0

        val cosT = cos(theta)
        val sinT = sin(theta)
        val cosP = cos(phi)
        val sinP = sin(phi)

        // Rz(theta)
        val x1 = x * cosT - y * sinT
        val y1 = x * sinT + y * cosT
        val z1 = z

        // Rx(phi)
        val x2 = x1
        val y2 = y1 * cosP - z1 * sinP
        val z2 = y1 * sinP + z1 * cosP

        val screenX = (centerX + x2 * scale).toFloat()
        val screenY = (centerY - y2 * scale).toFloat()

        return ScreenPoint(screenX, screenY, z2)
    }

    fun buildQuads(
        grid: FastPlotEvaluator.SurfaceGridData,
        yawDeg: Double,
        pitchDeg: Double,
        scale: Double,
        centerX: Float,
        centerY: Float
    ): List<ProjectedQuad> {
        val steps = grid.steps
        val projected = ArrayList<ScreenPoint>(grid.vertices.size)
        for (v in grid.vertices) {
            projected.add(project(v.x, v.y, v.z, yawDeg, pitchDeg, scale, centerX, centerY))
        }

        val zSpan = max(1e-4, grid.zMax - grid.zMin)
        val quads = ArrayList<ProjectedQuad>((steps - 1) * (steps - 1))

        for (i in 0 until steps - 1) {
            for (j in 0 until steps - 1) {
                val idx00 = i * steps + j
                val idx10 = (i + 1) * steps + j
                val idx11 = (i + 1) * steps + (j + 1)
                val idx01 = i * steps + (j + 1)

                val pt00 = projected[idx00]
                val pt10 = projected[idx10]
                val pt11 = projected[idx11]
                val pt01 = projected[idx01]

                val avgDepth = (pt00.depth + pt10.depth + pt11.depth + pt01.depth) / 4.0
                val avgZ = (grid.vertices[idx00].z + grid.vertices[idx10].z + grid.vertices[idx11].z + grid.vertices[idx01].z) / 4.0
                val heightRatio = ((avgZ - grid.zMin) / zSpan).coerceIn(0.0, 1.0)

                quads.add(
                    ProjectedQuad(
                        p0 = Offset(pt00.x, pt00.y),
                        p1 = Offset(pt10.x, pt10.y),
                        p2 = Offset(pt11.x, pt11.y),
                        p3 = Offset(pt01.x, pt01.y),
                        avgDepth = avgDepth,
                        avgHeightRatio = heightRatio
                    )
                )
            }
        }

        // Painter's algorithm: sort ascending by depth (furthest drawn first)
        quads.sortBy { it.avgDepth }
        return quads
    }

    fun heightToColor(ratio: Double): Color {
        val r = ratio.coerceIn(0.0, 1.0)
        return when {
            r < 0.4 -> {
                val t = (r / 0.4).toFloat()
                lerpColor(Color(0xFF1E242C), Color(0xFFDC143C), t)
            }
            r < 0.8 -> {
                val t = ((r - 0.4) / 0.4).toFloat()
                lerpColor(Color(0xFFDC143C), Color(0xFFFFB300), t)
            }
            else -> {
                val t = ((r - 0.8) / 0.2).toFloat()
                lerpColor(Color(0xFFFFB300), Color(0xFFFFD54F), t)
            }
        }
    }

    private fun lerpColor(c1: Color, c2: Color, t: Float): Color {
        return Color(
            red = c1.red + (c2.red - c1.red) * t,
            green = c1.green + (c2.green - c1.green) * t,
            blue = c1.blue + (c2.blue - c1.blue) * t,
            alpha = 1f
        )
    }
}

@Composable
fun Surface3DPlotCard(
    modifier: Modifier = Modifier,
    expr: MathExpr,
    functionName: String = "z = f(x, y)",
    boundValues: Map<String, Double> = emptyMap(),
    onClose: () -> Unit = {}
) {
    var yaw by remember { mutableStateOf(-45f) }
    var pitch by remember { mutableStateOf(35f) }
    var zoomScale by remember { mutableStateOf(16f) }
    var wireframeOnly by remember { mutableStateOf(false) }

    val surfaceData = remember(expr, boundValues) {
        FastPlotEvaluator.sampleSurface(expr, steps = 36, bindings = boundValues)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF252A31), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121417)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "3D ISOMETRIC SURFACE PLOTTER",
                        color = Color(0xFFFFB300),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = functionName,
                        color = Color(0xFFF4F5F7),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { yaw = -45f; pitch = 35f; zoomScale = 16f }) {
                        Text("Reset", color = Color(0xFFADB5BD), fontSize = 11.sp)
                    }
                    TextButton(onClick = { wireframeOnly = !wireframeOnly }) {
                        Text(if (wireframeOnly) "Solid" else "Wire", color = Color(0xFFFFD54F), fontSize = 11.sp)
                    }
                    TextButton(onClick = onClose) {
                        Text("Close", color = Color(0xFFDC143C), fontSize = 11.sp)
                    }
                }
            }

            // Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color(0xFF14171B), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF252A31), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            yaw = (yaw + dragAmount.x * 0.5f).coerceIn(-180f, 180f)
                            pitch = (pitch - dragAmount.y * 0.4f).coerceIn(10f, 80f)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f

                    // Draw 3D axis box
                    val boxSize = 5.0
                    val boxCorners = listOf(
                        Triple(-boxSize, -boxSize, -boxSize),
                        Triple(boxSize, -boxSize, -boxSize),
                        Triple(boxSize, boxSize, -boxSize),
                        Triple(-boxSize, boxSize, -boxSize),
                        Triple(-boxSize, -boxSize, boxSize),
                        Triple(boxSize, -boxSize, boxSize),
                        Triple(boxSize, boxSize, boxSize),
                        Triple(-boxSize, boxSize, boxSize)
                    ).map { Surface3DProjector.project(it.first, it.second, it.third, yaw.toDouble(), pitch.toDouble(), zoomScale.toDouble(), cx, cy) }

                    // Wireframe bounding box edges
                    val edges = listOf(
                        0 to 1, 1 to 2, 2 to 3, 3 to 0,
                        4 to 5, 5 to 6, 6 to 7, 7 to 4,
                        0 to 4, 1 to 5, 2 to 6, 3 to 7
                    )
                    for (e in edges) {
                        drawLine(
                            color = Color(0xFF222933),
                            start = Offset(boxCorners[e.first].x, boxCorners[e.first].y),
                            end = Offset(boxCorners[e.second].x, boxCorners[e.second].y),
                            strokeWidth = 1f
                        )
                    }

                    // Build and sort surface quads
                    val quads = Surface3DProjector.buildQuads(
                        grid = surfaceData,
                        yawDeg = yaw.toDouble(),
                        pitchDeg = pitch.toDouble(),
                        scale = zoomScale.toDouble(),
                        centerX = cx,
                        centerY = cy
                    )

                    for (quad in quads) {
                        val path = Path().apply {
                            moveTo(quad.p0.x, quad.p0.y)
                            lineTo(quad.p1.x, quad.p1.y)
                            lineTo(quad.p2.x, quad.p2.y)
                            lineTo(quad.p3.x, quad.p3.y)
                            close()
                        }

                        if (!wireframeOnly) {
                            val fillColor = Surface3DProjector.heightToColor(quad.avgHeightRatio)
                            drawPath(path = path, color = fillColor)
                        }

                        drawPath(
                            path = path,
                            color = if (wireframeOnly) Surface3DProjector.heightToColor(quad.avgHeightRatio) else Color(0x33121417),
                            style = Stroke(width = if (wireframeOnly) 1.2f else 0.8f)
                        )
                    }
                }
            }

            // Controls & Metrics
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Yaw: ${yaw.roundToInt()}°  Pitch: ${pitch.roundToInt()}°  z ∈ [${String.format(java.util.Locale.US, "%.1f", surfaceData.zMin)}, ${String.format(java.util.Locale.US, "%.1f", surfaceData.zMax)}]",
                    color = Color(0xFFADB5BD),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom: ", color = Color(0xFFADB5BD), fontSize = 11.sp)
                    Slider(
                        value = zoomScale,
                        onValueChange = { zoomScale = it },
                        valueRange = 8f..32f,
                        modifier = Modifier.width(100.dp),
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB300), activeTrackColor = Color(0xFFFFB300))
                    )
                }
            }
        }
    }
}
