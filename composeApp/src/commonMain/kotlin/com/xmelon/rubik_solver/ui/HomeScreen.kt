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

    // Status shown in header subtitle — same slot used by scan hint text on scan screen.
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
            // Header: mirrors ScanHeader structure (Row with column + icon-button-sized spacer)
            // so the header height matches the scan screen exactly.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.home_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2
                    )
                }
                // Spacer matching the 48dp IconButton in ScanHeader so header heights align.
                Spacer(Modifier.size(48.dp))
            }

            // Card: identical to scan screen card.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))

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
                        pitchOverride = -90f,
                        yawOverride = -270f,
                        highlightCenterFace = null
                    )
                }
            }

            // Footer: identical Row structure to the scan screen footer so the button aligns
            // with the Scan button on the next screen. Side Spacers match the weight=1
            // OutlinedButtons; the Scan Cube button occupies the same weight=2 center slot.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onScanClicked,
                    enabled = solverReady,
                    modifier = Modifier.height(56.dp).weight(2f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(Res.string.home_button_scan), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
