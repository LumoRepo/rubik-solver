# Codebase Fixes Design — 2026-03-21

## Overview

Address all issues identified in the March 2026 code review. Fixes are grouped into independent buckets that can be executed in parallel (A/B/C/D), followed by a test bucket (E) that depends on the corrected code.

## Bucket A — rubik-model

### A1. CubeValidator crash on out-of-range facelets (Critical)
**File:** `rubik-model/src/main/kotlin/com/xmelon/rubik_solver/model/CubeValidator.kt`

Add an early guard at the top of `validate()`: if any facelet value is outside the valid range `0 until CubeColor.entries.size`, return `Invalid("Cube is not fully scanned")` before any counting or lookup. This subsumes the `-1` partially-scanned sentinel as well as any other corrupt facelet value, preventing `ArrayIndexOutOfBoundsException`. `CubeState.fromFacelets` explicitly permits `-1` as a valid partially-scanned sentinel, so `-1` is the expected case.

```kotlin
if (state.facelets.any { it !in 0 until CubeColor.entries.size }) return Invalid("Cube is not fully scanned")
```

### A2. `Move.parseSequence` crash on empty string (Minor)
**File:** `rubik-model/src/main/kotlin/com/xmelon/rubik_solver/model/Move.kt`

Add `if (sequence.isBlank()) return emptyList()` before the `split` call. Kotlin's `split` on an empty string returns `[""]`, causing `parse("")` to throw.

### A3. `tiltForward()` dead code (Important)
**File:** `rubik-model/src/main/kotlin/com/xmelon/rubik_solver/model/CubeState.kt`

Confirm `tiltForward()` is never called anywhere in the codebase (grep shows zero call sites outside the class itself). Delete the method and its KDoc entirely.

### A4. Dead commented-out assertion in `CubeStateTest` (Minor)
**File:** `rubik-model/src/test/kotlin/com/xmelon/rubik_solver/model/CubeStateTest.kt`

Delete the dead `expectedFaceletString` variable and the "Wait, my expected string..." comment block (lines 44–53).

---

## Bucket B — rubik-solver

### B1. Main-thread block at startup (Important)
**File:** `app/src/main/kotlin/com/xmelon/rubik_solver/MainActivity.kt`
**Location:** `HomeScreen` composable, `LaunchedEffect(Unit)` block (~line 89)

`CubeSolver.initialize()` is called from a `LaunchedEffect(Unit)` inside the `HomeScreen` composable. `LaunchedEffect` runs on `Dispatchers.Main` by default, so this ~1-second CPU-bound operation blocks the main thread. Wrap it in `withContext(Dispatchers.Default)`:

```kotlin
LaunchedEffect(Unit) {
    withContext(Dispatchers.Default) { CubeSolver.initialize() }
}
```

Note: `initialize()` is `@Synchronized` so concurrent calls from `solve()` are safe.

### B2. User-visible error strings in code (Minor)
**Files:** `rubik-solver/src/main/kotlin/com/xmelon/rubik_solver/solver/CubeSolver.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/kotlin/com/xmelon/rubik_solver/AppViewModel.kt`

Move hardcoded English solver error strings out of `CubeSolver.kt` (which has no Android dependency) into `strings.xml`. `CubeSolver` should return the raw error code strings (e.g. `"Error 2"`). Map error codes to `R.string.*` in `AppViewModel` where Android context is available.

---

## Bucket C — rubik-vision

### C1. Per-frame heap allocations in `extractGridColors` (Important)
**File:** `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`

Promote these to class-level pre-allocated fields (following the existing `pixelBuffer`/`labSortBuf` pattern):
- `rawLab: Array<FloatArray>` — `Array(9) { FloatArray(3) }`
- `smoothedLab: Array<FloatArray>` — `Array(9) { FloatArray(3) }`
- `colors: ArrayList<CubeColor>` — capacity 9
- `confidences: FloatArray` — size 9
- `rgbs: IntArray` — size 9

Clear/reuse each frame instead of allocating. Gate the `tileStr` `joinToString` construction behind `if (Log.isLoggable(TAG, Log.VERBOSE))`.

### C2. Median bias for even-sized buffers (Important)
**File:** `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`

In `labMedian`, when `n` is even, compute the true median as the average of `labSortBuf[n/2 - 1]` and `labSortBuf[n/2]` instead of using only `labSortBuf[n/2]`. With `TEMPORAL_BUFFER_SIZE = 8`, the buffer is always even once filled, making this bias systematic.

### C3. Silent error swallowing in `analyze()` (Important)
**File:** `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`

In the `catch` block for `extractGridColors`, after logging, clear all three per-frame color StateFlows so the UI reflects the failure state rather than showing stale data:

```kotlin
_detectedColors.value = emptyList()
_detectedRgbs.value = emptyList()
_detectedWbRgbs.value = IntArray(0)
```

Optionally also clear `_confidence`.

### C4. Clarify threading on `wasStable` (Minor)
**File:** `rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`

