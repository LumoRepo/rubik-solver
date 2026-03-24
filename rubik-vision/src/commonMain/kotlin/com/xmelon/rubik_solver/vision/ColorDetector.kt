package com.xmelon.rubik_solver.vision

import com.xmelon.rubik_solver.model.CubeColor

/**
 * Color classifier for Rubik's Cube facelets using CIELAB + Bayesian Gaussian models.
 *
 * Each of the 6 CubeColors has a [ColorModel] (mean + variance in LAB space),
 * initialized to CIELAB priors matching standard Rubik's pigments.
 * Classification uses negative log-likelihood (NLL): the color with the lowest
 * NLL score wins.  Calibration updates the matching model via Welford's online
 * algorithm.
 */
class ColorDetector {

    // ---- CIELAB priors tuned to typical smartphone camera response ----
    // Standard D65 pigment values are adjusted for the camera's color rendering,
    // which compresses the a-axis (less chromatic) and shifts luminance.
    // Key change: RED and ORANGE on camera are nearly identical in the a-channel
    // (both ≈ a=49-50). Their main discriminators are L (RED darker ≈36, ORANGE
    // lighter ≈44) and b (RED less yellow ≈38, ORANGE more yellow ≈50).
    // WHITE:  L=85, a=-2,  b=5
    // YELLOW: L=78, a=-4,  b=72
    // RED:    L=36, a=50,  b=38
    // ORANGE: L=44, a=49,  b=50
    // BLUE:   L=26, a=16,  b=-50
    // GREEN:  L=50, a=-42, b=32
    private val PRIORS: Map<CubeColor, FloatArray> = mapOf(
        CubeColor.WHITE  to floatArrayOf(85f,  -2f,   5f),
        CubeColor.YELLOW to floatArrayOf(78f,  -4f,  72f),
        CubeColor.RED    to floatArrayOf(36f,  50f,  38f),
        CubeColor.ORANGE to floatArrayOf(44f,  49f,  50f),
        CubeColor.BLUE   to floatArrayOf(26f,  16f, -50f),
        CubeColor.GREEN  to floatArrayOf(50f, -42f,  32f)
    )

    // Active models — one per color
    private val models: MutableMap<CubeColor, ColorModel> = buildPriorModels()

    // ---- Snapshot / Undo ----
    private val history = ArrayDeque<Map<CubeColor, ColorModel>>()

    // =====================================================================
    //  Public API
    // =====================================================================

    /**
     * Classifies [lab] (a 3-element CIELAB float array) using NLL against all 6 models.
     * Returns the winning [CubeColor] and a confidence in [0, 1].
     *
     * Confidence formula:
     *   margin = score[2nd] - score[1st]   (always >= 0)
     *   if margin < 2.0f → confidence = 0f
     *   else: scoreMax = max(|score[1st]|, |score[2nd]|) + 1e-6f
     *         confidence = clamp(margin / scoreMax, 0f, 1f)
     */
    fun classify(lab: FloatArray): Pair<CubeColor, Float> {
        val sorted = CubeColor.entries.sortedBy { model(it).score(lab) }
        val best   = sorted[0]
        val second = sorted[1]
        val s1 = model(best).score(lab)
        val s2 = model(second).score(lab)
        val margin = s2 - s1
        // Normalize margin by the larger score magnitude so confidence stays in [0,1]
        // even when ln(variance) is negative (converged models).
        // No hard near-tie threshold: truly ambiguous tiles (margin≈0) naturally produce
        // confidence≈0 via the formula; a hard threshold would block RED from stabilising
        // before any calibration (RED/ORANGE prior margin ~1.24 < old threshold 2.0).
        val scoreMax = maxOf(kotlin.math.abs(s1), kotlin.math.abs(s2)) + 1e-6f
        val confidence = (margin / scoreMax).coerceIn(0f, 1f)
        return Pair(best, confidence)
    }

    /**
     * Updates the model for each confirmed color using the corresponding tile RGB.
     * Each tile counts as weight=1.
     *
     * [tileRgbs] — 9 ARGB ints (one per tile)
     * [confirmedColors] — 9 confirmed CubeColors (one per tile)
     */
    fun calibrateFace(tileRgbs: IntArray, confirmedColors: List<CubeColor>) {
        if (tileRgbs.size != 9 || confirmedColors.size != 9) return
        for (i in 0..8) {
            val lab = LabConverter.sRgbToLab(tileRgbs[i])
            requireNotNull(models[confirmedColors[i]]) { "No model for ${confirmedColors[i]}" }
                .update(lab, weight = 1f)
        }
    }

