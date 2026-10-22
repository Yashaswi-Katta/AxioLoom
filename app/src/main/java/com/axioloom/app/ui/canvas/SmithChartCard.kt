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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

data class SmithChartAnalysis(
    val rNorm: Double,
    val xNorm: Double,
    val gammaRe: Double,
    val gammaIm: Double,
    val gammaMag: Double,
    val gammaAngleDeg: Double,
    val vswr: Double,
    val returnLossDb: Double,
    val zInRe: Double,
    val zInIm: Double
)

object SmithChartMath {
    fun compute(rL: Double, xL: Double, z0: Double = 50.0): SmithChartAnalysis {
        require(z0 > 0.0) { "Z0 must be positive" }
        val r = max(0.0, rL / z0)
        val x = xL / z0

        // Gamma = (z - 1) / (z + 1) = ((r - 1) + j*x) / ((r + 1) + j*x)
        val denom = (r + 1) * (r + 1) + x * x
        val gammaRe = if (denom > 1e-12) ((r - 1) * (r + 1) + x * x) / denom else 1.0
        val gammaIm = if (denom > 1e-12) (2.0 * x) / denom else 0.0

        val gammaMag = hypot(gammaRe, gammaIm)
        val gammaAngleDeg = atan2(gammaIm, gammaRe) * 180.0 / PI

        val vswr = if (gammaMag < 0.99999) {
            (1.0 + gammaMag) / (1.0 - gammaMag)
        } else {
            Double.POSITIVE_INFINITY
        }

        val returnLossDb = if (gammaMag > 1e-6) {
            -20.0 * log10(gammaMag)
        } else {
            Double.POSITIVE_INFINITY
        }

        // Quarter-wave input impedance Zin = Z0^2 / ZL
        val zlDenom = rL * rL + xL * xL
        val zInRe = if (zlDenom > 1e-12) (z0 * z0 * rL) / zlDenom else Double.POSITIVE_INFINITY
        val zInIm = if (zlDenom > 1e-12) (-z0 * z0 * xL) / zlDenom else 0.0

        return SmithChartAnalysis(
            rNorm = r,
            xNorm = x,
            gammaRe = gammaRe,
            gammaIm = gammaIm,
            gammaMag = gammaMag,
            gammaAngleDeg = gammaAngleDeg,
            vswr = vswr,
            returnLossDb = returnLossDb,
            zInRe = zInRe,
            zInIm = zInIm
        )
    }
}

