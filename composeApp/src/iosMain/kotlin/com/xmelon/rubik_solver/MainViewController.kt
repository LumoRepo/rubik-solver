package com.xmelon.rubik_solver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.xmelon.rubik_solver.ui.HomeScreen
import com.xmelon.rubik_solver.ui.ScreenProgressBar
import com.xmelon.rubik_solver.ui.ProgressPhase

fun MainViewController() = ComposeUIViewController {
    MaterialTheme {
        // iOS entry point — currently shows Home screen only.
        // Full scan/solve flow requires AppViewModel + CubeFrameAnalyzer iOS port (Task 5 / Task 8).
        HomeScreen(onScanClicked = {
            // Scan not yet available on iOS — solver stub in place (Task 5)
        })
    }
}
