#!/bin/bash
# Build min2phaseCXX as an XCFramework for iOS arm64 + simulator (arm64 + x86_64).
# Run this script on macOS with Xcode 16+ installed.
# Output: rubik-solver/min2phaseXCFramework/min2phase.xcframework
set -e

REPO_ROOT=$(git rev-parse --show-toplevel)
SRC="$REPO_ROOT/rubik-solver/min2phaseCXX"
OUT_DIR="$REPO_ROOT/rubik-solver/min2phaseXCFramework"

if [ ! -d "$SRC/include/min2phase" ]; then
    echo "ERROR: min2phaseCXX submodule not initialised. Run: git submodule update --init"
    exit 1
fi

build_slice() {
    local ARCH=$1 SDK=$2
    local BUILD_DIR="$SRC/build-$ARCH"
    cmake -S "$SRC" -B "$BUILD_DIR" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$SDK" --show-sdk-path)" \
        -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
        -DCMAKE_C_COMPILER="$(xcrun --sdk "$SDK" -f clang)" \
        -DCMAKE_CXX_COMPILER="$(xcrun --sdk "$SDK" -f clang++)" \
        -DBUILD_SERVER=OFF 2>/dev/null || \
    cmake -S "$SRC" -B "$BUILD_DIR" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$SDK" --show-sdk-path)" \
        -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
        -DCMAKE_C_COMPILER="$(xcrun --sdk "$SDK" -f clang)" \
        -DCMAKE_CXX_COMPILER="$(xcrun --sdk "$SDK" -f clang++)"
    # Also add the C wrapper source if not already in CMakeLists.txt
    cmake --build "$BUILD_DIR" --target min2phase
    # Compile and archive the C wrapper into the library
    "$(xcrun --sdk "$SDK" -f clang++)" -c \
        -arch "$ARCH" \
        -isysroot "$(xcrun --sdk "$SDK" --show-sdk-path)" \
        -mios-version-min=15.0 \
        -std=c++14 \
        -I"$SRC/include" \
        "$SRC/src/min2phase_c.cpp" \
        -o "$BUILD_DIR/min2phase_c.o"
    ar rcs "$BUILD_DIR/libmin2phase.a" "$BUILD_DIR/min2phase_c.o"
    # Merge with existing library
    libtool -static -o "$BUILD_DIR/libmin2phase_full.a" \
        "$BUILD_DIR/libmin2phase.a" \
        "$(find "$BUILD_DIR" -name "libmin2phase.a" -not -path "$BUILD_DIR/libmin2phase.a" | head -1)"
    mv "$BUILD_DIR/libmin2phase_full.a" "$BUILD_DIR/libmin2phase.a"
}

echo "Building arm64 device slice..."
build_slice arm64 iphoneos

echo "Building x86_64 simulator slice..."
build_slice x86_64 iphonesimulator

echo "Building arm64 simulator slice..."
ARM64_SIM_DIR="$SRC/build-arm64-sim"
cmake -S "$SRC" -B "$ARM64_SIM_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$(xcrun --sdk iphonesimulator --show-sdk-path)" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
    -DCMAKE_C_COMPILER="$(xcrun --sdk iphonesimulator -f clang)" \
    -DCMAKE_CXX_COMPILER="$(xcrun --sdk iphonesimulator -f clang++)" \
    -DBUILD_SERVER=OFF 2>/dev/null || true
cmake --build "$ARM64_SIM_DIR" --target min2phase
"$(xcrun --sdk iphonesimulator -f clang++)" -c \
    -arch arm64 \
    -isysroot "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
    -mios-simulator-version-min=15.0 \
    -std=c++14 \
    -I"$SRC/include" \
    "$SRC/src/min2phase_c.cpp" \
    -o "$ARM64_SIM_DIR/min2phase_c.o"
ar rcs "$ARM64_SIM_DIR/libmin2phase.a" "$ARM64_SIM_DIR/min2phase_c.o"

echo "Creating fat simulator library..."
mkdir -p "$SRC/build-sim-fat"
lipo -create \
    "$SRC/build-x86_64/libmin2phase.a" \
    "$ARM64_SIM_DIR/libmin2phase.a" \
    -output "$SRC/build-sim-fat/libmin2phase.a"

echo "Creating XCFramework..."
mkdir -p "$OUT_DIR"
xcodebuild -create-xcframework \
    -library "$SRC/build-arm64/libmin2phase.a" \
    -headers "$SRC/include" \
    -library "$SRC/build-sim-fat/libmin2phase.a" \
    -headers "$SRC/include" \
    -output "$OUT_DIR/min2phase.xcframework"

echo "Done: $OUT_DIR/min2phase.xcframework"
