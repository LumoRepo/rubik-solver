# Diagnostic Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Rubik scanner with structured logcat diagnostics and a live debug bitmap overlay so classification bugs can be diagnosed from `adb logcat -s RubikSolver` output.

**Architecture:** Add `modelDumpStr()` + `rankFor()` to `ColorDetector`; add stability-transition events + SCAN_FRAME throttle to `CubeFrameAnalyzer`; extend `buildDebugBitmap` with per-tile LAB + confidence text; extend CONFIRM and TILE_TAP log lines; verify DBG overlay renders.

**Tech Stack:** Kotlin, Android Log, Compose Canvas, `android.graphics.Paint`, existing `LabConverter`/`ColorModel` APIs.

---

## File map

| File | Role |
|------|------|
| `rubik-vision/…/vision/ColorDetector.kt` | Add `modelDumpStr()`, `rankFor()` |
| `rubik-vision/…/vision/CubeFrameAnalyzer.kt` | Stability events, SCAN_FRAME throttle, extended debug bitmap |
| `app/…/AppViewModel.kt` | Extend CONFIRM log |
| `app/…/ui/MainScreen.kt` | Extend TILE_TAP log |
| `app/…/ui/CameraPreview.kt` | Verify/fix debug bitmap overlay rendering |
| `rubik-vision/…/vision/ColorDetectorTest.kt` | Tests for new public methods |

---

## Task 1 — Add `modelDumpStr()` and `rankFor()` to ColorDetector

**Files:**
- Modify: `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorDetector.kt`
- Test: `rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorDetectorTest.kt`

- [ ] **Write failing tests**

Add to `ColorDetectorTest.kt` after the existing tests:

```kotlin
@Test fun `modelDumpStr includes variance and n for all 6 colors`() {
    val s = detector.modelDumpStr()
    CubeColor.entries.forEach { c ->
        assertThat(s).contains(c.name)
        assertThat(s).contains("v=")
        assertThat(s).contains("n=")
    }
}

@Test fun `rankFor returns 1 for best-matching prior color`() {
    // RED at its prior mean should rank 1 for RED
    val redRgb = LabConverter.labToSRgb(floatArrayOf(39f, 63f, 50f))
    assertThat(detector.rankFor(redRgb, CubeColor.RED)).isEqualTo(1)
}

@Test fun `rankFor returns value in 1-6 range`() {
    val rgbs = listOf(rgb(240, 235, 220), rgb(200, 20, 20), rgb(220, 80, 5))
    rgbs.forEach { r ->
        CubeColor.entries.forEach { c ->
            val rank = detector.rankFor(r, c)
            assertThat(rank).isAtLeast(1)
            assertThat(rank).isAtMost(6)
        }
    }
}
```

- [ ] **Run tests to confirm they fail**

```
./gradlew :rubik-vision:test 2>&1 | grep -E "FAILED|modelDumpStr|rankFor"
```
Expected: 3 new failures.

- [ ] **Implement `modelDumpStr()` and `rankFor()` in ColorDetector**

Add after `calibrationStr()` (around line 149):

```kotlin
/** Verbose model dump including variance and weighted-sample count. */
fun modelDumpStr(): String =
    CubeColor.entries.joinToString(" ") { c ->
        val m = models[c]!!
        "${c.name}[L=%.1f a=%.1f b=%.1f v=%.0f n=%.0f]"
            .format(m.mean[0], m.mean[1], m.mean[2], m.variance, m.n)
    }

/**
 * Returns the 1-based NLL rank of [color] for the pixel [wbRgb].
 * 1 = best match, 6 = worst. Used by the tile-tap log to show how many
 * cycle steps separate the chosen correction from the classifier's best guess.
 */
fun rankFor(wbRgb: Int, color: CubeColor): Int {
    val lab = LabConverter.sRgbToLab(wbRgb)
    val sorted = CubeColor.entries.sortedBy { models[it]!!.score(lab) }
    return sorted.indexOf(color) + 1
}
```

Also call `modelDumpStr()` at the end of `restoreCheckpoint()` and `resetCalibration()`:

