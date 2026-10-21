package com.offline.inkmath.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.inkmath.domain.cas.ComplexNumber
import com.offline.inkmath.domain.cas.StabilityVerdict
import com.offline.inkmath.domain.cas.TransferFunctionAnalysis
import java.util.Locale
import kotlin.math.*

enum class ControlCardTab {
    BODE_PLOT,
    S_PLANE
}

@Composable
fun TransferFunctionAnalyzerCard(
    analysis: TransferFunctionAnalysis,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(ControlCardTab.BODE_PLOT) }
    var touchCursorOmega by remember { mutableStateOf<Double?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF191D22),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            // Header: Title, stability pill, tabs and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${analysis.functionName}(${analysis.variable}) Analysis",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )

                    val (verdictBg, verdictColor) = when (analysis.stability) {
                        StabilityVerdict.STABLE -> Color(0xFF1B4332) to Color(0xFF52B788)
                        StabilityVerdict.MARGINALLY_STABLE -> Color(0xFF5C4D00) to Color(0xFFFFD54F)
                        StabilityVerdict.UNSTABLE -> Color(0xFF4A101D) to Color(0xFFDC143C)
                    }

                    Surface(
                        color = verdictBg,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            analysis.stability.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = verdictColor
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = activeTab == ControlCardTab.BODE_PLOT,
                        onClick = { activeTab = ControlCardTab.BODE_PLOT },
                        label = { Text("Bode", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = activeTab == ControlCardTab.S_PLANE,
                        onClick = { activeTab = ControlCardTab.S_PLANE },
                        label = { Text("s-Plane", style = MaterialTheme.typography.labelSmall) }
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color(0xFFADB5BD), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when (activeTab) {
                ControlCardTab.BODE_PLOT -> {
                    BodePlotCanvas(
                        analysis = analysis,
                        touchCursorOmega = touchCursorOmega,
                        onCursorChange = { touchCursorOmega = it }
                    )
                }
                ControlCardTab.S_PLANE -> {
                    SPlaneCanvas(analysis = analysis)
                }
            }
        }
    }
}

@Composable
private fun BodePlotCanvas(
    analysis: TransferFunctionAnalysis,
    touchCursorOmega: Double?,
    onCursorChange: (Double?) -> Unit
) {
    val points = analysis.bodePoints
    if (points.isEmpty()) return

    val minMag = points.minOf { it.magnitudeDb }.coerceAtMost(-40.0)
    val maxMag = points.maxOf { it.magnitudeDb }.coerceAtLeast(20.0)
    val minPhase = points.minOf { it.phaseDeg }.coerceAtMost(-180.0)
    val maxPhase = points.maxOf { it.phaseDeg }.coerceAtLeast(45.0)

    val activeCursorPoint = touchCursorOmega?.let { w ->
        points.minByOrNull { abs(it.omega - w) }
    }

    Column {
        // Readout bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val point = activeCursorPoint ?: points.first()
            Text(
                String.format(Locale.US, "ω: %.2f rad/s", point.omega),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFADB5BD)
            )
            Text(
                String.format(Locale.US, "Mag: %.2f dB", point.magnitudeDb),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFB300),
                fontWeight = FontWeight.Bold
            )
            Text(
                String.format(Locale.US, "Phase: %.1f°", point.phaseDeg),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFD54F),
                fontWeight = FontWeight.Bold
            )
            analysis.cutoffFrequencyRad?.let { wc ->
                Text(
                    String.format(Locale.US, "ω-3dB: %.2f", wc),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF52B788)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF121417), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            val w = 10.0.pow(-1.0 + frac * 5.0)
                            onCursorChange(w)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        val w = 10.0.pow(-1.0 + frac * 5.0)
                        onCursorChange(w)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // Decade grid lines: 10^-1, 10^0, 10^1, 10^2, 10^3, 10^4
            val gridColor = Color(0xFF252A31)
            for (dec in -1..4) {
                val frac = (dec - (-1.0)) / 5.0
                val x = (frac * w).toFloat()
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }

            // Divider between Magnitude (top half) and Phase (bottom half)
            drawLine(Color(0xFF323943), Offset(0f, midY), Offset(w, midY), strokeWidth = 1.5f)

            // 1. Draw Magnitude Plot (0..midY)
            val magPath = Path()
            points.forEachIndexed { i, p ->
                val x = (i / (points.size - 1).toFloat()) * w
                val normMag = ((p.magnitudeDb - minMag) / (maxMag - minMag)).coerceIn(0.0, 1.0).toFloat()
                val y = midY - normMag * (midY - 8f) - 4f
                if (i == 0) magPath.moveTo(x, y) else magPath.lineTo(x, y)
            }
            drawPath(magPath, Color(0xFFFFB300), style = Stroke(width = 2.5f))

            // Cutoff -3dB line on magnitude
            val target3Db = analysis.dcGainDb - 3.0
            if (target3Db in minMag..maxMag) {
                val normCutoff = ((target3Db - minMag) / (maxMag - minMag)).toFloat()
                val yCutoff = midY - normCutoff * (midY - 8f) - 4f
                drawLine(
                    Color(0xFF52B788),
                    Offset(0f, yCutoff),
                    Offset(w, yCutoff),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            }

            // 2. Draw Phase Plot (midY..h)
            val phasePath = Path()
            points.forEachIndexed { i, p ->
                val x = (i / (points.size - 1).toFloat()) * w
                val normPhase = ((p.phaseDeg - minPhase) / (maxPhase - minPhase)).coerceIn(0.0, 1.0).toFloat()
                val y = h - normPhase * (midY - 8f) - 4f
                if (i == 0) phasePath.moveTo(x, y) else phasePath.lineTo(x, y)
            }
            drawPath(phasePath, Color(0xFFFFD54F), style = Stroke(width = 2.5f))

            // Touch cursor vertical line
            touchCursorOmega?.let { curW ->
                val frac = (log10(curW) - (-1.0)) / 5.0
                val curX = (frac * w).toFloat().coerceIn(0f, w)
                drawLine(
                    Color.White,
                    Offset(curX, 0f),
                    Offset(curX, h),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                )
            }
        }
    }
}

