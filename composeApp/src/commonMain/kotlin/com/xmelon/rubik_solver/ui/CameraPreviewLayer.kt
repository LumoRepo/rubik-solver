package com.xmelon.rubik_solver.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xmelon.rubik_solver.vision.FrameAnalyzer

/**
 * Platform-specific camera preview layer.
 * Android: full CameraX preview with debug overlay.
 * iOS: placeholder until native camera integration is implemented.
 */
@Composable
expect fun CameraPreviewLayer(
    analyzer: FrameAnalyzer,
    debugMode: Boolean,
    onDebugToggle: () -> Unit,
    modifier: Modifier = Modifier
)
