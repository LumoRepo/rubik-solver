# iOS KMP Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Android Rubik Solver app to iOS by migrating to Kotlin Multiplatform + Compose Multiplatform, sharing all business logic and UI, with only camera and solver entry points as platform-specific code.

**Architecture:** Four modules are converted to KMP libraries (`rubik-model`, `rubik-vision`, `rubik-solver`, `composeApp`). Each module gains `commonMain`/`androidMain`/`iosMain` source sets. The solver uses `expect`/`actual` — Java min2phase on Android, `min2phaseCXX` compiled as an XCFramework on iOS. The camera uses `expect`/`actual` — CameraX on Android, AVFoundation on iOS. All Compose UI moves to `commonMain` unchanged (Compose Canvas is multiplatform).

**Tech Stack:** Kotlin Multiplatform 2.3.10, Compose Multiplatform (JetBrains, check compatibility table for Kotlin 2.3.x), AGP 9.1.0, min2phaseCXX (C++14, MIT), CameraX 1.5.3, AVFoundation (iOS), Xcode 16+

---

> **⚠️ Scope note:** This plan has 4 independent phases. Each phase leaves the Android app fully functional. You can stop after any phase and ship Android while iOS work continues.
>
> - **Phase 1** (Tasks 1–4): KMP foundation — all shared logic in `commonMain`
> - **Phase 2** (Task 5): iOS solver via min2phaseCXX XCFramework
> - **Phase 3** (Tasks 6–7): Compose Multiplatform UI
> - **Phase 4** (Tasks 8–9): iOS camera + app entry point
>
> Phases 2 and 3 are independent and can be done in parallel.

---

> **🔑 Before starting:** Check the JetBrains Compose Multiplatform compatibility table at
> https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html
> to find the correct CMP version for Kotlin 2.3.10. Use that version everywhere `<CMP_VERSION>` appears below.

---

## File Structure After Migration

```
gradle/libs.versions.toml               — add: kotlin-multiplatform, compose-multiplatform plugins + versions

rubik-model/
  build.gradle.kts                      — replace kotlin.jvm with kotlin.multiplatform
  src/commonMain/kotlin/...             — move all existing .kt files here (unchanged)
  src/commonTest/kotlin/...             — move all test files here, migrate Truth → kotlin.test

rubik-vision/
  build.gradle.kts                      — replace android.library with kotlin.multiplatform + androidTarget
  src/commonMain/kotlin/.../vision/
    FrameAnalyzer.kt                    — NEW: common interface for image frame analysis
    LabConverter.kt                     — moved from main (unchanged)
    ColorModel.kt                       — moved from main (unchanged)
    ColorDetector.kt                    — moved from main (unchanged)
    ScanOrchestrator.kt                 — moved from main (unchanged)
  src/androidMain/kotlin/.../vision/
    CubeFrameAnalyzer.kt                — moved here (Android ImageProxy impl, unchanged)
  src/commonTest/kotlin/...             — migrate tests, Truth → kotlin.test

rubik-solver/
  build.gradle.kts                      — replace kotlin.jvm with kotlin.multiplatform
  src/commonMain/kotlin/.../solver/
    SolveResult.kt                      — moved here (unchanged)
    CubeSolver.kt                       — becomes expect declaration only
  src/androidMain/kotlin/.../solver/
    CubeSolver.kt                       — NEW: actual wrapping Java min2phase
  src/androidMain/java/cs/min2phase/   — MOVE existing Java files here (unchanged)
  src/iosMain/kotlin/.../solver/
    CubeSolver.kt                       — NEW: actual calling C++ via cinterop
  src/iosMain/cinterop/
    min2phase.def                        — NEW: cinterop definition file
  min2phaseCXX/                         — NEW: git submodule

composeApp/   (renamed from app/)
  build.gradle.kts                      — replace android.application with multiplatform + CMP
  src/commonMain/kotlin/...
    AppViewModel.kt                     — moved, replace android.util.Log with expect log()
    Screen.kt                           — NEW: extract Screen enum from MainActivity
    ui/MainScreen.kt                    — moved (unchanged)
    ui/Cube3DView.kt                    — moved (unchanged)
    ui/ScreenFooters.kt                 — moved (unchanged)
    ui/ScreenHeaders.kt                 — moved (unchanged)
    ui/ScreenProgressBar.kt             — moved (unchanged)
    ui/Math3D.kt                        — moved (unchanged)
    ui/RenderingConstants.kt            — moved (unchanged)
    ui/CubeColors.kt                    — moved (unchanged)
    ui/FaceColorOverrides.kt            — moved (unchanged)
    ui/CameraPreview.kt                 — becomes expect declaration only
    ui/PlatformLog.kt                   — NEW: expect fun log()
    HomeScreen.kt                       — NEW: extract HomeScreen from MainActivity
    App.kt                              — NEW: shared root composable (extracted from MainActivity)
  src/commonMain/composeResources/
    values/strings.xml                  — NEW: copy from res/values/strings.xml (same format)
  src/androidMain/kotlin/...
    MainActivity.kt                     — stripped to just Activity + setContent { App() }
    ui/CameraPreview.kt                 — NEW: actual with CameraX code
    PlatformLog.kt                      — NEW: actual using android.util.Log
  src/iosMain/kotlin/...
    ui/CameraPreview.kt                 — NEW: actual using AVFoundation cinterop
    PlatformLog.kt                      — NEW: actual using NSLog
    MainViewController.kt               — NEW: entry point for iOS

iosApp/
  iosApp.xcodeproj/                     — NEW: Xcode project
  iosApp/iOSApp.swift                   — NEW: @main SwiftUI entry point
  iosApp/ContentView.swift              — NEW: hosts MainViewController via UIViewControllerRepresentable
  iosApp/Info.plist                     — NEW: NSCameraUsageDescription permission
```

---

## Phase 1: KMP Foundation

### Task 1: Add KMP plugin to version catalog and root build

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)

- [ ] **Step 1: Add KMP and CMP plugin entries to libs.versions.toml**

  In `gradle/libs.versions.toml`, add to `[versions]`:
  ```toml
  composeMultiplatform = "<CMP_VERSION>"
  ```
  Add to `[plugins]`:
  ```toml
  kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
  compose-multiplatform = { id = "org.jetbrains.compose", version = "<CMP_VERSION>" }
  ```
  Also add to `[libraries]`:
  ```toml
  kotlin-test = { group = "org.jetbrains.kotlin", name = "kotlin-test", version.ref = "kotlin" }
  lifecycle-viewmodel-kmp = { group = "androidx.lifecycle", name = "lifecycle-viewmodel", version.ref = "lifecycle" }
  ```

- [ ] **Step 2: Register CMP plugin in root build.gradle.kts**

  In `build.gradle.kts` (root), add inside the `plugins {}` block:
  ```kotlin
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  ```

- [ ] **Step 3: Verify Gradle sync succeeds**

  Run: `./gradlew help`
  Expected: BUILD SUCCESSFUL (plugin catalog resolves)

- [ ] **Step 4: Commit**

  ```bash
  git add gradle/libs.versions.toml build.gradle.kts
  git commit -m "build: add KMP and Compose Multiplatform to version catalog"
  ```

---

