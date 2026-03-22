# Color Detection Redesign Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the HSV + white-balance color detection pipeline with Bayesian Gaussian models in CIELAB space so classification converges with each confirmed face.

**Architecture:** A shared `LabConverter` converts sRGB pixels to CIELAB. `ColorModel` holds a Gaussian (mean + Welford M2) per color, updated at each face confirmation and manual override. `ColorDetector` classifies tiles by max-likelihood and `CubeFrameAnalyzer` extracts per-tile LAB medians from camera frames. All WB logic is removed.

**Tech Stack:** Kotlin, JUnit 5 (Jupiter), Google Truth — all new logic is Android-free (pure math) and fully testable on the JVM.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| **Create** | `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/LabConverter.kt` | sRGB ↔ CIELAB conversion (pure math, no Android) |
| **Create** | `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorModel.kt` | Bayesian Gaussian model for one color |
| **Rewrite** | `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorDetector.kt` | Classification + calibration using 6 ColorModels |
| **Rewrite** | `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt` | LAB tile extraction + temporal smoothing + StateFlows |
| **Modify** | `app/src/main/kotlin/com/xmelon/rubik_solver/ui/FaceColorOverrides.kt` | Add `calibrateTile` call on manual tile override |
| **Create** | `rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/LabConverterTest.kt` | Round-trip and known-color tests |
| **Create** | `rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorModelTest.kt` | Convergence and Welford correctness tests |
| **Create** | `rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorDetectorTest.kt` | Classification, calibration, checkpoint tests |

---

## Task 1: LabConverter — sRGB ↔ CIELAB

**Files:**
- Create: `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/LabConverter.kt`
- Create: `rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/LabConverterTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/LabConverterTest.kt
package com.xmelon.rubik_solver.vision

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

class LabConverterTest {

    // Helper: pack sRGB bytes into ARGB Int
    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun `pure white round-trips within 1 unit`() {
        val white = rgb(255, 255, 255)
        val lab = LabConverter.sRgbToLab(white)
        // CIELAB white: L*≈100, a*≈0, b*≈0
        assertThat(lab[0]).isWithin(1.5f).of(100f)
        assertThat(lab[1]).isWithin(1.5f).of(0f)
        assertThat(lab[2]).isWithin(1.5f).of(0f)
        val back = LabConverter.labToSRgb(lab)
        assertThat((back shr 16) and 0xFF).isWithin(2).of(255)
        assertThat((back shr 8)  and 0xFF).isWithin(2).of(255)
        assertThat( back         and 0xFF).isWithin(2).of(255)
    }

    @Test fun `pure black round-trips`() {
        val black = rgb(0, 0, 0)
        val lab = LabConverter.sRgbToLab(black)
        assertThat(lab[0]).isWithin(1f).of(0f)
        val back = LabConverter.labToSRgb(lab)
        assertThat((back shr 16) and 0xFF).isWithin(2).of(0)
    }

    @Test fun `red sRGB produces positive a-star`() {
        val red = rgb(220, 30, 30)
        val lab = LabConverter.sRgbToLab(red)
        // Red has positive a* in CIELAB
        assertThat(lab[1]).isGreaterThan(30f)
    }

    @Test fun `blue sRGB produces negative b-star`() {
        val blue = rgb(30, 30, 200)
        val lab = LabConverter.sRgbToLab(blue)
        assertThat(lab[2]).isLessThan(-20f)
    }

    @Test fun `yellow sRGB produces positive b-star`() {
        val yellow = rgb(220, 200, 20)
        val lab = LabConverter.sRgbToLab(yellow)
        assertThat(lab[2]).isGreaterThan(40f)
    }

    @Test fun `arbitrary color round-trips within 2 units per channel`() {
        val color = rgb(100, 150, 80)
        val lab = LabConverter.sRgbToLab(color)
        val back = LabConverter.labToSRgb(lab)
        assertThat((back shr 16) and 0xFF).isWithin(2).of(100)
        assertThat((back shr 8)  and 0xFF).isWithin(2).of(150)
        assertThat( back         and 0xFF).isWithin(2).of(80)
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```
cd C:\Users\anton\Documents\Rubik
./gradlew :rubik-vision:test --tests "*.LabConverterTest" 2>&1 | tail -20
```

Expected: compilation error — `LabConverter` not found.

- [ ] **Step 3: Implement LabConverter**

```kotlin
// rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/LabConverter.kt
package com.xmelon.rubik_solver.vision

import kotlin.math.abs
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
        if (t > DELTA * DELTA * DELTA) Math.cbrt(t.toDouble()).toFloat()
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
```

- [ ] **Step 4: Run tests — verify all pass**

```
./gradlew :rubik-vision:test --tests "*.LabConverterTest"
```

Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/LabConverter.kt \
        rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/LabConverterTest.kt
git commit -m "feat(vision): add LabConverter — sRGB ↔ CIELAB pure-math utility"
```

