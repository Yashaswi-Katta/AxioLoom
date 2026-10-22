package com.offline.inkmath

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner as PlatformLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner as ComposeLifecycleOwner
import com.offline.inkmath.ui.InkMathViewModel
import com.offline.inkmath.ui.canvas.MathWorkspaceScreen
import com.offline.inkmath.ui.theme.InkMathTheme
import java.io.File

/** Offline-first launcher for the ink workspace with fault-tolerant startup & diagnostics. */
class MainActivity : ComponentActivity() {
    private var inkMathViewModel: InkMathViewModel? = null
    private var startupError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            window.statusBarColor = android.graphics.Color.rgb(18, 20, 23)
            window.navigationBarColor = android.graphics.Color.rgb(18, 20, 23)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        try {
            inkMathViewModel = ViewModelProvider(this)[InkMathViewModel::class.java]
        } catch (t: Throwable) {
            startupError = "ViewModel initialization failed: ${t::class.java.name}: ${t.message ?: "No message"}\n" +
                t.stackTrace.take(25).joinToString("\n") { "  at $it" }
            Log.e("MainActivity", "Failed to initialize InkMathViewModel", t)
        }

        setContent {
            CompositionLocalProvider(
                ComposeLifecycleOwner provides this,
                PlatformLifecycleOwner provides this
            ) {
                InkMathTheme {
                    val context = LocalContext.current
                    var diagnosticError by remember {
                        mutableStateOf(InkMathApp.getSavedCrash(context) ?: startupError)
                    }

                    if (diagnosticError != null) {
                        DiagnosticsScreen(
                            errorText = diagnosticError!!,
                            onDismiss = {
                                InkMathApp.clearSavedCrash(context)
                                diagnosticError = null
                            },
                            onResetData = {
                                runCatching {
                                    context.getSharedPreferences("inkmath_crash_log", Context.MODE_PRIVATE)
                                        .edit().clear().commit()
                                    File(context.filesDir, "notebooks").deleteRecursively()
                                    File(context.filesDir, "workspace_v1.json").delete()
                                }
                                InkMathApp.clearSavedCrash(context)
                                diagnosticError = null
                                recreate()
                            }
                        )
                    } else if (inkMathViewModel != null) {
                        MathWorkspaceScreen(viewModel = inkMathViewModel!!)
                    } else {
                        DiagnosticsScreen(
                            errorText = startupError ?: "Could not initialize InkMath workspace.",
                            onDismiss = { },
                            onResetData = {
                                runCatching {
                                    File(context.filesDir, "notebooks").deleteRecursively()
                                }
                                recreate()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    errorText: String,
    onDismiss: () -> Unit,
    onResetData: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121417)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "INKMATH STARTUP DIAGNOSTICS",
                color = Color(0xFFDC143C),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "A startup error or previous crash was caught. You can review the details below, copy the log, or reset data and continue.",
                color = Color(0xFFF4F5F7),
                style = MaterialTheme.typography.bodyMedium
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 340.dp),
                color = Color(0xFF191D22),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF252A31))
            ) {
                Box(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = errorText,
                        color = Color(0xFFADB5BD),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("InkMath Crash Log", errorText)
                        clipboard?.setPrimaryClip(clip)
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF252A31),
                        contentColor = Color(0xFFF4F5F7)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (copied) "Copied!" else "Copy Log")
                }
                Button(
                    onClick = onResetData,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC143C),
                        contentColor = Color(0xFFF4F5F7)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Reset Data")
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFB300),
                    contentColor = Color(0xFF121417)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Continue to Workspace")
            }
        }
    }
}