### Task 2: Convert rubik-model to Kotlin Multiplatform

**Files:**
- Modify: `rubik-model/build.gradle.kts`
- Move: `rubik-model/src/main/kotlin/` → `rubik-model/src/commonMain/kotlin/`
- Move: `rubik-model/src/test/kotlin/` → `rubik-model/src/commonTest/kotlin/`

This module has zero Android dependencies — it's the easiest KMP conversion.

- [ ] **Step 1: Move source files to commonMain**

  ```bash
  mkdir -p rubik-model/src/commonMain/kotlin/com/xmelon/rubik_solver/model
  mv rubik-model/src/main/kotlin/com/xmelon/rubik_solver/model/*.kt \
     rubik-model/src/commonMain/kotlin/com/xmelon/rubik_solver/model/
  mkdir -p rubik-model/src/commonTest/kotlin/com/xmelon/rubik_solver/model
  mv rubik-model/src/test/kotlin/com/xmelon/rubik_solver/model/*.kt \
     rubik-model/src/commonTest/kotlin/com/xmelon/rubik_solver/model/
  ```

- [ ] **Step 2: Replace build.gradle.kts**

  Replace `rubik-model/build.gradle.kts` entirely:
  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.multiplatform)
  }

  kotlin {
      jvm()
      iosArm64()
      iosX64()
      iosSimulatorArm64()

      sourceSets {
          commonMain.dependencies {}
          commonTest.dependencies {
              implementation(libs.kotlin.test)
          }
      }
  }
  ```

- [ ] **Step 3: Migrate test assertions from Truth to kotlin.test**

  Open each test file in `commonTest`. Replace Truth assertions:
  ```kotlin
  // Before (Truth):
  import com.google.common.truth.Truth.assertThat
  assertThat(result).isEqualTo(expected)
  assertThat(list).hasSize(6)
  assertThat(result).isTrue()
  assertThat(result).isNotNull()

  // After (kotlin.test):
  import kotlin.test.*
  assertEquals(expected, result)
  assertEquals(6, list.size)
  assertTrue(result)
  assertNotNull(result)
  ```

- [ ] **Step 4: Run tests to verify they pass on JVM**

  Run: `./gradlew :rubik-model:jvmTest`
  Expected: BUILD SUCCESSFUL, all existing tests pass

- [ ] **Step 5: Verify iOS targets compile**

  Run: `./gradlew :rubik-model:compileKotlinIosArm64`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

  ```bash
  git add rubik-model/
  git commit -m "feat: convert rubik-model to Kotlin Multiplatform (commonMain)"
  ```

---

### Task 3: Convert rubik-vision to KMP — split common/android

**Files:**
- Modify: `rubik-vision/build.gradle.kts`
- Create: `rubik-vision/src/commonMain/kotlin/com/xmelon/rubik_solver/vision/FrameAnalyzer.kt`
- Move (pure Kotlin): LabConverter, ColorModel, ColorDetector, ScanOrchestrator → `commonMain`
- Move (Android): CubeFrameAnalyzer → `androidMain`
- Move: tests → `commonTest` (migrate Truth → kotlin.test) + `androidUnitTest` for CubeFrameAnalyzer

- [ ] **Step 1: Create the FrameAnalyzer interface in commonMain**

  Create `rubik-vision/src/commonMain/kotlin/com/xmelon/rubik_solver/vision/FrameAnalyzer.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.vision

  import com.xmelon.rubik_solver.model.CubeColor

  /**
   * Platform-agnostic interface for frame-by-frame cube color detection.
   * Android: implemented by CubeFrameAnalyzer using ImageProxy.
   * iOS: implemented by CubeFrameAnalyzer using CVPixelBuffer.
   */
  interface FrameAnalyzer {
      /** Expected center color for the face currently being scanned. */
      var expectedCenterColor: CubeColor?

      /** Resets temporal smoothing buffers between faces. */
      fun resetTemporalBuffers()
  }
  ```

- [ ] **Step 2: Move pure-Kotlin vision files to commonMain**

  ```bash
  mkdir -p rubik-vision/src/commonMain/kotlin/com/xmelon/rubik_solver/vision
  # Move these files (no Android imports):
  mv rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/LabConverter.kt \
     rubik-vision/src/commonMain/kotlin/com/xmelon/rubik_solver/vision/
  mv rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorModel.kt \
     rubik-vision/src/commonMain/kotlin/com/xmelon/rubik_solver/vision/
  mv rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ColorDetector.kt \
     rubik-vision/src/commonMain/kotlin/com/xmelon/rubik_solver/vision/
  mv rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/ScanOrchestrator.kt \
     rubik-vision/src/commonMain/kotlin/com/xmelon/rubik_solver/vision/
  ```

- [ ] **Step 3: Move CubeFrameAnalyzer to androidMain**

  ```bash
  mkdir -p rubik-vision/src/androidMain/kotlin/com/xmelon/rubik_solver/vision
  mv rubik-vision/src/main/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt \
     rubik-vision/src/androidMain/kotlin/com/xmelon/rubik_solver/vision/
  ```

  Then add `implements FrameAnalyzer` to `CubeFrameAnalyzer`'s class declaration:
  ```kotlin
  class CubeFrameAnalyzer : ImageAnalysis.Analyzer, FrameAnalyzer {
      // ... existing code unchanged
  }
  ```

- [ ] **Step 4: Move tests to commonTest / androidUnitTest**

  ```bash
  mkdir -p rubik-vision/src/commonTest/kotlin/com/xmelon/rubik_solver/vision
  # These test pure Kotlin code — move to commonTest:
  mv rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/LabConverterTest.kt \
     rubik-vision/src/commonTest/kotlin/com/xmelon/rubik_solver/vision/
  mv rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorModelTest.kt \
     rubik-vision/src/commonTest/kotlin/com/xmelon/rubik_solver/vision/
  mv rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ColorDetectorTest.kt \
     rubik-vision/src/commonTest/kotlin/com/xmelon/rubik_solver/vision/
  # ScanOrchestratorTest uses coroutines — also commonTest:
  mv rubik-vision/src/test/kotlin/com/xmelon/rubik_solver/vision/ScanOrchestratorTest.kt \
     rubik-vision/src/commonTest/kotlin/com/xmelon/rubik_solver/vision/
  ```
  Migrate Truth → kotlin.test in all moved test files (same pattern as Task 2, Step 3).

- [ ] **Step 5: Replace build.gradle.kts**

  Replace `rubik-vision/build.gradle.kts` entirely:
  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.multiplatform)
      alias(libs.plugins.android.library)
  }

  kotlin {
      androidTarget {
          compilations.all {
              kotlinOptions.jvmTarget = "21"
          }
      }
      iosArm64()
      iosX64()
      iosSimulatorArm64()

      sourceSets {
          commonMain.dependencies {
              implementation(project(":rubik-model"))
              implementation(libs.kotlinx.coroutines.core)
          }
          androidMain.dependencies {
              implementation(libs.androidx.core.ktx)
              implementation(libs.camerax.core)
              implementation(libs.camerax.camera2)
              implementation(libs.camerax.lifecycle)
              implementation(libs.camerax.view)
          }
          commonTest.dependencies {
              implementation(libs.kotlin.test)
              implementation(libs.kotlinx.coroutines.core)
          }
      }
  }

  android {
      namespace = "com.xmelon.rubik_solver.vision"
      compileSdk = 36
      defaultConfig { minSdk = 28 }
      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_21
          targetCompatibility = JavaVersion.VERSION_21
      }
  }
  ```

