package com.axioloom.app.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axioloom.app.domain.dsp.DiscreteSignalEngine
import kotlin.math.max
import kotlin.math.min

enum class StemSignalMode(val label: String) {
    INPUT("x[n]"),
    IMPULSE("h[n]"),
    CONVOLUTION("y[n] = x * h"),
    CIRCULAR("y_circ[n]"),
    SPECTRUM("|X[k]|")
}

@Composable
fun DiscreteStemPlotCard(
    modifier: Modifier = Modifier,
    initialX: List<Double> = listOf(1.0, 2.0, 3.0),
    initialH: List<Double> = listOf(1.0, 1.0),
    onClose: () -> Unit = {}
) {
    var xInputText by remember(initialX) { mutableStateOf(initialX.joinToString(", ")) }
    var hInputText by remember(initialH) { mutableStateOf(initialH.joinToString(", ")) }

    val xSequence = remember(xInputText) {
        xInputText.split(",")
            .mapNotNull { it.trim().toDoubleOrNull() }
            .ifEmpty { listOf(1.0, 2.0, 3.0) }
    }
    val hSequence = remember(hInputText) {
        hInputText.split(",")
            .mapNotNull { it.trim().toDoubleOrNull() }
            .ifEmpty { listOf(1.0, 1.0) }
    }

    val convResult = remember(xSequence, hSequence) {
        DiscreteSignalEngine.convolveWithSteps(xSequence, hSequence)
    }
    val circularConvResult = remember(xSequence, hSequence) {
        DiscreteSignalEngine.circularConvolve(xSequence, hSequence)
    }
    val spectrumResult = remember(xSequence) {
        DiscreteSignalEngine.analyzeSpectrum(xSequence)
    }

    var selectedMode by remember { mutableStateOf(StemSignalMode.CONVOLUTION) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    var showSteps by remember { mutableStateOf(false) }

    val activeSamples: List<Double> = when (selectedMode) {
        StemSignalMode.INPUT -> xSequence
        StemSignalMode.IMPULSE -> hSequence
        StemSignalMode.CONVOLUTION -> convResult.output
        StemSignalMode.CIRCULAR -> circularConvResult
        StemSignalMode.SPECTRUM -> spectrumResult.magnitude
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
                    text = "DISCRETE-TIME DSP & CONVOLUTION",
                    color = Color(0xFFFFB300),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(onClick = onClose) {
                    Text("Close", color = Color(0xFFDC143C))
                }
            }

            // Input editors
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = xInputText,
                    onValueChange = { xInputText = it },
                    label = { Text("x[n]", color = Color(0xFFADB5BD), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF4F5F7),
                        unfocusedTextColor = Color(0xFFF4F5F7),
                        focusedBorderColor = Color(0xFFFFB300),
                        unfocusedBorderColor = Color(0xFF252A31)
                    ),
                    singleLine = true
                )
                OutlinedTextField(
                    value = hInputText,
                    onValueChange = { hInputText = it },
                    label = { Text("h[n]", color = Color(0xFFADB5BD), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF4F5F7),
                        unfocusedTextColor = Color(0xFFF4F5F7),
                        focusedBorderColor = Color(0xFFFFB300),
                        unfocusedBorderColor = Color(0xFF252A31)
                    ),
                    singleLine = true
                )
            }

            // Mode Selector Tabs
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StemSignalMode.values().forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode; hoveredIndex = null },
                        label = { Text(mode.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF252A31),
                            selectedLabelColor = Color(0xFFFFB300),
                            containerColor = Color(0xFF191D22),
                            labelColor = Color(0xFFADB5BD)
                        )
                    )
                }
            }

            // Stem Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color(0xFF16191D), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF252A31), RoundedCornerShape(8.dp))
            ) {
                StemPlotCanvas(
                    samples = activeSamples,
                    hoveredIndex = hoveredIndex,
                    onHover = { hoveredIndex = it }
                )

                // Tooltip badge
                val curHovered = hoveredIndex
                if (curHovered != null && curHovered in activeSamples.indices) {
                    val idx = curHovered
                    val v = activeSamples[idx]
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        color = Color(0xFF252A31),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "n=$idx: $v",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Output readout
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedMode.label} = [${activeSamples.joinToString(", ")}]",
                    color = Color(0xFFF4F5F7),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showSteps = !showSteps }) {
                    Text(if (showSteps) "Hide Steps" else "Show Steps", color = Color(0xFFFFD54F), fontSize = 11.sp)
                }
            }

            // Shift-and-multiply table
            if (showSteps && selectedMode == StemSignalMode.CONVOLUTION) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(Color(0xFF191D22), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "CONVOLUTION SUM TABLE: y[n] = \\sum_k x[k] h[n - k]",
                        color = Color(0xFFFFB300),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    convResult.steps.forEach { step ->
                        Text(
                            text = "y[${step.n}] = ${step.equation}",
                            color = Color(0xFFADB5BD),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StemPlotCanvas(
    samples: List<Double>,
    hoveredIndex: Int?,
    onHover: (Int?) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(samples) {
                detectTapGestures(
                    onPress = { offset ->
                        val n = samples.size
                        if (n > 0) {
                            val padX = 24.dp.toPx()
                            val plotW = size.width - 2 * padX
                            val stepX = if (n > 1) plotW / (n - 1) else plotW / 2
                            val clickedIdx = ((offset.x - padX + stepX / 2) / stepX).toInt()
                            if (clickedIdx in samples.indices) onHover(clickedIdx) else onHover(null)
                        }
                    }
                )
            }
            .pointerInput(samples) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val n = samples.size
                        if (n > 0) {
                            val padX = 24.dp.toPx()
                            val plotW = size.width - 2 * padX
                            val stepX = if (n > 1) plotW / (n - 1) else plotW / 2
                            val idx = ((offset.x - padX + stepX / 2) / stepX).toInt()
                            if (idx in samples.indices) onHover(idx)
                        }
                    },
                    onDrag = { change, _ ->
                        val n = samples.size
                        if (n > 0) {
                            val padX = 24.dp.toPx()
                            val plotW = size.width - 2 * padX
                            val stepX = if (n > 1) plotW / (n - 1) else plotW / 2
                            val idx = ((change.position.x - padX + stepX / 2) / stepX).toInt()
                            if (idx in samples.indices) onHover(idx)
                        }
                    },
                    onDragEnd = { onHover(null) }
                )
            }
    ) {
        val n = samples.size
        if (n == 0) return@Canvas

        val padX = 24.dp.toPx()
        val padY = 24.dp.toPx()
        val plotW = size.width - 2 * padX
        val plotH = size.height - 2 * padY

        val minVal = min(0.0, samples.minOrNull() ?: 0.0)
        val maxVal = max(1.0, samples.maxOrNull() ?: 1.0)
        val span = max(1e-4, maxVal - minVal)

        // Baseline Y
        val baselineY = padY + ((maxVal - 0.0) / span * plotH).toFloat()

        // Draw baseline y = 0
        drawLine(
            color = Color(0xFF374151),
            start = Offset(padX, baselineY),
            end = Offset(size.width - padX, baselineY),
            strokeWidth = 1.5.dp.toPx()
        )

        val stepX = if (n > 1) plotW / (n - 1) else plotW / 2

        for (i in samples.indices) {
            val v = samples[i]
            val x = if (n > 1) padX + i * stepX else padX + plotW / 2
            val y = padY + ((maxVal - v) / span * plotH).toFloat()

            val isHovered = (hoveredIndex == i)
            val stemColor = if (isHovered) Color(0xFFFFD54F) else Color(0xFFFFB300)
            val markerColor = if (isHovered) Color(0xFFFFD54F) else Color(0xFFFFB300)

            // Vertical stem line from baseline to y
            drawLine(
                color = stemColor,
                start = Offset(x, baselineY),
                end = Offset(x, y),
                strokeWidth = if (isHovered) 3.dp.toPx() else 2.dp.toPx()
            )

            // Lollipop head
            drawCircle(
                color = markerColor,
                radius = if (isHovered) 6.dp.toPx() else 4.5.dp.toPx(),
                center = Offset(x, y)
            )

            // Hover indicator cursor
            if (isHovered) {
                drawLine(
                    color = Color(0xFF6B7280),
                    start = Offset(x, padY),
                    end = Offset(x, size.height - padY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )
            }
        }
    }
}
