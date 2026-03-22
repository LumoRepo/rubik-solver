#!/bin/bash
# Build min2phaseCXX + C wrapper as an XCFramework for iOS.
# Builds arm64 device + arm64 simulator (Apple Silicon CI / M-series Mac).
# x86_64 simulator omitted — no longer needed for arm64-only CI or M-series Macs.
# Output: rubik-solver/min2phaseXCFramework/min2phase.xcframework
set -e

REPO_ROOT=$(git rev-parse --show-toplevel)
SRC="$REPO_ROOT/rubik-solver/min2phaseCXX"
WRAPPER="$REPO_ROOT/rubik-solver/min2phase_wrapper"
OUT_DIR="$REPO_ROOT/rubik-solver/min2phaseXCFramework"

if [ ! -d "$SRC/include/min2phase" ]; then
    echo "ERROR: min2phaseCXX submodule not initialised. Run: git submodule update --init"
    exit 1
fi

# build_slice ARCH SDK BUILD_SUFFIX VERSION_FLAG
# Builds min2phase from CMake, then compiles + merges the C wrapper into the .a
# BUILD_SUFFIX distinguishes device vs simulator build dirs for the same arch.
# VERSION_FLAG is the clang deployment-target flag (-mios-version-min or -mios-simulator-version-min).
build_slice() {
    local ARCH=$1 SDK=$2 SUFFIX=$3 VERSION_FLAG=$4
    local BUILD_DIR="$SRC/build-$SUFFIX"
    local SYSROOT CLANG CLANGXX
    SYSROOT=$(xcrun --sdk "$SDK" --show-sdk-path)
    CLANG=$(xcrun --sdk "$SDK" -f clang)
    CLANGXX=$(xcrun --sdk "$SDK" -f clang++)

    # Build the main library (try with BUILD_SERVER=OFF first, fallback without)
    cmake -S "$SRC" -B "$BUILD_DIR" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_SYSROOT="$SYSROOT" \
        -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
        -DCMAKE_C_COMPILER="$CLANG" \
        -DCMAKE_CXX_COMPILER="$CLANGXX" \
        -DBUILD_SERVER=OFF 2>/dev/null || \
    cmake -S "$SRC" -B "$BUILD_DIR" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_SYSROOT="$SYSROOT" \
        -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
        -DCMAKE_C_COMPILER="$CLANG" \
        -DCMAKE_CXX_COMPILER="$CLANGXX"
    cmake --build "$BUILD_DIR" --target min2phase

    # Find the built static library
    local LIB
    LIB=$(find "$BUILD_DIR" -name "libmin2phase.a" | head -1)
    if [ -z "$LIB" ]; then
        echo "ERROR: libmin2phase.a not found in $BUILD_DIR"
        exit 1
    fi

    # Compile the C wrapper and add it into the library
    "$CLANGXX" -c \
        -arch "$ARCH" \
        -isysroot "$SYSROOT" \
        "$VERSION_FLAG" \
        -std=c++14 \
        -I"$SRC/include" \
        -I"$WRAPPER/include" \
        "$WRAPPER/src/min2phase_c.cpp" \
        -o "$BUILD_DIR/min2phase_c.o"
    ar -q "$LIB" "$BUILD_DIR/min2phase_c.o"
    ranlib "$LIB"
}

echo "Building arm64 device slice..."
build_slice arm64 iphoneos arm64-device -mios-version-min=15.0

echo "Building arm64 simulator slice..."
build_slice arm64 iphonesimulator arm64-sim -mios-simulator-version-min=15.0

DEVICE_LIB=$(find "$SRC/build-arm64-device" -name "libmin2phase.a" | head -1)
SIM_LIB=$(find "$SRC/build-arm64-sim" -name "libmin2phase.a" | head -1)

# Single-arch arm64 simulator → xcodebuild names it "ios-arm64-simulator"
# matching the libraryPath in build.gradle.kts iosSimulatorArm64 cinterop.
echo "Creating XCFramework..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
xcodebuild -create-xcframework \
    -library "$DEVICE_LIB" \
    -headers "$SRC/include" \
    -library "$SIM_LIB" \
    -headers "$SRC/include" \
    -output "$OUT_DIR/min2phase.xcframework"

echo "Done: $OUT_DIR/min2phase.xcframework"
echo "XCFramework contents:"
ls "$OUT_DIR/min2phase.xcframework/"