---

## Task 2: ColorModel — Bayesian Gaussian

**Files:**
- Create: `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorModel.kt`
- Create: `rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorModelTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorModelTest.kt
package com.xmelon.rubik_solver.vision

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ColorModelTest {

    @Test fun `starts with prior variance before MIN_SAMPLES`() {
        val m = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        assertThat(m.variance).isEqualTo(ColorModel.DEFAULT_PRIOR_VARIANCE)
    }

    @Test fun `mean converges toward repeated sample`() {
        val m = ColorModel(mean = floatArrayOf(0f, 0f, 0f))
        val target = floatArrayOf(80f, 10f, -30f)
        repeat(30) { m.update(target) }
        assertThat(m.mean[0]).isWithin(0.5f).of(80f)
        assertThat(m.mean[1]).isWithin(0.5f).of(10f)
        assertThat(m.mean[2]).isWithin(0.5f).of(-30f)
    }

    @Test fun `variance transitions from prior to Welford after MIN_SAMPLES`() {
        val m = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        // Feed MIN_SAMPLES + 1 identical samples — Welford variance should be ~0
        repeat((ColorModel.MIN_SAMPLES + 1).toInt()) {
            m.update(floatArrayOf(50f, 0f, 0f))
        }
        // After MIN_SAMPLES, variance comes from Welford M2/n, not prior
        assertThat(m.variance).isLessThan(ColorModel.DEFAULT_PRIOR_VARIANCE)
    }

    @Test fun `variance with scattered samples is greater than with tight samples`() {
        val tight = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        val spread = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        repeat(20) { tight.update(floatArrayOf(50f, 0f, 0f)) }
        // Scattered: samples alternate ±30 from mean
        repeat(20) { i ->
            val sign = if (i % 2 == 0) 1f else -1f
            spread.update(floatArrayOf(50f + 30f * sign, 0f, 0f))
        }
        assertThat(spread.variance).isGreaterThan(tight.variance)
    }

    @Test fun `score is lower for matching sample than distant sample`() {
        val m = ColorModel(mean = floatArrayOf(80f, -5f, 65f))  // Yellow prior
        val yellowLike = floatArrayOf(82f, -4f, 63f)
        val blueLike   = floatArrayOf(32f, 14f, -52f)
        assertThat(m.score(yellowLike)).isLessThan(m.score(blueLike))
    }

    @Test fun `weight=3 update moves mean 3x faster than weight=1`() {
        val m1 = ColorModel(mean = floatArrayOf(0f, 0f, 0f))
        val m3 = ColorModel(mean = floatArrayOf(0f, 0f, 0f))
        val target = floatArrayOf(100f, 0f, 0f)
        m1.update(target, weight = 1f)
        m3.update(target, weight = 3f)
        // m3 should have moved more toward target
        assertThat(m3.mean[0]).isGreaterThan(m1.mean[0])
    }

    @Test fun `copy is independent of original`() {
        val m = ColorModel(mean = floatArrayOf(50f, 10f, -20f))
        m.update(floatArrayOf(60f, 15f, -15f))
        val meanAfterFirstUpdate = m.mean[0]
        val copy = m.copy()
        m.update(floatArrayOf(100f, 100f, 100f))
        // copy's mean should be frozen at the point of copy, not follow m
        assertThat(copy.mean[0]).isWithin(0.01f).of(meanAfterFirstUpdate)
        assertThat(copy.n).isLessThan(m.n)
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```
./gradlew :rubik-vision:test --tests "*.ColorModelTest"
```

Expected: compilation error — `ColorModel` not found.

- [ ] **Step 3: Implement ColorModel**

```kotlin
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
        const val DEFAULT_PRIOR_VARIANCE = 625f   // = 25²; wide prior over CIELAB space
        const val MIN_SAMPLES = 5f                // Welford variance used only after this many samples
    }

    /** Effective variance: Welford estimate once we have enough data, prior otherwise. */
    val variance: Float get() = if (n > MIN_SAMPLES) (m2 / n).coerceAtLeast(1f) else priorVariance

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
```

- [ ] **Step 4: Run tests — verify all pass**

```
./gradlew :rubik-vision:test --tests "*.ColorModelTest"
```

Expected: 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorModel.kt \
        rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorModelTest.kt
git commit -m "feat(vision): add ColorModel — Bayesian Gaussian in CIELAB with Welford online update"
```

---

## Task 3: ColorDetector rewrite

**Files:**
- Rewrite: `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorDetector.kt`
- Create: `rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorDetectorTest.kt`

**Note:** The existing `ColorDetector.kt` is being fully replaced. Read it first (`git diff HEAD` before overwriting helps if debugging is needed).

- [ ] **Step 1: Write failing tests**

