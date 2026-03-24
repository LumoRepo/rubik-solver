---
name: code_review_findings
description: Comprehensive code review findings: critical bugs, optimizations, redundancies
type: project
---

# Rubik Solver Code Review Summary (2026-03-23)

## Critical Issues (Fix Immediately)

1. **Bitmap Leak** - `CubeFrameAnalyzer.kt:144-173`
   - `bitmap` leaked if `toBitmap()` throws exception
   - **Fix:** `finally { bitmap?.recycle() }`

2. **Center Seeding Bug** - `CubeFrameAnalyzer.kt:283-288`
   - `centerSeeded` flag prevents recalibration when `expectedCenterColor` changes
   - **Fix:** Check both `!centerSeeded || expectedCenterColor != _expectedCenterColor.get()`

3. **Unsafe toFaceletString** - `CubeState.kt:131-136`
   - Throws `IllegalStateException` with color name mismatch when centers not uniform
   - **Fix:** Distinguish unscanned (-1) vs color mismatch errors

## High Priority

1. **Unbounded Memory** - `CubeFrameAnalyzer.kt:83`
   - `pixelBuffer` grows monotonically, never shrinks
   - **Fix:** Bound at `minOf(previewWidth, previewHeight) * 9`

2. **Error Message Inconsistency** - `CubeValidator.kt:28`
   - Uses `color.name` ("RED") vs lowercase elsewhere
   - **Fix:** Use `color.label` consistently

## Medium Priority

1. **Duplicate Color Mappings** - `CubeColors.kt`
   - `colorNameRes()` duplicated in multiple places
   - **Fix:** Consolidate into single lookup table

2. **StateFlow Usage** - `ScanOrchestrator.kt:27-38`
   - `MutableStateFlow` as storage when read-only externally
   - **Fix:** Use `mutableStateOf()` for state holder pattern

3. **Missing KDocs** - Multiple private helper methods
   - **Fix:** Add KDocs to `applySingleCW()`, `extractTileLab()`, `labMedian()`

## Low Priority

- Inconsistent spacing around `+` operators in `CubeState.kt`
- Trailing whitespace in multiple files
- TODOs without owners in `CubeFrameAnalyzer.kt`

---

**Session Date:** 2026-03-23
**Next Review:** See specific findings