`wasStable` is written from two threads: the camera executor thread (in `analyze()`) and the main thread (via `resetTemporalBuffers()` called from `AppViewModel`). The `@Volatile` annotation is therefore correct and must be retained. Add a comment explaining this cross-thread access pattern. `lastScanFrameMs` is only written from the camera executor thread — document this too.

---

## Bucket D — app

### D1. `currentFramePolys` race condition in `Cube3DView` (Critical)
**File:** `app/src/main/kotlin/com/xmelon/rubik_solver/ui/Cube3DView.kt`

Replace `mutableListOf<RenderPoly>()` with `AtomicReference<List<RenderPoly>>(emptyList())`. In `Canvas {}`, build the full list into a local `val`, then swap via `.set()` after completion. The `pointerInput` handler reads via `.get()`. No partial list is ever visible across threads.

### D2. Scan order constant duplicated in 3 places (Important)
**Files:** `ScanOrchestrator.kt`, `AppViewModel.kt`, `ScreenProgressBar.kt`

Add `val SCAN_ORDER: List<Face>` as a `companion object` constant on `ScanOrchestrator`. Replace the existing private `scanOrder` field inside `ScanOrchestrator` with `SCAN_ORDER` (eliminating the duplication within the class itself). Remove the duplicate local definitions in `AppViewModel.updateScanProgress` and `ScreenProgressBar`, replacing them with `ScanOrchestrator.SCAN_ORDER`.

### D3. Composables reaching through ViewModel internals (Important)
**Files:** `AppViewModel.kt`, `MainScreen.kt`

Add wrapper methods to `AppViewModel` for each operation currently accessed via `vm.analyzer.*` or `vm.orchestrator.*` from `MainScreen.kt`. Based on actual call sites in `MainScreen.kt`:

- `fun saveCheckpoint()` → `analyzer.colorDetector.saveCheckpoint()`
- `fun restoreCheckpoint()` → `analyzer.colorDetector.restoreCheckpoint()`
- `fun resetTemporalBuffers()` → `analyzer.resetTemporalBuffers()`
- `fun resetCalibration()` → `analyzer.colorDetector.resetCalibration()`
- `fun colorCycle(...)` → `analyzer.colorDetector.colorCycle(...)`
- `fun rankFor(...)` → `analyzer.colorDetector.rankFor(...)`

Update all call sites in `MainScreen.kt` to use the new ViewModel methods.

### D4. `Screen` and `AppMode` enum placement (Minor)
**Files:** `app/src/main/kotlin/com/xmelon/rubik_solver/MainActivity.kt`, `app/src/main/kotlin/com/xmelon/rubik_solver/AppViewModel.kt`

`Screen` is defined in `MainActivity.kt` and `AppMode` is defined in `AppViewModel.kt`. Move both to a new file `app/src/main/kotlin/com/xmelon/rubik_solver/AppState.kt`. Update all imports.

### D5. `remember(bmp)` no-op in `CameraPreview` (Minor)
**File:** `app/src/main/kotlin/com/xmelon/rubik_solver/ui/CameraPreview.kt`

Remove the `remember` wrapper around `bmp.asImageBitmap()`. The key changes every frame (new `Bitmap` object each frame) so the cache is never reused; call `bmp.asImageBitmap()` directly.

### D6. `gap` dead code in `Cube3DView.buildCubies` (Minor)
**File:** `app/src/main/kotlin/com/xmelon/rubik_solver/ui/Cube3DView.kt`

Delete `gap`, `gapX`, `gapY`, `gapZ` (all evaluate to `0f`). Use `cx`, `cy`, `cz` directly in the `v(...)` lambda.

---

## Bucket E — Tests (after A/B/C/D complete)

### E1. `CubeValidatorTest` — out-of-range facelets
Add a test passing a `CubeState` with at least one `-1` facelet to `validate()`, asserting `Invalid` is returned (exercising the new guard from A1).

### E2. `CubeStateTest` — `permute()` vs `applyMoves()` agreement
Add a test confirming `permute()` and `applyMoves()` produce the same result on a known scramble sequence.

### E3. `CubeSolverTest` — timeout path
Pass `timeoutMs = 1` on a computationally hard scramble. Assert the solver returns a failure/timeout result rather than crashing or hanging.

### E4. `CubeFrameAnalyzerTest` (new file)
- Test `extractGridColors` with a synthetic solid-color bitmap; verify detected color matches expected.
- Verify the temporal ring buffer stabilizes after `TEMPORAL_BUFFER_SIZE` identical frames.
- Verify `_detectedColors`, `_detectedRgbs`, and `_detectedWbRgbs` are all cleared when `extractGridColors` throws.

### E5. `ScanOrchestratorTest` — additional cases
- `reset()` clears all facelets and resets index to 0.
- `buildCubeState()` throws (or returns failure) when called before scan is complete.
- `commitCurrentFace` on an already-scanned face triggers immediate completion if all faces are done.

---

## Execution Order

```
Parallel: Bucket A + Bucket B + Bucket C + Bucket D
Then:     Bucket E
```

Buckets A, B, C, D have no cross-dependencies and can run simultaneously. Bucket E requires the corrected implementations to be in place before tests are written.
