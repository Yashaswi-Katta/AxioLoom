package com.offline.inkmath.ui.canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.inkmath.domain.circuit.MnaResult
import com.offline.inkmath.domain.circuit.SymbolicMnaSolver
import com.offline.inkmath.ui.InkMathViewModel
import kotlinx.coroutines.launch

/**
 * Interactive Circuit Solver Card for Modified Nodal Analysis (MNA).
 * Formulates and renders G * x = i matrix equations and displays solved node voltages,
 * branch currents, and Thevenin/Norton equivalents.
 */
@Composable
fun CircuitSolverSheet(
    solver: SymbolicMnaSolver,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var netlistText by remember {
        mutableStateOf(
            "V1 1 0 10\nR1 1 2 1k\nR2 2 0 1k"
        )
    }
    var portNodeA by remember { mutableStateOf("2") }
    var portNodeB by remember { mutableStateOf("0") }
    var mnaResult by remember { mutableStateOf<MnaResult?>(null) }
    var isSolving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun runMna() {
        coroutineScope.launch {
            try {
                isSolving = true
                errorMessage = null
                val elements = solver.parseNetlist(netlistText)
                if (elements.isEmpty()) {
                    errorMessage = "No valid circuit elements in netlist"
                    isSolving = false
                    return@launch
                }
                val nA = portNodeA.toIntOrNull() ?: 2
                val nB = portNodeB.toIntOrNull() ?: 0
                val result = solver.solve(elements, theveninPort = nA to nB)
                mnaResult = result
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to solve MNA circuit"
            } finally {
                isSolving = false
            }
        }
    }

    // Auto-solve initial default circuit
    LaunchedEffect(Unit) {
        runMna()
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "MNA Circuit Solver",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )
                    Surface(
                        color = Color(0xFF252A31),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "KCL / KVL Matrix",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC0C7D0)
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Text("\u2715", color = Color(0xFF88929E), fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Netlist Input Field
            OutlinedTextField(
                value = netlistText,
                onValueChange = { netlistText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Circuit Netlist (R, C, L, V, I, G)") },
                placeholder = { Text("V1 1 0 10\nR1 1 2 1k\nC1 2 0 10u") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = Color.White),
                maxLines = 5
            )

            Spacer(Modifier.height(8.dp))

            // Presets and Thevenin Port Selection Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Presets:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF88929E))
                FilterChip(
                    selected = netlistText.contains("1k"),
                    onClick = {
                        netlistText = "V1 1 0 10\nR1 1 2 1k\nR2 2 0 1k"
                        portNodeA = "2"; portNodeB = "0"
                        runMna()
                    },
                    label = { Text("Voltage Divider", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = netlistText.contains("s*C") || netlistText.contains("1/(1+s*R*C)"),
                    onClick = {
                        netlistText = "V_in 1 0 V_in\nR 1 2 R\nC 2 0 C"
                        portNodeA = "2"; portNodeB = "0"
                        runMna()
                    },
                    label = { Text("RC Low-Pass (Symbolic)", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = netlistText.contains("RLC"),
                    onClick = {
                        netlistText = "V1 1 0 1\nR1 1 2 50\nL1 2 3 100u\nC1 3 0 10n"
                        portNodeA = "3"; portNodeB = "0"
                        runMna()
                    },
                    label = { Text("RLC Band-Pass", style = MaterialTheme.typography.labelSmall) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Thevenin port inputs and Solve button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Port (Thevenin):", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC0C7D0))
                    OutlinedTextField(
                        value = portNodeA,
                        onValueChange = { portNodeA = it },
                        modifier = Modifier.width(50.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFFFB300))
                    )
                    Text("to", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC0C7D0))
                    OutlinedTextField(
                        value = portNodeB,
                        onValueChange = { portNodeB = it },
                        modifier = Modifier.width(50.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFFFB300))
                    )
                }

                Button(
                    onClick = { runMna() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF121417))
                ) {
                    Text(if (isSolving) "Solving..." else "Solve MNA", fontWeight = FontWeight.Bold)
                }
            }

            errorMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            // MNA Results Display
            mnaResult?.let { res ->
                Spacer(Modifier.height(10.dp))
                Divider(color = Color(0xFF252A31))
                Spacer(Modifier.height(8.dp))

                Text("Solved Node Voltages:", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    res.nodeVoltages.forEach { (node, v) ->
                        Surface(
                            color = Color(0xFF252A31),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "V_$node = $v",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFFB300)
                            )
                        }
                    }
                }

                if (res.sourceCurrents.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Source Currents:", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC0C7D0))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        res.sourceCurrents.forEach { (src, cur) ->
                            Text(
                                "I($src) = $cur",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            )
                        }
                    }
                }

                // Thevenin / Norton Equivalents
                if (res.theveninVoltage != null && res.theveninImpedance != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFF1E232A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Thevenin Equivalent (Nodes $portNodeA \u2194 $portNodeB):", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text("V_th = ${res.theveninVoltage}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = Color(0xFFFFB300))
                            Text("Z_th = ${res.theveninImpedance}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = Color(0xFFF4F5F7))
                        }
                    }
                }
            }
        }
    }
}
