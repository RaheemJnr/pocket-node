#!/usr/bin/env bash
# Build script for CKB Light Client JNI (Android)
# Builds for arm64-v8a, armeabi-v7a, and x86_64

set -e

echo "======================================"
echo "CKB Light Client - Android JNI Build"
echo "======================================"
echo ""

# Try to find Android NDK if not already set
if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ANDROID_NDK_HOME not set or invalid, searching for NDK..."
    for ndk_path in \
        "$ANDROID_HOME/ndk/"* \
        "$HOME/Android/Sdk/ndk/"* \
        "$HOME/Library/Android/sdk/ndk/"* \
        "/usr/local/android-ndk"* \
        "$HOME/soft/ndk/"* \
        "$HOME/android-ndk"*; do
        if [ -d "$ndk_path/toolchains/llvm/prebuilt" ]; then
            export ANDROID_NDK_HOME="$ndk_path"
            echo "Found NDK at: $ANDROID_NDK_HOME"
            break
        fi
    done
fi

if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: Android NDK not found!"
    exit 1
fi

# Try a list of known toolchain layouts first (fast, deterministic). Fall back
# to a recursive find if none match. The recursive find handles symlinks and
# nested-extract layouts; the explicit paths handle the common case quickly
# and survive when the NDK is laid out under a nested wrapper directory
# (some hosted-toolcache extractors do this).
CLANG_PATH=""
for candidate in \
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" \
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang" \
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-arm64/bin/clang" \
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe" \
    ; do
    if [ -x "$candidate" ]; then
        CLANG_PATH="$candidate"
        break
    fi
done

if [ -z "$CLANG_PATH" ]; then
    # Fallback: recursive search. Drop -type f so symlinks count, and resolve
    # nested wrapper directories that some extractors create.
    CLANG_PATH=$(find -L "$ANDROID_NDK_HOME" -name 'clang' -executable 2>/dev/null | head -n 1)
fi

if [ -z "$CLANG_PATH" ]; then
    echo "ERROR: clang not found in NDK"
    echo "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
    echo "Top-level contents:"
    ls -la "$ANDROID_NDK_HOME" 2>/dev/null || true
    echo "Toolchains layout (if present):"
    ls -la "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/" 2>/dev/null || true
    exit 1
fi

NDK_BIN=$(dirname "$CLANG_PATH")
HOST_DIR=$(dirname "$NDK_BIN")
NDK_HOST=$(basename "$HOST_DIR")
SYSROOT="$HOST_DIR/sysroot"

export ANDROID_API_LEVEL=21
echo "Using NDK: $ANDROID_NDK_HOME"
echo "NDK Host: $NDK_HOST"

# Multi-ABI setup (arm64 + armv7 only — x86_64 is emulator-only and excluded from APK)
RUST_TARGETS=("aarch64-linux-android" "armv7-linux-androideabi")
ANDROID_ARCHS=("arm64-v8a" "armeabi-v7a")
if [ "${BUILD_X86_64:-0}" = "1" ]; then
    RUST_TARGETS+=("x86_64-linux-android")
    ANDROID_ARCHS+=("x86_64")
    echo "BUILD_X86_64=1 → also building x86_64-linux-android"
fi

# Environment common flags
ROCKSDB_DISABLE_FLAGS="-DROCKSDB_NO_DYNAMIC_EXTENSION -DROCKSDB_LITE"
ROCKSDB_DISABLE_FLAGS="$ROCKSDB_DISABLE_FLAGS -Dposix_madvise=madvise -Dfread_unlocked=fread"
ROCKSDB_DISABLE_FLAGS="$ROCKSDB_DISABLE_FLAGS -DPOSIX_MADV_NORMAL=MADV_NORMAL -DPOSIX_MADV_RANDOM=MADV_RANDOM"
ROCKSDB_DISABLE_FLAGS="$ROCKSDB_DISABLE_FLAGS -DPOSIX_MADV_SEQUENTIAL=MADV_SEQUENTIAL -DPOSIX_MADV_WILLNEED=MADV_WILLNEED -DPOSIX_MADV_DONTNEED=MADV_DONTNEED"

export ROCKSDB_DISABLE_FALLOCATE=1
export ROCKSDB_DISABLE_SYNC_FILE_RANGE=1
export ROCKSDB_DISABLE_PTHREAD_MUTEX_ADAPTIVE_NP=1
export ROCKSDB_DISABLE_SCHED_GETCPU=1
export ROCKSDB_DISABLE_AUXV=1

# Compile stubs if exists
STUBS_BASE_DIR="$(pwd)/target/android-stubs"
mkdir -p "$STUBS_BASE_DIR"

