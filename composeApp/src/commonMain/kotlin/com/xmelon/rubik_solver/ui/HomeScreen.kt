package com.xmelon.rubik_solver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xmelon.rubik_solver.generated.resources.Res
import com.xmelon.rubik_solver.generated.resources.home_button_scan
import com.xmelon.rubik_solver.generated.resources.home_status_error
import com.xmelon.rubik_solver.generated.resources.home_status_loading
import com.xmelon.rubik_solver.generated.resources.home_status_ready
import com.xmelon.rubik_solver.generated.resources.home_subtitle
import com.xmelon.rubik_solver.generated.resources.home_title
import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.solver.CubeSolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(onScanClicked: () -> Unit) {
    var solverReady by remember { mutableStateOf(false) }
    var errorCause by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.Default) { CubeSolver.initialize() }
            solverReady = true
        } catch (e: Exception) {
            errorCause = e.message ?: e::class.simpleName ?: "unknown"
        }
    }

    // Compute statusText in composable scope so stringResource (with format args) works on all platforms
    val statusText = when {
        errorCause != null -> stringResource(Res.string.home_status_error, errorCause!!)
        solverReady        -> stringResource(Res.string.home_status_ready)
        else               -> stringResource(Res.string.home_status_loading)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Standardized Bold Title + Muted Subtitle
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(Res.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Content: Standardized Card (Elevation 8dp, Rounded 24dp)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))

                    // Show only the center facelet of each face colored; rest blank (DarkGray).
                    val centersOnlyState = remember {
                        val faceColors = intArrayOf(
                            CubeColor.WHITE.ordinal, CubeColor.BLUE.ordinal, CubeColor.RED.ordinal,
                            CubeColor.YELLOW.ordinal, CubeColor.GREEN.ordinal, CubeColor.ORANGE.ordinal
                        )
                        CubeState.fromFacelets(IntArray(54) { i -> if (i % 9 == 4) faceColors[i / 9] else -1 })
                    }
                    Cube3DView(
                        modifier = Modifier.fillMaxSize(),
                        cubeState = centersOnlyState,
                        dragEnabled = false,
                        pitchOverride = 90f,
                        yawOverride = 0f,
                        highlightCenterFace = null
                    )
                }
            }

            // Footer: Standardized Action Area
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp).align(Alignment.CenterHorizontally)
                )
                Button(
                    onClick = onScanClicked,
                    enabled = solverReady,
                    modifier = Modifier.height(56.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(Res.string.home_button_scan), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
