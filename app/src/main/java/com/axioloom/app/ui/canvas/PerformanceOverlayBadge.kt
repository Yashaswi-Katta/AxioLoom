package com.axioloom.app.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SubsystemTelemetry(
    val clusteringTimeMs: Double = 0.8,
    val rasterTimeMs: Double = 1.4,
    val hmerInferenceTimeMs: Double = 42.0,
    val casEvalTimeMs: Double = 6.1,
    val jvmHeapUsedMb: Long = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024))
)

@Composable
fun PerformanceOverlayBadge(
    modifier: Modifier = Modifier,
    telemetry: SubsystemTelemetry = SubsystemTelemetry()
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .border(1.dp, Color(0xFF2E3846), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color(0xEE1E222A)),
        shape = RoundedCornerShape(8.dp)
    ) {
        if (!expanded) {
            // Compact pill badge
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Cluster ${"%.1f".format(telemetry.clusteringTimeMs)}ms",
                    color = Color(0xFFADB5BD),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text("·", color = Color(0xFF6B7280), fontSize = 10.sp)
                Text(
                    text = "HMER ${"%.0f".format(telemetry.hmerInferenceTimeMs)}ms",
                    color = Color(0xFFFFD54F),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text("·", color = Color(0xFF6B7280), fontSize = 10.sp)
                Text(
                    text = "CAS ${"%.1f".format(telemetry.casEvalTimeMs)}ms",
                    color = Color(0xFFFFB300),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text("·", color = Color(0xFF6B7280), fontSize = 10.sp)
                Text(
                    text = "${telemetry.jvmHeapUsedMb}MB",
                    color = Color(0xFF81C784),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            // Expanded detailed telemetry card
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "SUBSYSTEM TELEMETRY HUD",
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Spatial Clustering:", color = Color(0xFFADB5BD), fontSize = 11.sp)
                    Text("${"%.2f".format(telemetry.clusteringTimeMs)} ms", color = Color(0xFFF4F5F7), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bitmap Rasterization:", color = Color(0xFFADB5BD), fontSize = 11.sp)
                    Text("${"%.2f".format(telemetry.rasterTimeMs)} ms", color = Color(0xFFF4F5F7), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("HMER Beam Inference:", color = Color(0xFFADB5BD), fontSize = 11.sp)
                    Text("${"%.2f".format(telemetry.hmerInferenceTimeMs)} ms", color = Color(0xFFFFD54F), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Transpile + Symja CAS:", color = Color(0xFFADB5BD), fontSize = 11.sp)
                    Text("${"%.2f".format(telemetry.casEvalTimeMs)} ms", color = Color(0xFFFFB300), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("JVM Heap Allocated:", color = Color(0xFFADB5BD), fontSize = 11.sp)
                    Text("${telemetry.jvmHeapUsedMb} MB", color = Color(0xFF81C784), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