- [ ] **Step 6: Build Android target to verify CubeFrameAnalyzer compiles and pure-Kotlin tests run**

  Run: `./gradlew :rubik-vision:assembleDebug`
  Expected: BUILD SUCCESSFUL

  > **Note:** There is no `jvm()` target in `rubik-vision` (the module has no use for a JVM artifact — it bridges camera hardware). Tests for the pure-Kotlin code run via the Android unit test runner: `./gradlew :rubik-vision:testDebugUnitTest`

- [ ] **Step 7: Commit**

  ```bash
  git add rubik-vision/
  git commit -m "feat: convert rubik-vision to KMP, split common/android source sets"
  ```

---

### Task 4: Convert rubik-solver to KMP with expect/actual

The solver introduces `expect`/`actual` — common code declares the contract, platform code implements it.

**Files:**
- Modify: `rubik-solver/build.gradle.kts`
- Modify: `rubik-solver/src/commonMain/.../solver/CubeSolver.kt` — becomes expect declaration
- Modify: `rubik-solver/src/commonMain/.../solver/SolveResult.kt` — moves to commonMain unchanged
- Create: `rubik-solver/src/androidMain/.../solver/CubeSolver.kt` — actual with Java min2phase
- Move: `rubik-solver/src/androidMain/java/cs/min2phase/` — Java source files
- Create: `rubik-solver/src/iosMain/.../solver/CubeSolver.kt` — stub actual (C++ wired in Task 5)

- [ ] **Step 1: Move SolveResult and existing solver to commonMain**

  ```bash
  mkdir -p rubik-solver/src/commonMain/kotlin/com/xmelon/rubik_solver/solver
  mkdir -p rubik-solver/src/androidMain/kotlin/com/xmelon/rubik_solver/solver
  mkdir -p rubik-solver/src/androidMain/java/cs/min2phase
  mkdir -p rubik-solver/src/iosMain/kotlin/com/xmelon/rubik_solver/solver

  # SolveResult has no platform code — goes to commonMain unchanged
  mv rubik-solver/src/main/kotlin/com/xmelon/rubik_solver/solver/SolveResult.kt \
     rubik-solver/src/commonMain/kotlin/com/xmelon/rubik_solver/solver/

  # Java min2phase sources move to androidMain java source set
  mv rubik-solver/src/main/java/cs/min2phase/*.java \
     rubik-solver/src/androidMain/java/cs/min2phase/
  ```

- [ ] **Step 2: Write the failing test for the expect/actual contract**

  Move the existing test to commonTest first:
  ```bash
  mkdir -p rubik-solver/src/commonTest/kotlin/com/xmelon/rubik_solver/solver
  mv rubik-solver/src/test/kotlin/com/xmelon/rubik_solver/solver/CubeSolverTest.kt \
     rubik-solver/src/commonTest/kotlin/com/xmelon/rubik_solver/solver/
  ```
  Migrate assertions from Truth → kotlin.test (same pattern as Task 2).

  Run: `./gradlew :rubik-solver:jvmTest`
  Expected: FAIL — `CubeSolver` doesn't exist yet in commonMain

- [ ] **Step 3: Create the expect declaration in commonMain**

  Create `rubik-solver/src/commonMain/kotlin/com/xmelon/rubik_solver/solver/CubeSolver.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.solver

  import com.xmelon.rubik_solver.model.CubeState

  expect object CubeSolver {
      fun initialize()
      suspend fun solve(state: CubeState, timeoutMs: Long = 5_000L): SolveResult
  }
  ```

- [ ] **Step 4: Create the Android actual**

  Create `rubik-solver/src/androidMain/kotlin/com/xmelon/rubik_solver/solver/CubeSolver.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.solver

  import com.xmelon.rubik_solver.model.CubeColor
  import com.xmelon.rubik_solver.model.CubeState
  import com.xmelon.rubik_solver.model.Face
  import com.xmelon.rubik_solver.model.Move
  import cs.min2phase.Search
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import kotlinx.coroutines.withTimeoutOrNull

  actual object CubeSolver {
      private const val MAX_DEPTH = 21
      private const val PROBE_MAX = 1000L
      private const val PROBE_MIN = 0L

      @Synchronized
      actual fun initialize() {
          if (!Search.isInited()) Search.init()
      }

      actual suspend fun solve(state: CubeState, timeoutMs: Long): SolveResult =
          withContext(Dispatchers.Default) {
              initialize()
              if (state.isSolved()) return@withContext SolveResult.Success(emptyList(), 0)
              withTimeoutOrNull(timeoutMs) {
                  try {
                      val result = Search().solution(
                          state.toFaceletString(), MAX_DEPTH, PROBE_MAX, PROBE_MIN, 0
                      )
                      if (result.startsWith("Error 1")) diagnoseColorCounts(state)
                      else if (result.startsWith("Error")) parseError(result)
                      else { val moves = Move.parseSequence(result); SolveResult.Success(moves, moves.size) }
                  } catch (e: Exception) {
                      SolveResult.Error("Solver error: ${e.message}")
                  }
              } ?: SolveResult.Error("Solver timed out after ${timeoutMs / 1000}s")
          }

      // Copy diagnoseColorCounts() and parseError() from old CubeSolver.kt unchanged
  }
  ```

- [ ] **Step 5: Create the iOS stub actual**

  Create `rubik-solver/src/iosMain/kotlin/com/xmelon/rubik_solver/solver/CubeSolver.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.solver

  import com.xmelon.rubik_solver.model.CubeState

  // Stub — wired to min2phaseCXX in Task 5
  actual object CubeSolver {
      actual fun initialize() { /* TODO: Task 5 */ }
      actual suspend fun solve(state: CubeState, timeoutMs: Long): SolveResult =
          SolveResult.Error("iOS solver not yet implemented — see Task 5")
  }
  ```

- [ ] **Step 6: Replace build.gradle.kts**

  Replace `rubik-solver/build.gradle.kts` entirely:
  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.multiplatform)
  }

  kotlin {
      jvm()
      androidTarget {
          compilations.all { kotlinOptions.jvmTarget = "21" }
      }
      iosArm64()
      iosX64()
      iosSimulatorArm64()

      sourceSets {
          commonMain.dependencies {
              implementation(project(":rubik-model"))
              implementation(libs.kotlinx.coroutines.core)
          }
          commonTest.dependencies {
              implementation(libs.kotlin.test)
              implementation(libs.kotlinx.coroutines.core)
          }
      }
  }
  ```

- [ ] **Step 7: Verify Android build with the new actual compiles**

  Run: `./gradlew :composeApp:assembleDebug`
  Expected: BUILD SUCCESSFUL — the Android actual (which calls the Java min2phase) compiles and links.

  > **Note:** `rubik-solver` declares `androidTarget()` (needed for Java interop with `cs.min2phase`), not `jvm()`. There is no `jvmTest` task. Test verification happens through the full Android build here and through the iOS simulator test in Task 5.

- [ ] **Step 8: Verify Android app still builds**

  Run: `./gradlew :app:assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

  ```bash
  git add rubik-solver/
  git commit -m "feat: convert rubik-solver to KMP with expect/actual (iOS stub)"
  ```

