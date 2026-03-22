package com.xmelon.rubik_solver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xmelon.rubik_solver.vision.FrameAnalyzer

@Composable
actual fun CameraPreviewLayer(
    analyzer: FrameAnalyzer,
    debugMode: Boolean,
    onDebugToggle: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Camera not yet available on iOS",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Surface(
            onClick    = onDebugToggle,
            modifier   = Modifier.align(Alignment.TopEnd).padding(8.dp),
            shape      = RoundedCornerShape(8.dp),
            color      = if (debugMode) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            tonalElevation = 2.dp
        ) {
            Text(
                "DBG",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style    = MaterialTheme.typography.labelSmall,
                color    = if (debugMode) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
