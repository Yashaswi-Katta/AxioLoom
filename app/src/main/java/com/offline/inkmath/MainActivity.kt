package com.offline.inkmath

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.offline.inkmath.ui.InkMathViewModel
import com.offline.inkmath.ui.canvas.MathWorkspaceScreen
import com.offline.inkmath.ui.theme.InkMathTheme

/** Offline-first launcher for the ink workspace. */
class MainActivity : ComponentActivity() {
    private lateinit var inkMathViewModel: InkMathViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(18, 20, 23)
        window.navigationBarColor = android.graphics.Color.rgb(18, 20, 23)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        inkMathViewModel = ViewModelProvider(this)[InkMathViewModel::class.java]
        setContent {
            InkMathTheme {
                MathWorkspaceScreen(viewModel = inkMathViewModel)
            }
        }
    }
}