    /**
     * Updates a single model with weight=3 from a manually corrected tile.
     *
     * [tileRgb] — ARGB int of the tile
     * [correctedColor] — the color the user confirmed
     */
    fun calibrateTile(tileRgb: Int, correctedColor: CubeColor) {
        val lab = LabConverter.sRgbToLab(tileRgb)
        requireNotNull(models[correctedColor]) { "No model for $correctedColor" }
            .update(lab, weight = 3f)
    }

    /**
     * Updates the model for [color] directly from a LAB observation.
     * Used by the center-tile seed path which already works in LAB space.
     */
    fun calibrateTileLab(lab: FloatArray, color: CubeColor, weight: Float = 1f) {
        requireNotNull(models[color]) { "No model for $color" }.update(lab, weight)
    }

    /**
     * Returns the next color in the NLL ranking for [wbRgb], cycling past [currentColor].
     * All 6 colors are sorted by NLL score ascending (best match first); each call advances
     * one step in that ranking, wrapping around, covering all 6 colors without repeating.
     */
    fun colorCycle(wbRgb: Int, currentColor: CubeColor): CubeColor {
        val lab = LabConverter.sRgbToLab(wbRgb)
        val ranked = CubeColor.entries.sortedBy { model(it).score(lab) }
        return if (currentColor == ranked[0]) ranked[1] else ranked[0]
    }

    /** Saves a deep copy of the current model state (for undo).
     *  History is capped at 6 entries — one per face scan — so the oldest is
     *  silently dropped when the cap is reached. */
    fun saveCheckpoint() {
        if (history.size >= 6) history.removeAt(0)
        history.addLast(models.mapValues { it.value.copy() })
    }

    /** Restores the most recently saved model state, or resets to priors if the stack is empty. */
    fun restoreCheckpoint() {
        if (history.isNotEmpty()) {
            val snapshot = history.removeLast()
            for ((color, model) in snapshot) {
                models[color] = model.copy()
            }
        } else {
            resetCalibration()
        }
    }

    /** Resets all models to CIELAB priors and clears history. */
    fun resetCalibration() {
        val fresh = buildPriorModels()
        for ((color, model) in fresh) {
            models[color] = model
        }
        history.clear()
    }

    /** Returns a human-readable calibration summary for debug logging. */
    fun calibrationStr(): String =
        CubeColor.entries.joinToString(" ") { c ->
            val m = model(c)
            "${c.name}[n=${m.n.toInt()} L=${m.mean[0].toInt()} a=${m.mean[1].toInt()} b=${m.mean[2].toInt()}]"
        }

    /** Verbose model dump including effective variance and weighted-sample count. */
    fun modelDumpStr(): String =
        CubeColor.entries.joinToString(" ") { c ->
            val m = model(c)
            "${c.name}[L=${m.mean[0]} a=${m.mean[1]} b=${m.mean[2]} v=${m.variance.toInt()} n=${m.n.toInt()}]"
        }

    /**
     * Returns the 1-based NLL rank of [color] for pixel [wbRgb].
     * 1 = best match, 6 = worst. Used by the tile-tap log to show how many
     * cycle steps separate the chosen correction from the classifier's best guess.
     */
    fun rankFor(wbRgb: Int, color: CubeColor): Int {
        val lab = LabConverter.sRgbToLab(wbRgb)
        val sorted = CubeColor.entries.sortedBy { model(it).score(lab) }
        return sorted.indexOf(color) + 1
    }

    /** 1-based NLL rank of [color] for an already-computed [lab] vector. */
    fun rankForLab(lab: FloatArray, color: CubeColor): Int {
        val sorted = CubeColor.entries.sortedBy { model(it).score(lab) }
        return sorted.indexOf(color) + 1
    }

    /** NLL score for [color] at [lab]. Lower = better match. */
    fun scoreFor(color: CubeColor, lab: FloatArray): Float = model(color).score(lab)

    // =====================================================================
    //  Private helpers
    // =====================================================================

    private fun model(color: CubeColor): ColorModel =
        requireNotNull(models[color]) { "No model for $color" }

    private fun buildPriorModels(): MutableMap<CubeColor, ColorModel> =
        CubeColor.entries.associateTo(mutableMapOf()) { color ->
            color to ColorModel(mean = requireNotNull(PRIORS[color]) { "No prior for $color" }.copyOf())
        }
}
