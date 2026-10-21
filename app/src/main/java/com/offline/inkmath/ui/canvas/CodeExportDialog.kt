package com.offline.inkmath.ui.canvas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.inkmath.domain.codegen.BilinearDiscretizer
import com.offline.inkmath.domain.codegen.CPrecision
import com.offline.inkmath.domain.codegen.EmbeddedCGenerator
import com.offline.inkmath.domain.codegen.GeneratedCCode
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.MathIntent

enum class CodeExportTab {
    HEADER,
    SOURCE,
    ARDUINO
}

@Composable
fun CodeExportDialog(
    block: EquationBlock,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var precision by remember { mutableStateOf(CPrecision.FLOAT) }
    var selectedFs by remember { mutableStateOf(1000.0) }
    var selectedTab by remember { mutableStateOf(CodeExportTab.SOURCE) }

    val intent = block.intent
    val generatedCode: GeneratedCCode? = remember(intent, precision, selectedFs) {
        if (intent == null) null
        else {
            val filter = BilinearDiscretizer.discretize(intent, selectedFs)
            if (filter != null) {
                EmbeddedCGenerator.generateFilterDriver(filter, precision = precision)
            } else {
                val expr = when (intent) {
                    is MathIntent.EvaluationQuery -> intent.expression
                    is MathIntent.Assignment -> intent.rhs
                    is MathIntent.FunctionDef -> intent.body
                    else -> null
                }
                if (expr != null) {
                    EmbeddedCGenerator.generateMathFunction(expr, precision = precision)
                } else null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EMBEDDED C / ESP32 FIRMWARE",
                    color = Color(0xFFFFB300),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (generatedCode != null) "C99 Ready" else "No Code",
                    color = Color(0xFFADB5BD),
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                // Controls bar: Sampling Rate and Precision
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(100.0, 1000.0, 10000.0, 48000.0).forEach { fs ->
                            val label = when (fs) {
                                100.0 -> "100Hz"
                                1000.0 -> "1kHz"
                                10000.0 -> "10kHz"
                                else -> "48kHz"
                            }
                            FilterChip(
                                selected = selectedFs == fs,
                                onClick = { selectedFs = fs },
                                label = { Text(label, fontSize = 10.sp) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = precision == CPrecision.FLOAT,
                            onClick = { precision = CPrecision.FLOAT },
                            label = { Text("float", fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = precision == CPrecision.DOUBLE,
                            onClick = { precision = CPrecision.DOUBLE },
                            label = { Text("double", fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Tabs: Header, Source, Arduino
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = Color(0xFF191D22),
                    contentColor = Color(0xFFFFB300)
                ) {
                    Tab(
                        selected = selectedTab == CodeExportTab.HEADER,
                        onClick = { selectedTab = CodeExportTab.HEADER },
                        text = { Text(".h Header", fontSize = 11.sp) }
                    )
                    Tab(
                        selected = selectedTab == CodeExportTab.SOURCE,
                        onClick = { selectedTab = CodeExportTab.SOURCE },
                        text = { Text(".c Source", fontSize = 11.sp) }
                    )
                    Tab(
                        selected = selectedTab == CodeExportTab.ARDUINO,
                        onClick = { selectedTab = CodeExportTab.ARDUINO },
                        text = { Text("Arduino .ino", fontSize = 11.sp) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Code Display Box
                val activeCode = when (selectedTab) {
                    CodeExportTab.HEADER -> generatedCode?.headerCode ?: "// No header generated"
                    CodeExportTab.SOURCE -> generatedCode?.sourceCode ?: "// No source generated"
                    CodeExportTab.ARDUINO -> generatedCode?.arduinoSnippet ?: "// No snippet generated"
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color(0xFF252A31), RoundedCornerShape(8.dp)),
                    color = Color(0xFF121417),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = activeCode,
                            color = Color(0xFFF4F5F7),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val codeToCopy = when (selectedTab) {
                        CodeExportTab.HEADER -> generatedCode?.headerCode
                        CodeExportTab.SOURCE -> generatedCode?.sourceCode
                        CodeExportTab.ARDUINO -> generatedCode?.arduinoSnippet
                    } ?: ""
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("C99 Firmware", codeToCopy))
                    Toast.makeText(context, "Copied C code to clipboard", Toast.LENGTH_SHORT).show()
                },
                enabled = generatedCode != null
            ) {
                Text("Copy Code", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFFADB5BD))
            }
        }
    )
}
