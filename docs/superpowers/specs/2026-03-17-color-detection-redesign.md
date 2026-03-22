# Color Detection Redesign — Bayesian Gaussian in CIELAB

**Date:** 2026-03-17
**Scope:** Full rewrite of `ColorDetector` and `CubeFrameAnalyzer`
**Goal:** Reliable convergence — each confirmed face improves future classification
**Constraint:** Public interface to `AppViewModel` and `ScanOrchestrator` unchanged

---

## Problem Statement

The current system is fragile under lighting variation because:

1. **HSV is not perceptually uniform** — a small camera white-balance shift causes large hue angle changes
2. **WB correction is a single point of failure** — anchored to one white face scan; if that's wrong, everything downstream is wrong
3. **Learning is shallow** — only center tiles drive calibration; manual overrides are dead ends; hue profiles use fixed lerp rates regardless of sample count
4. **No confidence signal** — classifier produces a color with no indication of certainty; user can't tell if a tile is certain or a guess

---

## Approach: Bayesian Gaussian Models in CIELAB

Each of the 6 `CubeColor` values gets a Gaussian model (mean + scalar variance) in CIELAB space. Classification is max-likelihood; each confirmed tile and manual override updates the relevant model via Welford's online algorithm. Variance narrows with data — the classifier becomes genuinely more certain over time. White balance is eliminated as a separate step.

---

## Architecture

### Boundaries

```
CubeFrameAnalyzer                    ColorDetector
─────────────────────────────────    ──────────────────────────────
Knows about: camera frames,          Knows about: colors, learning,
pixels, tiles, timing                classification, confidence

Responsibilities:                    Responsibilities:
  - Extract 9 tile LAB values          - Maintain 6 Gaussian models
    from each camera frame               (one per CubeColor)
  - Temporal median smoothing          - Classify a LAB value →
    (ring buffer, per tile)              CubeColor + confidence
  - Detect center tile stability       - Update models from confirmed
  - Emit StateFlows                      tiles + manual overrides
                                       - Checkpoint / restore / reset
                                       - colorCycle for manual tap
        ↓ calls                              ↑ called by
  colorDetector.classify(lab[9])       calibrateFace / calibrateTile
```

### What Is Eliminated

- `rGain`, `gGain`, `bGain` white balance gains
- White-face calibration step
- `calibrateWB()` method
- Adaptive saturation-gap white detection
- Confirmed hue profiles
- All HSV classification logic

### What Is Preserved (Public Interface)

- `detectedColors: StateFlow<List<CubeColor>>`
- `detectedRgbs: StateFlow<List<Int>>`
- `detectedWbRgbs: StateFlow<IntArray>` — now produced from `labToSRgb(smoothed[tile])`; same semantics, different source. Type difference vs `detectedRgbs` (`IntArray` vs `List<Int>`) matches existing codebase and is intentional.
- `centerStable: StateFlow<Boolean>`
- `calibrateFace(tileRgbs: IntArray, confirmedColors: List<CubeColor>)` — parameter order and types match existing `AppViewModel.confirmFace` call site
- `colorCycle(rgb: Int, current: CubeColor): CubeColor`
- `saveCheckpoint()` — snapshots all 6 ColorModel states
- `restoreCheckpoint()` — restores last snapshot; called by rescan/undo path
- `resetCalibration()` — resets all 6 models to priors; called by `AppViewModel.restartScan()`

### New Additive API

- `confidence: StateFlow<List<Float>>` — per-tile confidence in [0, 1]; UI can optionally use this to highlight uncertain tiles
- `calibrateTile(tileRgb: Int, correctedColor: CubeColor)` — called from the tile tap override handler in `MainScreen`; updates the model for `correctedColor` with weight 3. The RGB value comes from `detectedRgbs` at override time (accessible in `AppViewModel` via the existing `liveRgbs` state).

---

## ColorModel — Bayesian Gaussian Core

### State

```kotlin
data class ColorModel(
    val mean: FloatArray,  // size 3: CIELAB L*, a*, b*
    var m2: Float,         // running sum of squared deviations (Welford's M2)
    var n: Float,          // effective sample count
    val priorVariance: Float
) {
    val variance: Float get() = if (n > MIN_SAMPLES) m2 / n else priorVariance
    companion object { const val MIN_SAMPLES = 5f }
}
```

