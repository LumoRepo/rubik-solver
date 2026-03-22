package com.xmelon.rubik_solver.ui

import androidx.compose.ui.graphics.Color
import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.Face

/**
 * Converts a view-space tile index to the color-space (camera/facelets) index for Face.D.
 * Face D is captured rotated 90° CW relative to Kociemba layout, so indices are remapped.
 */
internal fun dViewToColor(idx: Int): Int = (idx % 3) * 3 + (2 - idx / 3)
internal fun dColorToView(ci: Int): Int  = (2 - ci % 3) * 3 + (ci / 3)

internal fun isFaceScanned(facelets: IntArray, face: Face): Boolean =
    (0 until 9).all { facelets[face.offset + it] != -1 }

/**
 * Returns the set of tile indices (in Cube3DView coordinate space) that have been
 * manually overridden on the current face, used to draw the yellow override indicator.
 */
internal fun buildOverrideIndicators(
    currentFace:    Face,
    colorOverrides: Map<Int, CubeColor>
): Map<Face, Set<Int>> {
    val indices = colorOverrides.keys.map { ci ->
        if (currentFace == Face.D) dColorToView(ci) else ci
    }.toSet()
    return if (indices.isNotEmpty()) mapOf(currentFace to indices) else emptyMap()
}

/**
 * Builds the face color override map for scan mode:
 * - Unscanned faces: center tile shows the expected (reference) color.
 * - Current face when aligned: all 9 tiles show live WB-corrected camera colors.
 */
internal fun buildScanFaceOverrides(
    currentFace:         Face,
    effectiveLiveColors: List<CubeColor>,
    isAwaitingAlignment: Boolean,
    liveWbRgbs:          IntArray,
    colorPalette:        Map<CubeColor, Int>,
    facelets:            IntArray,
    colorOverrides:      Map<Int, CubeColor>
): Map<Face, Array<Color?>> {
    val result = mutableMapOf<Face, Array<Color?>>()

    // For every unscanned face show the expected center color so the user
    // always has a color reference even before scanning that face.
    for (f in Face.entries) {
        if (!isFaceScanned(facelets, f)) {
            result[f] = Array(9) { idx ->
                if (idx == 4) CubeColor.expectedCenter(f).let { ec ->
                    colorPalette[ec]?.let { Color(it).copy(alpha = 0.85f) }
                        ?: ec.toComposeColor().copy(alpha = 0.85f)
                } else null
            }
        }
    }

    if (!isAwaitingAlignment) {
        val f = currentFace
        // Compute a per-color group-average WB value so every tile of the same
        // detected color renders an identical calibrated RGB rather than per-pixel noise.
        val liveGroupAvgRgb = run {
            val rSum = IntArray(CubeColor.entries.size)
            val gSum = IntArray(CubeColor.entries.size)
            val bSum = IntArray(CubeColor.entries.size)
            val cnt  = IntArray(CubeColor.entries.size)
            for (ci in 0..8) {
                val c  = effectiveLiveColors.getOrNull(ci) ?: continue
                val wb = liveWbRgbs.getOrElse(ci) { 0 }
                val o  = c.ordinal
                rSum[o] += (wb shr 16) and 0xFF
                gSum[o] += (wb shr  8) and 0xFF
                bSum[o] +=  wb         and 0xFF
                cnt[o]++
            }
            IntArray(CubeColor.entries.size) { o ->
                val n = cnt[o]
                if (n == 0) 0
                else (-0x1000000) or ((rSum[o]/n) shl 16) or ((gSum[o]/n) shl 8) or (bSum[o]/n)
            }
        }
        result[f] = Array(9) { idx ->
            val ci   = if (f == Face.D) dViewToColor(idx) else idx
            val live = effectiveLiveColors.getOrNull(ci)
            if (idx == 4) {
                // Center tile is always locked to the expected color — never the live camera value.
                val ec    = CubeColor.expectedCenter(f)
                val palWb = colorPalette[ec]
                if (palWb != null) Color(palWb).copy(alpha = 0.85f)
                else ec.toComposeColor().copy(alpha = 0.85f)
            } else if (live != null) {
                // Manually overridden tiles show the reference color so the user
                // can clearly see which classification they've set (not the camera color).
                if (colorOverrides.containsKey(ci)) {
                    live.toComposeColor().copy(alpha = 0.85f)
                } else {
                    val avgWb = liveGroupAvgRgb[live.ordinal]
                    if (avgWb != 0) Color(avgWb).copy(alpha = 0.85f)
                    else live.toComposeColor().copy(alpha = 0.85f)
                }
            } else null
        }
    }
    return result
}


/**
 * Computes the effective WB RGB values for a confirmed face, applying group averaging
 * across tiles of the same detected color to reduce per-pixel noise.
 * Manually overridden tiles use their palette color instead.
 */
internal fun buildEffectiveWbRgbs(
    liveWbRgbs:          IntArray,
    effectiveLiveColors: List<CubeColor>,
    colorOverrides:      Map<Int, CubeColor>,
    colorPalette:        Map<CubeColor, Int>
): IntArray {
    if (liveWbRgbs.size != 9) return liveWbRgbs
    val rS = IntArray(CubeColor.entries.size)
    val gS = IntArray(CubeColor.entries.size)
    val bS = IntArray(CubeColor.entries.size)
    val ct = IntArray(CubeColor.entries.size)
    for (i in 0..8) {
        if (colorOverrides.containsKey(i)) continue
        val c = effectiveLiveColors.getOrNull(i) ?: continue
        val wb = liveWbRgbs[i]; val o = c.ordinal
        rS[o] += (wb shr 16) and 0xFF
        gS[o] += (wb shr  8) and 0xFF
        bS[o] +=  wb         and 0xFF
        ct[o]++
    }
    return IntArray(9) { i ->
        val overrideColor = colorOverrides[i]
        if (overrideColor != null) return@IntArray colorPalette[overrideColor] ?: liveWbRgbs[i]
        val o = effectiveLiveColors.getOrNull(i)?.ordinal ?: return@IntArray liveWbRgbs[i]
        val n = ct[o]
        if (n == 0) liveWbRgbs[i]
        else (-0x1000000) or ((rS[o]/n) shl 16) or ((gS[o]/n) shl 8) or (bS[o]/n)
    }
}

internal fun faceTargetRotX(face: Face) = when (face) { Face.U -> 90f; Face.D -> -90f; else -> 0f }
internal fun faceTargetRotY(face: Face) = when (face) {
    Face.F -> 0f; Face.R -> -90f; Face.B -> -180f; Face.L -> -270f; Face.D -> -270f; else -> 0f
}
