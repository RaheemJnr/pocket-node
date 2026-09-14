#!/usr/bin/env bash
# Build script for CKB Light Client static library (iOS)
# Builds for device (aarch64-apple-ios) and simulator (aarch64-apple-ios-sim),
# generates the UniFFI Swift bindings, then packages both slices into an
# xcframework with the generated C header + modulemap.
#
# Built with --features uniffi-bridge so the `ffi` module (and the UniFFI
# scaffolding) is compiled in. The jni_bridge module is cfg-gated to
# target_os = "android" and is never built here.

set -euo pipefail
cd "$(dirname "$0")"

echo "======================================"
echo "CKB Light Client - iOS Build"
echo "======================================"
echo ""

RUST_TARGETS=("aarch64-apple-ios" "aarch64-apple-ios-sim")
# The mobile slices only need the scaffolding; the bindgen CLI (clap,
# uniffi_bindgen) is host-only so it never links into the shipped library.
CARGO_FEATURES="uniffi-bridge"
HOST_FEATURES="uniffi-cli"

SWIFT_PACKAGE_DIR="../../ios/Packages/CkbLightClient"
GENERATED_DIR="$SWIFT_PACKAGE_DIR/Generated"
SOURCES_DIR="$SWIFT_PACKAGE_DIR/Sources/CkbLightClient"
INCLUDE_DIR="$GENERATED_DIR/include"
XCFRAMEWORK_PATH="$SWIFT_PACKAGE_DIR/CkbLightClientFFI.xcframework"

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
        --features "$CARGO_FEATURES" \
        --package ckb-light-client-lib

    echo "Completed $TARGET build."
done

DEVICE_LIB="target/aarch64-apple-ios/release/libckb_light_client_lib.a"
SIM_LIB="target/aarch64-apple-ios-sim/release/libckb_light_client_lib.a"

echo ""
echo "======================================"
echo "Generating Swift bindings"
echo "======================================"

# uniffi-bindgen --library mode reads the exported metadata out of a compiled
# library. The host cdylib is the cheapest one to point it at.
cargo build --release \
    --features "$HOST_FEATURES" \
    --package ckb-light-client-lib

HOST_DYLIB="target/release/libckb_light_client_lib.dylib"

rm -rf "$GENERATED_DIR"
mkdir -p "$GENERATED_DIR"

cargo run --release \
    --features "$HOST_FEATURES" \
    --bin uniffi-bindgen -- \
    generate \
    --library "$HOST_DYLIB" \
    --language swift \
    --out-dir "$GENERATED_DIR"

# The .swift file is checked in as the package source; the C header and
# modulemap become the xcframework's headers.
mkdir -p "$SOURCES_DIR" "$INCLUDE_DIR"
# Replace only the generated file by name; any hand-written Swift alongside it
# in the package sources must survive a regeneration.
rm -f "$SOURCES_DIR/CkbLightClient.swift"
mv "$GENERATED_DIR"/*.swift "$SOURCES_DIR/"
mv "$GENERATED_DIR"/*.h "$INCLUDE_DIR/"
# uniffi emits <namespace>FFI.modulemap (module CkbLightClientFFI, which is what
# the generated Swift imports). The xcframework needs it named module.modulemap
# so each slice's headers dir is importable as a clang module.
mv "$GENERATED_DIR"/*.modulemap "$INCLUDE_DIR/module.modulemap"

echo "Bindings written to $SOURCES_DIR and $INCLUDE_DIR"

mkdir -p "$SWIFT_PACKAGE_DIR"

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
    -library "$DEVICE_LIB" -headers "$INCLUDE_DIR" \
    -library "$SIM_LIB" -headers "$INCLUDE_DIR" \
    -output "$XCFRAMEWORK_PATH"

echo ""
echo "======================================"
echo "All builds completed successfully!"
echo "======================================"
echo ""
echo "Sizes:"
du -h "$DEVICE_LIB" "$SIM_LIB"
du -sh "$XCFRAMEWORK_PATH"
