package com.xmelon.rubik_solver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.model.Face
import com.xmelon.rubik_solver.model.Move
import com.xmelon.rubik_solver.solver.CubeSolver
import com.xmelon.rubik_solver.solver.SolveResult
import com.xmelon.rubik_solver.ui.ProgressPhase
import com.xmelon.rubik_solver.ui.buildEffectiveWbRgbs
import com.xmelon.rubik_solver.ui.isFaceScanned
import com.xmelon.rubik_solver.vision.CubeFrameAnalyzer
import com.xmelon.rubik_solver.vision.LabConverter
import com.xmelon.rubik_solver.vision.ScanOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "RubikSolver"
private fun log(msg: String) { platformLog(TAG, msg) }

/** Renders all 6 faces of a 54-element facelets array as a compact string, e.g.:
 *  U[WWW|WWW|WWW] R[BBB|BBB|BBB] F[RRR|RRR|RRR] D[YYY|YYY|YYY] L[GGG|GGG|GGG] B[OOO|OOO|OOO] */
private fun IntArray.toCubeStr(): String {
    val labels = CubeColor.entries.map { it.label }
    return Face.entries.joinToString(" ") { face ->
        val o = face.offset
        val rows = (0 until 9).map { i -> val v = this[o + i]; if (v < 0) '?' else labels.getOrElse(v) { '?' } }
        "${face.name}[${rows.take(3).joinToString("")}|${rows.drop(3).take(3).joinToString("")}|${rows.drop(6).joinToString("")}]"
    }
}

class AppViewModel : ViewModel() {
    val analyzer = CubeFrameAnalyzer()
    val orchestrator = ScanOrchestrator()
    var currentScreen by mutableStateOf(Screen.HOME)

    // -- App Mode --
    var appMode by mutableStateOf(AppMode.SCAN)

    // -- Scan State --
    var isAwaitingAlignment by mutableStateOf(true)
    var isViewingScanned by mutableStateOf(false)
    var colorOverrides by mutableStateOf(mapOf<Int, CubeColor>())
    var tileWarningColor by mutableStateOf<CubeColor?>(null)
    var debugMode by mutableStateOf(false)
    var dragResetKey by mutableIntStateOf(0)

    // -- Solve State --
    var isLoadingSolve by mutableStateOf(false)
    var solveResult by mutableStateOf<SolveResult?>(null)
    var currentStep by mutableIntStateOf(0)
    var animIsReverse by mutableStateOf(false)
    var moveKey by mutableIntStateOf(0)

    val moves: List<Move> get() = (solveResult as? SolveResult.Success)?.moves ?: emptyList()
    val totalMoves: Int get() = moves.size
    val totalSteps: Int get() = totalMoves + 1
    val isSolved: Boolean get() = currentStep > totalMoves

    /** Photographed WB-corrected ARGB per CubeColor, populated as faces are confirmed. */
    val colorPalette = mutableStateMapOf<CubeColor, Int>()

    var overallProgress by mutableFloatStateOf(0f)
    var progressPhase by mutableStateOf(ProgressPhase.IDLE)
    var scanningFace by mutableStateOf<Face?>(null)
    var solvingStep by mutableIntStateOf(0)
    var solvingTotalSteps by mutableIntStateOf(0)

    init {
        // Synchronize VM progress state with orchestrator flows
        viewModelScope.launch {
            orchestrator.currentFaceToScan.collectLatest { face ->
                log("FACE_CHANGE → $face")
                scanningFace = face
                updateScanProgress(face)
                resetFaceUiState(face)
            }
        }
        viewModelScope.launch {
            orchestrator.isScanComplete.collectLatest { complete ->
                log("SCAN_COMPLETE=$complete")
                if (complete) runSolver()
            }
        }
    }

    private fun updateScanProgress(face: Face) {
        val idx = ScanOrchestrator.SCAN_ORDER.indexOf(face).coerceAtLeast(0)
        overallProgress = (idx.toFloat() / ScanOrchestrator.SCAN_ORDER.size) * 0.5f
        progressPhase = ProgressPhase.SCANNING
    }

    fun resetFaceUiState(face: Face) {
        colorOverrides = emptyMap()
        tileWarningColor = null
        isAwaitingAlignment = true
        isViewingScanned = isFaceScanned(orchestrator.scannedFacelets.value, face)
        log("RESET_FACE face=$face viewingScanned=$isViewingScanned cube=${orchestrator.scannedFacelets.value.toCubeStr()}")
        analyzer.expectedCenterColor = CubeColor.expectedCenter(face)
        analyzer.resetTemporalBuffers()
    }

    private fun runSolver() {
        viewModelScope.launch {
            try {
                val state = orchestrator.buildCubeState()
                log("SOLVER_START cube=${state.facelets.toCubeStr()}")
                isLoadingSolve = true
                val result = withContext(Dispatchers.Default) { CubeSolver.solve(state) }
                log("SOLVER_DONE result=${result::class.simpleName}")
                solveResult = result
                currentStep = 0
                animIsReverse = false
                isLoadingSolve = false
                appMode = AppMode.SOLVE
                progressPhase = ProgressPhase.SOLVING
                updateSolveProgress(0)
            } catch (e: Exception) {
                log("SOLVER_ERROR ${e.message}")
                solveResult = SolveResult.Error(e.message ?: "Unexpected error")
                isLoadingSolve = false
                appMode = AppMode.SOLVE
            }
        }
    }

