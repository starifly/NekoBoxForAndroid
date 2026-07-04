#!/bin/bash
# Build the olcRTC CLIENT as an Android native executable for all ABIs and install
# them as bundled sidecars (app/executableSo/<abi>/libolcrtc.so).
#
# olcRTC (github.com/openlibrecommunity/olcrtc) is an encrypted TCP-over-WebRTC
# tunnel. We bundle ONLY the client (cnc role): it opens a loopback SOCKS5 listener
# that a sing-box `socks` outbound dials, and tunnels traffic out over a common meet
# service. The server side is deployed separately and is never shipped in the app.
#
# We ship it as a child-process sidecar (like mieru/naive/masterdnsvpn) rather than a
# gomobile .aar: gomobile permits only ONE binding per app (every bound .aar bundles
# go.Seq + libgojni.so, which collide with libcore's), and a separate process also
# keeps olcRTC's heavy dependency graph (pion/webrtc, livekit, kcp, grpc, ...) out of
# libcore's pinned sing-box module graph entirely.
#
# A tiny wrapper main (buildScript/lib/olcrtc-src) imports olcRTC's `mobile` package at
# a pinned commit, wires socket protection to libcore's protect_path unix socket, and
# parses CLI flags. We clone the pinned upstream commit and point the wrapper module at
# it via a replace directive so the build is fully reproducible and offline-stable.
#
# OLCRTC_REPO/OLCRTC_COMMIT default to the hawkff fork at a commit that carries the
# protected pion net (internal/protect/pionnet.go + the jitsi SetNet hook, merged
# upstream in openlibrecommunity/olcrtc#111) plus Jitsi ICE-service URL normalization.
# Pinning at/after both keeps the media path off the tun and prevents malformed
# service-discovery entries from failing peer-connection setup.
#
# Usage: ./run lib olcrtc
set -e
set -o pipefail

source "buildScript/init/env.sh"

if [ -z "$ANDROID_NDK_HOME" ]; then
  echo "Error: ANDROID_NDK_HOME is not set (NDK required to cross-compile olcRTC)." >&2
  exit 1
fi

OLCRTC_REPO="${OLCRTC_REPO:-https://github.com/hawkff/olcrtc.git}"
OLCRTC_COMMIT="${OLCRTC_COMMIT:-08a25f26da88feddc9903513039fc71f3d0449dd}"

if ! command -v go >/dev/null 2>&1; then
  echo "Error: go not found on PATH (olcRTC needs Go 1.26+)." >&2
  exit 1
fi

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

SRC="$(pwd)/buildScript/lib/olcrtc-src"
WORK="$(pwd)/.olcrtc-build"
OUT="$(pwd)/app/executableSo"

# Clone the pinned olcRTC commit so the wrapper builds against an immutable tree.
need_clone=1
if [ -d "$WORK/.git" ]; then
  if git -C "$WORK" fetch --depth 1 origin "$OLCRTC_COMMIT" \
     && git -C "$WORK" checkout -q FETCH_HEAD; then
    need_clone=0
  else
    echo ">> existing $WORK is unusable; re-cloning"
  fi
fi
if [ "$need_clone" -eq 1 ]; then
  rm -rf "$WORK"
  git init -q "$WORK"
  git -C "$WORK" remote add origin "$OLCRTC_REPO"
  git -C "$WORK" fetch --depth 1 origin "$OLCRTC_COMMIT"
  git -C "$WORK" checkout -q FETCH_HEAD
fi

# Stage the wrapper in a scratch build dir and point it at the cloned olcRTC via a
# replace directive, so `go build` never has to resolve a pseudo-version online.
BUILD="$(pwd)/.olcrtc-wrapper"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cp "$SRC/main.go" "$SRC/go.mod" "$BUILD/"
( cd "$BUILD" && go mod edit -replace "github.com/openlibrecommunity/olcrtc=$WORK" && go mod tidy )

build_abi() {
  local abi="$1" goarch="$2" cc="$3" goarm="$4"
  echo ">> building libolcrtc.so for $abi"
  mkdir -p "$OUT/$abi"
  ( cd "$BUILD" && env GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} CGO_ENABLED=1 \
    CC="$DEPS/$cc" \
    go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' \
      -o "$OUT/$abi/libolcrtc.so" . )
}

build_abi "arm64-v8a"   "arm64" "aarch64-linux-android21-clang"
build_abi "armeabi-v7a" "arm"   "armv7a-linux-androideabi21-clang" "7"
build_abi "x86"         "386"   "i686-linux-android21-clang"
build_abi "x86_64"      "amd64" "x86_64-linux-android21-clang"

rm -rf "$BUILD"

echo ">> installed olcRTC client binaries:"
ls -la "$OUT"/*/libolcrtc.so
