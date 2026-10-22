package com.axioloom.app.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axioloom.app.domain.logic.KMapCell
import com.axioloom.app.domain.logic.KarnaughMinimizer
import com.axioloom.app.domain.logic.MinimizationResult

/**
 * Interactive Karnaugh Map (K-Map) Card.
 * Renders 2, 3, and 4-variable Gray-code grids, supports interactive cell tapping (0 -> 1 -> X -> 0),
 * displays color-coded Prime Implicant loops with torus wrap-arounds, and updates minimal SOP/POS expressions in real time.
 */
@Composable
fun KarnaughMapCard(
    onDismiss: () -> Unit,
    initialNumVars: Int = 4,
    initialMinterms: Set<Int> = setOf(0, 1, 2, 5, 8, 9, 10),
    initialDontCares: Set<Int> = emptySet(),
    modifier: Modifier = Modifier
) {
    var numVars by remember { mutableIntStateOf(initialNumVars) }
    var minterms by remember { mutableStateOf(initialMinterms) }
    var dontCares by remember { mutableStateOf(initialDontCares) }

    val minimizationResult by remember(numVars, minterms, dontCares) {
        derivedStateOf {
            try {
                KarnaughMinimizer.minimize(minterms, dontCares, numVars)
            } catch (_: Exception) {
                null
            }
        }
    }

    val grid by remember(numVars, minterms, dontCares) {
        derivedStateOf {
            KarnaughMinimizer.buildGrid(numVars, minterms, dontCares)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF191D22),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            // Header: Title, Variable chips, Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Karnaugh Map (${numVars} Vars)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(2, 3, 4).forEach { vCount ->
                            FilterChip(
                                selected = numVars == vCount,
                                onClick = {
                                    val maxTerm = (1 shl vCount) - 1
                                    minterms = minterms.filter { it <= maxTerm }.toSet()
                                    dontCares = dontCares.filter { it <= maxTerm }.toSet()
                                    numVars = vCount
                                },
                                label = { Text("${vCount}V", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Text("\u2715", color = Color(0xFF88929E), fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Interactive Gray-code Grid
            val rowBits = if (numVars == 4) 2 else 1
            val colBits = if (numVars == 2) 1 else 2
            val rowGray = KarnaughMinimizer.getGrayCodeOrder(rowBits)
            val colGray = KarnaughMinimizer.getGrayCodeOrder(colBits)

            val rowLabels = if (numVars == 4) "AB \\ CD" else if (numVars == 3) "A \\ BC" else "A \\ B"

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121417), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(rowLabels, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD54F))
                Spacer(Modifier.height(4.dp))

                // Column Headers
                Row(
                    modifier = Modifier.padding(start = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    colGray.forEach { (_, colLabel) ->
                        Box(
                            modifier = Modifier.width(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(colLabel, style = MaterialTheme.typography.labelSmall, color = Color(0xFFC0C7D0), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Grid Rows
                grid.forEachIndexed { rIdx, rowCells ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        // Row Header
                        Box(
                            modifier = Modifier.width(36.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(rowGray[rIdx].second, style = MaterialTheme.typography.labelSmall, color = Color(0xFFC0C7D0), fontWeight = FontWeight.Bold)
                        }

                        // Cells
                        rowCells.forEach { cell ->
                            val cellColor = when (cell.value) {
                                "1" -> Color(0xFFFFB300)
                                "X" -> Color(0xFF00E5FF)
                                else -> Color(0xFF495057)
                            }
                            val cellBg = when (cell.value) {
                                "1" -> Color(0xFF2A2318)
                                "X" -> Color(0xFF10282E)
                                else -> Color(0xFF1E232A)
                            }

                            Surface(
                                modifier = Modifier
                                    .size(48.dp)
                                    .pointerInput(cell.minterm) {
                                        detectTapGestures {
                                            // Cycle: 0 -> 1 -> X -> 0
                                            when (cell.value) {
                                                "0" -> {
                                                    minterms = minterms + cell.minterm
                                                    dontCares = dontCares - cell.minterm
                                                }
                                                "1" -> {
                                                    minterms = minterms - cell.minterm
                                                    dontCares = dontCares + cell.minterm
                                                }
                                                else -> {
                                                    minterms = minterms - cell.minterm
                                                    dontCares = dontCares - cell.minterm
                                                }
                                            }
                                        }
                                    },
                                shape = RoundedCornerShape(6.dp),
                                color = cellBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, cellColor.copy(alpha = 0.5f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = cell.value,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = cellColor
                                    )
                                    Text(
                                        text = "${cell.minterm}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = Color(0xFF6C757D),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Minimal Equations Display
            minimizationResult?.let { res ->
                Surface(
                    color = Color(0xFF1E232A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Minimal Sum of Products (SOP):", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "F = ${res.minimalSopLatex}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB300)
                        )

                        Spacer(Modifier.height(6.dp))
                        Text("Minimal Product of Sums (POS):", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC0C7D0))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "F = ${res.minimalPosLatex}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFF4F5F7)
                        )

                        if (res.loops.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Prime Implicant Loops:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF88929E))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                res.loops.forEach { loop ->
                                    Surface(
                                        color = Color(loop.colorHex).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(loop.colorHex))
                                    ) {
                                        Text(
                                            text = loop.label,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(loop.colorHex)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
