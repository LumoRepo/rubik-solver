// rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorModel.kt
package com.xmelon.rubik_solver.vision

import kotlin.math.ln

/**
 * Bayesian Gaussian model for one CubeColor in CIELAB space.
 *
 * Uses Welford's online algorithm for incremental weighted mean + variance.
 * Variance stays at [priorVariance] until [n] > [MIN_SAMPLES] to prevent
 * spurious overconfidence from the first 1-2 samples.
 */
class ColorModel(
    mean: FloatArray = FloatArray(3),
    var m2: Float = 0f,
    var n: Float = 0f,
    val priorVariance: Float = DEFAULT_PRIOR_VARIANCE
) {
    val mean: FloatArray = mean.copyOf()

    companion object {
        const val DEFAULT_PRIOR_VARIANCE = 300f   // = ~17²; calibrated for standard Rubik's pigment separation
        const val MIN_SAMPLES = 5f                // Welford variance used only after this many samples
        // Floor variance after calibration. Tiles of the same color vary ~5 LAB units
        // per channel; √30 ≈ 5.5 keeps the model from collapsing to a single point and
        // ensures two calibrated models compete on distance rather than variance ratio.
        const val MIN_CALIBRATED_VARIANCE = 30f
    }

    /** Effective variance: Welford estimate once we have enough data, prior otherwise. */
    val variance: Float get() = if (n > MIN_SAMPLES) (m2 / n).coerceAtLeast(MIN_CALIBRATED_VARIANCE) else priorVariance

    /**
     * Negative log-likelihood score for [lab]. Lower = better match.
     * Formula: ||lab - mean||² / variance + ln(variance)
     */
    fun score(lab: FloatArray): Float {
        val dL = lab[0] - mean[0]
        val da = lab[1] - mean[1]
        val db = lab[2] - mean[2]
        val dist2 = dL * dL + da * da + db * db
        return dist2 / variance + ln(variance)
    }

    /**
     * Updates the model with a new LAB observation using Welford's weighted algorithm.
     *
     * Two-delta form ensures numerically correct incremental variance:
     *   delta1 = sample - oldMean
     *   newMean = oldMean + delta1 * weight/n
     *   delta2 = sample - newMean
     *   M2 += weight * dot(delta1, delta2)
     */
    fun update(lab: FloatArray, weight: Float = 1f) {
        n += weight
        val d1L = lab[0] - mean[0]
        val d1a = lab[1] - mean[1]
        val d1b = lab[2] - mean[2]
        val ratio = weight / n
        mean[0] += d1L * ratio
        mean[1] += d1a * ratio
        mean[2] += d1b * ratio
        val d2L = lab[0] - mean[0]
        val d2a = lab[1] - mean[1]
        val d2b = lab[2] - mean[2]
        m2 += weight * (d1L * d2L + d1a * d2a + d1b * d2b)
    }

    /** Returns a deep copy so the original is unaffected by further updates. */
    fun copy(): ColorModel = ColorModel(mean.copyOf(), m2, n, priorVariance)
}