@Composable
fun SmithChartCard(
    modifier: Modifier = Modifier,
    initialRL: Double = 100.0,
    initialXL: Double = 0.0,
    initialZ0: Double = 50.0,
    onClose: () -> Unit = {}
) {
    var rL by remember { mutableStateOf(initialRL) }
    var xL by remember { mutableStateOf(initialXL) }
    var z0 by remember { mutableStateOf(initialZ0) }

    val analysis = remember(rL, xL, z0) {
        SmithChartMath.compute(rL, xL, z0)
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
                Text(
                    text = "RF SMITH CHART & TRANSMISSION LINE",
                    color = Color(0xFFFFB300),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(onClick = onClose) {
                    Text("Close", color = Color(0xFFDC143C))
                }
            }

            // Quick presets
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { rL = 50.0; xL = 0.0 },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252A31)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("Matched 50Ω", fontSize = 11.sp, color = Color(0xFFF4F5F7)) }
                Button(
                    onClick = { rL = 100.0; xL = 0.0 },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252A31)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("100Ω (2:1 VSWR)", fontSize = 11.sp, color = Color(0xFFF4F5F7)) }
                Button(
                    onClick = { rL = 25.0; xL = 35.0 },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252A31)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("25+j35Ω", fontSize = 11.sp, color = Color(0xFFF4F5F7)) }
                Button(
                    onClick = { rL = 50.0; xL = -50.0 },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252A31)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("50-j50Ω", fontSize = 11.sp, color = Color(0xFFF4F5F7)) }
            }

            // Scrub Sliders
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("R_L: ${rL.roundToInt()}Ω", color = Color(0xFFADB5BD), fontSize = 11.sp, modifier = Modifier.width(64.dp))
                Slider(
                    value = rL.toFloat(),
                    onValueChange = { rL = it.roundToInt().toDouble() },
                    valueRange = 0f..250f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFB300), activeTrackColor = Color(0xFFFFB300))
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sign = if (xL >= 0) "+" else ""
                Text("X_L: $sign${xL.roundToInt()}Ω", color = Color(0xFFADB5BD), fontSize = 11.sp, modifier = Modifier.width(64.dp))
                Slider(
                    value = xL.toFloat(),
                    onValueChange = { xL = it.roundToInt().toDouble() },
                    valueRange = -150f..150f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD54F), activeTrackColor = Color(0xFFFFD54F))
                )
            }

            // Smith Chart Vector Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color(0xFF16191D), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF252A31), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                SmithChartCanvas(analysis = analysis)
            }

            // Detailed Parameters Readout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Color(0xFF191D22), RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "Z_L = ${rL.roundToInt()} + j(${xL.roundToInt()}) Ω  (z_L = ${"%.2f".format(analysis.rNorm)} + j${"%.2f".format(analysis.xNorm)})",
                        color = Color(0xFFF4F5F7),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Z_0 = ${z0.roundToInt()} Ω",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "|Γ| = ${"%.3f".format(analysis.gammaMag)} ∠ ${"%.1f".format(analysis.gammaAngleDeg)}°",
                        color = Color(0xFFFFB300),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "VSWR = ${if (analysis.vswr.isInfinite()) "∞" else "%.2f".format(analysis.vswr)}",
                        color = if (analysis.vswr <= 1.5) Color(0xFF4CAF50) else if (analysis.vswr <= 2.5) Color(0xFFFFD54F) else Color(0xFFDC143C),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "RL = ${if (analysis.returnLossDb.isInfinite()) "∞" else "%.1f".format(analysis.returnLossDb)} dB",
                        color = Color(0xFFADB5BD),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Z_in(λ/4) = ${"%.1f".format(analysis.zInRe)} + j(${"%.1f".format(analysis.zInIm)}) Ω",
                    color = Color(0xFF81C784),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun SmithChartCanvas(analysis: SmithChartAnalysis) {
    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) * 0.44f

        val gridColor = Color(0xFF2E3846)
        val outerBorderColor = Color(0xFF4B5563)

        // Clip everything to unit circle
        val unitCirclePath = Path().apply {
            addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        }

        clipPath(unitCirclePath) {
            // Horizontal real axis line: -1 to +1
            drawLine(
                color = gridColor,
                start = Offset(cx - radius, cy),
                end = Offset(cx + radius, cy),
                strokeWidth = 1.2.dp.toPx()
            )

            // Constant resistance circles: r in [0.2, 0.5, 1.0, 2.0, 5.0]
            val rValues = listOf(0.2, 0.5, 1.0, 2.0, 5.0)
            for (r in rValues) {
                val circleCenterNormX = r / (r + 1.0)
                val circleRadiusNorm = 1.0 / (r + 1.0)

                val circCx = cx + (circleCenterNormX * radius).toFloat()
                val circR = (circleRadiusNorm * radius).toFloat()

                drawCircle(
                    color = gridColor,
                    radius = circR,
                    center = Offset(circCx, cy),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Constant reactance arcs: x in [+-0.2, +-0.5, +-1.0, +-2.0, +-5.0]
            val xValues = listOf(0.2, 0.5, 1.0, 2.0, 5.0, -0.2, -0.5, -1.0, -2.0, -5.0)
            for (x in xValues) {
                val arcCenterNormY = -1.0 / x
                val arcRadiusNorm = abs(1.0 / x)

                val arcCx = cx + radius
                val arcCy = cy + (arcCenterNormY * radius).toFloat()
                val arcR = (arcRadiusNorm * radius).toFloat()

                drawCircle(
                    color = gridColor,
                    radius = arcR,
                    center = Offset(arcCx, arcCy),
                    style = Stroke(width = 0.9.dp.toPx())
                )
            }

            // Constant VSWR circle: radius = gammaMag * radius
            if (analysis.gammaMag > 0.01 && analysis.gammaMag <= 1.0) {
                val vswrRadius = (analysis.gammaMag * radius).toFloat()
                drawCircle(
                    color = Color(0xFFFFD54F),
                    radius = vswrRadius,
                    center = Offset(cx, cy),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                )
            }

            // Center match point (50 ohm) marker
            drawCircle(color = Color(0xFF6B7280), radius = 2.5.dp.toPx(), center = Offset(cx, cy))

            // Quarter-wave point: opposite side (-gammaRe, -gammaIm)
            val qx = cx - (analysis.gammaRe * radius).toFloat()
            val qy = cy + (analysis.gammaIm * radius).toFloat()
            drawCircle(
                color = Color(0xFF81C784),
                radius = 4.dp.toPx(),
                center = Offset(qx, qy)
            )

            // Load point: (gammaRe, -gammaIm) in screen coordinates
            val lx = cx + (analysis.gammaRe * radius).toFloat()
            val ly = cy - (analysis.gammaIm * radius).toFloat()

            // Line from center to load
            drawLine(
                color = Color(0xFFFFB300),
                start = Offset(cx, cy),
                end = Offset(lx, ly),
                strokeWidth = 1.2.dp.toPx()
            )

            // Marker for Load
            drawCircle(
                color = Color(0xFFFFB300),
                radius = 5.5.dp.toPx(),
                center = Offset(lx, ly)
            )
            drawCircle(
                color = Color(0xFF121417),
                radius = 2.dp.toPx(),
                center = Offset(lx, ly)
            )
        }

        // Draw outer boundary unit circle
        drawCircle(
            color = outerBorderColor,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