---

## Phase 2: iOS Solver via min2phaseCXX

### Task 5: Wire min2phaseCXX as iOS solver actual

This task builds the C++ solver as an XCFramework and connects it to the `iosMain` actual.
**Requires macOS with Xcode 16+ installed.**

**Files:**
- Create: `rubik-solver/min2phaseCXX/` (git submodule)
- Create: `rubik-solver/src/iosMain/cinterop/min2phase.def`
- Modify: `rubik-solver/src/iosMain/kotlin/.../solver/CubeSolver.kt` — replace stub
- Modify: `rubik-solver/build.gradle.kts` — add cinterop config
- Create: `rubik-solver/ios-framework/` — CMake build for XCFramework

- [ ] **Step 1: Add min2phaseCXX as a git submodule**

  ```bash
  git submodule add https://github.com/lilborgo/min2phaseCXX rubik-solver/min2phaseCXX
  git submodule update --init
  ```

- [ ] **Step 2: Write a shell script to build the XCFramework**

  Create `rubik-solver/build-ios-framework.sh`:
  ```bash
  #!/bin/bash
  set -e
  REPO_ROOT=$(git rev-parse --show-toplevel)
  SRC="$REPO_ROOT/rubik-solver/min2phaseCXX"
  OUT="$REPO_ROOT/rubik-solver/min2phaseXCFramework"

  build_slice() {
    local ARCH=$1 SDK=$2 TARGET=$3
    cmake -S "$SRC" -B "$SRC/build-$ARCH" \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_SYSTEM_NAME=iOS \
      -DCMAKE_OSX_SYSROOT=$(xcrun --sdk $SDK --show-sdk-path) \
      -DCMAKE_OSX_ARCHITECTURES=$ARCH \
      -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
      -DCMAKE_C_COMPILER=$(xcrun --sdk $SDK -f clang) \
      -DCMAKE_CXX_COMPILER=$(xcrun --sdk $SDK -f clang++) \
      # Exclude the HTTP server — not needed and has Linux-only socket code
      -DBUILD_SERVER=OFF
    cmake --build "$SRC/build-$ARCH" --target min2phase
  }

  build_slice arm64     iphoneos         arm64-apple-ios15.0
  build_slice x86_64   iphonesimulator  x86_64-apple-ios15.0-simulator

  # arm64 simulator needs a distinct output dir from arm64 device — pass a suffix
  ARCH=arm64 SDK=iphonesimulator TARGET=arm64-apple-ios15.0-simulator
  cmake -S "$SRC" -B "$SRC/build-arm64-sim" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT=$(xcrun --sdk iphonesimulator --show-sdk-path) \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
    -DBUILD_SERVER=OFF
  cmake --build "$SRC/build-arm64-sim" --target min2phase

  # Combine simulator slices (x86_64 + arm64)
  mkdir -p "$SRC/build-sim-fat"
  lipo -create \
    "$SRC/build-x86_64/libmin2phase.a" \
    "$SRC/build-arm64-sim/libmin2phase.a" \
    -output "$SRC/build-sim-fat/libmin2phase.a"

  # Build XCFramework
  xcodebuild -create-xcframework \
    -library "$SRC/build-arm64/libmin2phase.a" \
    -headers "$SRC/include" \
    -library "$SRC/build-sim-fat/libmin2phase.a" \
    -headers "$SRC/include" \
    -output "$OUT/min2phase.xcframework"

  echo "XCFramework built at $OUT/min2phase.xcframework"
  ```
  ```bash
  chmod +x rubik-solver/build-ios-framework.sh
  ```

  > **Note:** If the min2phaseCXX CMakeLists.txt doesn't have a `BUILD_SERVER` option, comment it out or manually exclude `http.cpp` from the cmake build by editing `CMakeLists.txt` in the submodule. The HTTP server file has Linux-only socket headers and will not compile on iOS.

- [ ] **Step 3: Run the build script on macOS**

  ```bash
  ./rubik-solver/build-ios-framework.sh
  ```
  Expected: `rubik-solver/min2phaseXCFramework/min2phase.xcframework` directory created

- [ ] **Step 4: Create a C wrapper for the C++ solver**

  Kotlin/Native cinterop only supports `language = C`. The min2phaseCXX API is C++, so it needs a thin `extern "C"` wrapper.

  Create `rubik-solver/min2phaseCXX/include/min2phase_c.h`:
  ```c
  #pragma once
  #ifdef __cplusplus
  extern "C" {
  #endif

  void   min2phase_c_init(void);
  /** Returns a statically-allocated solution string. Not thread-safe — copy immediately. */
  const char* min2phase_c_solve(
      const char* facelets,
      int         max_depth,
      int         probe_max,
      int         probe_min,
      int         verbose
  );

  #ifdef __cplusplus
  }
  #endif
  ```

  Create `rubik-solver/min2phaseCXX/include/min2phase_c.cpp` (in `src/` if CMake separates them, but placing both in `include/` is fine for a thin wrapper):
  ```cpp
  #include "min2phase_c.h"
  #include "min2phase/min2phase.h"
  #include <string>

  static std::string g_last_result;

  extern "C" {
      void min2phase_c_init(void) {
          min2phase::init();
      }

      const char* min2phase_c_solve(
          const char* facelets, int max_depth, int probe_max, int probe_min, int verbose
      ) {
          g_last_result = min2phase::solve(
              std::string(facelets), (int8_t)max_depth, probe_max, probe_min, (int8_t)verbose
          );
          return g_last_result.c_str();
      }
  }
  ```

  Add `min2phase_c.cpp` to the CMake build: in `min2phaseCXX/CMakeLists.txt`, add it to the `min2phase` target's source list.

- [ ] **Step 5: Create the cinterop definition file**

  Create `rubik-solver/src/iosMain/cinterop/min2phase.def`:
  ```
  headers = min2phase/min2phase_c.h
  headerFilter = min2phase/**
  staticLibraries = libmin2phase.a
  libraryPaths = ../min2phaseXCFramework/min2phase.xcframework/ios-arm64
  language = C
  ```
  The C wrapper header is at `min2phaseCXX/include/min2phase_c.h`. The `includeDirs` in `build.gradle.kts` (Step 6 below) points to `min2phaseCXX/include`, which is also where `min2phase/min2phase.h` lives, so both headers resolve correctly.

- [ ] **Step 6: Add cinterop to rubik-solver/build.gradle.kts**

  Inside the `kotlin {}` block, for each iOS target add:
  ```kotlin
  iosArm64 {
      compilations.getByName("main") {
          cinterops {
              val min2phase by creating {
                  defFile(project.file("src/iosMain/cinterop/min2phase.def"))
                  includeDirs("min2phaseCXX/include")
              }
          }
      }
  }
  // repeat for iosX64, iosSimulatorArm64 (adjust libraryPaths per architecture)
  ```