In `restoreCheckpoint()`, after the restore logic:
```kotlin
logd { "CAL restoreCheckpoint ${modelDumpStr()}" }
```
Replace the existing `logd { "CAL restoreCheckpoint" }` line (or add after it).

In `resetCalibration()`, replace:
```kotlin
logd { "CAL resetCalibration" }
```
with:
```kotlin
logd { "CAL resetCalibration MODEL_DUMP ${modelDumpStr()}" }
```

- [ ] **Run tests to confirm they pass**

```
./gradlew :rubik-vision:test 2>&1 | grep -E "FAILED|tests completed"
```
Expected: same 3 pre-existing ScanOrchestratorTest failures, all new tests pass.

---

## Task 2 — Stability events + SCAN_FRAME throttle in CubeFrameAnalyzer

**Files:**
- Modify: `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`

No unit tests (event emission is integration-level; verified via logcat after install).

- [ ] **Add new fields**

In the companion object, add the throttle interval:
```kotlin
private const val SCAN_FRAME_INTERVAL_MS = 1000L
```

After the `consecutiveCenterInRange` field declaration, add:
```kotlin
// Transition tracking for CENTER_STABLE / CENTER_LOST events
@Volatile private var wasStable: Boolean = false
// Throttle for SCAN_FRAME (single-thread executor — no @Volatile needed)
private var lastScanFrameMs: Long = 0L
```

- [ ] **Reset new fields in `resetTemporalBuffers()`**

Add to `resetTemporalBuffers()` body:
```kotlin
wasStable = false
lastScanFrameMs = 0L
```

- [ ] **Emit CENTER_STABLE, CENTER_LOST, SCAN_FRAME in `extractGridColors()`**

Replace the existing "6. Debug logging (throttled)" block (lines 206–212):

```kotlin
// 6. Stability-transition events + SCAN_FRAME throttle
val tileStr = colors.mapIndexed { i, c ->
    "%s:%.2f".format(c.name.first(), confidences[i])
}.joinToString(" ")

if (nowStable && !wasStable) {
    wasStable = true
    val cL = smoothedLab[4]
    Log.d(TAG, "CENTER_STABLE face=${expected?.name} " +
        "center=[%.1f,%.1f,%.1f] color=${colors[4].name} conf=%.2f tiles=[$tileStr]"
            .format(cL[0], cL[1], cL[2], confidences[4]))
    Log.d(TAG, "MODEL_DUMP ${colorDetector.modelDumpStr()}")
} else if (!nowStable && wasStable) {
    wasStable = false
    Log.d(TAG, "CENTER_LOST face=${expected?.name}")
} else if (!nowStable) {
    val now = System.currentTimeMillis()
    if (now - lastScanFrameMs >= SCAN_FRAME_INTERVAL_MS) {
        lastScanFrameMs = now
        val cL = smoothedLab[4]
        val allColors = colors.map { it.name.first() }.joinToString("")
        Log.d(TAG, "SCAN_FRAME face=${expected?.name} " +
            "center=[%.1f,%.1f,%.1f] ${colors[4].name}:%.2f all=[$allColors]"
                .format(cL[0], cL[1], cL[2], confidences[4]))
    }
}

// Legacy change-detection log (kept for compatibility)
if (colors != lastLoggedColors) {
    lastLoggedColors = colors
    Log.d(TAG, "SCAN_DETECT exp=${expected?.name} center=${colors.getOrNull(4)?.name}" +
        " conf=%.2f stable=$nowStable".format(confidences[4]) +
        " ${colors.map { it.label }}")
}
```

- [ ] **Build to verify no compile errors**

```
./gradlew :rubik-vision:compileDebugKotlin 2>&1 | grep -i error
```
Expected: no errors.

---

## Task 3 — Extend `buildDebugBitmap` with LAB + confidence annotations

**Files:**
- Modify: `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`

- [ ] **Update `buildDebugBitmap` signature**

