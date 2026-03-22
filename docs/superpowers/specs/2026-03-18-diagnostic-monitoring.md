# Diagnostic Monitoring — Spec

## Goal

Give Claude Code full visibility into the Rubik scanner's runtime behaviour so
classification bugs can be diagnosed from logcat output alone, without needing to
look at device screenshots.  A secondary visual channel (the existing debug
bitmap, extended with per-tile annotations) gives the same information on-device.

---

## Background

The app already emits structured log lines under `TAG = "RubikSolver"`.  The DBG
button in `CameraPreviewLayer` exists but currently has no visible effect
(the bitmap IS generated but the debug bitmap overlay logic may not render
correctly after the LAB rewrite — verify and fix).  `ColorDetector.calibrationStr()`
emits compact model state (mean only, no variance/n) at CONFIRM.

`RenderingConstants.OVERLAY_SCALE_FACTOR = 0.33f` is defined in
`app/…/ui/RenderingConstants.kt`.  The current DBG overlay draw:
```kotlin
val px = (short * 2f * RenderingConstants.OVERLAY_SCALE_FACTOR).toInt()  // = short * 0.66
```
produces a centred box at 66% of the screen's shorter dimension — visually correct.
If the overlay is still invisible after the LAB rewrite, replace with a simple
`fillMaxSize` centred draw.

What is missing:
- Per-tile LAB values and per-tile confidence are never logged
- Color model variance and sample count (n) are not in logs
- No event when center tile first becomes stable / loses stability
- Debug bitmap only shows color letters — no LAB, no confidence
- TILE_TAP does not log tile LAB or NLL ranking
- CONFIRM does not log per-tile LABs

---

## Logcat event design

All events use `Log.d("RubikSolver", …)` — captured with `adb logcat -s RubikSolver`.

### New / extended events

| Tag | When emitted | Key payload |
|-----|------|-------------|
| `CENTER_STABLE` | Center tile first reaches stability (wasStable false→true) | face, center LAB, classified color, confidence, all-9 colors+confidences |
| `CENTER_LOST`  | Center tile drops out of stability (wasStable true→false) | face |
| `SCAN_FRAME`   | While center NOT stable, throttled 1 s/event | face, center LAB, center color+conf, all-9 colors+confs |
| `TILE_TAP`     | Existing — extend | add tile LAB + NLL rank of chosen color (1=best, 6=worst) |
| `CONFIRM`      | Existing — extend | add per-tile LAB array + full MODEL_DUMP after calibration |
| `MODEL_DUMP`   | After CONFIRM, RESTORE, RESET | all 6 models: `NAME[L=x a=y b=z v=w n=k]` |

`n` in MODEL_DUMP is a weighted float counter (weight=1 per auto tile, weight=3 per
corrected tile); `%.0f` format is sufficient.

`MODEL_DUMP` format (single grepable line):
```
MODEL_DUMP WHITE[L=92.0 a=-2.0 b=6.0 v=300.0 n=0] RED[L=39.0 a=63.0 b=50.0 v=300.0 n=9] ...
```

`CENTER_STABLE` format:
```
CENTER_STABLE face=ORANGE center=[55.1,51.8,62.3] color=ORANGE conf=0.23 tiles=[W:0.41 O:0.23 G:0.31 W:0.44 O:0.23 G:0.19 W:0.51 O:0.28 G:0.22]
```

`SCAN_FRAME` format (compact, throttled 1/sec):
```
SCAN_FRAME face=ORANGE center=[55.1,51.8,62.3] ORANGE:0.18 all=[W O G W O G W O G]
```

`CONFIRM` extension (appended to existing log line):
```
CONFIRM face=ORANGE … labs=[[42,63,50],[55,52,62],...] models_after=WHITE[…] RED[…] …
```
LAB values derived from `liveWbRgbs` via `LabConverter.sRgbToLab()` inline in
`confirmFace()` — no new parameter needed.

`TILE_TAP` extension:
```
TILE_TAP face=ORANGE ci=3 lab=[42.1,63.2,50.1] before=ORANGE after=RED rank=2
```
`rank` = 1-based position of `alt` in the NLL-sorted list returned by `colorCycle`.
Only log LAB when `wb != 0`; log `lab=N/A` otherwise.