- [ ] **Step 7: Write the failing test**

  In `rubik-solver/src/commonTest/.../CubeSolverTest.kt`, there should be a test like:
  ```kotlin
  @Test
  fun `solved cube returns empty move list`() = runTest {
      CubeSolver.initialize()
      val result = CubeSolver.solve(CubeState.solved())
      assertTrue(result is SolveResult.Success)
      assertEquals(0, (result as SolveResult.Success).moveCount)
  }
  ```
  Run on iOS simulator: `./gradlew :rubik-solver:iosSimulatorArm64Test`
  Expected: FAIL (stub returns Error)

- [ ] **Step 8: Replace the iOS stub with real implementation**

  Replace `rubik-solver/src/iosMain/kotlin/com/xmelon/rubik_solver/solver/CubeSolver.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.solver

  import com.xmelon.rubik_solver.model.CubeState
  import com.xmelon.rubik_solver.model.Move
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import kotlinx.coroutines.withTimeoutOrNull
  import min2phase.*   // generated from cinterop (C wrapper functions)

  actual object CubeSolver {
      actual fun initialize() {
          min2phase_c_init()
      }

      actual suspend fun solve(state: CubeState, timeoutMs: Long): SolveResult =
          withContext(Dispatchers.Default) {
              initialize()
              if (state.isSolved()) return@withContext SolveResult.Success(emptyList(), 0)
              withTimeoutOrNull(timeoutMs) {
                  try {
                      val result = min2phase_c_solve(
                          state.toFaceletString(),
                          21,   // maxDepth
                          1000, // probeMax
                          0,    // probeMin
                          0     // verbose
                      )?.toKString() ?: return@withTimeoutOrNull SolveResult.Error("Null from solver")
                      if (result.startsWith("Error")) SolveResult.Error(result)
                      else { val moves = Move.parseSequence(result); SolveResult.Success(moves, moves.size) }
                  } catch (e: Exception) {
                      SolveResult.Error("Solver error: ${e.message}")
                  }
              } ?: SolveResult.Error("Solver timed out after ${timeoutMs / 1000}s")
          }
  }
  ```

- [ ] **Step 9: Run iOS tests**

  Run: `./gradlew :rubik-solver:iosSimulatorArm64Test`
  Expected: BUILD SUCCESSFUL, all solver tests pass on iOS simulator

- [ ] **Step 10: Commit**

  ```bash
  git add rubik-solver/
  git commit -m "feat: wire min2phaseCXX as iOS solver via Kotlin/Native cinterop"
  ```

---

## Phase 3: Compose Multiplatform UI

### Task 6: Rename app → composeApp and add Compose Multiplatform

This is the largest structural change. The Android app still works identically after this task.

