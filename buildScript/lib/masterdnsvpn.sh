#!/bin/bash
# Build the (forked) MasterDnsVPN client for all Android ABIs and install them as
# bundled native executables (app/executableSo/<abi>/libmasterdnsvpn.so).
#
# Uses the hawkff fork branch that adds the Android FD_CONTROL_UNIX_SOCKET protect hook,
# so the client's upstream resolver sockets are protected from the VPN via libcore's
# protect_path server. PluginManager.initNativeInternal resolves
# "masterdnsvpn-plugin" -> libmasterdnsvpn.so.
#
# Requires Go 1.25+ on PATH (MasterDnsVPN's go.mod is go 1.25). libcore uses its own Go;
# in CI this build job gets its own setup-go.
#
# Usage: ./run lib masterdnsvpn
set -e
set -o pipefail

source "buildScript/init/env.sh"

if [ -z "$ANDROID_NDK_HOME" ]; then
  echo "Error: ANDROID_NDK_HOME is not set (NDK required to cross-compile MasterDnsVPN)." >&2
  exit 1
fi

MDVPN_REPO="${MDVPN_REPO:-https://github.com/hawkff/MasterDnsVPN.git}"
MDVPN_REF="${MDVPN_REF:-android-vpnservice-protect-hook}"

DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-arm64/bin"
fi
if [ ! -d "$DEPS" ]; then
  echo "Error: NDK LLVM toolchain not found under $ANDROID_NDK_HOME." >&2
  exit 1
fi

WORK="$(pwd)/.masterdnsvpn-build"
OUT="$(pwd)/app/executableSo"

need_clone=1
if [ -d "$WORK/.git" ]; then
  if git -C "$WORK" fetch --depth 1 origin "$MDVPN_REF" \
     && git -C "$WORK" checkout -q FETCH_HEAD; then
    need_clone=0
  else
    echo ">> existing $WORK is unusable; re-cloning"
  fi
fi
if [ "$need_clone" -eq 1 ]; then
  rm -rf "$WORK"
  git clone --depth 1 --branch "$MDVPN_REF" "$MDVPN_REPO" "$WORK"
fi

pushd "$WORK" >/dev/null

build_abi() {
  local abi="$1" goarch="$2" cc="$3" goarm="$4"
  echo ">> building libmasterdnsvpn.so for $abi"
  mkdir -p "$OUT/$abi"
  env GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} CGO_ENABLED=1 \
    CC="$DEPS/$cc" \
    go build -trimpath -ldflags='-s -w' \
      -o "$OUT/$abi/libmasterdnsvpn.so" ./cmd/client
}

build_abi "arm64-v8a"   "arm64" "aarch64-linux-android21-clang"
build_abi "armeabi-v7a" "arm"   "armv7a-linux-androideabi21-clang" "7"
build_abi "x86"         "386"   "i686-linux-android21-clang"
build_abi "x86_64"      "amd64" "x86_64-linux-android21-clang"

popd >/dev/null

echo ">> installed MasterDnsVPN binaries:"
ls -la "$OUT"/*/libmasterdnsvpn.so