`variance` falls back to `priorVariance` until `n > 5` (i.e., at least 5 weighted samples). This prevents overconfidence from the first 1–2 samples which can produce a spuriously tight M2/n estimate. The threshold 5 is to be calibrated in production alongside the prior variance.

### Priors

Standard Rubik's pigments in CIELAB with wide variance (625 = 25²). Values to be calibrated in production:

| Color  | L* | a*  | b*  |
|--------|----|-----|-----|
| White  | 95 | 0   | 3   |
| Yellow | 82 | −5  | 65  |
| Red    | 41 | 55  | 38  |
| Orange | 58 | 38  | 52  |
| Blue   | 32 | 14  | −52 |
| Green  | 48 | −55 | 32  |

### Update Rule (Welford's Online Algorithm)

Called on every confirmed tile and every manual override:

```
weight = 1.0   (normal tile confirmation)
weight = 3.0   (manual override — stronger signal)

n      += weight
delta1  = sample - mean                    // delta before mean update
mean   += delta1 * (weight / n)
delta2  = sample - mean                    // delta after mean update
m2     += weight * dot(delta1, delta2)     // scalar: sum deltas elementwise then sum
```

Scalar `M2` accumulated as `Σ weight * (delta1 · delta2)` where `·` is the dot product across the 3 LAB channels. This gives `variance = M2 / n` in units of squared LAB distance, consistent with the 3D Euclidean distance used in classification.

### Classification

```
for each CubeColor c:
    dist²   = ||labValue - c.mean||²           // squared Euclidean in LAB
    score(c) = dist² / c.variance + ln(c.variance)   // negative log-likelihood

winner   = argmin(score)
margin   = score[2nd] − score[1st]             // always ≥ 0
scoreMax = max(|score[1st]|, |score[2nd]|) + ε
confidence = clamp(margin / scoreMax, 0f, 1f)  // normalized to [0,1], safe when scores are negative
```

The `scoreMax` normalization keeps confidence in [0, 1] even when `ln(variance)` is negative (variance < 1.0 as models converge). Confidence < 0.15 = uncertain. Threshold to be calibrated in production.

### colorCycle

Returns the next-nearest color by NLL score — used for manual tile tap cycling. Given the current color, find its rank in the sorted-by-score list and return the next one. Wraps around.

---

## CubeFrameAnalyzer — Pixel → LAB → Smooth → Classify

### LAB Conversion

A single `LabConverter` object (or top-level function) owns the conversion and is shared by both `CubeFrameAnalyzer` and `ColorDetector`. This is critical: `calibrateFace` and `calibrateTile` must produce LAB values on the same scale and white point as the values being classified, or the Gaussian models will learn from different coordinates than they classify in.

```
sRgbToLab(rgb: Int): FloatArray(3)
    RGBA → linear RGB  (sRGB gamma decode per channel)
    linear RGB → XYZ D65  (standard 3×3 matrix, hardcoded)
    XYZ D65 → CIELAB  (standard f() function, D65 white point)
```

One `labScratch: FloatArray(3)` reused across pixels in `CubeFrameAnalyzer` — no per-pixel allocation. `ColorDetector` calls the same function during `calibrateFace` / `calibrateTile`.

### Tile Extraction

For each of 9 tiles per frame:
1. Compute tile bounds from grid layout (same as current)
2. Sample pixels at 2×2 stride
3. Convert each to LAB
4. Compute per-channel **median** across sampled pixels → one LAB triplet
5. Fallback: if fewer than 3 pixels sampled (extreme glare/clipping), use center pixel only

Median is naturally robust to outlier pixels (glare, grid lines). No similarity filter needed.

### Temporal Smoothing

Ring buffer of LAB values per tile, capacity 8, in LAB space:

```
ringBuffer[9]: ArrayDeque<FloatArray(3)>
each frame: push new tile LAB, pop oldest if at capacity
smoothed[tile] = per-channel median across buffer entries
```