**Files:**
- Rename: `app/` → `composeApp/`
- Modify: `settings.gradle.kts`
- Modify: `composeApp/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Rename the module in settings.gradle.kts**

  In `settings.gradle.kts`, change:
  ```kotlin
  include(":app")
  ```
  to:
  ```kotlin
  include(":composeApp")
  project(":composeApp").projectDir = file("composeApp")
  ```

- [ ] **Step 2: Rename the directory**

  ```bash
  mv app composeApp
  ```

- [ ] **Step 3: Verify Android build still works before touching build.gradle**

  Run: `./gradlew :composeApp:assembleDebug`
  Expected: BUILD SUCCESSFUL (nothing changed yet except the name)

- [ ] **Step 4: Replace composeApp/build.gradle.kts with KMP + CMP**

  Replace `composeApp/build.gradle.kts` entirely:
  ```kotlin
  import com.google.firebase.appdistribution.gradle.firebaseAppDistribution

  plugins {
      alias(libs.plugins.kotlin.multiplatform)
      alias(libs.plugins.android.application)
      alias(libs.plugins.compose.multiplatform)
      alias(libs.plugins.kotlin.compose)
      alias(libs.plugins.google.services)
      alias(libs.plugins.firebase.appdistribution)
  }

  kotlin {
      androidTarget {
          compilations.all { kotlinOptions.jvmTarget = "21" }
      }
      iosArm64()
      iosX64()
      iosSimulatorArm64()

      sourceSets {
          commonMain.dependencies {
              implementation(project(":rubik-model"))
              implementation(project(":rubik-solver"))
              implementation(project(":rubik-vision"))
              implementation(compose.runtime)
              implementation(compose.foundation)
              implementation(compose.material3)
              implementation(compose.materialIconsExtended)
              implementation(compose.components.resources)
              implementation(libs.lifecycle.viewmodel.kmp)
              implementation(libs.kotlinx.coroutines.core)
          }
          androidMain.dependencies {
              implementation(libs.androidx.core.ktx)
              implementation(libs.androidx.lifecycle.runtime)
              implementation(libs.androidx.lifecycle.viewmodel.compose)
              implementation(libs.activity.compose)
              implementation(libs.camerax.core)
              implementation(libs.camerax.camera2)
              implementation(libs.camerax.lifecycle)
              implementation(libs.camerax.view)
          }
      }
  }

  android {
      namespace = "com.xmelon.rubik_solver"
      compileSdk = 36
      defaultConfig {
          applicationId = "com.xmelon.rubik_solver"
          minSdk = 28
          targetSdk = 36
          versionCode = 1
          versionName = "0.1.0"
      }
      buildTypes {
          release {
              isMinifyEnabled = true
              proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
              firebaseAppDistribution {
                  artifactType = "APK"
                  serviceCredentialsFile = "composeApp/xmelon-rubik-solver-firebase-admin.json"
              }
          }
          getByName("debug") {
              firebaseAppDistribution {
                  artifactType = "APK"
                  serviceCredentialsFile = "composeApp/xmelon-rubik-solver-firebase-admin.json"
              }
          }
      }
      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_21
          targetCompatibility = JavaVersion.VERSION_21
      }
      buildFeatures {
          compose = true
          buildConfig = true
      }
  }
  ```

- [ ] **Step 5: Verify Android build still works**

  Run: `./gradlew :composeApp:assembleDebug`
  Expected: BUILD SUCCESSFUL

  > **Firebase note:** The `google-services` and `firebase-appdistribution` plugins apply Android-only manifest processing. When `kotlin.multiplatform` is the root plugin, applying these must be done carefully. If Gradle reports configuration errors for the iOS targets, add `afterEvaluate { /* apply google-services only for android variants */ }` guards, or apply the firebase plugins only in `androidMain`'s build configuration block. Verify the iOS framework compilation (`./gradlew :composeApp:compileKotlinIosArm64`) succeeds alongside the Android build.

- [ ] **Step 6: Commit**

  ```bash
  git add composeApp/ settings.gradle.kts gradle/libs.versions.toml
  git commit -m "build: rename app → composeApp, add Compose Multiplatform plugin"
  ```

---

### Task 7: Move all UI code to commonMain

**Files:** All files under `composeApp/src/main/kotlin/` move to `composeApp/src/commonMain/kotlin/`

- [ ] **Step 1: Create directory structure**

  ```bash
  mkdir -p composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/ui
  mkdir -p composeApp/src/androidMain/kotlin/com/xmelon/rubik_solver
  mkdir -p composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver
  mkdir -p composeApp/src/commonMain/composeResources/values
  ```

- [ ] **Step 2: Create PlatformLog.kt expect/actual**

  Create `composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/PlatformLog.kt`:
  ```kotlin
  package com.xmelon.rubik_solver

  expect fun platformLog(tag: String, msg: String)
  ```

  Create `composeApp/src/androidMain/kotlin/com/xmelon/rubik_solver/PlatformLog.kt`:
  ```kotlin
  package com.xmelon.rubik_solver
  actual fun platformLog(tag: String, msg: String) { android.util.Log.i(tag, msg) }
  ```

  Create `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/PlatformLog.kt`:
  ```kotlin
  package com.xmelon.rubik_solver
  import platform.Foundation.NSLog
  actual fun platformLog(tag: String, msg: String) { NSLog("$tag: $msg") }
  ```

- [ ] **Step 3: Move pure UI files to commonMain**

  ```bash
  # Move all UI files that have no Android-specific imports:
  for f in Math3D RenderingConstants ScreenFooters ScreenHeaders ScreenProgressBar \
            CubeColors FaceColorOverrides Cube3DView; do
    mv composeApp/src/main/kotlin/com/xmelon/rubik_solver/ui/${f}.kt \
       composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/ui/
  done
  ```

- [ ] **Step 4: Move MainScreen.kt to commonMain, fix Android-only imports**

  ```bash
  mv composeApp/src/main/kotlin/com/xmelon/rubik_solver/ui/MainScreen.kt \
     composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/ui/
  ```

  `MainScreen.kt` has two Android-only imports that must be removed or abstracted:

  **a) `rememberLauncherForActivityResult`** — from `androidx.activity:activity-compose`, Android-only.
  Extract the camera permission request into `expect`/`actual`:

  Create `composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/CameraPermission.kt`:
  ```kotlin
  package com.xmelon.rubik_solver
  import androidx.compose.runtime.Composable

  @Composable
  expect fun rememberCameraPermissionState(onResult: (Boolean) -> Unit): () -> Unit
  ```

  Create `composeApp/src/androidMain/kotlin/com/xmelon/rubik_solver/CameraPermission.kt`:
  ```kotlin
  package com.xmelon.rubik_solver
  import android.Manifest
  import androidx.activity.compose.rememberLauncherForActivityResult
  import androidx.activity.result.contract.ActivityResultContracts
  import androidx.compose.runtime.Composable

  @Composable
  actual fun rememberCameraPermissionState(onResult: (Boolean) -> Unit): () -> Unit {
      val launcher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission(), onResult
      )
      return { launcher.launch(Manifest.permission.CAMERA) }
  }
  ```

  Create `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/CameraPermission.kt`:
  ```kotlin
  package com.xmelon.rubik_solver
  import androidx.compose.runtime.Composable
  import platform.AVFoundation.*

  @Composable
  actual fun rememberCameraPermissionState(onResult: (Boolean) -> Unit): () -> Unit = {
      AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted -> onResult(granted) }
  }
  ```

  In `MainScreen.kt`, replace the launcher usage with `rememberCameraPermissionState { ... }`.

  **b) `ContextCompat.checkSelfPermission`** — Android-only.
  Replace with `checkCameraPermission()` from Step 5 below.

- [ ] **Step 5: Add `checkCameraPermission` to the CameraPermission files from Step 4**

  > **Note:** `CameraPermission.kt` files were created in Step 4. **Append** to them — do not recreate.

  Append to `composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/CameraPermission.kt`:
  ```kotlin
  expect fun checkCameraPermission(): Boolean
  ```

  Append to `composeApp/src/androidMain/kotlin/com/xmelon/rubik_solver/CameraPermission.kt`:
  ```kotlin
  // Permission status check. Returning true is safe here — the composable launcher
  // in rememberCameraPermissionState above handles the actual Android permission flow.
  actual fun checkCameraPermission(): Boolean = true
  ```

  Append to `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/CameraPermission.kt`:
  ```kotlin
  // platform.AVFoundation.* already imported above
  actual fun checkCameraPermission(): Boolean =
      AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
          AVAuthorizationStatusAuthorized
  ```

  In `MainScreen.kt`, replace `ContextCompat.checkSelfPermission(...)` with `checkCameraPermission()`.

- [ ] **Step 6: Create CameraPreview expect declaration in commonMain**

  Create `composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/ui/CameraPreview.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.ui

  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Modifier
  import com.xmelon.rubik_solver.vision.FrameAnalyzer

  @Composable
  expect fun CameraPreviewView(
      analyzer: FrameAnalyzer,
      modifier: Modifier = Modifier
  )
  ```

  Move the existing CameraX code to androidMain:
  ```bash
  mv composeApp/src/main/kotlin/com/xmelon/rubik_solver/ui/CameraPreview.kt \
     composeApp/src/androidMain/kotlin/com/xmelon/rubik_solver/ui/
  ```

  Add `actual` keyword to the existing `@Composable fun CameraPreviewView(...)` in the androidMain file.

  Create iOS stub (full implementation in Task 8):
  `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/ui/CameraPreview.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.ui
  import androidx.compose.material3.Text
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Modifier
  import com.xmelon.rubik_solver.vision.FrameAnalyzer

  @Composable
  actual fun CameraPreviewView(analyzer: FrameAnalyzer, modifier: Modifier) {
      Text("Camera coming in Task 8") // stub
  }
  ```

- [ ] **Step 7: Extract App.kt and HomeScreen.kt from MainActivity**

  Create `composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/App.kt`:
  ```kotlin
  package com.xmelon.rubik_solver

  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.Scaffold
  import androidx.compose.material3.SnackbarHost
  import androidx.compose.material3.SnackbarHostState
  import androidx.compose.runtime.Composable
  import androidx.compose.runtime.remember

  // NOTE: `viewModel()` composable helper is Android-only and cannot be in commonMain.
  // AppViewModel is instead constructed by the platform entry point and passed in.
  @Composable
  fun App(vm: AppViewModel) {
      MaterialTheme {
          val snackbarHostState = remember { SnackbarHostState() }
          Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
              // ... extract the Column + when(vm.currentScreen) body from MainActivity here
          }
      }
  }
  ```

  In `composeApp/src/androidMain/kotlin/com/xmelon/rubik_solver/MainActivity.kt`:
  ```kotlin
  class MainActivity : ComponentActivity() {
      private val vm: AppViewModel by viewModels {
          // Provide the Android CubeFrameAnalyzer here, where it is in scope
          object : ViewModelProvider.Factory {
              override fun <T : ViewModel> create(modelClass: Class<T>): T {
                  @Suppress("UNCHECKED_CAST")
                  return AppViewModel(CubeFrameAnalyzer()) as T
              }
          }
      }
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContent { App(vm) }
      }
  }
  ```

  In `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/MainViewController.kt`:
  ```kotlin
  fun MainViewController() = ComposeUIViewController {
      val vm = remember { AppViewModel(CubeFrameAnalyzer()) }
      App(vm)
  }
  ```

  Create `composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/HomeScreen.kt`:
  Extract the `HomeScreen` composable function from `MainActivity.kt` into this file unchanged.

- [ ] **Step 8: Move AppViewModel.kt to commonMain and change analyzer type**

  ```bash
  mv composeApp/src/main/kotlin/com/xmelon/rubik_solver/AppViewModel.kt \
     composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/
  ```

  **Critical:** `AppViewModel` currently has `val analyzer = CubeFrameAnalyzer()` — `CubeFrameAnalyzer` is Android-only and cannot be referenced in `commonMain`. Change it to use the `FrameAnalyzer` interface:

  ```kotlin
  // Before (Android-only, will not compile in commonMain):
  class AppViewModel : ViewModel() {
      val analyzer = CubeFrameAnalyzer()

  // After (interface, works in commonMain):
  class AppViewModel(val analyzer: FrameAnalyzer) : ViewModel() {
  ```

  The `CubeFrameAnalyzer()` instance is constructed by the platform entry point and injected (see Step 7 above).

  Replace `android.util.Log.i(TAG, msg)` with `platformLog(TAG, msg)`.
  Replace `BuildConfig.DEBUG` with a compile-time constant: create
  `composeApp/src/commonMain/kotlin/com/xmelon/rubik_solver/BuildConfig.kt`:
  ```kotlin
  package com.xmelon.rubik_solver
  expect val isDebugBuild: Boolean
  ```
  Android actual: `actual val isDebugBuild: Boolean = BuildConfig.DEBUG`
  iOS actual: `actual val isDebugBuild: Boolean = false` (or use a build flag)

- [ ] **Step 9: Migrate strings to composeResources**

  Copy `composeApp/src/main/res/values/strings.xml` to
  `composeApp/src/commonMain/composeResources/values/strings.xml` — same XML format, no changes needed.

  In all composables that use `stringResource(R.string.foo)`, replace with:
  ```kotlin
  // Before:
  import androidx.compose.ui.res.stringResource
  import com.xmelon.rubik_solver.R
  stringResource(R.string.scan_button_edit)

  // After:
  import <GENERATED_PACKAGE>.generated.resources.Res
  import <GENERATED_PACKAGE>.generated.resources.scan_button_edit
  import org.jetbrains.compose.resources.stringResource
  stringResource(Res.string.scan_button_edit)
  ```

  > **Important:** The generated resource accessor package (`<GENERATED_PACKAGE>`) is derived from the Gradle module name and group. After running `./gradlew :composeApp:generateComposeResClass`, check the actual generated source under `composeApp/build/generated/compose/resourceGenerator/kotlin/` to find the exact package name, then update all import statements. Do **not** assume the package name before generating.

  Do this for every `stringResource(R.string.*)` call across all moved composable files.

- [ ] **Step 10: Strip MainActivity down to Android entry point only**

  Replace `composeApp/src/main/kotlin/com/xmelon/rubik_solver/MainActivity.kt` with the
  androidMain version — it should now just be:
  ```kotlin
  package com.xmelon.rubik_solver

  import android.os.Bundle
  import androidx.activity.ComponentActivity
  import androidx.activity.compose.setContent

  // NOTE: The full MainActivity with ViewModelProvider.Factory was defined in Step 7 above.
  // This step only moves it to androidMain — do not replace its content with a simplified version.
  // The correct final form (from Step 7) constructs AppViewModel(CubeFrameAnalyzer()) and calls App(vm).
  ```

  Move this file:
  ```bash
  mv composeApp/src/main/kotlin/com/xmelon/rubik_solver/MainActivity.kt \
     composeApp/src/androidMain/kotlin/com/xmelon/rubik_solver/
  ```

- [ ] **Step 11: Build and verify Android app unchanged**

  Run: `./gradlew :composeApp:assembleDebug && ./gradlew :composeApp:installDebug`
  Expected: BUILD SUCCESSFUL, app installs and runs correctly on Android

- [ ] **Step 12: Verify iOS targets compile**

  Run: `./gradlew :composeApp:compileKotlinIosArm64`
  Expected: BUILD SUCCESSFUL (camera is stubbed, but everything else compiles)

- [ ] **Step 13: Commit**

  ```bash
  git add composeApp/
  git commit -m "feat: migrate all UI to Compose Multiplatform commonMain"
  ```

---

## Phase 4: iOS Camera and App Entry Point

### Task 8: Implement iOS camera with AVFoundation

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/ui/CameraPreview.kt`
- Modify: `composeApp/src/iosMain/cinterop/avfoundation.def` (if needed — AVFoundation is usually available via `platform.AVFoundation`)

AVFoundation is available in Kotlin/Native iOS targets via the built-in `platform.AVFoundation` package — no cinterop needed.

- [ ] **Step 1: Implement iOS CubeFrameAnalyzer in iosMain**

  Create `rubik-vision/src/iosMain/kotlin/com/xmelon/rubik_solver/vision/CubeFrameAnalyzer.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.vision

  import com.xmelon.rubik_solver.model.CubeColor
  import platform.AVFoundation.*
  import platform.CoreVideo.CVPixelBufferRef
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow

  class CubeFrameAnalyzer : FrameAnalyzer {
      override var expectedCenterColor: CubeColor? = null
      val colorDetector = ColorDetector()

      private val _detectedColors = MutableStateFlow<List<CubeColor>>(emptyList())
      val detectedColors: StateFlow<List<CubeColor>> = _detectedColors

      override fun resetTemporalBuffers() {
          // Reset temporal smoothing buffers — port the logic from Android CubeFrameAnalyzer
      }

      /**
       * Called by the iOS AVCaptureVideoDataOutput delegate.
       * pixelBuffer: CVPixelBufferRef locked by the caller.
       */
      fun analyzeFrame(pixelBuffer: CVPixelBufferRef) {
          // 1. Lock pixel buffer
          // 2. Extract the center 9-tile grid region (same crop logic as Android)
          // 3. Sample RGB at each tile center
          // 4. Run colorDetector.classify() on each sample
          // 5. Publish to _detectedColors
          // Port the sampling logic from CubeFrameAnalyzer.kt (Android) — the math is identical,
          // only the pixel access API differs.
      }
  }
  ```

  > **Note:** The color detection math (grid sampling, LAB conversion, classification) is identical to Android. Port it directly from the Android `CubeFrameAnalyzer`. The only difference is pixel data access: Android uses `ImageProxy.planes[0].buffer`, iOS uses `CVPixelBufferGetBaseAddress` on the locked `CVPixelBufferRef`.

- [ ] **Step 2: Implement CameraPreviewView actual for iOS**

  Replace `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/ui/CameraPreview.kt`:
  ```kotlin
  package com.xmelon.rubik_solver.ui

  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.interop.UIKitView
  import com.xmelon.rubik_solver.vision.CubeFrameAnalyzer
  import com.xmelon.rubik_solver.vision.FrameAnalyzer
  import platform.AVFoundation.*
  import platform.UIKit.UIView
  import platform.QuartzCore.CALayer
  import platform.AVFoundation.AVCaptureVideoPreviewLayer  // Note: AVCaptureVideoPreviewLayer is in AVFoundation, not QuartzCore

  @Composable
  actual fun CameraPreviewView(analyzer: FrameAnalyzer, modifier: Modifier) {
      val iosAnalyzer = analyzer as CubeFrameAnalyzer
      UIKitView(
          modifier = modifier,
          factory = {
              val session = AVCaptureSession().apply {
                  sessionPreset = AVCaptureSessionPreset1280x720
                  val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)!!
                  val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)!!
                  addInput(input)
                  val output = AVCaptureVideoDataOutput().apply {
                      setSampleBufferDelegate(
                          // Create an Objective-C delegate that calls iosAnalyzer.analyzeFrame()
                          object : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
                              override fun captureOutput(
                                  output: AVCaptureOutput,
                                  didOutputSampleBuffer: CMSampleBufferRef?,
                                  fromConnection: AVCaptureConnection
                              ) {
                                  val pixelBuffer = CMSampleBufferGetImageBuffer(didOutputSampleBuffer) ?: return
                                  iosAnalyzer.analyzeFrame(pixelBuffer)
                              }
                          },
                          queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)
                      )
                  }
                  addOutput(output)
              }
              val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
                  videoGravity = AVLayerVideoGravityResizeAspectFill
              }
              session.startRunning()
              UIView().also { it.layer.addSublayer(previewLayer) }
          },
          update = { /* nothing */ }
      )
  }
  ```

- [ ] **Step 3: Handle camera permission on iOS**

  In `composeApp/src/iosMain/kotlin/com/xmelon/rubik_solver/CameraPermission.kt`:
  ```kotlin
  package com.xmelon.rubik_solver
  import platform.AVFoundation.*

  actual fun checkCameraPermission(): Boolean =
      AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
          AVAuthorizationStatusAuthorized
  ```

  Add a permission request call in `App.kt` iOS path. The iOS system will show the permission dialog on first camera access if `NSCameraUsageDescription` is in `Info.plist` (set up in Task 9).

- [ ] **Step 4: Verify the iOS camera code compiles**

  Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

  ```bash
  git add rubik-vision/ composeApp/
  git commit -m "feat: implement iOS camera with AVFoundation"
  ```

---

### Task 9: Create the iosApp Xcode project

This is the thin Swift shell that hosts the shared Compose UI.
**Requires macOS with Xcode 16+.**

**Files:**
- Create: `iosApp/iosApp.xcodeproj/`
- Create: `iosApp/iosApp/iOSApp.swift`
- Create: `iosApp/iosApp/ContentView.swift`
- Create: `iosApp/iosApp/Info.plist`

- [ ] **Step 1: Generate the Xcode project using the KMP template**

  The easiest way: use the KMP project wizard at https://kmp.jetbrains.com to generate a new project with iOS + Android, then copy just the `iosApp/` directory into this repo. Alternatively:

  ```bash
  # Create directory structure manually
  mkdir -p iosApp/iosApp
  ```

- [ ] **Step 2: Create iOSApp.swift**

  Create `iosApp/iosApp/iOSApp.swift`:
  ```swift
  import SwiftUI

  @main
  struct iOSApp: App {
      var body: some Scene {
          WindowGroup {
              ContentView()
          }
      }
  }
  ```

- [ ] **Step 3: Create ContentView.swift**

  Create `iosApp/iosApp/ContentView.swift`:
  ```swift
  import SwiftUI
  import ComposeApp   // the Kotlin framework compiled from :composeApp

  struct ContentView: View {
      var body: some View {
          ComposeView()
              .ignoresSafeArea(.keyboard)
      }
  }

  struct ComposeView: UIViewControllerRepresentable {
      func makeUIViewController(context: Context) -> UIViewController {
          MainViewControllerKt.MainViewController()
      }
      func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
  }
  ```

- [ ] **Step 4: Verify MainViewController.kt in iosMain**

  This file was already created in Task 7 Step 7. Verify it reads exactly:
  ```kotlin
  package com.xmelon.rubik_solver

  import androidx.compose.runtime.remember
  import androidx.compose.ui.window.ComposeUIViewController

  fun MainViewController() = ComposeUIViewController {
      val vm = remember { AppViewModel(CubeFrameAnalyzer()) }
      App(vm)
  }
  ```
  > **Important:** `App()` requires `vm: AppViewModel` — do **not** call `App()` without it. If this file doesn't match, update it to the above.

- [ ] **Step 5: Create Info.plist with camera permission**

  Create `iosApp/iosApp/Info.plist`:
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
      "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0">
  <dict>
      <key>NSCameraUsageDescription</key>
      <string>Camera is needed to scan your Rubik's Cube faces.</string>
      <key>UILaunchScreen</key>
      <dict/>
  </dict>
  </plist>
  ```

- [ ] **Step 6: Configure Xcode project**

  In Xcode:
  1. Open `iosApp/iosApp.xcodeproj`
  2. Add a framework dependency: `composeApp.framework` (produced by Gradle's `embedAndSignAppleFrameworkForXcode` task)
  3. In Build Phases → Run Script, add:
     ```bash
     cd "$SRCROOT/.."
     ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
     ```
  4. Set minimum deployment target to iOS 15.0
  5. Set bundle ID (e.g., `com.xmelon.rubikSolver`)

- [ ] **Step 7: Build and run on iOS Simulator**

  In Xcode: Product → Run (iPhone 15 simulator)
  Expected: App launches, home screen shows 3D cube, scan screen shows camera stub (or real camera if running on device)

- [ ] **Step 8: Build and run on a physical iOS device**

  Connect iPhone, select as target in Xcode, Product → Run.
  Expected: Camera permission dialog appears, camera preview shows, cube scanning works.

- [ ] **Step 9: Run Android build to confirm nothing regressed**

  Run: `./gradlew :composeApp:assembleDebug && ./gradlew :composeApp:installDebug`
  Expected: Android app unchanged

- [ ] **Step 10: Final commit**

  ```bash
  git add iosApp/ composeApp/src/iosMain/
  git commit -m "feat: add iosApp Xcode entry point, complete iOS port"
  ```

---

## Appendix: Version Compatibility

| Library | Notes |
|---|---|
| Kotlin Multiplatform | Same version as `kotlin` in `libs.versions.toml` (2.3.10) |
| Compose Multiplatform | Check JetBrains compatibility table for Kotlin 2.3.x |
| lifecycle-viewmodel KMP | `androidx.lifecycle:lifecycle-viewmodel` 2.10.0 supports KMP |
| kotlinx-coroutines | Already at 1.10.1 — supports KMP |
| min2phaseCXX | MIT license, last updated April 2025, pure C++14 |

## Appendix: What Stays Android-Only (Never Moves to commonMain)

| Code | Reason |
|---|---|
| `MainActivity.kt` | Android Activity lifecycle entry point |
| `CameraPreview` (androidMain) | CameraX is Android-only |
| `CubeFrameAnalyzer` (androidMain) | ImageProxy is CameraX-only |
| Java min2phase sources | JVM-only language |
| Firebase / Google Services config | Android distribution tooling |

## Appendix: Testing Strategy Per Phase

| Phase | How to verify |
|---|---|
| Phase 1 | `./gradlew test` — all unit tests pass on JVM |
| Phase 2 | `./gradlew :rubik-solver:iosSimulatorArm64Test` |
| Phase 3 | `./gradlew :composeApp:assembleDebug && installDebug` — Android unchanged |
| Phase 4 | Run on iOS Simulator + physical device |
