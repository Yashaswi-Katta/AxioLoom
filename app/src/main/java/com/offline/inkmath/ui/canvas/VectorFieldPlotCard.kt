package com.offline.inkmath.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.offline.inkmath.domain.evaluator.FastPlotEvaluator
import com.offline.inkmath.domain.model.MathExpr
import kotlin.math.*

object VectorFieldSimulator {

    fun rk4Streamline(
        pExpr: MathExpr,
        qExpr: MathExpr,
        x0: Double,
        y0: Double,
        steps: Int = 80,
        h: Double = 0.05,
        bindings: Map<String, Double> = emptyMap()
    ): List<Offset> {
        val path = ArrayList<Offset>(steps * 2 + 1)
        val params = HashMap<String, Double>(2)

        fun field(x: Double, y: Double): Pair<Double, Double> {
            params["x"] = x; params["y"] = y
            val u = FastPlotEvaluator.evaluate(pExpr, params, bindings).let { if (it.isFinite()) it else 0.0 }
            val v = FastPlotEvaluator.evaluate(qExpr, params, bindings).let { if (it.isFinite()) it else 0.0 }
            val mag = hypot(u, v)
            return if (mag > 1e-6) (u / mag) to (v / mag) else 0.0 to 0.0
        }

        // Backward integration
        var currX = x0
        var currY = y0
        val backward = ArrayList<Offset>(steps)
        for (i in 0 until steps) {
            val (k1u, k1v) = field(currX, currY)
            if (k1u == 0.0 && k1v == 0.0) break
            val (k2u, k2v) = field(currX - 0.5 * h * k1u, currY - 0.5 * h * k1v)
            val (k3u, k3v) = field(currX - 0.5 * h * k2u, currY - 0.5 * h * k2v)
            val (k4u, k4v) = field(currX - h * k3u, currY - h * k3v)

            currX -= (h / 6.0) * (k1u + 2.0 * k2u + 2.0 * k3u + k4u)
            currY -= (h / 6.0) * (k1v + 2.0 * k2v + 2.0 * k3v + k4v)
            if (abs(currX) > 6.0 || abs(currY) > 6.0) break
            backward.add(Offset(currX.toFloat(), currY.toFloat()))
        }
        backward.reverse()
        path.addAll(backward)
        path.add(Offset(x0.toFloat(), y0.toFloat()))

        // Forward integration
        currX = x0
        currY = y0
        for (i in 0 until steps) {
            val (k1u, k1v) = field(currX, currY)
            if (k1u == 0.0 && k1v == 0.0) break
            val (k2u, k2v) = field(currX + 0.5 * h * k1u, currY + 0.5 * h * k1v)
            val (k3u, k3v) = field(currX + 0.5 * h * k2u, currY + 0.5 * h * k2v)
            val (k4u, k4v) = field(currX + h * k3u, currY + h * k3v)

            currX += (h / 6.0) * (k1u + 2.0 * k2u + 2.0 * k3u + k4u)
            currY += (h / 6.0) * (k1v + 2.0 * k2v + 2.0 * k3v + k4v)
            if (abs(currX) > 6.0 || abs(currY) > 6.0) break
            path.add(Offset(currX.toFloat(), currY.toFloat()))
        }

        return path
    }
}

@Composable
fun VectorFieldPlotCard(
    modifier: Modifier = Modifier,
    pExpr: MathExpr,
    qExpr: MathExpr,
    title: String = "F(x, y) = ⟨P, Q⟩",
    onClose: () -> Unit = {}
) {
    val vectors = remember(pExpr, qExpr) {
        FastPlotEvaluator.sampleVectorField(pExpr, qExpr, gridSize = 15)
    }

    val maxMag = remember(vectors) {
        max(1e-4, vectors.maxOfOrNull { it.magnitude } ?: 1.0)
    }

    val streamlines = remember { mutableStateListOf<List<Offset>>() }

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
                        text = "2D VECTOR FIELD & STREAMLINES",
                        color = Color(0xFFFFB300),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = title,
                        color = Color(0xFFF4F5F7),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (streamlines.isNotEmpty()) {
                        TextButton(onClick = { streamlines.clear() }) {
                            Text("Clear", color = Color(0xFFADB5BD), fontSize = 11.sp)
                        }
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
                    .height(280.dp)
                    .background(Color(0xFF14171B), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF252A31), RoundedCornerShape(8.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(pExpr, qExpr) {
                            detectTapGestures { tapOffset ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val scale = min(size.width, size.height) / 11.0f
                                val worldX = ((tapOffset.x - cx) / scale).toDouble()
                                val worldY = (-(tapOffset.y - cy) / scale).toDouble()

                                val sl = VectorFieldSimulator.rk4Streamline(pExpr, qExpr, worldX, worldY)
                                if (sl.size > 2) {
                                    streamlines.add(sl)
                                }
                            }
                        }
                ) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val scale = min(size.width, size.height) / 11.0f

                    // Draw grid axes
                    drawLine(Color(0xFF252A31), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5f)
                    drawLine(Color(0xFF252A31), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.5f)

                    // Draw Quiver Arrows
                    val arrowMaxLen = scale * 0.55f
                    for (v in vectors) {
                        val sx = cx + (v.x * scale).toFloat()
                        val sy = cy - (v.y * scale).toFloat()

                        val magRatio = (v.magnitude / maxMag).coerceIn(0.0, 1.0)
                        val color = when {
                            magRatio < 0.3 -> Color(0xFF414852)
                            magRatio < 0.7 -> Color(0xFFFFD54F)
                            else -> Color(0xFFFFB300)
                        }
                        val strokeWidth = (1.0f + magRatio.toFloat() * 1.8f)

                        if (v.magnitude > 1e-4) {
                            val dirX = (v.u / v.magnitude).toFloat()
                            val dirY = (v.v / v.magnitude).toFloat()

                            val arrowLen = arrowMaxLen * (0.35f + 0.65f * magRatio.toFloat())
                            val endX = sx + dirX * arrowLen
                            val endY = sy - dirY * arrowLen

                            // Shaft
                            drawLine(color = color, start = Offset(sx, sy), end = Offset(endX, endY), strokeWidth = strokeWidth)

                            // Arrowhead
                            val headSize = 5f
                            val angle = atan2(-dirY, dirX)
                            val arrowAngle = PI.toFloat() / 6f

                            val p1 = Offset(
                                endX - headSize * cos(angle - arrowAngle),
                                endY - headSize * sin(angle - arrowAngle)
                            )
                            val p2 = Offset(
                                endX - headSize * cos(angle + arrowAngle),
                                endY - headSize * sin(angle + arrowAngle)
                            )
                            drawLine(color = color, start = Offset(endX, endY), end = p1, strokeWidth = strokeWidth)
                            drawLine(color = color, start = Offset(endX, endY), end = p2, strokeWidth = strokeWidth)
                        } else {
                            drawCircle(color = Color(0xFF414852), radius = 1.5f, center = Offset(sx, sy))
                        }
                    }

                    // Draw Streamlines
                    for (streamline in streamlines) {
                        val path = Path()
                        streamline.forEachIndexed { idx, pt ->
                            val sx = cx + (pt.x * scale)
                            val sy = cy - (pt.y * scale)
                            if (idx == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                        }
                        drawPath(path = path, color = Color(0xFFFFD54F), style = Stroke(width = 2f))
                    }
                }
            }

            // Instructions
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Tap anywhere on plot to seed RK4 streamline",
                    color = Color(0xFFADB5BD),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "max ||F|| = ${"%.2f".format(maxMag)}",
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