```kotlin
// rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorDetectorTest.kt
package com.xmelon.rubik_solver.vision

import com.google.common.truth.Truth.assertThat
import com.xmelon.rubik_solver.model.CubeColor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ColorDetectorTest {

    private lateinit var detector: ColorDetector

    @BeforeEach fun setup() { detector = ColorDetector() }

    // Helper: pack sRGB bytes into ARGB Int
    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // Helper: known "standard" sRGB values close to Rubik's pigments
    // These need not be exact — just close enough for the prior to win
    private val stdWhite  = rgb(240, 240, 230)
    private val stdYellow = rgb(200, 180, 20)
    private val stdRed    = rgb(180, 30, 30)
    private val stdOrange = rgb(200, 80, 10)
    private val stdBlue   = rgb(20, 50, 180)
    private val stdGreen  = rgb(20, 150, 50)

    @Test fun `priors classify standard Rubik colors correctly`() {
        assertThat(detector.classify(LabConverter.sRgbToLab(stdWhite)).first).isEqualTo(CubeColor.WHITE)
        assertThat(detector.classify(LabConverter.sRgbToLab(stdYellow)).first).isEqualTo(CubeColor.YELLOW)
        assertThat(detector.classify(LabConverter.sRgbToLab(stdRed)).first).isEqualTo(CubeColor.RED)
        assertThat(detector.classify(LabConverter.sRgbToLab(stdOrange)).first).isEqualTo(CubeColor.ORANGE)
        assertThat(detector.classify(LabConverter.sRgbToLab(stdBlue)).first).isEqualTo(CubeColor.BLUE)
        assertThat(detector.classify(LabConverter.sRgbToLab(stdGreen)).first).isEqualTo(CubeColor.GREEN)
    }

    @Test fun `confidence is in 0-1 range`() {
        val lab = LabConverter.sRgbToLab(stdRed)
        val (_, conf) = detector.classify(lab)
        assertThat(conf).isIn(0f..1f)
    }

    @Test fun `calibrateFace with 9 tiles shifts models toward confirmed colors`() {
        // Feed a blue-looking tile as YELLOW — model should shift
        val blueLookingRgbs = IntArray(9) { stdBlue }
        val allYellow = List(9) { CubeColor.YELLOW }
        detector.calibrateFace(blueLookingRgbs, allYellow)
        // After calibration, the YELLOW model mean should have moved toward blue-LAB
        // The exact value doesn't matter — what matters is classify() can still run
        val (color, _) = detector.classify(LabConverter.sRgbToLab(stdBlue))
        // color may now be YELLOW since we trained it that way
        assertThat(color).isEqualTo(CubeColor.YELLOW)
    }

    @Test fun `calibrateTile with weight-3 affects classification quickly`() {
        // Train BLUE model with 1 orange-looking tile at weight 3
        detector.calibrateTile(stdOrange, CubeColor.BLUE)
        // BLUE model has shifted toward orange — now orange-LAB should score well for BLUE
        val lab = LabConverter.sRgbToLab(stdOrange)
        val scores = CubeColor.entries.associateWith { c ->
            detector.scoreFor(c, lab)  // internal test hook — see implementation note
        }
        assertThat(scores[CubeColor.BLUE]).isLessThan(scores[CubeColor.GREEN]!!)
    }

    @Test fun `colorCycle advances through all 6 colors without repeating`() {
        val lab = stdRed
        val seen = mutableSetOf<CubeColor>()
        var cur = detector.classify(LabConverter.sRgbToLab(lab)).first
        repeat(6) {
            seen.add(cur)
            cur = detector.colorCycle(lab, cur)
        }
        assertThat(seen).hasSize(6)
    }

    @Test fun `saveCheckpoint and restoreCheckpoint rolls back model state`() {
        detector.saveCheckpoint()
        // Corrupt the models with garbage data
        val garbageRgbs = IntArray(9) { rgb(128, 128, 128) }
        detector.calibrateFace(garbageRgbs, List(9) { CubeColor.RED })
        detector.restoreCheckpoint()
        // After restore, priors should classify correctly again
        assertThat(detector.classify(LabConverter.sRgbToLab(stdWhite)).first).isEqualTo(CubeColor.WHITE)
    }

    @Test fun `resetCalibration restores prior classification`() {
        val garbageRgbs = IntArray(9) { rgb(128, 128, 128) }
        detector.calibrateFace(garbageRgbs, List(9) { CubeColor.RED })
        detector.resetCalibration()
        assertThat(detector.classify(LabConverter.sRgbToLab(stdWhite)).first).isEqualTo(CubeColor.WHITE)
    }

    @Test fun `convergence — 54 samples (6 faces × 9 tiles) converges all models`() {
        // Synthetic "real" pigment LABs — slightly different from priors
        val realPigments = mapOf(
            CubeColor.WHITE  to rgb(235, 230, 215),
            CubeColor.YELLOW to rgb(210, 185, 15),
            CubeColor.RED    to rgb(185, 25, 25),
            CubeColor.ORANGE to rgb(205, 75, 8),
            CubeColor.BLUE   to rgb(15, 45, 190),
            CubeColor.GREEN  to rgb(15, 145, 45)
        )
        // Simulate 6 face confirmations (each face has a mix of colors)
        val faceColors = listOf(
            List(9) { CubeColor.WHITE },
            List(9) { CubeColor.YELLOW },
            List(9) { CubeColor.RED },
            List(9) { CubeColor.ORANGE },
            List(9) { CubeColor.BLUE },
            List(9) { CubeColor.GREEN }
        )
        for (colors in faceColors) {
            val rgbs = IntArray(9) { realPigments[colors[it]]!! }
            detector.calibrateFace(rgbs, colors)
        }
        // After training, each color should classify correctly
        for ((color, rgb) in realPigments) {
            val (classified, _) = detector.classify(LabConverter.sRgbToLab(rgb))
            assertThat(classified).isEqualTo(color)
        }
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```
./gradlew :rubik-vision:test --tests "*.ColorDetectorTest"
```

Expected: compilation errors — methods not found in old ColorDetector.

- [ ] **Step 3: Rewrite ColorDetector.kt**

```kotlin
// rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorDetector.kt
package com.xmelon.rubik_solver.vision

