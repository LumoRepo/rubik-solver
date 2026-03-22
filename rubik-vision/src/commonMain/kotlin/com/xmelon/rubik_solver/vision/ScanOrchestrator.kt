package com.xmelon.rubik_solver.vision

import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.model.Face
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guides the user through scanning all 6 faces of the cube and accumulates the
 * scanned colors into a single [CubeState].
 */
private fun log(msg: String) { println("[RubikSolver] $msg") }

class ScanOrchestrator {

    companion object {
        val SCAN_ORDER: List<Face> = listOf(Face.U, Face.F, Face.R, Face.B, Face.L, Face.D)
    }
    
    // @Volatile: ensures writes from one thread are immediately visible to reads on others.
    // All mutations happen on the main thread via onClick, but @Volatile costs nothing here
    // and guards against accidental cross-thread access in the future.
    @kotlin.concurrent.Volatile private var currentIndex = 0
    
    // Store the 54 facelets as we collect them
    private val _scannedFacelets = MutableStateFlow(IntArray(54) { -1 })
    val scannedFacelets = _scannedFacelets.asStateFlow()

    // WB-corrected photographed colors per facelet (0xAARRGGBB, -1 = not yet scanned)
    private val _scannedWbRgbs = MutableStateFlow(IntArray(54) { -1 })
    val scannedWbRgbs = _scannedWbRgbs.asStateFlow()

    private val _currentFaceToScan = MutableStateFlow(SCAN_ORDER[currentIndex])
    val currentFaceToScan = _currentFaceToScan.asStateFlow()

    private val _isScanComplete = MutableStateFlow(false)
    val isScanComplete = _isScanComplete.asStateFlow()

    /**
     * Called when the user clicks "Capture" on the current face.
     * @param colors The 9 colors detected for this face.
     */
    fun commitCurrentFace(colors: List<CubeColor>, wbRgbs: IntArray? = null) {
        if (colors.size != 9 || currentIndex >= SCAN_ORDER.size) return

        val face = SCAN_ORDER[currentIndex]
        val offset = face.offset

        // === FACE D ORIENTATION ===
        // The camera captures D from below after a pitch down from L (Green).
        // This makes L the "Top" in the camera frame, while Kociemba expects 
        // B (Orange) to be Top when F (Red) is Front.
        //
        // Commit transform (camera → Kociemba): stored[k] = camera[f(k)]
        //   f(k) = (k%3)*3 + (2-k/3)  →  [2,5,8,1,4,7,0,3,6]   (CW-90)
        // camera → Kociemba index mapping for D face: f(k) = (k%3)*3 + (2-k/3)
        val dMap = intArrayOf(2, 5, 8, 1, 4, 7, 0, 3, 6)
        val oriented = if (face == Face.D) {
            listOf(
                colors[2], colors[5], colors[8],
                colors[1], colors[4], colors[7],
                colors[0], colors[3], colors[6]
            )
        } else {
            colors
        }.toMutableList().also { it[4] = CubeColor.expectedCenter(face) }

        val newFacelets = _scannedFacelets.value.copyOf()
        for (i in 0 until 9) {
            newFacelets[offset + i] = oriented[i].ordinal
        }
        _scannedFacelets.value = newFacelets

        if (wbRgbs != null && wbRgbs.size == 9) {
            val newWbRgbs = _scannedWbRgbs.value.copyOf()
            for (i in 0 until 9) {
                val src = if (face == Face.D) dMap[i] else i
                newWbRgbs[offset + i] = wbRgbs[src]
            }
            _scannedWbRgbs.value = newWbRgbs
        }

        // If every facelet is now filled (i.e. this was a re-scan of an existing face),
        // complete immediately instead of stepping through the remaining already-scanned faces.
        if (newFacelets.none { it < 0 }) {
            log("COMMIT $face → SCAN_COMPLETE (all filled)")
            currentIndex = SCAN_ORDER.size
            _isScanComplete.value = true
        } else {
            val filled = newFacelets.count { it >= 0 } / 9
            log("COMMIT $face → next (${filled}/6 faces done)")
            moveToNextFace()
        }
    }

    /**
     * Move to the previous face in the scan order.
     * @return true if the face actually changed, false if already at the first face.
     */
    fun moveToPreviousFace(): Boolean {
        // Clamp first: if scan was completed, currentIndex is past the last face
        val from = currentIndex.coerceAtMost(SCAN_ORDER.size - 1)
        return if (from > 0) {
            currentIndex = from - 1
            log("PREV → ${SCAN_ORDER[currentIndex]}")
            _currentFaceToScan.value = SCAN_ORDER[currentIndex]
            _isScanComplete.value = false
            true
        } else {
            false
        }
    }

    /**
     * Jump directly to a specific face (for re-scanning).
     */
    fun jumpToFace(face: Face) {
        val index = SCAN_ORDER.indexOf(face)
        if (index >= 0) {
            log("JUMP_TO $face (idx=$index)")
            currentIndex = index
            _currentFaceToScan.value = SCAN_ORDER[currentIndex]
            _isScanComplete.value = false
        }
    }

    /**
     * Move to the next face in the scan order.
     */
    fun moveToNextFace() {
        currentIndex++
        if (currentIndex >= SCAN_ORDER.size) {
            _isScanComplete.value = true
        } else {
            _currentFaceToScan.value = SCAN_ORDER[currentIndex]
        }
    }

    /**
     * Restarts the scanning process from the beginning.
     */
    fun reset() {
        currentIndex = 0
        _scannedFacelets.value = IntArray(54) { -1 }
        _scannedWbRgbs.value = IntArray(54) { -1 }
        _isScanComplete.value = false
        _currentFaceToScan.value = SCAN_ORDER[currentIndex]
    }

    /**
     * Resets the data for a specific face.
     */
    fun resetFace(face: Face) {
        val offset = face.offset
        val newFacelets = _scannedFacelets.value.copyOf()
        val newWbRgbs = _scannedWbRgbs.value.copyOf()
        for (i in 0 until 9) {
            newFacelets[offset + i] = -1
            newWbRgbs[offset + i] = -1
        }
        _scannedFacelets.value = newFacelets
        _scannedWbRgbs.value = newWbRgbs
    }

    /**
     * Builds the [CubeState] once all faces are scanned.
     * @throws IllegalStateException if the scan is incomplete.
     */
    fun buildCubeState(): CubeState {
        check(isScanComplete.value) { "Cannot build CubeState: scan is incomplete" }
        val facelets = _scannedFacelets.value
        check(facelets.none { it < 0 }) { "Cannot build CubeState: contains unfilled facelets" }
        return CubeState.fromFacelets(facelets)
    }
}
