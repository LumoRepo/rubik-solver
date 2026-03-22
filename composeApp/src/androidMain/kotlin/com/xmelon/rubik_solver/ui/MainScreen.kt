package com.xmelon.rubik_solver.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xmelon.rubik_solver.AppMode
import com.xmelon.rubik_solver.AppViewModel
import com.xmelon.rubik_solver.checkCameraPermission
import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.model.Face
import com.xmelon.rubik_solver.model.Move
import com.xmelon.rubik_solver.platformLog
import com.xmelon.rubik_solver.rememberCameraPermissionState
import com.xmelon.rubik_solver.solver.SolveResult
import com.xmelon.rubik_solver.vision.LabConverter
import kotlinx.coroutines.*
import com.xmelon.rubik_solver.generated.resources.Res
import com.xmelon.rubik_solver.generated.resources.permission_camera_required
import com.xmelon.rubik_solver.generated.resources.permission_grant
import org.jetbrains.compose.resources.stringResource

private const val TAG = "RubikSolver"
private fun log(msg: String) { platformLog(TAG, msg) }



@Composable
fun MainScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val initialPerm = checkCameraPermission()
    var hasPerm by remember { mutableStateOf(initialPerm) }
    val requestPermission = rememberCameraPermissionState { hasPerm = it }
    LaunchedEffect(Unit) { if (!hasPerm) requestPermission() }

    if (hasPerm) {
        MainScreenContent(vm, onBack, onDone)
    } else {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.permission_camera_required))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { requestPermission() }) {
                    Text(stringResource(Res.string.permission_grant))
                }
            }
        }
    }
}

