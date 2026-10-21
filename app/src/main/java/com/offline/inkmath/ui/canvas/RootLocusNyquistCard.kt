package com.offline.inkmath.ui.canvas

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
import com.offline.inkmath.domain.cas.ComplexNumber
import com.offline.inkmath.domain.cas.RootLocusAnalysis
import com.offline.inkmath.domain.cas.RootLocusEngine
import com.offline.inkmath.domain.cas.TransferFunctionAnalysis
import kotlin.math.*

@Composable
fun RootLocusNyquistCard(
    analysis: TransferFunctionAnalysis,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = Root Locus, 1 = Nyquist
    var gainK by remember { mutableStateOf(1.0f) }

    val rlAnalysis: RootLocusAnalysis = remember(analysis) {
        RootLocusEngine.analyze(analysis.numeratorCoeffs, analysis.denominatorCoeffs)
    }

    // Closed-loop poles at current interactive gain K
    val currentPoles: List<ComplexNumber> = remember(rlAnalysis, gainK) {
        // Interpolate or find closest branch
        rlAnalysis.branches.minByOrNull { abs(it.gainK - gainK.toDouble()) }?.poles ?: emptyList()
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
                        text = "CONTROL SYSTEMS: ROOT-LOCUS & NYQUIST",
                        color = Color(0xFFFFB300),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${analysis.functionName}(s)",
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

            // Tabs: Root-Locus vs Nyquist
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF191D22),
                contentColor = Color(0xFFFFB300)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Root-Locus (s-plane)", fontSize = 11.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Nyquist Contour", fontSize = 11.sp) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Canvas Plot Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color(0xFF14171B), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF252A31), RoundedCornerShape(8.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    if (selectedTab == 0) {
                        // ROOT-LOCUS S-PLANE PLOT
                        // Auto-scale s-plane based on pole/zero bounds
                        val allPoints = mutableListOf<ComplexNumber>()
                        allPoints.addAll(rlAnalysis.openLoopPoles)
                        allPoints.addAll(rlAnalysis.openLoopZeros)
                        for (b in rlAnalysis.branches) allPoints.addAll(b.poles)

                        val maxRe = max(1.0, allPoints.maxOfOrNull { abs(it.re) } ?: 2.0)
                        val maxIm = max(1.0, allPoints.maxOfOrNull { abs(it.im) } ?: 2.0)
                        val span = max(maxRe, maxIm) * 1.25

                        fun sToScreen(c: ComplexNumber): Offset {
                            val px = (w / 2f + (c.re / span) * (w / 2f)).toFloat()
                            val py = (h / 2f - (c.im / span) * (h / 2f)).toFloat()
                            return Offset(px, py)
                        }

                        // Draw Axes
                        val cx = w / 2f
                        val cy = h / 2f
                        drawLine(Color(0xFF252A31), Offset(0f, cy), Offset(w, cy), strokeWidth = 1.5f) // Real axis
                        drawLine(Color(0xFF252A31), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f) // Imaginary jw axis

                        // Draw locus branches
                        for (b in rlAnalysis.branches) {
                            for (p in b.poles) {
                                val pt = sToScreen(p)
                                drawCircle(Color(0x88FFB300), radius = 1.5f, center = pt)
                            }
                        }

                        // Draw Open-Loop Zeros 'o'
                        for (z in rlAnalysis.openLoopZeros) {
                            val pt = sToScreen(z)
                            drawCircle(Color(0xFF64B5F6), radius = 5f, center = pt, style = Stroke(width = 2f))
                        }

                        // Draw Open-Loop Poles 'x'
                        for (p in rlAnalysis.openLoopPoles) {
                            val pt = sToScreen(p)
                            val s = 4.5f
                            drawLine(Color(0xFFDC143C), Offset(pt.x - s, pt.y - s), Offset(pt.x + s, pt.y + s), strokeWidth = 2.5f)
                            drawLine(Color(0xFFDC143C), Offset(pt.x - s, pt.y + s), Offset(pt.x + s, pt.y - s), strokeWidth = 2.5f)
                        }

                        // Highlight Current Closed-Loop Poles at Gain K (Gold)
                        for (cp in currentPoles) {
                            val pt = sToScreen(cp)
                            drawCircle(Color(0xFFFFD54F), radius = 5f, center = pt)
                        }
                    } else {
                        // NYQUIST POLAR CONTOUR PLOT G(jw)
                        val points = rlAnalysis.nyquistPoints
                        val maxR = max(1.5, points.maxOfOrNull { hypot(it.re, it.im) } ?: 2.0)
                        val span = min(5.0, maxR) * 1.2

                        fun gToScreen(re: Double, im: Double): Offset {
                            val px = (w / 2f + (re / span) * (w / 2f)).toFloat()
                            val py = (h / 2f - (im / span) * (h / 2f)).toFloat()
                            return Offset(px, py)
                        }

                        val cx = w / 2f
                        val cy = h / 2f
                        drawLine(Color(0xFF252A31), Offset(0f, cy), Offset(w, cy), strokeWidth = 1.5f)
                        drawLine(Color(0xFF252A31), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)

                        // Draw Unit Circle
                        val unitRadius = ((1.0 / span) * (w / 2f)).toFloat()
                        drawCircle(Color(0xFF2E3846), radius = unitRadius, center = Offset(cx, cy), style = Stroke(width = 1f))

                        // Critical point (-1, 0)
                        val critPt = gToScreen(-1.0, 0.0)
                        val cs = 6f
                        drawLine(Color(0xFFDC143C), Offset(critPt.x - cs, critPt.y), Offset(critPt.x + cs, critPt.y), strokeWidth = 2.5f)
                        drawLine(Color(0xFFDC143C), Offset(critPt.x, critPt.y - cs), Offset(critPt.x, critPt.y + cs), strokeWidth = 2.5f)

                        // Contour path (positive frequencies in Amber, negative in muted Gold)
                        if (points.isNotEmpty()) {
                            val pathPos = Path()
                            val pathNeg = Path()
                            for (i in points.indices) {
                                val ptPos = gToScreen(points[i].re, points[i].im)
                                val ptNeg = gToScreen(points[i].re, -points[i].im)
                                if (i == 0) {
                                    pathPos.moveTo(ptPos.x, ptPos.y)
                                    pathNeg.moveTo(ptNeg.x, ptNeg.y)
                                } else {
                                    pathPos.lineTo(ptPos.x, ptPos.y)
                                    pathNeg.lineTo(ptNeg.x, ptNeg.y)
                                }
                            }
                            drawPath(pathPos, Color(0xFFFFB300), style = Stroke(width = 2.5f))
                            drawPath(pathNeg, Color(0x66FFD54F), style = Stroke(width = 1.5f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (selectedTab == 0) {
                // Root Locus Gain Slider and Pole Details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gain K: ${"%.2f".format(gainK)}",
                        fontSize = 11.sp,
                        color = Color(0xFFFFD54F),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(95.dp)
                    )
                    Slider(
                        value = gainK,
                        onValueChange = { gainK = it },
                        valueRange = 0.01f..25.0f,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Damping & Natural Frequency of dominant pole
                val dominantPole = currentPoles.maxByOrNull { it.re }
                if (dominantPole != null) {
                    val wn = dominantPole.magnitude()
                    val zeta = if (wn > 1e-6) (-dominantPole.re / wn).coerceIn(-1.0, 1.0) else 0.0
                    Text(
                        text = "Dominant Pole: ${dominantPole.formatted()}  ·  ζ = ${"%.3f".format(zeta)}  ·  ωn = ${"%.2f".format(wn)} rad/s",
                        fontSize = 11.sp,
                        color = Color(0xFFF4F5F7),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Stability Margins Breakdown
            val m = rlAnalysis.margins
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "GM: ${m.gainMarginDb?.let { "${"%.1f".format(it)} dB" } ?: "∞"}",
                    fontSize = 11.sp,
                    color = Color(0xFFFFB300),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "PM: ${m.phaseMarginDeg?.let { "${"%.1f".format(it)}°" } ?: "∞"}",
                    fontSize = 11.sp,
                    color = Color(0xFFFFD54F),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "K_crit: ${m.criticalGainK?.let { "%.2f".format(it) } ?: "None"}",
                    fontSize = 11.sp,
                    color = if (m.criticalGainK != null) Color(0xFFDC143C) else Color(0xFF64B5F6),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
