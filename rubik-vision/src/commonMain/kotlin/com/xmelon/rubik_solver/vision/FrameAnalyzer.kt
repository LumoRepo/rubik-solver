package com.xmelon.rubik_solver.vision

import com.xmelon.rubik_solver.model.CubeColor

/**
 * Platform-agnostic interface for frame-by-frame cube color detection.
 * Android: implemented by CubeFrameAnalyzer using ImageProxy / CameraX.
 * iOS: implemented by CubeFrameAnalyzer using CVPixelBuffer / AVFoundation.
 */
interface FrameAnalyzer {
    /** Expected center color for the face currently being scanned. */
    var expectedCenterColor: CubeColor?

    /** Resets temporal smoothing buffers between faces. */
    fun resetTemporalBuffers()
}
