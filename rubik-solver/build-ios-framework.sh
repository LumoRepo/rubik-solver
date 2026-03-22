#!/bin/bash
# Build min2phaseCXX + C wrapper as an XCFramework for iOS arm64 + simulator (arm64 + x86_64).
# Run this script on macOS with Xcode 16+ installed.
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

# build_slice ARCH SDK
# Builds min2phase from CMake, then compiles + merges the C wrapper into the .a
build_slice() {
    local ARCH=$1 SDK=$2
    local BUILD_DIR="$SRC/build-$ARCH"
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

    # Compile the C wrapper and add it into the library
    "$CLANGXX" -c \
        -arch "$ARCH" \
        -isysroot "$SYSROOT" \
        -mios-version-min=15.0 \
        -std=c++14 \
        -I"$SRC/include" \
        -I"$WRAPPER/include" \
        "$WRAPPER/src/min2phase_c.cpp" \
        -o "$BUILD_DIR/min2phase_c.o"
    ar -q "$LIB" "$BUILD_DIR/min2phase_c.o"
    ranlib "$LIB"
}

echo "Building arm64 device slice..."
build_slice arm64 iphoneos

echo "Building x86_64 simulator slice..."
build_slice x86_64 iphonesimulator

echo "Building arm64 simulator slice..."
ARM64_SIM_DIR="$SRC/build-arm64-sim"
SYSROOT_SIM=$(xcrun --sdk iphonesimulator --show-sdk-path)
CLANGXX_SIM=$(xcrun --sdk iphonesimulator -f clang++)

cmake -S "$SRC" -B "$ARM64_SIM_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$SYSROOT_SIM" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
    -DCMAKE_C_COMPILER="$(xcrun --sdk iphonesimulator -f clang)" \
    -DCMAKE_CXX_COMPILER="$CLANGXX_SIM" \
    -DBUILD_SERVER=OFF 2>/dev/null || \
cmake -S "$SRC" -B "$ARM64_SIM_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$SYSROOT_SIM" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
    -DCMAKE_C_COMPILER="$(xcrun --sdk iphonesimulator -f clang)" \
    -DCMAKE_CXX_COMPILER="$CLANGXX_SIM"
cmake --build "$ARM64_SIM_DIR" --target min2phase

ARM64_SIM_LIB=$(find "$ARM64_SIM_DIR" -name "libmin2phase.a" | head -1)
"$CLANGXX_SIM" -c \
    -arch arm64 \
    -isysroot "$SYSROOT_SIM" \
    -mios-simulator-version-min=15.0 \
    -std=c++14 \
    -I"$SRC/include" \
    -I"$WRAPPER/include" \
    "$WRAPPER/src/min2phase_c.cpp" \
    -o "$ARM64_SIM_DIR/min2phase_c.o"
ar -q "$ARM64_SIM_LIB" "$ARM64_SIM_DIR/min2phase_c.o"
ranlib "$ARM64_SIM_LIB"

echo "Creating fat simulator library..."
FAT_SIM_DIR="$SRC/build-sim-fat"
mkdir -p "$FAT_SIM_DIR"
lipo -create \
    "$(find "$SRC/build-x86_64" -name "libmin2phase.a" | head -1)" \
    "$ARM64_SIM_LIB" \
    -output "$FAT_SIM_DIR/libmin2phase.a"

echo "Creating XCFramework..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
xcodebuild -create-xcframework \
    -library "$(find "$SRC/build-arm64" -name "libmin2phase.a" | head -1)" \
    -headers "$SRC/include" \
    -library "$FAT_SIM_DIR/libmin2phase.a" \
    -headers "$SRC/include" \
    -output "$OUT_DIR/min2phase.xcframework"

echo "Done: $OUT_DIR/min2phase.xcframework"