import com.xmelon.rubik_solver.model.CubeColor
import kotlin.math.abs
import kotlin.math.max

private fun logd(msg: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.d("RubikSolver", msg())
}

/**
 * Color classifier using Bayesian Gaussian models in CIELAB space.
 *
 * Each of the 6 CubeColors has a [ColorModel] (mean + Welford variance).
 * Models start as standard Rubik's pigment priors and converge with each
 * [calibrateFace] and [calibrateTile] call. No white-balance step.
 *
 * Public interface is backward-compatible with the previous implementation.
 */
class ColorDetector {

    private var models: MutableMap<CubeColor, ColorModel> = buildPriors()

    // ---- Checkpoint / Undo ----
    private data class Snapshot(val models: Map<CubeColor, ColorModel>)
    private val history = java.util.Stack<Snapshot>()

    companion object {
        // Standard Rubik's pigments in CIELAB (D65 white point).
        // These are reasonable starting priors; production calibration will tune them.
        internal val PRIORS = mapOf(
            CubeColor.WHITE  to floatArrayOf( 95f,   0f,   3f),
            CubeColor.YELLOW to floatArrayOf( 82f,  -5f,  65f),
            CubeColor.RED    to floatArrayOf( 41f,  55f,  38f),
            CubeColor.ORANGE to floatArrayOf( 58f,  38f,  52f),
            CubeColor.BLUE   to floatArrayOf( 32f,  14f, -52f),
            CubeColor.GREEN  to floatArrayOf( 48f, -55f,  32f)
        )

        private fun buildPriors(): MutableMap<CubeColor, ColorModel> =
            PRIORS.entries.associateTo(LinkedHashMap()) { (c, lab) ->
                c to ColorModel(mean = lab.copyOf())
            }
    }

    // =====================================================================
    //  Classification
    // =====================================================================

    /**
     * Classifies a CIELAB value. Returns (color, confidence in [0,1]).
     *
     * Confidence = normalized margin between top-2 NLL scores.
     * Values < 0.15 indicate genuine ambiguity.
     * Near-tie (margin < 2.0) hard-sets confidence to 0.
     */
    fun classify(lab: FloatArray): Pair<CubeColor, Float> {
        val sorted = CubeColor.entries.map { it to models[it]!!.score(lab) }
            .sortedBy { it.second }
        val (bestColor, bestScore) = sorted[0]
        val secondScore = sorted[1].second
        val margin = secondScore - bestScore   // always >= 0
        if (margin < 2.0f) return bestColor to 0f
        val scoreMax = max(abs(bestScore), abs(secondScore)) + 1e-6f
        val confidence = (margin / scoreMax).coerceIn(0f, 1f)
        return bestColor to confidence
    }

    /** Exposes NLL score for a specific color — used in tests and colorCycle. */
    internal fun scoreFor(color: CubeColor, lab: FloatArray): Float =
        models[color]!!.score(lab)

    // =====================================================================
    //  Calibration
    // =====================================================================

    /**
     * Updates models from all 9 confirmed tiles.
     * Signature matches existing AppViewModel call site:
     *   calibrateFace(liveRgbs: IntArray, correctedColors: List<CubeColor>)
     */
    fun calibrateFace(tileRgbs: IntArray, confirmedColors: List<CubeColor>) {
        if (tileRgbs.size != 9 || confirmedColors.size != 9) return
        for (i in 0..8) {
            models[confirmedColors[i]]!!.update(LabConverter.sRgbToLab(tileRgbs[i]), weight = 1f)
        }
        logd { "CAL calibrateFace ${calibrationStr()}" }
    }