### Classification Per Frame

```
for tile in 0..8:
    (color, conf) = colorDetector.classify(smoothed[tile])
    detectedColors[tile] = color
    confidence[tile] = conf
    detectedRgbs[tile]   = labToSRgb(smoothed[tile])   // for UI display
    detectedWbRgbs[tile] = labToSRgb(smoothed[tile])   // same source; WB step is gone
```

### centerStable

```
classified = colorDetector.classify(smoothed[4])
stable = classified.color == expectedColor
      && classified.confidence >= 0.15
      && consecutiveStableFrames >= 6
```

The confidence gate prevents "stable but wrong" — a misclassified center can't lock in if the model is still uncertain.

---

## Data Flow

```
Camera Frame (RGBA)
    ↓
[CubeFrameAnalyzer]
    Tile extraction (2×2 stride median → LAB per tile)
    ↓
    Temporal smoothing (ring buffer median, per tile, in LAB)
    ↓
    colorDetector.classify(lab) × 9
    ↓
StateFlows: detectedColors, detectedRgbs, detectedWbRgbs, centerStable, confidence

User confirms face
    ↓
[AppViewModel.confirmFace]
    colorDetector.saveCheckpoint()       // snapshot before update (for undo)
    colorDetector.calibrateFace(liveRgbs, correctedColors)
    ↓
[ColorDetector.calibrateFace]
    Convert each rgb → LAB
    For each tile: update ColorModel for confirmedColors[tile] (weight=1)
    ↓
    Models converge toward actual pigments under this camera/lighting

User taps tile to override
    ↓
[MainScreen tile tap handler → AppViewModel]
    colorDetector.calibrateTile(liveRgbs[tileIndex], newColor)
    ↓
[ColorDetector.calibrateTile]
    Convert rgb → LAB
    Update ColorModel for newColor (weight=3)
    ↓
    Override has immediate and lasting effect on future frames

User rescans / undoes face
    ↓
[AppViewModel.moveToPreviousFace / restartScan]
    colorDetector.restoreCheckpoint()    // rolls back to pre-confirmation models
    OR
    colorDetector.resetCalibration()     // full reset to priors (restartScan only)
```

---

## Checkpoint / Undo

`saveCheckpoint()` deep-copies all 6 `ColorModel` instances (mean array + m2 + n) into a single `Snapshot`. `restoreCheckpoint()` replaces live models with the snapshot copy. Only one checkpoint is maintained at a time (matching the existing single-checkpoint design). `resetCalibration()` resets all models to their priors.

---

## Error Handling

- Tile extraction < 3 pixels: fall back to center pixel only
- Near-tie: if `score[2nd] - score[1st] < 2.0`, confidence = 0.0 (genuinely ambiguous). The score scale is `dist²/variance + ln(variance)` which produces values in the range 4–20+ under typical conditions (prior variance 625, converged variance ~25); a threshold of 2.0 is a reasonable starting point. **To be calibrated in production** alongside the other thresholds.
- `scoreMax` near-zero edge: when both `score[1st]` and `score[2nd]` are near zero simultaneously (LAB value equidistant from two models), `scoreMax ≈ ε` causes the clamp to produce `confidence = 1.0`. This is a known edge case; in practice it requires two models to have nearly identical means and variances, which is prevented by the well-separated CIELAB priors. No special handling needed.
- No exceptions thrown from hot path — all edge cases return graceful defaults

---

## Testing

- Unit test `ColorModel`: verify mean converges to true value after N samples, variance narrows monotonically (using correct Welford formula)
- Unit test LAB conversion: round-trip sRGB → LAB → sRGB for known colors, verify < 1 unit error
- Unit test classification: standard Rubik's pigments classify correctly with prior alone
- Unit test confidence: verify confidence stays in [0, 1] when variance < 1.0 (scores negative)
- Unit test convergence: feed 9 synthetic samples per face × 6 faces, verify all 6 models converge to synthetic pigments
- Unit test undo: confirm → rescan → verify models restored to pre-confirm state
- Integration test `CubeFrameAnalyzer`: feed synthetic frame with known tile colors, verify StateFlow output
