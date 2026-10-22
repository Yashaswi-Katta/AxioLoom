package com.axioloom.app.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.*

data class RadialMenuItem(
    val label: String,
    val templateLatex: String
)

val RADIAL_MENU_ITEMS = listOf(
    RadialMenuItem("∫", "\\int_{0}^{1} x^2 d x ="),
    RadialMenuItem("d/dx", "\\frac{d}{d x}(x^3) ="),
    RadialMenuItem("∑", "\\sum_{n=1}^{10} n ="),
    RadialMenuItem("[M]", "\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}"),
    RadialMenuItem("ℒ", "\\mathcal{L}\\{e^{-2t}\\} ="),
    RadialMenuItem("H(s)", "H(s) = \\frac{10}{s + 2}"),
    RadialMenuItem("||", "R_1 \\parallel R_2 ="),
    RadialMenuItem("KMap", "\\operatorname{kmap}(4, \\sum m(0, 1, 2, 3))")
)

@Composable
fun RadialSymbolMenu(
    screenCenter: Offset,
    worldCenter: Offset,
    onSelectTemplate: (String, Offset) -> Unit,
    onDismiss: () -> Unit
) {
    val sizePx = 240.dp
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tapPos ->
                    val dx = tapPos.x - screenCenter.x
                    val dy = tapPos.y - screenCenter.y
                    val dist = hypot(dx, dy)
                    val rInner = 36.dp.toPx()
                    val rOuter = 120.dp.toPx()

                    if (dist < rInner) {
                        onDismiss()
                    } else if (dist <= rOuter) {
                        var angleDeg = (atan2(dy, dx) * 180.0 / PI + 360.0) % 360.0
                        val sliceDeg = 360.0 / RADIAL_MENU_ITEMS.size
                        val idx = ((angleDeg + sliceDeg / 2.0) % 360.0 / sliceDeg).toInt()
                            .coerceIn(0, RADIAL_MENU_ITEMS.size - 1)
                        onSelectTemplate(RADIAL_MENU_ITEMS[idx].templateLatex, worldCenter)
                    } else {
                        onDismiss()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = screenCenter.x
            val cy = screenCenter.y
            val rInner = 36.dp.toPx()
            val rOuter = 115.dp.toPx()
            val numSlices = RADIAL_MENU_ITEMS.size
            val sliceDeg = 360.0 / numSlices

            for (i in 0 until numSlices) {
                val startAngle = (i * sliceDeg - sliceDeg / 2.0).toFloat()
                val sweepAngle = (sliceDeg - 1.5).toFloat()

                val isHovered = (hoveredIndex == i)
                val fillColor = if (isHovered) Color(0xFF2A313C) else Color(0xFF1E222A)
                val strokeColor = if (isHovered) Color(0xFFFFB300) else Color(0xFF2E3846)

                val path = Path().apply {
                    val startRad = startAngle * PI / 180.0
                    val endRad = (startAngle + sweepAngle) * PI / 180.0

                    moveTo(
                        (cx + rInner * cos(startRad)).toFloat(),
                        (cy + rInner * sin(startRad)).toFloat()
                    )
                    lineTo(
                        (cx + rOuter * cos(startRad)).toFloat(),
                        (cy + rOuter * sin(startRad)).toFloat()
                    )
                    arcTo(
                        rect = Rect(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter),
                        startAngleDegrees = startAngle,
                        sweepAngleDegrees = sweepAngle,
                        forceMoveTo = false
                    )
                    lineTo(
                        (cx + rInner * cos(endRad)).toFloat(),
                        (cy + rInner * sin(endRad)).toFloat()
                    )
                    arcTo(
                        rect = Rect(cx - rInner, cy - rInner, cx + rInner, cy + rInner),
                        startAngleDegrees = startAngle + sweepAngle,
                        sweepAngleDegrees = -sweepAngle,
                        forceMoveTo = false
                    )
                    close()
                }

                drawPath(path = path, color = fillColor)
                drawPath(path = path, color = strokeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f))

                // Draw Text Label at sector centroid
                val midAngleRad = (i * sliceDeg) * PI / 180.0
                val labelR = (rInner + rOuter) / 2f
                val tx = (cx + labelR * cos(midAngleRad)).toFloat()
                val ty = (cy + labelR * sin(midAngleRad)).toFloat()

                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = if (isHovered) android.graphics.Color.parseColor("#FFD54F") else android.graphics.Color.parseColor("#F4F5F7")
                        textSize = 34f
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    canvas.nativeCanvas.drawText(
                        RADIAL_MENU_ITEMS[i].label,
                        tx,
                        ty + 12f,
                        paint
                    )
                }
            }

            // Draw center close circle
            drawCircle(color = Color(0xFF16191D), radius = rInner, center = Offset(cx, cy))
            drawCircle(color = Color(0xFFDC143C), radius = rInner, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#DC143C")
                    textSize = 36f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.nativeCanvas.drawText("✕", cx, cy + 12f, paint)
            }
        }
    }
}