    /**
     * Updates the model for one manually overridden tile. Weight=3 for strong signal —
     * a user correction should have meaningful impact on the next face.
     */
    fun calibrateTile(tileRgb: Int, correctedColor: CubeColor) {
        models[correctedColor]!!.update(LabConverter.sRgbToLab(tileRgb), weight = 3f)
        logd { "CAL calibrateTile $correctedColor" }
    }

    // =====================================================================
    //  Manual Cycle
    // =====================================================================

    /**
     * Returns the next color in NLL-ranked order from [wbRgb].
     * Signature unchanged — used for tile tap cycling in FaceColorOverrides.
     */
    fun colorCycle(wbRgb: Int, currentColor: CubeColor): CubeColor {
        val lab = LabConverter.sRgbToLab(wbRgb)
        val ranked = CubeColor.entries.sortedBy { models[it]!!.score(lab) }
        val idx = ranked.indexOf(currentColor)
        return ranked[(idx + 1) % ranked.size]
    }

    // =====================================================================
    //  Checkpoint / Undo
    // =====================================================================

    /** Saves current model state before a face confirmation (enables undo). */
    fun saveCheckpoint() {
        if (history.size >= 6) history.removeAt(0)
        history.push(Snapshot(models.mapValues { it.value.copy() }))
        logd { "CAL saveCheckpoint [${history.size}]" }
    }

    /** Restores models to state before the last confirmation. */
    fun restoreCheckpoint() {
        if (history.isNotEmpty()) {
            models = history.pop().models.toMutableMap() as MutableMap<CubeColor, ColorModel>
            logd { "CAL restoreCheckpoint [${history.size}]" }
        } else {
            logd { "CAL restoreCheckpoint EMPTY → resetCalibration" }
            resetCalibration()
        }
    }

    /** Resets all models to priors. Called by AppViewModel.restartScan(). */
    fun resetCalibration() {
        models = buildPriors()
        history.clear()
        logd { "CAL resetCalibration" }
    }

    /** Returns a human-readable calibration summary for debug logging. */
    fun calibrationStr(): String =
        CubeColor.entries.joinToString(" ") { c ->
            val m = models[c]!!
            "${c.name}[n=%.1f,v=%.0f]".format(m.n, m.variance)
        }
}
```

- [ ] **Step 4: Run tests — verify all pass**

```
./gradlew :rubik-vision:test --tests "*.ColorDetectorTest"
```

Expected: all 8 tests pass. If `calibrateTile` test fails due to `scoreFor` visibility, check that the method is marked `internal`.

- [ ] **Step 5: Run all rubik-vision tests to confirm no regressions**

```
./gradlew :rubik-vision:test
```

Expected: all tests pass (including `ScanOrchestratorTest`).

- [ ] **Step 6: Commit**

```bash
git add rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorDetector.kt \
        rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorDetectorTest.kt
git commit -m "feat(vision): rewrite ColorDetector — Bayesian Gaussian in CIELAB, remove WB"
```

---

## Task 4: CubeFrameAnalyzer rewrite

**Files:**
- Rewrite: `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`

No new unit tests for this class — it depends on `ImageProxy` / `Bitmap` (Android) and is covered by the integration (on-device) test path. The focus here is correctness of the rewrite.

- [ ] **Step 1: Read current CubeFrameAnalyzer.kt before overwriting**

Keep the current file open in a diff view or take a note of the following parts to preserve unchanged:
- `visibleRegion()` — unchanged
- `buildDebugBitmap()` — changed slightly: remove `colorDetector.applyWb(p)` call
- `analyze()` outer structure — unchanged
- `resetTemporalBuffers()` — updated to clear LAB buffers
- `debugMode`, `debugBitmap` StateFlow — unchanged
- `previewWidth`, `previewHeight` — unchanged
- `expectedCenterColor` — unchanged

- [ ] **Step 2: Rewrite CubeFrameAnalyzer.kt**

```kotlin
// rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt
package com.xmelon.rubik_solver.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.xmelon.rubik_solver.model.CubeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Analyzes live camera frames to extract 9 facelet colors.
 *
 * Pipeline:
 *   1. Extract per-tile LAB median (2×2-stride pixel sampling).
 *   2. Temporal median smoothing (ring buffer of 8 LAB values per tile).
 *   3. Classify each tile using ColorDetector (Bayesian Gaussian in CIELAB).
 *   4. Detect center stability via confidence gate + consecutive frame count.
 *
 * No white-balance step. Set [previewWidth] / [previewHeight] to the pixel
 * dimensions of the PreviewView.
 */
