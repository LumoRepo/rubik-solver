package com.xmelon.rubik_solver.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xmelon.rubik_solver.generated.resources.Res
import com.xmelon.rubik_solver.generated.resources.progress_idle
import com.xmelon.rubik_solver.generated.resources.progress_solved
import com.xmelon.rubik_solver.generated.resources.progress_solving
import com.xmelon.rubik_solver.generated.resources.progress_solving_initial
import com.xmelon.rubik_solver.generated.resources.progress_solving_move
import com.xmelon.rubik_solver.generated.resources.progress_scanning
import com.xmelon.rubik_solver.model.Face
import com.xmelon.rubik_solver.vision.ScanOrchestrator
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

enum class ProgressPhase { IDLE, SCANNING, SOLVING }

@Composable
fun ScreenProgressBar(
    progress: Float,
    phase: ProgressPhase,
    scanningFace: Face? = null,
    currentStep: Int = 0,
    totalSteps: Int = 0,
    modifier: Modifier = Modifier
) {
    val barColor = when (phase) {
        ProgressPhase.IDLE     -> MaterialTheme.colorScheme.outlineVariant
        ProgressPhase.SCANNING -> MaterialTheme.colorScheme.primary
        ProgressPhase.SOLVING  -> MaterialTheme.colorScheme.secondary
    }

    val label = when (phase) {
        ProgressPhase.IDLE -> stringResource(Res.string.progress_idle)
        ProgressPhase.SCANNING -> {
            val idx = (scanningFace?.let { ScanOrchestrator.SCAN_ORDER.indexOf(it) + 1 } ?: 1).coerceAtLeast(1)
            val name = scanningFace?.let { stringResource(it.colorNameRes()) } ?: "—"
            stringResource(Res.string.progress_scanning, name, idx)
        }
        ProgressPhase.SOLVING -> when {
            totalSteps == 0           -> stringResource(Res.string.progress_solving)
            currentStep == 0          -> stringResource(Res.string.progress_solving_initial)
            currentStep >= totalSteps -> stringResource(Res.string.progress_solved)
            else -> stringResource(Res.string.progress_solving_move, currentStep, totalSteps - 1)
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