Change:
```kotlin
private fun buildDebugBitmap(
    src: Bitmap, startX: Int, startY: Int,
    boxSize: Int, cellSize: Int, inset: Int,
    colors: List<CubeColor>
): Bitmap {
```
To:
```kotlin
private fun buildDebugBitmap(
    src: Bitmap, startX: Int, startY: Int,
    boxSize: Int, cellSize: Int, inset: Int,
    colors: List<CubeColor>,
    confidences: FloatArray,
    smoothedLab: Array<FloatArray>
): Bitmap {
```

- [ ] **Update call site in `extractGridColors()`**

Change:
```kotlin
_debugBitmap.value = if (debugMode)
    buildDebugBitmap(bitmap, startX, startY, boxSize, cellSize, inset, colors)
else null
```
To:
```kotlin
_debugBitmap.value = if (debugMode)
    buildDebugBitmap(bitmap, startX, startY, boxSize, cellSize, inset,
        colors, confidences, smoothedLab)
else null
```

- [ ] **Replace the text-drawing body of `buildDebugBitmap`**

Replace the `cvs.drawText` section (the `for (tileRow in 0..2)` loop that draws letters) with:

```kotlin
val smallTextSize = cellSize * 0.18f
val labelTextSize = cellSize * 0.22f
val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }

for (tileRow in 0..2) {
    for (tileCol in 0..2) {
        val idx = tileRow * 3 + tileCol
        val conf = confidences.getOrElse(idx) { 0f }
        val lab  = smoothedLab.getOrElse(idx) { FloatArray(3) }
        val color = colors.getOrNull(idx)

        val cx = (tileCol * cellSize + (tileCol + 1) * cellSize) / 2f
        val cy = (tileRow * cellSize + (tileRow + 1) * cellSize) / 2f

        // Confidence-coded border colour
        val borderArgb = when {
            conf >= 0.20f -> 0xFF4CAF50.toInt()   // green
            conf >= 0.10f -> 0xFFFFC107.toInt()   // yellow
            else          -> 0xFFF44336.toInt()   // red
        }
        borderPaint.color = borderArgb
        val bx0 = (tileCol * cellSize + 2).toFloat()
        val by0 = (tileRow * cellSize + 2).toFloat()
        val bx1 = ((tileCol + 1) * cellSize - 2).toFloat()
        val by1 = ((tileRow + 1) * cellSize - 2).toFloat()
        cvs.drawRect(bx0, by0, bx1, by1, borderPaint)

        // LAB line 1: "L:55 a:52"
        paint.textSize = smallTextSize
        val labLine1 = "L:%.0f a:%.0f".format(lab[0], lab[1])
        val labLine2 = "b:%.0f".format(lab[2])
        val ty1 = cy - smallTextSize * 1.6f
        val ty2 = cy - smallTextSize * 0.3f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = smallTextSize * 0.15f
        paint.color = android.graphics.Color.BLACK
        cvs.drawText(labLine1, cx, ty1, paint)
        cvs.drawText(labLine2, cx, ty2, paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        cvs.drawText(labLine1, cx, ty1, paint)
        cvs.drawText(labLine2, cx, ty2, paint)

        // Color name + confidence % on bottom
        paint.textSize = labelTextSize
        val confPct = (conf * 100).toInt()
        val label = "${color?.name?.take(3) ?: "?"} $confPct%"
        val ty3 = cy + labelTextSize * 1.4f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = labelTextSize * 0.15f
        paint.color = android.graphics.Color.BLACK
        cvs.drawText(label, cx, ty3, paint)
        paint.style = Paint.Style.FILL
        paint.color = borderArgb
        cvs.drawText(label, cx, ty3, paint)
    }
}
```

- [ ] **Build to verify no compile errors**

```
./gradlew :rubik-vision:compileDebugKotlin 2>&1 | grep -i error
```
Expected: no errors.

---

## Task 4 — Extend CONFIRM log in AppViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/xmelon/rubik_solver/AppViewModel.kt`

- [ ] **Add CONFIRM_LABS and MODEL_DUMP log lines**

In `confirmFace()`, after the existing `log("CONFIRM cal_after=…")` line (currently line 156), add:

