package com.axioloom.app.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.sim.OdeNumericalSimulator
import com.axioloom.app.domain.sim.OdeTrajectory
import kotlin.math.max
import kotlin.math.min

enum class OdeSystemPreset(val title: String) {
    VAN_DER_POL("Van der Pol Oscillator"),
    HARMONIC("Harmonic Oscillator"),
    PENDULUM("Non-Linear Pendulum"),
    LORENZ("Lorenz Attractor")
}

@Composable
fun OdeSimulationCard(
    modifier: Modifier = Modifier,
    canvasOde: MathExpr? = null,
    onClose: () -> Unit = {}
) {
    var selectedPreset by remember { mutableStateOf(OdeSystemPreset.VAN_DER_POL) }
    var paramSlider by remember { mutableStateOf(1.5f) } // mu for VdP, omega for Harmonic, damping for Pendulum
    var initialPos by remember { mutableStateOf(0.5f) }
    var selectedViewMode by remember { mutableStateOf(0) } // 0 = Time Domain, 1 = Phase Plane

    val trajectory: OdeTrajectory = remember(selectedPreset, paramSlider, initialPos) {
        when (selectedPreset) {
            OdeSystemPreset.VAN_DER_POL -> {
                OdeNumericalSimulator.vanDerPol(mu = paramSlider.toDouble(), y0 = initialPos.toDouble())
            }
            OdeSystemPreset.HARMONIC -> {
                OdeNumericalSimulator.harmonicOscillator(omega = paramSlider.toDouble(), y0 = initialPos.toDouble())
            }
            OdeSystemPreset.PENDULUM -> {
                OdeNumericalSimulator.nonLinearPendulum(damping = paramSlider.toDouble() * 0.1, theta0 = initialPos.toDouble() * 2.0)
            }
            OdeSystemPreset.LORENZ -> {
                OdeNumericalSimulator.lorenzAttractor(
                    rho = 28.0 * (paramSlider.toDouble() / 1.5),
                    x0 = if (initialPos == 0f) 0.01 else initialPos.toDouble()
                )
            }
        }
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
                        text = "NON-LINEAR ODE RK45 SIMULATOR",
                        color = Color(0xFFFFB300),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = selectedPreset.title,
                        color = Color(0xFFF4F5F7),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                TextButton(onClick = onClose) {
                    Text("Close", color = Color(0xFFDC143C), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Presets row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OdeSystemPreset.values().forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(preset.name.replace("_", " "), fontSize = 10.sp) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Tab Mode: Time-domain vs Phase-plane
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedViewMode == 0,
                        onClick = { selectedViewMode = 0 },
                        label = { Text("Time Domain x(t)", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedViewMode == 1,
                        onClick = { selectedViewMode = 1 },
                        label = { Text("Phase Plane (x2 vs x1)", fontSize = 11.sp) }
                    )
                }

                Text(
                    text = "${trajectory.stepCount} steps",
                    color = Color(0xFF88929E),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(8.dp))

            // Canvas Plot Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Color(0xFF14171B), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF252A31), RoundedCornerShape(8.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    if (trajectory.stepCount < 2) return@Canvas

                    if (selectedViewMode == 0) {
                        // TIME DOMAIN: x_1(t) [Amber] and x_2(t) [Gold] vs t
                        val tMin = trajectory.timePoints.first()
                        val tMax = trajectory.timePoints.last()
                        val dt = max(1e-4, tMax - tMin)

                        val series1 = trajectory.getSeries(0)
                        val series2 = if (trajectory.stateCount > 1) trajectory.getSeries(1) else null

                        var yMin = series1.minOrNull() ?: -1.0
                        var yMax = series1.maxOrNull() ?: 1.0
                        if (series2 != null) {
                            yMin = min(yMin, series2.minOrNull() ?: yMin)
                            yMax = max(yMax, series2.maxOrNull() ?: yMax)
                        }
                        val dy = max(1e-4, yMax - yMin)

                        // Draw zero line
                        if (yMin < 0.0 && yMax > 0.0) {
                            val yZero = (h * (1.0 - (0.0 - yMin) / dy)).toFloat()
                            drawLine(Color(0xFF252A31), Offset(0f, yZero), Offset(w, yZero), strokeWidth = 1.5f)
                        }

                        // Path 1 (Amber)
                        val path1 = Path()
                        for (i in 0 until trajectory.stepCount) {
                            val px = (w * (trajectory.timePoints[i] - tMin) / dt).toFloat()
                            val py = (h * (1.0 - (series1[i] - yMin) / dy)).toFloat()
                            if (i == 0) path1.moveTo(px, py) else path1.lineTo(px, py)
                        }
                        drawPath(path1, Color(0xFFFFB300), style = Stroke(width = 2.5f))

                        // Path 2 (Gold)
                        if (series2 != null) {
                            val path2 = Path()
                            for (i in 0 until trajectory.stepCount) {
                                val px = (w * (trajectory.timePoints[i] - tMin) / dt).toFloat()
                                val py = (h * (1.0 - (series2[i] - yMin) / dy)).toFloat()
                                if (i == 0) path2.moveTo(px, py) else path2.lineTo(px, py)
                            }
                            drawPath(path2, Color(0xFFFFD54F), style = Stroke(width = 1.8f))
                        }
                    } else {
                        // PHASE PLANE: x_2 vs x_1
                        val s1 = trajectory.getSeries(0)
                        val s2 = if (trajectory.stateCount > 1) trajectory.getSeries(1) else trajectory.getSeries(0)

                        val xMin = s1.minOrNull() ?: -1.0
                        val xMax = s1.maxOrNull() ?: 1.0
                        val yMin = s2.minOrNull() ?: -1.0
                        val yMax = s2.maxOrNull() ?: 1.0

                        val dx = max(1e-4, xMax - xMin)
                        val dy = max(1e-4, yMax - yMin)

                        // Center crosshairs
                        val cx = (w * (0.0 - xMin) / dx).toFloat()
                        val cy = (h * (1.0 - (0.0 - yMin) / dy)).toFloat()
                        drawLine(Color(0xFF252A31), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1f)
                        drawLine(Color(0xFF252A31), Offset(0f, cy), Offset(w, cy), strokeWidth = 1f)

                        // Trajectory path (Warm Amber)
                        val path = Path()
                        for (i in 0 until trajectory.stepCount) {
                            val px = (w * (s1[i] - xMin) / dx).toFloat()
                            val py = (h * (1.0 - (s2[i] - yMin) / dy)).toFloat()
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(path, Color(0xFFFFB300), style = Stroke(width = 2f))

                        // Start dot (Crimson)
                        val sx = (w * (s1[0] - xMin) / dx).toFloat()
                        val sy = (h * (1.0 - (s2[0] - yMin) / dy)).toFloat()
                        drawCircle(Color(0xFFDC143C), radius = 4.5f, center = Offset(sx, sy))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Parameter Scrubbing Sliders
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Param: ${String.format(java.util.Locale.US, "%.2f", paramSlider)}",
                    fontSize = 11.sp,
                    color = Color(0xFFFFD54F),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(100.dp)
                )
                Slider(
                    value = paramSlider,
                    onValueChange = { paramSlider = it },
                    valueRange = 0.1f..5.0f,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Init x0: ${String.format(java.util.Locale.US, "%.2f", initialPos)}",
                    fontSize = 11.sp,
                    color = Color(0xFFFFB300),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(100.dp)
                )
                Slider(
                    value = initialPos,
                    onValueChange = { initialPos = it },
                    valueRange = -2.0f..2.0f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