class CubeFrameAnalyzer : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "CubeFrameAnalyzer"
        private const val TEMPORAL_BUFFER_SIZE = 8
        private const val CENTER_STABLE_FRAMES = 6
        private const val CENTER_STABLE_CONFIDENCE = 0.15f
    }

    /** Per-session color detector. Owned here so each scan session gets isolated state. */
    val colorDetector = ColorDetector()

    /** Pixel size of the PreviewView — set from the View's onLayoutChange. */
    @Volatile var previewWidth: Int = 0
    @Volatile var previewHeight: Int = 0

    /** Expected center color for the current face. Set by AppViewModel. */
    private val _expectedCenterColor = AtomicReference<CubeColor?>(null)
    var expectedCenterColor: CubeColor?
        get() = _expectedCenterColor.get()
        set(value) { _expectedCenterColor.set(value) }

    // ---- StateFlows ----
    private val _detectedColors = MutableStateFlow<List<CubeColor>>(emptyList())
    val detectedColors = _detectedColors.asStateFlow()

    private val _detectedRgbs = MutableStateFlow<IntArray>(IntArray(0))
    val detectedRgbs = _detectedRgbs.asStateFlow()

    /** LAB-derived sRGB per tile — same semantics as previous detectedWbRgbs. */
    private val _detectedWbRgbs = MutableStateFlow<IntArray>(IntArray(0))
    val detectedWbRgbs = _detectedWbRgbs.asStateFlow()

    /** Per-tile confidence in [0,1]. < 0.15 = uncertain. */
    private val _confidence = MutableStateFlow<List<Float>>(emptyList())
    val confidence = _confidence.asStateFlow()

    @Volatile var debugMode: Boolean = false
    private val _debugBitmap = MutableStateFlow<Bitmap?>(null)
    val debugBitmap = _debugBitmap.asStateFlow()

    // ---- Temporal LAB ring buffers ----
    private val tileLabRingBuffers: Array<ArrayDeque<FloatArray>> = Array(9) { ArrayDeque() }

    // Reusable pixel buffer
    private var pixelBuffer = IntArray(0)

    // Scratch buffer for per-channel LAB sort.
    // Tiles can have hundreds of sampled pixels — starts at 512, grows on demand (same pattern
    // as pixelBuffer). Never shrinks so it stabilizes at the first large tile encountered.
    private var labSortBuf = FloatArray(512)

    // Rotation matrix cache
    private var cachedRotationDegrees = Int.MIN_VALUE
    private var cachedRotationMatrix = Matrix()

    // Center stability
    @Volatile private var consecutiveCenterInRange = 0
    private val _centerStable = MutableStateFlow(false)
    val centerStable = _centerStable.asStateFlow()

    @Volatile private var lastLoggedColors: List<CubeColor> = emptyList()

    /**
     * Clears temporal LAB buffers and resets center stability.
     * Call when the user switches to a new face.
     */
    fun resetTemporalBuffers() {
        tileLabRingBuffers.forEach { it.clear() }
        lastLoggedColors = emptyList()
        consecutiveCenterInRange = 0
        _centerStable.value = false
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = try {
                image.toBitmap()
            } catch (e: Exception) {
                Log.e(TAG, "toBitmap() failed", e)
                return
            }
            val degrees = image.imageInfo.rotationDegrees
            val rotatedBitmap = if (degrees != 0) {
                if (degrees != cachedRotationDegrees) {
                    cachedRotationMatrix = Matrix().apply { postRotate(degrees.toFloat()) }
                    cachedRotationDegrees = degrees
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, cachedRotationMatrix, true)
                    .also { bitmap.recycle() }
            } else {
                bitmap
            }
            try {
                val (colors, rgbs) = extractGridColors(rotatedBitmap)
                _detectedColors.value = colors
                _detectedRgbs.value = rgbs
            } catch (e: Exception) {
                Log.e(TAG, "extractGridColors() failed", e)
            } finally {
                rotatedBitmap.recycle()
            }
        } finally {
            image.close()
        }
    }

    private fun visibleRegion(camW: Int, camH: Int): IntArray {
        val pW = previewWidth; val pH = previewHeight
        if (pW <= 0 || pH <= 0) return intArrayOf(0, 0, camW, camH)
        val camAspect = camW.toFloat() / camH
        val prevAspect = pW.toFloat() / pH
        return if (camAspect > prevAspect) {
            val visW = (camH * prevAspect).toInt()
            intArrayOf((camW - visW) / 2, 0, visW, camH)
        } else {
            val visH = (camW / prevAspect).toInt()
            intArrayOf(0, (camH - visH) / 2, camW, visH)
        }
    }

    private fun extractGridColors(bitmap: Bitmap): Pair<List<CubeColor>, IntArray> {
        val (rx, ry, rw, rh) = visibleRegion(bitmap.width, bitmap.height)
        val boxSize = (minOf(rw, rh) * 0.66f).toInt()
        val startX = rx + (rw - boxSize) / 2
        val startY = ry + (rh - boxSize) / 2
        val cellSize = boxSize / 3
        val inset = (cellSize * 0.15f).toInt().coerceAtLeast(2)

        // 1. Extract raw LAB per tile
        val rawLab = Array(9) { FloatArray(3) }
        for (row in 0..2) {
            for (col in 0..2) {
                val x0 = startX + col * cellSize + inset
                val y0 = startY + row * cellSize + inset
                val x1 = startX + (col + 1) * cellSize - inset
                val y1 = startY + (row + 1) * cellSize - inset
                rawLab[row * 3 + col] = extractTileLab(bitmap, x0, y0, x1, y1)
            }
        }

        // 2. Temporal median smoothing in LAB space
        val smoothedLab = Array(9) { FloatArray(3) }
        for (i in 0..8) {
            val buf = tileLabRingBuffers[i]
            buf.addLast(rawLab[i].copyOf())
            if (buf.size > TEMPORAL_BUFFER_SIZE) buf.removeFirst()
            smoothedLab[i] = labMedian(buf)
        }

        // 3. Classify + build output arrays
        val colors = ArrayList<CubeColor>(9)
        val confidences = FloatArray(9)
        val rgbs = IntArray(9)
        for (i in 0..8) {
            val (color, conf) = colorDetector.classify(smoothedLab[i])
            colors.add(color)
            confidences[i] = conf
            rgbs[i] = LabConverter.labToSRgb(smoothedLab[i])
        }

        // 4. Center stability: classified == expected AND confidence gate AND N consecutive frames
        val expected = expectedCenterColor
        val centerMatch = expected != null
                && colors[4] == expected
                && confidences[4] >= CENTER_STABLE_CONFIDENCE
        consecutiveCenterInRange = if (centerMatch)
            minOf(consecutiveCenterInRange + 1, CENTER_STABLE_FRAMES)
        else 0
        val nowStable = consecutiveCenterInRange >= CENTER_STABLE_FRAMES
        if (nowStable != _centerStable.value) _centerStable.value = nowStable

        // 5. Emit StateFlows
        _detectedWbRgbs.value = rgbs
        _confidence.value = confidences.toList()

        // 6. Debug logging (throttled)
        if (colors != lastLoggedColors) {
            lastLoggedColors = colors
            Log.d(TAG, "SCAN_DETECT exp=${expected?.name} center=${colors.getOrNull(4)?.name}" +
                " conf=%.2f stable=$nowStable cal=${colorDetector.calibrationStr()}".format(confidences[4]) +
                " ${colors.map { it.label }}")
        }

        // 7. Debug bitmap
        _debugBitmap.value = if (debugMode)
            buildDebugBitmap(bitmap, startX, startY, boxSize, cellSize, inset, colors)
        else null

        return colors to rgbs
    }

    /**
     * Extracts a single tile's representative LAB value as the per-channel median
     * of all 2×2-stride-sampled pixels in the tile's inset region.
     * Near-black pixels (likely lens dirt or border) are excluded.
     * Fallback: center pixel if fewer than 3 pixels survive the filter.
     */
    private fun extractTileLab(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val w = (x1 - x0).coerceAtLeast(1)
        val h = (y1 - y0).coerceAtLeast(1)
        val needed = w * h
        if (pixelBuffer.size < needed) pixelBuffer = IntArray(needed)
        bitmap.getPixels(pixelBuffer, 0, w, x0, y0, w, h)
        val labs = ArrayList<FloatArray>((w / 2 + 1) * (h / 2 + 1))
        for (row in 0 until h step 2) {
            for (col in 0 until w step 2) {
                val p = pixelBuffer[row * w + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8)  and 0xFF
                val b =  p         and 0xFF
                if (r + g + b > 30) labs.add(LabConverter.sRgbToLab(p))
            }
        }
        if (labs.size < 3) {
            // Fallback to center pixel
            val cx = w / 2; val cy = h / 2
            return LabConverter.sRgbToLab(pixelBuffer[cy * w + cx])
        }
        return labMedian(labs)
    }

    /** Per-channel median of a list of LAB triplets. */
    private fun labMedian(labs: List<FloatArray>): FloatArray {
        val n = labs.size
        val mid = n / 2
        if (labSortBuf.size < n) labSortBuf = FloatArray(n)
        val result = FloatArray(3)
        for (ch in 0..2) {
            for (i in 0 until n) labSortBuf[i] = labs[i][ch]
            labSortBuf.sort(0, n)
            result[ch] = labSortBuf[mid]
        }
        return result
    }

    private fun buildDebugBitmap(
        src: Bitmap, startX: Int, startY: Int,
        boxSize: Int, cellSize: Int, inset: Int,
        colors: List<CubeColor>
    ): Bitmap {
        val gridPixels = IntArray(boxSize * boxSize)
        src.getPixels(gridPixels, 0, boxSize, startX, startY, boxSize, boxSize)
        val outPixels = IntArray(boxSize * boxSize)

        for (tileRow in 0..2) {
            for (tileCol in 0..2) {
                val lx0 = tileCol * cellSize + inset
                val ly0 = tileRow * cellSize + inset
                val lx1 = (tileCol + 1) * cellSize - inset
                val ly1 = (tileRow + 1) * cellSize - inset
                if (lx1 <= lx0 || ly1 <= ly0) continue
                // Paint all sampled pixels with full opacity using the raw camera color
                for (py in ly0 until ly1 step 2) {
                    for (px in lx0 until lx1 step 2) {
                        val p = gridPixels[py * boxSize + px]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8)  and 0xFF
                        val b =  p         and 0xFF
                        if (r + g + b > 30) {
                            outPixels[py * boxSize + px] = (0xFF shl 24) or (p and 0x00FFFFFF)
                        }
                    }
                }
            }
        }

        val bmp = Bitmap.createBitmap(boxSize, boxSize, Bitmap.Config.ARGB_8888)
        bmp.setPixels(outPixels, 0, boxSize, 0, 0, boxSize, boxSize)

        val cvs = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        for (tileRow in 0..2) {
            for (tileCol in 0..2) {
                val idx = tileRow * 3 + tileCol
                val letter = colors.getOrNull(idx)?.name?.first()?.toString() ?: "?"
                val cx = (tileCol * cellSize + (tileCol + 1) * cellSize) / 2f
                val cy = (tileRow * cellSize + (tileRow + 1) * cellSize) / 2f
                paint.textSize = cellSize * 0.38f
                val ty = cy + paint.textSize * 0.36f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = paint.textSize * 0.18f
                paint.color = android.graphics.Color.BLACK
                cvs.drawText(letter, cx, ty, paint)
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.WHITE
                cvs.drawText(letter, cx, ty, paint)
            }
        }
        return bmp
    }
}
```

- [ ] **Step 3: Build the rubik-vision module**

```
./gradlew :rubik-vision:assembleDebug
```

Expected: BUILD SUCCESSFUL. Fix any compilation errors before proceeding.

- [ ] **Step 4: Run all rubik-vision tests**

```
./gradlew :rubik-vision:test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt
git commit -m "feat(vision): rewrite CubeFrameAnalyzer — LAB tile median, confidence-gated stability"
```

---

## Task 5: Add calibrateTile call site in FaceColorOverrides

**Files:**
- Read: `app/src/main/kotlin/com/xmelon/rubik_solver/ui/FaceColorOverrides.kt`
- Read: `app/src/main/kotlin/com/xmelon/rubik_solver/ui/MainScreen.kt`
- Modify: whichever file contains the tile tap / color override handler

**Goal:** When the user taps a tile and cycles its color, call `analyzer.colorDetector.calibrateTile(tileRgb, newColor)` so the manual correction improves future frames.

- [ ] **Step 1: Read FaceColorOverrides.kt and MainScreen.kt**

Find the function/lambda where `colorOverrides` is updated — search for `colorCycle` or `colorOverrides`. There will be a line like:

```kotlin
colorOverrides = colorOverrides + (tileIndex to newColor)
```

or similar. The tile RGB at that point is available from `liveRgbs[tileIndex]` (which flows from `analyzer.detectedRgbs`).

- [ ] **Step 2: Add calibrateTile call alongside the override**

In the tile tap handler, immediately after the line that updates `colorOverrides`, add:

```kotlin
// Feed the correction into the Bayesian model (weight=3 for fast convergence)
val tileRgb = liveRgbs.getOrElse(tileIndex) { 0 }
if (tileRgb != 0) analyzer.colorDetector.calibrateTile(tileRgb, newColor)
```

Where `liveRgbs` is the current `detectedRgbs` value and `analyzer` is `AppViewModel.analyzer`. Adjust variable names to match the actual code.

- [ ] **Step 3: Build the full app**

```
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Fix any compilation errors.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/xmelon/rubik_solver/ui/FaceColorOverrides.kt
# or MainScreen.kt — whichever file was modified
git commit -m "feat(app): feed manual tile overrides into Bayesian color model"
```

---

## Task 6: Final build and test

- [ ] **Step 1: Run all module tests**

```
./gradlew :rubik-vision:test :rubik-model:test
```

Expected: all pass.

- [ ] **Step 2: Build full app**

```
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL with no warnings about missing methods.

- [ ] **Step 3: Install on device**

```
./gradlew :app:installDebug
```

- [ ] **Step 4: Smoke test on device**

On device:
1. Start a scan. Verify live colors appear on the 3D overlay.
2. Hold the white face toward the camera — center should stabilize without needing a white-face calibration step.
3. Confirm the first face. Confirm the second face. Verify that classification feels more confident on face 3+ (colors stabilize faster).
4. Tap a tile to override its color — verify the app doesn't crash.
5. Use the back button during scan — verify undo works and rescan shows correct colors.
6. Tap Restart — verify a fresh scan starts cleanly.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat(vision): complete color detection redesign — Bayesian Gaussian in CIELAB

Replaces HSV + white-balance pipeline with per-color Gaussian models in
CIELAB space. Each confirmed face and manual override updates the models
via Welford's online algorithm (weight=3 for overrides). Classification
converges with each face confirmation. No white-balance step required."
```