@Composable
private fun SPlaneCanvas(analysis: TransferFunctionAnalysis) {
    val poles = analysis.poles
    val zeros = analysis.zeros

    val maxCoord = listOf(
        poles.maxOfOrNull { max(abs(it.re), abs(it.im)) } ?: 1.0,
        zeros.maxOfOrNull { max(abs(it.re), abs(it.im)) } ?: 1.0,
        2.0
    ).max().coerceAtLeast(1.0) * 1.3

    Column {
        // Summary list of poles & zeros
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Poles (×): ${if (poles.isEmpty()) "None" else poles.joinToString(", ") { it.formatted() }}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFDC143C)
            )
            Text(
                "Zeros (○): ${if (zeros.isEmpty()) "None" else zeros.joinToString(", ") { it.formatted() }}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFB300)
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF121417), RoundedCornerShape(8.dp))
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val scale = min(cx, cy) / maxCoord.toFloat()

            // 1. Shade Left-Half Plane (LHP: sigma < 0) subtle green/charcoal
            drawRect(
                color = Color(0x1852B788),
                topLeft = Offset(0f, 0f),
                size = Size(cx, h)
            )

            // 2. Axes: Real (sigma) and Imaginary (j*omega)
            val axisColor = Color(0xFF3A424E)
            drawLine(axisColor, Offset(0f, cy), Offset(w, cy), strokeWidth = 1.5f)
            drawLine(axisColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)

            // Grid tick circles
            for (r in 1..maxCoord.toInt()) {
                val radius = r * scale
                if (radius < min(cx, cy)) {
                    drawCircle(
                        Color(0xFF1E242B),
                        radius = radius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1f)
                    )
                }
            }

            // 3. Draw Zeros as Amber (O) markers
            for (z in zeros) {
                val x = cx + (z.re * scale).toFloat()
                val y = cy - (z.im * scale).toFloat()
                drawCircle(
                    color = Color(0xFFFFB300),
                    radius = 6f,
                    center = Offset(x, y),
                    style = Stroke(width = 2.5f)
                )
            }

            // 4. Draw Poles as Crimson Red (X) markers
            for (p in poles) {
                val x = cx + (p.re * scale).toFloat()
                val y = cy - (p.im * scale).toFloat()
                val size = 6f
                drawLine(Color(0xFFDC143C), Offset(x - size, y - size), Offset(x + size, y + size), strokeWidth = 2.5f)
                drawLine(Color(0xFFDC143C), Offset(x - size, y + size), Offset(x + size, y - size), strokeWidth = 2.5f)
            }
        }
    }
}