---

## Debug bitmap extension

`buildDebugBitmap()` extended to draw three text lines per tile cell:

```
┌─────────────────┐
│ L:55 a:52       │   ← LAB channels (integer precision)
│ b:62            │
│ ORANGE  23%     │   ← color name + confidence %
└─────────────────┘
```

**Confidence colour coding** (4 px tile border + confidence text colour):
- Green  `#4CAF50` — confidence ≥ 0.20
- Yellow `#FFC107` — confidence ≥ 0.10
- Red    `#F44336` — confidence < 0.10

New signature:
```kotlin
private fun buildDebugBitmap(
    src: Bitmap, startX: Int, startY: Int,
    boxSize: Int, cellSize: Int, inset: Int,
    colors: List<CubeColor>,
    confidences: FloatArray,           // NEW
    smoothedLab: Array<FloatArray>     // NEW
): Bitmap
```

---

## New / changed methods

### `ColorDetector.modelDumpStr(): String`
```kotlin
fun modelDumpStr(): String =
    CubeColor.entries.joinToString(" ") { c ->
        val m = models[c]!!
        "${c.name}[L=%.1f a=%.1f b=%.1f v=%.0f n=%.0f]"
            .format(m.mean[0], m.mean[1], m.mean[2], m.variance, m.n)
    }
```
Called from `calibrateFace`, `restoreCheckpoint`, `resetCalibration`.

### `CubeFrameAnalyzer` additions
```kotlin
@Volatile private var wasStable: Boolean = false   // detect transitions
private var lastScanFrameMs: Long = 0L              // single-thread executor; no @Volatile needed
```
`resetTemporalBuffers()` must also reset `wasStable = false` and `lastScanFrameMs = 0L`.

`colorCycle` rank for TILE_TAP: add `fun colorCycleRank(wbRgb: Int, result: CubeColor): Int`
helper — or inline the sorted list in the tap handler.

### `AppViewModel.confirmFace()`
After calibration:
```kotlin
val labStr = liveWbRgbs.take(9).joinToString(",") { rgb ->
    val lab = LabConverter.sRgbToLab(rgb)
    "[%.0f,%.0f,%.0f]".format(lab[0], lab[1], lab[2])
}
log("CONFIRM_LABS $labStr")
log("MODEL_DUMP ${analyzer.colorDetector.modelDumpStr()}")
```

### `MainScreen` TILE_TAP
```kotlin
val labStr = if (wb != 0) {
    val lab = LabConverter.sRgbToLab(wb)
    "[%.1f,%.1f,%.1f]".format(lab[0], lab[1], lab[2])
} else "N/A"
// rank: re-sort colors by NLL to find alt's position
val rank = CubeColor.entries.sortedBy { analyzer.colorDetector.scoreFor(it, ...) }.indexOf(alt) + 1
log("TILE_TAP … lab=$labStr rank=$rank")
```
Note: `scoreFor` is `internal` — accessible from the same module. The app module
is separate, so expose `scoreFor` as `fun` (remove `internal`) or add a thin
`rankFor(wbRgb, color)` public wrapper on `ColorDetector`.

---

## Files changed

| File | Change |
|------|--------|
| `rubik-vision/…/ColorDetector.kt` | Add `modelDumpStr()`; add public `rankFor(wbRgb: Int, color: CubeColor): Int` |
| `rubik-vision/…/CubeFrameAnalyzer.kt` | `wasStable`+`lastScanFrameMs` fields; CENTER_STABLE/LOST/SCAN_FRAME events; extend `buildDebugBitmap`; reset in `resetTemporalBuffers` |
| `app/…/AppViewModel.kt` | Extend CONFIRM: per-tile LABs + MODEL_DUMP |
| `app/…/ui/MainScreen.kt` | Extend TILE_TAP: tile LAB + NLL rank |
| `app/…/ui/CameraPreview.kt` | Verify/fix debug bitmap overlay rendering |

No new files, no new permissions, no new UI components.

---

## Out of scope

- Network export / web dashboard
- File-based session JSON
- Per-frame logging (too noisy; SCAN_FRAME throttle is sufficient)