@Composable
private fun MainScreenContent(
    vm: AppViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val orchestrator = vm.orchestrator

    // -- Common state --
    val currentFace by orchestrator.currentFaceToScan.collectAsState()
    val facelets by orchestrator.scannedFacelets.collectAsState()

    // -- Common animations --
    val cubeRotation = rememberCubeRotation(vm, currentFace)
    val solveMoveAnimation = rememberSolveMoveAnimation(vm)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            when (vm.appMode) {
                AppMode.SCAN -> ScanModeLayout(
                    vm = vm,
                    currentFace = currentFace,
                    facelets = facelets,
                    cubeRotation = cubeRotation,
                    onBack = onBack
                )
                AppMode.SOLVE -> SolveModeLayout(
                    vm = vm,
                    facelets = facelets,
                    cubeRotation = cubeRotation,
                    solveMoveAnimation = solveMoveAnimation,
                    onDone = onDone
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ScanModeLayout(
    vm: AppViewModel,
    currentFace: Face,
    facelets: IntArray,
    cubeRotation: CubeRotationState,
    onBack: () -> Unit
) {
    val analyzer = vm.analyzer
    val orchestrator = vm.orchestrator
    val liveColors by analyzer.detectedColors.collectAsState()
    val liveRgbs by analyzer.detectedRgbs.collectAsState()
    val liveWbRgbs by analyzer.detectedWbRgbs.collectAsState()

    val effectiveLiveColors by remember {
        derivedStateOf {
            if (vm.colorOverrides.isEmpty()) liveColors
            else liveColors.toMutableList().also { list ->
                vm.colorOverrides.forEach { (i, c) -> if (i < list.size) list[i] = c }
            }
        }
    }

    // Stability-based alignment: the camera's median center-tile hue must fall within
    // the expected color's default hue range for CENTER_STABLE_FRAMES consecutive frames.
    // This works regardless of how the classifier ranks the center tile, fixing the
    // deadlock where red/orange confusion prevented orange from ever aligning.
    val centerStable by analyzer.centerStable.collectAsState()

    LaunchedEffect(centerStable, vm.isViewingScanned) {
        when {
            vm.isViewingScanned -> return@LaunchedEffect
            centerStable && vm.isAwaitingAlignment  -> vm.isAwaitingAlignment = false
            !centerStable && !vm.isAwaitingAlignment -> vm.isAwaitingAlignment = true
        }
    }

    val overrideIndicators = remember(currentFace, vm.colorOverrides) {
        buildOverrideIndicators(currentFace, vm.colorOverrides)
    }
    val faceColorOverrides = remember(
        currentFace, effectiveLiveColors, vm.isAwaitingAlignment, liveWbRgbs, vm.colorPalette.size, facelets
    ) {
        buildScanFaceOverrides(
            currentFace, effectiveLiveColors, vm.isAwaitingAlignment,
            liveWbRgbs, vm.colorPalette, facelets, vm.colorOverrides
        )
    }
    val finalFaceOverrides = if (vm.debugMode && !vm.isAwaitingAlignment) {
        faceColorOverrides.toMutableMap().also { map ->
            val t = map[currentFace]?.clone() ?: arrayOfNulls(9)
            for (i in 0 until 9) { if (i != 4 && t[i] != null) t[i] = Color.Transparent }
            map[currentFace] = t
        }
    } else faceColorOverrides

    ScanHeader(
        face = currentFace,
        isAwaitingAlignment = vm.isAwaitingAlignment,
        tileWarningColor = vm.tileWarningColor,
        canClear = isFaceScanned(facelets, currentFace),
        onClear = {
            orchestrator.resetFace(currentFace)
            vm.resetAnalyzerBuffers()
            vm.isAwaitingAlignment = true
            vm.tileWarningColor = null
            vm.restoreColorCheckpoint()
        },
        modifier = Modifier.fillMaxWidth()
    )

    Card(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            CameraPreviewLayer(
                analyzer = analyzer,
                debugMode = vm.debugMode,
                onDebugToggle = { vm.toggleDebugMode() },
                modifier = Modifier.fillMaxSize()
            )
            Cube3DView(
                modifier = Modifier.fillMaxSize(),
                cubeState = CubeState.fromFacelets(facelets),
                pitchOverride = cubeRotation.pitch.value,
                yawOverride = cubeRotation.yaw.value,
                dragEnabled = !vm.isAwaitingAlignment || vm.isViewingScanned,
                dragResetKey = vm.dragResetKey,
                faceOverrides = finalFaceOverrides,
                highlightCenterFace = if (!vm.isAwaitingAlignment) currentFace else null,
                overrideIndicatorIndices = overrideIndicators,
                colorPalette = vm.colorPalette,
                onTileTap = { face, idx ->
                    if (!vm.isAwaitingAlignment && face == currentFace) {
                        val ci = if (face == Face.D) dViewToColor(idx) else idx
                        if (ci != 4 && ci < effectiveLiveColors.size) {
                            val wb = liveWbRgbs.getOrElse(ci) { 0 }
                            val before = effectiveLiveColors[ci]
                            val alt = vm.colorCycle(wb, before)
                            vm.colorOverrides = vm.colorOverrides + (ci to alt)
                            vm.tileWarningColor = null
                            val labStr = if (wb != 0) {
                                val lab = LabConverter.sRgbToLab(wb)
                                "[%.1f,%.1f,%.1f]".format(lab[0], lab[1], lab[2])
                            } else "N/A"
                            val rank = if (wb != 0) vm.rankFor(wb, alt) else -1
                            log("TILE_TAP face=$face ci=$ci wb=#${"%06X".format(wb)} lab=$labStr before=${before.name} after=${alt.name} rank=$rank")
                        }
                    }
                }
            )
            if (vm.isLoadingSolve) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScanFooter(
            currentFace = currentFace,
            isViewingScanned = vm.isViewingScanned,
            isAwaitingAlignment = vm.isAwaitingAlignment,
            faceScanned = isFaceScanned(facelets, currentFace),
            effectiveLiveColorsSize = effectiveLiveColors.size,
            onBack = {
                if (currentFace == Face.U) {
                    vm.resetColorCalibration()
                    onBack()
                } else {
                    log("NAV_BACK from $currentFace")
                    if (orchestrator.moveToPreviousFace()) vm.restoreColorCheckpoint()
                }
            },
            onEdit = {
                log("EDIT face=$currentFace")
                vm.isViewingScanned = false
                vm.tileWarningColor = null
                vm.dragResetKey++
                vm.resetAnalyzerBuffers()
                vm.restoreColorCheckpoint()
            },
            onConfirm = {
                vm.tileWarningColor = null
                vm.confirmFace(effectiveLiveColors, liveRgbs, liveWbRgbs)
            },
            onForward = {
                log("NAV_FORWARD from $currentFace")
                vm.saveColorCheckpoint()
                orchestrator.moveToNextFace()
            }
        )
    }
}

@Composable
private fun ColumnScope.SolveModeLayout(
    vm: AppViewModel,
    facelets: IntArray,
    cubeRotation: CubeRotationState,
    solveMoveAnimation: SolveMoveAnimationState,
    onDone: () -> Unit
) {
    val baseState = remember(facelets, vm.currentStep, vm.moves, vm.animIsReverse) {
        val n = when {
            vm.animIsReverse -> vm.currentStep
            vm.currentStep < 1 -> 0
            else -> vm.currentStep - 1
        }
        CubeState.fromFacelets(facelets).applyMoves(vm.moves.take(n))
    }

    val animEffStep = if (vm.animIsReverse) vm.currentStep + 1 else vm.currentStep
    val animatingMove: Move? = if (animEffStep in 1..vm.totalMoves) vm.moves[animEffStep - 1] else null

    SolveHeader(
        solveResult = vm.solveResult,
        currentStep = vm.currentStep,
        totalMoves = vm.totalMoves,
        moves = vm.moves,
        modifier = Modifier.fillMaxWidth()
    )

    Card(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            Cube3DView(
                modifier = Modifier.fillMaxSize(),
                cubeState = baseState,
                animatingMove = animatingMove,
                moveProgress = solveMoveAnimation.progress.value,
                pitchOverride = cubeRotation.pitch.value,
                yawOverride = cubeRotation.yaw.value,
                dragEnabled = true,
                dragResetKey = vm.dragResetKey,
                colorPalette = vm.colorPalette
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SolveFooter(
            currentStep = vm.currentStep,
            isSolved = vm.isSolved,
            canGoNext = !vm.isSolved && vm.solveResult is SolveResult.Success,
            onPrev = { vm.goPrevStep() },
            onMiddle = {
                if (vm.isSolved) onDone()
                else if (vm.currentStep == 0) vm.restartScan()
                else vm.repeatStep()
            },
            onNext = { vm.goNextStep() }
        )
    }
}

@Stable
private class CubeRotationState(val pitch: Animatable<Float, *>, val yaw: Animatable<Float, *>)

@Composable
private fun rememberCubeRotation(vm: AppViewModel, currentFace: Face): CubeRotationState {
    val pitch = remember { Animatable(faceTargetRotX(Face.U)) }
    val yaw = remember { Animatable(faceTargetRotY(Face.U)) }

    val rotTargetX = if (vm.appMode == AppMode.SOLVE) 0f else faceTargetRotX(currentFace)
    val rotTargetY = if (vm.appMode == AppMode.SOLVE) 0f else faceTargetRotY(currentFace)

    LaunchedEffect(rotTargetX, rotTargetY) {
        val dy = rotTargetY - yaw.value
        when {
            dy > 180f -> yaw.snapTo(yaw.value + 360f)
            dy < -180f -> yaw.snapTo(yaw.value - 360f)
        }
        launch { pitch.animateTo(rotTargetX, tween(800)) }
        launch { yaw.animateTo(rotTargetY, tween(800)) }
    }
    return remember { CubeRotationState(pitch, yaw) }
}

@Stable
private class SolveMoveAnimationState(val progress: Animatable<Float, *>)

@Composable
private fun rememberSolveMoveAnimation(vm: AppViewModel): SolveMoveAnimationState {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(vm.moveKey) {
        when {
            vm.animIsReverse -> {
                progress.snapTo(1f)
                if (vm.currentStep + 1 in 1..vm.totalMoves) progress.animateTo(0f, tween(800))
                vm.animIsReverse = false
                progress.snapTo(1f)
            }
            vm.currentStep == 0 || vm.currentStep > vm.totalMoves -> progress.snapTo(1f)
            else -> {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(800))
            }
        }
    }
    return remember { SolveMoveAnimationState(progress) }
}
