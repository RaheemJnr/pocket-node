#!/usr/bin/env bash
# Build script for CKB Light Client static library (iOS)
# Builds for device (aarch64-apple-ios) and simulator (aarch64-apple-ios-sim),
# then packages both into an xcframework.
#
# Default features only (no jni-bridge, no android_logger) — the jni_bridge
# module is cfg-gated to target_os = "android" and is never built here.

set -euo pipefail
cd "$(dirname "$0")"

for tool in rustup cargo xcodebuild; do
    command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool not found on PATH"; exit 1; }
done

echo "======================================"
echo "CKB Light Client - iOS Build"
echo "======================================"
echo ""

# Apple Silicon only (design D3 in docs/IOS_M1_DESIGN.md): no x86_64 simulator
# slice. Add "x86_64-apple-ios" here if an Intel Mac ever needs to run the sim.
RUST_TARGETS=("aarch64-apple-ios" "aarch64-apple-ios-sim")

export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-15.0}"
echo "IPHONEOS_DEPLOYMENT_TARGET=$IPHONEOS_DEPLOYMENT_TARGET"

for TARGET in "${RUST_TARGETS[@]}"; do
    echo ""
    echo "======================================"
    echo "Building for $TARGET"
    echo "======================================"

    rustup target add "$TARGET"

    cargo build --release \
        --target "$TARGET" \
        --package ckb-light-client-lib

    echo "Completed $TARGET build."
done

DEVICE_LIB="target/aarch64-apple-ios/release/libckb_light_client_lib.a"
SIM_LIB="target/aarch64-apple-ios-sim/release/libckb_light_client_lib.a"

XCFRAMEWORK_DIR="../../ios/Packages/CkbLightClient"
XCFRAMEWORK_PATH="$XCFRAMEWORK_DIR/CkbLightClientFFI.xcframework"

mkdir -p "$XCFRAMEWORK_DIR"

if [ -d "$XCFRAMEWORK_PATH" ]; then
    echo ""
    echo "Removing previous xcframework at $XCFRAMEWORK_PATH"
    rm -rf "$XCFRAMEWORK_PATH"
fi

echo ""
echo "======================================"
echo "Creating xcframework"
echo "======================================"

xcodebuild -create-xcframework \
    -library "$DEVICE_LIB" \
    -library "$SIM_LIB" \
    -output "$XCFRAMEWORK_PATH"

echo ""
echo "======================================"
echo "All builds completed successfully!"
echo "======================================"
echo ""
echo "Sizes:"
du -h "$DEVICE_LIB" "$SIM_LIB"
du -sh "$XCFRAMEWORK_PATH"