```kotlin
// Per-tile LABs derived from liveWbRgbs for diagnostic monitoring
val labStr = (0 until minOf(liveWbRgbs.size, 9)).joinToString(",") { i ->
    val lab = com.xmelon.rubik_solver.vision.LabConverter.sRgbToLab(liveWbRgbs[i])
    "[%.0f,%.0f,%.0f]".format(lab[0], lab[1], lab[2])
}
log("CONFIRM_LABS face=$face $labStr")
log("MODEL_DUMP ${analyzer.colorDetector.modelDumpStr()}")
```

- [ ] **Build to verify no compile errors**

```
./gradlew :app:compileDebugKotlin 2>&1 | grep -i error
```
Expected: no errors.

---

## Task 5 — Extend TILE_TAP log in MainScreen

**Files:**
- Modify: `app/src/main/kotlin/com/xmelon/rubik_solver/ui/MainScreen.kt`

- [ ] **Replace the TILE_TAP log line**

Find the existing log line (around line 217):
```kotlin
log("TILE_TAP face=$face ci=$ci wb=#${"%06X".format(wb)} before=${before.name} after=${alt.name}")
```

Replace with:
```kotlin
val labStr = if (wb != 0) {
    val lab = com.xmelon.rubik_solver.vision.LabConverter.sRgbToLab(wb)
    "[%.1f,%.1f,%.1f]".format(lab[0], lab[1], lab[2])
} else "N/A"
val rank = if (wb != 0) analyzer.colorDetector.rankFor(wb, alt) else -1
log("TILE_TAP face=$face ci=$ci wb=#${"%06X".format(wb)} lab=$labStr before=${before.name} after=${alt.name} rank=$rank")
```

- [ ] **Build to verify no compile errors**

```
./gradlew :app:compileDebugKotlin 2>&1 | grep -i error
```
Expected: no errors.

---

## Task 6 — Verify and fix DBG overlay rendering in CameraPreview

**Files:**
- Modify: `app/src/main/kotlin/com/xmelon/rubik_solver/ui/CameraPreview.kt`

- [ ] **Simplify the debug bitmap draw to fill the camera preview region**

The current draw uses `RenderingConstants.OVERLAY_SCALE_FACTOR` (= 0.33) which scales
the debug bitmap to only 66% of the shorter screen dimension — making it hard to read
on a phone. Replace with a draw that fills the same square region the camera grid
occupies (66% of min dimension, centred):

Replace the `Canvas(Modifier.fillMaxSize())` block (lines 88–95):

```kotlin
Canvas(Modifier.fillMaxSize()) {
    val short = minOf(size.width, size.height)
    val px    = (short * 0.66f).toInt()
    val left  = ((size.width  - px) / 2).toInt()
    val top   = ((size.height - px) / 2).toInt()
    drawImage(
        imgBitmap,
        srcOffset = IntOffset.Zero,
        srcSize   = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset(left, top),
        dstSize   = IntSize(px, px)
    )
}
```

(Removes the `* 2f *` factor that doubled the intended size and the
`RenderingConstants.OVERLAY_SCALE_FACTOR` dependency from this composable.)

- [ ] **Build full project**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

---

## Task 7 — Run tests, install, smoke test

- [ ] **Run all module tests**

```
./gradlew :rubik-vision:test 2>&1 | grep -E "tests completed|FAILED"
```
Expected: all new tests pass, only 3 pre-existing ScanOrchestratorTest failures.

- [ ] **Install on device**

```
./gradlew :app:installDebug 2>&1 | tail -5
```
Expected: `Installed on 1 device. BUILD SUCCESSFUL`

- [ ] **Capture logcat to verify events**

```
adb logcat -s RubikSolver -v time
```
While scanning a face, verify:
- `SCAN_FRAME` appears ~1/sec while awaiting alignment
- `CENTER_STABLE` fires when the center locks, with LAB values and tile list
- `CONFIRM_LABS` and `MODEL_DUMP` appear after confirming a face
- `TILE_TAP` includes `lab=` and `rank=` when tapping a tile
- Tapping DBG shows the annotated tile overlay on the camera preview
