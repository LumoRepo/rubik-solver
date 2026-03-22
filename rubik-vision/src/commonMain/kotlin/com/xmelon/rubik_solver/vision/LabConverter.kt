package com.xmelon.rubik_solver.vision

import kotlin.math.pow

/**
 * Converts between sRGB and CIELAB (D65 white point).
 * Pure math — no Android API dependencies.
 */
object LabConverter {

    /** Unpacks an ARGB Int and converts to CIELAB [L*, a*, b*]. */
    fun sRgbToLab(rgb: Int): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8)  and 0xFF) / 255f
        val b = ( rgb         and 0xFF) / 255f
        return linearRgbToLab(sRgbToLinear(r), sRgbToLinear(g), sRgbToLinear(b))
    }

    /** Converts CIELAB back to a fully-opaque ARGB Int. */
    fun labToSRgb(lab: FloatArray): Int {
        val (lr, lg, lb) = labToLinearRgb(lab)
        val r = (linearToSRgb(lr) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (linearToSRgb(lg) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (linearToSRgb(lb) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // --- sRGB gamma ---

    private fun sRgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f
        else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSRgb(c: Float): Float {
        val cl = c.coerceIn(0f, 1f)
        return if (cl <= 0.0031308f) cl * 12.92f
        else 1.055f * cl.pow(1f / 2.4f) - 0.055f
    }

    // --- Linear RGB → XYZ (sRGB D65) ---

    private fun linearRgbToXyz(r: Float, g: Float, b: Float): Triple<Float, Float, Float> = Triple(
        0.4124564f * r + 0.3575761f * g + 0.1804375f * b,
        0.2126729f * r + 0.7151522f * g + 0.0721750f * b,
        0.0193339f * r + 0.1191920f * g + 0.9503041f * b
    )

    // D65 white point
    private const val Xn = 0.95047f
    private const val Yn = 1.00000f
    private const val Zn = 1.08883f
    private const val DELTA = 6f / 29f

    private fun f(t: Float): Float =
        if (t > DELTA * DELTA * DELTA) t.toDouble().pow(1.0 / 3.0).toFloat()
        else t / (3f * DELTA * DELTA) + 4f / 29f

    private fun fInv(t: Float): Float =
        if (t > DELTA) t * t * t
        else 3f * DELTA * DELTA * (t - 4f / 29f)

    private fun linearRgbToLab(r: Float, g: Float, b: Float): FloatArray {
        val (x, y, z) = linearRgbToXyz(r, g, b)
        val fx = f(x / Xn); val fy = f(y / Yn); val fz = f(z / Zn)
        return floatArrayOf(
            116f * fy - 16f,
            500f * (fx - fy),
            200f * (fy - fz)
        )
    }

    // --- XYZ → Linear RGB (inverse of linearRgbToXyz) ---

    private fun labToLinearRgb(lab: FloatArray): Triple<Float, Float, Float> {
        val fy = (lab[0] + 16f) / 116f
        val fx = lab[1] / 500f + fy
        val fz = fy - lab[2] / 200f
        val x = fInv(fx) * Xn
        val y = fInv(fy) * Yn
        val z = fInv(fz) * Zn
        return Triple(
             3.2404542f * x - 1.5371385f * y - 0.4985314f * z,
            -0.9692660f * x + 1.8760108f * y + 0.0415560f * z,
             0.0556434f * x - 0.2040259f * y + 1.0572252f * z
        )
    }
}
