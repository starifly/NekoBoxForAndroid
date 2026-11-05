#!/bin/bash
# Build the hev-socks5-tunnel JNI library (libhev-socks5-tunnel.so) for all
# Android ABIs and place the artifacts under app/src/main/jniLibs.
#
# Source: the hev-socks5-tunnel git submodule at the repository root
# (initialized automatically when missing). The app Gradle build invokes this
# script via the "buildHevTun" task whenever the .so artifacts are absent.
#
# The JNI entry points (TProxyStartService/TProxyStopService/TProxyIsRunning/
# TProxyGetStats) are bound at library load time via RegisterNatives to the
# class named by -DPKGNAME/-DCLSNAME below; keep them in sync with
# moe.matsuri.nb4a.hevtun.HevTunNative.
#
# Usage:
#   ./buildScript/compile-hevtun.sh
#
# Optional environment overrides:
#   HEV_SRC     path to a hev-socks5-tunnel checkout
#               (default: <repo root>/hev-socks5-tunnel submodule)
#   HEV_VERSION tag used when initializing the submodule is not possible
#               (default: 2.17.1)
set -o errexit
set -o pipefail
set -o nounset

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/init/env_ndk.sh"

HEV_VERSION="${HEV_VERSION:-2.17.1}"
HEV_SRC="${HEV_SRC:-$REPO_DIR/hev-socks5-tunnel}"
ABIS="${HEV_ABIS:-armeabi-v7a arm64-v8a x86 x86_64}"
OUT_DIR="$REPO_DIR/app/src/main/jniLibs"
BUILD_DIR="$REPO_DIR/.hev-build"

if [ ! -f "$HEV_SRC/Android.mk" ]; then
    if [ -e "$REPO_DIR/.git" ] || [ -d "$REPO_DIR/.git" ]; then
        echo ">> initializing hev-socks5-tunnel submodule"
        git -C "$REPO_DIR" submodule update --init --recursive hev-socks5-tunnel
    else
        echo ">> cloning hev-socks5-tunnel $HEV_VERSION into $HEV_SRC"
        mkdir -p "$(dirname "$HEV_SRC")"
        git clone --branch "$HEV_VERSION" --depth 1 --recursive \
            https://github.com/heiher/hev-socks5-tunnel "$HEV_SRC"
    fi
fi

# Persistent obj/libs dirs so ndk-build stays incremental between runs.
mkdir -p "$BUILD_DIR"
pushd "$BUILD_DIR" > /dev/null

if [ ! -e jni/hev-socks5-tunnel ]; then
    mkdir -p jni
    ln -sfn "$HEV_SRC" jni/hev-socks5-tunnel
    echo 'include $(call all-subdir-makefiles)' > jni/Android.mk
fi

"$ANDROID_NDK_HOME/ndk-build" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=jni/Android.mk \
    "APP_ABI=$ABIS" \
    APP_PLATFORM=android-21 \
    "APP_CFLAGS=-O3 -DPKGNAME=moe/matsuri/nb4a/hevtun -DCLSNAME=HevTunNative" \
    "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu" \
    NDK_LIBS_OUT="$BUILD_DIR/libs" \
    NDK_OUT="$BUILD_DIR/obj"

popd > /dev/null

for abi in $ABIS; do
    mkdir -p "$OUT_DIR/$abi"
    cp -f "$BUILD_DIR/libs/$abi/libhev-socks5-tunnel.so" "$OUT_DIR/$abi/"
    echo ">> install $OUT_DIR/$abi/libhev-socks5-tunnel.so"
done