    fun updateSolveProgress(step: Int) {
        val progress = (step.toFloat() / totalSteps).coerceIn(0f, 1f)
        overallProgress = 0.5f + progress * 0.5f
        solvingStep = step
        solvingTotalSteps = totalSteps
    }

    // -- Actions --

    fun saveColorCheckpoint() = analyzer.colorDetector.saveCheckpoint()
    fun restoreColorCheckpoint() = analyzer.colorDetector.restoreCheckpoint()
    fun resetAnalyzerBuffers() = analyzer.resetTemporalBuffers()
    fun resetColorCalibration() = analyzer.colorDetector.resetCalibration()
    fun colorCycle(wb: Int, current: CubeColor): CubeColor = analyzer.colorDetector.colorCycle(wb, current)
    fun rankFor(wb: Int, color: CubeColor): Int = analyzer.colorDetector.rankFor(wb, color)
    fun toggleDebugMode() { debugMode = !debugMode; analyzer.debugMode = debugMode }

    fun confirmFace(liveColors: List<CubeColor>, liveRgbs: IntArray, liveWbRgbs: IntArray) {
        val face = orchestrator.currentFaceToScan.value
        val overrideStr = if (colorOverrides.isEmpty()) "none"
            else colorOverrides.entries.joinToString { "${it.key}→${it.value.name}" }
        log("CONFIRM face=$face colors=${liveColors.map { it.name }} overrides=[$overrideStr]")
        // Build correctedColors: start from live classifications, then apply:
        //  1. Manual tile overrides (user-tapped corrections)
        //  2. Force center to expected color (always authoritative)
        val correctedColors = if (liveColors.size >= 9)
            liveColors.toMutableList().also { list ->
                colorOverrides.forEach { (ci, color) -> if (ci < list.size) list[ci] = color }
                list[4] = CubeColor.expectedCenter(face)
            }
        else liveColors
        analyzer.colorDetector.saveCheckpoint()
        // Only calibrate the center tile — it is 100% verified ground truth (color always
        // known from the scan order). Non-center tiles have uncertain classifications and
        // calibrating them contaminates models, especially RED/ORANGE which overlap on camera.
        // weight=8 pushes n above MIN_SAMPLES so Welford variance kicks in and the model
        // becomes a tight anchor around the actual camera LAB for this face's color.
        val expectedCenter = CubeColor.expectedCenter(face)
        if (liveWbRgbs.size >= 5) {
            val centerLab = LabConverter.sRgbToLab(liveWbRgbs[4])
            analyzer.colorDetector.calibrateTileLab(centerLab, expectedCenter, weight = 8f)
        }
        // Manually overridden tiles are also trustworthy — user has explicitly verified them.
        if (liveWbRgbs.size >= 9) {
            colorOverrides.forEach { (ci, color) ->
                if (ci < liveWbRgbs.size && ci != 4) analyzer.colorDetector.calibrateTile(liveWbRgbs[ci], color)
            }
        }
        log("CONFIRM cal_after=${analyzer.colorDetector.calibrationStr()}")
        val labStr = (0 until minOf(liveWbRgbs.size, 9)).joinToString(",") { i ->
            val lab = LabConverter.sRgbToLab(liveWbRgbs[i])
            "[%.0f,%.0f,%.0f]".format(lab[0], lab[1], lab[2])
        }
        log("CONFIRM_LABS face=$face $labStr")
        log("MODEL_DUMP ${analyzer.colorDetector.modelDumpStr()}")
        val effectiveWbRgbs = buildEffectiveWbRgbs(liveWbRgbs, correctedColors, colorOverrides, colorPalette)
        orchestrator.commitCurrentFace(correctedColors, effectiveWbRgbs)
        if (liveWbRgbs.size == 9) {
            colorPalette[CubeColor.expectedCenter(face)] = liveWbRgbs[4]
        }
        log("CONFIRM cube=${orchestrator.scannedFacelets.value.toCubeStr()}")
        isAwaitingAlignment = true
    }

    fun goNextStep() {
        if (isSolved || solveResult !is SolveResult.Success) return
        animIsReverse = false
        currentStep++
        moveKey++
        updateSolveProgress(currentStep)
    }

    fun goPrevStep() {
        if (currentStep == 0) {
            log("GO_BACK_TO_SCAN → Face.D")
            appMode = AppMode.SCAN
            orchestrator.jumpToFace(Face.D)
            resetFaceUiState(Face.D)
            return
        }
        animIsReverse = true
        currentStep--
        moveKey++
        updateSolveProgress(currentStep)
    }

    fun repeatStep() {
        if (currentStep == 0 || isSolved) return
        animIsReverse = false
        moveKey++
    }

    fun restartScan() {
        orchestrator.reset()
        analyzer.colorDetector.resetCalibration()
        analyzer.resetTemporalBuffers()
        analyzer.expectedCenterColor = CubeColor.expectedCenter(orchestrator.currentFaceToScan.value)
        colorPalette.clear()
        solveResult = null
        currentStep = 0
        appMode = AppMode.SCAN
        val firstFace = orchestrator.currentFaceToScan.value
        scanningFace = firstFace
        updateScanProgress(firstFace)
        solvingStep = 0
        solvingTotalSteps = 0
    }

    fun resetProgress() {
        overallProgress = 0f
        progressPhase = ProgressPhase.IDLE
        scanningFace = null
        solvingStep = 0
        solvingTotalSteps = 0
    }
}