for i in "${!RUST_TARGETS[@]}"; do
    TARGET="${RUST_TARGETS[$i]}"
    ARCH="${ANDROID_ARCHS[$i]}"
    
    echo ""
    echo "======================================"
    echo "Building for $ARCH ($TARGET)"
    echo "======================================"
    
    rustup target add $TARGET
    
    # Configure toolchain for this target
    CLANG_TARGET=$TARGET
    if [ "$TARGET" == "armv7-linux-androideabi" ]; then
        CLANG_TARGET="armv7a-linux-androideabi"
    fi
    
    # Target-specific compiler paths
    CLANG_PATH="$NDK_BIN/${CLANG_TARGET}${ANDROID_API_LEVEL}-clang"
    CLANGXX_PATH="$NDK_BIN/${CLANG_TARGET}${ANDROID_API_LEVEL}-clang++"
    AR_PATH="$NDK_BIN/llvm-ar"
    RANLIB_PATH="$NDK_BIN/llvm-ranlib"

    # Export target-specific variables for Cargo
    # We use hardcoded mapping to avoid incompatible bash substitutions
    if [ "$TARGET" == "aarch64-linux-android" ]; then
        export CC_aarch64_linux_android="$CLANG_PATH"
        export CXX_aarch64_linux_android="$CLANGXX_PATH"
        export AR_aarch64_linux_android="$AR_PATH"
        export RANLIB_aarch64_linux_android="$RANLIB_PATH"
        export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG_PATH"
    elif [ "$TARGET" == "armv7-linux-androideabi" ]; then
        export CC_armv7_linux_androideabi="$CLANG_PATH"
        export CXX_armv7_linux_androideabi="$CLANGXX_PATH"
        export AR_armv7_linux_androideabi="$AR_PATH"
        export RANLIB_armv7_linux_androideabi="$RANLIB_PATH"
        export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$CLANG_PATH"
    elif [ "$TARGET" == "x86_64-linux-android" ]; then
        export CC_x86_64_linux_android="$CLANG_PATH"
        export CXX_x86_64_linux_android="$CLANGXX_PATH"
        export AR_x86_64_linux_android="$AR_PATH"
        export RANLIB_x86_64_linux_android="$RANLIB_PATH"
        export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CLANG_PATH"
    fi
    
    # DO NOT export global CC/CXX as it breaks host build scripts
    
    export CXXFLAGS="-fPIC -D__ANDROID_API__=${ANDROID_API_LEVEL} $ROCKSDB_DISABLE_FLAGS"
    export CFLAGS="-fPIC -D__ANDROID_API__=${ANDROID_API_LEVEL}"
    export BINDGEN_EXTRA_CLANG_ARGS="--sysroot=$SYSROOT --target=$TARGET -D__ANDROID_API__=${ANDROID_API_LEVEL}"
    
    # Build stubs for this arch
    if [ -f "android-stubs.c" ]; then
        STUBS_DIR="$STUBS_BASE_DIR/$ARCH"
        mkdir -p "$STUBS_DIR"
        $CLANG_PATH -c android-stubs.c -o "$STUBS_DIR/android-stubs.o"
        $AR_PATH rcs "$STUBS_DIR/libandroid_stubs.a" "$STUBS_DIR/android-stubs.o"
        export RUSTFLAGS="-L $STUBS_DIR -l static=android_stubs"
    fi

    # Run build
    cargo build --release \
        --target $TARGET \
        --package ckb-light-client-lib \
        --features jni-bridge,portable

    # Output paths
    SO_FILE="target/$TARGET/release/libckb_light_client_lib.so"
    MAIN_JNILIBS="../../android/app/src/main/jniLibs/$ARCH"
    
    mkdir -p "$MAIN_JNILIBS"
    cp "$SO_FILE" "$MAIN_JNILIBS/"

    # Strip debug symbols to reduce .so size (~30-40% smaller)
    LLVM_STRIP="$NDK_BIN/llvm-strip"
    if [ -x "$LLVM_STRIP" ]; then
        "$LLVM_STRIP" --strip-all "$MAIN_JNILIBS/libckb_light_client_lib.so"
        echo "Stripped libckb_light_client_lib.so"
    fi

    # Copy libc++_shared.so
    NDK_TARGET_DIR=$TARGET
    if [ "$TARGET" == "armv7-linux-androideabi" ]; then
        NDK_TARGET_DIR="arm-linux-androideabi"
    fi
    LIBCXX=$(find "$ANDROID_NDK_HOME" -name "libc++_shared.so" | grep "$NDK_TARGET_DIR" | head -n 1)
    if [ -n "$LIBCXX" ]; then
        cp "$LIBCXX" "$MAIN_JNILIBS/"
        if [ -x "$LLVM_STRIP" ]; then
            "$LLVM_STRIP" --strip-all "$MAIN_JNILIBS/libc++_shared.so"
            echo "Stripped libc++_shared.so"
        fi
    fi
    
    echo "Completed $ARCH build."
    
    # Cleanup target-specific vars for next iteration
    unset CC_aarch64_linux_android CXX_aarch64_linux_android AR_aarch64_linux_android RANLIB_aarch64_linux_android CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
    unset CC_armv7_linux_androideabi CXX_armv7_linux_androideabi AR_armv7_linux_androideabi RANLIB_armv7_linux_androideabi CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER
    unset CC_x86_64_linux_android CXX_x86_64_linux_android AR_x86_64_linux_android RANLIB_x86_64_linux_android CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER
    unset RUSTFLAGS
done

echo ""
echo "======================================"
echo "All builds completed successfully!"
echo "======================================"
